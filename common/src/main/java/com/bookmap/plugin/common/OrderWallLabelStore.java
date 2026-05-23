package com.bookmap.plugin.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe storage for retained order wall labels.
 *
 * Supports multiple historical segments at the same price level while allowing
 * at most one active segment per side/price.
 */
public class OrderWallLabelStore {

    private static class InstrumentLabels {
        private final Map<String, OrderWallLabel> labelsById = new ConcurrentHashMap<>();
        private final Map<String, String> activeLabelIdByPrice = new ConcurrentHashMap<>();
        private final Set<String> displayedLabelIds = ConcurrentHashMap.newKeySet();
    }

    private final Map<String, InstrumentLabels> labelsByInstrument = new ConcurrentHashMap<>();

    public OrderWallLabel getActiveLabel(String instrumentAlias, boolean bid, int priceTick) {
        InstrumentLabels instrumentLabels = labelsByInstrument.get(instrumentAlias);
        if (instrumentLabels == null) {
            return null;
        }
        String priceKey = OrderWallLabel.buildKey(bid, priceTick);
        String activeId = instrumentLabels.activeLabelIdByPrice.get(priceKey);
        return activeId != null ? instrumentLabels.labelsById.get(activeId) : null;
    }

    public void putLabel(OrderWallLabel label) {
        InstrumentLabels instrumentLabels =
                labelsByInstrument.computeIfAbsent(label.getInstrumentAlias(), ignored -> new InstrumentLabels());
        instrumentLabels.labelsById.put(label.getId(), label);

        String priceKey = label.getPriceKey();
        if (label.isActive()) {
            instrumentLabels.activeLabelIdByPrice.put(priceKey, label.getId());
        } else {
            instrumentLabels.activeLabelIdByPrice.remove(priceKey, label.getId());
        }
    }

    public boolean removeLabel(String instrumentAlias, String labelId) {
        InstrumentLabels instrumentLabels = labelsByInstrument.get(instrumentAlias);
        if (instrumentLabels == null) {
            return false;
        }
        OrderWallLabel removed = instrumentLabels.labelsById.remove(labelId);
        if (removed == null) {
            return false;
        }
        instrumentLabels.activeLabelIdByPrice.remove(removed.getPriceKey(), removed.getId());
        instrumentLabels.displayedLabelIds.remove(labelId);
        return true;
    }

    public void markDisplayed(String instrumentAlias, String labelId) {
        InstrumentLabels instrumentLabels = labelsByInstrument.get(instrumentAlias);
        if (instrumentLabels == null) {
            return;
        }
        if (instrumentLabels.labelsById.containsKey(labelId)) {
            instrumentLabels.displayedLabelIds.add(labelId);
        }
    }

    public boolean hasBeenDisplayed(String instrumentAlias, String labelId) {
        InstrumentLabels instrumentLabels = labelsByInstrument.get(instrumentAlias);
        return instrumentLabels != null && instrumentLabels.displayedLabelIds.contains(labelId);
    }

    public List<OrderWallLabel> getLabels(String instrumentAlias) {
        InstrumentLabels instrumentLabels = labelsByInstrument.get(instrumentAlias);
        if (instrumentLabels == null || instrumentLabels.labelsById.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(instrumentLabels.labelsById.values()));
    }

    public void clearAll(String instrumentAlias) {
        labelsByInstrument.remove(instrumentAlias);
    }
}
