# Standalone native trading migration progress

Updated: 2026-10-01. Design: [standalone-native-trading-plan.md](standalone-native-trading-plan.md).

## User decisions

- ViteApp and Bookmap run one at a time; one Schwab stream consumer.
- Both keep matching trading behavior, with mirrored TS/Java core modules.
- Native code remains independent of Bookmap API; integration stays in adapters.
- Remove replay and AI/chat from ViteApp before porting. Replay was already removed.
- Preserve Bookmap's existing indicator subset; do not port additional browser indicators.
- Bookmap supports sound (`Layer1ApiSoundAlertMessage` already used for wall changes).
  Port notification messages/sounds; skip browser speech synthesis and DOM blinking.
- User supplies `%USERPROFILE%\.bmtrader\secrets.json`; publish an empty schema/template.
  Do not copy or execute the existing secret provisioning script.
- Document anything impossible to port and skip it; no such core trade operation identified yet.
- Commit changes independently in each repository. Never test with live broker mutations.

## Phase checklist

| Phase | Status | Evidence / remaining work |
| --- | --- | --- |
| Scope cleanup | Complete | Removed unused OpenAI secret accessor, agent-response client, AI notes; regenerated dead-code inventory. `npm run build` passed. |
| 1. Contracts and parity baseline | In progress | Added matching domain candles, HTTP ports, market clock and 22 Massive/market scenarios generated from production TS; account/state contracts remain. |
| 2. Mirrored module extraction | In progress | Extracted TS pure sizing/target/entry/core-target/exit-selection helpers and Schwab payload builders into `src/trading`; compatibility exports retained. Reorganized all Java decisions under `core` and existing broker code under `libraries/broker`. Other browser handlers still mix globals/UI. |
| 3. Secrets, Firestore, OAuth | Pending | Read-only Firestore config REST checks passed during planning; writes/auth lifecycle not implemented. |
| 4. Massive/history/market state | In progress | Matching direct REST clients, history composition, shares reference and headless premarket statistics implemented; ViteApp uses extracted client. Java runtime wiring, trade streams and candle/VWAP/level state remain. |
| 5. Account/streams/runtime | Pending | Java mutation client exists; ongoing account sync and vendor streams still depend on ViteApp. |
| 6. State/workflow completion | Pending | Four extended routes already implemented but flagged; state ownership/additional commands/jobs missing. |
| 7. Bookmap adapter | Pending | Runtime currently owned by WebSocket server; UI/config updates still come from ViteApp. |
| 8. Remove bridge/flag, final verification | Pending | Must verify browser and proxy closed, token expiry, restore/reconnect, and obfuscated JAR. |

## Baseline and verification

- Baseline: ViteApp `7dfd5a3`; bookmap-plugin `a080dbf`.
- Cleanup commit: ViteApp `d4686b5`; plan/progress commit: bookmap-plugin `62e71f5`.
- Browser extraction: production build, direct execution (6 tests), extended execution
  (19 handler fixtures plus accepted-add state test), core-target exits (6 tests),
  and Massive (22 scenarios) passed.
- Java extraction: full Gradle build passed, including native-only compilation,
  regular tests and actual obfuscated-JAR tests. Existing execution fixture tests
  remain unchanged except package imports; Massive parity checks all 22 scenarios.
- Native Java target remains 11. Gradle builds use per-command
  `JAVA_HOME=C:\Users\lingr\trading\.tools\jdk-21.0.12.1+1`.
- Browser validation: `npm run build`, relevant Node test scripts in package.json.
- Java validation: `./gradlew.bat build`, including standalone native compile/release-JAR tests.
- No credentials copied, no live orders sent, no real state documents overwritten.

## Resume checkpoint

The first library extraction and Massive REST slice are implemented and verified.
TS code: `ViteApp/src/trading`; Java: `miniviteapp/{core,libraries,models,ports,runtime}`.
Old TS paths are thin compatibility exports. The extended fixture loader includes
the new risk-sizing module so it still executes real production code.

Next: implement user-supplied local credentials schema/store, Firestore REST codec
and repositories, and broker OAuth refresh with rotation/persistence. Then account
projection, streams and local runtime market state. Do not remove the bridge/flag
yet: Bookmap still obtains tokens, account snapshots, execution context and views
from ViteApp. The Java Massive client is verified independently but not wired into
the plugin lifecycle. Keep implementing through phase 8; this checkpoint is not
completion of the standalone migration.

## Explicit behavior corrections in the Massive extraction

- Use session dates/history premarket grouping in `America/New_York`, including
  historical DST; domain timestamps stay epoch milliseconds, chart conversion
  remains in the browser adapter.
- Follow paginated history instead of silently stopping at the first page; today
  requests a 50,000 base-bar limit. Deduplicate/sort buckets across pages.
- Successful responses without `results` are empty intervals; HTTP failures and
  entitlement errors remain errors. Diagnostics do not embed credential URLs.
- Preserve weighted-share fallback and prior-day median dollar RVOL. Preserve
  existing daily lookback range. Remove undocumented `extendedHours` query flag.
- Replace a preexisting literal Massive streaming key with the configured key.
- No OHLC/VWAP trade-batch correction yet; that remains a separate verified change.

Source verification: Massive [custom bars](https://massive.com/docs/rest/stocks/aggregates/custom-bars)
and [trades](https://www.massive.com/docs/websocket/stocks/trades) docs checked
2026-10-01 for pagination, omitted empty results, timestamps, limit and stream shape.
