package com.bookmap.plugin.rong;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import com.bookmap.plugin.rong.tradebuttons.TradebookButtonGroup;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class SignalWebSocketServer extends WebSocketServer {

    private static final int DEFAULT_PROTECTED_ABSOLUTE_WALL_LEVELS = 2;

    @FunctionalInterface
    public interface TradeButtonConfigListener {
        void onTradeButtonsChanged(List<TradebookButtonGroup> tradebooks);
    }

    @FunctionalInterface
    public interface KeyLevelConfigListener {
        void onKeyLevelsChanged(String symbol, List<KeyLevelDefinition> levels);
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

    private final Object schedulerLock = new Object();
    private ScheduledExecutorService scheduler;
    private final Path breakoutLogFile;
    private BufferedWriter breakoutWriter;

    // Per-symbol state
    private final Map<String, OrderBookState> symbolToOrderBook = new ConcurrentHashMap<>();
    private final Map<String, Double> symbolToPips = new ConcurrentHashMap<>();
    private final Map<String, List<TradebookButtonGroup>> symbolToTradebooks = new ConcurrentHashMap<>();
    private final Map<String, List<KeyLevelDefinition>> symbolToKeyLevels = new ConcurrentHashMap<>();
    private final Map<String, MarketLevelDefinition> symbolToMarketLevels = new ConcurrentHashMap<>();
    private final Map<String, List<ExitOrderPairDefinition>> symbolToExitOrderPairs = new ConcurrentHashMap<>();
    private final Map<String, AccountStateDefinition> symbolToAccountState = new ConcurrentHashMap<>();
    private final Map<String, Set<TradeButtonConfigListener>> symbolToTradeButtonListeners = new ConcurrentHashMap<>();
    private final Set<KeyLevelConfigListener> keyLevelConfigListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<MarketLevelConfigListener> marketLevelConfigListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<ExitOrderPairsConfigListener> exitOrderPairsConfigListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<AccountStateListener> accountStateListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Broadcast config
    private final double orderbookPercentile;
    private final int orderbookIntervalMs;
    private final Set<WebSocket> orderbookSubscribers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private ScheduledFuture<?> orderbookBroadcastTask;
    private volatile boolean shuttingDown;

    public SignalWebSocketServer(int port, double orderbookPercentile, int orderbookIntervalMs) {
        super(new InetSocketAddress("127.0.0.1", port));
        setDaemon(true);
        setReuseAddr(true);
        this.orderbookPercentile = orderbookPercentile;
        this.orderbookIntervalMs = orderbookIntervalMs;
        Path signalsDir = Paths.get(System.getProperty("user.home"), "Bookmap", "bookmap-signals");
        this.breakoutLogFile = signalsDir.resolve("breakout.jsonl");
    }

    /** Register a symbol's order book and pips multiplier. */
    public void registerSymbol(String symbol, OrderBookState orderBook, double pips) {
        symbolToOrderBook.put(symbol, orderBook);
        symbolToPips.put(symbol, pips);
        PluginLog.info("[Rong] Registered symbol: " + symbol);
    }

    /** Unregister a symbol when its plugin instance stops. */
    public void unregisterSymbol(String symbol) {
        symbolToOrderBook.remove(symbol);
        symbolToPips.remove(symbol);
        PluginLog.info("[Rong] Unregistered symbol: " + symbol);
    }

    public boolean appendOrderbookSnapshot(String symbol, JsonObject target, int minimumWallSize) {
        return appendOrderbookSnapshot(
                symbol, target, minimumWallSize, DEFAULT_PROTECTED_ABSOLUTE_WALL_LEVELS);
    }

    public boolean appendOrderbookSnapshot(
            String symbol,
            JsonObject target,
            int minimumWallSize,
            int protectedAbsoluteWallLevels) {
        JsonObject snapshot = buildOrderbookSnapshot(symbol, minimumWallSize, protectedAbsoluteWallLevels);
        if (snapshot == null) {
            return false;
        }
        target.add("orderbook", snapshot);
        return true;
    }

    public OrderbookWallThreshold getOrderbookWallThreshold(String symbol, int minimumWallSize) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        int absoluteMinSize = Math.max(0, minimumWallSize);
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
                    System.currentTimeMillis());
        }
    }

    private JsonObject buildOrderbookSnapshot(
            String symbol,
            int minimumWallSize,
            int protectedAbsoluteWallLevels) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        if (cleanSymbol.isEmpty()) {
            return null;
        }

        OrderBookState orderBook = symbolToOrderBook.get(cleanSymbol);
        Double pips = symbolToPips.get(cleanSymbol);
        if (orderBook == null || pips == null || pips <= 0 || !Double.isFinite(pips)) {
            return null;
        }

        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("symbol", cleanSymbol);
        snapshot.addProperty("timestamp", System.currentTimeMillis());
        snapshot.addProperty("wallThreshold", minimumWallSize);
        snapshot.addProperty("absoluteWallThreshold", minimumWallSize);
        snapshot.addProperty("percentile", orderbookPercentile);
        snapshot.addProperty("protectedAbsoluteWallLevels", Math.max(0, protectedAbsoluteWallLevels));

        synchronized (orderBook) {
            WallThreshold threshold = WallThreshold.from(orderBook, minimumWallSize, orderbookPercentile);
            snapshot.addProperty("percentileWallThreshold", threshold.percentileMinSize);
            snapshot.addProperty("effectiveWallThreshold", threshold.effectiveMinSize);
            Integer bestBidTick = orderBook.getBestBid();
            Integer bestAskTick = orderBook.getBestAsk();
            if (bestBidTick != null) {
                snapshot.addProperty("bestBid", bestBidTick * pips);
            }
            if (bestAskTick != null) {
                snapshot.addProperty("bestAsk", bestAskTick * pips);
            }
            snapshot.add("largeBids",
                    buildWallLevels(orderBook.getBids(), pips, threshold, protectedAbsoluteWallLevels));
            snapshot.add("largeAsks",
                    buildWallLevels(orderBook.getAsks(), pips, threshold, protectedAbsoluteWallLevels));
        }
        return snapshot;
    }

    private JsonArray buildWallLevels(
            NavigableMap<Integer, Integer> levels,
            double pips,
            WallThreshold threshold,
            int protectedAbsoluteWallLevels) {
        JsonArray result = new JsonArray();
        int protectedLevelsIncluded = 0;
        int protectedLevelLimit = Math.max(0, protectedAbsoluteWallLevels);
        for (Map.Entry<Integer, Integer> entry : levels.entrySet()) {
            int size = entry.getValue();
            boolean passesEffectiveThreshold = size >= threshold.effectiveMinSize;
            // Keep the nearest absolute-floor levels so a crowded symbol does not hide 5K candidates entirely.
            boolean protectedAbsoluteLevel = size >= threshold.absoluteMinSize
                    && size < threshold.effectiveMinSize
                    && protectedLevelsIncluded < protectedLevelLimit;
            if (!passesEffectiveThreshold && !protectedAbsoluteLevel) {
                continue;
            }
            if (protectedAbsoluteLevel) {
                protectedLevelsIncluded++;
            }
            JsonArray level = new JsonArray();
            level.add(entry.getKey() * pips);
            level.add(size);
            result.add(level);
        }
        return result;
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

    public void registerKeyLevelConfigListener(KeyLevelConfigListener listener) {
        keyLevelConfigListeners.add(listener);
        for (Map.Entry<String, List<KeyLevelDefinition>> entry : symbolToKeyLevels.entrySet()) {
            listener.onKeyLevelsChanged(entry.getKey(), entry.getValue());
        }
    }

    public void unregisterKeyLevelConfigListener(KeyLevelConfigListener listener) {
        keyLevelConfigListeners.remove(listener);
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

    public ExitWallAdjustment resolveExitWallAdjustment(
            String symbol,
            int pairIndex,
            int minimumWallSize,
            double targetOffset) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        if (cleanSymbol.isEmpty()) {
            return ExitWallAdjustment.unavailable("missing symbol");
        }

        AccountStateDefinition state = symbolToAccountState.get(cleanSymbol);
        if (state == null || !state.hasOpenPosition()) {
            return ExitWallAdjustment.unavailable("no open position for " + cleanSymbol);
        }

        AccountPositionDefinition position = state.getPosition();
        boolean longPosition = position.getNetQuantity() > 0;
        boolean expectedLimitBuySide = !longPosition;
        LimitOrderRef limitOrder = findPairLimitOrder(cleanSymbol, state, pairIndex, expectedLimitBuySide);
        if (limitOrder == null) {
            return ExitWallAdjustment.unavailable("no LIMIT order found for pair " + pairIndex);
        }

        OrderBookState orderBook = symbolToOrderBook.get(cleanSymbol);
        Double pips = symbolToPips.get(cleanSymbol);
        if (orderBook == null || pips == null || pips <= 0 || !Double.isFinite(pips)) {
            return ExitWallAdjustment.unavailable("order book is not ready for " + cleanSymbol);
        }

        boolean bidWall = !longPosition;
        OrderBookState.DepthLevel wall = orderBook.findFirstLevelAtLeast(bidWall, minimumWallSize);
        if (wall == null) {
            return ExitWallAdjustment.unavailable(
                    "no " + (bidWall ? "bid" : "offer") + " wall >= " + minimumWallSize);
        }

        int offsetTicks = Math.max(1, (int) Math.ceil((targetOffset / pips) - 1e-9));
        int targetTick = longPosition
                ? wall.getPriceTick() - offsetTicks
                : wall.getPriceTick() + offsetTicks;
        if (targetTick <= 0) {
            return ExitWallAdjustment.unavailable("computed target price is not valid");
        }

        return ExitWallAdjustment.available(
                cleanSymbol,
                pairIndex,
                longPosition,
                bidWall,
                wall.getPriceTick(),
                wall.getSize(),
                wall.getPriceTick() * pips,
                targetTick * pips,
                targetOffset,
                limitOrder);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        PluginLog.info("[Rong] Client connected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        orderbookSubscribers.remove(conn);
        PluginLog.info("[Rong] Client disconnected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        String trimmed = message.trim();
        JsonObject json = parseJsonObject(trimmed);
        if (json != null) {
            String type = getString(json, "type");
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
            if ("account_state".equals(type)) {
                handleAccountState(json);
                return;
            }
        }
        if (trimmed.contains("\"subscribe\"") && trimmed.contains("\"orderbook\"")) {
            orderbookSubscribers.add(conn);
            ensureOrderbookBroadcast();
            conn.send("{\"type\":\"subscribed\",\"channel\":\"orderbook\",\"intervalMs\":" + orderbookIntervalMs + ",\"percentile\":" + orderbookPercentile + "}");
            PluginLog.info("[Rong] Client subscribed to orderbook (interval=" + orderbookIntervalMs + "ms, percentile=" + orderbookPercentile + ")");
        } else if (trimmed.contains("\"unsubscribe\"") && trimmed.contains("\"orderbook\"")) {
            orderbookSubscribers.remove(conn);
            conn.send("{\"type\":\"unsubscribed\",\"channel\":\"orderbook\"}");
            PluginLog.info("[Rong] Client unsubscribed from orderbook");
        }
    }

    private JsonObject parseJsonObject(String message) {
        try {
            JsonElement element = JsonParser.parseString(message);
            if (element != null && element.isJsonObject()) {
                return element.getAsJsonObject();
            }
        } catch (RuntimeException e) {
            PluginLog.error("[TradeButton] Failed to parse WebSocket message: " + e.getMessage());
        }
        return null;
    }

    private void handleTradeButtonConfig(JsonObject json) {
        String symbol = SymbolUtils.cleanSymbol(getString(json, "symbol"));
        if (symbol.isEmpty()) {
            PluginLog.error("[TradeButton] Ignoring button config with missing symbol");
            return;
        }

        JsonArray tradebooksArray = null;
        JsonElement tradebooksElement = json.get("tradebooks");
        if (tradebooksElement != null && tradebooksElement.isJsonArray()) {
            tradebooksArray = tradebooksElement.getAsJsonArray();
        }
        if (tradebooksArray == null) {
            PluginLog.error("[TradeButton] Ignoring button config with missing tradebooks array for " + symbol);
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
            tradebooks.add(new TradebookButtonGroup(
                    id,
                    label,
                    getString(tradebookJson, "side"),
                    tradebookId,
                    tradebookName,
                    entryMethods));
        }

        List<TradebookButtonGroup> immutableTradebooks = Collections.unmodifiableList(tradebooks);
        symbolToTradebooks.put(symbol, immutableTradebooks);
        notifyTradeButtonListeners(symbol, immutableTradebooks);
        PluginLog.info("[TradeButton] Updated " + tradebooks.size() + " tradebook button groups for " + symbol);
    }

    private void handleKeyLevelsConfig(JsonObject json) {
        String symbol = SymbolUtils.cleanSymbol(getString(json, "symbol"));
        if (symbol.isEmpty()) {
            PluginLog.error("[KeyLevel] Ignoring config with missing symbol");
            return;
        }

        JsonElement levelsElement = json.get("levels");
        if (levelsElement == null) {
            levelsElement = json.get("keyLevels");
        }
        if (levelsElement == null || !levelsElement.isJsonArray()) {
            PluginLog.error("[KeyLevel] Ignoring config with missing levels array for " + symbol);
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

        MarketLevelDefinition marketLevels = parseMarketLevels(symbol, json);
        symbolToMarketLevels.put(symbol, marketLevels);
        notifyMarketLevelConfigListeners(symbol, marketLevels);

        PluginLog.info("[KeyLevel] Updated " + levels.size() + " websocket key levels for " + symbol
                + " and " + marketLevels.getCamPivots().size() + " cam pivot(s)");
    }

    private KeyLevelDefinition parseKeyLevel(String symbol, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        try {
            if (element.isJsonPrimitive()) {
                double price = element.getAsDouble();
                return price > 0 ? new KeyLevelDefinition(symbol, price, null) : null;
            }
            if (!element.isJsonObject()) {
                return null;
            }
            JsonObject levelJson = element.getAsJsonObject();
            double price = getDouble(levelJson, "price");
            if (price <= 0) {
                return null;
            }
            String label = getString(levelJson, "label");
            return new KeyLevelDefinition(symbol, price, label);
        } catch (RuntimeException e) {
            PluginLog.error("[KeyLevel] Ignoring malformed level for " + symbol + ": " + e.getMessage());
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
            double price = getDouble(pivotsObject, level);
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
            double nestedValue = getDouble(pairObject, pairField);
            if (Double.isFinite(nestedValue)) {
                return nestedValue;
            }
        }
        double primaryValue = getDouble(root, primaryTopLevelField);
        if (Double.isFinite(primaryValue)) {
            return primaryValue;
        }
        return getDouble(root, secondaryTopLevelField);
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
            PluginLog.error("[ExitOrder] Ignoring config with missing symbol");
            return;
        }

        JsonElement pairsElement = json.get("pairs");
        if (pairsElement == null || !pairsElement.isJsonArray()) {
            PluginLog.error("[ExitOrder] Ignoring config with missing pairs array for " + symbol);
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
        PluginLog.info("[ExitOrder] Updated " + pairs.size() + " websocket exit pair(s) for " + symbol);
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
            PluginLog.error("[AccountState] Ignoring update with missing symbol");
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
        double averagePrice = getDouble(positionJson, "averagePrice");
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
            double price = getDouble(executionJson, "price");
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
            PluginLog.error("[AccountState] Ignoring malformed execution for " + symbol + ": " + e.getMessage());
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
                    getDouble(orderJson, "price"),
                    Double.isFinite(quantity) ? quantity : 0,
                    parseOrderIsBuy(orderJson),
                    getString(orderJson, "source"),
                    firstNonEmpty(getString(orderJson, "parentOrderID"), getString(orderJson, "parentOrderId")),
                    pairIndex);
        } catch (RuntimeException e) {
            PluginLog.error("[AccountState] Ignoring malformed order for " + symbol + ": " + e.getMessage());
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

    private LimitOrderRef findPairLimitOrder(
            String symbol,
            AccountStateDefinition state,
            int pairIndex,
            boolean expectedBuySide) {
        LimitOrderRef fallback = null;
        for (AccountOrderDefinition order : state.getOpenOrders()) {
            if (order == null || order.getPairIndex() != pairIndex || !isLimitOrder(order)) {
                continue;
            }
            LimitOrderRef ref = new LimitOrderRef(
                    order.getOrderId(),
                    order.getParentOrderId(),
                    order.getQuantity(),
                    order.getPrice(),
                    order.isBuy(),
                    order.getSource());
            if (order.isBuy() == expectedBuySide) {
                return ref;
            }
            if (fallback == null) {
                fallback = ref;
            }
        }
        if (fallback != null) {
            return fallback;
        }

        List<ExitOrderPairDefinition> pairs =
                symbolToExitOrderPairs.getOrDefault(symbol, Collections.emptyList());
        for (ExitOrderPairDefinition pair : pairs) {
            if (pair == null || pair.getIndex() != pairIndex || pair.getLimit() == null) {
                continue;
            }
            ExitOrderLegDefinition limit = pair.getLimit();
            LimitOrderRef ref = new LimitOrderRef(
                    limit.getOrderId(),
                    pair.getParentOrderId(),
                    limit.getQuantity(),
                    limit.getPrice(),
                    limit.isBuy(),
                    pair.getSource());
            if (limit.isBuy() == expectedBuySide) {
                return ref;
            }
            if (fallback == null) {
                fallback = ref;
            }
        }
        return fallback;
    }

    private boolean isLimitOrder(AccountOrderDefinition order) {
        return "LIMIT".equalsIgnoreCase(order.getRole())
                || "LIMIT".equalsIgnoreCase(order.getOrderType());
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
            PluginLog.error("[ExitOrder] Ignoring malformed pair for " + symbol + ": " + e.getMessage());
            return null;
        }
    }

    private ExitOrderLegDefinition parseExitOrderLeg(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonObject()) {
            return null;
        }
        JsonObject legJson = element.getAsJsonObject();
        double price = getDouble(legJson, "price");
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
            } catch (RuntimeException e) {
                PluginLog.error("[KeyLevel] Failed to update listener for " + symbol + ": " + e.getMessage());
            }
        }
    }

    private void notifyMarketLevelConfigListeners(String symbol, MarketLevelDefinition marketLevels) {
        for (MarketLevelConfigListener listener : marketLevelConfigListeners) {
            try {
                listener.onMarketLevelsChanged(symbol, marketLevels);
            } catch (RuntimeException e) {
                PluginLog.error("[MarketLevel] Failed to update listener for " + symbol + ": " + e.getMessage());
            }
        }
    }

    private void notifyExitOrderPairsConfigListeners(String symbol, List<ExitOrderPairDefinition> pairs) {
        for (ExitOrderPairsConfigListener listener : exitOrderPairsConfigListeners) {
            try {
                listener.onExitOrderPairsChanged(symbol, pairs);
            } catch (RuntimeException e) {
                PluginLog.error("[ExitOrder] Failed to update listener for " + symbol + ": " + e.getMessage());
            }
        }
    }

    private void notifyAccountStateListeners(AccountStateDefinition state) {
        for (AccountStateListener listener : accountStateListeners) {
            try {
                listener.onAccountStateChanged(state);
            } catch (RuntimeException e) {
                PluginLog.error("[AccountState] Failed to update listener for "
                        + state.getSymbol() + ": " + e.getMessage());
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
            } catch (RuntimeException e) {
                PluginLog.error("[TradeButton] Failed to update button listener for " + symbol + ": " + e.getMessage());
            }
        }
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
        private final long timestamp;

        private OrderbookWallThreshold(
                boolean available,
                String symbol,
                double percentile,
                int absoluteMinSize,
                int percentileMinSize,
                int effectiveMinSize,
                long timestamp) {
            this.available = available;
            this.symbol = normalize(symbol);
            this.percentile = percentile;
            this.absoluteMinSize = absoluteMinSize;
            this.percentileMinSize = percentileMinSize;
            this.effectiveMinSize = effectiveMinSize;
            this.timestamp = timestamp;
        }

        private static OrderbookWallThreshold unavailable(
                String symbol,
                double percentile,
                int absoluteMinSize) {
            return new OrderbookWallThreshold(
                    false, symbol, percentile, absoluteMinSize, 0, absoluteMinSize, 0);
        }

        private static OrderbookWallThreshold available(
                String symbol,
                double percentile,
                int absoluteMinSize,
                int percentileMinSize,
                int effectiveMinSize,
                long timestamp) {
            return new OrderbookWallThreshold(
                    true, symbol, percentile, absoluteMinSize, percentileMinSize, effectiveMinSize, timestamp);
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

        public long getTimestamp() {
            return timestamp;
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
            int effectiveMinSize = Math.max(normalizedAbsoluteMinSize, percentileMinSize);
            return new WallThreshold(normalizedAbsoluteMinSize, percentileMinSize, effectiveMinSize);
        }
    }

    private static class LimitOrderRef {
        private final String orderId;
        private final String parentOrderId;
        private final double quantity;
        private final double currentPrice;
        private final boolean buy;
        private final String source;

        private LimitOrderRef(
                String orderId,
                String parentOrderId,
                double quantity,
                double currentPrice,
                boolean buy,
                String source) {
            this.orderId = normalize(orderId);
            this.parentOrderId = normalize(parentOrderId);
            this.quantity = quantity;
            this.currentPrice = currentPrice;
            this.buy = buy;
            this.source = normalize(source);
        }
    }

    public static class ExitWallAdjustment {
        private final boolean available;
        private final String reason;
        private final String symbol;
        private final int pairIndex;
        private final boolean longPosition;
        private final boolean bidWall;
        private final int wallPriceTick;
        private final int wallSize;
        private final double wallPrice;
        private final double targetPrice;
        private final double offset;
        private final String limitOrderId;
        private final String parentOrderId;
        private final double limitOrderQuantity;
        private final double currentLimitPrice;
        private final boolean limitOrderBuy;
        private final String source;

        private ExitWallAdjustment(
                boolean available,
                String reason,
                String symbol,
                int pairIndex,
                boolean longPosition,
                boolean bidWall,
                int wallPriceTick,
                int wallSize,
                double wallPrice,
                double targetPrice,
                double offset,
                String limitOrderId,
                String parentOrderId,
                double limitOrderQuantity,
                double currentLimitPrice,
                boolean limitOrderBuy,
                String source) {
            this.available = available;
            this.reason = normalize(reason);
            this.symbol = normalize(symbol);
            this.pairIndex = pairIndex;
            this.longPosition = longPosition;
            this.bidWall = bidWall;
            this.wallPriceTick = wallPriceTick;
            this.wallSize = wallSize;
            this.wallPrice = wallPrice;
            this.targetPrice = targetPrice;
            this.offset = offset;
            this.limitOrderId = normalize(limitOrderId);
            this.parentOrderId = normalize(parentOrderId);
            this.limitOrderQuantity = limitOrderQuantity;
            this.currentLimitPrice = currentLimitPrice;
            this.limitOrderBuy = limitOrderBuy;
            this.source = normalize(source);
        }

        private static ExitWallAdjustment unavailable(String reason) {
            return new ExitWallAdjustment(
                    false, reason, "", 0, false, false, 0, 0, 0,
                    0, 0, "", "", 0, 0, false, "");
        }

        private static ExitWallAdjustment available(
                String symbol,
                int pairIndex,
                boolean longPosition,
                boolean bidWall,
                int wallPriceTick,
                int wallSize,
                double wallPrice,
                double targetPrice,
                double offset,
                LimitOrderRef limitOrder) {
            return new ExitWallAdjustment(
                    true,
                    "",
                    symbol,
                    pairIndex,
                    longPosition,
                    bidWall,
                    wallPriceTick,
                    wallSize,
                    wallPrice,
                    targetPrice,
                    offset,
                    limitOrder.orderId,
                    limitOrder.parentOrderId,
                    limitOrder.quantity,
                    limitOrder.currentPrice,
                    limitOrder.buy,
                    limitOrder.source);
        }

        public boolean isAvailable() {
            return available;
        }

        public String getReason() {
            return reason;
        }

        public String getSymbol() {
            return symbol;
        }

        public int getPairIndex() {
            return pairIndex;
        }

        public boolean isLongPosition() {
            return longPosition;
        }

        public boolean isBidWall() {
            return bidWall;
        }

        public int getWallPriceTick() {
            return wallPriceTick;
        }

        public int getWallSize() {
            return wallSize;
        }

        public double getWallPrice() {
            return wallPrice;
        }

        public double getTargetPrice() {
            return targetPrice;
        }

        public double getOffset() {
            return offset;
        }

        public String getLimitOrderId() {
            return limitOrderId;
        }

        public String getParentOrderId() {
            return parentOrderId;
        }

        public double getLimitOrderQuantity() {
            return limitOrderQuantity;
        }

        public double getCurrentLimitPrice() {
            return currentLimitPrice;
        }

        public boolean isLimitOrderBuy() {
            return limitOrderBuy;
        }

        public String getSource() {
            return source;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }

    private synchronized void ensureOrderbookBroadcast() {
        if (shuttingDown || orderbookBroadcastTask != null) {
            return;
        }
        try {
            orderbookBroadcastTask = getOrCreateScheduler().scheduleAtFixedRate(
                () -> sendOrderbookSnapshots(),
                0, orderbookIntervalMs, TimeUnit.MILLISECONDS
            );
        } catch (RejectedExecutionException e) {
            PluginLog.error("[Rong] Failed to schedule orderbook broadcast: " + e.getMessage());
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        PluginLog.error("[Rong] WebSocket error: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        if (shuttingDown) {
            return;
        }
        PluginLog.info("[Rong] WebSocket server started on port " + getPort());
        try {
            Files.createDirectories(breakoutLogFile.getParent());
            breakoutWriter = Files.newBufferedWriter(breakoutLogFile,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            PluginLog.info("[Rong] Logging to " + breakoutLogFile.getParent());
        } catch (IOException e) {
            PluginLog.error("[Rong] Failed to open log files: " + e.getMessage());
        }
    }

    public void broadcastSignal(String json) {
        writeToFile(breakoutWriter, json);
        broadcast(json);
    }

    public void shutdown() {
        shuttingDown = true;
        synchronized (schedulerLock) {
            if (orderbookBroadcastTask != null) {
                orderbookBroadcastTask.cancel(true);
                orderbookBroadcastTask = null;
            }
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
            }
        }
        closeWriter(breakoutWriter);
        try {
            stop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Send orderbook snapshots for ALL registered symbols. */
    private void sendOrderbookSnapshots() {
        if (orderbookSubscribers.isEmpty()) return;
        for (Map.Entry<String, OrderBookState> entry : symbolToOrderBook.entrySet()) {
            String symbol = entry.getKey();
            OrderBookState orderBook = entry.getValue();
            Double pips = symbolToPips.get(symbol);
            if (pips == null) continue;

            String orderbookJson = orderBook.toJson(symbol, pips, orderbookPercentile);
            for (WebSocket conn : orderbookSubscribers) {
                if (conn.isOpen()) {
                    // Orderbook snapshot sending is disabled; keep snapshot computation available for now.
                    // conn.send(orderbookJson);
                }
            }
        }
    }

    private void writeToFile(BufferedWriter writer, String json) {
        if (writer != null) {
            try {
                writer.write(json);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                PluginLog.error("[Rong] Failed to write to log: " + e.getMessage());
            }
        }
    }

    private void closeWriter(BufferedWriter writer) {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                PluginLog.error("[Rong] Failed to close log file: " + e.getMessage());
            }
        }
    }

    private ScheduledExecutorService getOrCreateScheduler() {
        synchronized (schedulerLock) {
            if (scheduler == null || scheduler.isShutdown()) {
                scheduler = Executors.newScheduledThreadPool(2, r -> {
                    Thread t = new Thread(r, "ws-scheduler");
                    t.setDaemon(true);
                    return t;
                });
            }
            return scheduler;
        }
    }
}
