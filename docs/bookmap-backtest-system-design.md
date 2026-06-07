# Bookmap Backtest System Design

## Purpose

The goal is to backtest strategies that depend on Bookmap replay data, not just 1-minute candles. A strategy such as "buy after a 500,000 share offer wall clears and price holds above it for 3 seconds" requires ordered depth, trade, top-of-book, and replay-time events.

Bookmap `.bmf` files are proprietary binary recordings. The system therefore has two independent parts:

1. A Bookmap replay exporter plugin that runs inside Bookmap while a `.bmf` file is replayed and writes normalized event logs.
2. A backtest framework that consumes those normalized logs and runs strategy code deterministically outside Bookmap.

This keeps Bookmap responsible for decoding `.bmf` and keeps the backtester pure, repeatable, and testable.

## Part 1: Bookmap Replay Exporter Plugin

### Repo And Build Target

The exporter lives in the existing `bookmap-plugin` repo alongside the live `Rong` plugin. The current Gradle build produces a Bookmap addon JAR. The JAR can contain two annotated plugin classes:

- `Rong`: live trading helper, price lines, live signals, trade buttons.
- `RongBacktestExporter`: replay-only data exporter for historical backtests.

The exporter must not share runtime state with live trading behavior. It should use separate output directories and no live WebSocket behavior.

### Responsibilities

The exporter is responsible for:

- Listening to Bookmap replay/data callbacks.
- Converting Bookmap tick prices to real prices using `InstrumentInfo.pips`.
- Writing append-only JSONL event logs.
- Preserving Bookmap data/replay time from `TimeListener`.
- Grouping all exported symbols from one Bookmap session under a shared run id.
- Emitting enough metadata to replay the event stream later without Bookmap.

The exporter is not responsible for:

- Making trading decisions.
- Simulating orders.
- Computing strategy PnL.
- Reading `Backtest/src/data.ts`.
- Decoding `.bmf` outside Bookmap.

### Output Layout

Default output root:

```text
%USERPROFILE%/Bookmap/backtest-exports/
```

One Bookmap process/session creates one run directory:

```text
Bookmap/backtest-exports/
  20260606_084512/
    MU/
      metadata.json
      events.jsonl
    TSLA/
      metadata.json
      events.jsonl
```

If the same symbol is attached more than once in the same run, the exporter writes `events-2.jsonl`, `metadata-2.json`, and so on.

### Event Format

Each event is one JSON object per line. Common fields:

```json
{
  "schema_version": 1,
  "type": "trade",
  "run_id": "20260606_084512",
  "symbol": "MU",
  "ts_ns": 1768919400123456789,
  "ts_iso_utc": "2026-01-20T14:30:00.123456789Z",
  "wall_clock_ms": 1780764321000
}
```

`ts_ns` is Bookmap data/replay time when available. `wall_clock_ms` is only for diagnostics and should not drive backtests.

### Event Types

#### `session_start`

Written first in every event file. Includes instrument metadata:

```json
{
  "type": "session_start",
  "symbol": "MU",
  "alias": "MU",
  "full_name": "MU:NASDAQ:STOCKS@BMD",
  "exchange": "NASDAQ",
  "instrument_type": "STOCKS",
  "pips": 0.01,
  "multiplier": 1.0,
  "size_multiplier": 1.0,
  "is_full_depth": true
}
```

#### `depth`

Absolute depth update for one price level:

```json
{
  "type": "depth",
  "side": "ask",
  "price_tick": 12345,
  "price": 123.45,
  "size": 500000
}
```

Bookmap's simplified `onDepth` callback provides absolute level size. `size: 0` means the level is empty and must be removed from the consumer's book state.

#### `trade`

Trade print:

```json
{
  "type": "trade",
  "price_level": 12345.0,
  "price": 123.45,
  "size": 800,
  "is_otc": false,
  "is_bid_aggressor": false,
  "is_execution_start": true,
  "is_execution_end": true,
  "aggressor_order_id": "...",
  "passive_order_id": "..."
}
```

The exact meaning of `is_bid_aggressor` is preserved as supplied by Bookmap. Strategy code should treat it as Bookmap-native metadata and document any interpretation.

#### `bbo`

Best bid/offer update:

```json
{
  "type": "bbo",
  "bid_price_tick": 12344,
  "bid_price": 123.44,
  "bid_size": 1200,
  "ask_price_tick": 12345,
  "ask_price": 123.45,
  "ask_size": 3000
}
```

