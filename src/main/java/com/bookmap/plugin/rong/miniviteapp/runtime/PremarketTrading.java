package com.bookmap.plugin.rong.miniviteapp.runtime;

import com.google.gson.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.*;
import com.bookmap.plugin.rong.miniviteapp.core.marketdata.MarketClock;
import com.bookmap.plugin.rong.miniviteapp.ports.SocketPort;
import static com.bookmap.plugin.rong.miniviteapp.models.DomainJson.*;

/** Independent extended-hours limits and local stops. Never invokes regular-session strategy handlers. */
public final class PremarketTrading implements AutoCloseable {
    public static final class RejectedOrderException extends RuntimeException {
        public RejectedOrderException(String message) { super(message); }
    }
    public interface Broker {
        JsonArray orders() throws Exception;
        JsonObject order(String id) throws Exception;
        double position(String symbol) throws Exception;
        void cancel(String id) throws Exception;
        String submit(JsonObject order) throws Exception;
    }
    private static final Set<String> TERMINAL = Set.of("CANCELED", "REJECTED", "EXPIRED", "FILLED", "REPLACED");
    private final Broker broker;
    private final Executor executor;
    private final SocketPort.Scheduler scheduler;
    private final LongSupplier now;
    private final BooleanSupplier enabled;
    private final Function<String, JsonObject> quotes;
    private final BiConsumer<String, String> log;
    private final Runnable refresh;
    private final Set<String> busy = new HashSet<>();
    private final OpeningReconciliation reconciliation;
    private final Map<String, Set<String>> submitted = new HashMap<>();
    private final Map<String, Double> reportedFills = new HashMap<>();
    private final PremarketOrderIndex orderIndex = new PremarketOrderIndex();
    private final PremarketStopMonitor stops;
    private final Map<String, Integer> stopExits = new HashMap<>();
    public JsonObject stopView(String symbol) { return stops.view(symbol); }
    public void selectStop(String symbol, double price, double net) {
        check(); require(symbol.matches("[A-Z][A-Z0-9./-]{0,14}"), "invalid symbol");
        stops.select(symbol, price, net);
        synchronized (this) { stopSymbols.add(symbol); }
        log.accept(symbol, "Premarket local stop selected at " + number(stops.view(symbol), "price") + "; hover + Z to adjust");
    }
    public void observePositions(JsonObject positions, JsonArray orders, long readStartedAt) {
        Set<String> symbols;
        synchronized (this) { symbols = new HashSet<>(stopSymbols); }
        for (String symbol : symbols) {
            boolean recent; synchronized (this) { recent = !submitted.getOrDefault(symbol, Set.of()).isEmpty(); }
            stops.position(symbol, number(object(positions, symbol), "netQuantity"), readStartedAt, recent || !workingOrders(orders, symbol).isEmpty());
            startStopExit(symbol);
        }
    }
    private final Set<String> stopSymbols = new HashSet<>();
    public void observeTrade(String symbol, double price, long timestamp) {
        stops.trade(symbol, price, timestamp); startStopExit(symbol);
    }
    private void startStopExit(String symbol) {
        boolean uncertain, warn = false;
        synchronized (this) {
            if (closed || busy.contains(symbol)) return;
            if (stops.exitStarted(symbol)) return;
            uncertain = reconciliation.unresolved(symbol);
            if (uncertain) {
                if (!stops.triggered(symbol)) return;
                String status = "EXIT BLOCKED: uncertain broker outcome; awaiting broker reconciliation";
                warn = !status.equals(string(stops.view(symbol), "status"));
                stops.status(symbol, status);
            } else {
                int direction = stops.claimExit(symbol); if (direction == 0) return;
                busy.add(symbol); stopExits.put(symbol, direction);
            }
        }
        if (uncertain) {
            if (warn) log.accept(symbol, "Premarket local stop EXIT BLOCKED: broker outcome uncertain; inspect broker orders and position immediately");
            return;
        }
        log.accept(symbol, "Premarket local stop triggered; canceling all symbol orders before a full-position limit exit");
        try { executor.execute(() -> prepare(symbol, "stop_exit", 0, 0, 0)); }
        catch (RuntimeException error) { failed(symbol, error); }
    }
    public void observeOrders(JsonArray orders) {
        reconciliation.observe(orders);
        orderIndex.observe(orders);
        List<String[]> notices = new ArrayList<>();
        synchronized (this) {
            for (var entry : submitted.entrySet()) for (String id : new HashSet<>(entry.getValue())) {
                JsonObject raw = find(orders, id); if (raw.size() == 0) continue;
                String status = string(raw, "status"); double filled = number(raw, "filledQuantity");
                if (filled > reportedFills.getOrDefault(id, 0.0) || TERMINAL.contains(status)) {
                    notices.add(new String[]{entry.getKey(), "Premarket order " + id + ": broker reports " + status + ", filled " + filled + " of " + number(raw, "quantity") + " shares"});
                    reportedFills.put(id, filled);
                }
                if (TERMINAL.contains(status)) { entry.getValue().remove(id); reportedFills.remove(id); }
            }
        }
        for (String[] notice : notices) log.accept(notice[0], notice[1]);
    }
    public JsonArray ordersForChart(String symbol) { return orderIndex.snapshot(symbol); }
    private volatile boolean closed;

