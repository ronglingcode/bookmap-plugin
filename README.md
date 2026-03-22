# Bookmap Plugin

A Bookmap addon that detects order wall breakouts, draws configurable price lines on charts, and sends real-time signals via WebSocket.

This repository produces **two plugins** from a shared codebase:


| Plugin                    | JAR                        | Description                       |
| ------------------------- | -------------------------- | --------------------------------- |
| **Rong**                  | `rong-1.0-all.jar`         | Personal plugin (private use)     |
| **Bookmap Active Trader** | `activetrader-1.0-all.jar` | Product plugin (for distribution) |


Both plugins share the same core logic in the `common` module. Features can be added independently to either plugin.

## How It Works

**Breakout detection:**

1. Monitors the order book for large resting ask orders (default: >= 500,000 shares at a single price level)
2. Tracks when these walls get consumed by aggressive buying (size drops below 20% of peak)
3. When price trades above a consumed wall, broadcasts a breakout signal via WebSocket

**Chart drawing:**
4. Hold a key (S/T/E by default) and click on the chart to draw a price line at that level
5. Premarket high/low lines are drawn and updated automatically during 4:00-9:30 AM ET
6. Predefined key price levels can be loaded from a JSON config file and/or added at runtime via the settings panel
8. All lines use Bookmap's data coordinates so they track through scroll and zoom

## Features

- **Order wall breakout detection** — monitors large ask-side walls and broadcasts signals when consumed
- **Key+click price lines** — hold a configurable key and click to draw stop loss, take profit, or entry lines
- **Auto-drawn indicators** — premarket high/low and Camarilla Pivot levels drawn automatically
- **Predefined key levels** — instrument-specific price levels loaded from a JSON config file or added via settings panel
- **WebSocket API** — real-time heartbeat, breakout, order book, and price select messages
- **Settings panels** — configure key bindings, enable/disable indicators, and manage key price levels at runtime

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
│   ├── CamPivotTracker      # Camarilla Pivot levels (R1–R6, S1–S6)
│   ├── IndicatorDataFetcher # Fetches indicator data from EdgeDesk API
│   ├── KeyLevelDefinition   # Predefined key level data model
│   ├── KeyLevelConfig       # JSON config file + session level storage
│   ├── KeyLevelManager      # Converts key levels to drawn price lines
│   ├── PluginLog            # File logger (per-session log files)
│   ├── IndicatorConfig      # Enable/disable toggles for auto indicators
│   ├── IndicatorSettingsPanel / KeyBindingSettingsPanel / KeyLevelSettingsPanel  # Settings UI
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


| Type           | Description                                  | Frequency                             |
| -------------- | -------------------------------------------- | ------------------------------------- |
| `heartbeat`    | Current price per symbol                     | Every 5s                              |
| `breakout`     | Wall breakout signal                         | On event                              |
| `orderbook`    | Order book snapshot (filtered by percentile) | At subscription interval (default 1s) |
| `priceSelect`  | Key+click price selection from chart         | On event                              |
| `subscribed`   | Confirmation of orderbook subscription       | Once on subscribe                     |
| `unsubscribed` | Confirmation of orderbook unsubscription     | Once on unsubscribe                   |


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


| Key | Line Type   | Color | Style  |
| --- | ----------- | ----- | ------ |
| `S` | Stop Loss   | Red   | Dashed |
| `T` | Take Profit | Green | Dashed |
| `E` | Entry       | Blue  | Solid  |


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


| Line    | Color  | Description                          |
| ------- | ------ | ------------------------------------ |
| PM High | Orange | Highest trade price during premarket |
| PM Low  | Purple | Lowest trade price during premarket  |


- **Premarket hours**: 4:00 AM - 9:30 AM Eastern Time
- Lines update in real-time as new highs/lows are made during premarket
- Lines persist after premarket ends as reference levels for regular trading hours
- Resets automatically at the start of each new premarket session (4:00 AM ET)
- Enabled by default; disable via the **Indicators** settings panel

### Camarilla Pivots (R1–R6, S1–S6)

Automatically draws all 12 Camarilla Pivot levels calculated from the previous day's high, low, and close.


| Lines | Color                        | Description       |
| ----- | ---------------------------- | ----------------- |
| R1–R6 | Red gradient (light → dark)  | Resistance levels |
| S1–S6 | Blue gradient (light → dark) | Support levels    |


