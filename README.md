# Bookmap Plugin

A Bookmap addon that detects order wall breakouts, draws chart price levels, and sends real-time signals via WebSocket.

This repository produces two Bookmap addon plugins in the same build:

| Plugin   | JAR                | Description                   |
| -------- | ------------------ | ----------------------------- |
| **Rong** | `rong-1.0-all.jar` | Personal plugin (private use) |
| **Rong Backtest Exporter** | `rong-1.0-all.jar` | Replay data exporter for Bookmap backtests |

## How It Works

**Breakout detection:**

1. Monitors the order book for large resting ask orders (default: >= 500,000 shares at a single price level)
2. Tracks when these walls get consumed by aggressive buying (size drops to 10% or less of peak)
3. When price trades above a consumed wall, broadcasts a breakout signal via WebSocket

**Chart drawing:**
4. Hold an action key and left-click on the chart to send the key and clicked price via WebSocket
5. Premarket high/low lines are drawn and updated automatically during 4:00-9:30 AM ET
6. Key price levels received over WebSocket are drawn on the matching instrument
7. Large liquidity walls are labeled directly on the heatmap using compact growth paths like `5→7→10`
8. Wall labels retain the increasing size path seen at each level and start a new phase label after 2x growth
9. All lines and labels use Bookmap's data coordinates so they track through scroll and zoom

## Features

- **Order wall breakout detection** — monitors large ask-side walls and broadcasts signals when consumed
- **Key+left-click pass-through** — sends the pressed key and clicked chart price to the trading bot
- **Auto-drawn indicators** — premarket high/low and Camarilla Pivot levels drawn automatically
- **WebSocket key levels/zones** — instrument-specific price levels and zones pushed by an external app
- **WebSocket API** — real-time breakout, order book, and price select messages
- **Settings panels** — enable/disable indicators and optionally export replay data

## Project Structure

```
bookmap-plugin/
├── build.gradle
├── settings.gradle
└── src/main/java/com/bookmap/plugin/rong/
    ├── RongPlugin              # @Layer1StrategyName("Rong")
    ├── pricelines/             # Line model, storage, painting, and click price mapping
    │   ├── ChartClickHandler   # Key+left-click detection & coordinate mapping
    │   └── PriceLine*/PriceZone* # Level/zone model, storage, and painting
    ├── orderwall/              # Large wall detection, labels, and painting
    │   └── OrderWall*
    ├── tradebuttons/           # Floating trade button panel and tradebook button config
    │   ├── TradeButtonWindow
    │   └── TradebookButtonGroup
    ├── MarketLevel*            # WebSocket cam pivots, previous day, and premarket lines
    ├── KeyLevel*               # WebSocket key level definitions and drawing bridge
    ├── OrderBookState          # Full order book state
    ├── SignalWebSocketServer   # WebSocket server for external clients
    └── PluginLog               # File logger
```

## Build

Requires Java 11+ installed.

```bash
mac: ./gradlew shadowJar
windows: gradlew shadowJar
```

Output JARs:

- `build/libs/rong-1.0-all.jar` contains both `Rong` and `Rong Backtest Exporter`

## Install in Bookmap

1. Open Bookmap
2. Go to **Settings** (gear icon) > **API Plugins Configuration**
3. Click **Add** and select the desired `-all.jar` file
4. In the popup, check the plugin name and click OK
5. Add the addon to a chart: right-click the chart > **Add Addon** > select the plugin

The `Rong` plugin starts a WebSocket server on `localhost:8765` when attached to an instrument. The `Rong Backtest Exporter` plugin does not start a server; it writes replay data to disk.

## Backtest Exporter

Use `Rong Backtest Exporter` when replaying a `.bmf` file in Bookmap. Attach it to each instrument you want to export. It writes normalized JSONL events that can be consumed later by a standalone backtest engine.

The live `Rong` plugin can also export the same replay event format, but this is disabled by default. To enable it, open the `Rong` addon configuration and turn on **Replay Export > Export replay data from Rong**. This lets you keep the Rong plugin attached while replaying feeds and collect the same historical data without attaching the separate exporter addon.

Default output:

```text
C:\Users\{username}\Bookmap\backtest-exports\{run-id}\{symbol}\
  metadata.json
  events.jsonl
```

The event stream includes:

- `session_start`
- `depth`
- `trade`
- `bbo`
- `snapshot_end`
- `realtime_start`
- `session_end`

Optional Java system properties:

