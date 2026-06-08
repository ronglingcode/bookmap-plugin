package com.bookmap.plugin.rong.orderwall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe storage for recent wall change visual alerts.
 */
public class OrderWallChangeStore {

    private static final int MAX_EVENTS_PER_INSTRUMENT = 40;

    @FunctionalInterface
    public interface ChangeListener {
        void onWallChangeEventsChanged(String instrumentAlias);
    }

    private final Map<String, Deque<OrderWallChangeEvent>> eventsByInstrument = new ConcurrentHashMap<>();
    private final List<ChangeListener> listeners = new CopyOnWriteArrayList<>();

    public void addEvent(OrderWallChangeEvent event) {
        Deque<OrderWallChangeEvent> events = eventsByInstrument
                .computeIfAbsent(event.getInstrumentAlias(), ignored -> new ConcurrentLinkedDeque<>());
        events.addFirst(event);
        while (events.size() > MAX_EVENTS_PER_INSTRUMENT) {
            events.pollLast();
        }
        notifyListeners(event.getInstrumentAlias());
    }

    public List<OrderWallChangeEvent> getRecentEvents(String instrumentAlias, long maxAgeMs, long nowMs) {
        Deque<OrderWallChangeEvent> events = eventsByInstrument.get(instrumentAlias);
        if (events == null || events.isEmpty()) {
            return Collections.emptyList();
        }
        List<OrderWallChangeEvent> recent = new ArrayList<>();
        for (OrderWallChangeEvent event : events) {
            if (nowMs - event.getCreatedAtMs() <= maxAgeMs) {
                recent.add(event);
            }
        }
        return Collections.unmodifiableList(recent);
    }

    public OrderWallChangeEvent getLatestEvent(String instrumentAlias, boolean bid, int priceTick,
                                               long maxAgeMs, long nowMs) {
        Deque<OrderWallChangeEvent> events = eventsByInstrument.get(instrumentAlias);
        if (events == null || events.isEmpty()) {
            return null;
        }
        for (OrderWallChangeEvent event : events) {
            if (nowMs - event.getCreatedAtMs() > maxAgeMs) {
                continue;
            }
            if (event.isBid() == bid && event.getPriceTick() == priceTick) {
                return event;
            }
        }
        return null;
    }

    public boolean hasRecentEvents(long maxAgeMs, long nowMs) {
        for (String instrumentAlias : eventsByInstrument.keySet()) {
            if (!getRecentEvents(instrumentAlias, maxAgeMs, nowMs).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public void addListener(ChangeListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ChangeListener listener) {
        listeners.remove(listener);
    }

    public void clearAll(String instrumentAlias) {
        eventsByInstrument.remove(instrumentAlias);
        notifyListeners(instrumentAlias);
    }

    private void notifyListeners(String instrumentAlias) {
        for (ChangeListener listener : listeners) {
            listener.onWallChangeEventsChanged(instrumentAlias);
        }
    }
}
