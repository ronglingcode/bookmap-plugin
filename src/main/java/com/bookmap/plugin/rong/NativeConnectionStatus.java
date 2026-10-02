package com.bookmap.plugin.rong;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Observable connection state for the two native vendor streams. */
public final class NativeConnectionStatus {
    public static final class Snapshot {
        private final String schwab;
        private final String massiveHistory;
        private final String massiveStream;

        private Snapshot(String schwab, String massiveHistory, String massiveStream) {
            this.schwab = schwab;
            this.massiveHistory = massiveHistory;
            this.massiveStream = massiveStream;
        }

        public String getSchwab() { return schwab; }
        public String getMassiveHistory() { return massiveHistory; }
        public String getMassiveStream() { return massiveStream; }
        public boolean isSchwabConnected() { return "connected".equalsIgnoreCase(schwab); }
        public boolean isMassiveHistoryReady() { return "ready".equalsIgnoreCase(massiveHistory); }
        public boolean isMassiveStreamReceiving() { return "receiving trades".equalsIgnoreCase(massiveStream); }
        public boolean areAllConnected() { return isSchwabConnected() && isMassiveHistoryReady() && isMassiveStreamReceiving(); }
    }

    private final Set<Consumer<Snapshot>> listeners =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile String schwab = "not started";
    private volatile String massiveHistory = "not started";
    private volatile String massiveStream = "not started";

    public Snapshot snapshot() { return new Snapshot(schwab, massiveHistory, massiveStream); }

    public void setStarting() {
        schwab = "connecting";
        massiveHistory = "loading";
        massiveStream = "connecting";
        notifyListeners();
    }

    public void update(String source, String status) {
        String value = status == null || status.trim().isEmpty() ? "unknown" : status.trim();
        if ("schwab".equalsIgnoreCase(source)) schwab = value;
        else if ("massiveHistory".equalsIgnoreCase(source)) massiveHistory = value;
        else if ("massiveStream".equalsIgnoreCase(source)) massiveStream = value;
        else return;
        notifyListeners();
    }

    public void setUnavailable(String reason) {
        String value = reason == null || reason.trim().isEmpty() ? "unavailable" : reason.trim();
        schwab = value;
        massiveHistory = value;
        massiveStream = value;
        notifyListeners();
    }

    public void addListener(Consumer<Snapshot> listener) {
        listeners.add(listener);
        listener.accept(snapshot());
    }

    public void removeListener(Consumer<Snapshot> listener) { listeners.remove(listener); }

    private void notifyListeners() {
        Snapshot value = snapshot();
        for (Consumer<Snapshot> listener : listeners) listener.accept(value);
    }
}
