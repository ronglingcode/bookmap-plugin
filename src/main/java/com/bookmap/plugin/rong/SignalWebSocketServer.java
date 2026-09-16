package com.bookmap.plugin.rong;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import com.bookmap.plugin.rong.patterns.Direction;
import com.bookmap.plugin.rong.patterns.PatternType;
import com.bookmap.plugin.rong.tradebuttons.TradebookButtonGroup;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class SignalWebSocketServer extends WebSocketServer {

    private static final int WALL_THRESHOLD_LARGEST_LEVEL_COUNT = 3;

    @FunctionalInterface
    public interface TradeButtonConfigListener {
        void onTradeButtonsChanged(List<TradebookButtonGroup> tradebooks);
    }

    @FunctionalInterface
    public interface KeyLevelConfigListener {
        void onKeyLevelsChanged(String symbol, List<KeyLevelDefinition> levels);
    }

    @FunctionalInterface
    public interface KeyZoneConfigListener {
        void onKeyZonesChanged(String symbol, List<KeyZoneDefinition> zones);
    }

    @FunctionalInterface
    public interface MarketLevelConfigListener {
        void onMarketLevelsChanged(String symbol, MarketLevelDefinition marketLevels);
    }

    @FunctionalInterface
    public interface ExitOrderPairsConfigListener {
        void onExitOrderPairsChanged(String symbol, List<ExitOrderPairDefinition> pairs);
    }

    @FunctionalInterface
    public interface AccountStateListener {
        void onAccountStateChanged(AccountStateDefinition state);
    }

    @FunctionalInterface
    public interface VwapUpdateListener {
        void onVwapChanged(VwapUpdateDefinition update);
    }

    @FunctionalInterface
    public interface CorePlanConfigListener {
        void onCorePlanChanged(CorePlanConfigDefinition config);
    }

    @FunctionalInterface
    public interface NewPositionListener {
        void onNewPosition(NewPositionDefinition position);
    }

    @FunctionalInterface
    public interface EntryRetestStateListener {
        void onEntryRetestStateChanged(EntryRetestState state);
    }

    // Per-symbol state
    private final Map<String, OrderBookState> symbolToOrderBook = new ConcurrentHashMap<>();
    private final Map<String, Double> symbolToPips = new ConcurrentHashMap<>();
    private final Map<String, List<TradebookButtonGroup>> symbolToTradebooks = new ConcurrentHashMap<>();
    private final Map<String, List<KeyLevelDefinition>> symbolToKeyLevels = new ConcurrentHashMap<>();
    private final Map<String, List<KeyZoneDefinition>> symbolToKeyZones = new ConcurrentHashMap<>();
    private final Map<String, EntryRetestState> symbolToEntryRetestState = new ConcurrentHashMap<>();
    private final Map<String, MarketLevelDefinition> symbolToMarketLevels = new ConcurrentHashMap<>();
    private final Map<String, List<ExitOrderPairDefinition>> symbolToExitOrderPairs = new ConcurrentHashMap<>();
    private final Map<String, AccountStateDefinition> symbolToAccountState = new ConcurrentHashMap<>();
    private final Map<String, RegularSessionHighLowTracker> symbolToRegularSessionHighLow = new ConcurrentHashMap<>();
    private final Map<String, VwapUpdateDefinition> symbolToVwapUpdate = new ConcurrentHashMap<>();
    private final Map<String, CorePlanConfigDefinition> symbolToCorePlan = new ConcurrentHashMap<>();
    private final Map<String, Set<TradeButtonConfigListener>> symbolToTradeButtonListeners = new ConcurrentHashMap<>();
    private final Map<String, Set<VwapUpdateListener>> symbolToVwapUpdateListeners = new ConcurrentHashMap<>();
    private final Map<String, Set<CorePlanConfigListener>> symbolToCorePlanListeners = new ConcurrentHashMap<>();
    private final Map<String, Set<NewPositionListener>> symbolToNewPositionListeners = new ConcurrentHashMap<>();
    private final Map<String, Set<EntryRetestStateListener>> symbolToEntryRetestStateListeners =
            new ConcurrentHashMap<>();
    private final Object entryRetestStateLock = new Object();
    private final Set<KeyLevelConfigListener> keyLevelConfigListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<KeyZoneConfigListener> keyZoneConfigListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<MarketLevelConfigListener> marketLevelConfigListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<ExitOrderPairsConfigListener> exitOrderPairsConfigListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<AccountStateListener> accountStateListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Wall filtering config
    private final double orderbookPercentile;

    public SignalWebSocketServer(int port, double orderbookPercentile) {
        super(new InetSocketAddress("127.0.0.1", port));
        setDaemon(true);
        setReuseAddr(true);
        this.orderbookPercentile = orderbookPercentile;
    }

    /** Register a symbol's order book and pips multiplier. */
    public void registerSymbol(String symbol, OrderBookState orderBook, double pips) {
        symbolToOrderBook.put(symbol, orderBook);
        symbolToPips.put(symbol, pips);
    }

    /** Unregister a symbol when its plugin instance stops. */
    public void unregisterSymbol(String symbol) {
        symbolToOrderBook.remove(symbol);
        symbolToPips.remove(symbol);
        symbolToRegularSessionHighLow.remove(symbol);
    }

    public void updateRegularSessionHighLow(String symbol, double price, long timestampNs) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        if (cleanSymbol.isEmpty()) {
            return;
        }
        symbolToRegularSessionHighLow
                .computeIfAbsent(cleanSymbol, ignored -> new RegularSessionHighLowTracker())
                .onTrade(price, timestampNs);
    }

    public boolean appendRegularSessionHighLow(String symbol, JsonObject target) {
        RegularSessionHighLowTracker.Snapshot snapshot = getRegularSessionHighLow(symbol);
        if (snapshot == null) {
            return false;
        }
        target.add("bookmapDayHighLow", snapshot.toJson());
        return true;
    }

    public String describeRegularSessionHighLow(String symbol) {
        RegularSessionHighLowTracker.Snapshot snapshot = getRegularSessionHighLow(symbol);
        if (snapshot == null) {
            return "HOD/LOD: waiting";
        }
        return String.format(Locale.US, "HOD/LOD: %.2f/%.2f", snapshot.getHigh(), snapshot.getLow());
    }

    /**
     * Estimate a market fill from Bookmap's current inside market.
     * Long entries use the best ask and short entries use the best bid.
     */
    public Double getMarketEntryEstimate(String symbol, boolean isLong) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        if (cleanSymbol.isEmpty()) {
            return null;
        }

        OrderBookState orderBook = symbolToOrderBook.get(cleanSymbol);
        Double pips = symbolToPips.get(cleanSymbol);
        if (orderBook == null || pips == null || pips <= 0 || !Double.isFinite(pips)) {
            return null;
        }

        synchronized (orderBook) {
            Integer priceTick = isLong ? orderBook.getBestAsk() : orderBook.getBestBid();
            if (priceTick == null || priceTick <= 0) {
                return null;
            }
            return BookmapPriceNormalizer.toWirePrice(priceTick, pips);
        }
    }

    private RegularSessionHighLowTracker.Snapshot getRegularSessionHighLow(String symbol) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        if (cleanSymbol.isEmpty()) {
            return null;
        }
        RegularSessionHighLowTracker tracker = symbolToRegularSessionHighLow.get(cleanSymbol);
        return tracker == null ? null : tracker.snapshot();
    }

    public OrderbookWallThreshold getOrderbookWallThreshold(String symbol, int thresholdFloor) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        int absoluteMinSize = Math.max(0, thresholdFloor);
        if (cleanSymbol.isEmpty()) {
            return OrderbookWallThreshold.unavailable("", orderbookPercentile, absoluteMinSize);
        }

        OrderBookState orderBook = symbolToOrderBook.get(cleanSymbol);
        Double pips = symbolToPips.get(cleanSymbol);
        if (orderBook == null || pips == null || pips <= 0 || !Double.isFinite(pips)) {
            return OrderbookWallThreshold.unavailable(cleanSymbol, orderbookPercentile, absoluteMinSize);
        }

        synchronized (orderBook) {
            WallThreshold threshold = WallThreshold.from(orderBook, absoluteMinSize, orderbookPercentile);
            return OrderbookWallThreshold.available(
                    cleanSymbol,
                    orderbookPercentile,
                    threshold.absoluteMinSize,
                    threshold.percentileMinSize,
                    threshold.effectiveMinSize,
                    orderBook.getLargestLevelSizes(WALL_THRESHOLD_LARGEST_LEVEL_COUNT),
                    System.currentTimeMillis());
        }
    }

    public void registerTradeButtonConfigListener(String symbol, TradeButtonConfigListener listener) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        symbolToTradeButtonListeners
                .computeIfAbsent(cleanSymbol, ignored -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(listener);

        List<TradebookButtonGroup> existingTradebooks = symbolToTradebooks.get(cleanSymbol);
        if (existingTradebooks != null) {
            listener.onTradeButtonsChanged(existingTradebooks);
        }
    }

    public void unregisterTradeButtonConfigListener(String symbol, TradeButtonConfigListener listener) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        Set<TradeButtonConfigListener> listeners = symbolToTradeButtonListeners.get(cleanSymbol);
        if (listeners == null) {
            return;
        }
        listeners.remove(listener);
        if (listeners.isEmpty()) {
            symbolToTradeButtonListeners.remove(cleanSymbol, listeners);
        }
    }

    public boolean hasEnabledWallBreakTradeButton(String symbol, boolean bidBreakdown) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        List<TradebookButtonGroup> tradebooks = symbolToTradebooks.get(cleanSymbol);
        if (tradebooks == null || tradebooks.isEmpty()) {
            return false;
        }
        for (TradebookButtonGroup tradebook : tradebooks) {
            if (tradebook.getEntryMethods().isEmpty()) {
                continue;
            }
            if (isMatchingWallBreakTradebook(tradebook, bidBreakdown)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Read-only tradebook eligibility for display-only Bookmap pattern badges.
     * An enabled group must have an entry method, match direction, and describe the
     * applicable Bookmap break or reversal tradebook family.
     */
    public boolean hasEnabledPatternTradebook(String symbol, PatternType patternType) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        List<TradebookButtonGroup> tradebooks = symbolToTradebooks.get(cleanSymbol);
        if (tradebooks == null || tradebooks.isEmpty()) return false;
        for (TradebookButtonGroup tradebook : tradebooks) {
            if (tradebook.getEntryMethods().isEmpty() || !matchesDirection(tradebook, patternType)) continue;
            if (patternType.getFamily() == PatternType.Family.BREAK) {
                if (isMatchingWallBreakTradebook(
                        tradebook, patternType == PatternType.BID_WALL_BREAKDOWN)) return true;
            } else if (isMatchingWallReversalTradebook(tradebook, patternType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the first enabled wall-reversal tradebook for the requested side.
     * This is the same ordering pushed by ViteApp and rendered in the trade-button window.
     */
    public TradebookButtonGroup getPrimaryWallReversalTradebook(
            String symbol, boolean bidWallReversal) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        List<TradebookButtonGroup> tradebooks = symbolToTradebooks.get(cleanSymbol);
        if (tradebooks == null || tradebooks.isEmpty()) {
            return null;
        }
        PatternType patternType =
                bidWallReversal ? PatternType.BID_REAPPEAR : PatternType.OFFER_REAPPEAR;
        for (TradebookButtonGroup tradebook : tradebooks) {
            if (tradebook.getEntryMethods().isEmpty()
                    || !matchesDirection(tradebook, patternType)) {
                continue;
            }
            if (isMatchingWallReversalTradebook(tradebook, patternType)) {
                return tradebook;
            }
        }
        return null;
    }

    public EntryRetestState getEntryRetestState(String symbol) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        return symbolToEntryRetestState.getOrDefault(cleanSymbol, EntryRetestState.ready());
    }

    public void registerEntryRetestStateListener(
            String symbol, EntryRetestStateListener listener) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        symbolToEntryRetestStateListeners
                .computeIfAbsent(
                        cleanSymbol,
                        ignored -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(listener);
        listener.onEntryRetestStateChanged(getEntryRetestState(cleanSymbol));
    }

    public void unregisterEntryRetestStateListener(
            String symbol, EntryRetestStateListener listener) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        Set<EntryRetestStateListener> listeners = symbolToEntryRetestStateListeners.get(cleanSymbol);
        if (listeners == null) {
            return;
        }
        listeners.remove(listener);
        if (listeners.isEmpty()) {
            symbolToEntryRetestStateListeners.remove(cleanSymbol, listeners);
        }
    }

    /** Marks the configured side ready after Bookmap sees a qualifying filled-depth decrease. */
    public void markEntryRetestSatisfied(String symbol, boolean bidRetest) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        if (cleanSymbol.isEmpty()) {
            return;
        }
        EntryRetestState updated = null;
        synchronized (entryRetestStateLock) {
            EntryRetestState previous = getEntryRetestState(cleanSymbol);
            EntryRetestState candidate = previous.withRetestSatisfied(bidRetest);
            if (candidate != previous) {
                symbolToEntryRetestState.put(cleanSymbol, candidate);
                updated = candidate;
            }
        }
        if (updated != null) {
            notifyEntryRetestStateListeners(cleanSymbol, updated);
            broadcastEntryRetestReady(cleanSymbol, bidRetest);
        }
    }

    private void broadcastEntryRetestReady(String symbol, boolean bidRetest) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "entry_retest_ready");
        json.addProperty("symbol", symbol);
        json.addProperty("side", bidRetest ? "bid" : "offer");
        json.addProperty("message", bidRetest ? "bid retest done" : "offer retest done");
        json.addProperty("timestamp", System.currentTimeMillis());
        broadcast(json.toString());
    }

    private void updateEntryRetestConfiguration(
            String symbol, EntryRetestMode bidRetestMode, EntryRetestMode offerRetestMode) {
        EntryRetestState updated = null;
        synchronized (entryRetestStateLock) {
            EntryRetestState previous = getEntryRetestState(symbol);
            EntryRetestState candidate = previous.withConfiguration(
                    bidRetestMode == null ? previous.getBidRetestMode() : bidRetestMode,
                    offerRetestMode == null ? previous.getOfferRetestMode() : offerRetestMode);
            if (candidate != previous) {
                symbolToEntryRetestState.put(symbol, candidate);
                updated = candidate;
            }
        }
        if (updated != null) {
            notifyEntryRetestStateListeners(symbol, updated);
        }
    }

    public void registerKeyLevelConfigListener(KeyLevelConfigListener listener) {
        keyLevelConfigListeners.add(listener);
        for (Map.Entry<String, List<KeyLevelDefinition>> entry : symbolToKeyLevels.entrySet()) {
            listener.onKeyLevelsChanged(entry.getKey(), entry.getValue());
        }
    }

    public void unregisterKeyLevelConfigListener(KeyLevelConfigListener listener) {
        keyLevelConfigListeners.remove(listener);
    }

    public void registerKeyZoneConfigListener(KeyZoneConfigListener listener) {
        keyZoneConfigListeners.add(listener);
        for (Map.Entry<String, List<KeyZoneDefinition>> entry : symbolToKeyZones.entrySet()) {
            listener.onKeyZonesChanged(entry.getKey(), entry.getValue());
        }
    }

    public void registerVwapUpdateListener(String symbol, VwapUpdateListener listener) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        symbolToVwapUpdateListeners
                .computeIfAbsent(cleanSymbol, ignored -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(listener);

        VwapUpdateDefinition existingUpdate = symbolToVwapUpdate.get(cleanSymbol);
        if (existingUpdate != null) {
            listener.onVwapChanged(existingUpdate);
        }
    }

    public void unregisterVwapUpdateListener(String symbol, VwapUpdateListener listener) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        Set<VwapUpdateListener> listeners = symbolToVwapUpdateListeners.get(cleanSymbol);
        if (listeners == null) {
            return;
        }
        listeners.remove(listener);
        if (listeners.isEmpty()) {
            symbolToVwapUpdateListeners.remove(cleanSymbol, listeners);
        }
    }

    public void registerCorePlanConfigListener(String symbol, CorePlanConfigListener listener) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        symbolToCorePlanListeners
                .computeIfAbsent(cleanSymbol, ignored -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(listener);

        CorePlanConfigDefinition existingConfig = symbolToCorePlan.get(cleanSymbol);
        if (existingConfig != null) {
            listener.onCorePlanChanged(existingConfig);
        }
    }

    public void unregisterCorePlanConfigListener(String symbol, CorePlanConfigListener listener) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        Set<CorePlanConfigListener> listeners = symbolToCorePlanListeners.get(cleanSymbol);
        if (listeners == null) {
            return;
        }
        listeners.remove(listener);
        if (listeners.isEmpty()) {
            symbolToCorePlanListeners.remove(cleanSymbol, listeners);
        }
    }

    public void registerNewPositionListener(String symbol, NewPositionListener listener) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        symbolToNewPositionListeners
                .computeIfAbsent(cleanSymbol, ignored -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(listener);
    }

    public void unregisterNewPositionListener(String symbol, NewPositionListener listener) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        Set<NewPositionListener> listeners = symbolToNewPositionListeners.get(cleanSymbol);
        if (listeners == null) {
            return;
        }
        listeners.remove(listener);
        if (listeners.isEmpty()) {
            symbolToNewPositionListeners.remove(cleanSymbol, listeners);
        }
    }

    public void unregisterKeyZoneConfigListener(KeyZoneConfigListener listener) {
        keyZoneConfigListeners.remove(listener);
    }

    public void registerMarketLevelConfigListener(MarketLevelConfigListener listener) {
        marketLevelConfigListeners.add(listener);
        for (Map.Entry<String, MarketLevelDefinition> entry : symbolToMarketLevels.entrySet()) {
            listener.onMarketLevelsChanged(entry.getKey(), entry.getValue());
        }
    }

    public void unregisterMarketLevelConfigListener(MarketLevelConfigListener listener) {
        marketLevelConfigListeners.remove(listener);
    }

    public void registerExitOrderPairsConfigListener(ExitOrderPairsConfigListener listener) {
        exitOrderPairsConfigListeners.add(listener);
        for (Map.Entry<String, List<ExitOrderPairDefinition>> entry : symbolToExitOrderPairs.entrySet()) {
            listener.onExitOrderPairsChanged(entry.getKey(), entry.getValue());
        }
    }

    public void unregisterExitOrderPairsConfigListener(ExitOrderPairsConfigListener listener) {
        exitOrderPairsConfigListeners.remove(listener);
    }

    public void registerAccountStateListener(AccountStateListener listener) {
        accountStateListeners.add(listener);
        for (AccountStateDefinition state : symbolToAccountState.values()) {
            listener.onAccountStateChanged(state);
        }
    }

    public void unregisterAccountStateListener(AccountStateListener listener) {
        accountStateListeners.remove(listener);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        String trimmed = message.trim();
        JsonObject json = parseJsonObject(trimmed);
        if (json != null) {
            String type = getString(json, "type");
            if (isPriceBearingMessageType(type)
                    && !BookmapPriceNormalizer.isSupportedWirePriceUnit(
                            getString(json, BookmapPriceNormalizer.WIRE_PRICE_UNIT_FIELD))) {
                return;
            }
            if ("trade_button_config".equals(type) || "trade_buttons_config".equals(type)) {
                handleTradeButtonConfig(json);
                return;
            }
            if ("key_levels_config".equals(type) || "key_level_config".equals(type)) {
                handleKeyLevelsConfig(json);
                return;
            }
            if ("exit_order_pairs_config".equals(type) || "exit_order_pair_config".equals(type)) {
                handleExitOrderPairsConfig(json);
                return;
            }
            if ("action_log".equals(type)) {
                handleActionLog(json);
                return;
            }
            if ("screen_log".equals(type)) {
                handleScreenLog(json);
                return;
            }
            if ("account_state".equals(type)) {
                handleAccountState(json);
                return;
            }
            if ("vwap_update".equals(type)) {
                handleVwapUpdate(json);
                return;
            }
            if ("core_plan_config".equals(type)) {
                handleCorePlanConfig(json);
                return;
            }
            if ("new_position".equals(type)) {
                handleNewPosition(json);
                return;
            }
        }
    }

    private JsonObject parseJsonObject(String message) {
        try {
            JsonElement element = JsonParser.parseString(message);
            if (element != null && element.isJsonObject()) {
                return element.getAsJsonObject();
            }
        } catch (RuntimeException ignored) {
            // Ignore invalid messages without writing diagnostic logs.
        }
        return null;
    }

    private void handleVwapUpdate(JsonObject json) {
        String symbol = SymbolUtils.cleanSymbol(getString(json, "symbol"));
        if (symbol.isEmpty()) {
            return;
        }

        try {
            VwapUpdateDefinition update = new VwapUpdateDefinition(
                    symbol,
                    getWirePrice(json, "vwap"),
                    getLong(json, "effectiveTimeMs"),
                    getLong(json, "sentAtMs"));
            VwapUpdateDefinition existing = symbolToVwapUpdate.get(symbol);
            if (existing != null
                    && (update.getEffectiveTimeMs() < existing.getEffectiveTimeMs()
                    || (update.getEffectiveTimeMs() == existing.getEffectiveTimeMs()
                    && update.getSentAtMs() <= existing.getSentAtMs()))) {
                return;
            }

            symbolToVwapUpdate.put(symbol, update);
            notifyVwapUpdateListeners(symbol, update);
        } catch (IllegalArgumentException ignored) {
            // Ignore invalid messages without writing diagnostic logs.
        }
    }

    private void handleCorePlanConfig(JsonObject json) {
        String symbol = SymbolUtils.cleanSymbol(getString(json, "symbol"));
        if (symbol.isEmpty()) {
            return;
        }

        boolean activeTrade = getBoolean(json, "hasActiveTrade");
        try {
            CorePlanConfigDefinition config = new CorePlanConfigDefinition(
                    symbol,
                    activeTrade,
                    getBoolean(json, "isLong"),
                    activeTrade ? getWirePrice(json, "entryPrice") : 0,
                    activeTrade ? getWirePrice(json, "coreTarget") : 0,
                    activeTrade ? getInt(json, "coreCount") : 0,
                    getString(json, "runnerCondition"),
                    activeTrade ? getInt(json, "runnerCount") : 0,
                    getString(json, "corePlan"),
                    activeTrade ? getWirePrice(json, "bufferedTarget") : 0,
                    activeTrade ? getInt(json, "partialsTaken") : 0,
                    getString(json, "tradeId"),
                    getBoolean(json, "reminderRequested"),
                    getString(json, "requestId"),
                    getString(json, "updateStatus"),
                    getString(json, "error"),
                    getLong(json, "timestamp"));
            symbolToCorePlan.put(symbol, config);
            notifyCorePlanListeners(symbol, config);
        } catch (IllegalArgumentException ignored) {
            // Ignore invalid messages without writing diagnostic logs.
        }
    }

    private void handleNewPosition(JsonObject json) {
        String symbol = SymbolUtils.cleanSymbol(getString(json, "symbol"));
        if (symbol.isEmpty()) {
            return;
        }

        try {
            double averagePrice = getWirePrice(json, "averagePrice");
            if (!Double.isFinite(averagePrice)) {
                averagePrice = 0;
            }
            NewPositionDefinition position = new NewPositionDefinition(
                    symbol,
                    getBoolean(json, "isLong"),
                    getDouble(json, "netQuantity"),
                    averagePrice,
                    getString(json, "eventId"),
                    getLong(json, "timestamp"));
            notifyNewPositionListeners(symbol, position);
        } catch (IllegalArgumentException ignored) {
            // Ignore invalid messages without writing diagnostic logs.
        }
    }

    private void handleTradeButtonConfig(JsonObject json) {
        String symbol = SymbolUtils.cleanSymbol(getString(json, "symbol"));
        if (symbol.isEmpty()) {
            return;
        }

        JsonArray tradebooksArray = null;
        JsonElement tradebooksElement = json.get("tradebooks");
        if (tradebooksElement != null && tradebooksElement.isJsonArray()) {
            tradebooksArray = tradebooksElement.getAsJsonArray();
        }
        if (tradebooksArray == null) {
            return;
        }

        List<TradebookButtonGroup> tradebooks = new ArrayList<>();
        for (JsonElement element : tradebooksArray) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject tradebookJson = element.getAsJsonObject();
            String id = getString(tradebookJson, "id");
            String tradebookId = getString(tradebookJson, "tradebookId");
            String tradebookName = getString(tradebookJson, "tradebookName");
            String label = getString(tradebookJson, "label");
            if (label.isEmpty()) {
                label = tradebookName;
            }
            if (label.isEmpty()) {
                label = tradebookId;
            }
            if (label.isEmpty()) {
                continue;
            }
            if (id.isEmpty()) {
                id = tradebookId.isEmpty() ? label : tradebookId;
            }
            List<String> entryMethods = getStringArray(tradebookJson, "entryMethods");
            if (entryMethods.isEmpty()) {
                continue;
            }
            Boolean sideIsLong = getOptionalBoolean(tradebookJson, "sideIsLong");
            if (sideIsLong == null) {
                continue;
            }
            tradebooks.add(new TradebookButtonGroup(
                    id,
                    label,
                    sideIsLong,
                    tradebookId,
                    tradebookName,
                    entryMethods));
        }

        List<TradebookButtonGroup> immutableTradebooks = Collections.unmodifiableList(tradebooks);
        symbolToTradebooks.put(symbol, immutableTradebooks);
        notifyTradeButtonListeners(symbol, immutableTradebooks);
    }

    private void handleKeyLevelsConfig(JsonObject json) {
        String symbol = SymbolUtils.cleanSymbol(getString(json, "symbol"));
        if (symbol.isEmpty()) {
            return;
        }

        JsonElement levelsElement = json.get("levels");
        if (levelsElement == null) {
            levelsElement = json.get("keyLevels");
        }
        if (levelsElement == null || !levelsElement.isJsonArray()) {
            return;
        }

        List<KeyLevelDefinition> levels = new ArrayList<>();
        JsonArray levelsArray = levelsElement.getAsJsonArray();
        for (JsonElement element : levelsArray) {
            KeyLevelDefinition level = parseKeyLevel(symbol, element);
            if (level != null) {
                levels.add(level);
            }
        }

        List<KeyLevelDefinition> immutableLevels = Collections.unmodifiableList(levels);
        symbolToKeyLevels.put(symbol, immutableLevels);
        notifyKeyLevelConfigListeners(symbol, immutableLevels);

        EntryRetestMode bidRetestMode = getOptionalEntryRetestMode(json, "waitForBidRetest");
        EntryRetestMode offerRetestMode = getOptionalEntryRetestMode(json, "waitForOfferRetest");
        if (bidRetestMode != null || offerRetestMode != null) {
            updateEntryRetestConfiguration(symbol, bidRetestMode, offerRetestMode);
        }

        List<KeyZoneDefinition> zones = parseKeyZones(symbol, json);
        List<KeyZoneDefinition> immutableZones = Collections.unmodifiableList(zones);
        symbolToKeyZones.put(symbol, immutableZones);
        notifyKeyZoneConfigListeners(symbol, immutableZones);

        MarketLevelDefinition marketLevels = parseMarketLevels(symbol, json);
        symbolToMarketLevels.put(symbol, marketLevels);
        notifyMarketLevelConfigListeners(symbol, marketLevels);
    }

    private KeyLevelDefinition parseKeyLevel(String symbol, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        try {
            if (element.isJsonPrimitive()) {
                double price = BookmapPriceNormalizer.normalizeWirePrice(element.getAsDouble());
                return price > 0 ? new KeyLevelDefinition(symbol, price, null) : null;
            }
            if (!element.isJsonObject()) {
                return null;
            }
            JsonObject levelJson = element.getAsJsonObject();
            double price = getWirePrice(levelJson, "price");
            if (price <= 0) {
                return null;
            }
            String label = getString(levelJson, "label");
            return new KeyLevelDefinition(symbol, price, label);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<KeyZoneDefinition> parseKeyZones(String symbol, JsonObject json) {
        JsonElement zonesElement = json.get("zones");
        if (zonesElement == null) {
            zonesElement = json.get("keyZones");
        }
        if (zonesElement == null || !zonesElement.isJsonArray()) {
            return new ArrayList<>();
        }

        List<KeyZoneDefinition> zones = new ArrayList<>();
        JsonArray zonesArray = zonesElement.getAsJsonArray();
        for (JsonElement element : zonesArray) {
            KeyZoneDefinition zone = parseKeyZone(symbol, element);
            if (zone != null) {
                zones.add(zone);
            }
        }
        return zones;
    }

    private KeyZoneDefinition parseKeyZone(String symbol, JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        try {
            JsonObject zoneJson = element.getAsJsonObject();
            double low = getWirePrice(zoneJson, "low");
            double high = getWirePrice(zoneJson, "high");
            if (!Double.isFinite(low) || !Double.isFinite(high) || low <= 0 || high <= 0 || low == high) {
                return null;
            }
            return new KeyZoneDefinition(
                    symbol,
                    low,
                    high,
                    getString(zoneJson, "label"),
                    getString(zoneJson, "color"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private MarketLevelDefinition parseMarketLevels(String symbol, JsonObject json) {
        JsonObject marketLevelsObject = getObjectField(json, "marketLevels");
        JsonObject camPivotsObject = getFirstObjectField(json, marketLevelsObject, "camPivots");
        JsonObject previousDayObject = getFirstObjectField(json, marketLevelsObject, "previousDay");
        if (previousDayObject == null) {
            previousDayObject = getFirstObjectField(json, marketLevelsObject, "yesterday");
        }
        JsonObject premarketObject = getFirstObjectField(json, marketLevelsObject, "premarket");

        return new MarketLevelDefinition(
                symbol,
                parseCamPivots(camPivotsObject),
                getPairPrice(json, previousDayObject, "high", "previousDayHigh", "yesterdayHigh"),
                getPairPrice(json, previousDayObject, "low", "previousDayLow", "yesterdayLow"),
                getPairPrice(json, premarketObject, "high", "premarketHigh", "pmHigh"),
                getPairPrice(json, premarketObject, "low", "premarketLow", "pmLow"));
    }

    private Map<String, Double> parseCamPivots(JsonObject pivotsObject) {
        Map<String, Double> pivots = new LinkedHashMap<>();
        if (pivotsObject == null) {
            return pivots;
        }
        String[] levels = {"R1", "R2", "R3", "R4", "R5", "R6",
                           "S1", "S2", "S3", "S4", "S5", "S6"};
        for (String level : levels) {
            double price = getWirePrice(pivotsObject, level);
            if (Double.isFinite(price) && price > 0) {
                pivots.put(level, price);
            }
        }
        return pivots;
    }

    private double getPairPrice(
            JsonObject root,
            JsonObject pairObject,
            String pairField,
            String primaryTopLevelField,
            String secondaryTopLevelField) {
        if (pairObject != null) {
            double nestedValue = getWirePrice(pairObject, pairField);
            if (Double.isFinite(nestedValue)) {
                return nestedValue;
            }
        }
        double primaryValue = getWirePrice(root, primaryTopLevelField);
        if (Double.isFinite(primaryValue)) {
            return primaryValue;
        }
        return getWirePrice(root, secondaryTopLevelField);
    }

    private JsonObject getFirstObjectField(JsonObject primary, JsonObject secondary, String field) {
        JsonObject object = getObjectField(primary, field);
        if (object != null) {
            return object;
        }
        return secondary == null ? null : getObjectField(secondary, field);
    }

    private JsonObject getObjectField(JsonObject json, String field) {
        if (json == null) {
            return null;
        }
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonObject()) {
            return null;
        }
        return element.getAsJsonObject();
    }

    private void handleExitOrderPairsConfig(JsonObject json) {
        String symbol = SymbolUtils.cleanSymbol(getString(json, "symbol"));
        if (symbol.isEmpty()) {
            return;
        }

        JsonElement pairsElement = json.get("pairs");
        if (pairsElement == null || !pairsElement.isJsonArray()) {
            return;
        }

        List<ExitOrderPairDefinition> pairs = new ArrayList<>();
        JsonArray pairsArray = pairsElement.getAsJsonArray();
        for (JsonElement element : pairsArray) {
            ExitOrderPairDefinition pair = parseExitOrderPair(symbol, element, pairs.size() + 1);
            if (pair != null) {
                pairs.add(pair);
            }
        }

        List<ExitOrderPairDefinition> immutablePairs = Collections.unmodifiableList(pairs);
        symbolToExitOrderPairs.put(symbol, immutablePairs);
        notifyExitOrderPairsConfigListeners(symbol, immutablePairs);
    }

    private void handleActionLog(JsonObject json) {
        String message = getString(json, "message").trim();
        String symbol = SymbolUtils.cleanSymbol(getString(json, "symbol"));
        String source = getString(json, "source").trim();
        if (!message.isEmpty()) {
            PluginLog.action(symbol, source, message);
        }
    }

    private void handleAccountState(JsonObject json) {
        String symbol = SymbolUtils.cleanSymbol(getString(json, "symbol"));
        if (symbol.isEmpty()) {
            return;
        }

        AccountPositionDefinition position = parseAccountPosition(symbol, json.get("position"));
        JsonElement ordersElement = json.get("openOrders");
        if (ordersElement == null) {
            ordersElement = json.get("orders");
        }
        List<AccountOrderDefinition> openOrders = parseAccountOrders(symbol, ordersElement);
        List<AccountExecutionDefinition> executions = parseAccountExecutions(symbol, json.get("executions"));

        AccountStateDefinition state = new AccountStateDefinition(
                symbol,
                position,
                openOrders,
                executions,
                getLong(json, "timestamp"));
        symbolToAccountState.put(symbol, state);
        ActionLogWindow.updateAccountState(state);
        notifyAccountStateListeners(state);
    }

    private AccountPositionDefinition parseAccountPosition(String symbol, JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonObject()) {
            return null;
        }
        JsonObject positionJson = element.getAsJsonObject();
        double netQuantity = getDouble(positionJson, "netQuantity");
        if (!Double.isFinite(netQuantity)) {
            netQuantity = getDouble(positionJson, "quantity");
        }
        double averagePrice = getWirePrice(positionJson, "averagePrice");
        return new AccountPositionDefinition(
                symbol,
                Double.isFinite(netQuantity) ? netQuantity : 0,
                Double.isFinite(averagePrice) ? averagePrice : 0,
                getDouble(positionJson, "riskPercent"));
    }

    private List<AccountOrderDefinition> parseAccountOrders(String symbol, JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonArray()) {
            return Collections.emptyList();
        }
        List<AccountOrderDefinition> orders = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            AccountOrderDefinition order = parseAccountOrder(symbol, item);
            if (order != null) {
                orders.add(order);
            }
        }
        return orders;
    }

    private List<AccountExecutionDefinition> parseAccountExecutions(String symbol, JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonArray()) {
            return Collections.emptyList();
        }
        List<AccountExecutionDefinition> executions = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            AccountExecutionDefinition execution = parseAccountExecution(symbol, item);
            if (execution != null) {
                executions.add(execution);
            }
        }
        return executions;
    }

    private AccountExecutionDefinition parseAccountExecution(String symbol, JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonObject()) {
            return null;
        }
        try {
            JsonObject executionJson = element.getAsJsonObject();
            double price = getWirePrice(executionJson, "price");
            double quantity = getDouble(executionJson, "quantity");
            long timeMs = getLong(executionJson, "timeMs");
            if (price <= 0 || !Double.isFinite(price)
                    || quantity <= 0 || !Double.isFinite(quantity)
                    || timeMs <= 0) {
                return null;
            }

            return new AccountExecutionDefinition(
                    symbol,
                    price,
                    quantity,
                    parseOrderIsBuy(executionJson),
                    getBoolean(executionJson, "positionEffectIsOpen"),
                    timeMs);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private AccountOrderDefinition parseAccountOrder(String symbol, JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonObject()) {
            return null;
        }
        try {
            JsonObject orderJson = element.getAsJsonObject();
            String role = getString(orderJson, "role");
            if (role.isEmpty()) {
                role = getString(orderJson, "kind");
            }
            double quantity = getDouble(orderJson, "quantity");
            if (!Double.isFinite(quantity)) {
                quantity = getDouble(orderJson, "qty");
            }
            int pairIndex = getInt(orderJson, "pairIndex");
            if (pairIndex <= 0) {
                pairIndex = getInt(orderJson, "index");
            }

            return new AccountOrderDefinition(
                    symbol,
                    firstNonEmpty(getString(orderJson, "orderID"), getString(orderJson, "orderId")),
                    role,
                    getString(orderJson, "orderType"),
                    getWirePrice(orderJson, "price"),
                    Double.isFinite(quantity) ? quantity : 0,
                    parseOrderIsBuy(orderJson),
                    getString(orderJson, "source"),
                    firstNonEmpty(getString(orderJson, "parentOrderID"), getString(orderJson, "parentOrderId")),
                    pairIndex);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean parseOrderIsBuy(JsonObject orderJson) {
        String side = getString(orderJson, "side").trim();
        if ("BUY".equalsIgnoreCase(side) || "LONG".equalsIgnoreCase(side)) {
            return true;
        }
        if ("SELL".equalsIgnoreCase(side) || "SHORT".equalsIgnoreCase(side)) {
            return false;
        }
        return getBoolean(orderJson, "isBuy");
    }

    private ExitOrderPairDefinition parseExitOrderPair(String symbol, JsonElement element, int fallbackIndex) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        try {
            JsonObject pairJson = element.getAsJsonObject();
            int index = getInt(pairJson, "index");
            if (index <= 0) {
                index = fallbackIndex;
            }
            ExitOrderLegDefinition stop = parseExitOrderLeg(pairJson.get("STOP"));
            if (stop == null) {
                stop = parseExitOrderLeg(pairJson.get("stop"));
            }
            ExitOrderLegDefinition limit = parseExitOrderLeg(pairJson.get("LIMIT"));
            if (limit == null) {
                limit = parseExitOrderLeg(pairJson.get("limit"));
            }
            if (stop == null && limit == null) {
                return null;
            }
            return new ExitOrderPairDefinition(
                    symbol,
                    index,
                    getString(pairJson, "source"),
                    getString(pairJson, "parentOrderID"),
                    stop,
                    limit);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private ExitOrderLegDefinition parseExitOrderLeg(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonObject()) {
            return null;
        }
        JsonObject legJson = element.getAsJsonObject();
        double price = getWirePrice(legJson, "price");
        if (price <= 0 || !Double.isFinite(price)) {
            return null;
        }
        return new ExitOrderLegDefinition(
                getString(legJson, "orderID"),
                price,
                getInt(legJson, "quantity"),
                getBoolean(legJson, "isBuy"));
    }

    private void notifyKeyLevelConfigListeners(String symbol, List<KeyLevelDefinition> levels) {
        for (KeyLevelConfigListener listener : keyLevelConfigListeners) {
            try {
                listener.onKeyLevelsChanged(symbol, levels);
            } catch (RuntimeException ignored) {
                // Continue notifying other listeners without writing diagnostic logs.
            }
        }
    }

    private void handleScreenLog(JsonObject json) {
        String message = getString(json, "message").trim();
        String symbol = SymbolUtils.cleanSymbol(getString(json, "symbol"));
        String source = getString(json, "source").trim();
        String level = getString(json, "level").trim();
        if (!message.isEmpty()) {
            String displaySource = level.isEmpty() ? source : source + " " + level.toUpperCase();
            PluginLog.action(symbol, displaySource.trim(), message);
        }
    }

    private void notifyKeyZoneConfigListeners(String symbol, List<KeyZoneDefinition> zones) {
        for (KeyZoneConfigListener listener : keyZoneConfigListeners) {
            try {
                listener.onKeyZonesChanged(symbol, zones);
            } catch (RuntimeException ignored) {
                // Continue notifying other listeners without writing diagnostic logs.
            }
        }
    }

    private void notifyMarketLevelConfigListeners(String symbol, MarketLevelDefinition marketLevels) {
        for (MarketLevelConfigListener listener : marketLevelConfigListeners) {
            try {
                listener.onMarketLevelsChanged(symbol, marketLevels);
            } catch (RuntimeException ignored) {
                // Continue notifying other listeners without writing diagnostic logs.
            }
        }
    }

    private void notifyExitOrderPairsConfigListeners(String symbol, List<ExitOrderPairDefinition> pairs) {
        for (ExitOrderPairsConfigListener listener : exitOrderPairsConfigListeners) {
            try {
                listener.onExitOrderPairsChanged(symbol, pairs);
            } catch (RuntimeException ignored) {
                // Continue notifying other listeners without writing diagnostic logs.
            }
        }
    }

    private void notifyAccountStateListeners(AccountStateDefinition state) {
        for (AccountStateListener listener : accountStateListeners) {
            try {
                listener.onAccountStateChanged(state);
            } catch (RuntimeException ignored) {
                // Continue notifying other listeners without writing diagnostic logs.
            }
        }
    }

    private void notifyVwapUpdateListeners(String symbol, VwapUpdateDefinition update) {
        Set<VwapUpdateListener> listeners = symbolToVwapUpdateListeners.get(symbol);
        if (listeners == null) {
            return;
        }
        for (VwapUpdateListener listener : listeners) {
            try {
                listener.onVwapChanged(update);
            } catch (RuntimeException ignored) {
                // Continue notifying other listeners without writing diagnostic logs.
            }
        }
    }

    private void notifyCorePlanListeners(String symbol, CorePlanConfigDefinition config) {
        Set<CorePlanConfigListener> listeners = symbolToCorePlanListeners.get(symbol);
        if (listeners == null) {
            return;
        }
        for (CorePlanConfigListener listener : listeners) {
            try {
                listener.onCorePlanChanged(config);
            } catch (RuntimeException ignored) {
                // Continue notifying other listeners without writing diagnostic logs.
            }
        }
    }

    private void notifyNewPositionListeners(String symbol, NewPositionDefinition position) {
        Set<NewPositionListener> listeners = symbolToNewPositionListeners.get(symbol);
        if (listeners == null) {
            return;
        }
        for (NewPositionListener listener : listeners) {
            try {
                listener.onNewPosition(position);
            } catch (RuntimeException ignored) {
                // Continue notifying other listeners without writing diagnostic logs.
            }
        }
    }

    private void notifyEntryRetestStateListeners(String symbol, EntryRetestState state) {
        Set<EntryRetestStateListener> listeners = symbolToEntryRetestStateListeners.get(symbol);
        if (listeners == null) {
            return;
        }
        for (EntryRetestStateListener listener : listeners) {
            try {
                listener.onEntryRetestStateChanged(state);
            } catch (RuntimeException ignored) {
                // Continue notifying other listeners without writing diagnostic logs.
            }
        }
    }

    private void notifyTradeButtonListeners(String symbol, List<TradebookButtonGroup> tradebooks) {
        Set<TradeButtonConfigListener> listeners = symbolToTradeButtonListeners.get(symbol);
        if (listeners == null) {
            return;
        }
        for (TradeButtonConfigListener listener : listeners) {
            try {
                listener.onTradeButtonsChanged(tradebooks);
            } catch (RuntimeException ignored) {
                // Continue notifying other listeners without writing diagnostic logs.
            }
        }
    }

    private boolean isMatchingWallBreakTradebook(TradebookButtonGroup tradebook, boolean bidBreakdown) {
        if (bidBreakdown && tradebook.isLong()) {
            return false;
        }
        if (!bidBreakdown && !tradebook.isLong()) {
            return false;
        }

        String searchable = (
                tradebook.getId() + " "
                        + tradebook.getLabel() + " "
                        + tradebook.getTradebookId() + " "
                        + tradebook.getTradebookName())
                .toLowerCase(Locale.US);
        if (bidBreakdown) {
            return searchable.contains("bidwallbreakdown")
                    || searchable.contains("bid wall breakdown")
                    || searchable.contains("bid_breakdown")
                    || searchable.contains("bid breakdown");
        }
        return searchable.contains("offerwallbreakout")
                || searchable.contains("offer wall breakout")
                || searchable.contains("offer_breakout")
                || searchable.contains("offer breakout");
    }

    private boolean matchesDirection(TradebookButtonGroup tradebook, PatternType patternType) {
        return patternType.getDirection() == Direction.LONG
                ? tradebook.isLong()
                : !tradebook.isLong();
    }

    private boolean isMatchingWallReversalTradebook(
            TradebookButtonGroup tradebook, PatternType patternType) {
        String searchable = searchableTradebookText(tradebook);
        if (searchable.contains("bookmapreversal")
                || searchable.contains("bookmap reversal")) {
            return true;
        }
        if (patternType.isBidWallPattern()) {
            return searchable.contains("range bound bid reversal")
                    || searchable.contains("rangeboundbidreversal")
                    || searchable.contains("bidreappear")
                    || searchable.contains("bid reappear")
                    || searchable.contains("bidstepup")
                    || searchable.contains("bid step up")
                    || searchable.contains("breakdown bid")
                    || searchable.contains("breakdownbidswinglow");
        }
        return searchable.contains("range bound offer reversal")
                || searchable.contains("rangeboundofferreversal")
                || searchable.contains("offerstepdownreappear")
                || searchable.contains("offer step down")
                || searchable.contains("offer reappear")
                || searchable.contains("offerreappear");
    }

    private String searchableTradebookText(TradebookButtonGroup tradebook) {
        return (tradebook.getId() + " "
                + tradebook.getLabel() + " "
                + tradebook.getTradebookId() + " "
                + tradebook.getTradebookName()).toLowerCase(Locale.US);
    }

    private List<String> getStringArray(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || !element.isJsonArray()) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item == null || item.isJsonNull()) {
                continue;
            }
            try {
                String value = item.getAsString();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            } catch (RuntimeException e) {
                // Ignore malformed entries and keep the rest of the config usable.
            }
        }
        return values;
    }

    private boolean isPriceBearingMessageType(String type) {
        return "key_levels_config".equals(type)
                || "key_level_config".equals(type)
                || "exit_order_pairs_config".equals(type)
                || "exit_order_pair_config".equals(type)
                || "account_state".equals(type)
                || "vwap_update".equals(type)
                || "core_plan_config".equals(type)
                || "new_position".equals(type);
    }

    private String getString(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull()) {
            return "";
        }
        try {
            return element.getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private double getWirePrice(JsonObject json, String field) {
        return BookmapPriceNormalizer.normalizeWirePrice(getDouble(json, field));
    }

    private double getDouble(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull()) {
            return Double.NaN;
        }
        try {
            return element.getAsDouble();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private int getInt(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull()) {
            return 0;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private boolean getBoolean(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull()) {
            return false;
        }
        try {
            return element.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Boolean getOptionalBoolean(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isBoolean()) {
            return null;
        }
        return element.getAsBoolean();
    }

    private EntryRetestMode getOptionalEntryRetestMode(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            if (element.getAsJsonPrimitive().isBoolean()) {
                // Legacy true had warn-only behavior.
                return element.getAsBoolean() ? EntryRetestMode.WARNING : EntryRetestMode.NO;
            }
            if (!element.getAsJsonPrimitive().isString()) {
                return null;
            }
            return EntryRetestMode.fromWireValue(element.getAsString());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private long getLong(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull()) {
            return 0L;
        }
        try {
            return element.getAsLong();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private String firstNonEmpty(String first, String second) {
        return first == null || first.isEmpty() ? second : first;
    }

    public static class OrderbookWallThreshold {
        private final boolean available;
        private final String symbol;
        private final double percentile;
        private final int absoluteMinSize;
        private final int percentileMinSize;
        private final int effectiveMinSize;
        private final List<Integer> largestLevelSizes;
        private final long timestamp;

        private OrderbookWallThreshold(
                boolean available,
                String symbol,
                double percentile,
                int absoluteMinSize,
                int percentileMinSize,
                int effectiveMinSize,
                List<Integer> largestLevelSizes,
                long timestamp) {
            this.available = available;
            this.symbol = symbol == null ? "" : symbol;
            this.percentile = percentile;
            this.absoluteMinSize = absoluteMinSize;
            this.percentileMinSize = percentileMinSize;
            this.effectiveMinSize = effectiveMinSize;
            this.largestLevelSizes = Collections.unmodifiableList(new ArrayList<>(largestLevelSizes));
            this.timestamp = timestamp;
        }

        private static OrderbookWallThreshold unavailable(
                String symbol,
                double percentile,
                int absoluteMinSize) {
            return new OrderbookWallThreshold(
                    false, symbol, percentile, absoluteMinSize, 0, absoluteMinSize,
                    Collections.emptyList(), 0);
        }

        private static OrderbookWallThreshold available(
                String symbol,
                double percentile,
                int absoluteMinSize,
                int percentileMinSize,
                int effectiveMinSize,
                List<Integer> largestLevelSizes,
                long timestamp) {
            return new OrderbookWallThreshold(
                    true, symbol, percentile, absoluteMinSize, percentileMinSize, effectiveMinSize,
                    largestLevelSizes, timestamp);
        }

        public boolean isAvailable() {
            return available;
        }

        public String getSymbol() {
            return symbol;
        }

        public double getPercentile() {
            return percentile;
        }

        public int getAbsoluteMinSize() {
            return absoluteMinSize;
        }

        public int getPercentileMinSize() {
            return percentileMinSize;
        }

        public int getEffectiveMinSize() {
            return effectiveMinSize;
        }

        public List<Integer> getLargestLevelSizes() {
            return largestLevelSizes;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }

    public enum EntryRetestMode {
        NO("no"),
        YES("yes"),
        WARNING("warning");

        private final String wireValue;

        EntryRetestMode(String wireValue) {
            this.wireValue = wireValue;
        }

        public String getWireValue() {
            return wireValue;
        }

        public boolean requiresRetest() {
            return this != NO;
        }

        private static EntryRetestMode fromWireValue(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.trim().toLowerCase(Locale.US);
            for (EntryRetestMode mode : values()) {
                if (mode.wireValue.equals(normalized)) {
                    return mode;
                }
            }
            return null;
        }
    }

    /** Immutable per-symbol readiness for the optional long/short entry retest rules. */
    public static final class EntryRetestState {
        private static final EntryRetestState READY =
                new EntryRetestState(EntryRetestMode.NO, EntryRetestMode.NO, true, true);

        private final EntryRetestMode bidRetestMode;
        private final EntryRetestMode offerRetestMode;
        private final boolean bidRetestSatisfied;
        private final boolean offerRetestSatisfied;

        private EntryRetestState(
                EntryRetestMode bidRetestMode,
                EntryRetestMode offerRetestMode,
                boolean bidRetestSatisfied,
                boolean offerRetestSatisfied) {
            this.bidRetestMode = bidRetestMode;
            this.offerRetestMode = offerRetestMode;
            this.bidRetestSatisfied = bidRetestSatisfied;
            this.offerRetestSatisfied = offerRetestSatisfied;
        }

        private static EntryRetestState ready() {
            return READY;
        }

        private EntryRetestState withConfiguration(
                EntryRetestMode newBidRetestMode, EntryRetestMode newOfferRetestMode) {
            boolean newBidRetestSatisfied = newBidRetestMode.requiresRetest()
                    ? bidRetestMode.requiresRetest() && bidRetestSatisfied
                    : true;
            boolean newOfferRetestSatisfied = newOfferRetestMode.requiresRetest()
                    ? offerRetestMode.requiresRetest() && offerRetestSatisfied
                    : true;
            if (bidRetestMode == newBidRetestMode
                    && offerRetestMode == newOfferRetestMode
                    && bidRetestSatisfied == newBidRetestSatisfied
                    && offerRetestSatisfied == newOfferRetestSatisfied) {
                return this;
            }
            return new EntryRetestState(
                    newBidRetestMode,
                    newOfferRetestMode,
                    newBidRetestSatisfied,
                    newOfferRetestSatisfied);
        }

        private EntryRetestState withRetestSatisfied(boolean bidRetest) {
            if (bidRetest) {
                if (!isBidRetestPending()) {
                    return this;
                }
                return new EntryRetestState(
                        bidRetestMode, offerRetestMode, true, offerRetestSatisfied);
            }
            if (!isOfferRetestPending()) {
                return this;
            }
            return new EntryRetestState(
                    bidRetestMode, offerRetestMode, bidRetestSatisfied, true);
        }

        public EntryRetestMode getBidRetestMode() {
            return bidRetestMode;
        }

        public EntryRetestMode getOfferRetestMode() {
            return offerRetestMode;
        }

        public boolean isBidRetestPending() {
            return bidRetestMode.requiresRetest() && !bidRetestSatisfied;
        }

        public boolean isOfferRetestPending() {
            return offerRetestMode.requiresRetest() && !offerRetestSatisfied;
        }

        public boolean isEntryRetestPending(boolean longEntry) {
            return longEntry ? isBidRetestPending() : isOfferRetestPending();
        }

        public boolean isEntryRetestBlocked(boolean longEntry) {
            EntryRetestMode mode = longEntry ? bidRetestMode : offerRetestMode;
            return mode == EntryRetestMode.YES && isEntryRetestPending(longEntry);
        }
    }

    private static class WallThreshold {
        private final int absoluteMinSize;
        private final int percentileMinSize;
        private final int effectiveMinSize;

        private WallThreshold(int absoluteMinSize, int percentileMinSize, int effectiveMinSize) {
            this.absoluteMinSize = absoluteMinSize;
            this.percentileMinSize = percentileMinSize;
            this.effectiveMinSize = effectiveMinSize;
        }

        private static WallThreshold from(OrderBookState orderBook, int absoluteMinSize, double percentile) {
            int normalizedAbsoluteMinSize = Math.max(0, absoluteMinSize);
            int percentileMinSize = percentile > 0
                    ? orderBook.getPercentileThreshold(percentile)
                    : 0;
            int effectiveMinSize = OrderBookState.combineSizeThresholds(
                    normalizedAbsoluteMinSize, percentileMinSize);
            return new WallThreshold(normalizedAbsoluteMinSize, percentileMinSize, effectiveMinSize);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
    }

    @Override
    public void onStart() {
    }

    public void shutdown() {
        try {
            stop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