The backtester can derive BBO from depth, but explicit BBO makes fill simulation and debugging easier.

#### `snapshot_end`

Marks the end of Bookmap's initial snapshot:

```json
{ "type": "snapshot_end" }
```

Strategy code can choose whether to ignore pre-snapshot events, but for backtesting it is usually best to process them to build the initial book and avoid generating signals until after the snapshot is complete.

#### `realtime_start`

Bookmap historical mode has finished and live/realtime mode has started:

```json
{ "type": "realtime_start" }
```

For `.bmf` replay, this can help identify where replayed historical data ends.

#### `session_end`

Written when the plugin stops:

```json
{
  "type": "session_end",
  "depth_events": 123456,
  "trade_events": 9876,
  "bbo_events": 5432
}
```

### Configuration

Initial implementation uses Java system properties so the plugin has no settings UI dependency:

| Property | Default | Meaning |
| --- | --- | --- |
| `bookmap.export.dir` | `~/Bookmap/backtest-exports` | Output root |
| `bookmap.export.depthMinSize` | `0` | Minimum absolute depth size to write. `0` is lossless for price-level depth. |
| `bookmap.export.flushEvery` | `1000` | Flush after this many events. |

The default is intentionally lossless. If exported JSONL is too large, rerun with `bookmap.export.depthMinSize=1000` or a higher threshold, but strategy research should start with lossless data until the minimum sufficient data contract is proven.

### Performance And Reliability

- Writes are buffered.
- Files are append-only JSONL so a partial export can still be inspected.
- A metadata file duplicates the first `session_start` event for quick indexing.
- No network server is opened by the exporter.
- The live `Rong` plugin can optionally use the shared exporter, but its replay export toggle defaults off.

### Exporter Acceptance Criteria

The exporter is done when:

- Bookmap lists both the live `Rong` addon and the replay exporter addon.
- Attaching `RongBacktestExporter` to a replayed instrument creates a run directory.
- `metadata.json` and `events.jsonl` are written.
- `events.jsonl` contains `session_start`, `depth`, `trade`, `bbo`, and `session_end` events for an active replay.
- The project builds with Gradle.

## Part 2: Backtest Framework

Part 2 will live in the `Backtest` repo because it needs direct access to `src/data.ts`, tradebooks, historical candle context, and the existing report conventions.

### Responsibilities

The framework is responsible for:

- Reading trader-selected symbol-day configs from `src/data.ts`.
- Finding matching exported Bookmap event logs.
- Reconstructing book state from `depth` events.
- Running strategy code over ordered events.
- Simulating orders, stops, targets, and fills.
- Producing synthetic trades, executions, summary metrics, and audit artifacts.
- Exporting results in shapes compatible with existing Backtest analysis scripts.

### Proposed Directory Layout

```text
Backtest/
  src/bookmap-backtest/
    cli/
      runBookmapBacktest.ts
    data/
      exportIndex.ts
      eventReader.ts
      eventTypes.ts
    engine/
      orderBook.ts
      replayEngine.ts
      brokerSimulator.ts
      sessionClock.ts
      riskModel.ts
    strategies/
      offerWallBreakoutHoldLong.ts
      strategyTypes.ts
    reports/
      reportWriter.ts
      metrics.ts
```

Reports:

```text
Backtest/analysis/bookmap-backtests/
  20260606_084512_offer_wall_breakout_hold_long/
    config.json
    trades.csv
    trades.json
    executions.csv
    executions.json
    summary.md
    per_symbol_day/
      2026-01-20/MU/audit.jsonl
```

### Backtest Input Contract

Backtest run config:

```json
{
  "export_root": "C:/Users/lingr/Bookmap/backtest-exports",
  "run_id": "20260606_084512",
  "date_range": ["2026-01-20", "2026-01-20"],
  "strategy_id": "offer_wall_breakout_hold_long",
  "symbols": "from_data_ts",
  "execution": {
    "entry_order": "market",
    "market_fill": "next_trade_or_bbo_ask",
    "slippage_cents": 0,
    "commission_per_share": 0
  }
}
```

Symbol-day selection should be explicit:

```ts
{
  symbol: "MU",
  gap_and_go: { ... },
  bookmap_backtest: {
    allowed_sides: ["long"],
    strategies: ["offer_wall_breakout_hold_long"]
  }
}
```

