package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.bookmap.plugin.rong.OrderBookState;
import com.bookmap.plugin.rong.pricelines.PriceLineStore;
import com.bookmap.plugin.rong.pricelines.PriceZoneStore;

class BookmapPatternEngineTest {

    private static final long BASE = Instant.parse("2026-07-17T14:00:00Z").toEpochMilli();

    @Test
    void detectsOfferBreakoutHoldAndPullbackRebreak() {
        Fixture hold = new Fixture();
        hold.bbo(9_999, 10_001, BASE);
        hold.qualifyAndClear(false, 10_000, BASE + 100);
        hold.bbo(10_001, 10_002, BASE + 1_301);
        hold.time(BASE + 3_800);
        BookmapPatternSignal holdSignal = hold.last(PatternType.OFFER_WALL_BREAKOUT);
        assertNotNull(holdSignal);
        assertRule(holdSignal, "break.hold");

        Fixture pullback = new Fixture();
        pullback.bbo(9_999, 10_001, BASE);
        pullback.qualifyAndClear(false, 10_000, BASE + 100);
        pullback.trade(10_005, 10, true, BASE + 1_350);
        pullback.trade(10_002, 10, true, BASE + 1_400);
        pullback.trade(10_006, 10, true, BASE + 1_450);
        BookmapPatternSignal pullbackSignal = pullback.last(PatternType.OFFER_WALL_BREAKOUT);
        assertNotNull(pullbackSignal);
        assertRule(pullbackSignal, "break.pullback_rebreak");
    }

    @Test
    void detectsBidBreakdownHoldAndPullbackRebreak() {
        Fixture hold = new Fixture();
        hold.bbo(9_999, 10_001, BASE);
        hold.qualifyAndClear(true, 10_000, BASE + 100);
        hold.bbo(9_997, 9_999, BASE + 1_301);
        hold.time(BASE + 3_800);
        assertRule(hold.last(PatternType.BID_WALL_BREAKDOWN), "break.hold");

        Fixture pullback = new Fixture();
        pullback.bbo(9_999, 10_001, BASE);
        pullback.qualifyAndClear(true, 10_000, BASE + 100);
        pullback.trade(9_995, 10, false, BASE + 1_350);
        pullback.trade(9_998, 10, false, BASE + 1_400);
        pullback.trade(9_994, 10, false, BASE + 1_450);
        assertRule(pullback.last(PatternType.BID_WALL_BREAKDOWN), "break.pullback_rebreak");
    }

    @Test
    void detectsOfferAndBidReappear() {
        Fixture offer = new Fixture();
        offer.bbo(9_997, 9_998, BASE);
        offer.qualifyAndClear(false, 10_000, BASE + 100);
        offer.depth(false, 9_999, 100, BASE + 1_400);
        offer.time(BASE + 1_900);
        BookmapPatternSignal offerSignal = offer.last(PatternType.OFFER_REAPPEAR);
        assertNotNull(offerSignal);
        assertRule(offerSignal, "reappear.better_price");

        Fixture bid = new Fixture();
        bid.bbo(10_002, 10_003, BASE);
        bid.qualifyAndClear(true, 10_000, BASE + 100);
        bid.depth(true, 10_001, 100, BASE + 1_400);
        bid.time(BASE + 1_900);
        BookmapPatternSignal bidSignal = bid.last(PatternType.BID_REAPPEAR);
        assertNotNull(bidSignal);
        assertRule(bidSignal, "reappear.better_price");
    }

    @Test
    void detectsOfferStepDownAndBidStepUpFromUnclearedReferences() {
        Fixture offer = new Fixture();
        offer.trade(10_050, 10, true, BASE + 10);
        offer.bbo(9_997, 9_998, BASE);
        offer.depth(false, 10_000, 100, BASE + 100);
        offer.time(BASE + 600);
        offer.depth(false, 9_999, 100, BASE + 700);
        offer.time(BASE + 1_200);
        assertNotNull(offer.last(PatternType.OFFER_STEP_DOWN));

        Fixture bid = new Fixture();
        bid.trade(9_950, 10, false, BASE + 10);
        bid.bbo(10_002, 10_003, BASE);
        bid.depth(true, 10_000, 100, BASE + 100);
        bid.time(BASE + 600);
        bid.depth(true, 10_001, 100, BASE + 700);
        bid.time(BASE + 1_200);
        assertNotNull(bid.last(PatternType.BID_STEP_UP));
    }

