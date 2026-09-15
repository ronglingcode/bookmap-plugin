package release;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1SimpleAttachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.annotations.UnrestrictedData;
import velox.api.layer1.simplified.BboListener;
import velox.api.layer1.simplified.CustomModuleAdapter;
import velox.api.layer1.simplified.CustomSettingsPanelProvider;
import velox.api.layer1.simplified.DepthDataListener;
import velox.api.layer1.simplified.HistoricalModeListener;
import velox.api.layer1.simplified.SnapshotEndListener;
import velox.api.layer1.simplified.TimeListener;
import velox.api.layer1.simplified.TradeDataListener;
import velox.gui.StrategyPanel;

class ReleaseJarTest {
    private static final String ENTRY = "com.bookmap.plugin.rong.RongPlugin";
    private static final Path JAR = Path.of(System.getProperty("release.jar"));
    private static final Map<String, String> CLASSES = new HashMap<>();
    private static final Map<String, String> MEMBERS = new HashMap<>();

    @BeforeAll
    static void readPrivateMapping() throws Exception {
        String owner = null;
        for (String line : Files.readAllLines(Path.of(System.getProperty("release.mapping")))) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.trim().split(" -> ", 2);
            if (!Character.isWhitespace(line.charAt(0))) {
                owner = parts[0];
                CLASSES.put(owner, parts[1].substring(0, parts[1].length() - 1));
            } else {
                String signature = parts[0].replaceFirst("^\\d+:\\d+:", "")
                        .replaceFirst(":\\d+(:\\d+)?$", "");
                MEMBERS.put(owner + "#" + signature, parts[1]);
            }
        }
    }

    @Test
    void releaseContainsNoReadmeSourcesMappingsOrImplementationDebugMetadata() throws Exception {
        try (JarFile jar = new JarFile(JAR.toFile())) {
            assertEquals("bmtrader", jar.getManifest().getMainAttributes().getValue("Implementation-Title"));
            assertEquals(System.getProperty("release.version"),
                    jar.getManifest().getMainAttributes().getValue("Implementation-Version"));
            for (var entry : jar.stream().collect(Collectors.toList())) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                String baseName = name.substring(name.lastIndexOf('/') + 1);
                assertFalse(baseName.startsWith("readme"), entry.getName());
                assertFalse(name.endsWith(".md") || name.endsWith(".java"), entry.getName());
                assertFalse(baseName.equals("mapping.txt") || name.startsWith("meta-inf/maven/"), entry.getName());
                if (isImplementation(entry.getName())) {
                    String bytes = new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.ISO_8859_1);
                    for (String metadata : List.of("SourceFile", "SourceDebugExtension", "LineNumberTable",
                            "LocalVariableTable", "LocalVariableTypeTable", "MethodParameters")) {
                        assertFalse(bytes.contains(metadata), entry.getName() + ": " + metadata);
                    }
                } else if (!entry.isDirectory() && !name.endsWith(".class")) {
                    String text = new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                    assertFalse(text.toLowerCase(Locale.ROOT).contains("readme"), entry.getName());
                }
            }
        }
        long implementations = CLASSES.keySet().stream().filter(n -> n.startsWith("com.bookmap.plugin.rong.")).count();
        long renamed = CLASSES.entrySet().stream().filter(e -> e.getKey().startsWith("com.bookmap.plugin.rong.")
                && !e.getKey().equals(e.getValue())).count();
        assertTrue(implementations > 100);
        assertTrue(renamed >= implementations * 0.95, "Implementation class names should be obfuscated");
        assertTrue(CLASSES.entrySet().stream().filter(e -> e.getKey().startsWith("com.bookmap.plugin.rong.")
                && e.getKey().equals(e.getValue())).allMatch(e -> e.getKey().equals(ENTRY)
                || e.getKey().startsWith(ENTRY + "$")), "Only Bookmap's entry class family should retain its name");
    }

    @Test
    void everyImplementationClassAndSignatureLinksFromTheReleaseJar() throws Exception {
        try (JarFile jar = new JarFile(JAR.toFile())) {
            for (var entry : jar.stream().filter(e -> isImplementation(e.getName())).collect(Collectors.toList())) {
                String name = entry.getName().replace('/', '.').replaceFirst("\\.class$", "");
                Class<?> type = Class.forName(name, false, getClass().getClassLoader());
                assertEquals(JAR.toRealPath(), Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
                type.getDeclaredConstructors();
                type.getDeclaredMethods();
                type.getDeclaredFields();
            }
        }
    }

    @Test
    void bookmapCanDiscoverConstructAndRequestSettingsFromTheAddon() throws Exception {
        Class<?> type = Class.forName(ENTRY);
        assertTrue(type.isAnnotationPresent(Layer1SimpleAttachable.class));
        assertTrue(type.isAnnotationPresent(UnrestrictedData.class));
        assertEquals("bmtrader", type.getAnnotation(Layer1StrategyName.class).value());
        assertEquals(Layer1ApiVersionValue.VERSION2, type.getAnnotation(Layer1ApiVersion.class).value());
        Object plugin = type.getConstructor().newInstance();
        for (Class<?> contract : List.of(CustomModuleAdapter.class, DepthDataListener.class, TradeDataListener.class,
                TimeListener.class, SnapshotEndListener.class, BboListener.class, HistoricalModeListener.class,
                CustomSettingsPanelProvider.class)) {
            assertTrue(contract.isInstance(plugin), contract.getName());
            for (Method callback : contract.getMethods()) {
                type.getMethod(callback.getName(), callback.getParameterTypes());
            }
        }
        AtomicReference<StrategyPanel[]> panels = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> panels.set(((CustomSettingsPanelProvider) plugin).getCustomSettingsPanels()));
        assertEquals(1, panels.get().length);
        assertTrue(panels.get()[0].getComponentCount() > 0);
    }

    @Test
    void obfuscatedWebsocketParserDeliversValidPositionsAndRejectsInvalidOnes() throws Exception {
        String serverName = "com.bookmap.plugin.rong.SignalWebSocketServer";
        String listenerName = serverName + "$NewPositionListener";
        Class<?> serverType = mappedClass(serverName);
        Class<?> listenerType = mappedClass(listenerName);
        Object server = serverType.getConstructor(int.class, double.class).newInstance(0, 90d);
        AtomicReference<Object> received = new AtomicReference<>();
        Object listener = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {listenerType},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        switch (method.getName()) {
                            case "hashCode": return System.identityHashCode(proxy);
                            case "equals": return proxy == args[0];
                            case "toString": return "Release test listener";
                            default: throw new AssertionError(method);
                        }
                    }
                    received.set(args[0]);
                    return null;
                });
        mappedMethod(serverName, "void registerNewPositionListener(java.lang.String," + listenerName + ")",
                String.class, listenerType).invoke(server, "AAPL", listener);
        Class<?> websocket = Class.forName("com.bookmap.plugin.shaded.websocket.WebSocket");
        Method onMessage = serverType.getMethod("onMessage", websocket, String.class);
        String valid = "{\"type\":\"new_position\",\"priceUnit\":\"real\",\"symbol\":\"AAPL\","
                + "\"isLong\":true,\"netQuantity\":100,\"averagePrice\":110.25,"
                + "\"eventId\":\"release-check\",\"timestamp\":1785243960000}";
        onMessage.invoke(server, null, valid);
        Object position = received.get();
        assertNotNull(position);
        String positionName = "com.bookmap.plugin.rong.NewPositionDefinition";
        assertEquals("AAPL", mappedMethod(positionName, "java.lang.String getSymbol()").invoke(position));
        assertEquals(110.25, (double) mappedMethod(positionName, "double getAveragePrice()").invoke(position), 0.00001);
        assertEquals(100d, (double) mappedMethod(positionName, "double getNetQuantity()").invoke(position), 0.00001);
        received.set(null);
        onMessage.invoke(server, null, valid.replace("\"netQuantity\":100", "\"netQuantity\":0"));
        assertNull(received.get());
    }

    @Test
    void enumReflectionStillWorksForPatternState() throws Exception {
        Class<?> type = mappedClass("com.bookmap.plugin.rong.patterns.PatternType");
        Object[] constants = type.getEnumConstants();
        assertNotNull(constants);
        assertTrue(constants.length > 0);
        for (Object constant : constants) {
            assertSame(constant, type.getMethod("valueOf", String.class).invoke(null, ((Enum<?>) constant).name()));
        }
    }

    private static boolean isImplementation(String name) {
        return name.endsWith(".class") && (name.startsWith("bmtrader/internal/")
                || name.startsWith("com/bookmap/plugin/rong/"));
    }

    private static Class<?> mappedClass(String original) throws Exception {
        return Class.forName(CLASSES.get(original));
    }

    private static Method mappedMethod(String owner, String signature, Class<?>... parameters) throws Exception {
        String name = MEMBERS.get(owner + "#" + signature);
        assertNotNull(name, "Missing mapping for " + owner + "#" + signature);
        Method method = mappedClass(owner).getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }
}
