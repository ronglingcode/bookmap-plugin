package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.PluginLog;
import com.bookmap.plugin.rong.SymbolUtils;
import com.bookmap.plugin.rong.miniviteapp.api.schwab.Api;
import com.bookmap.plugin.rong.miniviteapp.config.ExecutionConfig;
import com.bookmap.plugin.rong.miniviteapp.controllers.KeyboardHandler;
import com.bookmap.plugin.rong.miniviteapp.controllers.EntryHandler;
import com.bookmap.plugin.rong.miniviteapp.models.Models;
import com.bookmap.plugin.rong.miniviteapp.models.Models.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Single-user executor. State transitions use this monitor; I/O runs outside it. */
public final class MiniViteApp implements AutoCloseable {
    private final Api api;
    private final BiConsumer<Object, JsonObject> sender;
    private final ExecutorService executor = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "bmtrader-native-execution"); thread.setDaemon(true); return thread;
    });
    private boolean enabled, requiresReview, closed;
    private String reviewReason = "";
    private Object connection;
    private String account = "", token = "";
    private long expiresAt;
    private final Map<String, Snapshot> snapshots = new HashMap<>();
    private final Map<String, JsonObject> marketData = new HashMap<>();
    public MiniViteApp(BiConsumer<Object, JsonObject> sender) {
        this(new Api(), sender);
    }
    public MiniViteApp(Api api, BiConsumer<Object, JsonObject> sender) {
        this.api = api; this.sender = sender;
    }
    private JsonObject message(String type) {
        JsonObject json = new JsonObject(); json.addProperty("type", type);
        json.addProperty("version", ExecutionConfig.PROTOCOL_VERSION); return json;
    }
    public synchronized JsonObject status() {
        JsonObject json = message("execution_status");
        json.addProperty("enabled", enabled);
        // Compatibility aliases for earlier ViteApp builds; both always mirror the one switch.
        json.addProperty("exitsEnabled", enabled); json.addProperty("entriesEnabled", enabled);
        json.addProperty("blocked", requiresReview);
        json.addProperty("requiresReview", requiresReview);
        json.addProperty("reason", reviewReason);
        return json;
    }
    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) { token = ""; account = ""; expiresAt = 0; snapshots.clear(); marketData.clear(); }
    }
    public synchronized void unregister(String symbol) {
        snapshots.remove(SymbolUtils.cleanSymbol(symbol));
        marketData.remove(SymbolUtils.cleanSymbol(symbol));
    }
    public synchronized boolean resetAfterBrokerReview() {
        requiresReview = false; reviewReason = ""; return true;
    }
    public synchronized boolean receive(Object connection, JsonObject json) {
        String type = Models.string(json, "type");
        if (!type.startsWith("execution_")) return false;
        this.connection = connection;
        try {
            if (type.equals("execution_token")) {
                account = Models.string(json, "accountHash"); token = Models.string(json, "accessToken");
                expiresAt = (long) Models.number(json, "expiresAt");
            } else if (type.equals("execution_state")) {
                for (var element : json.getAsJsonArray("symbols")) {
                    Snapshot state = new Snapshot(element.getAsJsonObject());
                    snapshots.put(state.symbol, state);
                }
            } else if (type.equals("execution_market_data")) {
                marketData.put(SymbolUtils.cleanSymbol(Models.string(json, "symbol")), json.deepCopy());
            } else if (type.equals("execution_entry_state")) {
                if (!Models.bool(json, "initialized")) {
                    requiresReview = true;
                    reviewReason = ExecutionDiagnostics.sanitize("entry state initialization failed: " + Models.string(json, "reason"), token, account);
                    PluginLog.action(Models.string(json, "symbol"), "Native requires review: " + reviewReason);
                    sender.accept(connection, status());
                }
            }
        } catch (RuntimeException error) {
            // Describe the parsing failure, never echo the inbound payload.
            JsonObject rejected = message("execution_rejected");
            String incomingToken = json.has("accessToken") && json.get("accessToken").isJsonPrimitive()
                    ? json.get("accessToken").getAsString() : "";
            String reason = type + " update failed: " + ExecutionDiagnostics.describe(error, token, account, incomingToken);
            rejected.addProperty("reason", reason);
            sender.accept(connection, rejected);
            PluginLog.action("Native update rejected: " + reason);
        }
        return true;
    }
    public synchronized boolean route(JsonObject action) {
        String key = Models.string(action, "keyCode");
        if (key.isEmpty()) key = Models.string(action, "key_code");
        boolean shift = Models.bool(action, "shiftKey") || Models.bool(action, "shift_key");
        boolean entry = EntryHandler.supports(action, key);
        if (!entry && !KeyboardHandler.supports(key, shift)) return false;
        if (!enabled && !requiresReview) return false;
        String symbol = SymbolUtils.cleanSymbol(Models.string(action, "symbol"));
        try {
            long now = System.currentTimeMillis();
            Models.require(enabled && !closed, "native execution disabled");
            Models.require(!requiresReview, "broker review required: " + reviewReason);
            Snapshot state = snapshots.get(symbol);
            Models.require(state != null, "execution inputs unavailable");
            if (marketData.containsKey(symbol)) state = new Snapshot(state, marketData.get(symbol));
            Plan plan = entry ? EntryHandler.handleEntry(state, action, key) : KeyboardHandler.handleKeyPressed(state, key, shift, Models.number(action, "price"));
            if (!entry && !key.equals("KeyC")) {
                double total = plan.requests.stream().filter(request -> request.body != null)
                        .mapToDouble(request -> request.body.getAsJsonArray("orderLegCollection").get(0)
                                .getAsJsonObject().get("quantity").getAsDouble()).sum();
                Models.require(total <= Math.abs(state.netQuantity), "closing quantity exceeds position");
            }
            Set<String> unique = new HashSet<>();
            for (var request : plan.requests) Models.require(request.orderId.isEmpty() || unique.add(request.orderId), "duplicate order in execution plan");
            String actionId = UUID.randomUUID().toString();
            String capturedAccount = account;
            if (token.isEmpty() || expiresAt <= now)
                PluginLog.action(symbol, "Native warning: access token missing or expired; broker will decide");
            JsonObject started = message("execution_started"); started.addProperty("actionId", actionId);
            started.addProperty("symbol", symbol); started.addProperty("action", plan.action);
            started.addProperty("buttonName", Models.string(action, "button_name"));
            if (entry) started.addProperty("entryIsLong", Models.bool(plan.entry, "isLong"));
            started.addProperty("clearPending", plan.clearPending); started.addProperty("revision", state.revision);
            sender.accept(connection, started);
            Snapshot executionState = state;
            executor.execute(() -> execute(capturedAccount, actionId, executionState, plan));
        } catch (RuntimeException error) {
            String reason = "build " + (entry ? "wall_reversal_entry" : key) + " plan failed: "
                    + ExecutionDiagnostics.describe(error, token, account);
            PluginLog.action(symbol, "Native blocked: " + reason);
            JsonObject blocked = message("execution_blocked"); blocked.addProperty("symbol", symbol);
            blocked.addProperty("reason", reason); sender.accept(connection, blocked);
        }
        return true; // Migrated failures are never broadcast as executable legacy actions.
    }
    private synchronized String guard() {
        Models.require(enabled && !closed, "native execution disabled before dispatch");
        return token;
    }
    private void execute(String accountHash,
            String actionId, Snapshot state, Plan plan) {
        JsonArray results = new JsonArray(); boolean dispatched = false, unknown = false;
        String outcome = "accepted", reason = "";
        String operation = "prepare native execution";
        String accessToken = "";
        try {
            for (var request : plan.requests) {
                String requestOperation = request.method + (request.orderId.isEmpty() ? " new order" : " order " + request.orderId);
                operation = requestOperation;
                accessToken = guard();
                if (plan.entry != null) {
                    operation = "initial-entry broker preflight";
                    api.validateFlatEntry(accountHash, accessToken, state.symbol);
                }
                operation = requestOperation;
                accessToken = guard();
                dispatched = true;
                Api.Result result = api.mutate(accountHash, accessToken, request);
                results.add(result.toJson());
                if (!result.outcome.equals("accepted")) {
                    outcome = result.outcome; unknown = outcome.equals("unknown");
                    reason = result.reason;
                    if (unknown) reason += "; broker outcome unknown; review orders before resetting";
                    break;
                }
            }
        } catch (IllegalArgumentException error) {
            outcome = dispatched ? "partial" : "rejected";
            reason = operation + " failed: " + ExecutionDiagnostics.describe(error, token, accessToken, accountHash);
        } catch (Exception error) {
            // A network exception after dispatch cannot prove whether the mutation reached the broker.
            outcome = dispatched ? "unknown" : "rejected"; unknown = dispatched;
            reason = operation + " failed: " + ExecutionDiagnostics.describe(error, token, accessToken, accountHash)
                    + (dispatched ? "; broker outcome unknown; review orders before resetting" : "; order not sent");
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        synchronized (this) {
            if (unknown) { requiresReview = true; reviewReason = reason; }
            JsonObject finished = message("execution_result"); finished.addProperty("actionId", actionId);
            finished.addProperty("symbol", state.symbol); finished.addProperty("action", plan.action);
            finished.addProperty("outcome", outcome); finished.addProperty("reason", reason);
            finished.addProperty("requiresReview", requiresReview); finished.add("results", results);
            if (plan.entry != null && outcome.equals("accepted")) finished.add("entry", plan.entry);
            sender.accept(connection, finished);
            PluginLog.action(state.symbol, "Native " + plan.action + ": " + outcome + (reason.isEmpty() ? "" : " - " + reason));
        }
    }
    @Override public synchronized void close() {
        closed = true; token = ""; account = ""; snapshots.clear(); marketData.clear(); executor.shutdownNow();
    }
}