    @Test
    void stepPatternsRequireNewWallBeyondTheCorrectSessionExtreme() {
        Fixture offerAtHod = new Fixture();
        offerAtHod.trade(9_999, 10, true, BASE + 10);
        offerAtHod.bbo(9_997, 9_998, BASE + 20);
        offerAtHod.depth(false, 10_000, 100, BASE + 100);
        offerAtHod.time(BASE + 600);
        offerAtHod.depth(false, 9_999, 100, BASE + 700);
        offerAtHod.time(BASE + 1_200);
        assertFalse(offerAtHod.has(PatternType.OFFER_STEP_DOWN),
                "stepped-down offer must be strictly below HOD");

        Fixture bidAtLod = new Fixture();
        bidAtLod.trade(10_001, 10, false, BASE + 10);
        bidAtLod.bbo(10_002, 10_003, BASE + 20);
        bidAtLod.depth(true, 10_000, 100, BASE + 100);
        bidAtLod.time(BASE + 600);
        bidAtLod.depth(true, 10_001, 100, BASE + 700);
        bidAtLod.time(BASE + 1_200);
        assertFalse(bidAtLod.has(PatternType.BID_STEP_UP),
                "stepped-up bid must be strictly above LOD");
    }

    @Test
    void offerVShapeWaitsForRecordedLowBreakAndUsesPreTradeExtreme() {
        Fixture fixture = new Fixture();
        fixture.trade(10_050, 10, true, BASE + 10);
        fixture.trade(9_900, 10, false, BASE + 20);
        fixture.bbo(9_999, 10_001, BASE + 30);
        fixture.qualifyAndClear(false, 10_000, BASE + 100);
        fixture.trade(9_999, 10, false, BASE + 1_350);
        assertFalse(fixture.has(PatternType.OFFER_V_SHAPE_REJECTION));
        fixture.trade(9_900, 10, false, BASE + 1_400);
        assertFalse(fixture.has(PatternType.OFFER_V_SHAPE_REJECTION));
        fixture.trade(9_899, 10, false, BASE + 1_450);
        BookmapPatternSignal signal = fixture.last(PatternType.OFFER_V_SHAPE_REJECTION);
        assertNotNull(signal);
        assertEquals(9_899, signal.getTriggerPriceTick());
        assertRule(signal, "vshape.extreme_15s");
    }

    @Test
    void bidVShapeWaitsForRecordedHighBreakAndUsesPreTradeExtreme() {
        Fixture fixture = new Fixture();
        fixture.trade(9_950, 10, false, BASE + 10);
        fixture.trade(10_100, 10, true, BASE + 20);
        fixture.bbo(9_999, 10_001, BASE + 30);
        fixture.qualifyAndClear(true, 10_000, BASE + 100);
        fixture.trade(10_001, 10, true, BASE + 1_350);
        assertFalse(fixture.has(PatternType.BID_V_SHAPE_RECOVERY));
        fixture.trade(10_100, 10, true, BASE + 1_400);
        assertFalse(fixture.has(PatternType.BID_V_SHAPE_RECOVERY));
        fixture.trade(10_101, 10, true, BASE + 1_450);
        assertEquals(10_101, fixture.last(PatternType.BID_V_SHAPE_RECOVERY).getTriggerPriceTick());
    }

