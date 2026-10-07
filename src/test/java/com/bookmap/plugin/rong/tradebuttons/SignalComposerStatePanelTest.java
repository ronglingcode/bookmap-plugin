package com.bookmap.plugin.rong.tradebuttons;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class SignalComposerStatePanelTest {
    @Test void perStockPanelsRefreshIndependentlyAndKeepSelectionOnUnchangedReplayState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<String> amdState = new AtomicReference<>("AMD Enabled\nScanning");
            SignalComposerStatePanel amd = new SignalComposerStatePanel(amdState::get);
            SignalComposerStatePanel other = new SignalComposerStatePanel(() -> "OTHER Disabled");
            JTextArea text = (JTextArea)((JScrollPane)amd.getComponent(0)).getViewport().getView();
            assertFalse(text.isEditable()); assertTrue(text.getLineWrap());
            text.select(0, 3); amd.refresh(); assertEquals("AMD", text.getSelectedText());
            amdState.set("AMD Enabled\nWaiting for snapshot"); amd.refresh();
            assertTrue(text.getText().contains("Waiting for snapshot"));
            JTextArea otherText = (JTextArea)((JScrollPane)other.getComponent(0)).getViewport().getView();
            assertEquals("OTHER Disabled", otherText.getText());
        });
    }
}
