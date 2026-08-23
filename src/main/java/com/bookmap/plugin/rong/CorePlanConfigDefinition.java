package com.bookmap.plugin.rong;

/** The active trade's editable core-target plan as published by ViteApp. */
public final class CorePlanConfigDefinition {

    private final String symbol;
    private final boolean activeTrade;
    private final boolean longPosition;
    private final double entryPrice;
    private final double coreTarget;
    private final int coreCount;
    private final double bufferedTarget;
    private final int partialsTaken;
    private final String tradeId;
    private final boolean reminderRequested;
    private final String requestId;
    private final String updateStatus;
    private final String error;
    private final long timestamp;

    public CorePlanConfigDefinition(
            String symbol,
            boolean activeTrade,
            boolean longPosition,
            double entryPrice,
            double coreTarget,
            int coreCount,
            double bufferedTarget,
            int partialsTaken,
            String tradeId,
            boolean reminderRequested,
            String requestId,
            String updateStatus,
            String error,
            long timestamp) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        if (cleanSymbol.isEmpty()) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (activeTrade) {
            if (!BookmapPriceNormalizer.isValidWirePrice(entryPrice)) {
                throw new IllegalArgumentException("entryPrice must be a valid wire price");
            }
            if (!BookmapPriceNormalizer.isValidWirePrice(coreTarget)) {
                throw new IllegalArgumentException("coreTarget must be a valid wire price");
            }
            if (!BookmapPriceNormalizer.isValidWirePrice(bufferedTarget)) {
                throw new IllegalArgumentException("bufferedTarget must be a valid wire price");
            }
            if (coreCount < 0 || coreCount > 7) {
                throw new IllegalArgumentException("coreCount must be from 0 to 7");
            }
            if (partialsTaken < 0 || partialsTaken > 10) {
                throw new IllegalArgumentException("partialsTaken must be from 0 to 10");
            }
        }

        this.symbol = cleanSymbol;
        this.activeTrade = activeTrade;
        this.longPosition = longPosition;
        this.entryPrice = entryPrice;
        this.coreTarget = coreTarget;
        this.coreCount = coreCount;
        this.bufferedTarget = bufferedTarget;
        this.partialsTaken = partialsTaken;
        this.tradeId = tradeId == null ? "" : tradeId;
        this.reminderRequested = reminderRequested;
        this.requestId = requestId == null ? "" : requestId;
        this.updateStatus = updateStatus == null ? "" : updateStatus;
        this.error = error == null ? "" : error;
        this.timestamp = timestamp;
    }

    public String getSymbol() { return symbol; }
    public boolean hasActiveTrade() { return activeTrade; }
    public boolean isLongPosition() { return longPosition; }
    public double getEntryPrice() { return entryPrice; }
    public double getCoreTarget() { return coreTarget; }
    public int getCoreCount() { return coreCount; }
    public double getBufferedTarget() { return bufferedTarget; }
    public int getPartialsTaken() { return partialsTaken; }
    public String getTradeId() { return tradeId; }
    public boolean isReminderRequested() { return reminderRequested; }
    public String getRequestId() { return requestId; }
    public String getUpdateStatus() { return updateStatus; }
    public String getError() { return error; }
    public long getTimestamp() { return timestamp; }
}