- **Formula**: Uses a 1.1x range multiplier on previous day's range, with linear extensions for R5/R6 and S5/S6
- **Data source**: Previous day's daily candle is fetched from the EdgeDesk API (`/api/intraday-indicators`) on plugin initialization. This avoids the need for Bookmap to have previous-day price data in its session.
- **Static levels**: Pivots are calculated once and don't change during the day
- **Premarket seeding**: The same API call also returns premarket high/low data, which seeds the premarket tracker before any streaming data arrives
- Enabled by default; disable via the **Indicators** settings panel

### Key Price Levels

Draw predefined price levels on specific instruments' charts. Useful for marking significant support/resistance levels identified from daily or higher timeframe analysis.


| Line      | Color | Description                                         |
| --------- | ----- | --------------------------------------------------- |
| Key Level | Gold  | User-defined price level with optional custom label |


Key levels are instrument-specific — a $180 level on NVDA will only appear on NVDA's chart, not on any other instrument.

#### Sources

Key levels can come from two sources:

1. **JSON config file** — Create `~/bookmap-plugin/key-levels.json` to predefine levels that load on plugin startup:

```json
{
  "levels": [
    { "instrument": "NVDA", "price": 180.00, "label": "major support" },
    { "instrument": "NVDA", "price": 200.00, "label": "round number" },
    { "instrument": "ES", "price": 5400.00 }
  ]
}
```

- The `instrument` field must match the exact alias Bookmap uses (e.g., "NVDA", "ESM5")
- The `label` field is optional — if omitted, the default "Key Level" label is used
- File levels are read-only from the plugin; edit the file manually to change them

1. **Settings panel** — Add levels at runtime via the **Key Price Levels** settings panel. Enter the instrument alias, price, and optional label, then click "Add Level". Session levels can be removed via the panel but are not saved — they exist only while the plugin is running.

### Replay & Multi-Day Data

The premarket tracker uses Bookmap's **data/replay time** (not system clock), so it works correctly in both live and replay modes:

- **Multi-day replay**: When replaying feed data that spans multiple days, indicators automatically reset at the start of each new day's session. Previous day's lines are cleared and new lines are drawn for the current day.
- **Mid-session attach**: If the plugin is attached after trading has already started (e.g. you turn on your computer at 7 AM), it backfills from Bookmap's historical trade data to catch up on premarket high/low from 4:00 AM onward.
- **Time source**: All time decisions use the most recent data/replay timestamp received from Bookmap's `TimeListener`, which keeps trackers in sync with whatever time the chart is showing.

## Configuration

The following parameters are hardcoded constants in each plugin's main class:


| Parameter               | Default | Description                                                 |
| ----------------------- | ------- | ----------------------------------------------------------- |
| `WS_PORT`               | 8765    | WebSocket server port                                       |
| `WALL_THRESHOLD`        | 500,000 | Minimum shares at a price level to qualify as a wall        |
| `WALL_CONSUMED_RATIO`   | 0.20    | Wall is "consumed" when size drops below this ratio of peak |
| `SWING_LOOKBACK`        | 3       | Pivot N for swing low detection (N bars on each side)       |
| `BAR_SIZE`              | 100     | Number of trades per tick bar for swing low calculation     |
| `ORDERBOOK_PERCENTILE`  | 90      | Only send order book levels above this percentile threshold |
| `ORDERBOOK_INTERVAL_MS` | 1000    | Order book snapshot broadcast interval                      |


## Logging

Plugin logs are written to a dedicated file, separate from Bookmap's system logs. Each plugin session creates a new log file named by the session start time.


| OS          | Log directory                              |
| ----------- | ------------------------------------------ |
| **Windows** | `C:\Users\<username>\Bookmap\plugin_logs\` |
| **macOS**   | `~/Bookmap/plugin_logs/`                   |


Log files are named by datetime, e.g. `2026-03-21_10-30-45.txt`. Each line includes a timestamp and level:

```
2026-03-21 10:30:45.123 [INFO] [PremarketTracker] Backfilled NVDA: PM High=120.50, PM Low=118.20
2026-03-21 10:30:45.456 [ERROR] [IndicatorDataFetcher] HTTP 500 for AAPL
```

### Settings Panels

The plugin provides three settings panels accessible via the addon's configuration in Bookmap:


| Panel                       | Purpose                                                                         |
| --------------------------- | ------------------------------------------------------------------------------- |
| **Price Line Key Bindings** | Change which keys draw which line types (S/T/E defaults), clear all drawn lines |
| **Indicators**              | Enable/disable auto-drawn indicators (Premarket High/Low, Camarilla Pivots)     |
| **Key Price Levels**        | View file-loaded levels, add/remove session levels at runtime                   |


