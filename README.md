# Bookmap Plugin

A Bookmap addon that detects when price breaks through large ask-side limit order walls on US equities and sends real-time signals via WebSocket.

This repository produces **two plugins** from a shared codebase:

| Plugin | JAR | Description |
|--------|-----|-------------|
| **Rong** | `rong-1.0-all.jar` | Personal plugin (private use) |
| **Bookmap Active Trader** | `activetrader-1.0-all.jar` | Product plugin (for distribution) |

Both plugins share the same core logic in the `common` module. Features can be added independently to either plugin.

## How It Works

1. Monitors the order book for large resting ask orders (default: >= 500,000 shares at a single price level)
2. Tracks when these walls get consumed by aggressive buying (size drops below 20% of peak)
3. When price trades above a consumed wall, broadcasts a breakout signal containing:
   - **breakoutLevel**: the price level that was broken
   - **swingLow**: the most recent pivot swing low before the breakout

## Project Structure

```
bookmap-plugin/
├── common/          # Shared library (OrderBookState, SignalWebSocketServer, etc.)
├── rong/            # Personal plugin — @Layer1StrategyName("Rong")
└── activetrader/    # Product plugin — @Layer1StrategyName("Bookmap Active Trader")
```

## Build

Requires Java 11+ installed.

```bash
./gradlew shadowJar
```

Output JARs:
- `rong/build/libs/rong-1.0-all.jar`
- `activetrader/build/libs/activetrader-1.0-all.jar`

## Install in Bookmap

1. Open Bookmap
2. Go to **Settings** (gear icon) > **API Plugins Configuration**
3. Click **Add** and select the desired `-all.jar` file
4. In the popup, check the plugin name and click OK
5. Add the addon to a chart: right-click the chart > **Add Addon** > select the plugin

The plugin starts a WebSocket server on `localhost:8765` when attached to an instrument.

> **Note:** Do not run both plugins simultaneously — they share the same WebSocket port (8765).

## Connect Your Trading Bot

In your browser-based trading bot, connect to the WebSocket:

```javascript
const ws = new WebSocket('ws://localhost:8765');

ws.onmessage = (event) => {
  const data = JSON.parse(event.data);

  if (data.type === 'heartbeat') {
    // Sent every 5 seconds with current price
    // { "type": "heartbeat", "symbol": "AAPL", "price": 185.32, "timestamp": 1710345600000 }
  }

  if (data.type === 'breakout') {
    // Sent when price breaks through a large order wall
    // { "type": "breakout", "symbol": "AAPL", "breakoutLevel": 185.50, "swingLow": 184.20, "timestamp": 1710345600000 }
  }
};
```

## WebSocket API Reference

The plugin exposes a WebSocket server on `ws://localhost:8765`. Clients receive heartbeat and breakout messages automatically on connect. Additionally, clients can subscribe to real-time order book snapshots.

### Message types (server → client)

| Type | Description | Frequency |
|------|-------------|-----------|
| `heartbeat` | Current price per symbol | Every 5s |
| `breakout` | Wall breakout signal | On event |
| `orderbook` | Order book snapshot (filtered by percentile) | At subscription interval (default 1s) |
| `priceSelect` | Key+click price selection from chart | On event |
| `subscribed` | Confirmation of orderbook subscription | Once on subscribe |
| `unsubscribed` | Confirmation of orderbook unsubscription | Once on unsubscribe |

All messages include a `symbol` field identifying which instrument the data belongs to. Multiple instruments are supported simultaneously.

### Subscribe to order book (client → server)

```json
{"type":"subscribe","channel":"orderbook"}
{"type":"unsubscribe","channel":"orderbook"}
```

### TypeScript example

```typescript
interface Heartbeat {
  type: "heartbeat";
  symbol: string;
  price: number;
  timestamp: number;
}

interface Breakout {
  type: "breakout";
  symbol: string;
  breakoutLevel: number;
  swingLow: number;
  timestamp: number;
}

interface OrderBook {
  type: "orderbook";
  symbol: string;
  timestamp: number;
  percentile: number;
  minSize: number;
  bestBid?: number;
  bestAsk?: number;
  largeBids: [number, number][]; // [price, size][]
  largeAsks: [number, number][]; // [price, size][]
}

interface PriceSelect {
  type: "priceSelect";
  symbol: string;
  price: number;
  keyCode: string;
  timestamp: number;
}

interface Subscribed {
  type: "subscribed";
  channel: string;
  intervalMs: number;
  percentile: number;
}

type BookmapMessage = Heartbeat | Breakout | OrderBook | PriceSelect | Subscribed;

function connectToBookmap(
  onBreakout: (signal: Breakout) => void,
  onOrderBook?: (book: OrderBook) => void,
  onPriceSelect?: (select: PriceSelect) => void
) {
  const ws = new WebSocket("ws://localhost:8765");

  ws.onopen = () => {
    console.log("Connected to Bookmap plugin");
    ws.send(JSON.stringify({ type: "subscribe", channel: "orderbook" }));
  };

  ws.onmessage = (event: MessageEvent) => {
    const data: BookmapMessage = JSON.parse(event.data);

    switch (data.type) {
      case "heartbeat":
        break;

      case "breakout":
        onBreakout(data);
        break;

      case "orderbook":
        onOrderBook?.(data);
        break;

      case "priceSelect":
        onPriceSelect?.(data);
        break;

      case "subscribed":
        console.log(`Subscribed to ${data.channel} (every ${data.intervalMs}ms)`);
        break;
    }
  };

  ws.onclose = () => {
    console.log("Disconnected from Bookmap plugin");
    setTimeout(() => connectToBookmap(onBreakout, onOrderBook, onPriceSelect), 3000);
  };

  return ws;
}
```

## Configuration

These are currently hardcoded constants in each plugin's main class:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `WS_PORT` | 8765 | WebSocket server port |
| `WALL_THRESHOLD` | 500,000 | Minimum shares at a price level to qualify as a wall |
| `WALL_CONSUMED_RATIO` | 0.20 | Wall is "consumed" when size drops below this ratio of peak |
| `SWING_LOOKBACK` | 3 | Pivot N for swing low detection (N bars on each side) |
| `BAR_SIZE` | 100 | Number of trades per tick bar for swing low calculation |
| `ORDERBOOK_PERCENTILE` | 90 | Only send order book levels above this percentile threshold |
| `ORDERBOOK_INTERVAL_MS` | 1000 | Order book snapshot broadcast interval |
