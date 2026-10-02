package com.bookmap.plugin.rong;

import com.bookmap.plugin.rong.miniviteapp.runtime.LocalCredentials;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import velox.api.layer1.simplified.Api;
import static org.junit.jupiter.api.Assertions.*;

class RongPluginActivationTest {
    @TempDir Path directory;
    private static Field field(String name) throws Exception {
        Field field = RongPlugin.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    @Test void missingFileSilentlySkipsStartupCallbacksAndDoesNotDetachAnExistingInstance() throws Exception {
        String previous = System.getProperty("bmtrader.secrets");
        Field count = field("instanceCount"); int previousCount = count.getInt(null);
        @SuppressWarnings("unchecked") Map<String, Api> apis = (Map<String, Api>) field("nativeApis").get(null);
        Api api = (Api) Proxy.newProxyInstance(Api.class.getClassLoader(), new Class<?>[]{Api.class},
            (proxy, method, args) -> { throw new AssertionError("Inactive plugin called Bookmap: " + method.getName()); });
        try {
            System.setProperty("bmtrader.secrets", directory.resolve("missing.json").toString());
            count.setInt(null, previousCount + 1); apis.put("TEST_ACTIVE", api);
            Object server = field("sharedServer").get(null), runtime = field("nativeTrading").get(null);
            RongPlugin plugin = new RongPlugin();
            assertEquals(0, plugin.getCustomSettingsPanels().length);
            plugin.initialize("TEST", null, api, null);
            plugin.onDepth(true, 100, 10); plugin.onTrade(100, 10, null);
            plugin.onTimestamp(123); plugin.onBbo(100, 10, 101, 10);
            plugin.onSnapshotEnd(); plugin.onRealtimeStart();
            plugin.onIndicatorConfigChanged(IndicatorConfig.VWAP, true);
            RongPlugin.restartNativeTrading(); plugin.stop(); plugin.stop();
            assertEquals(previousCount + 1, count.getInt(null));
            assertSame(api, apis.get("TEST_ACTIVE")); assertFalse(apis.containsKey("TEST"));
            assertSame(server, field("sharedServer").get(null)); assertSame(runtime, field("nativeTrading").get(null));
            assertNull(field("orderBook").get(plugin)); assertNull(field("tradeButtonWindow").get(plugin));
        } finally {
            apis.remove("TEST_ACTIVE"); count.setInt(null, previousCount);
            if (previous == null) System.clearProperty("bmtrader.secrets"); else System.setProperty("bmtrader.secrets", previous);
        }
    }
    @Test void activationRequiresAFileButDoesNotValidateItsContents() throws Exception {
        String previous = System.getProperty("bmtrader.secrets");
        try {
            System.setProperty("bmtrader.secrets", directory.toString()); assertFalse(LocalCredentials.fileExists());
            Path file = directory.resolve("secrets.json"); Files.writeString(file, "");
            System.setProperty("bmtrader.secrets", file.toString()); assertTrue(LocalCredentials.fileExists());
        } finally { if (previous == null) System.clearProperty("bmtrader.secrets"); else System.setProperty("bmtrader.secrets", previous); }
    }
}