If `bookmap_backtest` is missing, the CLI can either skip the symbol or infer defaults from setup type. The safer default is skip with a warning.

### Core Event Model

The event reader streams JSONL in timestamp order. For one symbol file this is naturally ordered. For multi-symbol portfolio backtests, the framework can merge iterators by `ts_ns`.

Core event union:

```ts
type BookmapEvent =
  | SessionStartEvent
  | DepthEvent
  | TradeEvent
  | BboEvent
  | SnapshotEndEvent
  | RealtimeStartEvent
  | SessionEndEvent
```

### Strategy Interface

```ts
interface BookmapStrategy<TState, TParams> {
  id: string
  createState(ctx: StrategyInitContext, params: TParams): TState
  onEvent(ctx: StrategyContext, state: TState, event: BookmapEvent): StrategyAction[]
}
```

Strategy actions:

```ts
type StrategyAction =
  | { type: "place_market_order"; side: "long" | "short"; reason: string }
  | { type: "place_stop"; price: number; reason: string }
  | { type: "place_target"; price: number; reason: string }
  | { type: "cancel_orders"; reason: string }
  | { type: "annotate"; key: string; value: unknown }
```

### Broker Simulator

The broker simulator should be deliberately conservative:

- Long market entry fill: next trade price if available, otherwise current ask.
- Long stop fill: first trade at or below stop, or bid below stop if no trade comes first.
- Long target fill: first trade at or above target, or ask above target if no trade comes first.
- Short rules are symmetrical.
- Same-symbol strategies start with one open position max unless configured otherwise.
- No lookahead. All stops/targets must be based only on state known at entry.

### Demo Strategy: Offer Wall Breakout Hold Long

Parameters:

```json
{
  "wall_min_size": 500000,
  "consumed_ratio": 0.10,
  "hold_ms": 3000,
  "entry_order": "market",
  "stop_model": "low_of_day",
  "target_r": 1.0,
  "regular_open_time": "09:30:00 America/New_York",
  "regular_close_time": "16:00:00 America/New_York"
}
```

State machine:

1. Ignore trading signals before regular-market open.
2. Maintain ask-side wall candidates by price level:
   - Create candidate once ask depth at a level reaches `wall_min_size`.
   - Track peak size.
   - Mark cleared once current size <= `peak_size * consumed_ratio`.
3. Once cleared, wait for price confirmation:
   - Confirmation starts when last trade price or best bid is above wall price.
   - Confirmation fails if price falls back to or below wall before `hold_ms`.
   - If price stays above wall for `hold_ms`, enter long.
4. Entry:
   - Submit market buy.
   - Stop = regular-session low of day known at entry.
   - Risk per share = entry - stop.
   - Target = entry + risk per share.
   - Skip if risk <= 0 or stop is missing.
5. Exit:
   - First stop or target touch wins.
   - End-of-day liquidation can be a configurable fallback.

Audit annotations:

- wall price
- wall peak size
- wall first seen time
- wall clear time
- hold start time
- entry trigger time
- entry fill source
- low of day at entry
- stop
- target
- exit reason

### Reporting

The framework should generate:

- Trades JSON/CSV similar to existing `data/processed/.../trade_data.json`.
- Executions JSON/CSV similar to existing `execution_data.json`.
- Summary markdown with total trades, win rate, average R, total PnL, and per-symbol/day table.
- Audit JSONL with every strategy state transition.

Metrics:

- total trades
- win rate
- total PnL
- average PnL
- average R
- profit factor
- MAE/MFE in dollars and R
- average hold time
- trigger count vs trade count
- skipped signals and skip reasons

### Part 2 Acceptance Criteria

The backtester is ready when:

- It can read one exported symbol event file.
- It can reconstruct the book and day high/low.
- It can run `offer_wall_breakout_hold_long`.
- It produces one deterministic trade report from one exported replay.
- Re-running the same input produces byte-stable trades and nearly byte-stable summaries.
- The audit log explains every trigger, skip, entry, and exit.

## Open Decisions

- Whether to keep lossless JSONL long term or add a compact binary/Parquet conversion step after export.
- Whether Bookmap replay attaches one symbol at a time or multiple instruments from a `.bmf` should be batch exported manually.
- How much slippage to assume for market orders in thin symbols.
- Whether strategy side permissions should be added explicitly to `src/data.ts` or inferred from setup categories.
- Whether to add a Bookmap settings panel for exporter options after the first manual replay test.
