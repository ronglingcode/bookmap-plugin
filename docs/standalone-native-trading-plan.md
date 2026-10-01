# Standalone native trading and mirrored TypeScript/Java plan

Planning baseline: 2026-10-01. ViteApp `7dfd5a3`; bookmap-plugin `a080dbf`.

This is a design and migration plan, not an implemented rewrite. Both working
trees were clean at the start of this audit. The user confirmed that ViteApp and
Bookmap will run one at a time, so the running app owns the single Schwab stream.

## 1. Required outcome

Starting bmtrader inside Bookmap must be sufficient to load trading plans,
watchlist, historical data, live trades/quotes, account positions, orders, and
saved trading state; refresh its credentials; execute every supported trading
operation; and update its own buttons, indicators, logs, and exit-plan controls.
ViteApp and the localhost ProxyServer must not be required by Bookmap.

ViteApp remains a working browser app with the same trading features. Its browser
transport can still use the existing CORS proxy. Java uses direct HTTPS/WSS.
Bookmap-specific order-book analysis, wall/retest tracking, chart coordinates,
alias normalization, and rendering remain in the plugin adapter. The native
trading library accepts ordinary prices and order-book observations.

Scope is the active Schwab equity trading path and its current controls. Preserve
other ViteApp trading UI/tools and broker integrations without expanding this
migration to historical strategies, futures, or a new multi-broker implementation.
Remove AI/chat remnants before extraction; replay is already removed. Keep only
Bookmap's existing indicator subset. Calculations required by trading rules still
port even when their browser indicator is not rendered in Bookmap. Browser chart
layouts and exports remain browser-only.

## 2. What the source audit found

The current browser startup is `main.ts -> tosClient.initialize`: Firestore
config, token refresh, watchlist validation, streamer preferences, account sync,
and restoration of the daily trading state. Then it loads Massive history and
shares outstanding, starts the market-data worker, and installs scheduled jobs.

| Current responsibility | Active ViteApp source | Current Java position |
| --- | --- | --- |
| Startup/config/watchlist | `main.ts`, `tosClient.ts`, `models/models.ts`, `algorithms/watchlist.ts` | Supplied through WebSocket; no equivalent startup |
| Massive REST | `api/massive/api.ts`, `api/marketData.ts` | Missing |
| Massive live trades | `workers/marketDataWorker.ts`, `workers/tradeFlushBuffer.ts`, `streaming/timeSaleParse.ts` | Missing; receives a small price bundle |
| Candles/VWAP/levels/liquidity | `data/db.ts`, `indicators/basicIndicators.ts`, `indicators/camPivots.ts`, selected model helpers | Mostly supplied by ViteApp |
| Firestore config/state/logs | `firestore.ts`, `models/tradingPlans/tradingPlans.ts`, `models/tradingState.ts` | Missing |
| Schwab auth/account/stream | `api/schwab/api.ts`, `api/schwab/streaming.ts`, worker parsers | Mutations and initial-entry preflight exist; startup/auth/ongoing account sync do not |
| Order normalization/fills/P&L | `api/schwab/orderFactory.ts`, `api/broker.ts` | Receives already normalized observations |
| Decisions/sizing/targets | `controllers/*`, selected `algorithms/*`, current wall-reversal tradebook | Large tested subset already ported |
| Trade bookkeeping/discipline | `models/tradingState.ts`, `controllers/partialStopDisciplineController.ts` | Reads state supplied by ViteApp; accepted entry is initialized in ViteApp |
| Scheduled trade management | `algorithms/autoTrader.ts`, `controllers/orderFlow.ts` | Not fully ported |
| Trading notifications | `notifications/*`, active volume/risk warnings in `autoTrader.ts` | Some plugin logs exist; browser still supplies several alerts |
| Bookmap presentation/config | `bookmap/bookmapSocket.ts` | Plugin receives button/level/zone/account/core-plan configs |

Only the `BookmapWallReversal` family is constructed by the current
`tradebooksManager.createAllTradebooks`: gap give-and-go, gap-and-crap,
gap-down/go-up, gap-down/go-down, and range-bound bid/offer reversal. Do not port
every class or branch just because it remains imported or exposed in a namespace.

