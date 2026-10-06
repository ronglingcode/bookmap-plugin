package com.bookmap.plugin.rong.patterns;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CairoEvidenceRecorderTest {
    @Test void writesRealProducerReplayFixtureForCairo() throws Exception {
        CairoEvidenceRecorder r=new CairoEvidenceRecorder("PCVX",0.01,"replay",false,()->5000);
        long t=1_791_208_800_000_000_000L;
        r.depth(true,1020,10000,t,false);r.depth(false,1030,10000,t,false);r.readiness(true,t);
        r.trade(1030,100,true,t+5_800_000_000L,false);r.trade(1025,100,false,t+6_000_000_000L,false);
        r.trade(1020,100,false,t+6_100_000_000L,false);r.trade(1024,100,true,t+6_500_000_000L,false);
        r.bbo(1018,100,1019,200,t+6_800_000_000L,false);r.trade(1018,100,false,t+7_000_000_000L,false);
        java.nio.file.Path path=java.nio.file.Path.of("build","fixtures","cairo-evidence.jsonl");java.nio.file.Files.createDirectories(path.getParent());
        StringBuilder content=new StringBuilder();for(JsonObject batch:r.drain(0)) content.append(batch).append('\n');
        java.nio.file.Files.writeString(path,content);
    }
    @Test void flagDefaultsOnAndCanBypassAllNewObservationWork() {
        CairoObservationConfig defaults=new CairoObservationConfig(new JsonObject());
        assertTrue(defaults.evidenceEnabled); assertTrue(defaults.recordEvidence("PCVX"));
        CairoObservationConfig off=new CairoObservationConfig(JsonParser.parseString("{\"evidenceEnabled\":false}").getAsJsonObject());
        assertFalse(off.recordEvidence("PCVX"));
        assertEquals("unknown",defaults.sourceMode);
    }
    @Test void preservesTradesQuotesWallIdentityAndReplayMetadata() {
        CairoEvidenceRecorder r=new CairoEvidenceRecorder("PCVX",0.01,"replay",false,()->5000);
        long time=1_700_000_000_000_000_000L;
        r.depth(true,1020,10000,time,false);r.readiness(true,time);
        r.trade(1020,100,false,time+1,false);r.trade(1024,50,true,time+2,false);
        r.bbo(1018,100,1019,200,time+3,false);r.depth(true,1020,0,time+4,false);
        java.util.List<JsonObject> batches=r.drain(1000);assertEquals(1,batches.size());
        JsonObject b=batches.get(0);assertEquals("replay",b.get("mode").getAsString());assertEquals("ready",b.get("readiness").getAsString());
        JsonArray events=b.getAsJsonArray("events");assertEquals(6,events.size());
        assertEquals(events.get(0).getAsJsonObject().get("wallId"),events.get(5).getAsJsonObject().get("wallId"));
        assertEquals(10.24,events.get(3).getAsJsonObject().get("price").getAsDouble(),0.000001);
        assertEquals("snapshot",r.snapshot().get(0).get("delivery").getAsString());
    }
    @Test void overflowAndReplaySeekExposeNewEpochRatherThanSilentLoss() {
        CairoEvidenceRecorder r=new CairoEvidenceRecorder("PCVX",0.01,"replay",false,()->5000);
        for(int i=0;i<9000;i++) r.trade(1000,1,null,1_700_000_000_000_000_000L+i,false);
        JsonObject b=r.drain(0).get(0);assertTrue(b.get("epoch").getAsLong()>1);assertTrue(b.get("dropped").getAsLong()>0);
        long epoch=b.get("epoch").getAsLong();r.trade(1000,1,null,1_600_000_000_000_000_000L,false);
        assertTrue(r.drain(1).get(0).get("epoch").getAsLong()>epoch);
    }
}
