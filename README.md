# Bookmap Plugin

bmtrader is a standalone Bookmap addon for Schwab equity trading. It reads Massive
market data and Firestore configuration/state directly, refreshes local Schwab
credentials, and executes trades without ViteApp or ProxyServer running.

## Setup

1. Build with a JDK 11+ using `gradlew.bat build` (Windows) or `./gradlew build`.
2. Copy [config/secrets.template.json](config/secrets.template.json) to
   `%USERPROFILE%\bmtrader\secrets.json` and fill in your local credentials.
   Or import your existing `storeSecrets.js`:
   `node scripts/importSecrets.mjs ..\secrets\storeSecrets.js`.
   See [credential setup](config/README.md).
3. In Bookmap, open API Plugins Configuration, add
   `build/libs/lingrong1988_bmtrader_1.31.jar`, and attach bmtrader to a chart.
4. The first attachment starts the trading runtime; the final detachment stops it.
   Check the bmtrader Logs window for startup errors. Settings offer
   **Restart Native Trading / Reload Secrets**, **Open Schwab Authorization**,
   and **Import Schwab Callback URL**.

Run either ViteApp or Bookmap trading at a time; each owns its own vendor streams.
If the local secrets file is missing when attaching the addon, it silently stays
inactive: no windows, indicators, connections or trading services start. After
creating the file, disable and re-enable the addon to activate it.
The native runtime supports the active `schwab` and `momentumSimple` equity
profiles and the existing single-stock watchlist policy.

## Features

- Native wall-reversal market/breakout entries, same-direction adds, pending-entry
  replacements, opposite-position entries, partial reload and Swap.
- Cancel, flatten, partial exits, stop/target adjustments and Q/P workflows.
  [Exact operation table](docs/direct-broker-execution.md).
- Existing indicator subset: VWAP, premarket/previous-day levels, Camarilla pivots,
  configured key levels/zones, liquidity-wall labels and retest signals.
- Local account/orders/fills and position-risk display, entry-input dialog,
  core-plan editor, trading notifications and optional sound.
- One **Export** menu in Logs copies summary/detailed ThinkScript bubbles or trade
  CSV from cached account fills to the clipboard. [Formats and usage](docs/execution-exports-and-alert-cleanup.md).
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

`NativeTradingAdapter` owns Bookmap views, logs and sound. `RongPlugin` owns the
first/last-attachment lifecycle. Existing rendering remains under `pricelines`,
`orderwall` and `tradebuttons`. `SignalWebSocketServer.acceptLocalMessage`
reuses display parsers in process. Its optional port 8765 is not a trading dependency;
incoming external messages cannot replace locally owned tokens, inputs or views.

This is a personal MVP with one running app and one account. Exits submit without
broker preflight reads. Initial flat entries retain the existing broker exposure
preflight. There are no session ownership checks, input-age cutoffs or execution
coordination fences. Ambiguous mutation outcomes require broker review and manual
reset; they are never automatically resent or forwarded to ViteApp.

## Local logging

Every action/runtime/notification message shown in **bmtrader Logs** is also
queued for local UTF-8 files in `%USERPROFILE%\bmtrader\logs` (no hidden directory).
Use **Open Logs Folder** in the window to browse them. The UI shows the latest
20 entries and the current persistence status.

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

Share only `build/libs/lingrong1988_bmtrader_1.31.jar`. Unobfuscated intermediates
and the private mapping remain under `build/intermediates` and
`build/private/obfuscation`. Obfuscation does not prevent reverse engineering.

## Documentation

- [Standalone setup, data sources and operations](docs/direct-broker-execution.md)
- [Detailed design and source audit](docs/standalone-native-trading-plan.md)
- [Progress, verification and resume checkpoint](docs/standalone-native-trading-progress.md)