Use `ViteApp/docs/dead-code-audit.md` and `.json` as the starting exclusion list,
then verify each selected function's real caller, UI registration, or scheduler.
The audit is conservative around dynamically exposed functions and virtual
methods. A disabled feature is not automatically dead. In particular, retain the
core-target feature and its current default-off setting. Clock-in attendance has
already been removed; its legacy `attendanceAllowed: true` bridge field must not
become a new native rule. Replay and Lite are already removed.

## 3. Mirrored layout and dependency direction

Use `ViteApp/src/trading/` as the TypeScript library root and the existing
`com.bookmap.plugin.rong.miniviteapp` as the Java library root. Match paths beneath
those roots. Keeping the current Java namespace avoids another naming migration.

```text
trading/                              # Java: miniviteapp/
  models/                             # data-only domain types
    TradingConfig, TradingPlans, MarketState, AccountState,
    TradingState, TradingAction, ExecutionPlan, ExecutionResult
  libraries/
    massive/                          # REST, stream, wire normalization
      Api, Streaming, MarketDataMapper
    firestore/                        # document transport and field codec
      Api, DocumentCodec, ConfigRepository, StateRepository, LogRepository
    broker/
      Broker, schwab/Api, schwab/OAuth, schwab/Streaming,
      schwab/OrderFactory, schwab/AccountMapper
  core/
    marketdata/                       # historical/live calculations
      CandleAggregator, Vwap, MarketLevels, PremarketVolume, Liquidity
    algorithms/
      RiskManager, TakeProfit
    tradebooks/
      TradebooksManager, BookmapWallReversal
    controllers/
      EntryHandler, EntryRulesChecker, KeyboardHandler, Handler,
      OrderFlow, CoreTargetExitRules, PartialStopDiscipline, AutoTrader
    state/
      TradingState, AccountProjection
    notifications/
      NotificationEngine, FirstTouchToVwap
  runtime/
    TradingRuntime, RuntimeStore, ExecutionRunner, MarketClock
  ports/
    HttpTransport, StreamTransport, CredentialStore, NotificationSink,
    TradingViewSink
```

The names above denote modules/classes, not mandatory one-class-per-file rules.
Keep small related DTOs together and split the current large `Models` file by
responsibility. TypeScript filenames can use the project's camelCase convention;
Java class filenames use PascalCase. Methods and JSON field names match.

Dependency rules:

1. `models` has no UI or vendor dependency.
2. `core` consumes domain inputs, produces decisions, state changes, notifications,
   and execution plans. It does not call HTTP, Firestore, Swing, Bookmap, DOM,
   browser storage, or `window.HybridApp`.
3. `libraries` handle vendor transport and vendor payloads, not trading rules.
4. `runtime` composes the libraries and core, owns timers/streams/cache/persistence,
   and submits execution plans. Core does not import runtime.
5. Browser and Bookmap adapters translate UI/platform events into domain actions
   and render library outputs. They live outside the library roots.

Keep existing controller/algorithm names where practical so a feature change is
easy to find in both repos. Do not build a generalized plugin framework or add
unneeded brokers. `Broker` exposes the current Schwab operations.

Continue compiling the complete Java native namespace with `compileNativeExecution`
without Bookmap or plugin classes. Add an equivalent TypeScript boundary check
that rejects DOM/global-app dependencies from core. Java remains compatible with
the existing Java 11 target and obfuscated distribution; use explicit JSON codecs
rather than relying on reflective field names that obfuscation can change.

## 4. Mirroring behavior, not browser infrastructure

Each core action follows the same shape in both languages:

```text
TradingAction + TradingSnapshot -> ExecutionPlan or BlockedDecision
ExecutionPlan -> ExecutionRunner -> broker results
accepted entry / account observations / manual edit -> updated TradingState
updated domain state -> TradingView + notifications + persistence
```

Use matching method signatures conceptually, such as
`EntryHandler.handleEntry(snapshot, action)`,
`RiskManager.calculateTotalShares(...)`,
`TradingState.onEntryAccepted(state, entry)`, and
`PartialStopDiscipline.checkAndUpdatePhase(...)`.

