package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.Api;
import com.bookmap.plugin.rong.miniviteapp.config.ExecutionConfig;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.KeyboardHandler;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.EntryHandler;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.ExtendedHandler;
import com.bookmap.plugin.rong.miniviteapp.models.Models;
import com.bookmap.plugin.rong.miniviteapp.models.Models.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import static com.bookmap.plugin.rong.miniviteapp.models.DomainJson.array;
import static com.bookmap.plugin.rong.miniviteapp.models.DomainJson.object;

/** Single-user executor. State transitions use this monitor; I/O runs outside it. */
public final class MiniViteApp implements AutoCloseable {
    private final Api api;
    private final BiConsumer<Object, JsonObject> sender;
    private final ExecutionLog log;
    private final Function<String, String> symbolKey;
    private final ExecutorService executor = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "bmtrader-native-execution"); thread.setDaemon(true); return thread;
    });
    private boolean requiresReview, closed;
    private String reviewReason = "";
    private Object connection;
    private String account = "", token = "";
    private long expiresAt;
    private final Map<String, Snapshot> snapshots = new HashMap<>();
    private final Map<String, JsonObject> marketData = new HashMap<>();
    public MiniViteApp(BiConsumer<Object, JsonObject> sender) {
        this(new Api(), sender);
    }
    public MiniViteApp(BiConsumer<Object, JsonObject> sender, BiConsumer<String, String> log,
            Function<String, String> symbolKey) {
        this(new Api(ExecutionLog.from(log)::detail), sender, log, symbolKey);
    }
    public MiniViteApp(Api api, BiConsumer<Object, JsonObject> sender) {
        this(api, sender, (symbol, message) -> { }, symbol -> symbol == null ? "" : symbol.trim());
    }
    public MiniViteApp(Api api, BiConsumer<Object, JsonObject> sender, BiConsumer<String, String> log,
            Function<String, String> symbolKey) {
        this.api = api; this.sender = sender; this.log = ExecutionLog.from(log); this.symbolKey = symbolKey;
    }
    private JsonObject message(String type) {
        JsonObject json = new JsonObject(); json.addProperty("type", type);
        json.addProperty("version", ExecutionConfig.PROTOCOL_VERSION); return json;
    }
    public synchronized JsonObject status() {
        JsonObject json = message("execution_status");
        // Preserve internal protocol 3 status fields; all operations use this executor.
        json.addProperty("enabled", !closed);
        json.addProperty("exitsEnabled", !closed); json.addProperty("entriesEnabled", !closed);
        json.addProperty("extendedEnabled", !closed);
        json.addProperty("blocked", requiresReview);
        json.addProperty("requiresReview", requiresReview);
        json.addProperty("reason", reviewReason);
        return json;
    }
    public synchronized void unregister(String symbol) {
        snapshots.remove(symbolKey.apply(symbol));
        marketData.remove(symbolKey.apply(symbol));
    }
    public synchronized boolean resetAfterBrokerReview() {
        requiresReview = false; reviewReason = "";
        log.accept("", "Execution review block cleared by user; reset did not verify broker order state"); return true;
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
                marketData.put(symbolKey.apply(Models.string(json, "symbol")), json.deepCopy());
            } else if (type.equals("execution_entry_state")) {
                if (!Models.bool(json, "initialized")) {
                    requiresReview = true;
                    reviewReason = ExecutionDiagnostics.sanitize("entry state initialization failed: " + Models.string(json, "reason"), token, account);
                    log.accept(Models.string(json, "symbol"), "Native requires review: " + reviewReason);
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
            log.accept("", "Native update rejected: " + reason);
        }
        return true;
    }
    public synchronized boolean route(JsonObject action) {
        String key = Models.string(action, "keyCode");
        if (key.isEmpty()) key = Models.string(action, "key_code");
        boolean shift = Models.bool(action, "shiftKey") || Models.bool(action, "shift_key");
        boolean entry = EntryHandler.supports(action, key);
        String symbol = symbolKey.apply(Models.string(action, "symbol"));
        Snapshot state = snapshots.get(symbol);
        boolean sameDirection = entry && EntryHandler.matchesPositionSide(state, action);
        boolean extended = !KeyboardHandler.supports(key, shift) && !key.equals("KeyA") && !key.equals("KeyW")
                && !(entry
                && (state == null || state.entries.isEmpty()
                && (sameDirection || state.netQuantity == 0 && state.pairs.isEmpty())));
        try {
            long now = System.currentTimeMillis();
            Models.require(!closed, "native executor stopped");
            Models.require(!requiresReview, "broker review required: " + reviewReason);
            boolean directionalEntry = (key.equals("KeyB") || key.equals("KeyS")) && !entry;
            Models.require(entry || directionalEntry || key.equals("KeyA") || key.equals("KeyW") || KeyboardHandler.supports(key, shift), "unsupported native action: "
                    + (key.isEmpty() ? Models.string(action, "tradebook_id") : key));
            Models.require(state != null, "execution inputs unavailable");
            if (marketData.containsKey(symbol)) state = new Snapshot(state, marketData.get(symbol));
            if (state.entryContext != null && action.has("bookmapDayHighLow")) {
                JsonObject day = action.getAsJsonObject("bookmapDayHighLow");
                Models.require(!day.has("priceUnit") || Models.string(day, "priceUnit").equals("real"), "unsupported day-level price unit");
                double high = Models.number(day, day.has("highOfDay") ? "highOfDay" : "high");
                double low = Models.number(day, day.has("lowOfDay") ? "lowOfDay" : "low");
                if (Models.positive(high) && Models.positive(low)) {
                    JsonObject prices = new JsonObject();
                    prices.addProperty("currentPrice", state.currentPrice); prices.addProperty("bid", state.bid); prices.addProperty("ask", state.ask);
                    prices.addProperty("highOfDay", Math.max(Models.number(state.entryContext, "highOfDay"), Math.ceil(high * 100) / 100));
                    prices.addProperty("lowOfDay", Math.min(Models.number(state.entryContext, "lowOfDay"), Math.floor(low * 100) / 100));
                    state = new Snapshot(state, prices);
                }
            }
            Plan plan = entry ? EntryHandler.handleEntry(state, action, key, extended || sameDirection)
                    : directionalEntry ? EntryHandler.handleDirectionalEntry(state, action, key)
                    : key.equals("KeyA") ? ExtendedHandler.reload(state, shift, Models.number(action, "price"))
                    : key.equals("KeyW") ? ExtendedHandler.swap(state, action)
                    : KeyboardHandler.handleKeyPressed(state, key, shift, Models.number(action, "price"));
            for (String warning : plan.warnings) log.accept(symbol, warning);
            if (!key.equals("KeyC")) {
                double total = plan.requests.stream().filter(request -> request.body != null).mapToDouble(request -> closingQuantity(request.body)).sum();
                Models.require(total <= Math.abs(state.netQuantity), "closing quantity exceeds position");
            }
            Set<String> unique = new HashSet<>();
            for (var request : plan.requests) Models.require(request.orderId.isEmpty() || unique.add(request.orderId), "duplicate order in execution plan");
            String actionId = UUID.randomUUID().toString();
            String capturedAccount = account;
            if (token.isEmpty() || expiresAt <= now)
                log.accept(symbol, "Native warning: access token missing or expired; broker will decide");
            JsonObject started = message("execution_started"); started.addProperty("actionId", actionId);
            started.addProperty("symbol", symbol); started.addProperty("action", plan.action);
            started.addProperty("buttonName", Models.string(action, "button_name"));
            if (plan.entry != null) started.addProperty("entryIsLong", Models.bool(plan.entry, "isLong"));
            if (plan.action.equals("reload_partial")) {
                started.addProperty("reloadIsLong", Models.bool(state.entryContext, "reloadIsLong"));
                started.addProperty("exitPairCount", state.pairs.size());
            }
            started.addProperty("clearPending", plan.clearPending); started.addProperty("revision", state.revision);
            sender.accept(connection, started);
            log.detail(symbol, "Native " + plan.action + " planned " + plan.requests.size()
                    + " broker request(s); actionId=" + actionId + "; no requests attempted yet");
            Snapshot executionState = state;
            executor.execute(() -> execute(capturedAccount, actionId, executionState, plan));
        } catch (RuntimeException error) {
            String reason = error instanceof IllegalArgumentException && error.getMessage() != null && error.getMessage().startsWith("checkRule:")
                    ? ExecutionDiagnostics.sanitize(error.getMessage(), token, account)
                    : "build " + (entry ? "wall_reversal_entry" : key) + " plan failed: " + ExecutionDiagnostics.describe(error, token, account);
            log.accept(symbol, "Native blocked: " + reason + "; no broker requests attempted for this action");
            JsonObject blocked = message("execution_blocked"); blocked.addProperty("symbol", symbol);
            blocked.addProperty("reason", reason); sender.accept(connection, blocked);
        }
        return true;
    }
    private synchronized String guard(Plan plan) {
        Models.require(!closed, "native executor stopped before dispatch");
        return token;
    }
    private static double closingQuantity(JsonObject body) {
        if (Models.string(body, "orderStrategyType").equals("OCO")) { double quantity = 0; for (var child : body.getAsJsonArray("childOrderStrategies")) quantity = Math.max(quantity, closingQuantity(child.getAsJsonObject())); return quantity; }
        if (!body.has("orderLegCollection")) return 0;
        JsonObject leg = body.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject(); String instruction = Models.string(leg, "instruction"); return instruction.equals("SELL") || instruction.equals("BUY_TO_COVER") ? Models.number(leg, "quantity") : 0;
    }
    private static boolean isProtectedEntry(Request request) {
        return request.body != null && Models.string(request.body, "orderStrategyType").equals("TRIGGER");
    }
    private void execute(String accountHash, String actionId, Snapshot state, Plan plan) {
        JsonArray results = new JsonArray();
        int attempted = 0, acknowledged = 0, rejected = 0, uncertain = 0;
        boolean entryAccepted = false, stageFailed = false;
        List<String> reasons = new ArrayList<>();
        int cursor = 0;
        while (cursor < plan.requests.size() && !stageFailed) {
            Request first = plan.requests.get(cursor);
            try {
                if (first.delayBeforeMs > 0) Thread.sleep(first.delayBeforeMs);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                reasons.add("Native execution interrupted before next batch dispatch");
                break;
            }
            int end = cursor + 1;
            // Delays and protected entries delimit dependent workflow stages. Within a stage,
            // every cancellation/replacement/submission starts before awaiting any response.
            while (end < plan.requests.size() && plan.requests.get(end).delayBeforeMs == 0
                    && !isProtectedEntry(first) && !isProtectedEntry(plan.requests.get(end))) end++;
            List<CompletableFuture<JsonObject>> pending = new ArrayList<>();
            for (int i = cursor; i < end; i++) {
                Request request = plan.requests.get(i);
                int index = i + 1;
                pending.add(CompletableFuture.supplyAsync(
                        () -> executeRequest(accountHash, actionId, state, plan, request, index), executor));
            }
            for (int i = 0; i < pending.size(); i++) {
                JsonObject result = pending.get(i).join(); results.add(result);
                if (Models.bool(result, "attempted")) {
                    attempted++;
                    if (Models.string(result, "outcome").equals("accepted")) {
                        acknowledged++;
                        if (isProtectedEntry(plan.requests.get(cursor + i)) && plan.entry != null) entryAccepted = true;
                    } else if (Models.string(result, "outcome").equals("unknown")) uncertain++;
                    else rejected++;
                }
                if (!Models.string(result, "outcome").equals("accepted")) stageFailed = true;
                if (!Models.string(result, "reason").isEmpty()) reasons.add(Models.string(result, "reason"));
            }
            cursor = end;
        }
        boolean unknown = uncertain > 0;
        String outcome = unknown ? "unknown" : plan.requests.isEmpty() ? "no_op"
                : acknowledged == plan.requests.size() ? "accepted" : acknowledged > 0 ? "partial" : "rejected";
        String reason = String.join("; ", reasons);
        if (plan.requests.isEmpty()) reason = plan.action.equals("cancel_breakout_entries")
                ? state.entries.isEmpty() ? "No pending entry orders found in the execution snapshot"
                : "No pending entries selected by the stop-only cancellation rule"
                : "Execution plan contains no broker requests";
        if (unknown) reason += "; broker outcome unknown; review orders before resetting";
        finishExecution(actionId, state, plan, results, attempted, acknowledged, rejected, uncertain,
                entryAccepted, outcome, reason, unknown);
    }
    private JsonObject executeRequest(String accountHash, String actionId, Snapshot state, Plan plan,
            Request request, int index) {
        String accessToken = "";
        boolean dispatched = false;
        String operation = request.method + (request.orderId.isEmpty() ? " new order" : " order " + request.orderId);
        try {
            accessToken = guard(plan);
            log.detail(state.symbol, "Native " + plan.action + " actionId=" + actionId + " request "
                    + index + "/" + plan.requests.size() + ": attempting " + operation);
            dispatched = true;
            Api.Result response = isProtectedEntry(request) && request.method.equals("POST")
                    ? api.mutateEntry(accountHash, accessToken, request, state.symbol)
                    : api.mutate(accountHash, accessToken, request);
            log.detail(state.symbol, "Native " + plan.action + " actionId=" + actionId + ": " + operation
                    + " returned HTTP " + response.status + "; "
                    + (response.outcome.equals("accepted") ? "broker acknowledged request; final order state not confirmed"
                    : response.outcome.equals("rejected") ? "broker rejected request" : "broker outcome unknown")
                    + (response.newOrderId.isEmpty() ? "" : "; newOrderId=" + response.newOrderId)
                    + (response.reason.isEmpty() ? "" : "; " + response.reason));
            JsonObject result = response.toJson(); result.addProperty("attempted", true); return result;
        } catch (Exception error) {
            JsonObject result = new JsonObject();
            result.addProperty("method", request.method); result.addProperty("orderId", request.orderId);
            result.addProperty("attempted", dispatched);
            result.addProperty("outcome", dispatched ? "unknown" : "rejected");
            result.addProperty("reason", operation + " failed: "
                    + ExecutionDiagnostics.describe(error, accessToken, accountHash)
                    + (dispatched ? "; no classified broker result; broker outcome unknown; review orders before resetting" : "; request not attempted"));
            return result;
        }
    }
    private void finishExecution(String actionId, Snapshot state, Plan plan, JsonArray results,
            int attempted, int acknowledged, int rejected, int uncertain, boolean entryAccepted,
            String outcome, String reason, boolean unknown) {
        synchronized (this) {
            if (unknown) { requiresReview = true; reviewReason = reason; }
            JsonObject finished = message("execution_result"); finished.addProperty("actionId", actionId);
            finished.addProperty("symbol", state.symbol); finished.addProperty("action", plan.action);
            finished.addProperty("outcome", outcome); finished.addProperty("reason", reason);
            finished.addProperty("requiresReview", requiresReview); finished.add("results", results);
            finished.addProperty("plannedRequestCount", plan.requests.size());
            finished.addProperty("attemptedRequestCount", attempted);
            finished.addProperty("acknowledgedRequestCount", acknowledged);
            finished.addProperty("rejectedRequestCount", rejected);
            finished.addProperty("unknownRequestCount", uncertain);
            finished.addProperty("notAttemptedRequestCount", plan.requests.size() - attempted);
            // An accepted entry still needs its trade state if a subsequent old-entry cancellation fails.
            if (entryAccepted) finished.add("entry", plan.entry);
            int exitPairCount = array(object(plan.entry, "submitEntryResult"), "profitTargets").size();
            log.summary(state.symbol, "Native " + plan.action + " actionId=" + actionId + ": "
                    + (outcome.equals("accepted") ? "broker acknowledged all requests; final order state not confirmed" : outcome)
                    + "; planned=" + plan.requests.size() + "; attempted=" + attempted
                    + "; acknowledged=" + acknowledged + "; rejected=" + rejected + "; unknown=" + uncertain
                    + "; notAttempted=" + (plan.requests.size() - attempted)
                    + (exitPairCount == 0 ? "" : "; plannedExitPairs=" + exitPairCount) + (reason.isEmpty() ? "" : "; " + reason),
                    actionSummary(plan.action, outcome, attempted, acknowledged, rejected, uncertain, reason, exitPairCount));
            sender.accept(connection, finished);
        }
    }
    static String actionSummary(String action, String outcome, int attempted, int acknowledged, int rejected, int uncertain, String reason, int exitPairCount) {
        String label = action.replace('_', ' ');
        if (outcome.equals("accepted")) return label + ": broker acknowledged " + acknowledged
                + " request(s)" + (exitPairCount == 0 ? "" : " (" + exitPairCount + " planned exit pairs)")
                + "; final order state unconfirmed";
        if (outcome.equals("no_op")) return label + ": no requests sent; " + reason;
        String result = outcome.equals("unknown") ? "outcome unknown; review broker orders before resetting"
                : outcome.equals("partial") ? "partially acknowledged" : "rejected";
        return label + ": " + result + " (" + acknowledged + "/" + attempted + " acknowledged, "
                + rejected + " rejected, " + uncertain + " unknown)" + (reason.isEmpty() ? "" : "; " + reason);
    }

    @Override public synchronized void close() {
        closed = true; token = ""; account = ""; snapshots.clear(); marketData.clear(); executor.shutdownNow();
    }
}