    public PremarketTrading(Broker broker, Executor executor, SocketPort.Scheduler scheduler, LongSupplier now,
            BooleanSupplier enabled, Function<String, JsonObject> quotes, BiConsumer<String, String> log, Runnable refresh) {
        this.broker = broker; this.executor = executor; this.scheduler = scheduler; this.now = now;
        this.enabled = enabled; this.quotes = quotes;
        reconciliation = new OpeningReconciliation(now);
        this.log = com.bookmap.plugin.rong.miniviteapp.ExecutionLog.isolated(log, error -> {}); this.refresh = refresh;
        this.stops = new PremarketStopMonitor(now, enabled);
    }
    private void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
    private void check() {
        checkEnabled();
        require(MarketClock.isExtendedTradingTime(now.getAsLong()), "manual extended-hours controls are available 07:00–09:28 and 16:00–20:00 ET on weekdays");
    }
    private void checkEnabled() {
        require(!closed, "runtime stopped");
        require(enabled.getAsBoolean(), "premarket trading is disabled");
        require(!MarketClock.isRegularOrderTime(now.getAsLong()), "premarket actions are disabled during the regular order window (09:28–16:00 ET)");
    }
    public boolean dispatch(JsonObject action) {
        if (string(action, "type").equals("premarket_adjust")) return dispatchAdjustment(action);
        if (!string(action, "type").equals("premarket_order")) return false;
        String symbol = string(action, "symbol").trim();
        try {
            String operation = string(action, "operation");
            if (operation.equals("cancel_all")) checkEnabled(); else check();
            require(symbol.matches("[A-Z][A-Z0-9./-]{0,14}"), "invalid symbol");
            double fixedPrice = 0;
            if (operation.endsWith("_limit")) {
                fixedPrice = number(action, "price");
                require(validFixedPrice(fixedPrice), "enter a positive limit price at the supported price increment");
                if (operation.equals("hover_limit")) {
                    JsonObject quote = action.has("hoverQuote") ? object(action, "hoverQuote") : quotes.apply(symbol);
                    double bid = number(quote, "bid"), ask = number(quote, "ask");
                    require(bid > 0 && ask >= bid, "live bid/ask unavailable for hover + L");
                    double midpoint = bid + (ask - bid) / 2;
                    require(Math.abs(fixedPrice - midpoint) > .00000001, "hover above or below the current midpoint");
                    operation = fixedPrice > midpoint ? "sell_limit" : "buy_limit";
                }
            }
            require(Set.of("buy_now", "buy_bid", "sell_now", "sell_ask", "buy_limit", "sell_limit", "flatten", "cancel_all").contains(operation), "invalid premarket action");
            double quantity = number(action, "quantity"), offset = number(action, "offsetPercent");
            boolean cancelOnly = operation.equals("cancel_all");
            require(cancelOnly || operation.equals("flatten") || Double.isFinite(quantity) && quantity > 0 && quantity == Math.rint(quantity) && quantity <= Integer.MAX_VALUE, "enter a positive whole-share quantity");
            require(cancelOnly || fixedPrice > 0 || Double.isFinite(offset) && offset > 0 && offset < 100, "offset must be greater than 0 and less than 100 percent");
            synchronized (this) {
                require(busy.add(symbol), "previous premarket action is still running");
            }
            String capturedOperation = operation; double capturedPrice = fixedPrice;
            try { executor.execute(() -> prepare(symbol, capturedOperation, quantity, offset, capturedPrice)); }
            catch (RuntimeException error) { finish(symbol); throw error; }
        } catch (RuntimeException error) { log.accept(symbol, "Premarket blocked: " + error.getMessage()); }
        return true;
    }
    private void prepare(String symbol, String operation, double quantity, double offset, double fixedPrice) {
        try {
            if (operation.equals("cancel_all")) checkEnabled(); else check();
            JsonArray orders = broker.orders(); observeOrders(orders); List<String> working = workingOrders(orders, symbol);
            Set<String> recent; synchronized (this) { recent = new HashSet<>(submitted.getOrDefault(symbol, Set.of())); }
            for (String id : recent) {
                // A newly accepted order may not yet appear in the list endpoint. Check it by ID.
                if (working.contains(id)) continue;
                JsonObject raw = broker.order(id);
                if (!TERMINAL.contains(string(raw, "status"))) {
                    working.add(id); orders.add(raw);
                } else synchronized (this) { submitted.getOrDefault(symbol, new HashSet<>()).remove(id); }
            }
            if (operation.equals("flatten") || operation.equals("stop_exit") || operation.equals("cancel_all")) {
                for (String id : working) {
                    if (operation.equals("cancel_all")) checkEnabled(); else check();
                    // A lost response may mean the cancellation reached the broker. Never retry mutations.
                    cancelAndReconcile(symbol, id);
                }
                awaitCanceled(symbol, working, offset, 0, operation.equals("cancel_all"));
            } else {
                double net = broker.position(symbol);
                boolean buy = operation.startsWith("buy");
                boolean closing = buy && net < 0 || !buy && net > 0;
                String instruction = buy ? closing ? "BUY_TO_COVER" : "BUY" : closing ? "SELL" : "SELL_SHORT";
                double reserved = 0;
                for (String id : working) {
                    JsonObject pending = find(orders, id);
                    require(PremarketOrderIndex.isManualLimit(pending), "incompatible working order exists; use Flatten before changing sides");
                    JsonObject leg = pending.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject();
                    require(instruction.equals(string(leg, "instruction")), "opposing working order exists; use Flatten before changing sides");
                    reserved += Math.max(0, number(pending, "quantity") - number(pending, "filledQuantity"));
                }
                require(!closing || quantity + reserved <= Math.abs(net), "quantity exceeds unreserved shares in the position; close it first, then open the opposite side");
                require(!stops.triggered(symbol), "local stop triggered; new manual orders are blocked during exit");
                if (!closing) {
                    JsonObject quote = quotes.apply(symbol);
                    double entryPrice = fixedPrice > 0 ? fixedPrice : number(quote, buy ? operation.endsWith("now") ? "ask" : "bid" : operation.endsWith("now") ? "bid" : "ask");
                    stops.requireEntry(symbol, buy, entryPrice, net);
                    synchronized (this) { stopSymbols.add(symbol); }
                }
                if (fixedPrice > 0) { check(); post(symbol, limitOrder(symbol, quantity, fixedPrice, buy, closing)); }
                else submit(symbol, quantity, buy, closing, operation.endsWith("now"), offset);
                finish(symbol);
            }
        } catch (Exception error) { failed(symbol, error); }
    }
    private void awaitCanceled(String symbol, List<String> canceled, double offset, int attempt, boolean cancelOnly) {
        try {
            if (cancelOnly) checkEnabled(); else check();
            boolean pending = false;
            for (String id : canceled) {
                String status = string(broker.order(id), "status");
                require(!status.equals("REPLACED"), "order replaced elsewhere; reconcile working orders before Flatten");
                if (!TERMINAL.contains(status)) pending = true;
            }
            if (pending) {
                require(attempt < 20, "cancellation not confirmed; no new order submitted, check broker orders before retrying");
                scheduler.after(250, () -> {
                    if (closed) { finish(symbol); return; }
                    try { executor.execute(() -> awaitCanceled(symbol, canceled, offset, attempt + 1, cancelOnly)); }
                    catch (RuntimeException error) { failed(symbol, error); }
                });
                return;
            }
            // Check the complete list again: a bracket child or replacement can activate during cancellation.
            require(workingOrders(broker.orders(), symbol).isEmpty(), "working orders remain after cancellation; no new order submitted, reconcile and retry the requested action");
            if (cancelOnly) {
                log.accept(symbol, reconciliation.unresolved(symbol)
                        ? "Premarket Cancel All: known cancellations confirmed; previous submission remains unconfirmed; no new order submitted"
                        : "Premarket Cancel All: cancellation confirmed; no position-closing order submitted");
                finish(symbol); return;
            }
            double net = broker.position(symbol);
            Integer stopDirection; synchronized (this) { stopDirection = stopExits.get(symbol); }
            require(stopDirection == null || net == 0 || (net > 0 ? 1 : -1) == stopDirection, "position direction changed; local stop exit stopped for broker review");
            if (net == 0) log.accept(symbol, "Premarket Flatten: broker position is flat; working orders canceled");
            else submit(symbol, Math.abs(net), net < 0, true, true, offset);
            finish(symbol);
        } catch (Exception error) { failed(symbol, error); }
    }
    private void submit(String symbol, double quantity, boolean buy, boolean closing, boolean aggressive, double offset) throws Exception {
        check(); JsonObject quote = quotes.apply(symbol);
        double bid = number(quote, "bid"), ask = number(quote, "ask");
        require(Double.isFinite(bid) && Double.isFinite(ask) && bid > 0 && ask >= bid, "live bid/ask unavailable");
        double price = aggressive ? offset == 0 ? BigDecimal.valueOf(buy ? ask : bid).add(new BigDecimal(buy ? "0.01" : "-0.01")).doubleValue()
                : (buy ? ask * (1 + offset / 100) : bid * (1 - offset / 100)) : buy ? bid : ask;
        // Round aggressive orders outward. Passive orders retain the exact quote, including sub-dollar ticks.
        if (aggressive) price = BigDecimal.valueOf(price).setScale(price >= 1 ? 2 : 4, buy ? RoundingMode.CEILING : RoundingMode.FLOOR).doubleValue();
        require(Double.isFinite(price) && price > 0, "invalid limit price");
        JsonObject payload = limitOrder(symbol, quantity, price, buy, closing);
        check();
        post(symbol, payload);
    }
    private void post(String symbol, JsonObject payload) throws Exception {
        check();
        log.accept(symbol, "Premarket submitting " + string(payload.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject(), "instruction")
                + " " + number(payload.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject(), "quantity") + " shares, extended-hours LIMIT " + number(payload, "price"));
        require(!string(payload.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject(), "positionEffect").equals("OPENING")
                || !stops.triggered(symbol), "local stop triggered; no new opening order submitted");
        String id = submitTracked(symbol, payload);
        remember(symbol, id, payload);
        log.accept(symbol, "Premarket order " + id + " accepted; fill is not yet confirmed. Any unfilled quantity remains working.");
        synchronized (this) { if (stopExits.containsKey(symbol)) stops.status(symbol, "Exit accepted; awaiting fill (unfilled shares remain at risk)"); }
    }
    private void remember(String symbol, String id, JsonObject payload) {
        synchronized (this) { submitted.computeIfAbsent(symbol, ignored -> new HashSet<>()).add(id); }
        JsonObject raw = accepted(id, payload); orderIndex.acceptLocal(raw);
        if (string(payload.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject(), "positionEffect").equals("OPENING")) stops.entryAccepted(symbol);
    }
    private static JsonObject accepted(String id, JsonObject payload) {
        JsonObject raw = payload.deepCopy(); raw.addProperty("orderId", id); raw.addProperty("status", "WORKING");
        raw.addProperty("quantity", number(payload.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject(), "quantity")); return raw;
    }
    private static JsonObject find(JsonArray orders, String id) {
        for (JsonElement item : orders) {
            JsonObject raw = item.getAsJsonObject(); if (id.equals(string(raw, "orderId"))) return raw;
            JsonObject child = find(array(raw, "childOrderStrategies"), id); if (child.size() > 0) return child;
        }
        return new JsonObject();
    }
    public boolean chartDigit(JsonObject action) {
        String key = string(action, "keyCode"); if (key.isEmpty()) key = string(action, "key_code");
        if (!key.matches("Digit[1-9]") || !enabled.getAsBoolean() || !MarketClock.isExtendedTradingTime(now.getAsLong())) return false;
        String symbol = string(action, "symbol"), id = action.has("premarketOrderId") ? string(action, "premarketOrderId") : orderIndex.id(symbol, Integer.parseInt(key.substring(5)));
        if (!action.has("premarketOrderId") && id.isEmpty() && !MarketClock.isExtendedTradingTime(now.getAsLong())) return false;
        JsonObject edit = action.deepCopy(); edit.addProperty("type", "premarket_adjust"); edit.addProperty("orderId", id);
        return dispatchAdjustment(edit);
    }
    private boolean dispatchAdjustment(JsonObject action) {
        String symbol = string(action, "symbol"), id = string(action, "orderId"); double price = number(action, "price");
        try {
            check();
            require(!stops.triggered(symbol), "local stop triggered; order adjustments are blocked during exit");
            require(id.matches("[0-9]+"), "no numbered premarket limit order for this key");
            require(price > 0 && Double.isFinite(price), "hover a valid chart price");
            synchronized (this) {
                require(busy.add(symbol), "previous premarket action is still running");
            }
            try { orderIndex.reserve(id); executor.execute(() -> adjust(symbol, id, price)); }
            catch (RuntimeException error) { finish(symbol); throw error; }
        } catch (RuntimeException error) { log.accept(symbol, "Premarket adjustment blocked: " + error.getMessage()); }
        return true;
    }
    private void adjust(String symbol, String id, double price) {
        try {
            check();
            require(!stops.triggered(symbol), "local stop triggered; no replacement submitted");
            JsonObject raw = broker.order(id);
            require(symbol.equals(PremarketOrderIndex.symbol(raw)) && PremarketOrderIndex.isManualLimit(raw) && PremarketOrderIndex.live(raw), "numbered order changed or is no longer working");
            // Cancel first and size from its final cumulative fills, never a stale pre-PUT snapshot.
            cancelAndReconcile(symbol, id);
            awaitAdjustment(symbol, id, raw, price, 0);
        } catch (Exception error) { failed(symbol, error); }
    }
    private void awaitAdjustment(String symbol, String id, JsonObject original, double price, int attempt) {
        try {
            check();
            require(!stops.triggered(symbol), "local stop triggered; no replacement submitted");
            JsonObject raw = broker.order(id);
            if (!TERMINAL.contains(string(raw, "status"))) {
                require(attempt < 20, "cancellation not confirmed; no replacement submitted");
                scheduler.after(250, () -> {
                    try { executor.execute(() -> awaitAdjustment(symbol, id, original, price, attempt + 1)); }
                    catch (RuntimeException error) { failed(symbol, error); }
                }); return;
            }
            require(!string(raw, "status").equals("REPLACED"), "order replaced elsewhere; reconcile before moving it");
            double finalFilled = number(raw, "filledQuantity");
            require(finalFilled >= number(original, "filledQuantity") && finalFilled <= number(original, "quantity"), "inconsistent final fill quantity; no replacement submitted");
            double remaining = number(original, "quantity") - finalFilled;
            if (string(raw, "status").equals("FILLED") || remaining <= 0) { log.accept(symbol, "Premarket order filled during cancellation; no replacement submitted"); finish(symbol); return; }
            JsonObject leg = raw.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject();
            String instruction = string(leg, "instruction");
            require(Set.of("BUY", "SELL", "BUY_TO_COVER", "SELL_SHORT").contains(instruction), "unsupported order instruction");
            boolean buy = Set.of("BUY", "BUY_TO_COVER").contains(instruction), closing = Set.of("SELL", "BUY_TO_COVER").contains(instruction);
            double net = broker.position(symbol);
            require(closing ? (buy ? net < 0 : net > 0) && remaining <= Math.abs(net) : buy ? net >= 0 : net <= 0,
                    "position changed; reconcile before moving this order");
            if (closing) {
                JsonArray others = broker.orders(); double reservedShares = 0;
                for (String pendingId : workingOrders(others, symbol)) {
                    if (id.equals(pendingId)) continue;
                    JsonObject pending = find(others, pendingId);
                    require(PremarketOrderIndex.isManualLimit(pending), "incompatible working order; no replacement submitted");
                    require(instruction.equals(string(pending.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject(), "instruction")), "opposing working order; no replacement submitted");
                    reservedShares += Math.max(0, number(pending, "quantity") - number(pending, "filledQuantity"));
                }
                require(remaining + reservedShares <= Math.abs(net), "remaining closing orders exceed the position; no replacement submitted");
            }
            double rounded = roundedPrice(price); require(rounded > 0, "invalid limit price");
            JsonObject payload = limitOrder(symbol, remaining, rounded, buy, closing);
            check();
            require(!stops.triggered(symbol), "local stop triggered; no replacement submitted");
            reconciliation.observe(broker.orders());
            String replacement = submitTracked(symbol, payload);
            synchronized (this) { submitted.computeIfAbsent(symbol, ignored -> new HashSet<>()).remove(id); }
            orderIndex.replace(id, accepted(replacement, payload)); remember(symbol, replacement, payload);
            log.accept(symbol, "Premarket limit " + id + " replacement " + replacement + " accepted at " + rounded + "; fill not confirmed");
            finish(symbol);
        } catch (Exception error) { failed(symbol, error); }
    }
    public static JsonObject limitOrder(String symbol, double quantity, double price, boolean buy, boolean closing) {
        JsonObject instrument = new JsonObject(); instrument.addProperty("symbol", symbol); instrument.addProperty("assetType", "EQUITY");
        JsonObject leg = new JsonObject(); leg.add("instrument", instrument); leg.addProperty("orderLegType", "EQUITY");
        leg.addProperty("instruction", buy ? closing ? "BUY_TO_COVER" : "BUY" : closing ? "SELL" : "SELL_SHORT");
        leg.addProperty("positionEffect", closing ? "CLOSING" : "OPENING");
        leg.addProperty("quantity", quantity); JsonArray legs = new JsonArray(); legs.add(leg);
        JsonObject order = new JsonObject(); order.addProperty("session", "SEAMLESS"); order.addProperty("duration", "DAY");
        order.addProperty("orderType", "LIMIT"); order.addProperty("orderStrategyType", "SINGLE");
        order.addProperty("price", price); order.add("orderLegCollection", legs); return order;
    }
    public static List<String> workingOrders(JsonArray orders, String symbol) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonElement item : orders) collect(item.getAsJsonObject(), symbol, ids);
        return new ArrayList<>(ids);
    }
    private static void collect(JsonObject order, String symbol, Set<String> ids) {
        boolean matches = false;
        for (JsonElement item : array(order, "orderLegCollection"))
            if (symbol.equals(string(object(item.getAsJsonObject(), "instrument"), "symbol"))) matches = true;
        if (matches && !TERMINAL.contains(string(order, "status"))) {
            String id = string(order, "orderId");
            if (!id.matches("[0-9]+")) throw new IllegalArgumentException("working broker order has no usable ID");
            ids.add(id);
            // Cancel the parent once; its dependent children are reconciled on the subsequent read.
        } else for (JsonElement item : array(order, "childOrderStrategies")) collect(item.getAsJsonObject(), symbol, ids);
    }
    private void uncertain(String symbol) { log.accept(symbol, "Premarket broker outcome uncertain; reconciling broker orders; Cancel All remains available"); }
    private String submitTracked(String symbol, JsonObject payload) throws Exception {
        require(!reconciliation.unresolved(symbol), "broker outcome uncertain; awaiting matching broker order; Cancel All remains available");
        OpeningReconciliation.Intent intent = reconciliation.begin(symbol, payload);
        try { String id = broker.submit(payload); reconciliation.complete(intent, false); return id; }
        catch (Exception error) {
            boolean unknown = !(error instanceof RejectedOrderException); reconciliation.complete(intent, unknown);
            if (unknown) uncertain(symbol); throw error;
        }
    }
    private void failed(String symbol, Exception error) {
        // Vendor exceptions are sanitized by the enclosing broker adapter; never print request/credential data.
        synchronized (this) { if (stopExits.containsKey(symbol)) stops.status(symbol, "EXIT STOPPED: " + error.getMessage() + "; inspect broker and use Flatten"); }
        log.accept(symbol, "Premarket action stopped: " + error.getMessage()); finish(symbol);
    }
    private static double roundedPrice(double price) { return BigDecimal.valueOf(price).setScale(price >= 1 ? 2 : 4, RoundingMode.HALF_UP).doubleValue(); }
    public static boolean validFixedPrice(double price) { return Double.isFinite(price) && price > 0 && price == roundedPrice(price); }
    public static double snapChartPrice(double price) {
        if (!Double.isFinite(price) || price <= 0) throw new IllegalArgumentException("Hover a positive chart price");
        double snapped = roundedPrice(price);
        if (snapped <= 0) throw new IllegalArgumentException("Hover a valid chart price increment");
        return snapped;
    }
    private void cancelAndReconcile(String symbol, String id) throws Exception {
        try { broker.cancel(id); }
        catch (Exception error) {
            try {
                String status = string(broker.order(id), "status");
                if (TERMINAL.contains(status) && !status.equals("REPLACED")) {
                    log.accept(symbol, "Cancellation did not succeed, but broker confirmed order " + id + " is " + status + "; reconciling exposure"); return;
                }
            } catch (Exception readError) { error.addSuppressed(readError); }
            if (!(error instanceof RejectedOrderException)) uncertain(symbol); throw error;
        }
    }
    private void finish(String symbol) {
        orderIndex.release(symbol); synchronized (this) { busy.remove(symbol); stopExits.remove(symbol); }
        if (!closed) {
            startStopExit(symbol);
            try { refresh.run(); } catch (RuntimeException error) { log.accept(symbol, "Premarket account refresh deferred; retry on next poll"); }
        }
    }
    public synchronized boolean isBusy(String symbol) { return busy.contains(symbol); }
    @Override public void close() { closed = true; stops.close(); }
}
