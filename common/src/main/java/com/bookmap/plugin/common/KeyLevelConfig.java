package com.bookmap.plugin.common;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Manages predefined key price levels from two sources:
 *
 * <ol>
 *   <li><b>File levels</b> — loaded from {@code key-levels.json} at construction time.
 *       These are read-only from the plugin's perspective; the user edits the file manually.
 *       File levels are NOT removable via the settings panel.</li>
 *   <li><b>Session levels</b> — added at runtime via the settings panel.
 *       These exist only in memory and are lost when the plugin shuts down.</li>
 * </ol>
 *
 * <h3>JSON file format</h3>
 * <p>The config file should be placed at {@code ~/bookmap-plugin/key-levels.json}
 * (user's home directory). Example:</p>
 * <pre>{@code
 * {
 *   "levels": [
 *     { "instrument": "NVDA", "price": 180.00, "label": "major support" },
 *     { "instrument": "NVDA", "price": 200.00, "label": "round number" },
 *     { "instrument": "ES", "price": 5400.00 }
 *   ]
 * }
 * }</pre>
 *
 * <p>The {@code instrument} field must match the exact alias Bookmap uses (e.g., "NVDA", "ESM5").
 * The {@code label} field is optional — if omitted, the default "Key Level" label is used.</p>
 *
 * <h3>Thread safety</h3>
 * <p>Uses {@link CopyOnWriteArrayList} for both collections, matching existing patterns
 * in the codebase (e.g., PriceLineStore). Safe for concurrent reads from Bookmap's data
 * thread and UI thread writes from the settings panel.</p>
 */
public class KeyLevelConfig {

    /** Default config file path: ~/bookmap-plugin/key-levels.json */
    private static final String CONFIG_DIR = "bookmap-plugin";
    private static final String CONFIG_FILE = "key-levels.json";

    /** Levels loaded from the JSON config file (read-only after construction). */
    private final List<KeyLevelDefinition> fileLevels;

    /** Levels added at runtime via the settings panel (session-only, not persisted). */
    private final List<KeyLevelDefinition> sessionLevels = new CopyOnWriteArrayList<>();

    /** Listeners notified when session levels are added or removed. */
    public interface ChangeListener {
        void onKeyLevelsChanged();
    }

    private final List<ChangeListener> listeners = new CopyOnWriteArrayList<>();

    public KeyLevelConfig() {
        this.fileLevels = loadFromFile();
        System.out.println("[KeyLevelConfig] Loaded " + fileLevels.size() + " key levels from file");
        for (KeyLevelDefinition def : fileLevels) {
            System.out.println("[KeyLevelConfig]   " + def);
        }
    }

    /**
     * Returns all key levels (file + session) for a specific instrument.
     *
     * @param instrumentAlias Bookmap instrument alias (e.g., "NVDA")
     * @return unmodifiable list of matching definitions
     */
    public List<KeyLevelDefinition> getLevelsForInstrument(String instrumentAlias) {
        List<KeyLevelDefinition> result = new ArrayList<>();
        for (KeyLevelDefinition def : fileLevels) {
            if (def.getInstrument().equalsIgnoreCase(instrumentAlias)) {
                result.add(def);
            }
        }
        for (KeyLevelDefinition def : sessionLevels) {
            if (def.getInstrument().equalsIgnoreCase(instrumentAlias)) {
                result.add(def);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** Returns all key levels from both sources (for display in settings panel). */
    public List<KeyLevelDefinition> getAllLevels() {
        List<KeyLevelDefinition> result = new ArrayList<>(fileLevels);
        result.addAll(sessionLevels);
        return Collections.unmodifiableList(result);
    }

    /** Returns only file-loaded levels (for UI to mark them as non-removable). */
    public List<KeyLevelDefinition> getFileLevels() {
        return Collections.unmodifiableList(new ArrayList<>(fileLevels));
    }

    /** Returns only session levels (for UI to allow removal). */
    public List<KeyLevelDefinition> getSessionLevels() {
        return Collections.unmodifiableList(new ArrayList<>(sessionLevels));
    }

    /** Add a key level at runtime (session-only, not persisted to file). */
    public void addSessionLevel(KeyLevelDefinition def) {
        sessionLevels.add(def);
        System.out.println("[KeyLevelConfig] Session level added: " + def);
        notifyListeners();
    }

    /** Remove a session-only key level. File levels cannot be removed. */
    public void removeSessionLevel(KeyLevelDefinition def) {
        if (sessionLevels.remove(def)) {
            System.out.println("[KeyLevelConfig] Session level removed: " + def);
            notifyListeners();
        }
    }

    /** Check if a definition came from the file (and therefore cannot be removed via UI). */
    public boolean isFromFile(KeyLevelDefinition def) {
        return fileLevels.contains(def);
    }

    public void addChangeListener(ChangeListener listener) {
        listeners.add(listener);
    }

    public void removeChangeListener(ChangeListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (ChangeListener l : listeners) {
            l.onKeyLevelsChanged();
        }
    }

    // ---- JSON loading (hand-rolled, no external dependency) ----

    /**
     * Loads key levels from ~/bookmap-plugin/key-levels.json.
     *
     * <p>Uses a simple hand-rolled JSON parser to avoid adding a Gson/Jackson dependency.
     * The format is intentionally simple: a JSON object with a single "levels" array,
     * where each element has "instrument" (required), "price" (required), and "label" (optional).</p>
     *
     * @return list of parsed definitions, or empty list if file doesn't exist or has errors
     */
    private List<KeyLevelDefinition> loadFromFile() {
        File configFile = new File(System.getProperty("user.home"), CONFIG_DIR + File.separator + CONFIG_FILE);
        if (!configFile.exists()) {
            System.out.println("[KeyLevelConfig] Config file not found: " + configFile.getAbsolutePath());
            System.out.println("[KeyLevelConfig] To add predefined key levels, create this file. Example:");
            System.out.println("[KeyLevelConfig]   { \"levels\": [ { \"instrument\": \"NVDA\", \"price\": 180.00, \"label\": \"support\" } ] }");
            return Collections.emptyList();
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return parseJson(sb.toString());
        } catch (Exception e) {
            System.err.println("[KeyLevelConfig] Failed to load " + configFile.getAbsolutePath() + ": " + e.getMessage());
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    /**
     * Parses the JSON content into a list of KeyLevelDefinition.
     * Simple parser that handles the expected format without a full JSON library.
     */
    private List<KeyLevelDefinition> parseJson(String json) {
        List<KeyLevelDefinition> result = new ArrayList<>();

        // Find the "levels" array content
        int levelsIdx = json.indexOf("\"levels\"");
        if (levelsIdx < 0) return result;

        int arrayStart = json.indexOf('[', levelsIdx);
        if (arrayStart < 0) return result;

        int arrayEnd = json.lastIndexOf(']');
        if (arrayEnd <= arrayStart) return result;

        String arrayContent = json.substring(arrayStart + 1, arrayEnd);

        // Split into individual objects by finding matching braces
        int depth = 0;
        int objStart = -1;
        for (int i = 0; i < arrayContent.length(); i++) {
            char c = arrayContent.charAt(i);
            if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    String objStr = arrayContent.substring(objStart, i + 1);
                    KeyLevelDefinition def = parseObject(objStr);
                    if (def != null) {
                        result.add(def);
                    }
                    objStart = -1;
                }
            }
        }

        return result;
    }

    /**
     * Parses a single JSON object like: { "instrument": "NVDA", "price": 180.00, "label": "support" }
     */
    private KeyLevelDefinition parseObject(String obj) {
        String instrument = extractStringValue(obj, "instrument");
        Double price = extractNumberValue(obj, "price");
        String label = extractStringValue(obj, "label");

        if (instrument == null || price == null) {
            System.err.println("[KeyLevelConfig] Skipping invalid entry (missing instrument or price): " + obj.trim());
            return null;
        }

        return new KeyLevelDefinition(instrument, price, label);
    }

    /** Extracts a string value for a key from a JSON object string. */
    private String extractStringValue(String obj, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIdx = obj.indexOf(searchKey);
        if (keyIdx < 0) return null;

        int colonIdx = obj.indexOf(':', keyIdx + searchKey.length());
        if (colonIdx < 0) return null;

        // Find opening quote after colon
        int quoteStart = obj.indexOf('"', colonIdx + 1);
        if (quoteStart < 0) return null;

        int quoteEnd = obj.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return null;

        return obj.substring(quoteStart + 1, quoteEnd);
    }

    /** Extracts a numeric value for a key from a JSON object string. */
    private Double extractNumberValue(String obj, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIdx = obj.indexOf(searchKey);
        if (keyIdx < 0) return null;

        int colonIdx = obj.indexOf(':', keyIdx + searchKey.length());
        if (colonIdx < 0) return null;

        // Find the number after the colon (skip whitespace)
        int start = colonIdx + 1;
        while (start < obj.length() && Character.isWhitespace(obj.charAt(start))) {
            start++;
        }

        // Read digits, dots, minus signs
        int end = start;
        while (end < obj.length() && (Character.isDigit(obj.charAt(end)) || obj.charAt(end) == '.' || obj.charAt(end) == '-')) {
            end++;
        }

        if (end <= start) return null;

        try {
            return Double.parseDouble(obj.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
