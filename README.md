# Bookmap Plugin

A Bookmap addon that draws chart indicators and liquidity-wall signals, and forwards manual trading actions and exit-plan updates via WebSocket.

This repository builds the bmtrader trading addon:

| Plugin   | JAR                | Description                   |
| -------- | ------------------ | ----------------------------- |
| **bmtrader** | `lingrong1988_bmtrader_1.28.jar` | Personal plugin (private use) |

## How It Works

1. Receives instrument-specific VWAP, market levels, key levels, and zones from ViteApp and draws them in Bookmap.
2. Labels large liquidity walls directly on the heatmap using compact growth paths like `5→7→10`.
3. Displays wall-change alerts and pattern badges inside Bookmap.
4. Forwards trade-button and chart-hotkey actions to ViteApp, and allows editing the active exit plan.
5. Uses Bookmap's data coordinates so lines and labels track through scroll and zoom.

## Features

- **Chart keyboard hotkeys** — the highlighted Bookmap tab is shown at the top of the bmtrader Logs window and is authoritative for hover hotkeys. When enabled, C/F cancel or flatten and W swaps that symbol without using its price, B/S place bid/offer wall-reversal stop entries at the hovered price only before 10:00 AM New York time, top-row digits adjust exits, and numpad digits market out partials at any time. **Market Out 1**, Digit1, KeyM, and Numpad1 select the first exit pair tied for the smallest share quantity; digits 2–0 remain positional.
- **Order wall signals** — displays active bid/offer size changes at their price on Bookmap's right edge
- **Auto-drawn indicators** — ViteApp VWAP, premarket high/low, and Camarilla Pivot levels drawn automatically
- **WebSocket key levels/zones** — instrument-specific price levels and zones pushed by an external app
- **WebSocket API** — manual trading actions, exit-plan updates, and incoming display configurations
- **Live exit-plan editor** — the floating trade window edits ViteApp's active `coreTarget`/`coreCount` plan and reminds after the third completed partial
- **Settings panel** — enable/disable indicators

## Project Structure

```
bookmap-plugin/
├── build.gradle
├── settings.gradle
└── src/main/java/com/bookmap/plugin/rong/
    ├── RongPlugin              # @Layer1StrategyName("bmtrader")
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
    └── PluginLog               # UI action logging
```

## Build

Requires a JDK 11+ installed. For JDK distributions without `jmods`, the build uses
the JDK's `jimage` tool to prepare a local runtime library for obfuscation.

```bash
mac: ./gradlew build
windows: gradlew build
```

Output JAR:

- `build/libs/lingrong1988_bmtrader_1.28.jar` contains the `bmtrader` trading addon

The release JAR obfuscates implementation class, method, and field names and removes
source filenames, line numbers, and local-variable metadata. README/Markdown files,
Java sources, Maven project metadata, and the obfuscation mapping are excluded.
Bookmap's entry point, annotations, and API callbacks are preserved.

`shadowJar` also finishes by generating the obfuscated release JAR. Unobfuscated
intermediate JARs stay under `build/intermediates/`; the private mapping stays at
`build/private/obfuscation/mapping.txt`. Share only the release JAR. Obfuscation makes
decompilation harder but does not prevent reverse engineering.

`build` runs the regular unit tests plus release checks against the actual obfuscated
JAR, without putting the original implementation classes on their classpath.

## Install in Bookmap

1. Open Bookmap
2. Go to **Settings** (gear icon) > **API Plugins Configuration**
3. Click **Add** and select `lingrong1988_bmtrader_1.28.jar`
4. In the popup, check the plugin name and click OK
5. Add the addon to a chart: right-click the chart > **Add Addon** > select the plugin

The `bmtrader` plugin starts a WebSocket server on `localhost:8765` when attached to an instrument.

## Connect Your Trading Bot

In your browser-based trading bot, connect to the WebSocket:

```javascript
const ws = new WebSocket('ws://localhost:8765');

ws.onmessage = (event) => {
  const data = JSON.parse(event.data);

  if (data.type === 'custom_button_click') {
    // Handle a trade-button or chart-hotkey action for data.symbol.
  } else if (data.type === 'core_plan_update') {
    // Validate and save the requested exit-plan update, then acknowledge it.
  }
};
```

## WebSocket API Reference

The plugin exposes a WebSocket server on `ws://localhost:8765`. Clients receive manual trading actions and exit-plan updates, and send display configurations to draw.

Every price-bearing message uses the canonical wire-price contract:

- `priceUnit` is `"real"`.
- Price fields contain real instrument prices (for example, `185.50` USD), never Bookmap price levels/ticks.
- The plugin is the only component that converts between wire prices and Bookmap levels, using the instrument's `pips` value.
- Missing `priceUnit` remains accepted for older clients; an explicit unsupported unit such as `"ticks"` is rejected.

### Message types (server → client)

| Type | Description | Frequency |
| ---- | ----------- | --------- |
| `custom_button_click` | Manual trade-button or chart-hotkey action | On user action |
| `core_plan_update` | Requested exit-plan target/count change | On user action |

Both message types include a `symbol` field identifying the instrument. Trade actions may include Bookmap session high/low, an estimated market-entry price, or a hovered chart price. The plugin does not send order-book snapshots or wall levels. ViteApp still accepts optional `orderbook` context from compatible clients; without it, initial profit targets use the standard 3R fallback.

### Send key levels and zones (client → server)

```json
{
  "type": "key_levels_config",
  "priceUnit": "real",
  "symbol": "AAPL",
  "waitForBidRetest": "yes",
  "waitForOfferRetest": "warning",
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

`waitForBidRetest` applies to long entries and `waitForOfferRetest` applies to short entries. Each accepts `"no"` (no wait), `"yes"` (speak the pending warning and block the entry), or `"warning"` (speak the warning but allow the entry). Both waiting modes use muted button colors until Bookmap observes a material decrease from an order whose prior size is above the live order-change threshold and attributes the decrease to executions rather than cancellation. On that transition, ViteApp speaks `bid retest done` or `offer retest done`, and the buttons return to their normal colors. Sending an empty `levels` array clears existing key level lines for that symbol. Sending an empty or missing `zones` array clears existing key zones for that symbol. Missing or empty market-level fields clear their corresponding websocket-supplied market lines for that symbol.

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

The plugin shows the message only in the always-on-top **bmtrader Logs** window. `symbol` is optional; `level` is shown beside the source when provided.

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

## Indicators (Auto-Drawn Levels)

The plugin draws market levels supplied by the external WebSocket client. Each indicator can be enabled or disabled in the **Indicators** settings panel.

Order wall size-change sounds and visual alerts are enabled by default. **Enable Order Change Alerts** is the global switch for the feature across all instruments; turning it off suppresses both labels and sounds, while **Play Order Change Alert Sound** remains a subordinate preference. At each price, an event qualifies after a 500 ms stability window when its aggregate depth crosses the live `min(10,000, max(5,000, fifth-largest current order))` threshold or its absolute size delta is greater than that threshold. A same-side pull and add within 500 ms whose quantities match within 10% are combined into one moved-order event instead of two alerts. Timeline-anchored labels show only non-trade-consumed changes in the active intraday range: bids above the low of day and offers below the high of day. Same-price events use `BID UP`, `BID PULL`, `OFFER DOWN`, or `OFFER PULL`; moved orders use `BID UP`, `BID DOWN`, `OFFER UP`, or `OFFER DOWN`. Bullish labels are green and bearish labels are red.

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
| `ORDERBOOK_PERCENTILE`  | 97      | Adaptive crowd filter for wall labels and patterns |
| `WALL_THRESHOLD_FLOOR`  | 5,000   | Configurable absolute floor for wall labels and patterns; wall candidates use `max(WALL_THRESHOLD_FLOOR, ORDERBOOK_PERCENTILE threshold)` |

Adjust `WALL_THRESHOLD_FLOOR` from the Rong add-on settings under `Wall threshold floor`. The floating trade button window shows the live effective wall threshold as `max(configured floor, P97)` for the active symbol, together with the sizes of the three largest bid/ask depth levels. Wall labels and patterns use that value. Order-change alerts separately use `min(10,000, max(5,000, fifth-largest current order))`. These calculations stay within Bookmap.

## Logging

Logging is UI-only. Trading actions and incoming `action_log` / `screen_log` messages
appear in the always-on-top **bmtrader Logs** window, which keeps the latest 20 messages
in memory. Diagnostic info/error logging code has been removed.

The plugin does not create or append session `.txt` logs, breakout/pattern `.jsonl`
logs, or stdout/stderr log mirrors. Existing log files from older versions remain
on disk. Bookmap's own application logging is controlled by Bookmap.

### Settings Panel

The plugin provides an Indicators settings panel accessible via the addon's configuration in Bookmap:

| Panel                       | Purpose                                                                         |
| --------------------------- | ------------------------------------------------------------------------------- |
| **Indicators**              | Enable/disable auto-drawn indicators (Premarket High/Low, Camarilla Pivots)     |
