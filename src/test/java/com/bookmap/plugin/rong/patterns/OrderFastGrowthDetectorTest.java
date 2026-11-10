package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.patterns.PatternObservationEngineTest.BASE;
import static com.bookmap.plugin.rong.patterns.PatternObservationEngineTest.MARKET;
import java.util.*;
import java.util.stream.Collectors;
import com.google.gson.*;
import org.junit.jupiter.api.Test;

class OrderFastGrowthDetectorTest {
    private static class Harness {
        final List<PatternEvent> events = new ArrayList<>();
        final PatternEventPipeline pipeline;
        Harness() { this(PatternObservationConfig.defaults()); }
        Harness(PatternObservationConfig config) {
            pipeline = new PatternEventPipeline("NVDA",.01,config,events::add,d->{});
            pipeline.markReady(); time(0);
        }
        void time(long ms) { pipeline.onTimestamp(BASE+ms*1_000_000L,MARKET); }
        void depth(boolean bid,int price,long size,long ms) { pipeline.onDepth(bid,price,size,BASE+ms*1_000_000L,MARKET); }
        List<PatternEvent> growth() { return events.stream().filter(e->e.type==PatternEventType.BID_FAST_GROWTH||e.type==PatternEventType.OFFER_FAST_GROWTH).collect(Collectors.toList()); }
        void grow(boolean bid,int price) {
            depth(bid,price,172000,2000);depth(bid,price,210000,2600);
            depth(bid,price,240000,3100);depth(bid,price,270000,3600);
            depth(bid,price,300000,4100);depth(bid,price,334000,4600);
        }
    }
    @Test void repeatedGrowthOverSecondsEmitsDuringGrowthOnBothSidesWithoutATouch() {
        for(boolean bid:new boolean[]{true,false}) {
            Harness h=new Harness();h.grow(bid,bid?17000:17500);
            assertEquals(1,h.growth().size());PatternEvent e=h.growth().get(0);
            assertEquals(bid?PatternEventType.BID_FAST_GROWTH:PatternEventType.OFFER_FAST_GROWTH,e.type);
            assertEquals(PatternMeaning.UNKNOWN,e.meaning);assertEquals(334000,e.size);
            assertEquals(172000,e.evidence.growthBaselineSize);assertEquals(162000,e.evidence.addedSize);
            assertEquals(162000.0/172000,e.evidence.growthRatio,1e-12);
            assertEquals(2000_000_000L,e.evidence.growthDurationNs);assertEquals(5,e.evidence.growthIncreaseCount);
            assertEquals(500_000_000L,e.evidence.growthMaxGapNs);assertEquals(38000,e.evidence.growthLargestStepSize);
            assertEquals(BASE+4600_000_000L,e.eventTimeNs);assertEquals(e.eventTimeNs,e.observedAtNs);
        }
    }
    @Test void oneLargeJumpNeverQualifiesEvenIfItRemainsForSeconds() {
        for(boolean bid:new boolean[]{true,false}) {
            Harness h=new Harness();h.depth(bid,17000,172000,2000);h.depth(bid,17000,334000,2600);
            for(int t=3000;t<8000;t+=500){h.depth(bid,17000,334000,t);h.time(t);}
            assertTrue(h.growth().isEmpty());
        }
    }
    @Test void oneDominatingJumpWithTokenFollowupIncreasesDoesNotQualify() {
        Harness h=new Harness();h.depth(true,17000,172000,2000);h.depth(true,17000,332000,2600);
        h.depth(true,17000,332500,3100);h.depth(true,17000,333000,3600);
        h.depth(true,17000,333500,4100);h.depth(true,17000,334000,4600);
        assertTrue(h.growth().isEmpty());
    }
    @Test void manyIncreasesInOneBurstOrOneTimestampDoNotEstablishSecondsOfGrowth() {
        Harness fast=new Harness();fast.depth(true,17000,172000,2000);
        fast.depth(true,17000,210000,2600);fast.depth(true,17000,240000,2650);fast.depth(true,17000,270000,2700);
        fast.depth(true,17000,300000,2750);fast.depth(true,17000,334000,2800);
        fast.time(4800);assertTrue(fast.growth().isEmpty());
        Harness same=new Harness();same.depth(true,17000,172000,2000);
        same.depth(true,17000,210000,2600);same.depth(true,17000,260000,2600);same.depth(true,17000,334000,2600);
        same.time(4600);assertTrue(same.growth().isEmpty());
        Harness fragmented=new Harness();fragmented.depth(true,17000,172000,2000);
        for(int i=1;i<=160;i++)fragmented.depth(true,17000,172000+i*1000,2600);
        fragmented.depth(true,17000,332500,3100);fragmented.depth(true,17000,333000,3600);
        fragmented.depth(true,17000,333500,4100);fragmented.depth(true,17000,334000,4600);
        assertTrue(fragmented.growth().isEmpty());
    }
    @Test void flatUpdatesDoNotBridgeLongPausesBetweenAdditions() {
        Harness h=new Harness();h.depth(true,17000,172000,2000);h.depth(true,17000,210000,2600);
        h.depth(true,17000,210000,3100);h.depth(true,17000,210000,3600);
        h.depth(true,17000,260000,4600);h.depth(true,17000,334000,5600);
        assertTrue(h.growth().isEmpty());
    }
    @Test void aDecreaseOrPullRestartsContinuityRatherThanCombiningSeparateRuns() {
        for(long dropped:new long[]{0,200000}) {
            Harness h=new Harness();h.depth(true,17000,172000,2000);h.depth(true,17000,210000,2600);
            h.depth(true,17000,260000,3600);h.depth(true,17000,dropped,4000);h.depth(true,17000,334000,4600);
            h.time(5000);assertTrue(h.growth().isEmpty());
        }
    }
    @Test void percentageAndAbsoluteIncreaseMustBothMeetInclusiveThresholds() {
        long[][] cases={{10000,15000,1},{10000,14999,0},{3000,7999,0},{3000,8000,1},{2999,10000,0}};
        for(long[] c:cases) {
            Harness h=new Harness();h.depth(true,17000,c[0],2000);long delta=c[1]-c[0];
            for(int i=1;i<=5;i++)h.depth(true,17000,c[0]+delta*i/5,2600+(i-1)*500);
            assertEquals(c[2],h.growth().size(),Arrays.toString(c));
        }
    }
    @Test void anOldQuietBaselineCannotSupplyTheRequiredGrowthDuration() {
        Harness h=new Harness();h.depth(true,17000,172000,0);h.time(10000);
        h.depth(true,17000,334000,10001);h.time(10501);assertTrue(h.growth().isEmpty());
    }
    @Test void continuingAdditionsReviseTheEpisodeWithoutDuplicatesOrWaitingForAPlateau() {
        Harness h=new Harness();h.grow(true,17000);PatternEvent first=h.growth().get(0);
        h.depth(true,17000,334000,4700);h.time(4800);assertEquals(1,h.growth().size());
        h.depth(true,17000,350000,5000);assertEquals(2,h.growth().size());PatternEvent next=h.growth().get(1);
        assertEquals(first.id,next.id);assertEquals(2,next.revision);assertEquals(first.eventTimeNs,next.eventTimeNs);
        JsonObject snapshot=h.pipeline.aggregateSnapshot(new JsonObject(),false);
        List<JsonObject> rows=new ArrayList<>();snapshot.getAsJsonArray("patterns").forEach(e->{if(e.getAsJsonObject().get("type").getAsString().equals("BID_FAST_GROWTH"))rows.add(e.getAsJsonObject());});
        assertEquals(1,rows.size());JsonObject evidence=rows.get(0).getAsJsonObject("evidence");
        assertEquals(Long.toString(BASE+2600_000_000L),evidence.get("growthStartNs").getAsString());
        assertEquals("500000000",evidence.get("growthMaxGapNs").getAsString());
        assertEquals(6,evidence.get("growthIncreaseCount").getAsInt());assertEquals(178000,evidence.get("addedSize").getAsLong());
    }
    @Test void separateSidesAndPricesNeverSupplyTheMissingIncreases() {
        Harness h=new Harness();h.depth(true,17000,172000,2000);h.depth(false,17500,172000,2000);
        h.depth(true,17001,210000,2600);h.depth(true,17002,260000,3600);h.depth(true,17003,334000,4600);
        h.depth(false,17500,210000,4600);h.depth(false,17500,240000,5100);h.depth(false,17500,270000,5600);
        h.depth(false,17500,300000,6100);h.depth(false,17500,334000,6600);
        assertEquals(1,h.growth().size());assertEquals(PatternEventType.OFFER_FAST_GROWTH,h.growth().get(0).type);
    }
    @Test void replaySeekAndFallbackDepthCannotSupplyMissingHistory() {
        Harness h=new Harness();h.depth(true,17000,172000,2000);h.depth(true,17000,210000,2600);
        h.pipeline.onDepth(true,17000,260000,BASE+3600_000_000L,PatternEvent.TimestampProvenance.FALLBACK);
        h.depth(true,17000,334000,4600);assertTrue(h.growth().isEmpty());
        h.time(1000);assertFalse(h.pipeline.usable());h.depth(true,17000,334000,1100);
        h.pipeline.markReady();h.time(1200);h.depth(true,17000,400000,2300);h.depth(true,17000,450000,2800);h.depth(true,17000,500000,3300);h.depth(true,17000,550000,3800);h.depth(true,17000,600000,4300);
        assertEquals(1,h.growth().size());assertEquals(334000,h.growth().get(0).evidence.growthBaselineSize);
    }
    @Test void customDurationCadenceAndSizeRequirementsApplyToBothSides() {
        PatternObservationConfig config=PatternObservationConfig.parse(JsonParser.parseString(
                "{\"detectors\":{\"fastGrowthRatio\":0.25,\"fastGrowthMinIncrease\":1000,\"fastGrowthWindowMs\":1000,\"fastGrowthMinDurationMs\":200,\"fastGrowthMaxGapMs\":100,\"fastGrowthMinUpdates\":3}}").getAsJsonObject());
        assertTrue(config.valid,config.error);
        for(boolean bid:new boolean[]{true,false}) {
            Harness h=new Harness(config);h.depth(bid,17000,4000,2000);
            h.depth(bid,17000,4333,2600);h.depth(bid,17000,4666,2700);h.depth(bid,17000,5000,2800);
            assertEquals(1,h.growth().size());
        }
    }
    @Test void defaultCadenceRejectsGapsJustOverHalfASecondOnBothSides() {
        for(boolean bid:new boolean[]{true,false})for(long gap:new long[]{501,1000}) {
            Harness h=new Harness();h.depth(bid,17000,172000,2000);
            for(int i=1;i<=5;i++)h.depth(bid,17000,172000+162000*i/5,2600+(i-1)*gap);
            assertTrue(h.growth().isEmpty(),"gap="+gap);
        }
    }
    @Test void everyDepthUpdateIsTrackedWithoutHalfSecondSamplingOrPolling() {
        for(boolean bid:new boolean[]{true,false}) {
            Harness h=new Harness();h.depth(bid,17000,172000,2000);
            for(int i=1;i<=21;i++)h.depth(bid,17000,172000+162000*i/21,2600+(i-1)*100);
            assertEquals(1,h.growth().size());PatternEvent e=h.growth().get(0);
            assertEquals(21,e.evidence.growthIncreaseCount);assertEquals(100_000_000L,e.evidence.growthMaxGapNs);
            assertEquals(BASE+4600_000_000L,e.observedAtNs);
        }
    }

}
