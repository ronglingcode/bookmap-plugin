package com.bookmap.plugin.rong.pricelines;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SelectedStopManagerTest {
    @Test void countdownRefreshesOnlyWhenTheDisplayedSecondChanges() {
        PriceLineStore store = new PriceLineStore(); SelectedStopManager manager = new SelectedStopManager(store);
        manager.register("AAPL", .01); manager.update("AAPL", 99, 30);
        PriceLine first = store.getLines("AAPL").get(0); assertEquals("Stop 99.00 (30)", first.getCustomLabel());
        manager.update("AAPL", 99, 30); assertSame(first, store.getLines("AAPL").get(0));
        manager.update("AAPL", 99, 29); assertEquals("Stop 99.00 (29)", store.getLines("AAPL").get(0).getCustomLabel());
        manager.update("AAPL", 99, 0); assertTrue(store.getLines("AAPL").isEmpty());
    }
    @Test void selectionReplacesOneLineAndClearsWithoutTouchingBrokerOrders() {
        PriceLineStore store = new PriceLineStore(); SelectedStopManager manager = new SelectedStopManager(store);
        manager.register("AAPL", .01); manager.register("MSFT", .01);
        store.addLine(new PriceLine("AAPL", PriceLine.LineType.EXIT_ORDER, 9800, 98));
        manager.update("AAPL", 99);
        assertEquals(2, store.getLines("AAPL").size());
        PriceLine line = store.getLines("AAPL").stream().filter(x -> x.getType() == PriceLine.LineType.SELECTED_STOP).findFirst().orElseThrow();
        assertEquals(9900, line.getPriceInTicks()); assertEquals("Stop", line.getType().label);
        manager.update("AAPL", 99); assertEquals(2, store.getLines("AAPL").size());
        manager.update("MSFT", 200); assertEquals(1, store.getLines("AAPL").size());
        manager.update("", 0); assertTrue(store.getLines("MSFT").isEmpty());
        assertEquals(PriceLine.LineType.EXIT_ORDER, store.getLines("AAPL").get(0).getType());
    }
}
