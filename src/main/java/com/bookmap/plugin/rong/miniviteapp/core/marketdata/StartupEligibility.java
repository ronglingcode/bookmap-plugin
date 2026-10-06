package com.bookmap.plugin.rong.miniviteapp.core.marketdata;

import com.bookmap.plugin.rong.miniviteapp.models.Candle;
import com.google.gson.*;
import java.util.ArrayList;
import java.util.List;
import static com.bookmap.plugin.rong.miniviteapp.models.DomainJson.*;

public final class StartupEligibility {
    private StartupEligibility() { }
    public static String evaluate(JsonObject plan, double price, double shares, JsonObject stats, JsonArray daily) {
        if (number(plan, "marketCapInMillions") > 0 && number(plan, "marketCapInMillions") < 500) return "configured market cap below $500M";
        double cap = Eligibility.impliedMarketCapInBillions(shares, price);
        if (!string(plan, "symbol").equals("STI") && cap > 0 && cap < 0.9) return "implied market cap below $0.9B";
        if (!Eligibility.isPremarketVolumeWhitelisted(string(plan, "symbol"))) {
            JsonObject volume = Eligibility.premarketEligibility(number(stats, "lastDayShares"), number(stats, "previousDaysSharesAverage"), 500000, 0.9, 4);
            if (!bool(volume, "hardFloorPassed")) return "premarket shares below 500000 hard floor";
            if (!bool(volume, "absolutePassed") && !bool(volume, "relativePassed")) return "premarket shares below 0.9M and 4x prior average";
        }
        if (!plan.has("rangeBoundReversalPlan")) return "";
        List<Candle> candles = new ArrayList<>(); for (JsonElement value : daily) { JsonObject c = value.getAsJsonObject(); candles.add(new Candle(string(c, "symbol"), (long) number(c, "datetime"), number(c, "open"), number(c, "high"), number(c, "low"), number(c, "close"), number(c, "volume"), number(c, "vwap"))); }
        JsonObject range = object(plan, "rangeBoundReversalPlan"); return Eligibility.validatePreviousConsolidationArea(range.has("previousConsolidationArea") ? object(range, "previousConsolidationArea") : null, candles);
    }
}
