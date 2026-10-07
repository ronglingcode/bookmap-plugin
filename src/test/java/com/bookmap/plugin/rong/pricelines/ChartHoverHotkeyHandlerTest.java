package com.bookmap.plugin.rong.pricelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Canvas;
import java.awt.event.KeyEvent;
import java.time.Instant;
import java.util.Set;
import java.util.List;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import javax.swing.JLabel;
import javax.swing.JPanel;

import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.SignalWebSocketServer;
import com.bookmap.plugin.rong.tradebuttons.HotkeyRiskSelection;
import com.bookmap.plugin.rong.tradebuttons.TradebookButtonGroup;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.EntryHandler;
import com.bookmap.plugin.rong.miniviteapp.models.Models.Snapshot;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class ChartHoverHotkeyHandlerTest {

    @Test
    void bothEntryDirectionsUseSelectedRiskInNativePlansInsteadOfFirstButtonSize() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
        JsonObject fixture = JsonParser.parseReader(new InputStreamReader(
                getClass().getResourceAsStream("/direct-entry-fixtures.json"), StandardCharsets.UTF_8))
                .getAsJsonArray().get(0).getAsJsonObject();
        for (boolean isLong : new boolean[]{true, false}) {
            String id = isLong ? "RangeBoundBidReversal" : "RangeBoundOfferReversal";
            TradebookButtonGroup tradebook = new TradebookButtonGroup(
                    id, "reversal", isLong, id, "reversal", List.of("1 R", "0.5 R", "0.1 R"));
            for (String method : HotkeyRiskSelection.ENTRY_METHODS) {
                server.getHotkeyRiskSelection().setEntryMethod(method);
                JsonObject action = fixture.getAsJsonObject("action").deepCopy();
                action.addProperty("source", "bookmap_chart_hotkey");
                action.addProperty("price", 10);
                ChartHoverHotkeyHandler.addWallReversalEntryFields(action, tradebook, isLong, server);
                assertEquals(method, action.get("entry_method").getAsString());
                assertEquals(false, action.get("use_market_order").getAsBoolean());
                assertEquals("breakout", action.get("order_type").getAsString());
                var plan = EntryHandler.handleEntry(new Snapshot(fixture.getAsJsonObject("state")),
                        action, isLong ? "KeyB" : "KeyS");
                double risk = Double.parseDouble(method.split(" ")[0]);
                assertEquals(risk, plan.entry.get("multiplier").getAsDouble());
                assertEquals(Math.floor(1960 * risk), plan.entry.getAsJsonObject("submitEntryResult")
                        .get("totalQuantity").getAsDouble());
                assertEquals("1 R", tradebook.getEntryMethods().get(0));
            }
        }
    }

    @Test
    void numpadDigitsMapToViteNumpadHotkeys() {
        for (int digit = 1; digit <= 9; digit++) {
            String normalizedKey = "numpad" + digit;

            assertEquals(
                    normalizedKey,
                    ChartHoverHotkeyHandler.normalizeKey(keyPressed(KeyEvent.VK_NUMPAD0 + digit)));
            assertTrue(ChartHoverHotkeyHandler.isChartHotkey(normalizedKey));
            assertEquals("Numpad" + digit, ChartHoverHotkeyHandler.toViteKeyCode(normalizedKey));
        }
    }

    @Test
    void topRowDigitsRemainViteDigitHotkeys() {
        assertEquals("1", ChartHoverHotkeyHandler.normalizeKey(keyPressed(KeyEvent.VK_1)));
        assertEquals("Digit1", ChartHoverHotkeyHandler.toViteKeyCode("1"));
        assertEquals("Digit9", ChartHoverHotkeyHandler.toViteKeyCode("9"));
    }

    @Test
    void buyAndSellKeysMapToWallReversalHotkeys() {
        assertTrue(ChartHoverHotkeyHandler.isChartHotkey("b"));
        assertTrue(ChartHoverHotkeyHandler.isWallReversalHotkey("b"));
        assertEquals("KeyB", ChartHoverHotkeyHandler.toViteKeyCode("b"));

        assertTrue(ChartHoverHotkeyHandler.isChartHotkey("s"));
        assertTrue(ChartHoverHotkeyHandler.isWallReversalHotkey("s"));
        assertEquals("KeyS", ChartHoverHotkeyHandler.toViteKeyCode("s"));
    }

    @Test
    void buttonEquivalentHotkeysDoNotRequireHoveredPrices() {
        assertTrue(ChartHoverHotkeyHandler.isChartHotkey("c"));
        assertTrue(ChartHoverHotkeyHandler.isPriceIndependentHotkey("c"));
        assertEquals("KeyC", ChartHoverHotkeyHandler.toViteKeyCode("c"));

        assertTrue(ChartHoverHotkeyHandler.isChartHotkey("f"));
        assertTrue(ChartHoverHotkeyHandler.isPriceIndependentHotkey("f"));
        assertEquals("KeyF", ChartHoverHotkeyHandler.toViteKeyCode("f"));

        assertTrue(ChartHoverHotkeyHandler.isChartHotkey("w"));
        assertTrue(ChartHoverHotkeyHandler.isPriceIndependentHotkey("w"));
        assertEquals("KeyW", ChartHoverHotkeyHandler.toViteKeyCode("w"));

        for (String key : new String[]{"p", "m", "space", "numpad1", "numpad0"}) {
            assertTrue(ChartHoverHotkeyHandler.isChartHotkey(key));
            assertTrue(ChartHoverHotkeyHandler.isPriceIndependentHotkey(key));
        }
        assertTrue(ChartHoverHotkeyHandler.isChartHotkey("h"));
        assertEquals("Space", ChartHoverHotkeyHandler.toViteKeyCode("space"));
        assertFalse(ChartHoverHotkeyHandler.isPriceIndependentHotkey("b"));
        assertFalse(ChartHoverHotkeyHandler.isChartHotkey("q"));
        assertFalse(ChartHoverHotkeyHandler.isPriceIndependentHotkey("q"));
        assertFalse(ChartHoverHotkeyHandler.isPriceIndependentHotkey("1"));
    }

    @Test
    void removedTrailingKeysAreNotConsumedAsChartHotkeys() {
        for (String key : new String[]{"j", "k", "l"}) {
            assertFalse(ChartHoverHotkeyHandler.isChartHotkey(key));
            assertFalse(ChartHoverHotkeyHandler.isPriceIndependentHotkey(key));
        }
    }

    @Test
    void hoverHotkeyActionLogContainsEveryRequiredField() {
        assertEquals(
                "hover_key AAPL Numpad3 @ 12.35 + shift",
                ChartHoverHotkeyHandler.formatHoverHotkeyActionLog(
                        "AAPL", "Numpad3", 12.3456, true));
        assertEquals(
                "hover_key NVDA Digit1 @ 171.25",
                ChartHoverHotkeyHandler.formatHoverHotkeyActionLog(
                        "NVDA", "Digit1", 171.25, false));
    }

    @Test
    void entryHotkeysStopSendingAtTenAmNewYorkTime() {
        assertFalse(ChartHoverHotkeyHandler.isEntryHotkeyDisabledAt(
                "b", Instant.parse("2026-07-24T13:59:59Z")));
        assertTrue(ChartHoverHotkeyHandler.isEntryHotkeyDisabledAt(
                "b", Instant.parse("2026-07-24T14:00:00Z")));

        assertFalse(ChartHoverHotkeyHandler.isEntryHotkeyDisabledAt(
                "s", Instant.parse("2026-01-15T14:59:59Z")));
        assertTrue(ChartHoverHotkeyHandler.isEntryHotkeyDisabledAt(
                "s", Instant.parse("2026-01-15T15:00:00Z")));
    }

    @Test
    void exitAndManagementHotkeysRemainEnabledAfterEntryCutoff() {
        Instant afterMarketClose = Instant.parse("2026-07-24T21:00:00Z");

        for (String key : new String[] {
                "a", "c", "f", "g", "t", "w", "1", "0", "numpad1", "numpad0"
        }) {
            assertFalse(
                    ChartHoverHotkeyHandler.isEntryHotkeyDisabledAt(key, afterMarketClose),
                    key);
        }
    }

    @Test
    void painterCallbackUsesActualChartAliasInsteadOfRegisteredPainterName() {
        assertEquals(
                "MSFT",
                ChartHoverHotkeyHandler.resolveInstrumentFromPainterContext(
                        "RongPlugin#hoverHotkey_AAPL", "MSFT:NASDAQ:STOCKS@BMD"));
    }

    @Test
    void painterCallbackFallsBackToRegisteredNameWhenChartAliasIsUnavailable() {
        assertEquals(
                "AAPL",
                ChartHoverHotkeyHandler.resolveInstrumentFromPainterContext(
                        "RongPlugin#hoverHotkey_AAPL", null));
    }

    @Test
    void hoveredChartBranchResolvesItsOwnSymbolInSharedWindow() {
        JPanel sharedWindowContent = new JPanel();
        JPanel aaplChart = chartPanel("AAPL");
        JPanel msftChart = chartPanel("MSFT");
        sharedWindowContent.add(aaplChart);
        sharedWindowContent.add(msftChart);

        assertEquals(
                "AAPL",
                ChartHoverHotkeyHandler.identifyInstrumentFromComponent(
                        chartSurface(aaplChart), Set.of("AAPL", "MSFT")));
        assertEquals(
                "MSFT",
                ChartHoverHotkeyHandler.identifyInstrumentFromComponent(
                        chartSurface(msftChart), Set.of("AAPL", "MSFT")));
    }

    @Test
    void sharedContainerWithMultipleChartsDoesNotChooseArbitrarySymbol() {
        JPanel sharedWindowContent = new JPanel();
        sharedWindowContent.add(chartPanel("AAPL"));
        sharedWindowContent.add(chartPanel("MSFT"));

        assertNull(ChartHoverHotkeyHandler.identifyInstrumentFromComponent(
                sharedWindowContent, Set.of("AAPL", "MSFT")));
    }

    @Test
    void singleActivePainterResolvesWhenSeveralSymbolsAreRegistered() {
        assertEquals(
                "MSFT",
                ChartHoverHotkeyHandler.resolveUnidentifiedHoverInstrument(
                        Set.of("AAPL", "MSFT"), Set.of("MSFT")));
    }

    @Test
    void multipleActivePaintersRemainAmbiguous() {
        assertNull(ChartHoverHotkeyHandler.resolveUnidentifiedHoverInstrument(
                Set.of("AAPL", "MSFT"), Set.of("AAPL", "MSFT")));
    }

    @Test
    void bookmapWindowTitleIdentifiesHighlightedTab() {
        assertEquals(
                "MRNA",
                ChartHoverHotkeyHandler.identifyInstrumentFromTitle(
                        "MRNA    Bookmap", Set.of("WMT", "MRNA")));
    }

    @Test
    void highlightedTabOverridesAStaleComponentSymbol() {
        assertEquals(
                "MRNA",
                ChartHoverHotkeyHandler.resolveHoverInstrument(
                        "MRNA",
                        "WMT",
                        Set.of("WMT", "MRNA"),
                        Set.of("MRNA")));
    }

    @Test
    void overlappingSymbolNamesRemainAmbiguousInsteadOfUsingSubstringOrder() {
        JPanel chart = chartPanel("AAPL1");

        assertNull(ChartHoverHotkeyHandler.identifyInstrumentFromComponent(
                chartSurface(chart), Set.of("AAPL", "AAPL1")));
    }

    private static JPanel chartPanel(String symbol) {
        JPanel chart = new JPanel();
        chart.add(new JLabel(symbol));
        JPanel surface = new JPanel();
        surface.setName("heatmap");
        chart.add(surface);
        return chart;
    }

    private static JPanel chartSurface(JPanel chart) {
        return (JPanel) chart.getComponent(1);
    }

    private static KeyEvent keyPressed(int keyCode) {
        return new KeyEvent(
                new Canvas(),
                KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                0,
                keyCode,
                KeyEvent.CHAR_UNDEFINED);
    }
}
