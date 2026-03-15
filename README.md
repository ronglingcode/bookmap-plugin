# Bookmap Active Trader Plugin

A Bookmap addon that detects when price breaks through large ask-side limit order walls on US equities and sends real-time signals via WebSocket.

## How It Works

1. Monitors the order book for large resting ask orders (default: >= 500,000 shares at a single price level)
2. Tracks when these walls get consumed by aggressive buying (size drops below 20% of peak)
3. When price trades above a consumed wall, broadcasts a breakout signal containing:
   - **breakoutLevel**: the price level that was broken
   - **swingLow**: the most recent pivot swing low before the breakout

## Build

Requires Java 11+ installed.

```bash
./gradlew shadowJar
```

Output JAR: `build/libs/wall-breakout-plugin-1.0-all.jar`

## Install in Bookmap

1. Open Bookmap
2. Go to **Settings** (gear icon) > **API Plugins Configuration**
3. Click **Add** and select `wall-breakout-plugin-1.0-all.jar`
4. In the popup, check **Bookmap Active Trader** and click OK
5. Add the addon to a chart: right-click the chart > **Add Addon** > **Bookmap Active Trader**

The plugin starts a WebSocket server on `localhost:8765` when attached to an instrument.

## Connect Your Trading Bot

In your browser-based trading bot, connect to the WebSocket:

```javascript
const ws = new WebSocket('ws://localhost:8765');

ws.onmessage = (event) => {
  const data = JSON.parse(event.data);

  if (data.type === 'heartbeat') {
    // Sent every 5 seconds with current price
    // { "type": "heartbeat", "price": 185.32, "timestamp": 1710345600000 }
  }

  if (data.type === 'breakout') {
    // Sent when price breaks through a large order wall
    // { "type": "breakout", "breakoutLevel": 185.50, "swingLow": 184.20, "timestamp": 1710345600000 }
  }
};
```

## WebSocket API Reference

The plugin exposes a WebSocket server on `ws://localhost:8765`. Clients receive heartbeat and breakout messages automatically on connect. Additionally, clients can subscribe to real-time order book snapshots.

### Message types (server → client)

| Type | Description | Frequency |
|------|-------------|-----------|
| `heartbeat` | Current price | Every 5s (or at subscription interval for subscribers) |
| `breakout` | Wall breakout signal | On event |
| `orderbook` | Order book snapshot (top N levels) | At subscription interval (default 1s) |
| `subscribed` | Confirmation of orderbook subscription | Once on subscribe |
| `unsubscribed` | Confirmation of orderbook unsubscription | Once on unsubscribe |

### Subscribe to order book (client → server)

```json
{"type":"subscribe","channel":"orderbook"}
{"type":"subscribe","channel":"orderbook","intervalMs":500,"levels":10}
{"type":"unsubscribe","channel":"orderbook"}
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `intervalMs` | 1000 | How often to send snapshots (milliseconds) |
| `levels` | 20 | Number of price levels per side (bids + asks) |

### TypeScript example

```typescript
interface Heartbeat {
  type: "heartbeat";
  price: number;
  timestamp: number;
}

interface Breakout {
  type: "breakout";
  breakoutLevel: number;
  swingLow: number;
  timestamp: number;
}

interface OrderBook {
  type: "orderbook";
  timestamp: number;
  bids: [number, number][]; // [price, size][]
  asks: [number, number][]; // [price, size][]
}

interface Subscribed {
  type: "subscribed";
  channel: string;
  intervalMs: number;
  levels: number;
}

type BookmapMessage = Heartbeat | Breakout | OrderBook | Subscribed;

function connectToBookmap(
  onBreakout: (signal: Breakout) => void,
  onOrderBook?: (book: OrderBook) => void,
  orderbookIntervalMs = 1000,
  orderbookLevels = 20
) {
  const ws = new WebSocket("ws://localhost:8765");

  ws.onopen = () => {
    console.log("Connected to Bookmap plugin");
    // Subscribe to order book snapshots
    ws.send(
      JSON.stringify({
        type: "subscribe",
        channel: "orderbook",
        intervalMs: orderbookIntervalMs,
        levels: orderbookLevels,
      })
    );
  };

  ws.onmessage = (event: MessageEvent) => {
    const data: BookmapMessage = JSON.parse(event.data);

    switch (data.type) {
      case "heartbeat":
        // Price update — use for connection health or last price display
        break;

      case "breakout":
        onBreakout(data);
        break;

      case "orderbook":
        onOrderBook?.(data);
        break;

      case "subscribed":
        console.log(
          `Subscribed to ${data.channel} (every ${data.intervalMs}ms, ${data.levels} levels)`
        );
        break;
    }
  };

  ws.onclose = () => {
    console.log("Disconnected from Bookmap plugin");
    // Reconnect after 3 seconds
    setTimeout(
      () =>
        connectToBookmap(
          onBreakout,
          onOrderBook,
          orderbookIntervalMs,
          orderbookLevels
        ),
      3000
    );
  };

  return ws;
}

