# Bookmap Plugin

A Bookmap addon that detects order wall breakouts, draws configurable price lines on charts, and sends real-time signals via WebSocket.

This repository produces **two plugins** from a shared codebase:

| Plugin | JAR | Description |
|--------|-----|-------------|
| **Rong** | `rong-1.0-all.jar` | Personal plugin (private use) |
| **Bookmap Active Trader** | `activetrader-1.0-all.jar` | Product plugin (for distribution) |

Both plugins share the same core logic in the `common` module. Features can be added independently to either plugin.

## How It Works

**Breakout detection:**
1. Monitors the order book for large resting ask orders (default: >= 500,000 shares at a single price level)
2. Tracks when these walls get consumed by aggressive buying (size drops below 20% of peak)
3. When price trades above a consumed wall, broadcasts a breakout signal via WebSocket

**Chart drawing:**
4. Hold a key (S/T/E by default) and click on the chart to draw a price line at that level
5. Premarket high/low lines are drawn and updated automatically during 4:00-6:30 AM ET
6. All lines use Bookmap's data coordinates so they track through scroll and zoom

## Features

- **Order wall breakout detection** — monitors large ask-side walls and broadcasts signals when consumed
- **Key+click price lines** — hold a configurable key and click to draw stop loss, take profit, or entry lines
- **Auto-drawn indicators** — premarket high/low levels drawn and updated automatically
- **WebSocket API** — real-time heartbeat, breakout, order book, and price select messages
- **Settings panels** — configure key bindings and enable/disable indicators at runtime

## Project Structure

```
bookmap-plugin/
├── common/                  # Shared library
│   ├── ChartClickHandler    # Key+click detection & coordinate mapping
│   ├── PriceLine            # Line data model (manual + auto types)
│   ├── PriceLineStore       # Thread-safe line storage with change listeners
│   ├── PriceLinePainter     # ScreenSpaceCanvas drawing engine
│   ├── PriceLineConfig      # Key binding configuration
│   ├── PremarketTracker     # Auto premarket high/low tracking
│   ├── IndicatorConfig      # Enable/disable toggles for auto indicators
│   ├── IndicatorSettingsPanel / KeyBindingSettingsPanel  # Settings UI
│   ├── OrderBookState       # Full order book state
│   ├── OrderWallTracker     # Large wall detection & consumption
│   ├── SwingLowDetector     # Pivot swing low detection
│   ├── SignalWebSocketServer # WebSocket server for external clients
│   └── BreakoutSignal       # Signal data model
├── rong/                    # Personal plugin — @Layer1StrategyName("Rong")
└── activetrader/            # Product plugin — @Layer1StrategyName("Bookmap Active Trader")
```

## Build

Requires Java 11+ installed.

```bash
mac: ./gradlew shadowJar
windows: gradlew shadowJar
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

## Price Lines (Key+Click Drawing)

Hold a key and left-click on the chart to draw a persistent horizontal price line at the clicked price. Lines automatically track the price level through scroll and zoom.

### Default Key Bindings

| Key | Line Type | Color | Style |
|-----|-----------|-------|-------|
| `S` | Stop Loss | Red | Dashed |
| `T` | Take Profit | Green | Dashed |
| `E` | Entry | Blue | Solid |

Each line displays a label with its type and price value.

### Configuring Key Bindings

1. Right-click the chart > open the plugin's addon settings
2. In the **Price Line Key Bindings** panel, change the key for each line type
3. Use the **Clear All Price Lines** button to remove all drawn lines

Key+click events also continue to broadcast `priceSelect` messages via WebSocket, so external clients still receive them.

## Indicators (Auto-Drawn Levels)

The plugin can automatically draw price levels based on market data. Each indicator can be enabled or disabled in the **Indicators** settings panel.

### Premarket High / Low

Automatically draws and updates horizontal lines at the premarket session high and low prices.

| Line | Color | Description |
|------|-------|-------------|
| PM High | Orange | Highest trade price during premarket |
| PM Low | Purple | Lowest trade price during premarket |

- **Premarket hours**: 4:00 AM - 6:30 AM Eastern Time
- Lines update in real-time as new highs/lows are made during premarket
- Lines persist after premarket ends as reference levels for regular trading hours
- Resets automatically at the start of each new premarket session (4:00 AM ET)
- Enabled by default; disable via the **Indicators** settings panel

## Configuration

The following parameters are hardcoded constants in each plugin's main class:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `WS_PORT` | 8765 | WebSocket server port |
| `WALL_THRESHOLD` | 500,000 | Minimum shares at a price level to qualify as a wall |
| `WALL_CONSUMED_RATIO` | 0.20 | Wall is "consumed" when size drops below this ratio of peak |
| `SWING_LOOKBACK` | 3 | Pivot N for swing low detection (N bars on each side) |
| `BAR_SIZE` | 100 | Number of trades per tick bar for swing low calculation |
| `ORDERBOOK_PERCENTILE` | 90 | Only send order book levels above this percentile threshold |
| `ORDERBOOK_INTERVAL_MS` | 1000 | Order book snapshot broadcast interval |

Price line key bindings are configurable at runtime via the plugin's settings panel (see above).
