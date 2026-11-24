# Bookmap Plugin

bmtrader is a standalone Bookmap addon for Schwab equity trading. It reads Massive
market data and Firestore configuration/state directly, refreshes local Schwab
credentials, and executes trades without ViteApp or ProxyServer running.

Pattern recognition is not started by the addon: the general pattern engine,
False Breakout, Reclaim Second Bid, Bid Breakdown, their painters and pattern
export subscriptions are inactive, regardless of old pattern configuration.
Standalone detector classes and their tests remain available for offline research.
Cairo's price/candle feed continues independently.

## Setup

1. Build with a JDK 11+ using `gradlew.bat build` (Windows) or `./gradlew build`.
2. Copy [config/secrets.template.json](config/secrets.template.json) to
   `%USERPROFILE%\bmtrader\secrets.json` and fill in your local credentials.
   Or import your existing `storeSecrets.js`:
   `node scripts/importSecrets.mjs ..\secrets\storeSecrets.js`.
   See [credential setup](config/README.md).
3. In Bookmap, open API Plugins Configuration, add
   `build/libs/lingrong1988_bmtrader_1.48.jar`, and attach bmtrader to a chart.
4. The first attachment starts the trading runtime; the final detachment stops it.
   Check the bmtrader Logs window for startup errors. Settings offer
   **Restart Native Trading / Reload Secrets**, **Open Schwab Authorization**,
   and **Import Schwab Callback URL**.

Run either ViteApp or Bookmap trading at a time; each owns its own vendor streams.
If the local secrets file is missing when attaching the addon, it silently stays
inactive: no windows, indicators, connections or trading services start. After
creating the file, disable and re-enable the addon to activate it.
The native runtime supports the active `schwab` and `momentumSimple` equity
profiles and up to four selected stocks trading at the same time. Each selected
stock requires its own valid trading plan; a watchlist with more than four stocks
is blocked at startup and during entry validation.

## Features

- Flat-position chart keys **1–5** enter at market using the corresponding
  fixed-share button and the hovered price as stop, in one action. Held-position
  number keys still adjust exits; adds continue to use Z.
- Every opening action requires a fresh human-selected stop: hover + Z, then
  enter within 30 seconds. One thin chart line; each selection is single-use.
  [Selected-stop flow](docs/selected-stop.md).
- Native wall-reversal market/breakout entries, same-direction adds, pending-entry
  replacements, opposite-position entries, partial reload and Swap.
- Cancel, flatten, partial exits, stop/target adjustments and Q/P workflows.
  [Exact operation table](docs/direct-broker-execution.md).
- Existing indicator subset: VWAP, premarket/previous-day levels, Camarilla pivots,
  configured key levels/zones, liquidity-wall labels and retest signals.
- BID PULL and OFFER PULL cancellation alerts are limited to the inclusive range
  from regular-session low of day minus 2% of the latest traded stock price to
  high of day plus 2%. The range is checked when the depth change starts;
  cancellations are suppressed until day levels and stock price are available. Additions, moves,
  and trade-consumption alerts keep their existing behavior.
- Local account/orders/fills and position-risk display, entry-input dialog,
  core-plan editor, trading notifications and optional sound.
- **Auto-close positions without stop loss** is enabled by default. Uncheck it
  in the plugin's **Indicators** settings to keep missing-stop warnings without
  automatic closing. The setting applies across symbols and is saved across restarts.
- One **Export** menu in Logs fetches complete session order history on demand
  and copies summary/detailed ThinkScript bubbles or trade CSV to the clipboard.
  Live account/order reads use one order request per refresh. [Formats and usage](docs/execution-exports-and-alert-cleanup.md).
- Automatic history/live overlap handling, account refresh, token renewal,
  reconnecting streams, Firestore trading-state persistence and local action logs.

The execution routing flag and ViteApp fallback are removed. All supported trade
operations run natively. The optional core-target rule is a separate trading policy,
configured with `tradingPolicy.coreTargetEnabled` in the local JSON (default false).

## Architecture

The Bookmap-independent Java engine is under
`src/main/java/com/bookmap/plugin/rong/miniviteapp/`:

| Folder | Purpose | TypeScript counterpart in ViteApp |
| --- | --- | --- |
| `libraries/massive` | REST history/reference data and trade mapping | `src/trading/libraries/massive` |
| `libraries/firestore` | Config/state REST codec and trading-state persistence | `src/trading/libraries/firestore` |
| `libraries/broker/schwab` | OAuth, account/orders, payloads and streams | `src/trading/libraries/broker/schwab` |
| `core` | Trading decisions, market/account state, rules and sizing | `src/trading/core` |
| `runtime` | Startup, timers, credentials, streams and lifecycle | `src/trading/runtime` |
| `ports`, `adapters` | Injected I/O contracts and JDK transports | `src/trading/ports`, `adapters` |

For changes in either mirrored engine, check the corresponding module in the
other repository. The TypeScript fixture generators in `ViteApp/scripts` write
matching REST and stream cases into both repositories; run the Java parity tests
after regenerating them.

`NativeTradingAdapter` owns Bookmap views, logs and sound. `RongPlugin` owns the
first/last-attachment lifecycle. Existing rendering remains under `pricelines`,
`orderwall` and `tradebuttons`. `SignalWebSocketServer.acceptLocalMessage`
reuses display parsers in process. Its optional port 8765 is not a trading dependency;
incoming external messages cannot replace locally owned tokens, inputs or views.

### Market-data processing

Each chart applies every depth, trade, quote, timestamp and history-boundary event
synchronously to its ordered internal state. There is no raw-event queue, capacity
cutoff or market-worker handoff. Books, wall episodes, executed liquidity, candles,
VWAP and session levels update before the callback returns. Detachment waits for
an in-progress event before dismantling its state. A processing exception is
reported as incomplete evidence rather than zero fills.

Display projections and market publications are batched at 200 ms intervals (at
most five updates per second per publication stream). Native state remains current;
trading commands do not wait for a display timer. Price lines, zones, wall labels,
fill markers and hover displays use coalesced background refreshes. Display snapshots
may replace obsolete pending snapshots; historical wall segments remain retained.

Cairo consumes `cairo_batch` version 1 envelopes containing ordered original events.
Every trade is retained, including its original timestamp, sequence and quantity;
JSON serialization is deferred until publication. Candles and latest-price snapshots
are queryable before publication. The former 512-event delivery queue and Cairo's
8,192-event staging cutoff are removed. Publication failures retain the batch for
retry; sequence handling makes duplicate deliveries distinguishable. New-connection
snapshots and explicit request replies are available immediately. Cairo Live 0.1.10
and the updated ViteApp socket receiver understand the envelope; reload these clients
with the rebuilt plugin. No deployment is performed by the build.

The liquidity entry requirement automatically turns off at 09:35 America/New_York,
five minutes after market open. It applies again before the next session's cutoff.
Other entry rules and exit/cancel/flatten controls remain unchanged.

The session tracker expires removed levels through an expiration queue instead of
scanning the entire book per callback, preserving the 250 ms late-fill grace,
separate refill episodes and rolling execution evidence. Session boundaries are
cached and duplicate maintenance at the same timestamp is skipped. Wall-label
maturity uses a due-time queue rather than scanning all pending walls per timestamp;
one-second maturity, refill episodes and historical labels remain unchanged.
Diagnostics retain processed-event counts and the slowest callback type/time.
No raw callback is sampled or replaced by a display snapshot.

Wall labels and wall-change badges redraw on dedicated background timers, at most
once per 200 ms per painter. Data/configuration/viewport callbacks only mark them
dirty. Label images are cached (up to 512 per chart), and the newest 2,000 inactive
wall segments are retained per chart along with all active levels. The order book
and entry liquidity evidence are not pruned by this display retention limit.

Price lines, zones and fill markers coalesce data/viewport redraw requests on
background workers at 200 ms intervals. Unchanged levels and executions retain
their existing canvas shapes. Wall labels also retain unchanged historical shapes
and cache the Bookmap-prepared image, avoiding repeated pixel conversion. Historical
wall labels remain enabled. Premarket dates/minute checks are cached, and duplicate
depth sizes skip redundant order-book histogram updates; trades are never sampled.

