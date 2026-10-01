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
| 3. Secrets, Firestore, OAuth | In progress | Matching OAuth, credential ports, Firestore REST codecs/config/state/log operations complete. Java local-file store and blank template added. Browser uses libraries. Java startup, periodic refresh and local authorization controls remain part of runtime/adapter phases. |
| 4. Massive/history/market state | In progress | Matching direct REST clients, history composition, shares reference and headless premarket statistics implemented; ViteApp uses extracted client. Java runtime wiring, trade streams and candle/VWAP/level state remain. |
| 5. Account/streams/runtime | Pending | Java mutation client exists; ongoing account sync and vendor streams still depend on ViteApp. |
| 6. State/workflow completion | Pending | Four extended routes already implemented but flagged; state ownership/additional commands/jobs missing. |
| 7. Bookmap adapter | Pending | Runtime currently owned by WebSocket server; UI/config updates still come from ViteApp. |
| 8. Remove bridge/flag, final verification | Pending | Must verify browser and proxy closed, token expiry, restore/reconnect, and obfuscated JAR. |

## Baseline and verification

- Baseline: ViteApp `7dfd5a3`; bookmap-plugin `a080dbf`.
- Cleanup commit: ViteApp `d4686b5`; plan/progress commit: bookmap-plugin `62e71f5`.
- First library/Massive checkpoint: ViteApp `61b9006`; bookmap-plugin `570bb7a`.
- Browser extraction: production build, direct execution (6 tests), extended execution
  (19 handler fixtures plus accepted-add state test), core-target exits (6 tests),
  and Massive (22 scenarios) passed.
- Java extraction: full Gradle build passed, including native-only compilation,
  regular tests and actual obfuscated-JAR tests. Existing execution fixture tests
  remain unchanged except package imports; Massive parity checks all 22 scenarios.
- Services checkpoint: browser production build and direct/extended/Massive suites
  passed; 22 Firestore/OAuth/log scenarios plus refresh concurrency passed. Java
  full build passed with matching service scenarios, local credentials
  rotation/restart and concurrent expired-token calls. All existing release-JAR
  tests passed. No live Firestore write or OAuth request was used in tests.
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

Credentials/Firestore/OAuth libraries are now implemented; configuration details
are in `config/README.md`, empty schema in `config/secrets.template.json`.
Java `runtime/LocalCredentials` reads user JSON, persists rotations without losing
other sections, and supports `bmtrader.secrets` path override. Pure `MarketClock`
now belongs under `core/marketdata`, so core never imports runtime.

Next: implement headless candle/VWAP/session-level state, broker account/order
projection, direct account reads and vendor streams, then compose the local runtime.
Native OAuth needs periodic startup/runtime scheduling; clients alone do not start
background work. Do not remove the bridge/flag
yet: Bookmap still obtains tokens, account snapshots, execution context and views
from ViteApp. The Java Massive client is verified independently but not wired into
the plugin lifecycle. Keep implementing through phase 8; this checkpoint is not
completion of the standalone migration.

## Services behavior and compatibility

- Firestore config query fetches only latest timestamp-descending snapshot rather
  than downloading the whole collection. State keeps existing field names/maps;
  SDK timestamps decode to `{seconds,nanoseconds}` and round-trip precisely.
- Existing Firestore rules govern native REST requests. API keys are not admin
  credentials. Read-only access was checked in planning; real writes remain
  unverified to avoid overwriting live documents.
- Log/order/breakout payloads and seven-/three-day TTLs are mirrored. Log date uses
  current Eastern session date, and generated IDs prevent same-millisecond log
  overwrites. Browser UI logging remains in its adapter. Optional bulk log deletion
  still uses Firebase SDK; it is not a native trading dependency.
- OAuth uses returned expiry, coalesces refreshes, persists optional refresh-token
  rotation, and offers callback-code exchange. Browser localStorage and Java JSON
  file preserve extra credential fields. Java persistence flushes before replacement.
- Browser checks token expiry every 30 seconds, refreshing within 60 seconds of
  expiry. Broker error bodies/tokens are excluded from OAuth diagnostics. Manual
  reauthorization remains necessary when Schwab revokes the refresh token.
- Fixed browser startup's unreturned user-preference promise, so startup actually
  waits for stream credentials. No live mutation behavior changed in this checkpoint.

Firestore [REST access/authentication](https://firebase.google.com/docs/firestore/use-rest-api)
and [Value codec](https://firebase.google.com/docs/firestore/reference/rest/v1/Value)
checked 2026-10-01. OAuth endpoint/header/form follow existing working Schwab code;
no fixed vendor lifetime is assumed.

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