// Usage
connectToBookmap(
  (signal) => {
    console.log(
      `BREAKOUT above ${signal.breakoutLevel}, swing low at ${signal.swingLow}`
    );
    // Send to your trading bot logic...
  },
  (book) => {
    const bestBid = book.bids[0];
    const bestAsk = book.asks[0];
    console.log(
      `Book: ${bestBid[1]} @ ${bestBid[0]} | ${bestAsk[0]} @ ${bestAsk[1]}`
    );
  },
  500, // 500ms interval
  10 // top 10 levels
);
```

## Bookmap API Data vs Visual Display

The Bookmap Java API provides **raw, unfiltered market data**. UI features like size filters, trade clustering, and zoom-based aggregation do not affect what the plugin receives.

| What you see in Bookmap UI | What the API gives the plugin |
|---|---|
| Heatmap with size filter (hides small orders) | **All** depth updates, unfiltered |
| Trade bubbles (clustered nearby trades) | **Individual** trades, one by one |
| Zoom-dependent aggregation (price levels merge when zoomed out) | **Tick-level** data, unchanged by zoom |

### Bookmap's clustering modes

Bookmap's UI clusters trades into "bubbles" using one of four modes:

| Mode | How it works |
|------|-------------|
| **By Price** | Aggregates all trades at the same price level within a time window into one bubble |
| **By Time** | One bubble per fixed time interval (e.g., 100ms), aggregating all trades in that interval |
| **By Volume** | Creates a new bubble every time accumulated volume reaches a threshold (e.g., 10,000 shares) |
| **Smart** (default) | Weighted average of price and time — combines nearby trades using a proximity-based merge |

The **Smart** mode positions each bubble at the **volume-weighted average price (VWAP)** and **time-weighted average timestamp** of the trades it contains. It is essentially a sliding-window aggregation that merges trades that are close in both price and time, rather than a complex ML algorithm. This produces the intuitive "bubble" visualization where a burst of buying at a price level shows as a single large dot instead of hundreds of tiny ones.

Since the API only provides raw trades, any clustering must be implemented in the plugin itself.

### How Bookmap renders "order walls"

Bookmap has **no built-in wall detection algorithm**. The bright horizontal bands you see are a visual effect of the heatmap's relative color mapping:

- **Color intensity is relative**: Bookmap maps all visible order sizes to a color gradient using Upper and Lower Cutoff settings. Sizes at or above the Upper Cutoff get the brightest color (red/orange); sizes at or below the Lower Cutoff get the dimmest (dark blue). Everything in between is interpolated along the gradient. The exact interpolation curve is proprietary (likely logarithmic given heavy-tailed order size distributions).
- **Percentile-based auto mode**: When Upper Cutoff is set to "Auto 97%" (the default), Bookmap sorts all order sizes currently visible on screen and picks the 97th percentile value as the cutoff. The top 3% of sizes get maximum brightness. This recalculates dynamically as you scroll or zoom.
- **Thickness = adjacent large levels**: A "thick wall" means multiple consecutive price levels all have large orders (e.g., 500K at $185.50, $185.51, $185.52 = a 3-cent thick band). A single large level appears as a thin bright line.
- **The size filter dims, not hides**: Levels below the Lower Cutoff are dimmed but still visible — nothing is removed from the display.
- **Auto-adjusting contrast**: The color mapping recalculates as you scroll or zoom, so the same 500K order may look bright in a quiet market but dim next to a 2M order.

Bookmap also provides supporting tools (not wall detection):
- **Large Lot Tracker** — estimates the single largest order at a price level using a proprietary approximation algorithm (not 100% accurate). Triggers when an order exceeds 20% of total size at that level AND 10% of the largest visible order bar.
- **Iceberg Order Tracker** — detects hidden orders that replenish after being consumed. Recognizes when new limit orders appear instantly from the opposite side as a response to executions at the same price level.
- **Imbalance Indicators** — shows buy vs sell pressure at each level.

Our plugin takes a more explicit approach: any ask level with >= 500K shares is flagged as a wall, regardless of visual context. A future improvement could use percentile-based thresholds (similar to Bookmap's auto mode) by maintaining a running distribution of order sizes and flagging outliers.

## Configuration

These are currently hardcoded constants in `BookmapActiveTraderPlugin.java`:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `WS_PORT` | 8765 | WebSocket server port |
| `WALL_THRESHOLD` | 500,000 | Minimum shares at a price level to qualify as a wall |
| `WALL_CONSUMED_RATIO` | 0.20 | Wall is "consumed" when size drops below this ratio of peak |
| `SWING_LOOKBACK` | 3 | Pivot N for swing low detection (N bars on each side) |
| `BAR_SIZE` | 100 | Number of trades per tick bar for swing low calculation |