This is a personal MVP with one running app and one account. Exits submit without
broker preflight reads. Initial flat entries retain the existing broker exposure
preflight. There are no session ownership checks, input-age cutoffs or execution
coordination fences. Broker errors and ambiguous mutation outcomes are logged
without locking subsequent trading actions. Failed requests are never automatically
resent or forwarded to ViteApp; each later click is a new action.

## Local logging

All action/runtime/notification logs are queued for local UTF-8 files in
`%USERPROFILE%\bmtrader\logs` (no hidden directory), including diagnostics that
are omitted from the screen. Use **Open Logs Folder** to browse them.
The UI keeps the latest 20 important events: concise trading results, fill-count
summaries, startup/connection changes, warnings, errors and notifications.
One action gets one result summary even when it creates ten protective exit pairs
or sends ten broker requests. Entry summaries include the number of planned exit
pairs, without claiming they are active. Related fill-count updates arriving within
five seconds of each other update one screen row with the latest count. Every
underlying snapshot and leg diagnostic is still delivered to the file sink.
Broker order snapshots, individual fills, time samples, button/hotkey intent,
request planning, per-request HTTP results and timing go to files only.
External reports go to files; explicitly labeled warnings/errors also reach the UI.
Identical screen events are suppressed for 30 seconds; the next occurrence reports
the repeat count. Separate trading actions and connection transitions remain visible. Long screen messages
are shortened with a pointer to the full file entry. Filtering never removes file
entries. The persistence status remains visible below the feed.

Files use `bmtrader-YYYY-MM-DD.log`, followed by `-1.log`, `-2.log`, etc. when
rotating at 10 MiB. Dates use the computer's local timezone. Each line includes
the captured timestamp with milliseconds and timezone offset, session ID, symbol,
optional source and message. Later sessions append to the day's latest segment.
Retention keeps up to 30 calendar days and 100 MiB total, deleting only matching
bmtrader log files, oldest first.

One background writer serves all attached charts. It starts before native startup,
stays active through **Restart Native Trading**, and drains after final detachment.
Rapid detach/reattach waits for the previous writer on the background thread.
Files flush at least once per second while storage is responsive; orderly shutdown
flushes remaining entries. An abrupt crash may lose buffered entries; flush does
not guarantee survival of an OS crash or power loss.

The queue holds 4,096 entries. Queue overflow or storage failure reports a
rate-limited warning in the window while screen logging continues; storage retries
every five seconds. Unsaved entries are counted and are not replayed after a
storage failure. Logging never blocks trading on file I/O. Existing credential
redaction remains in place; both sinks additionally sanitize labeled tokens and
credential URL parameters. Raw broker responses and market streams are not logged.

Logs, notifications, order audits and breakout snapshots are **not sent to
Firestore**. Firestore still supplies configuration and saves trading state.

## Verification and release

`build` includes regular tests, `compileNativeExecution` without the Bookmap API
classpath, and tests against the actual obfuscated release JAR. Shared production
TypeScript fixtures check Java parity; fake services cover standalone startup,
renewal, requests, persistence and teardown. No live orders or real Firestore writes
are sent by these checks.

Share only `build/libs/lingrong1988_bmtrader_1.48.jar`. Unobfuscated intermediates
and the private mapping remain under `build/intermediates` and
`build/private/obfuscation`. Obfuscation does not prevent reverse engineering.

## Documentation

- [Manual premarket trading and numbered limit-order edits](docs/premarket-trading.md)
- [Bid breakdown entry/reclaim monitor](docs/bid-breakdown-monitor.md)
- [Manual entry bid to reclaim (I hotkey)](docs/entry-bid-to-reclaim.md)
- [Cairo aggregate patterns, capture and replay testing](docs/cairo-evidence.md)
- [Standalone setup, data sources and operations](docs/direct-broker-execution.md)
- [Detailed design and source audit](docs/standalone-native-trading-plan.md)
- [Progress, verification and resume checkpoint](docs/standalone-native-trading-progress.md)
