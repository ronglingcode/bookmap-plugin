package com.bookmap.plugin.rong;

import com.bookmap.plugin.rong.miniviteapp.runtime.NativeRuntime;
import com.bookmap.plugin.rong.miniviteapp.runtime.LocalCredentials;
import com.bookmap.plugin.rong.miniviteapp.runtime.TradingRuntime;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.NativeViews;
import com.bookmap.plugin.rong.miniviteapp.models.Candle;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;
import com.bookmap.plugin.rong.orderwall.OrderWallChangeSound;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.messages.Layer1ApiSoundAlertMessage;
import static com.bookmap.plugin.rong.miniviteapp.models.DomainJson.*;

/** Bookmap-only view/sound adapter. Runtime ownership follows the plugin's first/last attachment. */
public final class NativeTradingAdapter implements AutoCloseable {
    private final SignalWebSocketServer display;
    private final Map<String, Api> apis = new ConcurrentHashMap<>();
    private final byte[] sound = OrderWallChangeSound.createAlertSound();
    private final IndicatorConfig settings;
    private final NativeConnectionStatus connectionStatus;
    private volatile NativeRuntime runtime;
    private volatile boolean closed;
    public NativeTradingAdapter(SignalWebSocketServer display, IndicatorConfig settings) {
        this(display, settings, new NativeConnectionStatus());
    }
    public NativeTradingAdapter(SignalWebSocketServer display, IndicatorConfig settings,
            NativeConnectionStatus connectionStatus) {
        this.display = display; this.settings = settings; this.connectionStatus = connectionStatus;
        display.setTradingDispatch(this::dispatch); display.setManualInputs(symbol -> runtime == null || closed ? new JsonObject() : runtime.trading.manualInputs(symbol)); display.setRetestNotification(this::notifyUser);
        display.setSessionLevelsProvider(symbol -> {
            NativeRuntime current = runtime;
            return current == null || closed ? null : current.trading.sessionLevels(symbol);
        });
    }
    public void attach(String symbol, Api api) { apis.put(symbol, api); }
    public void detach(String symbol) { apis.remove(symbol); }
    public void start() {
        connectionStatus.setStarting();
        try { runtime = new NativeRuntime(new TradingRuntime.Events() {
            public void message(JsonObject value) { NativeTradingAdapter.this.accept(value); }
            public void log(String symbol, String value) { PluginLog.action(symbol, value); }
            public void detail(String symbol, String value) { PluginLog.detail(symbol, value); }
            public void summary(String symbol, String value, String screenMessage) { PluginLog.summary(symbol, value, screenMessage); }
            public void transition(String symbol, String value) { PluginLog.transition(symbol, value); }
            public void aggregate(String symbol, String group, String value, String screenMessage) { PluginLog.aggregate(symbol, group, value, screenMessage); }
            public void notify(String symbol, String value) { notifyUser(symbol, value); }
            public void status(String source, String value) { connectionStatus.update(source, value); }
            public void minuteBarsLoaded(String symbol, String date, List<Candle> bars) {
                if (!closed) display.updateRegularSessionMinuteBars(symbol, date, bars);
            }
        }); runtime.start(); }
        catch (Exception error) { connectionStatus.setUnavailable("startup failed"); PluginLog.action("", "Native startup unavailable. Check local secrets at " + LocalCredentials.defaultPath() + "; then use Restart Native Trading."); }
    }
    public void dispatch(JsonObject action) { if (runtime == null || closed || !runtime.trading.dispatch(action)) PluginLog.action(string(action, "symbol"), "Native runtime unavailable; no native action dispatched"); }
    public void observeBookmapTrade(String symbol, double price, long timestampNs) {
        NativeRuntime current = runtime;
        if (current != null && !closed) current.trading.observeBookmapTrade(symbol, price, timestampNs / 1_000_000L);
    }
    public void authorize(String callbackUrl) { if (runtime != null && !closed) runtime.trading.exchangeAuthorizationCode(callbackUrl); }
    public void openAuthorization() {
        if (runtime == null || closed) { PluginLog.action("", "Load local secrets and restart native trading first"); return; }
        try { java.awt.Desktop.getDesktop().browse(runtime.trading.authorizationUrl()); }
        catch (Exception error) { PluginLog.action("", "Cannot open Schwab authorization: " + error.getClass().getSimpleName()); }
    }
    public void resetAfterBrokerReview() { if (runtime != null && !closed) runtime.trading.resetAfterBrokerReview(); }
    public String exportExecutions(com.bookmap.plugin.rong.miniviteapp.core.account.ExecutionExports.Format format) {
        NativeRuntime current = runtime;
        if (current == null || closed) throw new IllegalStateException("Native trading is unavailable");
        return current.trading.exportExecutions(format);
    }
    private void accept(JsonObject value) {
        if (closed) return; String type = string(value, "type");
        if (type.equals("account_ready") || type.equals("market_ready") || type.equals("market_update") || type.equals("command_state")) {
            if (type.equals("market_update")) display.acceptLocalMessage(NativeViews.premarketLevels(value));
            NativeViews.project(value).forEach(display::acceptLocalMessage);
        }
    }
    private void notifyUser(String symbol, String text) {
        if (closed) return; PluginLog.action(symbol, text); if (!settings.isEnabled(IndicatorConfig.TRADING_NOTIFICATION_SOUND)) return;
        Api api = apis.get(symbol); if (api == null) api = apis.values().stream().findFirst().orElse(null);
        if (api != null) try { api.sendUserMessage(new Layer1ApiSoundAlertMessage(sound, symbol + " " + text, 1, Duration.ZERO, null, RongPlugin.class, "native:" + symbol + ":" + System.nanoTime())); }
        catch (RuntimeException error) { PluginLog.action(symbol, "Trading sound unavailable: " + error.getClass().getSimpleName()); }
    }
    @Override public void close() { closed = true; connectionStatus.setUnavailable("stopped"); if (runtime != null) runtime.close(); apis.clear(); }
}
