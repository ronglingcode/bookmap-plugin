package com.bookmap.plugin.rong.miniviteapp.runtime;

import com.bookmap.plugin.rong.miniviteapp.MiniViteApp;
import com.bookmap.plugin.rong.miniviteapp.ExecutionDiagnostics;
import com.bookmap.plugin.rong.miniviteapp.core.account.TradeLedger;
import com.bookmap.plugin.rong.miniviteapp.core.configuration.TradingConfig;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.ExecutionInputs;
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
    private final Map<String, JsonObject> quotes = new HashMap<>(), manual = new HashMap<>(), histories = new HashMap<>();
    private final Map<String, String> eligibility = new HashMap<>();
    private JsonObject account, ledger, policy = ExecutionInputs.defaultPolicy();
    private TradingConfig.Result config;
    private TradeState state;
    private MarketStreams streams;
    private CompletableFuture<Void> persistence = CompletableFuture.completedFuture(null);
    private volatile boolean stopped;
    private long revision;
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
            return response;
        };
        reads = new ReadApi(authenticatedReads);
        JsonObject firebase = object(sections, "firebaseConfig");
        firestore = new com.bookmap.plugin.rong.miniviteapp.libraries.firestore.Api(http, string(firebase, "projectId"), () -> string(firebase, "apiKey"));
        massiveKey = string(object(sections, "massive"), "apiKey"); massive = new com.bookmap.plugin.rong.miniviteapp.libraries.massive.Api(http, () -> massiveKey);
        market = new MarketLoader(massive, executor, now);
        execution = new MiniViteApp(new Api(authenticatedReads, events::log), (connection, message) -> executionEvent(message), events::log, String::trim);
        execution.setExtendedEnabled(true);
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
                events.log("", "Native trading runtime started; local credentials, Firestore, Massive and Schwab");
            } catch (Exception error) { failure("", "Native startup", error); throw new CompletionException(error); }
        }, executor);
    }
    private static void validateProfile(String profile) { if (!profile.equals("schwab") && !profile.equals("momentumSimple")) throw new IllegalArgumentException("Native equity runtime requires schwab or momentumSimple profile"); }
    private String accountHash() throws Exception { String account = string(credentials.loadSchwab(), "accountHashValue"); if (account.isEmpty()) throw new IllegalArgumentException("Schwab accountHashValue missing from local secrets"); return account; }
    private double price(String symbol) { var value = market.getState(symbol); return value == null ? 0 : number(value.metrics(), "currentPrice"); }
    private void publishToken() throws Exception {
        if (stopped) return; JsonObject value = credentials.loadSchwab(), token = message("execution_token"); token.addProperty("accountHash", accountHash()); token.addProperty("accessToken", string(value, "access_token")); token.addProperty("expiresAt", number(value, "expires_at")); execution.receive(this, token);
    }
    public void refreshToken() {
        if (stopped || !tokenReading.compareAndSet(false, true)) return;
        executor.execute(() -> { try { oauth.accessToken(false); publishToken(); } catch (Exception error) { failure("", "Token refresh", error); } finally { tokenReading.set(false); } });
    }
    public void exchangeAuthorizationCode(String callbackUrl) {
        executor.execute(() -> { try { oauth.exchangeAuthorizationCode(callbackUrl); publishToken(); if (streams != null) streams.close(); startStreamsAndHistory(); refreshAccount(); } catch (Exception error) { failure("", "Schwab authorization", error); } });
    }
    private void startStreamsAndHistory() {
        List<String> symbols; synchronized (lock) { if (stopped) return; symbols = new ArrayList<>(config.symbols); }
        // Register loading buffers before subscribing, so history/live overlap never loses prints.
        symbols.forEach(this::loadMarket);
        AtomicBoolean massiveReady = new AtomicBoolean();
        MarketStreams next = new MarketStreams(sockets, scheduler, executor, symbols, () -> massiveKey,
            () -> { String token = oauth.accessToken(false); publishToken(); return new MarketStreams.Credentials(reads.getStreamerInfo(token), token); }, new MarketStreams.Events() {
                public void trade(Trade trade) { if (market.acceptTrade(trade)) synchronized (lock) { dirty.add(trade.symbol); } }
                public void quote(JsonObject quote) { String symbol = string(quote, "symbol"); synchronized (lock) { JsonObject value = quotes.computeIfAbsent(symbol, key -> new JsonObject()); quote.entrySet().forEach(entry -> value.add(entry.getKey(), entry.getValue())); dirty.add(symbol); } }
                public void activity(JsonArray values) { refreshAccount(); }
                public void ready(String source) { if (source.equals("massive")) { if (massiveReady.getAndSet(true)) symbols.forEach(TradingRuntime.this::loadMarket); } else refreshAccount(); }
                public void status(String source, String status) { if (!stopped) events.log("", source + ": " + status); }
            });
        synchronized (lock) { if (stopped) { next.close(); return; } streams = next; }
        next.start();
    }
    private void loadMarket(String symbol) {
        JsonObject plan; synchronized (lock) { if (stopped || config == null) return; plan = config.plan(symbol); }
        if (plan == null) return;
        synchronized (lock) { eligibility.put(symbol, "startup eligibility pending"); }
        JsonObject correction = object(plan, "vwapCorrection");
        market.load(symbol, MarketClock.marketTime(now.getAsLong()).date, number(plan, "marketCapInMillions"), number(correction, "volumeSum"), number(correction, "tradingSum"))
            .whenComplete((loaded, error) -> {
                if (stopped) return; if (error != null) { failure(symbol, "Market history", error); return; }
                double shares = 0; try { shares = massive.getSharesOutstanding(symbol); } catch (Exception referenceError) { failure(symbol, "Shares reference (using zero fallback)", referenceError); }
                if (stopped) return;
                String reason = StartupEligibility.evaluate(plan, number(loaded.state.metrics(), "currentPrice"), shares, object(loaded.history, "premarketDollarCollection"), array(loaded.history, "dailyBars"));
                synchronized (lock) { histories.put(symbol, loaded.history); eligibility.put(symbol, reason); dirty.add(symbol); }
                if (!reason.isEmpty()) events.notify(symbol, "Entry blocked: " + reason);
                publishInputs(symbol); events.message(view(symbol, "market_ready"));
            });
    }
    public void refreshAccount() {
        if (stopped) return;
        if (!accountReading.compareAndSet(false, true)) { accountAgain.set(true); return; }
        executor.execute(() -> {
            try {
                String token = oauth.accessToken(false), date = MarketClock.marketTime(now.getAsLong()).date;
                JsonObject raw = reads.getAccount(token); JsonArray orders = reads.getOrders(accountHash(), token, date, false);
                JsonObject projected = AccountProjection.projectAccount(raw, orders, date, this::price);
                synchronized (lock) { if (stopped) return; account = projected; ledger = TradeLedger.projectTradeLedger(object(projected, "executions"), number(policy, "dailyMaxLoss")); }
                publishToken(); publishAccount();
            } catch (Exception error) { failure("", "Account refresh", error); }
            finally { accountReading.set(false); if (accountAgain.getAndSet(false)) refreshAccount(); }
        });
    }
    public void refreshConfig() {
        if (stopped || !configReading.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                TradingConfig.Result loaded = TradingConfig.readTradingConfig(firestore.fetchConfigData(), 1); validateProfile(loaded.profile);
                boolean restart; MarketStreams previous;
                synchronized (lock) {
                    if (stopped) return;
                    if (!loaded.profile.equals(config.profile)) throw new IllegalArgumentException("Profile changed; restart native runtime to load its trade state");
                    restart = !loaded.symbols.equals(config.symbols); previous = streams; config = loaded;
                }
                if (restart) { if (previous != null) previous.close(); startStreamsAndHistory(); }
                publishAccount();
            } catch (Exception error) { failure("", "Config refresh", error); }
            finally { configReading.set(false); }
        });
    }
    private void publishAccount() { List<String> symbols; synchronized (lock) { if (stopped || config == null) return; symbols = new ArrayList<>(config.symbols); for (String symbol : object(account, "positions").keySet()) if (!symbols.contains(symbol)) symbols.add(symbol); }
        symbols.forEach(symbol -> { publishInputs(symbol); events.message(view(symbol, "account_ready")); }); }
    private void publishDirty() { List<String> symbols; synchronized (lock) { if (stopped) return; symbols = new ArrayList<>(dirty); dirty.clear(); } symbols.forEach(symbol -> { publishInputs(symbol); events.message(view(symbol, "market_update")); }); }
    public JsonObject view(String symbol, String type) {
        JsonObject value = message(type); value.addProperty("symbol", symbol); value.addProperty("timestamp", now.getAsLong());
        if (type.equals("market_update")) { var loaded = market.getState(symbol); if (loaded != null) value.add("market", loaded.metrics()); return value; }
        synchronized (lock) { if (config != null) { JsonObject plan = config.plan(symbol); if (plan != null) value.add("plan", plan.deepCopy()); value.add("tradingSettings", config.tradingSettings.deepCopy()); }
            if (account != null) value.add("account", account.deepCopy()); if (ledger != null) value.add("ledger", ledger.deepCopy()); if (state != null) value.add("state", state.snapshot()); if (histories.containsKey(symbol)) value.add("history", histories.get(symbol).deepCopy()); }
        var loaded = market.getState(symbol); if (loaded != null) value.add("market", loaded.snapshot()); return value;
    }
    private void publishInputs(String symbol) {
        JsonObject input; var loaded = market.getState(symbol);
        synchronized (lock) {
            if (stopped || state == null || config == null || account == null) return;
            JsonObject plan = config.plan(symbol); if (plan == null || loaded == null) return;
            JsonArray stocks = new JsonArray(); config.symbols.forEach(stocks::add); JsonObject markets = new JsonObject(); config.symbols.forEach(stock -> { JsonObject prices = new JsonObject(); prices.addProperty("currentPrice", price(stock)); markets.add(stock, prices); });
            input = ExecutionInputs.create(symbol, plan, loaded.snapshot(), quotes.getOrDefault(symbol, new JsonObject()), account, ledger, state, stocks, markets, manual.getOrDefault(symbol, new JsonObject()), now.getAsLong(), ++revision, policy);
            String reason = eligibility.getOrDefault(symbol, "startup eligibility pending");
            if (!reason.isEmpty()) input.getAsJsonObject("entryContext").addProperty("watchlistBlockReason", reason);
        }
        JsonObject update = message("execution_state"); JsonArray symbols = new JsonArray(); symbols.add(input); update.add("symbols", symbols); execution.receive(this, update);
    }
    public boolean dispatch(JsonObject action) { if (stopped) return false; String symbol = string(action, "symbol").trim(); publishInputs(symbol); return execution.route(action); }
    public void resetAfterBrokerReview() { execution.resetAfterBrokerReview(); }
    private void executionEvent(JsonObject result) {
        String symbol = string(result, "symbol");
        if (result.has("entry")) {
            boolean changed; synchronized (lock) { JsonObject plan = config == null ? null : config.plan(symbol); changed = state != null && state.acceptEntry(symbol, object(result, "entry"), object(plan, "atr"), now.getAsLong()); }
            if (changed) persistState();
        }
        events.message(result.deepCopy());
        if (string(result, "type").equals("execution_result")) { publishInputs(symbol); refreshAccount(); }
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
    @Override public void close() { MarketStreams previous; List<Runnable> cancel; synchronized (lock) { stopped = true; previous = streams; cancel = new ArrayList<>(timers); timers.clear(); } cancel.forEach(Runnable::run); if (previous != null) previous.close(); market.close(); execution.close(); }
}

