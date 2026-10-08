package com.bookmap.plugin.rong.signal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.bookmap.plugin.rong.patterns.*;
import com.google.gson.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
class CairoPatternSnapshotTest {
 static final long BASE=Instant.parse("2026-10-07T14:00:00Z").getEpochSecond()*1_000_000_000L;
 static final PatternEvent.TimestampProvenance MARKET=PatternEvent.TimestampProvenance.MARKET;
 static final class Fixture {
  final List<PatternEvent> events=new ArrayList<>();
  final SignalCompositionPipeline pipeline=new SignalCompositionPipeline("PCVX",.01,SignalComposerConfig.defaults(),u->{},events::add,d->{},()->1L);
  Fixture(){pipeline.markReady();time(0);pipeline.onBbo(5100,5121,BASE,MARKET);trade(5090,1,false,100);}
  void time(long ms){pipeline.onTimestamp(BASE+ms*1_000_000L,MARKET);}
  void trade(int price,long size,Boolean buy,long ms){pipeline.onTrade(price,size,buy,BASE+ms*1_000_000L,MARKET);}
  void depth(boolean bid,int price,long size,long ms){pipeline.onDepth(bid,price,size,BASE+ms*1_000_000L,MARKET);}
  JsonObject snapshot(boolean reconnect){JsonObject l=new JsonObject();l.addProperty("threshold",5000);l.addProperty("asOf",Long.toString(BASE+5000_000_000L));l.addProperty("bestBid",51.0);l.addProperty("bestAsk",51.01);l.add("bids",new JsonArray());l.add("offers",new JsonArray());return pipeline.aggregateSnapshot(l,reconnect);}
 }
 @Test void symmetricBreaksRequireKnownConsumptionAndConfirmingPrintExactlyOnce(){
  for(boolean bid:new boolean[]{true,false})for(Boolean buy:new Boolean[]{null,bid,!bid}){
   Fixture f=new Fixture();int price=bid?5100:5120;
   f.depth(bid,price,8000,2000);f.time(2500);f.trade(price,8000,buy,2600);f.depth(bid,price,0,2600);f.time(3100);
   assertFalse(f.events.stream().anyMatch(e->e.type==PatternEventType.BID_BREAKDOWN||e.type==PatternEventType.OFFER_BREAKOUT));
   f.trade(price+(bid?-1:1),1,!bid,3200);f.time(3700);f.time(3800);
   long count=f.events.stream().filter(e->e.type==(bid?PatternEventType.BID_BREAKDOWN:PatternEventType.OFFER_BREAKOUT)).count();
   assertEquals(Boolean.valueOf(!bid).equals(buy)?1:0,count);
  }
 }
 @Test void offerBreakIsContextAndBidBreakIsComposedBeforeExport() throws Exception {
  Fixture f=new Fixture();f.depth(false,5120,8000,2000);f.time(2500);f.trade(5120,8000,true,2600);f.depth(false,5120,0,2600);f.trade(5121,1,true,2700);f.time(3100);
  JsonObject offer=f.snapshot(false);assertEquals(1,offer.getAsJsonArray("patterns").size());assertEquals(0,offer.getAsJsonArray("signals").size());assertEquals("LONG",offer.getAsJsonArray("contexts").get(0).getAsJsonObject().get("direction").getAsString());
  f.depth(true,5100,8000,3200);f.time(3700);f.trade(5100,8000,false,3800);f.depth(true,5100,0,3800);f.trade(5099,1,false,3900);f.time(4300);f.time(5000);
  JsonObject result=f.snapshot(false);result.addProperty("mode","replay");assertEquals(2,result.getAsJsonArray("patterns").size());assertEquals(1,result.getAsJsonArray("signals").size());
  assertEquals("BID_BREAKDOWN",result.getAsJsonArray("signals").get(0).getAsJsonObject().get("pattern").getAsString());
  String json=result.toString();assertFalse(json.contains("wall-update"));assertFalse(json.contains("\"kind\":\"trade\""));assertTrue(result.getAsJsonArray("patterns").get(0).getAsJsonObject().get("observedAt").isJsonPrimitive());
  Path path=Path.of("build","fixtures","cairo-patterns.jsonl");Files.createDirectories(path.getParent());Files.writeString(path,json+"\n");
  long epoch=result.get("epoch").getAsLong();f.pipeline.reset(ResetReason.REPLAY_SEEK);JsonObject reset=f.snapshot(true);assertTrue(reset.get("epoch").getAsLong()>epoch);assertEquals(0,reset.getAsJsonArray("patterns").size());assertEquals(0,reset.getAsJsonArray("signals").size());assertEquals("not-ready",reset.get("readiness").getAsString());
 }
 @Test void fullReconnectSnapshotDoesNotAdvanceMarketTime(){Fixture f=new Fixture();JsonObject first=f.snapshot(false),next=f.snapshot(true);assertEquals(first.get("eventTime"),next.get("eventTime"));assertTrue(next.get("sequence").getAsLong()>first.get("sequence").getAsLong());assertEquals("snapshot",next.get("delivery").getAsString());}
 @Test void signalStateChangesReviseWireCardsAndCarryMarketTime() {
  Fixture f=new Fixture();f.depth(true,5100,8000,2000);f.time(2500);f.trade(5100,8000,false,2600);f.depth(true,5100,0,2600);f.trade(5099,1,false,2700);f.time(3100);
  JsonObject first=f.snapshot(false).getAsJsonArray("signals").get(0).getAsJsonObject();assertEquals("VALID",first.get("state").getAsString());
  f.pipeline.onBbo(5200,5201,BASE+3200_000_000L,MARKET);
  JsonObject next=f.snapshot(false).getAsJsonArray("signals").get(0).getAsJsonObject();assertEquals("INVALID",next.get("state").getAsString());assertTrue(next.get("revision").getAsInt()>first.get("revision").getAsInt());assertEquals(Long.toString(BASE+3200_000_000L),next.get("stateAt").getAsString());
  assertEquals(first.get("compositionRevision"),next.get("compositionRevision"));
 }
 @Test void feedClosesWithEmptyNotReadySnapshotAndNeverTouchesMarketClock(){
  Fixture f=new Fixture();CairoPatternFeed feed=new CairoPatternFeed(reconnect->f.snapshot(reconnect),false);
  JsonObject value=feed.drain(1000).get(0);assertTrue(feed.drain(1100).isEmpty());feed.close();assertTrue(feed.drain(2000).isEmpty());
  JsonObject end=feed.terminal();assertEquals(value.get("eventTime"),end.get("eventTime"));assertEquals(value.get("epoch").getAsLong()+1,end.get("epoch").getAsLong());assertEquals("not-ready",end.get("readiness").getAsString());assertEquals(0,end.getAsJsonArray("patterns").size());assertEquals(0,end.getAsJsonArray("signals").size());
 }

}