    @Test
    void rejectsFlashPullWrongAggressorAndStaleVShape() {
        Fixture flash = new Fixture();
        flash.depth(false, 10_000, 100, BASE + 100);
        flash.depth(false, 10_000, 0, BASE + 300);
        flash.time(BASE + 700);
        flash.depth(false, 10_000, 100, BASE + 800);
        flash.depth(false, 10_000, 0, BASE + 900);
        flash.time(BASE + 1_500);
        assertTrue(flash.signals.isEmpty());

        Fixture wrongAggressor = new Fixture();
        wrongAggressor.bbo(10_001, 10_002, BASE);
        wrongAggressor.depth(false, 10_000, 100, BASE + 100);
        wrongAggressor.time(BASE + 600);
        wrongAggressor.trade(10_000, 100, false, BASE + 700);
        wrongAggressor.depth(false, 10_000, 10, BASE + 800);
        wrongAggressor.time(BASE + 1_300);
        wrongAggressor.time(BASE + 4_000);
        assertFalse(wrongAggressor.has(PatternType.OFFER_WALL_BREAKOUT));

        Fixture stale = new Fixture();
        stale.trade(10_050, 10, true, BASE + 10);
        stale.trade(9_900, 10, false, BASE + 20);
        stale.qualifyAndClear(false, 10_000, BASE + 100);
        stale.trade(9_999, 10, false, BASE + 1_350);
        stale.time(BASE + 301_301);
        stale.trade(9_899, 10, false, BASE + 301_400);
        assertFalse(stale.has(PatternType.OFFER_V_SHAPE_REJECTION));
    }

    @Test
    void rejectsInferiorReappearAndInactiveOrWrongPatternTradebooks() {
        Fixture inferior = new Fixture();
        inferior.bbo(9_997, 9_998, BASE);
        inferior.qualifyAndClear(false, 10_000, BASE + 100);
        inferior.depth(false, 10_001, 100, BASE + 1_400);
        inferior.time(BASE + 1_900);
        assertFalse(inferior.has(PatternType.OFFER_REAPPEAR));

        Fixture inactive = new Fixture(EnumSet.noneOf(PatternType.class));
        inactive.bbo(10_001, 10_002, BASE);
        inactive.qualifyAndClear(false, 10_000, BASE + 100);
        inactive.time(BASE + 3_800);
        assertTrue(inactive.signals.isEmpty());

        Fixture wrong = new Fixture(EnumSet.of(PatternType.BID_WALL_BREAKDOWN));
        wrong.bbo(10_001, 10_002, BASE);
        wrong.qualifyAndClear(false, 10_000, BASE + 100);
        wrong.time(BASE + 3_800);
        assertTrue(wrong.signals.isEmpty());
    }

    @Test
    void requiresCompletedSnapshotAndRegularSession() {
        OrderBookState book = new OrderBookState();
        List<BookmapPatternSignal> output = new ArrayList<>();
        BookmapPatternEngine engine = new BookmapPatternEngine(
                "TEST", 0.01, () -> 100, 95, book,
                new PriceLineStore(), new PriceZoneStore(), type -> true, output::add);
        depth(engine, book, false, 10_000, 100, BASE + 100);
        engine.onTimestamp((BASE + 700) * 1_000_000L);
        engine.onTrade(10_000, 100, true, (BASE + 800) * 1_000_000L);
        depth(engine, book, false, 10_000, 10, BASE + 900);
        engine.onBbo(10_001, 10, 10_002, 10, (BASE + 1_000) * 1_000_000L);
        engine.onTimestamp((BASE + 4_000) * 1_000_000L);
        assertTrue(output.isEmpty(), "engine must stay disarmed until snapshot completion");

        long afterClose = Instant.parse("2026-07-17T21:00:00Z").toEpochMilli();
        engine.markReady();
        depth(engine, book, false, 10_100, 100, afterClose + 100);
        engine.onTimestamp((afterClose + 700) * 1_000_000L);
        engine.onTrade(10_100, 100, true, (afterClose + 800) * 1_000_000L);
        depth(engine, book, false, 10_100, 10, afterClose + 900);
        engine.onBbo(10_101, 10, 10_102, 10, (afterClose + 1_000) * 1_000_000L);
        engine.onTimestamp((afterClose + 4_000) * 1_000_000L);
        assertTrue(output.isEmpty(), "after-hours events must not alert");
    }

