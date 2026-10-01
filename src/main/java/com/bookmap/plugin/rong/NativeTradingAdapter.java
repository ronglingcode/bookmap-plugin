package com.bookmap.plugin.rong;

import com.bookmap.plugin.rong.miniviteapp.runtime.NativeRuntime;
import com.bookmap.plugin.rong.miniviteapp.runtime.LocalCredentials;
import com.bookmap.plugin.rong.miniviteapp.runtime.TradingRuntime;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.NativeViews;
import com.google.gson.JsonObject;
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
    private final Map<String, Double> positions = new ConcurrentHashMap<>();
    private final byte[] sound = OrderWallChangeSound.createAlertSound();
    private final IndicatorConfig settings;
    private volatile NativeRuntime runtime;
    private volatile boolean closed;
    public NativeTradingAdapter(SignalWebSocketServer display, IndicatorConfig settings) { this.display = display; this.settings = settings; display.setTradingDispatch(this::dispatch); display.setManualInputs(symbol -> runtime == null || closed ? new JsonObject() : runtime.trading.manualInputs(symbol)); display.setRetestNotification(this::notifyUser); }
    public void attach(String symbol, Api api) { apis.put(symbol, api); }
    public void detach(String symbol) { apis.remove(symbol); }
    public void start() {
        try { runtime = new NativeRuntime(new TradingRuntime.Events() {
            public void message(JsonObject value) { accept(value); }
            public void log(String symbol, String value) { PluginLog.action(symbol, value); }
            public void notify(String symbol, String value) { notifyUser(symbol, value); }
        }); runtime.start(); }
        catch (Exception error) { PluginLog.action("", "Native startup unavailable. Check local secrets at " + LocalCredentials.defaultPath() + "; then use Restart Native Trading."); }
    }
    public void dispatch(JsonObject action) { if (runtime == null || closed) PluginLog.action(string(action, "symbol"), "Native runtime unavailable; no action sent"); else runtime.trading.dispatch(action); }
    public void authorize(String callbackUrl) { if (runtime != null && !closed) runtime.trading.exchangeAuthorizationCode(callbackUrl); }
    public void openAuthorization() {
        if (runtime == null || closed) { PluginLog.action("", "Load local secrets and restart native trading first"); return; }
        try { java.awt.Desktop.getDesktop().browse(runtime.trading.authorizationUrl()); }
        catch (Exception error) { PluginLog.action("", "Cannot open Schwab authorization: " + error.getClass().getSimpleName()); }
    }
    public void resetAfterBrokerReview() { if (runtime != null && !closed) runtime.trading.resetAfterBrokerReview(); }
    private void accept(JsonObject value) {
        if (closed) return; String type = string(value, "type");
        if (type.equals("account_ready") || type.equals("market_ready") || type.equals("market_update") || type.equals("command_state")) {
            NativeViews.project(value).forEach(display::acceptLocalMessage);
            if (type.equals("account_ready")) { String symbol = string(value, "symbol"); JsonObject position = object(object(object(value, "account"), "positions"), symbol); double net = number(position, "netQuantity"); Double previous = positions.put(symbol, net);
                if (previous != null && net != 0 && (previous == 0 || Math.signum(previous) != Math.signum(net))) { JsonObject signal = message("new_position"); signal.addProperty("symbol", symbol); signal.addProperty("isLong", net > 0); signal.addProperty("netQuantity", net); signal.addProperty("averagePrice", number(position, "averagePrice")); signal.addProperty("timestamp", number(value, "timestamp")); signal.addProperty("eventId", symbol + ":" + number(value, "timestamp")); display.acceptLocalMessage(signal); }
            }
        } else if (type.equals("execution_result") || type.equals("execution_blocked")) {
            PluginLog.action(string(value, "symbol"), type + " " + string(value, "action") + " " + string(value, "outcome") + " " + string(value, "reason"));
        }
    }
    private void notifyUser(String symbol, String text) {
        if (closed) return; PluginLog.action(symbol, text); if (!settings.isEnabled(IndicatorConfig.TRADING_NOTIFICATION_SOUND)) return;
        Api api = apis.get(symbol); if (api == null) api = apis.values().stream().findFirst().orElse(null);
        if (api != null) try { api.sendUserMessage(new Layer1ApiSoundAlertMessage(sound, symbol + " " + text, 1, Duration.ZERO, null, RongPlugin.class, "native:" + symbol + ":" + System.nanoTime())); }
        catch (RuntimeException error) { PluginLog.action(symbol, "Trading sound unavailable: " + error.getClass().getSimpleName()); }
    }
    @Override public void close() { closed = true; if (runtime != null) runtime.close(); apis.clear(); }
}