| Property | Default | Description |
| -------- | ------- | ----------- |
| `bookmap.export.dir` | `~/Bookmap/backtest-exports` | Export root directory |
| `bookmap.export.depthMinSize` | `0` | Minimum absolute depth level size to export; `0` exports all depth updates |
| `bookmap.export.flushEvery` | `1000` | Flush after this many JSONL events |
| `rong.replayExport.enabled` | `false` | Optional startup default for Rong's own replay export toggle |

Design details are in `docs/bookmap-backtest-system-design.md`.

## Connect Your Trading Bot

In your browser-based trading bot, connect to the WebSocket:

```javascript
const ws = new WebSocket('ws://localhost:8765');

ws.onmessage = (event) => {
  const data = JSON.parse(event.data);

  if (data.type === 'breakout') {
    // Sent when price breaks through a large order wall
    // { "type": "breakout", "symbol": "AAPL", "breakoutLevel": 185.50, "timestamp": 1710345600000 }
  }
};
```

## WebSocket API Reference

The plugin exposes a WebSocket server on `ws://localhost:8765`. Clients receive breakout messages as events occur. Additionally, clients can subscribe to real-time order book snapshots and send key levels/zones to draw.

### Message types (server → client)


| Type           | Description                                  | Frequency                             |
| -------------- | -------------------------------------------- | ------------------------------------- |
| `breakout`     | Wall breakout signal                         | On event                              |
| `orderbook`    | Order book snapshot (filtered by percentile) | At subscription interval (default 1s) |
| `priceSelect`  | Key+left-click price selection from chart    | On event                              |
| `subscribed`   | Confirmation of orderbook subscription       | Once on subscribe                     |
| `unsubscribed` | Confirmation of orderbook unsubscription     | Once on unsubscribe                   |


All messages include a `symbol` field identifying which instrument the data belongs to. Multiple instruments are supported simultaneously.

### Subscribe to order book (client → server)

```json
{"type":"subscribe","channel":"orderbook"}
{"type":"unsubscribe","channel":"orderbook"}
```

### Send key levels and zones (client → server)

```json
{
  "type": "key_levels_config",
  "symbol": "AAPL",
  "levels": [
    { "price": 185.50, "label": "daily resistance" },
    { "price": 180.00 }
  ],
  "zones": [
    { "low": 179.40, "high": 183.75, "label": "daily zone", "color": "#9ca3af" }
  ],
  "camPivots": {
    "R1": 184.12,
    "R2": 185.24,
    "R3": 186.36,
    "R4": 189.72,
    "S1": 181.88,
    "S2": 180.76,
    "S3": 179.64,
    "S4": 176.28
  },
  "previousDay": { "high": 187.20, "low": 178.30 },
  "premarket": { "high": 183.75, "low": 179.40 },
  "timestamp": 1710345600000
}
```

Sending an empty `levels` array clears existing key level lines for that symbol. Sending an empty or missing `zones` array clears existing key zones for that symbol. Missing or empty market-level fields clear their corresponding websocket-supplied market lines for that symbol.

### TypeScript example

