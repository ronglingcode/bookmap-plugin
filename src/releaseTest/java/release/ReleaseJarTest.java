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
        Path secrets = Files.createTempFile("bmtrader-release-settings-", ".json");
        String previous = System.getProperty("bmtrader.secrets");
        try {
            System.setProperty("bmtrader.secrets", secrets.toString());
            AtomicReference<StrategyPanel[]> panels = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> panels.set(((CustomSettingsPanelProvider) plugin).getCustomSettingsPanels()));
            assertEquals(1, panels.get().length);
            assertTrue(panels.get()[0].getComponentCount() > 0);
        } finally {
            if (previous == null) System.clearProperty("bmtrader.secrets"); else System.setProperty("bmtrader.secrets", previous);
            Files.deleteIfExists(secrets);
        }
    }

    @Test
    void distributedAddonRemainsSilentAndInactiveWithoutSecrets() throws Exception {
        Path directory = Files.createTempDirectory("bmtrader-release-inactive-");
        String previous = System.getProperty("bmtrader.secrets");
        try {
            System.setProperty("bmtrader.secrets", directory.resolve("missing.json").toString());
            Object plugin = Class.forName(ENTRY).getConstructor().newInstance();
            assertEquals(0, ((CustomSettingsPanelProvider) plugin).getCustomSettingsPanels().length);
            // Null API/instrument data prove no Bookmap calls or live startup are attempted.
            ((CustomModuleAdapter) plugin).initialize("TEST", null, null, null);
            ((DepthDataListener) plugin).onDepth(true, 100, 10);
            ((TradeDataListener) plugin).onTrade(100, 10, null);
            ((TimeListener) plugin).onTimestamp(123);
            ((BboListener) plugin).onBbo(100, 10, 101, 10);
            ((SnapshotEndListener) plugin).onSnapshotEnd();
            ((HistoricalModeListener) plugin).onRealtimeStart();
            ((CustomModuleAdapter) plugin).stop();
            ((CustomModuleAdapter) plugin).stop();
        } finally {
            if (previous == null) System.clearProperty("bmtrader.secrets"); else System.setProperty("bmtrader.secrets", previous);
            Files.deleteIfExists(directory);
        }
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

    @Test void advisoryConfigCompositionAndRasterFieldsSurviveObfuscation() throws Exception {
        String root = "com.bookmap.plugin.rong.", configName = root + "signal.SignalComposerConfig";
        Class<?> jsonObject = Class.forName("com.bookmap.plugin.shaded.gson.JsonObject");
        Class<?> parser = Class.forName("com.bookmap.plugin.shaded.gson.JsonParser");
        Object element = parser.getMethod("parseString", String.class).invoke(null,
                "{\"enabled\":true,\"normalConfirmationSize\":6000,\"symbols\":[\"test\"]}");
        Object json = element.getClass().getMethod("getAsJsonObject").invoke(element);
        Object config = mappedMethod(configName, configName + " parse(com.bookmap.plugin.shaded.gson.JsonObject)", jsonObject).invoke(null, json);
        assertEquals(true, mappedField(configName, "boolean enabled", config));
        assertEquals(6000L, mappedField(configName, "long normalConfirmationSize", config));
        assertEquals(1000L, mappedField(configName, "long observationFloorSize", config));
        assertEquals(true, mappedMethod(configName, "boolean eligible(java.lang.String)", String.class).invoke(config, "TEST"));
        assertEquals(false, mappedMethod(configName, "boolean eligible(java.lang.String)", String.class).invoke(config, "OTHER"));
        Object template = mappedMethod(configName, configName + " load(java.nio.file.Path)", Path.class)
                .invoke(null, Path.of(System.getProperty("signal.template")));
        assertEquals(true, mappedField(configName, "boolean valid", template));
        assertEquals(true, mappedField(configName, "boolean enabled", template));

        for (String name : List.of("patterns.PatternEventType", "patterns.PatternMeaning", "patterns.PatternSide", "patterns.PatternSizeCategory",
                "signal.ConfirmationStrength", "signal.SignalState", "signal.ResetReason")) {
            Class<?> type = mappedClass(root + name);
            for (Object value : type.getEnumConstants()) assertSame(value, enumValue(root + name, ((Enum<?>)value).name()));
        }
        String composerName = root + "signal.SignalComposer", eventName = root + "patterns.PatternEvent";
        Object small = releaseEvent("BIDS_CANCELLED", 1000, 5105, 50, "small");
        assertEquals("LEAST_SIGNIFICANT", ((Enum<?>)mappedField(eventName, root + "patterns.PatternSizeCategory sizeCategory", small)).name());
        Object composer = mappedClass(composerName).getConstructor(String.class, long.class, mappedClass(configName)).newInstance("TEST", 1L, config);
        Method observe = mappedMethod(composerName, root + "signal.CompositionUpdate onPatternEvent(" + eventName + ")", mappedClass(eventName));
        Object pending = observe.invoke(composer, releaseEvent("BIDS_CANCELLED", 3000, 5105, 100, "bid"));
        assertTrue(((List<?>)mappedField(root + "signal.CompositionUpdate", "java.util.List signals", pending)).isEmpty());
        Object update = observe.invoke(composer, releaseEvent("OFFER_REJECTION", 60000, 5120, 200, "offer"));
        List<?> signals = (List<?>)mappedField(root + "signal.CompositionUpdate", "java.util.List signals", update);
        assertEquals(1, signals.size()); Object signal = signals.get(0); String signalName = root + "signal.TradingSignal";
        assertEquals("SHORT", ((Enum<?>)mappedField(signalName, root + "patterns.Direction direction", signal)).name());
        String validationName = signalName + "$FirstValidation";
        Object first = mappedField(signalName, validationName + " firstValidation", signal);
        assertEquals(3000L, mappedField(validationName, "long appliedTriggerThreshold", first));
        assertEquals(200L, mappedField(validationName, "long eventTimeNs", first));
        assertEquals("EXCEPTIONAL", ((Enum<?>)mappedField(validationName, root + "signal.ConfirmationStrength confirmationStrength", first)).name());
        String explanation = (String)mappedField(signalName, "java.lang.String explanation", signal);
        assertTrue(explanation.contains("60K")); assertTrue(explanation.contains("AFTER"));
        String painterName = root + "signal.TradingSignalPainter";
        java.awt.image.BufferedImage image = (java.awt.image.BufferedImage)mappedMethod(painterName,
                "java.awt.image.BufferedImage renderBadge(" + signalName + ")", mappedClass(signalName)).invoke(null, signal);
        assertTrue(image.getWidth() > 100); assertTrue(image.getHeight() > 20);
        assertEquals(200L, mappedMethod(painterName, "long anchorTimeNs(" + signalName + ")", mappedClass(signalName)).invoke(null, signal));
        try (JarFile jar = new JarFile(JAR.toFile())) { assertNull(jar.getJarEntry("velox/api/layer1/common/helper/OpenGlHelper.class")); }
    }

    private static Object releaseEvent(String type, long size, int price, long time, String interaction) throws Exception {
        String root = "com.bookmap.plugin.rong.patterns.", event = root + "PatternEvent", builder = event + "$Builder";
        String evidence = event + "$Evidence", evidenceBuilder = event + "$EvidenceBuilder";
        Object details = mappedMethod(evidence, evidenceBuilder + " builder()").invoke(null);
        mappedMethod(evidenceBuilder, evidenceBuilder + " attribution(" + event + "$Attribution," + event + "$Coverage)",
                mappedClass(event + "$Attribution"), mappedClass(event + "$Coverage"))
                .invoke(details, enumValue(event + "$Attribution", "UNKNOWN"), enumValue(event + "$Coverage", "USABLE"));
        Object builtEvidence = mappedMethod(evidenceBuilder, evidence + " build()").invoke(details);
        Object value = mappedMethod(event, builder + " builder(java.lang.String,long," + root + "PatternEventType,java.lang.String)",
                String.class, long.class, mappedClass(root + "PatternEventType"), String.class)
                .invoke(null, "TEST", 1L, enumValue(root + "PatternEventType", type), interaction);
        mappedMethod(builder, builder + " size(long," + event + "$SizeBasis)", long.class, mappedClass(event + "$SizeBasis"))
                .invoke(value, size, enumValue(event + "$SizeBasis", "DISPLAYED_WALL"));
        mappedMethod(builder, builder + " price(int,double)", int.class, double.class).invoke(value, price, .01);
        mappedMethod(builder, builder + " times(long,long)", long.class, long.class).invoke(value, time, time);
        mappedMethod(builder, builder + " evidence(" + evidence + ")", mappedClass(evidence)).invoke(value, builtEvidence);
        return mappedMethod(builder, event + " build()").invoke(value);
    }
    private static Object enumValue(String owner, String name) throws Exception { return mappedClass(owner).getMethod("valueOf", String.class).invoke(null, name); }
    private static Object mappedField(String owner, String signature, Object instance) throws Exception {
        String name = MEMBERS.get(owner + "#" + signature); assertNotNull(name, "Missing field mapping: " + owner + "#" + signature);
        java.lang.reflect.Field field = mappedClass(owner).getDeclaredField(name); field.setAccessible(true); return field.get(instance);
    }

    @Test
    void nativeExecutionWireParsingAndClosingPlansSurviveObfuscation() throws Exception {
        Class<?> parser = Class.forName("com.bookmap.plugin.shaded.gson.JsonParser");
        Class<?> jsonObject = Class.forName("com.bookmap.plugin.shaded.gson.JsonObject");
        Object array = parser.getMethod("parseString", String.class).invoke(null,
                Files.readString(Path.of(System.getProperty("execution.fixtures"))));
        Object fixtures = array.getClass().getMethod("getAsJsonArray").invoke(array);
        Object fixture = fixtures.getClass().getMethod("get", int.class).invoke(fixtures, 3);
        Object json = fixture.getClass().getMethod("getAsJsonObject").invoke(fixture);
        Object stateJson = jsonObject.getMethod("getAsJsonObject", String.class).invoke(json, "state");
        String stateName = "com.bookmap.plugin.rong.miniviteapp.models.Models$Snapshot";
        Object state = mappedClass(stateName).getConstructor(jsonObject).newInstance(stateJson);
        String handlerName = "com.bookmap.plugin.rong.miniviteapp.core.controllers.KeyboardHandler";
        String planName = "com.bookmap.plugin.rong.miniviteapp.models.Models$Plan";
        Object plan = mappedMethod(handlerName, planName + " handleKeyPressed(" + stateName
                + ",java.lang.String,boolean,double)", mappedClass(stateName), String.class, boolean.class, double.class)
                .invoke(null, state, "KeyM", false, Double.NaN);
        Object requests = mappedMethod(planName, "com.bookmap.plugin.shaded.gson.JsonArray toJson()").invoke(plan);
        Object expected = jsonObject.getMethod("getAsJsonArray", String.class).invoke(json, "requests");
        assertEquals(expected, requests);
    }

    @Test
    void nativeBracketedEntryPlansSurviveObfuscation() throws Exception {
        Class<?> parser = Class.forName("com.bookmap.plugin.shaded.gson.JsonParser");
        Class<?> jsonObject = Class.forName("com.bookmap.plugin.shaded.gson.JsonObject");
        Object array = parser.getMethod("parseString", String.class).invoke(null,
                Files.readString(Path.of(System.getProperty("entry.fixtures"))));
        Object fixtures = array.getClass().getMethod("getAsJsonArray").invoke(array);
        Object fixture = fixtures.getClass().getMethod("get", int.class).invoke(fixtures, 0);
        Object json = fixture.getClass().getMethod("getAsJsonObject").invoke(fixture);
        Object stateJson = jsonObject.getMethod("getAsJsonObject", String.class).invoke(json, "state");
        Object actionJson = jsonObject.getMethod("getAsJsonObject", String.class).invoke(json, "action");
        String stateName = "com.bookmap.plugin.rong.miniviteapp.models.Models$Snapshot";
        Object state = mappedClass(stateName).getConstructor(jsonObject).newInstance(stateJson);
        String handlerName = "com.bookmap.plugin.rong.miniviteapp.core.controllers.EntryHandler";
        String planName = "com.bookmap.plugin.rong.miniviteapp.models.Models$Plan";
        Object plan = mappedMethod(handlerName, planName + " handleEntry(" + stateName
                + ",com.bookmap.plugin.shaded.gson.JsonObject,java.lang.String)", mappedClass(stateName), jsonObject, String.class)
                .invoke(null, state, actionJson, "");
        Object requests = mappedMethod(planName, "com.bookmap.plugin.shaded.gson.JsonArray toJson()").invoke(plan);
        assertEquals(jsonObject.getMethod("getAsJsonArray", String.class).invoke(json, "requests"), requests);
    }

    @Test
    void extendedReloadAndSwapPlansSurviveObfuscation() throws Exception {
        Class<?> parser = Class.forName("com.bookmap.plugin.shaded.gson.JsonParser");
        Class<?> jsonObject = Class.forName("com.bookmap.plugin.shaded.gson.JsonObject");
        Object array = parser.getMethod("parseString", String.class).invoke(null,
                Files.readString(Path.of(System.getProperty("extended.fixtures"))));
        Object fixtures = array.getClass().getMethod("getAsJsonArray").invoke(array);
        String stateName = "com.bookmap.plugin.rong.miniviteapp.models.Models$Snapshot";
        String handlerName = "com.bookmap.plugin.rong.miniviteapp.core.controllers.ExtendedHandler";
        String planName = "com.bookmap.plugin.rong.miniviteapp.models.Models$Plan";
        for (int index : new int[]{0, 15}) {
            Object element = fixtures.getClass().getMethod("get", int.class).invoke(fixtures, index);
            Object fixture = element.getClass().getMethod("getAsJsonObject").invoke(element);
            Object stateJson = jsonObject.getMethod("getAsJsonObject", String.class).invoke(fixture, "state");
            Object state = mappedClass(stateName).getConstructor(jsonObject).newInstance(stateJson);
            Object plan = index == 0
                    ? mappedMethod(handlerName, planName + " reload(" + stateName + ",boolean,double)", mappedClass(stateName), boolean.class, double.class)
                        .invoke(null, state, true, 10.0)
                    : mappedMethod(handlerName, planName + " swap(" + stateName + ")", mappedClass(stateName)).invoke(null, state);
            Object requests = mappedMethod(planName, "com.bookmap.plugin.shaded.gson.JsonArray toJson()").invoke(plan);
            Object expected = jsonObject.getMethod("getAsJsonArray", String.class).invoke(fixture, "requests");
            assertEquals(expected, requests);
        }
    }

    @Test void nativeFactoryLoadsPrivateJsonAndClosesWithoutBookmapOrProxy() throws Exception {
        Path temporary = Files.createTempFile("bmtrader-release-credentials-", ".json"); String previous = System.getProperty("bmtrader.secrets");
        Files.writeString(temporary, "{\"massive\":{\"apiKey\":\"fake-massive\"},\"firebaseConfig\":{\"projectId\":\"fake-project\",\"apiKey\":\"fake-api\"},\"schwab\":{}}");
        try {
            System.setProperty("bmtrader.secrets", temporary.toString()); Class<?> events = mappedClass("com.bookmap.plugin.rong.miniviteapp.runtime.TradingRuntime$Events");
            Object sink = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{events}, (proxy, method, args) -> null);
            try (AutoCloseable runtime = (AutoCloseable) mappedClass("com.bookmap.plugin.rong.miniviteapp.runtime.NativeRuntime").getConstructor(events).newInstance(sink)) { assertNotNull(runtime); }
        } finally { if (previous == null) System.clearProperty("bmtrader.secrets"); else System.setProperty("bmtrader.secrets", previous); Files.deleteIfExists(temporary); }
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
