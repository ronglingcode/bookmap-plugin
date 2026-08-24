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
4. Premarket high/low lines are drawn and updated automatically during 4:00-9:30 AM ET
5. Key price levels received over WebSocket are drawn on the matching instrument
6. Large liquidity walls are labeled directly on the heatmap using compact growth paths like `5→7→10`
7. Wall labels retain the increasing size path seen at each level and start a new phase label after 2x growth
8. All lines and labels use Bookmap's data coordinates so they track through scroll and zoom

## Features

- **Chart keyboard hotkeys** — the highlighted Bookmap tab is shown at the top of the Rong Logs window and is authoritative for hover hotkeys. When enabled, C/F cancel or flatten and W swaps that symbol without using its price, B/S place bid/offer wall-reversal stop entries at the hovered price only before 10:00 AM New York time, top-row digits adjust exits, and numpad digits market out partials at any time. **Wall Out 1**, **Market Out 1**, Digit1, KeyM, and Numpad1 select the first exit pair tied for the smallest share quantity; digits 2–0 remain positional.
- **Order wall breakout detection** — monitors large ask-side walls and broadcasts signals when consumed
- **Auto-drawn indicators** — ViteApp VWAP, premarket high/low, and Camarilla Pivot levels drawn automatically
- **WebSocket key levels/zones** — instrument-specific price levels and zones pushed by an external app
- **WebSocket API** — real-time breakout and order book messages
- **Live exit-plan editor** — the floating trade window edits ViteApp's active `coreTarget`/`coreCount` plan and reminds after the third completed partial
- **Settings panels** — enable/disable indicators and optionally export replay data

## Project Structure

```
bookmap-plugin/
├── build.gradle
├── settings.gradle
└── src/main/java/com/bookmap/plugin/rong/
    ├── RongPlugin              # @Layer1StrategyName("Rong")
    ├── pricelines/             # Line model, storage, painting, and hover price mapping
    │   ├── ChartHoverHotkeyHandler # Hover-key detection & coordinate mapping
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

Every price-bearing message uses the canonical wire-price contract:

- `priceUnit` is `"real"`.
- Price fields contain real instrument prices (for example, `185.50` USD), never Bookmap price levels/ticks.
- The plugin is the only component that converts between wire prices and Bookmap levels, using the instrument's `pips` value.
- Missing `priceUnit` remains accepted for older clients; an explicit unsupported unit such as `"ticks"` is rejected.

### Message types (server → client)


| Type           | Description                                  | Frequency                             |
| -------------- | -------------------------------------------- | ------------------------------------- |
| `breakout`     | Wall breakout signal                         | On event                              |
| `orderbook`    | Order book snapshot (filtered by percentile) | At subscription interval (default 1s) |
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
  "priceUnit": "real",
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

### Mirror a ViteApp screen log (client → server)

```json
{
  "type": "screen_log",
  "symbol": "AAPL",
  "source": "ViteApp",
  "level": "Error",
  "message": "entry inside key level",
  "timestamp": 1785243960500
}
```

The plugin writes the message to its session log and shows it in the always-on-top **Rong Logs** window. `symbol` is optional; `level` is shown beside the source when provided.

### New position reminder (client → server)

ViteApp sends this event once when an observed account position changes from flat to non-zero. The matching Bookmap trade window opens an always-on-top **New Position Reminder**. Reminder items are presented separately so more actions can be added later; the current item asks the trader to review the trade's invalidation condition.

```json
{
  "type": "new_position",
  "priceUnit": "real",
  "symbol": "AAPL",
  "isLong": true,
  "netQuantity": 100,
  "averagePrice": 110.25,
  "eventId": "AAPL:long:1785243960000",
  "timestamp": 1785243960000
}
```

Increasing or reducing an already-open position does not trigger another reminder. If Bookmap is disconnected at entry time, ViteApp keeps the event pending while the position remains open and sends it after reconnection.

### Active core plan (client → server)

ViteApp publishes the authoritative active plan. `coreCount` is the number of final original partials that are restricted to the 90%-of-planned-profit buffered target or better. The first three partials are always unrestricted, so `coreCount` is from 0 to 7. For example, `coreCount: 5` leaves partials 1–5 unrestricted and protects partials 6–10.

```json
{
  "type": "core_plan_config",
  "priceUnit": "real",
  "symbol": "AAPL",
  "hasActiveTrade": true,
  "isLong": true,
  "entryPrice": 100,
  "coreTarget": 110,
  "coreCount": 5,
  "runnerCondition": "after 10 minutes",
  "runnerCount": 2,
  "corePlan": "Hold core through the first pullback\nTrail runners behind M5 structure",
  "bufferedTarget": 109,
  "partialsTaken": 3,
  "tradeId": "AAPL:long:1785243900000",
  "reminderRequested": true,
  "timestamp": 1785243960000
}
```

The **Update Plan** button opens the same modeless, always-on-top form used by the third-partial reminder. Only **Core target** and **Core count** are editable. Runner condition, runner count, and the multiline core plan are displayed read-only as a reminder. An update is sent back to ViteApp as:

```json
{
  "type": "core_plan_update",
  "priceUnit": "real",
  "symbol": "AAPL",
  "coreTarget": 112,
  "coreCount": 6,
  "requestId": "AAPL:123456789",
  "timestamp": 1785243960500
}
```

ViteApp validates and persists the active plan, then acknowledges it with another `core_plan_config` carrying the same `requestId` and `updateStatus` of `success` or `error`.

### Closed-minute VWAP update (client → server)

```json
{
  "type": "vwap_update",
  "priceUnit": "real",
  "symbol": "AAPL",
  "vwap": 206.0134,
  "effectiveTimeMs": 1785243960000,
  "sentAtMs": 1785243960500
}
```

ViteApp sends its authoritative VWAP after each 1-minute candle closes. `effectiveTimeMs` is the candle-close boundary. On connection, ViteApp replays its closed-minute VWAP history; afterward it sends one update per close. The plugin plots these values directly and uses the latest value for pattern scoring. It never calculates VWAP from Bookmap trades. Duplicate and stale updates are ignored.

### TypeScript example

```typescript
interface Breakout {
  type: "breakout";
  priceUnit: "real";
  symbol: string;
  breakoutLevel: number;
  timestamp: number;
}