`ExecutionPlan` contains ordered broker operations, the existing within-action
delays, accepted-entry metadata, and warnings. It must represent replacements as
well as new entries: POST and PUT acceptance can both update trade metadata.
The current Java Plan/Request classes are the starting point, not throwaway work.

Mirror state transitions explicitly rather than putting them in UI refresh
functions. Keep broker-observed account state separate from locally maintained
trade plans and from current market data. Each action captures its inputs without
waiting for another action or account reconciliation. Account refresh batching
and token-refresh sharing are data-service concerns, not execution locks.

Keep JS number/Java double calculations aligned initially. Centralize every
round/floor/ceil rule and price increment; do not independently introduce
BigDecimal arithmetic into one port. Preserve order IDs as strings, time as UTC
epoch milliseconds, and missing-value semantics explicitly. Convert chart-time
encodings and Bookmap pips only in adapters. Define market boundaries with
`America/New_York` in both languages; verify against the current Pacific-time
behavior and DST fixtures before changing time helpers.

## 5. Exact data replacements

### Massive library

Direct Java requests must cover these current inputs:

| Raw request | Current use and derived data |
| --- | --- |
| Today's adjusted 1-minute bars, ascending (`/v2/aggs/ticker/{symbol}/range/1/minute/{today}/{tomorrow}`; current limit 1000) | Intraday candles/volumes from the configured 01:00 ET start; premarket high/low; regular-session high/low/open/opening range; VWAP; current price; closed-candle rules and aggregated 5/15/30-minute bars |
| Adjusted daily bars, approximately three calendar years, excluding today (`range/1/day`; current limit 50000) | Previous daily candle, Camarilla levels, maximum high over the loaded history, and the range-bound plan's previous-three-day consolidation check |
| Adjusted 30-minute bars for the previous 20 calendar days through today (`range/30/minute`; current request includes `extendedHours=true`, limit 50000) | Per-day premarket dollar turnover and shares, prior averages/median, latest totals, and dollar-turnover RVOL used by eligibility/quality checks |
| Ticker reference (`/v3/reference/tickers/{symbol}`) | `weighted_shares_outstanding`, falling back to `share_class_shares_outstanding`; implied market cap from shares × current price |
| Live `T.{symbol}` trades on Massive stocks WSS | Price/size/time/conditions/sequence/trade ID; updates to candles, volume, cumulative VWAP, day extremes, liquidity, and tick/candle-close notifications |

The API client maps `t/o/h/l/c/v/vw`; core computes the rest. Preserve the valid
bar-VWAP-versus-HLC typical-price rule, the Firestore VWAP correction seed near
09:00 ET, outward cent rounding for highs/lows, current trade-condition filters,
and late-data behavior. Preserve the sticky liquidity multiplier once it reaches
1. RVOL uses previous dollar-turnover median despite a misleading comment about
average. The so-called all-time high is the maximum over the loaded three-year
history, not lifetime history.

ATR, quantity caps, configured market cap, gap previous-close reference, analysis
areas, and target plans remain Firestore inputs. Do not replace those with
Massive-derived substitutes during migration. The unused SPY previous-trading-day
helper is not a required data dependency.

