package com.bookmap.plugin.rong;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import velox.api.layer1.simplified.Api;
import static org.junit.jupiter.api.Assertions.*;

class RongPluginRemovalTest {
    private static Field field(String name) throws Exception {
        Field field = RongPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static RongPlugin attachment(String alias, AtomicInteger removals) throws Exception {
        RongPlugin plugin = new RongPlugin();
        field("initialized").setBoolean(plugin, true);
        field("alias").set(plugin, alias);
        field("api").set(plugin, Proxy.newProxyInstance(Api.class.getClassLoader(), new Class<?>[]{Api.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("sendUserMessage")) {
                        removals.incrementAndGet();
                        throw new IllegalStateException("Bookmap has already removed the add-on");
                    }
                    return null;
                }));
        return plugin;
    }

    @Test void rejectedPainterRemovalsStillReleaseLastAttachmentAndAllowReattachment() throws Exception {
        AtomicInteger removals = new AtomicInteger();
        AtomicInteger shutdowns = new AtomicInteger();
        SignalWebSocketServer server = new SignalWebSocketServer(0, 97) {
            @Override public void shutdown() {
                super.shutdown();
                shutdowns.incrementAndGet();
                throw new IllegalStateException("shutdown failure");
            }
        };
        assertNull(field("sharedServer").get(null));
        int previousCount = field("instanceCount").getInt(null);
        try {
            field("sharedServer").set(null, server);
            field("instanceCount").setInt(null, 2);
            RongPlugin first = attachment("FIRST", removals);
            RongPlugin last = attachment("LAST", removals);
            assertDoesNotThrow(first::stop);
            first.stop();
            assertEquals(1, field("instanceCount").getInt(null));
            assertSame(server, field("sharedServer").get(null));
            assertEquals(0, shutdowns.get());
            assertDoesNotThrow(last::stop);
            assertEquals(12, removals.get());
            assertEquals(1, shutdowns.get());
            assertNull(field("sharedServer").get(null));
            assertEquals(0, field("instanceCount").getInt(null));
            // A fresh attachment can acquire and release shared state again.
            SignalWebSocketServer next = new SignalWebSocketServer(0, 97);
            field("sharedServer").set(null, next);
            field("instanceCount").setInt(null, 1);
            assertDoesNotThrow(attachment("NEXT", removals)::stop);
            assertNull(field("sharedServer").get(null));
        } finally {
            field("sharedServer").set(null, null);
            field("instanceCount").setInt(null, previousCount);
        }
    }

    @Test void unexpectedAttachmentFailureStillReleasesSharedResources() throws Exception {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 97) {
            @Override public void unregisterSymbol(String alias) {
                throw new IllegalStateException("attachment cleanup failed");
            }
        };
        assertNull(field("sharedServer").get(null));
        int previousCount = field("instanceCount").getInt(null);
        try {
            field("sharedServer").set(null, server);
            field("instanceCount").setInt(null, 1);
            RongPlugin plugin = attachment("TEST", new AtomicInteger());
            assertThrows(IllegalStateException.class, plugin::stop);
            assertEquals(0, field("instanceCount").getInt(null));
            assertNull(field("sharedServer").get(null));
            assertDoesNotThrow(plugin::stop);
        } finally {
            field("sharedServer").set(null, null);
            field("instanceCount").setInt(null, previousCount);
        }
    }
}
