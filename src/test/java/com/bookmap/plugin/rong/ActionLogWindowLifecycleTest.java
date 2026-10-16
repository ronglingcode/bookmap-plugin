package com.bookmap.plugin.rong;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.util.Collections;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class ActionLogWindowLifecycleTest {
    private static Object field(String name) throws Exception {
        Field field = ActionLogWindow.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void flush() throws Exception { SwingUtilities.invokeAndWait(() -> {}); }

    @AfterEach void close() throws Exception { ActionLogWindow.dispose(); flush(); }

    @Test void removalCancelsQueuedCreationAndIgnoresLateUpdates() throws Exception {
        // Keep creation queued until after removal; this also runs in headless CI.
        SwingUtilities.invokeAndWait(() -> {
            ActionLogWindow.showWindow();
            ActionLogWindow.append("queued before removal");
            ActionLogWindow.dispose();
            ActionLogWindow.append("late shutdown event");
            ActionLogWindow.updateAccountState(new AccountStateDefinition(
                    "TEST", null, Collections.emptyList(), Collections.emptyList(), 1L));
            ActionLogWindow.updateHighlightedSymbol("TEST");
            ActionLogWindow.updatePersistenceStatus("late writer callback");
        });
        flush();
        assertNull(field("frame"));
        assertNull(field("session"));
        assertEquals("", field("highlightedSymbol"));
        assertTrue(((ScreenLogBuffer) field("lines")).lines().isEmpty());
        assertTrue(((java.util.Map<?, ?>) field("accountStates")).isEmpty());
    }

    @Test void reattachmentInvalidatesOldQueuedCallbacks() throws Exception {
        java.lang.reflect.Method dispatch = ActionLogWindow.class.getDeclaredMethod("invokeWhenActive", Runnable.class);
        dispatch.setAccessible(true);
        java.util.concurrent.atomic.AtomicInteger delivered = new java.util.concurrent.atomic.AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            try {
                ActionLogWindow.showWindow();
                dispatch.invoke(null, (Runnable) () -> delivered.incrementAndGet());
                ActionLogWindow.dispose();
                // Start a new session without creating a frame, for headless CI.
                Field session = ActionLogWindow.class.getDeclaredField("session");
                session.setAccessible(true);
                session.set(null, new Object());
                dispatch.invoke(null, (Runnable) () -> delivered.addAndGet(10));
            } catch (Exception error) { throw new AssertionError(error); }
        });
        flush();
        assertEquals(10, delivered.get());
    }

    @Test void removeAndReaddDisposesOldFrameAndKeepsOneWindow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        ActionLogWindow.showWindow(); flush();
        JFrame old = (JFrame) field("frame");
        assertTrue(old.isDisplayable());
        ActionLogWindow.dispose();
        ActionLogWindow.showWindow(); flush();
        JFrame next = (JFrame) field("frame");
        assertNotSame(old, next);
        assertFalse(old.isDisplayable());
        assertTrue(next.isDisplayable());
        ActionLogWindow.showWindow(); flush();
        assertSame(next, field("frame"));
    }
}