interface OrderBook {
  type: "orderbook";
  priceUnit: "real";
  symbol: string;
  timestamp: number;
  percentile: number;
  minSize: number;
  bestBid?: number;
  bestAsk?: number;
  largeBids: [number, number][]; // [price, size][]
  largeAsks: [number, number][]; // [price, size][]
}

interface Subscribed {
  type: "subscribed";
  channel: string;
  intervalMs: number;
  percentile: number;
}

type BookmapMessage = Breakout | OrderBook | Subscribed;

function connectToBookmap(
  onBreakout: (signal: Breakout) => void,
  onOrderBook?: (book: OrderBook) => void
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

      case "subscribed":
        console.log(`Subscribed to ${data.channel} (every ${data.intervalMs}ms)`);
        break;
    }
  };

  ws.onclose = () => {
    console.log("Disconnected from Bookmap plugin");
    setTimeout(() => connectToBookmap(onBreakout, onOrderBook), 3000);
  };

  return ws;
}
```

## Indicators (Auto-Drawn Levels)

The plugin draws market levels supplied by the external WebSocket client. Each indicator can be enabled or disabled in the **Indicators** settings panel.

Order wall size-change sounds are enabled by default. The visual size-change alert overlays are disabled by default to keep the heatmap uncluttered, but they can still be enabled from the **Indicators** settings panel.

### VWAP

Draws a gold primary-chart VWAP line from ViteApp's authoritative closed-minute values.

- ViteApp replays available closed-minute history when the WebSocket connects.
- Each subsequent 1-minute candle close adds one new point.
- The latest received VWAP is also used for Bookmap pattern scoring.
- The line remains empty until a valid ViteApp update is received.
- Enabled by default; disable via the **Indicators** settings panel.

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

The following parameters are plugin defaults unless noted as configurable:


| Parameter               | Default | Description                                                 |
| ----------------------- | ------- | ----------------------------------------------------------- |
| `WS_PORT`               | 8765    | WebSocket server port                                       |
| `WALL_THRESHOLD`        | 500,000 | Minimum shares at a price level to qualify as a wall        |
| `WALL_CONSUMED_RATIO`   | 0.10    | Wall is "consumed" when size drops to this ratio of peak    |
| `ORDERBOOK_PERCENTILE`  | 97      | Adaptive crowd filter for wall labels, orderbook snapshots, and size-change alerts |
| `ORDERBOOK_INTERVAL_MS` | 1000    | Order book snapshot broadcast interval                      |
| `WALL_THRESHOLD_FLOOR`  | 5,000   | Configurable absolute floor for wall labels, size-change alerts, and wall-out/orderbook snapshot candidates; candidates use `max(WALL_THRESHOLD_FLOOR, ORDERBOOK_PERCENTILE threshold)` |
| `ORDERBOOK_PROTECTED_ABSOLUTE_LEVELS` | 2 | Per-side count of near-touch absolute-floor levels preserved in snapshots even when the percentile threshold is higher |

Adjust `WALL_THRESHOLD_FLOOR` from the Rong add-on settings under `Wall threshold floor`. The floating trade button window shows the live effective wall threshold as `max(configured floor, P97)` for the active symbol. Wall labels, alerts, patterns, **Wall Out 1**, and primary snapshot filtering all use that same live value. Snapshots deliberately retain the configured number of nearest absolute-floor levels as supplemental context.


## Logging

All logs are written under `~/Bookmap/` (`C:\Users\{username}\Bookmap\` on Windows).

| Log | Directory | Files |
| --- | --------- | ----- |
| **Plugin logs** | `~/Bookmap/plugin_logs/` | `{datetime}.txt` — one per session |
| **Signal logs** | `~/Bookmap/bookmap-signals/` | `breakout.jsonl` |

On Windows, the full paths are:
- `C:\Users\{username}\Bookmap\plugin_logs\`
- `C:\Users\{username}\Bookmap\bookmap-signals\`

Plugin log files are named by session start time, e.g. `2026-03-21_10-30-45.txt`. Each line includes a timestamp and level:

```
2026-03-21 10:30:45.123 [INFO] [MarketLevelManager] Drew 16 websocket market level(s) for NVDA
2026-03-21 10:30:45.456 [INFO] [KeyLevel] Updated 2 websocket key levels for AAPL and 12 cam pivot(s)
```

Signal logs (`breakout.jsonl`) are appended in JSONL format (one JSON object per line).

### Settings Panels

The plugin provides settings panels accessible via the addon's configuration in Bookmap:


| Panel                       | Purpose                                                                         |
| --------------------------- | ------------------------------------------------------------------------------- |
| **Indicators**              | Enable/disable auto-drawn indicators (Premarket High/Low, Camarilla Pivots)     |
| **Replay Export**           | Enable/disable JSONL replay export from the live Rong plugin                    |
