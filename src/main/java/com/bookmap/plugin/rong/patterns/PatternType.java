package com.bookmap.plugin.rong.patterns;

public enum PatternType {
    OFFER_WALL_BREAKOUT("Offer Breakout", Direction.LONG, Family.BREAK),
    BID_WALL_BREAKDOWN("Bid Breakdown", Direction.SHORT, Family.BREAK),
    OFFER_REAPPEAR("Offer Reappear", Direction.SHORT, Family.REAPPEAR),
    BID_REAPPEAR("Bid Reappear", Direction.LONG, Family.REAPPEAR),
    OFFER_STEP_DOWN("Offer Step Down", Direction.SHORT, Family.STEP),
    BID_STEP_UP("Bid Step Up", Direction.LONG, Family.STEP),
    OFFER_V_SHAPE_REJECTION("Offer V-Shape", Direction.SHORT, Family.V_SHAPE),
    BID_V_SHAPE_RECOVERY("Bid V-Shape", Direction.LONG, Family.V_SHAPE);

    public enum Family {
        BREAK,
        REAPPEAR,
        STEP,
        V_SHAPE
    }

    private final String displayName;
    private final Direction direction;
    private final Family family;

    PatternType(String displayName, Direction direction, Family family) {
        this.displayName = displayName;
        this.direction = direction;
        this.family = family;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Direction getDirection() {
        return direction;
    }

    public Family getFamily() {
        return family;
    }

    public boolean isBidWallPattern() {
        return this == BID_WALL_BREAKDOWN
                || this == BID_REAPPEAR
                || this == BID_STEP_UP
                || this == BID_V_SHAPE_RECOVERY;
    }
}
