package com.bookmap.plugin.rong.patterns;

public enum PatternType {
    OFFER_REAPPEAR("Offer Reappear", Direction.SHORT, Family.REAPPEAR),
    BID_REAPPEAR("Bid Reappear", Direction.LONG, Family.REAPPEAR),
    OFFER_STEP_DOWN("Offer Step Down", Direction.SHORT, Family.STEP),
    BID_STEP_UP("Bid Step Up", Direction.LONG, Family.STEP);

    public enum Family {
        REAPPEAR,
        STEP
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
        return this == BID_REAPPEAR
                || this == BID_STEP_UP;
    }
}