    @Test
    void featureToggleResetDropsExistingPatternEpisodes() {
        Fixture fixture = new Fixture();
        fixture.bbo(9_999, 10_001, BASE);
        fixture.qualifyAndClear(false, 10_000, BASE + 100);

        fixture.engine.resetForFeatureToggle();
        fixture.bbo(10_001, 10_002, BASE + 1_400);
        fixture.time(BASE + 4_000);

        assertFalse(fixture.has(PatternType.OFFER_WALL_BREAKOUT));
    }

    @Test
    void penalizesNearbyOpposingLiquidityAndStackedSweep() {
        Fixture nearby = new Fixture();
        nearby.bbo(9_999, 10_001, BASE);
        nearby.qualifyAndClear(true, 10_000, BASE + 100);
        nearby.depth(true, 9_999, 250, BASE + 1_350);
        nearby.bbo(9_997, 9_999, BASE + 1_400);
        nearby.time(BASE + 3_800);
        assertRule(nearby.last(PatternType.BID_WALL_BREAKDOWN), "liquidity.opposing_wall_2x");

        Fixture sweep = new Fixture();
        sweep.bbo(10_003, 10_004, BASE);
        for (int price = 10_000; price <= 10_002; price++) {
            sweep.depth(false, price, 100, BASE + 100);
        }
        sweep.time(BASE + 600);
        for (int price = 10_000; price <= 10_002; price++) {
            long offset = price - 10_000;
            sweep.trade(price, 100, true, BASE + 700 + offset * 10);
            sweep.depth(false, price, 10, BASE + 800 + offset * 10);
        }
        sweep.time(BASE + 1_320);
        sweep.time(BASE + 3_900);
        assertTrue(sweep.signals.stream()
                .filter(signal -> signal.getPatternType() == PatternType.OFFER_WALL_BREAKOUT)
                .anyMatch(signal -> hasRule(signal, "break.stacked_sweep")));
    }

    @Test
    void eventTimeMakesReplaySpeedIrrelevant() throws Exception {
        Fixture first = breakoutFixture();
        Thread.sleep(5);
        Fixture second = breakoutFixture();
        BookmapPatternSignal a = first.last(PatternType.OFFER_WALL_BREAKOUT);
        BookmapPatternSignal b = second.last(PatternType.OFFER_WALL_BREAKOUT);
        assertEquals(a.getScore(), b.getScore());
        assertEquals(a.getContributions().size(), b.getContributions().size());
        assertEquals(a.getEventTimeNs(), b.getEventTimeNs());
    }

    @Test
    void outputBoundaryContainsNoTradingOrBroadcastDependency() {
        for (java.lang.reflect.Field field : BookmapPatternEngine.class.getDeclaredFields()) {
            String type = field.getType().getName().toLowerCase();
            assertFalse(type.contains("websocket") || type.contains("ordermanager")
                    || type.contains("tradebutton") || type.contains("hotkey"), field.toString());
        }
        for (java.lang.reflect.Field field : BookmapPatternSignal.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase();
            assertFalse(name.contains("quantity") || name.contains("ordertype")
                    || name.contains("hotkey") || name.contains("command"), field.toString());
        }
    }

    @Test
    void sharedProvidersAreComputedOnceForSimultaneousPatternEpisodes() {
        CountingOrderBook book = new CountingOrderBook();
        CountingPriceLineStore lines = new CountingPriceLineStore();
        List<BookmapPatternSignal> output = new ArrayList<>();
        BookmapPatternEngine engine = new BookmapPatternEngine(
                "TEST", 0.01, () -> 100, 95, book, lines, new PriceZoneStore(),
                type -> true, output::add);
        engine.onTimestamp(BASE * 1_000_000L);
        engine.markReady();
        engine.onBbo(9_997, 10_001, 10, 10, BASE * 1_000_000L);

        depth(engine, book, false, 10_000, 100, BASE + 100);
        engine.onTimestamp((BASE + 600) * 1_000_000L);
        engine.onTrade(10_000, 100, true, (BASE + 700) * 1_000_000L);
        depth(engine, book, false, 10_000, 10, BASE + 800);
        engine.onTimestamp((BASE + 1_300) * 1_000_000L);
        depth(engine, book, false, 9_999, 100, BASE + 1_400);

        book.snapshotCalls = 0;
        lines.getLinesCalls = 0;
        engine.onTimestamp((BASE + 1_900) * 1_000_000L);

        assertTrue(output.stream().anyMatch(s -> s.getPatternType() == PatternType.OFFER_REAPPEAR));
        assertTrue(output.stream().anyMatch(s -> s.getPatternType() == PatternType.OFFER_STEP_DOWN));
        assertEquals(1, book.snapshotCalls, "nearby-liquidity provider should be shared");
        assertEquals(1, lines.getLinesCalls, "configured-level provider should be shared");
    }