```typescript
interface Breakout {
  type: "breakout";
  symbol: string;
  breakoutLevel: number;
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

type BookmapMessage = Breakout | OrderBook | PriceSelect | Subscribed;

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

## Price Select Clicks

Hold an action key and left-click on the chart to broadcast a `priceSelect` message via WebSocket. The plugin sends the pressed key and clicked price to the trading bot; it does not interpret those keys or draw manual stop loss, take profit, or entry lines locally.

Auto-drawn price levels still track through scroll and zoom. These include premarket high/low, Camarilla Pivot levels, key levels received over WebSocket, and broker-managed exits.

## Indicators (Auto-Drawn Levels)

The plugin draws market levels supplied by the external WebSocket client. Each indicator can be enabled or disabled in the **Indicators** settings panel.

Order wall size-change sounds are enabled by default. The visual size-change alert overlays are disabled by default to keep the heatmap uncluttered, but they can still be enabled from the **Indicators** settings panel.

### Premarket High / Low

Draws horizontal lines at the premarket session high and low prices sent by the external client.


| Line    | Color  | Description                          |
| ------- | ------ | ------------------------------------ |
| PM High | Orange | Highest trade price during premarket |
| PM Low  | Purple | Lowest trade price during premarket  |


- **Data source**: The external client sends `premarket.high` and `premarket.low` in the `key_levels_config` WebSocket message
- Lines update when the external client pushes refreshed premarket values
- Lines persist after premarket ends as reference levels for regular trading hours
- Enabled by default; disable via the **Indicators** settings panel

### Camarilla Pivots (R1–R6, S1–S6)

Draws Camarilla Pivot levels supplied by the external client.


| Lines | Color                        | Description       |
| ----- | ---------------------------- | ----------------- |
| R1–R6 | Red gradient (light → dark)  | Resistance levels |
| S1–S6 | Blue gradient (light → dark) | Support levels    |


- **Data source**: The external client sends `camPivots` in the `key_levels_config` WebSocket message
- **Static levels**: Pivots normally stay fixed for the day, but the plugin redraws them whenever the client sends an updated config
- Enabled by default; disable via the **Indicators** settings panel

### Previous Day High / Low

Draws the previous regular-session high and low supplied by the external client in the `previousDay` field of the `key_levels_config` WebSocket message.

### Key Price Levels And Zones

Draw key price levels and filled price zones on specific instruments' charts. Useful for marking significant support/resistance levels identified from daily or higher timeframe analysis.


| Line      | Color | Description                                         |
| --------- | ----- | --------------------------------------------------- |
| Key Level | Gold  | User-defined price level with optional custom label |
| Key Zone  | Gray by default | User-defined price zone with optional label and color |


Key levels and zones are instrument-specific — a $180 level or $179-$184 zone sent for NVDA will only appear on NVDA's chart, not on any other instrument. Bookmap does not read key levels from a local config file and does not expose a key-level entry UI; the external client owns the source data and pushes the latest levels/zones over WebSocket.

### Replay & Multi-Day Data

Market levels are client-owned. In live or replay mode, the plugin draws the latest `camPivots`, `previousDay`, and `premarket` values sent over WebSocket for each symbol.

## Configuration

The following parameters are hardcoded constants in each plugin's main class:


| Parameter               | Default | Description                                                 |
| ----------------------- | ------- | ----------------------------------------------------------- |
| `WS_PORT`               | 8765    | WebSocket server port                                       |
| `WALL_THRESHOLD`        | 500,000 | Minimum shares at a price level to qualify as a wall        |
| `WALL_CONSUMED_RATIO`   | 0.10    | Wall is "consumed" when size drops to this ratio of peak    |
| `ORDERBOOK_PERCENTILE`  | 95      | Adaptive crowd filter for orderbook snapshots and size-change alerts |
| `ORDERBOOK_INTERVAL_MS` | 1000    | Order book snapshot broadcast interval                      |
| `WALL_CHANGE_THRESHOLD` | 5,000   | Absolute floor for size-change alerts; alerts use `max(WALL_CHANGE_THRESHOLD, ORDERBOOK_PERCENTILE threshold)` |
| `WALL_OUT_MINIMUM_SIZE` | 5,000   | Absolute floor for wall-out/orderbook snapshot candidates; 5,000-share levels are included |
| `WALL_OUT_PROTECTED_ABSOLUTE_LEVELS` | 2 | Per-side count of near-touch absolute-floor levels preserved even when the percentile threshold is higher |

The floating trade button window shows the live effective wall threshold as `max(5K, P95)` for the active symbol.


## Logging

All logs are written under `~/Bookmap/` (`C:\Users\{username}\Bookmap\` on Windows).

| Log | Directory | Files |
| --- | --------- | ----- |
| **Plugin logs** | `~/Bookmap/plugin_logs/` | `{datetime}.txt` — one per session |
| **Signal logs** | `~/Bookmap/bookmap-signals/` | `breakout.jsonl`, `click-debug.log` |

On Windows, the full paths are:
- `C:\Users\{username}\Bookmap\plugin_logs\`
- `C:\Users\{username}\Bookmap\bookmap-signals\`

Plugin log files are named by session start time, e.g. `2026-03-21_10-30-45.txt`. Each line includes a timestamp and level:

```
2026-03-21 10:30:45.123 [INFO] [MarketLevelManager] Drew 16 websocket market level(s) for NVDA
2026-03-21 10:30:45.456 [INFO] [KeyLevel] Updated 2 websocket key levels for AAPL and 12 cam pivot(s)
```

Signal logs (`breakout.jsonl`) are appended in JSONL format (one JSON object per line). The `click-debug.log` records key+left-click events with millisecond timestamps.

### Settings Panels

The plugin provides settings panels accessible via the addon's configuration in Bookmap:


| Panel                       | Purpose                                                                         |
| --------------------------- | ------------------------------------------------------------------------------- |
| **Indicators**              | Enable/disable auto-drawn indicators (Premarket High/Low, Camarilla Pivots)     |
| **Replay Export**           | Enable/disable JSONL replay export from the live Rong plugin                    |
