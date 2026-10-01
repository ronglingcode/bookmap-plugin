package com.bookmap.plugin.rong.miniviteapp.core.marketdata;

import com.google.gson.JsonObject;

public final class Levels {
    private Levels() {}
    public static JsonObject calculateCamPivots(double high, double low, double close) {
        double range = (high - low) * 1.1;
        double r1 = close + range / 12, r2 = close + range / 6, r3 = close + range / 4, r4 = close + range / 2;
        double s1 = close - range / 12, s2 = close - range / 6, s3 = close - range / 4, s4 = close - range / 2, step = r4 - r3;
        JsonObject json = new JsonObject();
        String[] names = {"R1", "R2", "R3", "R4", "R5", "R6", "S1", "S2", "S3", "S4", "S5", "S6"};
        double r5 = r4 + step, r6 = r5 + step, s5 = s4 - step, s6 = s5 - step;
        double[] values = {r1, r2, r3, r4, r5, r6, s1, s2, s3, s4, s5, s6};
        for (int i = 0; i < names.length; i++) json.addProperty(names[i], Math.floor(values[i] * 100 + 0.5) / 100);
        return json;
    }
}
