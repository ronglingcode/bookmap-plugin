package com.bookmap.plugin.rong.orderwall;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import com.google.gson.*;
import com.bookmap.plugin.rong.*;
import com.bookmap.plugin.rong.patterns.*;
import com.bookmap.plugin.rong.pricelines.*;
import com.bookmap.plugin.rong.signal.*;

/** Behavioral comparison against compiled production classes. No broker, API, or network calls. */
public final class PatternStudy {
    static final long BASE = Instant.parse("2026-10-06T14:00:00Z").getEpochSecond() * 1_000_000_000L;
    static final PatternEvent.TimestampProvenance MARKET = PatternEvent.TimestampProvenance.MARKET;
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    interface Scenario { void run(Fixture f) throws Exception; }
    static final class Fixture implements AutoCloseable {
        final OrderBookState book = new OrderBookState();
        final List<BookmapPatternSignal> legacy = new ArrayList<>();
        final List<PatternEvent> events = new ArrayList<>();
        final List<TradingSignal> signals = new ArrayList<>();
        final List<OrderWallChangeEvent> changes = new CopyOnWriteArrayList<>();
        final List<String> diagnostics = new ArrayList<>();
        final BookmapPatternEngine old;
        final SignalCompositionPipeline canonical;
        final OrderWallChangeTracker wallChanges;
        CompositionUpdate latest;
        boolean changeRecording;
        long offset;
        Fixture() { this(5000); }
        Fixture(int displayFloor) {
            old = new BookmapPatternEngine("TEST", .01, () -> displayFloor, 97, book,
                    new PriceLineStore(), new PriceZoneStore(), type -> true, legacy::add);
            canonical = new SignalCompositionPipeline("TEST", .01, SignalComposerConfig.defaults(), u -> {
                latest = u; signals.addAll(u.signals);
            }, events::add, diagnostics::add, () -> 1000);
            // Production threshold policies and 500 ms wall-change delay are retained.
            wallChanges = new OrderWallChangeTracker("TEST", .01, () -> 5000, 5, .5, 500,
                    e -> { if (changeRecording) changes.add(e); }, bid -> true, () -> 101.0, () -> 99.0);
            for (int i=0; i<200; i++) {
                book.update(true, 9500-i, 500);
                wallChanges.onDepth(true, 9500-i, 500, BASE);
            }
            old.onTimestamp(BASE); old.markReady(); canonical.markReady(); canonical.onTimestamp(BASE, MARKET);
            wallChanges.markReady();
            bbo(9999, 10002, 0);
            trade(9900, 1, false, 0); trade(10100, 1, true, 0);
        }
        void depth(boolean bid, int price, int size, long ms) {
            offset=ms; wallChanges.onDepth(bid, price, size, BASE+ms*1_000_000L);
            book.update(bid, price, size);
            old.onDepth(bid, price, size, BASE+ms*1_000_000L);
            canonical.onDepth(bid, price, size, BASE+ms*1_000_000L, MARKET);
        }
        void trade(int price, int size, boolean buy, long ms) {
            offset=ms; wallChanges.onTrade(price, size, buy);
            old.onTrade(price, size, buy, BASE+ms*1_000_000L);
            canonical.onTrade(price, size, buy, BASE+ms*1_000_000L, MARKET);
        }
        void time(long ms) {
            offset=ms; old.onTimestamp(BASE+ms*1_000_000L);
            canonical.onTimestamp(BASE+ms*1_000_000L, MARKET);
        }
        void bbo(int bid, int ask, long ms) {
            offset=ms; old.onBbo(bid, 1, ask, 1, BASE+ms*1_000_000L);
            canonical.onBbo(bid, ask, BASE+ms*1_000_000L, MARKET);
        }
        void baseline(boolean bid, int price, int size) throws Exception {
            depth(bid, price, size, 2000); time(2500);
            Thread.sleep(560); changes.clear(); changeRecording=true;
        }
        JsonObject result(String name) throws Exception {
            Thread.sleep(560);
            JsonObject r=new JsonObject(); r.addProperty("scenario", name);
            JsonArray oldRows=new JsonArray();
            for(BookmapPatternSignal s:legacy) {
                JsonObject j=new JsonObject(); j.addProperty("type",s.getPatternType().name());
                j.addProperty("referenceSize",s.getReferenceWallPeakSize()); j.addProperty("score",s.getScore());
                j.addProperty("timeMs",(s.getEventTimeNs()-BASE)/1_000_000L); oldRows.add(j);
            }
            r.add("legacyPatterns",oldRows);
            JsonArray raw=new JsonArray();
            for(PatternEvent e:events) {
                JsonObject j=new JsonObject(); j.addProperty("type",e.type.name()); j.addProperty("size",e.size);
                j.addProperty("revision",e.revision); j.addProperty("meaning",e.meaning.name());
                j.addProperty("occurrenceMs",(e.eventTimeNs-BASE)/1_000_000L);
                j.addProperty("observedMs",(e.observedAtNs-BASE)/1_000_000L); raw.add(j);
            }
            r.add("observerEvents",raw);
            JsonArray composed=new JsonArray();
            for(TradingSignal s:signals) {
                JsonObject j=new JsonObject(); j.addProperty("type",s.trigger.type.name());
                j.addProperty("triggerSize",s.trigger.size); j.addProperty("revision",s.revision);
                j.addProperty("direction",s.direction.name()); j.addProperty("appliedThreshold",s.firstValidation.appliedTriggerThreshold);
                composed.add(j);
            }
            r.add("composerUpdates",composed);
            JsonArray delta=new JsonArray();
            for(OrderWallChangeEvent e:changes) {
                JsonObject j=new JsonObject(); j.addProperty("type",e.getType().name());
                j.addProperty("before",e.getPreviousSize()); j.addProperty("after",e.getCurrentSize());
                j.addProperty("tradeConsumption",e.isTradeConsumption());
                j.addProperty("retestFill",e.isQualifyingRetestFill()); j.addProperty("soundEligible",e.isActiveLiquidityAlert()); delta.add(j);
            }
            r.add("wallChanges",delta);
            r.addProperty("visibleLiquidityAlertCount",OrderWallChangePainter.visibleEvents(changes,System.currentTimeMillis(),true).size());
            JsonArray waiting=new JsonArray(); for(Direction d:latest.contexts.keySet()) waiting.add(d.name());
            r.add("waitingDirections",waiting);
            r.addProperty("inspection",canonical.inspectionText());
            return r;
        }
        public void close() { wallChanges.shutdown(); old.shutdown(); }
    }
    static JsonObject run(String name,Scenario scenario) throws Exception {
        try(Fixture f=new Fixture()) { scenario.run(f); return f.result(name); }
    }
    static JsonObject run(String name,int displayFloor,Scenario scenario) throws Exception {
        try(Fixture f=new Fixture(displayFloor)) { scenario.run(f); return f.result(name); }
    }
    public static void main(String[] args) throws Exception {
        JsonArray results=new JsonArray();
        results.add(run("partial_offer_consumption_without_break", f->{
            f.baseline(false,10000,8000); f.trade(10000,5000,true,2600);
            f.depth(false,10000,3000,2600); f.time(3200);
        }));
        results.add(run("offer_loss_with_only_ten_percent_opposite_aggressor", f->{
            f.baseline(false,10000,8000); f.trade(10000,800,false,2600);
            f.depth(false,10000,0,2600); f.trade(10001,1,true,2700); f.time(3200);
        }));
        results.add(run("confirmed_offer_breakout_without_bid_trigger", f->{
            f.baseline(false,10000,8000); f.trade(10000,8000,true,2600);
            f.depth(false,10000,0,2600); f.trade(10001,1,true,2700); f.time(3200);
        }));
        results.add(run("gradual_bid_consumption", f->{
            f.baseline(true,10000,8000); f.trade(10000,4000,false,2600); f.depth(true,10000,4000,2600);
            f.trade(10000,2000,false,2700); f.depth(true,10000,2000,2700);
            f.trade(10000,2000,false,2800); f.depth(true,10000,0,2800);
            f.trade(9999,1,false,2900); f.time(3400);
        }));
        results.add(run("offer_step_down_without_bid_trigger", f->{
            f.bbo(9990,10020,100); f.baseline(false,10010,8000);
            f.depth(false,10009,6000,2600); f.time(3100); f.time(3600);
        }));
        results.add(run("bid_reappear_and_step_same_interaction", f->{
            f.baseline(true,10000,8000); f.trade(10000,8000,false,2600);
            f.depth(true,10000,0,2600); f.time(3100);
            f.depth(true,10001,6000,3200); f.time(3700); f.time(4200);
        }));
        results.add(run("step_wall_disappears_before_delayed_revision", f->{
            f.baseline(true,10000,8000); f.depth(true,10001,6000,2600); f.time(3100);
            f.depth(true,10001,0,3200); f.time(3600);
        }));
        results.add(run("bid_step_initial_four_thousand_then_six_thousand", f->{
            f.baseline(true,10000,6000); f.depth(true,10001,4000,2600); f.time(3100);
            f.depth(true,10001,6000,3300); f.time(3600); f.time(3900);
        }));
        results.add(run("same_day_backward_seek", f->{
            f.baseline(true,10000,8000); f.time(1000); f.depth(true,10001,6000,1100); f.time(1700);
        }));
        results.add(run("large_wall_floor_twenty_thousand_bid_step_six_thousand",20000,f->{
            f.baseline(true,10000,8000); f.depth(true,10001,6000,2600); f.time(3100); f.time(3600);
        }));
        results.add(run("bid_step_far_from_current_quote",f->{
            f.baseline(true,10000,8000); f.bbo(9990,10051,2600);
            f.depth(true,10050,6000,2700); f.time(3200); f.time(3700);
        }));
        results.add(run("peak_sixteen_thousand_shrinks_then_eight_thousand_consumed",f->{
            f.baseline(true,10000,8000); f.depth(true,10000,16000,2600);
            f.depth(true,10000,8000,2700); f.trade(10000,8000,false,2800);
            f.depth(true,10000,0,2800); f.time(3300);
            f.depth(true,10001,6000,3400); f.time(3900); f.time(4400);
        }));
        results.add(run("gradual_bid_consumption_with_slow_callback_receipt",f->{
            f.baseline(true,10000,8000); f.trade(10000,4000,false,2600); f.depth(true,10000,4000,2600);
            Thread.sleep(560);
            f.trade(10000,2000,false,2700); f.depth(true,10000,2000,2700);
            Thread.sleep(560);
            f.trade(10000,2000,false,2800); f.depth(true,10000,0,2800);
            f.trade(9999,1,false,2900); f.time(3400);
        }));
        // ASCII JSON preserves diagnostic characters across Windows console encodings.
        String json = GSON.toJson(results);
        System.out.println(json.chars().mapToObj(c -> c > 127
                ? String.format("%c%c%04x", 92, 'u', c) : Character.toString((char)c))
                .collect(java.util.stream.Collectors.joining()));
    }
}
