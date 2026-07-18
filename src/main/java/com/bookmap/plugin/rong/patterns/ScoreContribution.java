package com.bookmap.plugin.rong.patterns;

import java.util.Objects;

public final class ScoreContribution {

    private final String ruleId;
    private final int points;
    private final String detail;

    public ScoreContribution(String ruleId, int points, String detail) {
        this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
        this.points = points;
        this.detail = detail == null ? "" : detail;
    }

    public String getRuleId() {
        return ruleId;
    }

    public int getPoints() {
        return points;
    }

    public String getDetail() {
        return detail;
    }
}