    private static void depth(BookmapPatternEngine engine, OrderBookState book,
                              boolean bid, int price, int size, long timeMs) {
        book.update(bid, price, size);
        engine.onDepth(bid, price, size, timeMs * 1_000_000L);
    }

    private static Fixture breakoutFixture() {
        Fixture fixture = new Fixture();
        fixture.bbo(9_999, 10_001, BASE);
        fixture.qualifyAndClear(false, 10_000, BASE + 100);
        fixture.bbo(10_001, 10_002, BASE + 1_301);
        fixture.time(BASE + 3_800);
        return fixture;
    }

    private static void assertRule(BookmapPatternSignal signal, String ruleId) {
        assertNotNull(signal);
        assertTrue(hasRule(signal, ruleId), () -> ruleId + " not in " + signal.getContributions());
    }

    private static boolean hasRule(BookmapPatternSignal signal, String ruleId) {
        return signal.getContributions().stream().anyMatch(c -> c.getRuleId().equals(ruleId));
    }

    private static final class Fixture {
        final OrderBookState book = new OrderBookState();
        final List<BookmapPatternSignal> signals = new ArrayList<>();
        final Set<PatternType> enabled;
        final BookmapPatternEngine engine;

        Fixture() {
            this(EnumSet.allOf(PatternType.class));
        }

        Fixture(Set<PatternType> enabled) {
            this.enabled = enabled;
            engine = new BookmapPatternEngine(
                    "TEST", 0.01, () -> 100, 95, book,
                    new PriceLineStore(), new PriceZoneStore(), enabled::contains, signals::add);
            engine.onTimestamp(BASE * 1_000_000L);
            engine.markReady();
        }

        void depth(boolean bid, int price, int size, long timeMs) {
            book.update(bid, price, size);
            engine.onDepth(bid, price, size, timeMs * 1_000_000L);
        }

        void trade(int price, int size, boolean buyer, long timeMs) {
            engine.onTrade(price, size, buyer, timeMs * 1_000_000L);
        }

        void bbo(int bid, int ask, long timeMs) {
            engine.onBbo(bid, 10, ask, 10, timeMs * 1_000_000L);
        }

        void time(long timeMs) {
            engine.onTimestamp(timeMs * 1_000_000L);
        }

        void qualifyAndClear(boolean bid, int price, long startMs) {
            depth(bid, price, 100, startMs);
            time(startMs + 500);
            trade(price, 100, !bid, startMs + 600);
            depth(bid, price, 10, startMs + 700);
            time(startMs + 1_200);
        }

        boolean has(PatternType type) {
            return signals.stream().anyMatch(signal -> signal.getPatternType() == type);
        }

        BookmapPatternSignal last(PatternType type) {
            BookmapPatternSignal result = null;
            for (BookmapPatternSignal signal : signals) {
                if (signal.getPatternType() == type) result = signal;
            }
            return result;
        }
    }

    private static final class CountingOrderBook extends OrderBookState {
        int snapshotCalls;

        @Override
        public synchronized NavigableMap<Integer, Integer> getLevelsSnapshot(boolean isBid) {
            snapshotCalls++;
            return super.getLevelsSnapshot(isBid);
        }
    }

    private static final class CountingPriceLineStore extends PriceLineStore {
        int getLinesCalls;

        @Override
        public List<com.bookmap.plugin.rong.pricelines.PriceLine> getLines(String instrumentAlias) {
            getLinesCalls++;
            return super.getLines(instrumentAlias);
        }
    }
}
