package com.bookmap.plugin.rong;

/** A flat-to-open position transition detected by ViteApp. */
public final class NewPositionDefinition {

    private final String symbol;
    private final boolean longPosition;
    private final double netQuantity;
    private final double averagePrice;
    private final String eventId;
    private final long timestamp;

    public NewPositionDefinition(
            String symbol,
            boolean longPosition,
            double netQuantity,
            double averagePrice,
            String eventId,
            long timestamp) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        if (cleanSymbol.isEmpty()) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (!Double.isFinite(netQuantity) || netQuantity == 0) {
            throw new IllegalArgumentException("netQuantity must be non-zero and finite");
        }
        if (longPosition != (netQuantity > 0)) {
            throw new IllegalArgumentException("position side does not match netQuantity");
        }
        if (averagePrice != 0 && !BookmapPriceNormalizer.isValidWirePrice(averagePrice)) {
            throw new IllegalArgumentException("averagePrice must be zero or a valid wire price");
        }
        if (eventId == null || eventId.trim().isEmpty()) {
            throw new IllegalArgumentException("eventId is required");
        }
        if (timestamp <= 0) {
            throw new IllegalArgumentException("timestamp must be positive");
        }

        this.symbol = cleanSymbol;
        this.longPosition = longPosition;
        this.netQuantity = netQuantity;
        this.averagePrice = averagePrice;
        this.eventId = eventId.trim();
        this.timestamp = timestamp;
    }

    public String getSymbol() { return symbol; }
    public boolean isLongPosition() { return longPosition; }
    public double getNetQuantity() { return netQuantity; }
    public double getAveragePrice() { return averagePrice; }
    public String getEventId() { return eventId; }
    public long getTimestamp() { return timestamp; }
}
