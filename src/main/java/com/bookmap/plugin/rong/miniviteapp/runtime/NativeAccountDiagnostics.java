package com.bookmap.plugin.rong.miniviteapp.runtime;

import static com.bookmap.plugin.rong.miniviteapp.models.DomainJson.array;
import static com.bookmap.plugin.rong.miniviteapp.models.DomainJson.bool;
import static com.bookmap.plugin.rong.miniviteapp.models.DomainJson.object;
import static com.bookmap.plugin.rong.miniviteapp.models.DomainJson.string;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.bookmap.plugin.rong.miniviteapp.core.marketdata.MarketClock;
import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.AccountProjection;
import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.SchwabTime;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Local, credential-free account diagnostics for tracing native Bookmap fills. */
public final class NativeAccountDiagnostics {
    private static final DateTimeFormatter LOCAL_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
    private static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");
    private static final ZoneId EASTERN = ZoneId.of("America/New_York");

    private NativeAccountDiagnostics() { }

    public static List<String> rawOrders(JsonArray orders, String sessionDate) {
        RawCounts counts = new RawCounts();
        List<String> lines = new ArrayList<>();
        for (JsonElement item : orders) {
            if (item.isJsonObject()) collectRawFills(item.getAsJsonObject(), sessionDate, counts, lines);
        }
        lines.add(0, "Native broker orders: sessionDate=" + sessionDate
                + " fetched=" + orders.size()
                + " executionActivities=" + counts.activities
                + " fillLegs=" + counts.legs
                + " parseableFillLegs=" + counts.parseableLegs
                + " todayFillLegs=" + counts.todayLegs);
        counts.samples.sort(Comparator.comparing((RawTimeSample sample) -> sample.rawTime).reversed());
        for (int i = 0; i < Math.min(5, counts.samples.size()); i++) {
            RawTimeSample sample = counts.samples.get(i);
            lines.add("Native broker execution time sample: orderId=" + sample.orderId
                    + " symbol=" + sample.symbol
                    + " raw=" + sample.rawTime
                    + " parsed=" + times(sample.timestamp));
        }
        return lines;
    }

    public static List<String> projectedFills(JsonObject account, String sessionDate) {
        JsonObject executions = object(account, "executions");
        List<String> lines = new ArrayList<>();
        List<String> symbols = new ArrayList<>(executions.keySet());
        Collections.sort(symbols);
        int count = 0;
        for (String symbol : symbols) {
            for (JsonElement item : array(executions, symbol)) {
                if (!item.isJsonObject()) continue;
                JsonObject fill = item.getAsJsonObject();
                count++;
                lines.add("Native projected fill: symbol=" + symbol
                        + " orderId=" + string(fill, "orderID")
                        + " side=" + (bool(fill, "isBuy") ? "BUY" : "SELL")
                        + " effect=" + (bool(fill, "positionEffectIsOpen") ? "OPENING" : "CLOSING")
                        + " quantity=" + string(fill, "quantity")
                        + " price=" + string(fill, "price")
                        + " " + times(longValue(fill, "timestamp")));
            }
        }
        lines.add(0, "Native projected fills available for display: sessionDate=" + sessionDate + " count=" + count);
        return lines;
    }

    private static void collectRawFills(JsonObject order, String sessionDate,
            RawCounts counts, List<String> lines) {
        String symbol = AccountProjection.orderSymbol(order);
        lines.add("Native broker order observed: orderId=" + orderId(order) + " symbol=" + symbol
                + " status=" + string(order, "status") + " type=" + string(order, "orderType")
                + " strategy=" + string(order, "orderStrategyType") + " quantity=" + string(order, "quantity")
                + " filledQuantity=" + string(order, "filledQuantity") + " cancelable=" + string(order, "cancelable"));
        for (JsonElement item : array(order, "orderActivityCollection")) {
            if (!item.isJsonObject()) continue;
            JsonObject activity = item.getAsJsonObject();
            if (!"EXECUTION".equals(string(activity, "activityType"))
                    || !"FILL".equals(string(activity, "executionType"))) continue;
            counts.activities++;
            for (JsonElement legItem : array(activity, "executionLegs")) {
                if (!legItem.isJsonObject()) continue;
                counts.legs++;
                JsonObject leg = legItem.getAsJsonObject();
                String rawTime = string(leg, "time").replace('\r', ' ').replace('\n', ' ');
                long timestamp = parseTime(rawTime);
                counts.samples.add(new RawTimeSample(orderId(order), symbol, rawTime, timestamp));
                if (timestamp > 0) counts.parseableLegs++;
                if (timestamp <= 0 || !sessionDate.equals(MarketClock.marketTime(timestamp).date)) continue;
                counts.todayLegs++;
                lines.add("Native broker raw fill: orderId=" + orderId(order)
                        + " symbol=" + symbol
                        + " status=" + string(order, "status")
                        + " quantity=" + string(leg, "quantity")
                        + " price=" + string(leg, "price")
                        + " " + times(timestamp));
            }
        }
        for (JsonElement child : array(order, "childOrderStrategies")) {
            if (child.isJsonObject()) collectRawFills(child.getAsJsonObject(), sessionDate, counts, lines);
        }
    }

    private static String orderId(JsonObject order) {
        String id = string(order, "orderId");
        return id.isEmpty() ? string(order, "orderID") : id;
    }

    private static long parseTime(String value) {
        try { return SchwabTime.executionMillis(value); }
        catch (RuntimeException error) { return 0; }
    }

    private static long longValue(JsonObject value, String field) {
        try { return value.get(field).getAsLong(); }
        catch (RuntimeException error) { return 0; }
    }

    private static String times(long timestampMs) {
        if (timestampMs <= 0) return "time=invalid";
        Instant instant = Instant.ofEpochMilli(timestampMs);
        return "utc=" + instant
                + " pacific=" + LOCAL_TIME.format(instant.atZone(PACIFIC))
                + " eastern=" + LOCAL_TIME.format(instant.atZone(EASTERN));
    }

    private static final class RawCounts {
        private int activities;
        private int legs;
        private int parseableLegs;
        private int todayLegs;
        private final List<RawTimeSample> samples = new ArrayList<>();
    }

    private static final class RawTimeSample {
        private final String orderId;
        private final String symbol;
        private final String rawTime;
        private final long timestamp;

        private RawTimeSample(String orderId, String symbol, String rawTime, long timestamp) {
            this.orderId = orderId;
            this.symbol = symbol;
            this.rawTime = rawTime;
            this.timestamp = timestamp;
        }
    }
}
