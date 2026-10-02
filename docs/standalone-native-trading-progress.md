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
- Credentials use the visible `%USERPROFILE%\bmtrader\secrets.json` folder;
  `scripts/importSecrets.mjs` populates the local JSON from the user's
  `storeSecrets.js` without printing values or overwriting rotated tokens. The
  tracked template retains empty credential values and matching source fields.
  Do not copy or execute the existing secret provisioning script.
- Document anything impossible to port and skip it; no such core trade operation identified yet.
- Commit changes independently in each repository. Never test with live broker mutations.

## Trailing-stop removal — 2026-10-01

Mirrored the user's uncommitted ViteApp trailing-stop removal. Deleted the native
5/15/30-minute stop-price calculation and J/K/L stop-adjustment / Shift market-exit
branches. Removed those keys from the Bookmap chart hotkey sets. Direct controller
and executor requests reject them rather than falling through to another exit.
Q/P, pending-entry stop widening, manual stop/target adjustments and stop-discipline
reminders remain. The removed ViteApp five-minute trailing reminder had no native
counterpart. The existing uncommitted shared fixture change was preserved; removed
its obsolete Java dispatch case. Operation docs and the implementation plan reflect
the current supported workflows.

Verification: `gradlew.bat build` passes with native-only compilation, 203 regular
tests and nine obfuscated-release-JAR tests. The shared TS state-fixture check
passes all 85 remaining scenarios. Regression checks cover unconsumed chart keys,
both Shift variants, controller rejection and zero broker requests with an open
position. No live orders or Firestore writes were sent. ViteApp source was not
edited; its uncommitted trailing-stop removal remains owned by the user. The
Bookmap removal was committed after the user requested it.

## Phase checklist

| Phase | Status | Evidence / remaining work |
| --- | --- | --- |
| Scope cleanup | Complete | Replay and AI/chat removed from ViteApp before extraction; browser build passes. |
| 1. Contracts and parity baseline | Complete | Mirrored domain/ports and production-generated Massive, services, market, broker, stream and state/workflow/view fixtures. |
| 2. Mirrored module extraction | Complete | TS src/trading and Java miniviteapp libraries/core/runtime/ports/adapters. Browser compatibility adapters retained. |
| 3. Secrets, Firestore, OAuth | Complete | Local JSON/template, direct config/state/audit REST, coalesced refresh/rotation and native manual OAuth controls. |
| 4. Massive/history/market state | Complete | Direct REST/reference/trades + stream, history/live overlap, eligibility, local indicators and closed-minute VWAP history/projection. |
| 5. Account/streams/runtime | Complete | Direct account/orders/preferences, ledger, two reconnecting vendor streams, 3s read coalescing/429 delay, periodic config/account/OAuth jobs. |
| 6. State/workflow completion | Complete | Local accepted state, core edits, Q/P/Z/Space/manual inputs, pending-stop job, risk/volume/VWAP/discipline/core reminders and Firestore audits. |
| 7. Bookmap adapter | Complete | First/last attachment runtime ownership, existing display parsers in process, local controls and optional sound. |
| 8. Remove bridge/flag, final verification | Complete | Old flag/forwarding and browser bridge/context removed. External inputs ignored. Both builds and fake-service/parity/release suites pass; docs revised. |
| Manual live smoke | Not run | User supplies local JSON. Actual Bookmap attachment, live vendor order acceptance and real Firestore writes were not exercised. |

## Baseline and verification

- Baseline: ViteApp `7dfd5a3`; bookmap-plugin `a080dbf`.
- Final TS workflow/bridge-removal checkpoint: ViteApp `a883086`.
- Standalone startup checkpoint: ViteApp `90bb54b`; bookmap-plugin `6cbad1e`.
- This document is committed with the final Java adapter/workflow/setup slice.
- Cleanup commit: ViteApp `d4686b5`; plan/progress commit: bookmap-plugin `62e71f5`.
- First library/Massive checkpoint: ViteApp `61b9006`; bookmap-plugin `570bb7a`.
- Services checkpoint: ViteApp `a6e0bf6`; bookmap-plugin `bde33bd`.
- Market core: ViteApp `339c99e`; bookmap-plugin `8b48a65`.
- Market loader/browser adoption: ViteApp `b3e7341`; bookmap-plugin `a3b503f`.
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
- Market checkpoint: 39 TS/Java headless market scenarios, 23 Massive scenarios,
  and real browser worker batch regression passed. Browser build and existing
  direct/extended execution fixtures passed. Full Java build passed: 196 tests,
  including native-only compilation and actual obfuscated-JAR verification.
