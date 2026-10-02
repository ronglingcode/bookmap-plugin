package com.bookmap.plugin.rong.miniviteapp.runtime;

import com.bookmap.plugin.rong.miniviteapp.MiniViteApp;
import com.bookmap.plugin.rong.miniviteapp.ExecutionDiagnostics;
import com.bookmap.plugin.rong.miniviteapp.core.account.TradeLedger;
import com.bookmap.plugin.rong.miniviteapp.core.account.ExecutionExports;
import com.bookmap.plugin.rong.miniviteapp.core.configuration.TradingConfig;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.ExecutionInputs;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.Workflows;
import com.bookmap.plugin.rong.miniviteapp.core.marketdata.MarketClock;
import com.bookmap.plugin.rong.miniviteapp.core.marketdata.StartupEligibility;
import com.bookmap.plugin.rong.miniviteapp.core.state.TradeState;
import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.*;
import com.bookmap.plugin.rong.miniviteapp.models.Trade;
import com.bookmap.plugin.rong.miniviteapp.ports.*;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import static com.bookmap.plugin.rong.miniviteapp.models.DomainJson.*;

/** Single-app orchestration. State locks are short; network and executor dispatch stay outside. */
public final class TradingRuntime implements AutoCloseable {
    public interface Events {
        void message(JsonObject message);
        void log(String symbol, String message);
        void notify(String symbol, String message);
        default void status(String source, String status) { }
    }
    private final Object lock = new Object();
    private final OAuth oauth;
    private final CredentialPort credentials;
    private final ReadApi reads;
    private final com.bookmap.plugin.rong.miniviteapp.libraries.firestore.Api firestore;
    private final com.bookmap.plugin.rong.miniviteapp.libraries.massive.Api massive;
    private final SocketPort sockets;
    private final SocketPort.Scheduler scheduler;
    private final Executor executor;
    private final LongSupplier now;
    private final Events events;
    private final MiniViteApp execution;
    private final MarketLoader market;
    private final String massiveKey;
    private final List<Runnable> timers = new ArrayList<>();
    private final AtomicBoolean accountReading = new AtomicBoolean(), accountAgain = new AtomicBoolean(), configReading = new AtomicBoolean(), tokenReading = new AtomicBoolean();
    private final Set<String> dirty = new HashSet<>();
    private final Set<String> accountSymbols = new LinkedHashSet<>();
    private final Map<String, JsonObject> quotes = new HashMap<>(), manual = new HashMap<>(), histories = new HashMap<>();
    private final Map<String, String> eligibility = new HashMap<>();
    private final Map<String, String> pendingReplacements = new HashMap<>();
    private final Map<String, Long> reminders = new HashMap<>(), entryWarnings = new HashMap<>();
    private JsonObject account, ledger, policy = ExecutionInputs.defaultPolicy();
    private TradingConfig.Result config;
    private TradeState state;
    private MarketStreams streams;
    private CompletableFuture<Void> persistence = CompletableFuture.completedFuture(null);
    private volatile boolean stopped;
    private long revision, nextAccountRead;
    private Runnable accountRetry;
    public TradingRuntime(HttpPort http, CredentialPort credentials, JsonObject sections, SocketPort sockets, SocketPort.Scheduler scheduler,
            Executor executor, LongSupplier now, Events events) {
        this.credentials = credentials; this.sockets = sockets; this.scheduler = scheduler; this.executor = executor; this.now = now; this.events = events;
        oauth = new OAuth(http, credentials, java.net.URI.create("https://api.schwabapi.com/v1/oauth/token"), now);
        HttpPort authenticatedReads = (uri, method, headers, body) -> {
            HttpPort.Response response = http.request(uri, method, headers, body);
            if (response.status == 401 && method.equals("GET")) {
                Map<String, String> updated = new LinkedHashMap<>(headers); updated.put("Authorization", "Bearer " + oauth.accessToken(true));
                response = http.request(uri, method, updated, body);
            }
            if (response.status == 429 && method.equals("GET")) {
                long delay = 60000; try { delay = Math.max(3000, (long) (Double.parseDouble(response.header("Retry-After")) * 1000)); } catch (RuntimeException ignored) { }
                synchronized (lock) { nextAccountRead = Math.max(nextAccountRead, now.getAsLong() + delay); }
            }
            if (!method.equals("GET") && uri.getPath().contains("/orders")) {
                events.log("", "Broker order " + method + " returned HTTP " + response.status);
            }
            return response;
        };
        reads = new ReadApi(authenticatedReads);
        JsonObject firebase = object(sections, "firebaseConfig");
        firestore = new com.bookmap.plugin.rong.miniviteapp.libraries.firestore.Api(http, string(firebase, "projectId"), () -> string(firebase, "apiKey"));
        massiveKey = string(object(sections, "massive"), "apiKey"); massive = new com.bookmap.plugin.rong.miniviteapp.libraries.massive.Api(http, () -> massiveKey);
        market = new MarketLoader(massive, executor, now);
        if (object(sections, "tradingPolicy").has("coreTargetEnabled")) policy.addProperty("coreTargetEnabled", bool(object(sections, "tradingPolicy"), "coreTargetEnabled"));
        execution = new MiniViteApp(new Api(authenticatedReads, this.events::log), (connection, message) -> executionEvent(message), this.events::log, String::trim);
    }
    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            try {
                if (massiveKey.isBlank()) throw new IllegalArgumentException("Massive API key missing from local secrets");
                TradingConfig.Result loaded = TradingConfig.readTradingConfig(firestore.fetchConfigData(), 1);
                validateProfile(loaded.profile);
                String token = oauth.accessToken(false), date = MarketClock.marketTime(now.getAsLong()).date;
                JsonObject raw = reads.getAccount(token);
                JsonArray orders = reads.getOrders(accountHash(), token, date, false);
                JsonObject projected = AccountProjection.projectAccount(raw, orders, date, this::price);
                JsonObject restored = firestore.getTradingState(loaded.profile);
                synchronized (lock) {
                    if (stopped) return; config = loaded; account = projected; ledger = TradeLedger.projectTradeLedger(object(projected, "executions"), number(policy, "dailyMaxLoss"));
                    state = new TradeState(date, number(projected, "currentBalance"), now.getAsLong(), restored);
                }
                publishToken(); startStreamsAndHistory(); publishAccount();
                repeat(30000, this::refreshToken); repeat(15000, this::refreshAccount); repeat(60000, this::refreshConfig); repeat(100, this::publishDirty);
                repeat(1000, this::pendingJobs); repeat(20000, this::disciplineJobs);
                events.log("", "Native trading runtime started; local credentials, Firestore, Massive and Schwab");
            } catch (Exception error) {
                events.status("schwab", "startup failed");
                events.status("massive", "startup failed");
                failure("", "Native startup", error);
                throw new CompletionException(error);
            }
        }, executor);
    }
    private static void validateProfile(String profile) { if (!profile.equals("schwab") && !profile.equals("momentumSimple")) throw new IllegalArgumentException("Native equity runtime requires schwab or momentumSimple profile"); }
    private JsonObject selectedPlan(String symbol) { return config != null && config.symbols.contains(symbol) ? config.plan(symbol) : null; }
    private String accountHash() throws Exception { String account = string(credentials.loadSchwab(), "accountHashValue"); if (account.isEmpty()) throw new IllegalArgumentException("Schwab accountHashValue missing from local secrets"); return account; }
    private double price(String symbol) { var value = market.getState(symbol); double price = value == null ? 0 : number(value.metrics(), "currentPrice"); if (price > 0) return price; synchronized (lock) { JsonObject quote = quotes.get(symbol); double bid = number(quote, "bidPrice"), ask = number(quote, "askPrice"); return bid > 0 && ask > 0 ? (bid + ask) / 2 : Math.max(bid, ask); } }
    private void publishToken() throws Exception {
        if (stopped) return; JsonObject value = credentials.loadSchwab(), token = message("execution_token"); token.addProperty("accountHash", accountHash()); token.addProperty("accessToken", string(value, "access_token")); token.addProperty("expiresAt", number(value, "expires_at")); execution.receive(this, token);
    }
    public void refreshToken() {
        if (stopped || !tokenReading.compareAndSet(false, true)) return;
        executor.execute(() -> { try { oauth.accessToken(false); publishToken(); } catch (Exception error) { failure("", "Token refresh", error); } finally { tokenReading.set(false); } });
    }
    public void exchangeAuthorizationCode(String callbackUrl) {
        if (stopped) return;
        executor.execute(() -> { try { oauth.exchangeAuthorizationCode(callbackUrl); if (stopped) return; events.log("", "Schwab authorization saved locally"); boolean initialized; synchronized (lock) { initialized = config != null && state != null; } if (!initialized) { start(); return; } publishToken(); if (streams != null) streams.close(); startStreamsAndHistory(); refreshAccount(); } catch (Exception error) { failure("", "Schwab authorization", error); } });
    }
    public java.net.URI authorizationUrl() throws Exception { return oauth.authorizationUrl(); }
    private void startStreamsAndHistory() {
        List<String> symbols; synchronized (lock) { if (stopped) return; symbols = new ArrayList<>(config.symbols); }
        // Register loading buffers before subscribing, so history/live overlap never loses prints.
        symbols.forEach(this::loadMarket);
        AtomicBoolean massiveReady = new AtomicBoolean(), massiveTradeReceived = new AtomicBoolean();
        MarketStreams next = new MarketStreams(sockets, scheduler, executor, symbols, () -> massiveKey,
            () -> { String token = oauth.accessToken(false); publishToken(); return new MarketStreams.Credentials(reads.getStreamerInfo(token), token); }, new MarketStreams.Events() {
                public void trade(Trade trade) {
                    if (massiveTradeReceived.compareAndSet(false, true)) events.status("massiveStream", "receiving trades");
                    if (market.acceptTrade(trade)) synchronized (lock) { dirty.add(trade.symbol); }
                }
                public void quote(JsonObject quote) { String symbol = string(quote, "symbol"); synchronized (lock) { JsonObject value = quotes.computeIfAbsent(symbol, key -> new JsonObject()); quote.entrySet().forEach(entry -> value.add(entry.getKey(), entry.getValue())); dirty.add(symbol); } }
                public void activity(JsonArray values) { refreshAccount(); }
                public void ready(String source) { if (source.equals("massive")) { massiveTradeReceived.set(false); events.status("massiveStream", "connected; waiting for trades"); if (massiveReady.getAndSet(true)) symbols.forEach(TradingRuntime.this::loadMarket); } else refreshAccount(); }
                public void status(String source, String status) {
                    if (!stopped) {
                        events.status(source.equals("massive") ? "massiveStream" : source, status);
                        events.log("", source + ": " + status);
                    }
                }
            });
        synchronized (lock) { if (stopped) { next.close(); return; } streams = next; }
        next.start();
    }
    private void loadMarket(String symbol) {
        JsonObject plan; synchronized (lock) { if (stopped || config == null) return; plan = selectedPlan(symbol); }
        if (plan == null) return;
        synchronized (lock) { eligibility.put(symbol, "startup eligibility pending"); }
        events.status("massiveHistory", "loading");
        JsonObject correction = object(plan, "vwapCorrection");
        market.load(symbol, MarketClock.marketTime(now.getAsLong()).date, number(plan, "marketCapInMillions"), number(correction, "volumeSum"), number(correction, "tradingSum"))
            .whenComplete((loaded, error) -> {
                if (stopped) return; if (error != null) { events.status("massiveHistory", "failed"); failure(symbol, "Market history", error); return; }
                double shares = 0; try { shares = massive.getSharesOutstanding(symbol); } catch (Exception referenceError) { failure(symbol, "Shares reference (using zero fallback)", referenceError); }
                if (stopped) return;
                String reason = StartupEligibility.evaluate(plan, number(loaded.state.metrics(), "currentPrice"), shares, object(loaded.history, "premarketDollarCollection"), array(loaded.history, "dailyBars"));
                loaded.history.addProperty("sharesOutstanding", shares);
                synchronized (lock) { if (selectedPlan(symbol) == null || !string(loaded.state.snapshot(), "date").equals(MarketClock.marketTime(now.getAsLong()).date)) return; histories.put(symbol, loaded.history); eligibility.put(symbol, reason); dirty.add(symbol); }
                events.status("massiveHistory", "ready");
                if (!reason.isEmpty()) events.notify(symbol, "Entry blocked: " + reason);
                publishInputs(symbol); events.message(view(symbol, "market_ready"));
            });
    }
    public void refreshAccount() {
        if (stopped) return;
        synchronized (lock) {
            long delay = nextAccountRead - now.getAsLong();
            if (delay > 0) { if (accountRetry == null) accountRetry = scheduler.after(delay, () -> { synchronized (lock) { accountRetry = null; } refreshAccount(); }); return; }
        }
        if (!accountReading.compareAndSet(false, true)) { accountAgain.set(true); return; }
        synchronized (lock) { nextAccountRead = now.getAsLong() + 3000; }
        executor.execute(() -> {
            try {
                String token = oauth.accessToken(false), date = MarketClock.marketTime(now.getAsLong()).date;
                JsonObject raw = reads.getAccount(token); JsonArray orders = reads.getOrders(accountHash(), token, date, false);
                JsonObject projected = AccountProjection.projectAccount(raw, orders, date, this::price);
                synchronized (lock) { if (stopped) return; account = projected; ledger = TradeLedger.projectTradeLedger(object(projected, "executions"), number(policy, "dailyMaxLoss")); }
                publishToken(); publishAccount(); disciplineJobs(); accountNotifications();
            } catch (Exception error) { failure("", "Account refresh", error); accountAgain.set(true); }
            finally { accountReading.set(false); if (accountAgain.getAndSet(false)) refreshAccount(); }
        });
    }
    public void refreshConfig() {
        if (stopped || !configReading.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                TradingConfig.Result loaded = TradingConfig.readTradingConfig(firestore.fetchConfigData(), 1); validateProfile(loaded.profile);
                String date = MarketClock.marketTime(now.getAsLong()).date; boolean rollover;
                synchronized (lock) { rollover = !string(state.snapshot(), "date").equals(TradeState.dateLabel(date)) && !string(state.snapshot(), "date").equals(date); }
                JsonObject restored = rollover ? firestore.getTradingState(loaded.profile) : null;
                boolean restart; MarketStreams previous; List<String> removed;
                synchronized (lock) {
                    if (stopped) return;
                    if (!loaded.profile.equals(config.profile)) throw new IllegalArgumentException("Profile changed; restart native runtime to load its trade state");
                    restart = rollover || !loaded.symbols.equals(config.symbols); previous = streams; removed = new ArrayList<>(config.symbols); removed.removeAll(loaded.symbols); config = loaded;
                    if (rollover) { state = new TradeState(date, number(account, "currentBalance"), now.getAsLong(), restored); histories.clear(); eligibility.clear(); pendingReplacements.clear(); reminders.clear(); entryWarnings.clear(); }
                    for (String symbol : loaded.symbols) if (histories.containsKey(symbol)) { JsonObject history = histories.get(symbol); eligibility.put(symbol, StartupEligibility.evaluate(loaded.plan(symbol), price(symbol), number(history, "sharesOutstanding"), object(history, "premarketDollarCollection"), array(history, "dailyBars"))); }
                }
                if (rollover) for (String symbol : loaded.symbols) market.forget(symbol);
                removed.forEach(symbol -> { execution.unregister(symbol); market.forget(symbol); synchronized (lock) { histories.remove(symbol); eligibility.remove(symbol); manual.remove(symbol); } events.message(view(symbol, "account_ready")); });
                if (restart) { if (previous != null) previous.close(); startStreamsAndHistory(); }
                publishAccount();
            } catch (Exception error) { failure("", "Config refresh", error); }
            finally { configReading.set(false); }
        });
    }
    private void publishAccount() { List<String> symbols; synchronized (lock) { if (stopped || config == null) return; accountSymbols.addAll(config.symbols); for (String field : new String[]{"positions", "entryOrders", "exitPairs", "executions"}) accountSymbols.addAll(object(account, field).keySet()); symbols = new ArrayList<>(accountSymbols); }
        symbols.forEach(symbol -> { publishInputs(symbol); events.message(view(symbol, "account_ready")); }); }
    private void publishDirty() { List<String> symbols; synchronized (lock) { if (stopped) return; symbols = new ArrayList<>(dirty); dirty.clear(); } symbols.forEach(symbol -> { events.message(view(symbol, "market_update")); priceNotifications(symbol); }); }
    public JsonObject view(String symbol, String type) {
        JsonObject value = message(type); value.addProperty("symbol", symbol); value.addProperty("timestamp", now.getAsLong());
        if (type.equals("market_update")) { var loaded = market.getState(symbol); if (loaded != null) value.add("market", loaded.metrics()); return value; }
        synchronized (lock) { if (config != null) { JsonObject plan = selectedPlan(symbol); if (plan != null) value.add("plan", plan.deepCopy()); value.add("tradingSettings", config.tradingSettings.deepCopy()); value.add("policy", policy.deepCopy()); }
            if (account != null) value.add("account", account.deepCopy()); if (ledger != null) value.add("ledger", ledger.deepCopy()); if (state != null) value.add("state", state.snapshot()); if (histories.containsKey(symbol)) value.add("history", histories.get(symbol).deepCopy()); }
        var loaded = market.getState(symbol); if (loaded != null) value.add("market", loaded.snapshot()); return value;
    }
    private void publishInputs(String symbol) {
        JsonObject input; var loaded = market.getState(symbol);
        synchronized (lock) {
            if (stopped || state == null || config == null || account == null) return;
            JsonObject plan = selectedPlan(symbol);
            if (plan == null || loaded == null) input = exitInputs(symbol);
            else {
            JsonArray stocks = new JsonArray(); config.symbols.forEach(stocks::add); JsonObject markets = new JsonObject(); config.symbols.forEach(stock -> { JsonObject prices = new JsonObject(); prices.addProperty("currentPrice", price(stock)); markets.add(stock, prices); });
            input = ExecutionInputs.create(symbol, plan, loaded.snapshot(), quotes.getOrDefault(symbol, new JsonObject()), account, ledger, state, stocks, markets, manual.getOrDefault(symbol, new JsonObject()), now.getAsLong(), ++revision, policy);
            String reason = eligibility.getOrDefault(symbol, "startup eligibility pending");
            if (!reason.isEmpty()) input.getAsJsonObject("entryContext").addProperty("watchlistBlockReason", reason);
            }
        }
        JsonObject update = message("execution_state"); JsonArray symbols = new JsonArray(); symbols.add(input); update.add("symbols", symbols); execution.receive(this, update);
    }
    private JsonObject exitInputs(String symbol) {
        JsonObject result = message("exit_inputs"), position = object(object(account, "positions"), symbol); boolean isLong = number(position, "netQuantity") > 0; JsonObject active = state.direction(symbol, isLong);
        result.addProperty("symbol", symbol); result.addProperty("revision", ++revision); result.addProperty("netQuantity", number(position, "netQuantity")); result.addProperty("averagePrice", number(position, "averagePrice"));
        result.addProperty("currentPrice", price(symbol)); result.addProperty("bid", number(quotes.get(symbol), "bidPrice")); result.addProperty("ask", number(quotes.get(symbol), "askPrice"));
        result.addProperty("batchCount", number(policy, "batchCount")); result.addProperty("splitPartials", !bool(object(active, "submitEntryResult"), "isSingleOrder")); result.addProperty("hasPlan", bool(active, "hasValue")); result.addProperty("entryPrice", number(active, "entryPrice"));
        result.addProperty("coreTarget", number(object(active, "plan"), "coreTarget")); result.addProperty("coreCount", number(object(active, "plan"), "coreCount")); result.addProperty("coreRuleEnabled", bool(policy, "coreTargetEnabled")); result.addProperty("rulesSupported", true);
        result.add("entries", array(object(account, "entryOrders"), symbol).deepCopy()); JsonArray pairs = com.bookmap.plugin.rong.miniviteapp.core.controllers.NativeViews.sortedExitPairs(array(object(account, "exitPairs"), symbol)); int index = 0; for (JsonElement item : pairs) item.getAsJsonObject().addProperty("originalPartial", Math.max(0, number(policy, "batchCount") - pairs.size()) + ++index); result.add("pairs", pairs); return result;
    }
    public boolean dispatch(JsonObject action) {
        if (stopped) return false; String symbol = string(action, "symbol").trim(), type = string(action, "type"), key = string(action, "keyCode"); if (key.isEmpty()) key = string(action, "key_code");
        try {
            if (type.equals("core_plan_update")) { for (String field : new String[]{"coreTarget", "coreCount"}) if (!action.has(field) || !action.get(field).isJsonPrimitive() || !action.getAsJsonPrimitive(field).isNumber() || !Double.isFinite(action.get(field).getAsDouble())) throw new IllegalArgumentException("Invalid core plan input: " + field); synchronized (lock) { if (!bool(policy, "coreTargetEnabled") || state == null || number(object(object(account, "positions"), symbol), "netQuantity") == 0) throw new IllegalArgumentException("No active core plan"); boolean isLong = number(object(object(account, "positions"), symbol), "netQuantity") > 0; state.updateCorePlan(symbol, isLong, Math.round(number(action, "coreTarget") * 100) / 100.0, number(action, "coreCount")); }
                persistState(); JsonObject view = view(symbol, "command_state"); view.addProperty("requestId", string(action, "requestId")); view.addProperty("updateStatus", "success"); events.message(view); return true; }
            if (type.equals("manual_inputs") || key.equals("KeyZ") || key.equals("Space")) {
                synchronized (lock) { JsonObject value = manual.computeIfAbsent(symbol, stock -> new JsonObject());
                    if (key.equals("Space")) { for (String field : new String[]{"customEntryPrice", "customStopLong", "customStopShort"}) value.remove(field); }
                    else if (key.equals("KeyZ")) { double price = number(action, "price"); if (!(price > 0)) throw new IllegalArgumentException("Hover price unavailable for custom stop"); value.addProperty("customStopLong", price); value.addProperty("customStopShort", price); if (state != null) { double net = number(object(object(account, "positions"), symbol), "netQuantity"); if (net != 0) { var loaded = market.getState(symbol); double extreme = loaded == null ? 0 : number(loaded.metrics(), net > 0 ? "lowOfDay" : "highOfDay"); state.direction(symbol, net > 0).addProperty("coreInvalidationLevel", extreme > 0 ? net > 0 ? Math.min(extreme, price) : Math.max(extreme, price) : price); } } }
                    else for (String field : new String[]{"customEntryPrice", "customStopLong", "customStopShort", "fixedQuantity"}) if (action.has(field)) { if (!action.get(field).isJsonPrimitive() || !action.getAsJsonPrimitive(field).isNumber() || !Double.isFinite(action.get(field).getAsDouble()) || action.get(field).getAsDouble() < 0) throw new IllegalArgumentException("Invalid manual input: " + field); value.add(field, action.get(field)); }
                } persistState(); events.message(view(symbol, "command_state")); return true;
            }
            if (key.equals("KeyE") || key.equals("KeyR") || key.equals("KeyV")) { events.log(symbol, key + " is disabled or has no active tradebook in the current browser profile"); return true; }
            if (!string(action, "retest_warning").isEmpty()) events.notify(symbol, string(action, "retest_warning"));
            publishInputs(symbol); return execution.route(action);
        } catch (RuntimeException error) { failure(symbol, "Native command", error); if (type.equals("core_plan_update")) { JsonObject view = view(symbol, "command_state"); view.addProperty("requestId", string(action, "requestId")); view.addProperty("updateStatus", "error"); view.addProperty("error", error.getMessage()); events.message(view); } return true; }
    }
    public JsonObject manualInputs(String symbol) { synchronized (lock) { return manual.getOrDefault(symbol, new JsonObject()).deepCopy(); } }
    /** Snapshot the whole cached account, including symbols with no attached chart. No vendor reads. */
    public String exportExecutions(ExecutionExports.Format format) {
        JsonObject executions;
        synchronized (lock) {
            if (stopped || account == null) throw new IllegalStateException("Native account is not ready");
            executions = object(account, "executions").deepCopy();
        }
        long timestamp = now.getAsLong(); String date = MarketClock.marketTime(timestamp).date;
        JsonArray fills = new JsonArray();
        for (JsonElement values : executions.asMap().values()) for (JsonElement fill : values.getAsJsonArray())
            if (MarketClock.marketTime(fill.getAsJsonObject().get("timestamp").getAsLong()).date.equals(date)) fills.add(fill);
        if (fills.isEmpty()) throw new IllegalStateException("No cached executions for today's market session");
        return ExecutionExports.export(fills, format, timestamp, java.time.ZoneId.systemDefault());
    }
    public void resetAfterBrokerReview() { execution.resetAfterBrokerReview(); }
    private void executionEvent(JsonObject result) {
        String symbol = string(result, "symbol");
        if ((string(result, "type").equals("execution_result") && string(result, "action").equals("refresh_pending_entry") && !string(result, "outcome").equals("accepted") && !string(result, "outcome").equals("unknown")) || string(result, "type").equals("execution_blocked")) synchronized (lock) { pendingReplacements.remove(symbol); }
        if (result.has("entry")) {
            boolean changed; synchronized (lock) { JsonObject plan = config == null ? null : selectedPlan(symbol); changed = state != null && state.acceptEntry(symbol, object(result, "entry"), object(plan, "atr"), now.getAsLong()); }
            if (changed) persistState();
        }
        events.message(result.deepCopy());
        if (string(result, "type").equals("execution_result")) { publishInputs(symbol); refreshAccount(); }
    }
    private void pendingJobs() {
        List<JsonObject> actions = new ArrayList<>(); List<String[]> notices = new ArrayList<>(); long time = now.getAsLong(); double seconds = MarketClock.marketTime(time).minutesSinceMarketOpen * 60;
        synchronized (lock) { if (stopped || config == null || account == null) return;
            for (String symbol : config.symbols) { var loaded = market.getState(symbol); if (loaded == null) continue; JsonObject prices = loaded.metrics(); JsonArray entries = array(object(account, "entryOrders"), symbol); String old = pendingReplacements.getOrDefault(symbol, "");
                JsonObject decision = Workflows.pendingStopRefresh(entries, array(object(account, "exitPairs"), symbol).size(), seconds, old, number(prices, "lowOfDay"), number(prices, "highOfDay"));
                if (decision != null) { pendingReplacements.put(symbol, string(decision, "orderID")); JsonObject action = message("native_job"); action.addProperty("symbol", symbol); action.addProperty("keyCode", "RefreshEntryStop"); actions.add(action); }
                Set<String> activeWarnings = new HashSet<>();
                for (JsonElement item : entries) { JsonObject order = item.getAsJsonObject(); boolean isLong = bool(order, "isBuy"); double stop = number(order, "exitStopPrice"), extreme = number(prices, isLong ? "lowOfDay" : "highOfDay");
                    if (stop <= 0 || extreme <= 0 || !(isLong ? stop > extreme : stop < extreme)) continue; String key = symbol + ":" + string(order, "orderID") + ":" + stop; activeWarnings.add(key); Long last = entryWarnings.get(key);
                    if (last == null || seconds >= 0 && seconds < 180 && time - last >= 60000) { entryWarnings.put(key, time); notices.add(new String[]{symbol, "Pending entry stop " + stop + " is inside day extreme " + extreme}); }
                }
                entryWarnings.keySet().removeIf(key -> key.startsWith(symbol + ":") && !activeWarnings.contains(key));
            }
        }
        notices.forEach(value -> events.notify(value[0], value[1])); actions.forEach(this::dispatch);
    }
    private void disciplineJobs() {
        boolean changed = false; List<String[]> notices = new ArrayList<>(); long time = now.getAsLong();
        synchronized (lock) { if (stopped || state == null || account == null) return;
            for (String symbol : object(account, "positions").keySet()) { double net = number(object(object(account, "positions"), symbol), "netQuantity"); if (net == 0) continue; var loaded = market.getState(symbol); if (loaded == null) continue; JsonObject prices = loaded.metrics(), active = state.direction(symbol, net > 0); if (!bool(active, "hasValue")) continue;
                JsonObject decision = Workflows.stopDiscipline(string(active, "stopTightenPhase"), Math.abs(net), number(active, "initialQuantity"), net > 0, array(object(account, "exitPairs"), symbol), number(prices, "lowOfDay"), number(prices, "highOfDay"));
                if (!string(active, "stopTightenPhase").equals(string(decision, "phase"))) { active.add("stopTightenPhase", decision.get("phase")); changed = true; }
                if (bool(decision, "remind") && time - reminders.getOrDefault(symbol, 0L) >= 20000) { reminders.put(symbol, time); notices.add(new String[]{symbol, "TIGHTEN STOP using Bookmap levels for " + (int) number(decision, "neededShares") + " shares"}); }
            }
        }
        if (changed) persistState(); notices.forEach(value -> events.notify(value[0], value[1]));
    }
    private void accountNotifications() {
        List<String[]> notices = new ArrayList<>(); List<JsonObject> coreReminders = new ArrayList<>(); boolean changed = false;
        synchronized (lock) { if (stopped || state == null || account == null) return;
            Set<String> symbols = new HashSet<>(config.symbols); symbols.addAll(object(account, "positions").keySet());
            for (String symbol : symbols) {
                JsonObject position = object(object(account, "positions"), symbol); double net = number(position, "netQuantity"); var loaded = market.getState(symbol); JsonObject prices = loaded == null ? new JsonObject() : loaded.metrics();
                JsonArray pairs = array(object(account, "exitPairs"), symbol); double risk = com.bookmap.plugin.rong.miniviteapp.core.controllers.NativeViews.positionRisk(net, number(position, "averagePrice"), pairs, number(prices, "lowOfDay"), number(prices, "highOfDay")), pending = 0;
                for (JsonElement item : array(object(account, "entryOrders"), symbol)) { JsonObject entry = item.getAsJsonObject(); if (number(entry, "exitStopPrice") > 0) pending += Math.abs(number(entry, "price") - number(entry, "exitStopPrice")) * number(entry, "quantity"); }
                if (risk / number(policy, "riskDollars") > 1.2) notices.add(new String[]{symbol, "Position risk exceeds 1.2 R: " + Math.round(risk / number(policy, "riskDollars") * 100) / 100.0 + " R"});
                if (pending / number(policy, "riskDollars") > 1.2) notices.add(new String[]{symbol, "Pending entry risk exceeds 1.2 R: " + Math.round(pending / number(policy, "riskDollars") * 100) / 100.0 + " R"});
                JsonObject active = state.direction(symbol, net > 0); if (net != 0 && bool(policy, "coreTargetEnabled") && bool(active, "hasValue") && !bool(active, "coreTargetReminderShown")) {
                    JsonObject view = view(symbol, "command_state"); for (JsonObject projected : com.bookmap.plugin.rong.miniviteapp.core.controllers.NativeViews.project(view)) if (string(projected, "type").equals("core_plan_config") && number(projected, "partialsTaken") >= 3) {
                        active.addProperty("coreTargetReminderShown", true); changed = true; view.addProperty("reminderRequested", true); coreReminders.add(view); notices.add(new String[]{symbol, "Three partials completed; review the core target plan"});
                    }
                }
            }
        }
        if (changed) persistState(); coreReminders.forEach(events::message); notices.forEach(value -> events.notify(value[0], value[1]));
    }
    private void priceNotifications(String symbol) {
        var loaded = market.getState(symbol); if (loaded == null) return; JsonObject prices = loaded.snapshot(); List<String> notices = new ArrayList<>(); boolean persist = false; long time = now.getAsLong();
        synchronized (lock) { if (stopped || state == null || account == null || ledger == null) return; JsonObject saved = state.symbol(symbol); double net = number(object(object(account, "positions"), symbol), "netQuantity"); JsonObject active = state.direction(symbol, net > 0), trade = null;
            for (JsonElement item : array(object(ledger, "trades"), symbol)) if (!bool(item.getAsJsonObject(), "isClosed")) trade = item.getAsJsonObject();
            if (net != 0 && trade != null && array(trade, "entries").size() > 0) {
                JsonObject first = array(trade, "entries").get(0).getAsJsonObject(); long fillTime = (long) number(first, "timestamp"); double entryVwap = 0;
                for (JsonElement item : array(prices, "vwaps")) { JsonObject v = item.getAsJsonObject(); if (number(v, "datetime") > fillTime / 60000 * 60000) break; entryVwap = number(v, "value"); }
                if (entryVwap > 0) { JsonObject position = new JsonObject(); position.addProperty("positionKey", (net > 0 ? "long:" : "short:") + fillTime + ":" + java.math.BigDecimal.valueOf(number(first, "price")).stripTrailingZeros().toPlainString() + ":" + java.math.BigDecimal.valueOf(number(first, "quantity")).stripTrailingZeros().toPlainString()); position.addProperty("entryPrice", number(active, "entryPrice") > 0 ? number(active, "entryPrice") : number(first, "price")); position.addProperty("entryVwap", entryVwap); position.addProperty("isLong", net > 0);
                    JsonObject decision = Workflows.firstVwapTouch(object(saved, "firstVwapTouch"), position, number(prices, "currentPrice"), number(prices, "vwap")); if (bool(decision, "persist")) { saved.add("firstVwapTouch", decision.get("state")); persist = true; } if (bool(decision, "notify")) notices.add("First " + (net > 0 ? "pop" : "dip") + " to VWAP after entry away from VWAP; manage this level"); }
            }
            JsonArray candles = array(prices, "candles"); List<JsonObject> regular = new ArrayList<>(); for (JsonElement item : candles) if (MarketClock.marketTime((long) number(item.getAsJsonObject(), "datetime")).isRegularSession) regular.add(item.getAsJsonObject());
            if (regular.size() >= 2 && MarketClock.marketTime(time).minutesSinceMarketOpen > 61.0 / 60) { JsonObject current = regular.get(regular.size() - 1), previous = regular.get(regular.size() - 2); long bucket = (long) number(current, "datetime"); String key = symbol + ":volume";
                if (number(current, "volume") > number(previous, "volume") && reminders.getOrDefault(key, 0L) != bucket) { reminders.put(key, bucket); if (MarketClock.marketTime(time).minutesSinceMarketOpen < 15 || trade != null && array(trade, "entries").asList().stream().anyMatch(fill -> (long) number(fill.getAsJsonObject(), "timestamp") / 60000 * 60000 == bucket)) notices.add("Volume higher than the previous minute"); }
                if (number(current, "volume") > number(previous, "volume") && reminders.getOrDefault(symbol + ":volume-direction", 0L) != bucket && net != 0) { reminders.put(symbol + ":volume-direction", bucket); double direction = number(current, "close") - number(current, "open"); if (direction != 0) notices.add((net > 0) == (direction > 0) ? "Higher volume in favor; consider holding, only tighten stop" : "Higher volume against position"); }
                if (regular.size() >= 3 && MarketClock.marketTime(time).minutesSinceMarketOpen * 60 >= 100) { long closed = (long) number(previous, "datetime"); String closedKey = symbol + ":closed-volume"; Long seen = reminders.put(closedKey, closed);
                    if (seen != null && seen != closed) { boolean entryCandle = array(object(ledger, "trades"), symbol).asList().stream().anyMatch(t -> array(t.getAsJsonObject(), "entries").asList().stream().anyMatch(fill -> (long) number(fill.getAsJsonObject(), "timestamp") / 60000 * 60000 == closed));
                        if (entryCandle) notices.add(number(previous, "volume") > number(regular.get(regular.size() - 3), "volume") ? "Higher volume on entry candle" : "Lower volume on entry candle"); }
                }
            }
        }
        if (persist) persistState(); notices.forEach(value -> events.notify(symbol, value));
    }
    public void persistState() {
        JsonObject saved; String profile; CompletableFuture<Void> previous, next = new CompletableFuture<>();
        synchronized (lock) { if (stopped || state == null || config == null) return; saved = state.snapshot(); profile = config.profile; previous = persistence; persistence = next; }
        previous.handle((value, error) -> null).thenRunAsync(() -> { try { firestore.setTradingState(profile, saved); } catch (Exception error) { failure("", "State persistence", error); } finally { next.complete(null); } }, executor);
    }
    private void repeat(long delay, Runnable task) {
        synchronized (lock) { if (stopped) return; final Runnable[] cancel = new Runnable[1]; cancel[0] = scheduler.after(delay, () -> { synchronized (lock) { timers.remove(cancel[0]); if (stopped) return; } task.run(); repeat(delay, task); }); timers.add(cancel[0]); }
    }
    private void failure(String symbol, String operation, Throwable error) {
        if (stopped) return; String reason;
        try { JsonObject value = credentials.loadSchwab(); reason = ExecutionDiagnostics.describe(error, string(value, "access_token"), string(value, "refresh_token"), string(value, "accountHashValue"), massiveKey); }
        catch (Exception ignored) { reason = error.getClass().getSimpleName(); }
        events.log(symbol, operation + " failed: " + reason);
    }
    public CompletableFuture<Void> pendingPersistence() { synchronized (lock) { return persistence; } }
    @Override public void close() { MarketStreams previous; List<Runnable> cancel; synchronized (lock) { stopped = true; previous = streams; cancel = new ArrayList<>(timers); timers.clear(); if (accountRetry != null) { cancel.add(accountRetry); accountRetry = null; } } cancel.forEach(Runnable::run); if (previous != null) previous.close(); market.close(); execution.close(); }
}

