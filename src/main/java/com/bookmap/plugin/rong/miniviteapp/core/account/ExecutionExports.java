package com.bookmap.plugin.rong.miniviteapp.core.account;

import com.bookmap.plugin.rong.miniviteapp.core.marketdata.MarketClock;
import com.google.gson.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.bookmap.plugin.rong.miniviteapp.models.DomainJson.*;

/** Pure formatters mirrored by TypeScript core/account/executionExports.ts. */
public final class ExecutionExports {
    private ExecutionExports() {}
    public enum Format {
        SUMMARY("ThinkScript summary"), DETAILS("ThinkScript details"), CSV("Trade CSV");
        public final String label;
        Format(String label) { this.label = label; }
    }

    public static JsonArray aggregateExecutionBubbles(JsonArray fills, boolean details) {
        Map<String, List<JsonObject>> groups = new LinkedHashMap<>();
        for (JsonElement item : fills) {
            JsonObject fill = item.getAsJsonObject(); var time = MarketClock.marketTime(fill.get("timestamp").getAsLong());
            JsonArray key = new JsonArray(); key.add(string(fill, "symbol")); key.add(time.date);
            key.add((long) Math.floor(time.minutesSinceMarketOpen)); key.add(bool(fill, "isBuy"));
            if (details) key.add(clusteredPrice(Math.round(number(fill, "price") * 100) / 100.0));
            groups.computeIfAbsent(key.toString(), unused -> new ArrayList<>()).add(fill);
        }
        JsonArray result = new JsonArray();
        for (List<JsonObject> group : groups.values()) {
            if (group.size() == 1) { result.add(group.get(0).deepCopy()); continue; }
            double quantity = 0, dollars = 0;
            for (JsonObject fill : group) { quantity += number(fill, "quantity"); dollars += number(fill, "quantity") * number(fill, "price"); }
            JsonObject fill = group.get(0).deepCopy(); fill.addProperty("quantity", quantity); fill.addProperty("price", dollars / quantity); result.add(fill);
        }
        return result;
    }
    private static double clusteredPrice(double price) {
        int cents = price > 200 ? 5 : price > 100 ? 4 : price > 50 ? 3 : price > 25 ? 2 : 0;
        return cents == 0 ? price : Math.floor(price * 100 / cents) * cents / 100;
    }
    private static String decimal(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    /** Snippet for the existing study's time/BubbleGreen/BubbleRed definitions. */
    public static String executionBubbleScript(JsonArray fills) {
        StringBuilder text = new StringBuilder();
        for (JsonElement item : fills) {
            JsonObject fill = item.getAsJsonObject(); boolean buy = bool(fill, "isBuy");
            long seconds = (long) Math.floor(MarketClock.marketTime(fill.get("timestamp").getAsLong()).minutesSinceMarketOpen) * 60;
            String symbol = string(fill, "symbol").replace("\\", "\\\\").replace("\"", "\\\"");
            text.append("AddChartBubble(GetSymbol() == \"").append(symbol).append("\" and time == ").append(seconds)
                .append(", ").append(decimal(Math.round(number(fill, "price") * 100) / 100.0)).append(", \"")
                .append(buy ? "+" : "-").append(decimal(number(fill, "quantity"))).append("\", GlobalColor(\"")
                .append(buy ? "BubbleGreen" : "BubbleRed").append("\"), ").append(buy ? 0 : 1).append(");\n");
        }
        return text.toString();
    }
    public static String executionScript(JsonArray fills, boolean details) { return executionBubbleScript(aggregateExecutionBubbles(fills, details)); }
    private static String row(Object... cells) {
        List<String> values = new ArrayList<>();
        for (Object cell : cells) {
            String value = cell.toString();
            values.add(value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r") ? "\"" + value.replace("\"", "\"\"") + "\"" : value);
        }
        return String.join(",", values);
    }
    public static String executionTradesCsv(JsonArray fills, long now, ZoneId timeZone) {
        DateTimeFormatter date = DateTimeFormatter.ofPattern("M/d/uuuu", Locale.US).withZone(timeZone);
        DateTimeFormatter stamp = DateTimeFormatter.ofPattern("M/d/uuuu HH:mm:ss", Locale.US).withZone(timeZone);
        String day = date.format(Instant.ofEpochMilli(now));
        List<String> lines = new ArrayList<>(List.of(
            "Account Statement since " + day + " through " + day,
            "Cash Balance",
            "DATE,TIME,TYPE,REF #,DESCRIPTION,Misc Fees,Commissions & Fees,AMOUNT,BALANCE",
            "Futures Statements",
            "Trade Date,Exec Date,Exec Time,Type,Ref #,Description,Misc Fees,Commissions & Fees,Amount,Balance",
            "Forex Statements",
            ",Date,Time,Type,Ref #,Description,Commissions & Fees,Amount,Amount(USD),Balance",
            "Total Cash **************",
            "Account Order History",
            "Notes,,Time Placed,Spread,Side,Qty,Pos Effect,Symbol,Exp,Strike,Type,PRICE,,TIF,Status"));
        for (JsonElement item : fills) {
            JsonObject fill = item.getAsJsonObject(); boolean buy = bool(fill, "isBuy");
            lines.add(row("", "", stamp.format(Instant.ofEpochMilli(fill.get("timestamp").getAsLong())), buy ? "BUY" : "SELL",
                (buy ? "+" : "-") + decimal(number(fill, "quantity")), "TO " + (bool(fill, "positionEffectIsOpen") ? "OPEN" : "CLOSE"), string(fill, "symbol"), "", "", "ETF", "~", "MKT", "DAY", "FILLED"));
        }
        lines.add("Account Trade History"); lines.add(",Exec Time,Spread,Side,Qty,Pos Effect,Symbol,Exp,Strike,Type,Price,Net Price,Order Type");
        for (JsonElement item : fills) {
            JsonObject fill = item.getAsJsonObject(); boolean buy = bool(fill, "isBuy"); String price = decimal(number(fill, "price"));
            lines.add(row("", stamp.format(Instant.ofEpochMilli(fill.get("timestamp").getAsLong())), "STOCK", buy ? "BUY" : "SELL",
                (buy ? "+" : "-") + decimal(number(fill, "quantity")), "TO " + (bool(fill, "positionEffectIsOpen") ? "OPEN" : "CLOSE"), string(fill, "symbol"), "", "", "ETF", price, price, "MKT"));
        }
        lines.addAll(List.of("Profits and Losses", "Symbol,Description,P/L Open,P/L %,P/L Day,Margin Req,Mark Value", "Account Summary",
            "Net Liquidating Value,**************", "Stock Buying Power,**************", "Option Buying Power,**************",
            "Equity Commissions & Fees YTD,**************", "Futures Commissions & Fees YTD,**************", "Total Commissions & Fees YTD,**************"));
        return String.join("\n", lines) + "\n";
    }
    public static String export(JsonArray fills, Format format, long now, ZoneId timeZone) {
        return format == Format.CSV ? executionTradesCsv(fills, now, timeZone) : executionScript(fills, format == Format.DETAILS);
    }
}