- Market loader/adoption checkpoint: 39 shared scenarios plus browser DB startup/live
  regression, loader overlap test and worker batching passed. Browser production
  build and direct/extended execution checks passed. Full Java build passed,
  including loader overlap test, native-only compile and released JAR checks.
- Account/stream checkpoint: 23 shared read/projection scenarios, 18 stream scenarios
  and TS/Java fake-socket lifecycle passed. Browser build, direct/extended execution,
  services and Massive checks passed. Full Java build and released JAR checks passed.
- Native Java target remains 11. Gradle builds use per-command
  `JAVA_HOME=C:\Users\lingr\trading\.tools\jdk-21.0.12.1+1`.
- Browser validation: `npm run build`, relevant Node test scripts in package.json.
- Java validation: `./gradlew.bat build`, including standalone native compile/release-JAR tests.
- No credentials copied, no live orders sent, no real state documents overwritten.

## Resume checkpoint — implementation complete

Resumed after usage reset on 2026-10-01. All implementation phases are complete.
The native runtime is wired to RongPlugin's first/last attachment; startup requires
only user-owned JSON and the vendor services. ViteApp and ProxyServer can be closed.
Every supported operation is native; the old extended-execution routing flag and
browser executionBridge/executionEntryContext/market publishers are gone. The
optional core-target protection remains a separate false-by-default trading policy.

Current final slice adds mirrored workflow decisions and local view projection,
Q/P/pending-stop requests, accepted PUT state, manual entry/stop/fixed-share
controls, notifications/audits and lifecycle. Runtime handles removed selections,
held/unselected exit state, clearing closed positions, day rollover and obsolete
history loads. Read coalescing respects a three-second minimum and Retry-After.
Native chart entries now share authoritative local retest readiness with buttons;
warning mode logs/notifies while yes blocks. Retest completion now logs/sounds
locally once, without a browser listener. Space clears price inputs and retains
fixed quantity. VWAP history seeds only completed minutes; live display updates
use the last closed point, and repeated unchanged points are ignored.

Verification completed:

- Browser production build and headless type-check pass.
- 99 shared account/state/config/workflow/view cases, 39 market cases plus four
  browser/loader regressions, 23 Massive, 22 Firestore/OAuth plus refresh concurrency,
  23 broker read/projection and 18 stream protocol plus socket lifecycle pass.
- Direct execution: five production fixture/metadata tests. Extended execution:
  nineteen production-handler fixtures plus accepted-add regression. Core exits:
  six tests. Fake runtime covers startup, token expiry/rotation, accepted broker
  entry, persistence/audit, manual inputs, read coalescing/429, removed/held symbols
  and teardown, without browser or proxy.
- Full Java Gradle build passes: 201 regular tests and nine actual obfuscated-JAR
  release tests; native-only engine compilation passes without Bookmap API.
- Browser price/VWAP, exit-pair selection and new-position regressions also pass.
- No live broker mutations, real Firestore writes or real secrets used by checks.
- Existing nonblocking build notices: Vite large bundle/missing runtime stylesheet;
  Java deprecated API/Gradle usage. Both builds succeed.

The current operation/setup source of truth is [direct-broker-execution.md](direct-broker-execution.md).
Secrets/setup: [config/README.md](../config/README.md). Release artifact:
`build/libs/lingrong1988_bmtrader_1.30.jar`.

TS TradingRuntime is a headless mirror; production browser code still uses its
chart/global bootstrap with extracted libraries/core. Other brokers/futures,
inactive historical strategies, extra browser indicators/DOM/speech and Firebase
sign-in were not ported. R/E are disabled; V has no constructed active tradebook;
U is unused. No active Schwab equity operation was found impossible to port.
Partial market/target workflows retain the split-protective-order requirement;
new native/browser entries already submit multiple brackets. Legacy single-order
restoration requires resetting captured targets with P before managing partials.

Next session: fill the user-owned local secrets JSON, attach the release addon with
ViteApp/ProxyServer closed, and inspect startup, loaded config/levels/account state,
stream reconnect and OAuth renewal. Inspect vendor permissions and actual UI
attachment before assessing live execution. Do not send real orders as an automated
smoke check. Future trading changes should update both matching core modules and
the relevant fixture generators, then build both repos.

The sections below preserve earlier checkpoints; their unfinished-work notes are
historical and superseded by this checkpoint.

## Account and stream checkpoint

