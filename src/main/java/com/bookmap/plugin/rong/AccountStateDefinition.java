package com.bookmap.plugin.rong;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-symbol account snapshot pushed from ViteApp to the Rong account panel.
 */
public class AccountStateDefinition {

    private final String symbol;
    private final AccountPositionDefinition position;
    private final List<AccountOrderDefinition> openOrders;
    private final List<AccountExecutionDefinition> executions;
    private final long timestamp;

    public AccountStateDefinition(
            String symbol,
            AccountPositionDefinition position,
            List<AccountOrderDefinition> openOrders,
            List<AccountExecutionDefinition> executions,
            long timestamp) {
        this.symbol = normalize(symbol);
        this.position = position;
        List<AccountOrderDefinition> orders = openOrders == null ? Collections.emptyList() : openOrders;
        this.openOrders = Collections.unmodifiableList(new ArrayList<>(orders));
        List<AccountExecutionDefinition> fills = executions == null ? Collections.emptyList() : executions;
        this.executions = Collections.unmodifiableList(new ArrayList<>(fills));
        this.timestamp = timestamp;
    }

    public String getSymbol() {
        return symbol;
    }

    public AccountPositionDefinition getPosition() {
        return position;
    }

    public List<AccountOrderDefinition> getOpenOrders() {
        return openOrders;
    }

    public List<AccountExecutionDefinition> getExecutions() {
        return executions;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public boolean hasOpenPosition() {
        return position != null && position.isOpen();
    }

    public boolean hasOpenOrders() {
        return !openOrders.isEmpty();
    }

    public boolean hasVisibleState() {
        return hasOpenPosition() || hasOpenOrders();
    }

    private String normalize(String value) {
        return value == null ? "" : value;
    }
}
