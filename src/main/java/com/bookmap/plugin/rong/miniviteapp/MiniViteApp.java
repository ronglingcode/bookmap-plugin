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
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Session-wide executor. All session/state transitions use this monitor; I/O runs outside it. */
public final class MiniViteApp implements AutoCloseable {
    private final Api api;
    private final BiConsumer<Object, JsonObject> sender;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "bmtrader-native-execution"); thread.setDaemon(true); return thread;
    });
    private boolean enabled, exitsEnabled, entriesEnabled, busy, requiresReview, closed;
    private Object owner;
    private String epoch = "", account = "", token = "";
    private long tokenGeneration, expiresAt, lastHeartbeat, mutationBarrier;
    private final Map<String, Snapshot> snapshots = new HashMap<>();
    private final Map<String, Boolean> liveSymbols = new HashMap<>();
    private final Map<String, Supplier<String>> liveVerifiers = new HashMap<>();
    private final Set<String> legacyOperations = new HashSet<>();
    private Pending pending;
    private static final class Pending {
        final String symbol; final Set<String> orderIds;
        final String actionId;
        boolean entryInitialized;
        final Set<String> createdIds = new HashSet<>();
        Pending(String symbol, List<Request> requests, String actionId, boolean entry) {
            this.symbol = symbol; this.actionId = actionId; entryInitialized = !entry; orderIds = new HashSet<>();
            requests.stream().filter(request -> !request.orderId.isEmpty()).forEach(request -> orderIds.add(request.orderId));
        }
    }
    public MiniViteApp(BiConsumer<Object, JsonObject> sender) {
        this(new Api(), sender);
    }
    public MiniViteApp(Api api, BiConsumer<Object, JsonObject> sender) {
        this.api = api; this.sender = sender;
    }
    private JsonObject message(String type) {
        JsonObject json = new JsonObject(); json.addProperty("type", type);
        json.addProperty("version", ExecutionConfig.PROTOCOL_VERSION); json.addProperty("epoch", epoch); return json;
    }
    public synchronized JsonObject status() {
        JsonObject json = message("execution_status");
        json.addProperty("enabled", enabled); json.addProperty("exitsEnabled", exitsEnabled);
        json.addProperty("entriesEnabled", entriesEnabled);
        json.addProperty("blocked", busy || pending != null || requiresReview);
        json.addProperty("requiresReview", requiresReview);
        return json;
    }
    public synchronized void setEnabled(boolean enabled, boolean exitsEnabled) {
        setEnabled(enabled, exitsEnabled, false);
    }
    public synchronized void setEnabled(boolean enabled, boolean exitsEnabled, boolean entriesEnabled) {
        this.enabled = enabled; this.exitsEnabled = exitsEnabled; this.entriesEnabled = entriesEnabled;
        if (!enabled) revoke();
    }
    public synchronized void setLive(String symbol, boolean live) { liveSymbols.put(SymbolUtils.cleanSymbol(symbol), live); }
    public synchronized void setLiveVerifier(String symbol, BooleanSupplier verifier) {
        setLiveBlockReasonProvider(symbol, () -> verifier.getAsBoolean() ? "" : "Bookmap live data has not been verified");
    }
    public synchronized void setLiveBlockReasonProvider(String symbol, Supplier<String> verifier) {
        liveVerifiers.put(SymbolUtils.cleanSymbol(symbol), verifier);
    }
    private String liveBlockReason(String symbol) {
        Supplier<String> verifier = liveVerifiers.get(symbol);
        if (verifier == null) return liveSymbols.getOrDefault(symbol, false) ? "" : "Bookmap live data has not been verified";
        try {
            String reason = verifier.get();
            return reason == null ? "Bookmap provider live status could not be read" : reason;
        } catch (RuntimeException error) { return "Bookmap provider live status could not be read"; }
    }
    public synchronized void unregister(String symbol) {
        liveSymbols.remove(SymbolUtils.cleanSymbol(symbol)); liveVerifiers.remove(SymbolUtils.cleanSymbol(symbol));
    }
    private void revoke() {
        if (busy || pending != null || !legacyOperations.isEmpty()) requiresReview = true;
        owner = null; token = ""; account = ""; epoch = ""; snapshots.clear(); legacyOperations.clear();
        expiresAt = 0; tokenGeneration = 0; lastHeartbeat = 0;
    }
    public synchronized void disconnected(Object connection) { if (owner == connection) revoke(); }
    public synchronized boolean resetAfterBrokerReview() {
        if (busy || !legacyOperations.isEmpty()) return false;
        pending = null; requiresReview = false; revoke(); return true;
    }
    public synchronized boolean receive(Object connection, String origin, JsonObject json) {
        String type = Models.string(json, "type");
        if (!type.startsWith("execution_")) return false;
        try {
            if (type.equals("execution_hello")) {
                Models.require(enabled && !closed, "native execution disabled");
                Models.require(ExecutionConfig.ORIGINS.contains(origin), "untrusted execution origin");
                Models.require(Models.number(json, "version") == ExecutionConfig.PROTOCOL_VERSION, "unsupported execution protocol");
                Models.require(owner == null || owner == connection, "another ViteApp owns execution");
                Models.require(Models.bool(json, "live") && Models.string(json, "broker").equals("Schwab"), "live Schwab required");
                Models.require(owner != null || (!busy && pending == null), "broker review required before reconnect");
                if (owner == null) { owner = connection; epoch = UUID.randomUUID().toString(); }
                lastHeartbeat = System.currentTimeMillis(); sender.accept(connection, message("execution_session"));
                return true;
            }
            Models.require(owner != null && owner == connection && epoch.equals(Models.string(json, "epoch")), "not the execution owner");
            if (type.equals("execution_revoke")) { revoke(); return true; }
            if (type.equals("execution_token")) {
                long generation = (long) Models.number(json, "generation");
                if (generation <= tokenGeneration) return true;
                String nextAccount = Models.string(json, "accountHash");
                Models.require(nextAccount.matches("[A-Za-z0-9]+"), "invalid execution account");
                if (!account.isEmpty() && !account.equals(nextAccount)) { revoke(); return true; }
                account = nextAccount; token = Models.string(json, "accessToken");
                expiresAt = (long) Models.number(json, "expiresAt"); tokenGeneration = generation;
                Models.require(!token.isEmpty() && expiresAt > System.currentTimeMillis(), "expired execution token");
            } else if (type.equals("execution_state")) {
                lastHeartbeat = System.currentTimeMillis();
                Models.require(Models.string(json, "accountHash").equals(account), "execution state account mismatch");
                for (var element : json.getAsJsonArray("symbols")) {
                    Snapshot state = new Snapshot(element.getAsJsonObject());
                    Snapshot previous = snapshots.get(state.symbol);
                    if (previous == null || state.revision > previous.revision) snapshots.put(state.symbol, state);
                }
                if (!busy && !requiresReview && pending != null) {
                    Snapshot state = snapshots.get(pending.symbol);
                    if (state != null && state.observedAt > mutationBarrier) {
                        Set<String> ids = new HashSet<>(); state.entries.forEach(order -> ids.add(order.id));
                        state.pairs.forEach(pair -> { if (pair.limit != null) ids.add(pair.limit.id); if (pair.stop != null) ids.add(pair.stop.id); });
                        if (pending.entryInitialized && Collections.disjoint(ids, pending.orderIds)
                                && state.observedOrderIds.containsAll(pending.createdIds)) pending = null;
                    }
                }
            } else if (type.equals("execution_entry_state")) {
                if (pending != null && pending.actionId.equals(Models.string(json, "actionId"))) {
                    pending.entryInitialized = Models.bool(json, "initialized");
                    if (!pending.entryInitialized) { requiresReview = true; sender.accept(connection, status()); }
                }
            } else if (type.equals("execution_legacy_begin")) {
                String id = Models.string(json, "requestId");
                boolean allowed = !busy && pending == null && !requiresReview;
                if (allowed) legacyOperations.add(id);
                JsonObject ack = message("execution_legacy_ack"); ack.addProperty("requestId", id);
                ack.addProperty("allowed", allowed); sender.accept(connection, ack);
            } else if (type.equals("execution_legacy_end")) {
                Models.require(legacyOperations.remove(Models.string(json, "requestId")), "unknown legacy operation");
                if (Models.string(json, "outcome").equals("unknown")) requiresReview = true;
                mutationBarrier = System.currentTimeMillis(); snapshots.clear();
            }
        } catch (RuntimeException error) {
            // Never echo the inbound payload, exceptions, or credentials.
            JsonObject rejected = message("execution_rejected");
            rejected.addProperty("reason", "execution session rejected; check origin, account and protocol");
            sender.accept(connection, rejected);
        }
        return true;
    }
    public synchronized boolean route(JsonObject action) {
        String key = Models.string(action, "keyCode");
        if (key.isEmpty()) key = Models.string(action, "key_code");
        boolean shift = Models.bool(action, "shiftKey") || Models.bool(action, "shift_key");
        boolean entry = EntryHandler.supports(action, key);
        if (!entry && !KeyboardHandler.supports(key, shift)) return false;
        if (!enabled && !busy && pending == null && !requiresReview) return false;
        if (enabled && (entry ? !entriesEnabled : !key.equals("KeyC") && !exitsEnabled) && !busy && pending == null && !requiresReview) return false;
        String symbol = SymbolUtils.cleanSymbol(Models.string(action, "symbol"));
        try {
            long now = System.currentTimeMillis();
            Models.require(enabled && owner != null && !closed, "native execution is not connected");
            Models.require(!busy && pending == null && !requiresReview && legacyOperations.isEmpty(), "execution awaiting reconciliation or broker review");
            Models.require(now - lastHeartbeat <= ExecutionConfig.MAX_STATE_AGE_MS, "execution session is stale");
            String liveReason = liveBlockReason(symbol);
            Models.require(liveReason.isEmpty(), liveReason);
            Models.require(expiresAt - now > ExecutionConfig.TOKEN_MARGIN_MS && !token.isEmpty(), "execution token expired or missing");
            Snapshot state = snapshots.get(symbol);
            Models.require(state != null && now - state.observedAt <= ExecutionConfig.MAX_STATE_AGE_MS
                    && state.observedAt > mutationBarrier && state.observedAt <= now + 1000, "broker state is stale");
            if (!key.equals("KeyC")) Models.require(now - state.quoteObservedAt <= ExecutionConfig.MAX_QUOTE_AGE_MS
                    && state.quoteObservedAt > 0 && state.quoteObservedAt <= now + 1000, "market quote is stale");
            if (entry) {
                Models.require(entriesEnabled && state.entryContext != null &&
                    Models.number(state.entryContext, "observedAt") > 0 && Models.number(state.entryContext, "observedAt") <= now + 1000
                    && now - Models.number(state.entryContext, "observedAt") <= ExecutionConfig.MAX_STATE_AGE_MS,
                    "entry context stale or entries disabled");
                double seconds = Models.number(state.entryContext, "secondsSinceMarketOpen")
                    + (now - Models.number(state.entryContext, "observedAt")) / 1000.0;
                Models.require(seconds > 0 && seconds < 6.5 * 3600, "regular market session required");
            }
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
            busy = true; pending = new Pending(symbol, plan.requests, actionId, entry);
            Object capturedOwner = owner; String capturedEpoch = epoch, capturedAccount = account, capturedToken = token;
            JsonObject started = message("execution_started"); started.addProperty("actionId", actionId);
            started.addProperty("symbol", symbol); started.addProperty("action", plan.action);
            started.addProperty("buttonName", Models.string(action, "button_name"));
            if (entry) started.addProperty("entryIsLong", Models.bool(plan.entry, "isLong"));
            started.addProperty("clearPending", plan.clearPending); started.addProperty("revision", state.revision);
            sender.accept(owner, started);
            executor.execute(() -> execute(capturedOwner, capturedEpoch, capturedAccount, capturedToken, actionId, state, plan));
        } catch (IllegalArgumentException error) {
            PluginLog.action(symbol, "Native blocked: " + error.getMessage());
        } catch (RuntimeException error) {
            PluginLog.action(symbol, "Native blocked: invalid execution inputs");
        }
        return true; // Migrated failures are never broadcast as executable legacy actions.
    }
    private synchronized void guard(Object connection, String capturedEpoch, String capturedToken, String symbol, Plan plan) {
        Models.require(enabled && owner == connection && epoch.equals(capturedEpoch) && !closed
                && (plan.entry != null ? entriesEnabled : plan.action.equals("cancel_pending_entries") || exitsEnabled)
                && token.equals(capturedToken)
                && expiresAt - System.currentTimeMillis() > ExecutionConfig.TOKEN_MARGIN_MS,
                "execution session revoked before dispatch");
        String liveReason = liveBlockReason(symbol);
        Models.require(liveReason.isEmpty(), liveReason);
    }
    private void execute(Object connection, String capturedEpoch, String accountHash, String accessToken,
            String actionId, Snapshot state, Plan plan) {
        JsonArray results = new JsonArray(); boolean dispatched = false, unknown = false;
        String outcome = "accepted", reason = "";
        try {
            guard(connection, capturedEpoch, accessToken, state.symbol, plan);
            if (plan.entry == null && !plan.action.equals("cancel_pending_entries")) {
                Models.require(api.getPosition(accountHash, accessToken, state.symbol) == state.netQuantity,
                        "position changed; refresh and retry");
                // Flatten sizing depends on every protective leg. Other actions verify selected pairs only.
                for (var pair : state.pairs) {
                    boolean selected = plan.action.equals("flatten") || plan.requests.stream().anyMatch(request ->
                            pair.limit != null && pair.limit.id.equals(request.orderId)
                            || pair.stop != null && pair.stop.id.equals(request.orderId));
                    if (!selected) continue;
                    if (pair.limit != null) api.validateOrder(accountHash, accessToken, pair.limit, true);
                    if (pair.stop != null) api.validateOrder(accountHash, accessToken, pair.stop, true);
                }
            }
            for (var request : plan.requests) {
                guard(connection, capturedEpoch, accessToken, state.symbol, plan);
                if (request.original != null) api.validateOrder(accountHash, accessToken, request.original,
                        !request.method.equals("DELETE"));
                if (plan.entry != null) {
                    api.validateFlatEntry(accountHash, accessToken, state.symbol, request.body, Models.number(plan.entry, "entryPrice"));
                    Models.require(System.currentTimeMillis() - state.observedAt <= ExecutionConfig.MAX_STATE_AGE_MS
                        && System.currentTimeMillis() - state.quoteObservedAt <= ExecutionConfig.MAX_QUOTE_AGE_MS,
                        "entry inputs expired during broker preflight");
                } else if (request.body != null) {
                    double liveQuantity = api.getPosition(accountHash, accessToken, state.symbol);
                    double quantity = request.body.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().get("quantity").getAsDouble();
                    Models.require(Math.signum(liveQuantity) == Math.signum(state.netQuantity) && Math.abs(liveQuantity) >= quantity,
                            "position changed during execution; reconcile first");
                }
                guard(connection, capturedEpoch, accessToken, state.symbol, plan);
                dispatched = true;
                Api.Result result = api.mutate(accountHash, accessToken, request);
                results.add(result.toJson());
                if (!result.outcome.equals("accepted")) { outcome = result.outcome; unknown = outcome.equals("unknown"); break; }
            }
        } catch (IllegalArgumentException error) {
            outcome = dispatched ? "partial" : "rejected"; reason = error.getMessage();
        } catch (Exception error) {
            // A network exception after dispatch cannot prove whether the mutation reached the broker.
            outcome = dispatched ? "unknown" : "rejected"; unknown = dispatched;
            reason = dispatched ? "broker outcome unknown; review orders before resetting" : "broker preflight failed";
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        synchronized (this) {
            busy = false; mutationBarrier = System.currentTimeMillis();
            if (pending != null) {
                if (plan.entry != null && !outcome.equals("accepted")) pending.entryInitialized = true;
                pending.orderIds.clear();
                for (var element : results) {
                    var result = element.getAsJsonObject();
                    if (Models.string(result, "outcome").equals("accepted") && !Models.string(result, "orderId").isEmpty())
                        pending.orderIds.add(Models.string(result, "orderId"));
                    if (plan.entry != null && Models.string(result, "outcome").equals("accepted") && !Models.string(result, "newOrderId").isEmpty())
                        pending.createdIds.add(Models.string(result, "newOrderId"));
                }
            }
            if (unknown || owner != connection || !epoch.equals(capturedEpoch)) requiresReview = true;
            if (!dispatched && !requiresReview) pending = null;
            JsonObject finished = message("execution_result"); finished.addProperty("actionId", actionId);
            finished.addProperty("symbol", state.symbol); finished.addProperty("action", plan.action);
            finished.addProperty("outcome", outcome); finished.addProperty("reason", reason);
            finished.addProperty("requiresReview", requiresReview); finished.add("results", results);
            if (plan.entry != null && outcome.equals("accepted")) finished.add("entry", plan.entry);
            sender.accept(connection, finished);
            PluginLog.action(state.symbol, "Native " + plan.action + ": " + outcome + (reason.isEmpty() ? "" : " - " + reason));
        }
    }
    @Override public synchronized void close() { closed = true; revoke(); executor.shutdownNow(); }
}