- `libraries/broker/schwab/ReadApi` reads account positions/balance, validated
  streamer preferences and daily orders. Default is one daily order request;
  capped results subdivide by hour, ten minutes, then minute. Optional time-window
  mode preserves five-minute windows for the first 30 minutes after the open,
  with historical Eastern DST. A capped minute fails explicitly rather than
  silently returning incomplete account state. Boundary order IDs are deduplicated.
- Browser Schwab account reads now use this library and `AccountProjection`,
  through `adapters/browserAccount` for UI dates/enums. Existing observation
  ordering, mutation diagnostics and no-mutation-retry behavior are preserved.
- Projection includes EXECUTION/FILL activities on partial, canceled and replaced
  orders, rather than requiring final FILLED status. This corrects lost fills/P&L.
  Pending quantities subtract already-filled shares. Partially filled OCO pairs
  use the smaller remaining leg quantity while sibling updates propagate.
  These are explicit corrections, covered by shared scenarios.
- Matching `StreamingProtocol` modules build login/subscription messages and parse
  partial quotes (including zero sizes), account events and filtered trade prints.
  LOGIN requires numeric success code 0 before subscription; the existing browser
  worker/nonworker paths now check it. Vendor messages are not logged as errors.
- `runtime/ManagedSocket`/`MarketStreams` reconnect with delays bounded at 30s,
  rebuild Schwab credentials/preferences each time and cancel retries on close.
  Java uses JDK WebSocket transport under `adapters/JdkSockets`; consumers are
  called outside lifecycle monitors. No Bookmap API is referenced by these modules.
- Shared suites: 23 broker read/projection scenarios, 18 stream protocol scenarios;
  TS/Java fake-socket tests verify failed login, fresh reconnect token, no subscription
  after failed login, print filtering, zero sizes and teardown. Native plugin startup
  has not adopted these new streams yet. Browser worker still owns its existing
  sockets; it uses the new quote parser/login check but not the reconnect manager yet.
- Native account/trade/P&L grouping, periodic refresh and startup composition remain
  unfinished. No live broker read/write, socket login or Firestore mutation was used
  during this checkpoint's tests.

## Market checkpoint details

- `core/marketdata/MarketState` is mirrored in TS/Java and works without a chart:
  session date, 1am history filter, VWAP correction at/after 9am, sparse minute
  candles, price/volume/dollars, day/premarket levels and sticky liquidity scale.
- The specified handoff excludes the incomplete aggregate bucket from history,
  backfills individual prints from its start, and deduplicates buffered prints by
  sequence. Added paginated Massive `/v3/trades` client with exact nanosecond parsing.
  Loader orchestration is implemented and browser-adopted; streams/runtime are
  not active in Bookmap yet.
- Headless state ignores already-closed-bucket late prints, counts a same-minute
  late print for volume/range/open, and keeps the timestamp-latest close/current
  price. Its per-minute duplicate set is bounded. Browser now uses this policy.
  This deliberately changes the former default browser behavior, which permitted
  closed-bucket late prints unless `skipLateTimeAndSalesChartUpdates` was enabled.
  Accepted same-minute late prints still update volume/range/earliest open while
  keeping the latest timestamp's close. Native/TS tests cover both cases.
- Browser worker now forwards all prints in its 100ms batch instead of last price
  with summed size. Existing DOM/chart render throttling remains. Regression proves
  10x100 + 12x200 + 9x100 produces OHLC 10/12/9/9 and $4,300, not $3,600.
- Regular-session trade-condition filter preserves the existing exact 4pm-inclusive
  boundary and uses historical Eastern DST. Worker and nonworker browser paths share
  the extracted mapper; Java has the matching mapper.
- Eligibility uses shares/previous-days-average shares; display/quality RVOL uses
  dollars/previous-days-median dollars. Corrected the plan's earlier conflation.
  Existing 500k floor, 0.9M-or-4x volume policy, implied-cap rounding and last-three-
  days consolidation logic are extracted without changing thresholds.

Individual-trade backfill source: Massive [REST trades](https://massive.com/docs/rest/stocks/trades-quotes/trades),
checked 2026-10-01 (nanosecond SIP time, per-symbol sequence, pagination, 50,000 limit).

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
- OHLC/VWAP batch correction and history/live overlap are now verified (see above).

Source verification: Massive [custom bars](https://massive.com/docs/rest/stocks/aggregates/custom-bars)
and [trades](https://www.massive.com/docs/websocket/stocks/trades) docs checked
2026-10-01 for pagination, omitted empty results, timestamps, limit and stream shape.