Handle `next_url` pagination rather than assuming the requested limit guarantees
complete history. `extendedHours` is present in current code but is not a
documented Custom Bars query parameter; use documented date/session semantics
and fixtures to confirm equivalent coverage. See the official [Custom Bars
reference](https://massive.com/docs/rest/stocks/aggregates/custom-bars), [ticker
reference](https://massive.com/docs/rest/stocks/tickers/ticker-overview), and
[trade-stream reference](https://massive.com/docs/websocket/stocks/trades).

Subscribe once per runtime to the watchlist, not once per Bookmap chart. Seed
history without requiring a chart widget. Specify the history/live overlap
boundary so loaded volume is not counted again. On reconnect, backfill the gap
and resume domain events; never replay trading mutations as part of backfill.

### Firestore library

Implement Java with the Firestore REST API and the existing JDK HTTP/Gson stack.
Keep the Firebase Web SDK behind the matching TypeScript repository interfaces.
This avoids introducing an Admin SDK/service-account requirement into the addon.

The audit performed two read-only REST checks: `configData/tradingPlan` returned
HTTP 200 and a latest-snapshot query returned HTTP 200 with one document. Both
exposed the expected field names: `plans`, `stockSelections`, `activeProfileName`,
`tradingSettings`, `timestamp`, and `expiredAt`. No credentials were required for
these reads under the current deployed rules. Writes were not probed.

Repository operations:

1. Load the latest `configDataSnapshot` by timestamp. Use `orderBy timestamp DESC,
   limit 1` rather than downloading the entire collection as ViteApp does today;
   handle timestamp precision/ties deterministically in both implementations.
2. Decode complete plans, stock selections, active profile, and trading settings.
   Preserve current watchlist validation, support/resistance and entry-boundary
   rules, plan reasons/core-plan requirements, ATR configuration, and defaults.
3. Refresh plans on the current 60-second cadence. Make the update policy explicit:
   today's browser refresh replaces plans only, while startup also chooses the
   profile/watchlist/settings. Keep those session choices stable unless the user
   invokes a configuration reload; do not overwrite an active trade's captured
   plan with a new daily plan.
4. Read/write `state-{activeProfileName}/tradingState` using compatible field names.
   Restore the same-day plan and initial balance; start a fresh daily state when
   the date changes. Keep scheduler handles out of persistent state.
5. Preserve log/order repositories and their existing retention fields where used:
   `{profile}-Logs`, `{profile}-Orders`, and `BreakoutTradeState`.

Implement a single explicit codec for Firestore integer/double, boolean, string,
array, map, null, and timestamp values. Convert Firebase SDK Timestamp objects
and REST timestamp encodings into the same domain time representation. Preserve
compatibility with existing state documents; add migration fixtures before any
schema changes. Same-day app switching can then recover trade plans without
requiring the other application to run.

The provided Firebase block is app configuration, not a service-account key or
user login. Authorization still follows deployed rules (and any applicable App
Check enforcement); confirm state-write access during implementation without
loosening rules. If authentication is required for some operation, expose it as a
Firestore transport concern and use the matching Firebase-user auth flow. See
the official [REST authentication documentation](https://firebase.google.com/docs/firestore/use-rest-api).

### Broker library

Java must independently provide:

1. OAuth code exchange/refresh, token expiry metadata, and local token persistence.
2. Streamer preferences (`userPreference`) and awaited initialization before WSS
   login. The current browser method starts a promise chain without returning it;
   the new contract must actually await the result.
3. Account positions and liquidation balance, plus daily orders. Reuse the current
   normalizer for nested TRIGGER/OCO structures, status handling, working entries,
   exit pairs, executions, and replacement IDs.
4. Shared account cache plus fill grouping/P&L/last-exit/reload-direction projection.
   Those projections are core/account-domain logic; they are not broker HTTP code.
5. Schwab `LEVELONE_EQUITIES` quotes (including partial updates and sizes) and
   `ACCT_ACTIVITY`, with reconnect/login/subscription lifecycle.
6. Existing order placement, bracket/OCO construction, replacements, cancels,
   flattening, partial exits, and entry preflight behavior.

Preserve current account-risk inputs, including the liquidation-balance × 3.9
buying-power estimate minus watchlist exposure; do not silently switch to another
broker balance field. Preserve signed positions, execution order/grouping, and
the realized P&L definition used by daily loss rules.

Use one account refresh pipeline for the entire runtime. Coalesce account-event,
periodic, and post-mutation refresh requests, reuse results across symbols, and
respect HTTP 429/Retry-After without adding a wait before trading actions. Begin
with the existing 15-second baseline and event-triggered refreshes; measure the
final request rate. The current native bridge's extra 3-second input-publishing
poll disappears. Port the optional time-window order reader for actual capped
results/configured use, not as a high-frequency default scan. Verify Schwab's
current read quotas and response limits before setting final scheduling values.

### Credentials and refresh lifecycle

The supplied provisioning script contains the needed Massive, Firebase, and
Schwab blocks; schema inspection confirmed app key/secret, account identifiers,
and access/refresh token fields. It also contains unrelated providers' secrets.
No script execution or credential copying occurred during planning.

The user will supply `%USERPROFILE%\\.bmtrader\\secrets.json` (Java resolves it as
`user.home/.bmtrader/secrets.json`). Do not copy or execute the provisioning script.
Publish a blank template/schema and permit an explicit path override. The private
file stays outside the repo, classpath and JAR. Never include credentials in
distributables or sanitized fixtures.

The Java CredentialStore uses that private file; the browser implementation uses
localStorage. On startup, refresh the access token. Thereafter schedule from the
actual `expires_in` response with a small refresh lead time, not the current
hard-coded 1150-second timer. Share a refresh in flight within each app and apply
the fresh token to subsequent broker requests and streamer authentication.
Persist any new refresh token returned by the vendor.

Read-only requests can refresh and retry once after a clear authorization
failure. Do not automatically replay a broker mutation after a timeout, 5xx, or
other uncertain result. Preserve explicit broker-review/reset handling. Token
refresh failure is shown in the local UI; an expired/revoked refresh token has a
local browser-consent/code-import path that does not require ViteApp. Verify the
current Schwab token and stream reauthentication rules during implementation;
the public developer page did not expose readable endpoint documentation in this
audit, so fixed refresh-token lifetime and stream behavior are not assumed.

Since the apps run one at a time, no distributed owner/session coordination is
needed. If a returned refresh token supersedes the copied token, switching apps
must import the newest credential value; document that local handoff. This is
distinct from shared Firestore trade-state recovery.

## 6. Complete native trading/state scope

The existing four flag-controlled routes are already implemented in Java. Finish
their independent inputs/state, retain their tests, and make them unconditional:

- Entry with pending entries or exit orders while flat.
- Opposite-position market entry.
- Opposite-position breakout entry.
- Generic non-chart B/S selecting exactly one enabled directional tradebook.

Retain all established native behavior: cancel, flatten including uncovered
shares, partial market exits, single/batch price adjustments, initial and
same-direction wall entries, risk labels, reload, and same-direction swap.
Retest/order-book observations continue to come from Bookmap itself.

Add the remaining active browser trading commands where they are currently
available only in ViteApp, with explicit Bookmap buttons/hotkeys or local controls:

- Q: cancel breakout entries and clear the pending timer.
- P: replace protective exits from the saved profit-target plan, preserving its
  current cancel/submit ordering and delay.
- J/K/L (and Shift): 5/15/30-minute trailing-stop/conditional market-out workflows
  using closed candles and the current higher-high/lower-low rule.
- Z/custom-stop and custom-entry/fixed-quantity edits: update domain manual inputs
  and core invalidation levels, not browser chart objects.
- Clear manual chart overrides, and preserve risk-line controls when their
  existing feature setting is enabled. Do not resurrect the explicitly disabled
  E/breakeven action or V/VWAP-fail strategy that is not currently constructed.
- Core-target/count edits and reminders: process locally, persist state, and
  update the Bookmap window without its current "Saving in ViteApp" round trip.

Own these state/automatic workflows natively:

1. Accepted initial/reverse/replacement entry registration; original quantity,
   actual partial count, tradebook ID, entry/stop/risk, captured plan/ATR, core
   parameters, and submission time. Same-direction adds preserve the active plan.
2. Execution-based trade grouping, add stack, last exit quantity, daily realized
   loss and initial balance, original partial identities/order sorting, and
   pending-timer clearing.
3. Stop-tightening discipline: after position size falls below 90% of initial,
   assess tightened shares versus `max(0, current - initial * 0.5)`; re-check after
   reloads; preserve phase transitions and reminder behavior.
4. First-five-minute pending-entry protective-stop refresh at the current
   low/high of day, including the existing replacement-ID suppression so the
   periodic job does not repeatedly replace the old order while its new ID is
   propagating. This is local to that existing job, not a lock on manual actions.
5. Core-target protection/reminders when enabled, plus active volume, oversized
   risk, pending-stop-inside-day-extreme, and first-touch-to-VWAP notifications.
6. Local button enablement, strategy definitions/labels, market/key levels/zones,
   VWAP history/live points, positions, pending entries, exits, fills, risk text,
   and core-plan display projections, limited to Bookmap's existing indicators.
   These currently live partly in chart/UI
   code and `bookmapSocket.ts`; extract the data calculations into core/view models.

Logging, persistence, and rendering consume committed local state; order
acceptance does not wait for a Firestore write or UI acknowledgement. The current
Java accepted-entry result goes to a local state transition first. There is no
ViteApp `execution_entry_state` acknowledgement dependency in the final design.

## 7. Existing mismatches to resolve explicitly

These are migration decisions, not permission to silently change trading rules:

| Finding | Proposed treatment |
| --- | --- |
| TypeScript initializes some trade state immediately after starting an asynchronous order request; Java waits for broker acceptance and asks ViteApp to initialize | Make broker acceptance the common event for both implementations. Preserve partial success: an accepted entry still initializes even if a later cancellation fails. |
| When half sizing still exceeds estimated buying power, TS returns without submitting; native Java halves and warns, then submits for the broker to decide | Preserve the previously established native policy and explicitly align the extracted TS path, with a dedicated parity fixture and documented behavior change. Do not disguise this as a move-only refactor. |
| The worker merges a batch into last price × total size, losing intermediate highs/lows and true traded dollar value | Separate trade computation from render throttling. Recommend processing every accepted print in both cores while keeping 100 ms UI batching; record the resulting OHLC/VWAP/liquidity correction in its own change. |
| Today's history and live stream can overlap; computations currently depend on a chart being initialized | Introduce a specified history/live boundary and headless market state, with fixtures for the incomplete current bar and reconnect. Apply the same boundary in TS and Java. |
| Daily/session and chart timestamps mix local timezone and synthetic TradingView UTC | Use explicit domain epoch/session time and adapter conversions, checked against DST/session fixtures. Preserve decisions at the trading boundaries. |
| Runtime timers are mixed into persisted state; legacy fields/defaults exist | Keep runtime handles local and use backward-compatible persisted codecs with old-document fixtures. |
| Plan refresh replaces top-level plans but constructed tradebooks capture earlier plan objects | Specify common configuration reload behavior and keep active-trade plans captured. Any live strategy-definition update must be implemented and tested in both, rather than accidentally changing one. |

Established local checks remain conservative and small: preserve actual trade
rules and existing review handling, but do not add session ownership, account
matching, age cutoffs, execution fences, or new exit GET preflights. Unsupported
actions must report a local error, not silently forward to ViteApp.

## 8. Phased implementation with completion criteria

| Phase | Work | Completion evidence |
| --- | --- | --- |
| 1. Contracts and parity baseline | Record active actions/rules/defaults and source mapping; define domain DTOs, time/rounding conventions, ports, and sanitized fixtures; inventory known mismatches | Existing tests still pass; every current native action maps to a contract; fixture copies are identical |
| 2. Extract the TypeScript core and align Java layout | Move selected decisions/calculations/state transitions into `src/trading`; leave thin existing exports/adapters for current browser callers; reorganize existing native modules beneath the matching roots | Production browser build passes; pure core runs without `window`/DOM; Java native compile passes without Bookmap APIs; existing action payloads remain covered |
| 3. Native secrets, Firestore, and OAuth startup | Ignored credential import/store; direct config/state codec/repositories; refresh and code-import lifecycle; awaited streamer preferences | Fake HTTP expiry/refresh tests; direct read-only config works; saved-state migration fixtures pass; private files excluded from Git and release JAR |
| 4. Massive history and headless market state | Exact four REST inputs, pagination, current/daily/premarket calculations, clock/rounding; begin local indicator/view projection | TS/Java fixtures agree on VWAP, day/premarket levels, Camarilla, volume/RVOL, eligibility, liquidity, and targets without a UI |
| 5. Independent account and streaming runtime | Direct Schwab account/orders reads and projection, one Schwab stream, one Massive stream, event/poll refresh batching, reconnect/backfill | Recorded orders/fills normalize identically; stream fixtures and request-rate instrumentation pass; no ViteApp token/market/account messages needed |
| 6. Complete native state and workflows | Entry acceptance/state ownership, daily restore, partial identities/add stack/discipline, pending-entry refresh, remaining commands, core edits, notifications | Long/short, pending/opposite, replacement, partial fills, reload/swap, restart and same-day app-switch scenarios agree in both ports |
| 7. Replace Bookmap transport dependencies | Own runtime from shared plugin lifecycle; introduce a Bookmap adapter/action dispatcher and local config/view listeners; rewire existing drawing/window managers | Plugin initializes configs and UI with browser/proxy closed; core-plan save succeeds locally; adding/removing charts does not duplicate streams or stop the runtime prematurely |
| 8. Remove legacy routing and finish documentation | Remove experimental execution flag/fallback and incoming `execution_token/state/market_data` dependencies; retire bridge-only TS code; retain any genuinely useful optional integration as an adapter | All trading dispatch is local; standalone restart/token-expiry scenario passes; both production builds and release-JAR tests pass; operation/source/module documentation updated |

Phases are an implementation order, not separate architecture forks. Keep
intermediate changes reviewable and builds usable. Preserve the current bridge
temporarily only while native services are being completed; its removal is part
of completion, not deferred work. Move runtime ownership out of
`SignalWebSocketServer`: a listener socket's existence must not control native
trading. Use the existing shared plugin instance lifecycle to start one runtime
and stop it after the last addon instance closes.

Complete each repository's work and commit independently. Cross-repository
fixtures/contracts are versioned copies, not runtime file references. No new
server, JavaScript engine embedded in Bookmap, or third shared runtime is needed.

## 9. Parity and standalone verification

The same fixture inputs must run through actual extracted TS functions and Java
functions, comparing decisions, quantities, prices, order JSON, request ordering,
delays, state transitions, and notifications. Extend current direct/extended
execution fixture generators instead of writing parallel expected-output logic.

Necessary scenarios include:

- Every established and formerly flag-controlled command, long/short, generic
  direction selection, missing/multiple tradebooks, pending/orphan orders.
- Partial fills, cancellations, replacements, nested OCO orders, duplicate read
  records, last-exit/add-stack/P&L reconstruction, restored old state documents.
- First market minute, first five minutes, midday start, DST, premarket seed,
  empty intervals, incomplete bar, missing VWAP, condition filters, price/volume
  batching, history overlap, stream disconnect/reconnect.
- Watchlist and premarket/market-cap/consolidation gates, liquidity reductions,
  ATR caps, risk labels, buying-power branches, reload discipline, core protection.
- OAuth expiry and concurrent refresh requests, revoked refresh token, quote
  partial updates, stream login errors, capped order pages, 429 reads, and uncertain
  mutation responses. A refresh/reconnect must never resend an uncertain order.
- Fully local config reload/manual edit/core-plan save and local UI notifications.

Use recording/fake broker transports and sanitized recorded vendor responses.
Finish with browser production build and appropriate Node parity tests, Java
native compile/full Gradle build, and existing obfuscated-JAR verification.
Read-only live API checks can verify connectivity/entitlement and config access;
automatic tests must not place real orders or overwrite the real state document.

Final acceptance: with ViteApp and ProxyServer closed, Bookmap loads every required
input, maintains and restores state, refreshes its token across expiry, receives
trades/quotes/account events, builds/executes all supported commands via its own
runtime, and updates its controls/indicators. The test harness demonstrates this
with fake mutations; it must not claim live broker lifecycle validation from
fixture tests alone.

## 10. Port limitations and omissions

Bookmap supports sound alerts: `RongPlugin.playWallChangeSound` already sends a
`Layer1ApiSoundAlertMessage` with generated WAV audio. Route trading notification
sounds through the same Bookmap API in the adapter, keeping the core independent.
Browser speech synthesis has no matching existing Bookmap implementation; retain
the message and sound and skip spoken text. Browser DOM blinking, browser chart
layouts, and indicators absent from Bookmap are not port targets. Keep a record
here of any further capability that cannot be ported, its reason and replacement.
Replay and AI/chat tools are removed rather than ported. No unsupported core
trading operation has been identified at planning time.

## 11. Future feature workflow

For an entry/exit/risk change, edit the matching core module in each language,
add one common scenario fixture, and run both runners. Vendor authentication,
Firestore encoding, plugin rendering, and browser UI should stay untouched unless
the feature actually needs a new input or control. Maintain a short module map
and a contract/fixture version so a reviewer can identify the two implementations
and their evidence in one place.
