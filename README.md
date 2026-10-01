# Bookmap Plugin

bmtrader is a standalone Bookmap addon for Schwab equity trading. It reads Massive
market data and Firestore configuration/state directly, refreshes local Schwab
credentials, and executes trades without ViteApp or ProxyServer running.

## Setup

1. Build with a JDK 11+ using `gradlew.bat build` (Windows) or `./gradlew build`.
2. Copy [config/secrets.template.json](config/secrets.template.json) to
   `%USERPROFILE%\.bmtrader\secrets.json` and fill in your local credentials.
   See [credential setup](config/README.md).
3. In Bookmap, open API Plugins Configuration, add
   `build/libs/lingrong1988_bmtrader_1.30.jar`, and attach bmtrader to a chart.
4. The first attachment starts the trading runtime; the final detachment stops it.
   Check the bmtrader Logs window for startup errors. Settings offer
   **Restart Native Trading / Reload Secrets**, **Open Schwab Authorization**,
   and **Import Schwab Callback URL**.

Run either ViteApp or Bookmap trading at a time; each owns its own vendor streams.
The native runtime supports the active `schwab` and `momentumSimple` equity
profiles and the existing single-stock watchlist policy.

## Features

- Native wall-reversal market/breakout entries, same-direction adds, pending-entry
  replacements, opposite-position entries, partial reload and Swap.
- Cancel, flatten, partial exits, stop/target adjustments, Q/P workflows and
  5/15/30-minute trailing stops. [Exact operation table](docs/direct-broker-execution.md).
- Existing indicator subset: VWAP, premarket/previous-day levels, Camarilla pivots,
  configured key levels/zones, liquidity-wall labels and retest signals.
- Local account/orders/fills and position-risk display, entry-input dialog,
  core-plan editor, new-position reminders, trading notifications and optional sound.
- Automatic history/live overlap handling, account refresh, token renewal,
  reconnecting streams and Firestore state/audit persistence.

The execution routing flag and ViteApp fallback are removed. All supported trade
operations run natively. The optional core-target rule is a separate trading policy,
configured with `tradingPolicy.coreTargetEnabled` in the local JSON (default false).

## Architecture

The Bookmap-independent Java engine is under
`src/main/java/com/bookmap/plugin/rong/miniviteapp/`:

| Folder | Purpose | TypeScript counterpart in ViteApp |
| --- | --- | --- |
| `libraries/massive` | REST history/reference data and trade mapping | `src/trading/libraries/massive` |
| `libraries/firestore` | Config/state REST codec and audit persistence | `src/trading/libraries/firestore` |
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

## Verification and release

`build` includes regular tests, `compileNativeExecution` without the Bookmap API
classpath, and tests against the actual obfuscated release JAR. Shared production
TypeScript fixtures check Java parity; fake services cover standalone startup,
renewal, requests, persistence and teardown. No live orders or real Firestore writes
are sent by these checks.

Share only `build/libs/lingrong1988_bmtrader_1.30.jar`. Unobfuscated intermediates
and the private mapping remain under `build/intermediates` and
`build/private/obfuscation`. Obfuscation does not prevent reverse engineering.

## Documentation

- [Standalone setup, data sources and operations](docs/direct-broker-execution.md)
- [Detailed design and source audit](docs/standalone-native-trading-plan.md)
- [Progress, verification and resume checkpoint](docs/standalone-native-trading-progress.md)
