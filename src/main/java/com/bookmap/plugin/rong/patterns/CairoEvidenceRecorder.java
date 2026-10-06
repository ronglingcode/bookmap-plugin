package com.bookmap.plugin.rong.patterns;

import com.bookmap.plugin.rong.BookmapPriceNormalizer;
import com.bookmap.plugin.rong.PluginLog;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.IntSupplier;

/** Independent observer. Callback methods do no file or network I/O. */
public final class CairoEvidenceRecorder implements AutoCloseable {
    private static final int MAX_PENDING = 8192, MAX_HISTORY = 20000;
    private final String alias, source = UUID.randomUUID().toString();
    private final double pips;
    private final String mode;
    private final boolean capture;
    private final IntSupplier threshold;
    private final Deque<JsonObject> pending = new ArrayDeque<>(), history = new ArrayDeque<>();
    private final Map<String, Wall> walls = new HashMap<>();
    private long sequence, epoch = 1, lastTime, lastHeartbeat, dropped, wallSequence;
    private boolean ready, closed;
    private String captureError;
    private Path captureFile;
    private long captureBytes;
    private int segment;
    private static final class Wall {
        final String id; final boolean bid; final int tick;
        int peak, size; long first, volume; final Deque<long[]> trades=new ArrayDeque<>();
        Wall(String id, boolean bid, int tick, int size, long time) { this.id=id; this.bid=bid; this.tick=tick; this.peak=this.size=size; this.first=time; }
    }
    public CairoEvidenceRecorder(String alias, double pips, String mode, boolean capture, IntSupplier threshold) {
        this.alias=alias; this.pips=pips; this.mode=mode; this.capture=capture; this.threshold=threshold;
    }
    public synchronized void depth(boolean bid, int tick, int size, long time, boolean fallback) {
        if (closed || tick <= 0 || size < 0) return;
        time(time);
        String key=(bid ? "B:" : "A:")+tick; Wall wall=walls.get(key);
        int floor=Math.max(1, threshold.getAsInt());
        if (wall == null && size >= floor) {
            if (walls.size() >= 2048) { discontinuity(); walls.clear(); }
            wall=new Wall(source+":"+epoch+":"+(++wallSequence),bid,tick,size,time); walls.put(key,wall);
            emitWall("wall-start",wall,time,fallback,floor,"unknown");
        } else if (wall != null && wall.size != size) {
            wall.size=size; wall.peak=Math.max(wall.peak,size);
            wall.volume=0; while(!wall.trades.isEmpty() && wall.trades.peekFirst()[0]<time-2_000_000_000L) wall.trades.removeFirst(); for(long[] trade:wall.trades) wall.volume+=trade[1];
            String attribution=size <= wall.peak*0.1 ? (wall.volume >= wall.peak*0.7 ? "probable-consumption" : "unknown-or-pull") : "unknown";
            boolean ended = size == 0 || size < floor && time-wall.first < 500_000_000L;
            emitWall(ended ? "wall-end" : "wall-update",wall,time,fallback,floor,attribution);
            if (ended) walls.remove(key);
        }
    }
    private void emitWall(String kind, Wall wall, long time, boolean fallback, int floor, String attribution) {
        JsonObject e=event(kind,time,fallback); e.addProperty("wallId",wall.id); e.addProperty("bid",wall.bid);
        e.addProperty("price",BookmapPriceNormalizer.toWirePrice(wall.tick,pips)); e.addProperty("size",wall.size);
        e.addProperty("peakSize",wall.peak); e.addProperty("firstTime",Long.toString(wall.first)); e.addProperty("threshold",floor);
        e.addProperty("attribution",attribution); append(e);
    }
    public synchronized void trade(int tick, int size, Boolean buyAggressor, long time, boolean fallback) {
        if (closed || tick <= 0 || size <= 0) return; time(time);
        Wall wall=walls.get((Boolean.TRUE.equals(buyAggressor) ? "A:" : "B:")+tick);
        if (buyAggressor != null && wall != null) { wall.trades.addLast(new long[]{time,size}); while(wall.trades.size()>512) wall.trades.removeFirst(); }
        JsonObject e=event("trade",time,fallback); e.addProperty("price",BookmapPriceNormalizer.toWirePrice(tick,pips)); e.addProperty("size",size);
        if (buyAggressor == null) e.add("buyAggressor",JsonNull.INSTANCE); else e.addProperty("buyAggressor",buyAggressor); append(e);
    }
    public synchronized void bbo(int bid, int bidSize, int ask, int askSize, long time, boolean fallback) {
        if (closed || bid <= 0 || ask <= 0 || bid > ask) return; time(time);
        JsonObject e=event("bbo",time,fallback); e.addProperty("bestBid",BookmapPriceNormalizer.toWirePrice(bid,pips));
        e.addProperty("bestAsk",BookmapPriceNormalizer.toWirePrice(ask,pips)); e.addProperty("bidSize",bidSize); e.addProperty("askSize",askSize); append(e);
    }
    public synchronized void readiness(boolean value, long time) { if (closed) return; time(time); ready=value; append(event("status",time,false)); }
    private void time(long time) {
        if (lastTime > 0 && time < lastTime) { discontinuity(); walls.clear(); }
        lastTime=time;
    }
    private void discontinuity() { epoch++; sequence=0; pending.clear(); history.clear(); ready=false; dropped++; }
    private JsonObject event(String kind,long time,boolean fallback) {
        JsonObject e=new JsonObject(); e.addProperty("id",source+":"+epoch+":"+(sequence+1)); e.addProperty("sequence",++sequence);
        e.addProperty("kind",kind); e.addProperty("eventTime",Long.toString(time)); e.addProperty("timestampFallback",fallback); return e;
    }
    private void append(JsonObject e) {
        if (pending.size() >= MAX_PENDING) {
            // Keep depth usable after an export gap, but explicitly start a new evidence epoch.
            boolean wasReady=ready; discontinuity(); ready=wasReady; walls.clear();
            e.addProperty("sequence",++sequence); e.addProperty("id",source+":"+epoch+":"+sequence);
        }
        pending.addLast(e); history.addLast(e);
        long cutoff=lastTime-600_000_000_000L;
        while (history.size()>MAX_HISTORY || (!history.isEmpty() && Long.parseLong(history.peekFirst().get("eventTime").getAsString())<cutoff)) history.removeFirst();
    }
    private JsonObject envelope(JsonArray events, boolean snapshot) {
        JsonObject value=new JsonObject(); value.addProperty("type","cairo_evidence"); value.addProperty("version",1);
        value.addProperty("sourceInstanceId",source); value.addProperty("epoch",epoch); value.addProperty("symbol",alias);
        value.addProperty("priceUnit","USD"); value.addProperty("tickSize",pips); value.addProperty("mode",mode);
        value.addProperty("readiness",ready ? "ready" : "not-ready"); value.addProperty("delivery",snapshot ? "snapshot" : "stream");
        value.addProperty("watermark",sequence); value.addProperty("dropped",dropped); value.addProperty("detectorRevision","evidence-v1");
        value.addProperty("captureEnabled",capture); if (captureError != null) value.addProperty("captureError",captureError);
        value.addProperty("eventTime",Long.toString(lastTime)); value.add("events",events); return value;
    }
    /** Called only by the server's background worker. All trades and BBO transitions survive batching. */
    public List<JsonObject> drain(long now) {
        List<JsonObject> result=new ArrayList<>();
        synchronized (this) {
            for (int batches=0; batches<(closed ? 64 : 16) && !pending.isEmpty(); batches++) {
                JsonArray events=new JsonArray(); for (int i=0;i<128 && !pending.isEmpty();i++) events.add(pending.removeFirst()); result.add(envelope(events,false));
            }
            if (result.isEmpty() && now-lastHeartbeat>=2000) result.add(envelope(new JsonArray(),false));
            if (!result.isEmpty()) lastHeartbeat=now;
        }
        for (JsonObject value : result) persist(value);
        return result;
    }
    public synchronized List<JsonObject> snapshot() {
        List<JsonObject> result=new ArrayList<>(); JsonArray events=new JsonArray();
        for (JsonObject e:history) { events.add(e); if (events.size()==128) { result.add(envelope(events,true)); events=new JsonArray(); } }
        if (events.size()>0 || result.isEmpty()) result.add(envelope(events,true));
        return result;
    }
    private void persist(JsonObject value) {
        if (!capture || captureError != null || value.getAsJsonArray("events").size()==0) return;
        try {
            Path root=Path.of(System.getProperty("user.home"),"bmtrader","evidence"); Files.createDirectories(root);
            byte[] bytes=(value.toString()+"\n").getBytes(StandardCharsets.UTF_8);
            if (captureFile==null || captureBytes+bytes.length>10*1024*1024) {
                captureFile=root.resolve("evidence-"+source+"-"+(segment++)+".jsonl"); captureBytes=0;
                // Delete only dedicated evidence files, retaining at most 100 MiB.
                try (java.util.stream.Stream<Path> paths=Files.list(root)) {
                    List<Path> files=new ArrayList<>(); paths.filter(p->p.getFileName().toString().matches("evidence-[a-f0-9-]+-[0-9]+\\.jsonl")).forEach(files::add);
                    files.sort(Comparator.comparingLong(p->{try{return Files.getLastModifiedTime(p).toMillis();}catch(Exception ignored){return Long.MAX_VALUE;}}));
                    long total=0; for(Path p:files) total+=Files.size(p);
                    for(Path p:files) { if(total<=90L*1024*1024) break; long length=Files.size(p); Files.delete(p); total-=length; }
                }
            }
            Files.write(captureFile,bytes,StandardOpenOption.CREATE,StandardOpenOption.APPEND); captureBytes+=bytes.length;
        } catch(Exception error) { synchronized(this) { captureError="Evidence capture failed: "+error.getClass().getSimpleName(); } PluginLog.action(alias,captureError); }
    }
    @Override public synchronized void close() { closed=true; ready=false; walls.clear(); }
}
