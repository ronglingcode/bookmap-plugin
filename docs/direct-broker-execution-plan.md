# Native broker execution plan

Status: cancel, exits, adjustments, Add Partial, Swap, and flat initial entries
with any risk-method label always execute natively. Entries with exposure/pending
orders and generic directional B/S are implemented behind the
default-off `experimentalDirectBrokerExecution` flag. Off forwards only these
extended workflows to ViteApp; on executes them natively. See
[setup and supported actions](direct-broker-execution.md).

## Objective and initial scope

Local execution checks follow this rule: block only realistic cases where a
broker-accepted order could differ from the intended trade. Conditions that only
predict broker rejection may warn, never block. Trust broker-provided fields and
our own payload calculations; do not accumulate checks for hypothetical invalid
inputs. Preserve trading rules and initial-entry exposure checks. Exit position
and protective-order preflight comparisons were removed by user request; exits
use supplied account inputs and send directly without broker GETs.

Add a small Java execution engine inside bmtrader that mirrors the relevant
ViteApp modules. For explicitly migrated Bookmap actions, submit broker requests
directly from Java. Start with Schwab equities, the primary broker in this workspace.

Maintain separate established and extended action groups. The experimental flag
only controls the extended group; tokens/inputs and established execution remain active.
Each action becomes eligible only after its
own migration, protocol negotiation, and validation.

ViteApp continues to own OAuth refresh, account streaming/synchronization,
configuration, and trading state during the initial rollout. It pushes those
inputs ahead of time; a migrated action does not wait for ViteApp to build or
approve its order at click time. Initially, native execution requires a connected,
ViteApp supplying current inputs. This is a personal MVP with one app and one
account; no session ownership, IDs, origin allowlists, account matching, input-age
cutoffs, execution fences, or waits for another action/account reconciliation.
Browser-independent operation
would require a later migration of account updates and state ownership.

## Original execution path (before this migration)

- Floating tradebook buttons broadcast `custom_button_click` from
  `tradebuttons/TradeButtonWindow.java`.
- Floating hotkey buttons use `tradebuttons/HotkeyButtonAction.java`; chart
  hotkeys broadcast from `pricelines/ChartHoverHotkeyHandler.java`.
- `ViteApp/src/bookmap/bookmapSocket.ts` handles these messages, invoking
  `controllers/keyboardHandler.ts` or the selected tradebook's `startEntry`.
- `controllers/handler.ts`, `controllers/orderFlow.ts`, and the tradebooks
  apply action-specific rules, sizing, and state transitions.
- `api/broker.ts` delegates Schwab requests to `api/schwab/api.ts` and
  `api/schwab/orderFactory.ts`.
- `ProxyServer/routes/schwab.js` forwards requests to Schwab. Its POST route
  returns a synthetic JSON order ID extracted from the broker Location header.
  The Java client must handle the actual broker response rather than assume
  this proxy envelope or an always-present JSON body.
- The plugin already receives `account_state`, `exit_order_pairs_config`,
  and `core_plan_config`. These are display inputs, not yet a complete,
  versioned execution snapshot. In particular, account-state `timestamp` is
  currently the send time, not proof of a recent successful broker observation.
- ViteApp refreshes access tokens through `api/broker.ts`; the Schwab refresh
  helper currently returns only the access-token string. Preserve expiry metadata
  from successful OAuth responses when adding synchronization.
- The plugin WebSocket server binds to `127.0.0.1`. This MVP assumes the user's
  single ViteApp connection supplies the token and execution inputs.

## Mirrored Java structure

Place the engine under `com.bookmap.plugin.rong.miniviteapp`:

```text
miniviteapp/
  MiniViteApp.java
  config/ExecutionConfig.java
  models/Models.java
  models/TradingState.java
  controllers/KeyboardHandler.java
  controllers/Handler.java
  controllers/OrderFlow.java
  controllers/EntryRulesChecker.java
  controllers/ExitRulesCheckerNew.java
  controllers/CoreTargetExitRules.java
  algorithms/RiskManager.java
  algorithms/TakeProfit.java
  tradebooks/BookmapWallReversal.java
  api/Broker.java
  api/schwab/Api.java
  api/schwab/OrderFactory.java
  utils/WebRequest.java
  utils/ExitPairSelection.java
  bookmap/ExecutionRouter.java
  bookmap/ExecutionSession.java
```

Create only the modules required by the current migration. Preserve folder names,
function names where practical, rule ordering, and observable decisions. Add source
file/function references and a source revision to each ported module; maintain a
TS-to-Java mapping as the port grows. Adapt browser globals to an explicit engine
context, promises to Java asynchronous operations, and timers to a scheduler.
Avoid empty copies of unrelated charting, UI, research, or AI modules.

Keep existing Bookmap display models as adapters to execution models. Do not
treat a rounded display price, display ordering, or formatted risk label as an
authoritative execution input. Match existing price rounding and share allocation
with fixtures before choosing Java numeric representations.

One engine per shared plugin server, with per-symbol state. Execute HTTP
work on a bounded executor using Java 11 HttpClient; never block Swing, Bookmap
market-data callbacks, or the WebSocket message thread. Shut down engine resources
and invalidate credentials when the shared service stops.

## Routing and synchronization contract

Introduce an additive, versioned protocol for:

1. Native executor status, reporting enabled while the plugin runs.
2. An access-token update: broker, account hash, token, expiry, and generation.
3. Execution configuration and immutable state snapshots:
   state revision, and action-specific inputs.
4. Action lifecycle events: action ID, action name, symbol, state revision,
   affected order IDs, and per-request result.
5. Reconciliation results.

Use the existing local WebSocket. Receive the latest ViteApp inputs without
an ownership handshake or identity checks. Do not broadcast credentials.

Send the short-lived access token and account hash on connection and
after successful refresh/account changes. Keep refresh tokens, client secrets, and
OAuth ownership in ViteApp. Tokens remain in Java memory and never enter settings,
logs, exceptions, generic message dumps, or test fixtures. Send credentials only
while connected to the native executor. Clear credentials on plugin shutdown.
Reconnects do not revoke execution or require review.

Route every button/hotkey through one native plugin router:

- Supported and ready: Java owns execution; send informational lifecycle events
  to ViteApp without repeating the broker mutation.
- Extended flag off: preserve the ViteApp `custom_button_click` route for extended
  operations. This never changes the established native group.
- Unsupported or missing inputs: reject visibly through `execution_blocked`.
- Clients must supply compatible native execution inputs. Native failures never
  fall back, and unresolved broker review blocks both routes for those actions.

Readiness includes compatible versions and supplied decision inputs, without
age cutoffs or in-progress/reconciliation waits. Token authorization is delegated to the broker.
ViteApp requires a live broker profile. The plugin does not verify Bookmap
provider identity, live/replay mode, historical loading, or the Bookmap clock.

Each click submits independently. Requests within an action execute sequentially.
ViteApp broker mutations require no native acknowledgement. Mirror non-broker
side effects through explicit events without invoking ViteApp broker methods again.

## Results, reconciliation, and rollback

Distinguish validation rejection, broker acceptance, broker rejection, and unknown
outcome. Acceptance is not a fill or a completed cancellation. Preserve raw HTTP
status and relevant headers, handle empty bodies, and report sanitized errors.
Confirm exact success semantics against the current broker contract when building
the adapter.

Action IDs identify lifecycle messages. Never automatically resubmit or forward an action
after a mutation may have reached Schwab. On a broker timeout or unknown outcome,
require broker review before further native execution. Track partial success
separately for multi-request actions. Accepted results do not create a
reconciliation wait.

ViteApp receives results, performs its account refresh, updates trading state and
existing UI/Firestore reporting, and publishes the latest snapshot. Other clicks
do not wait for those side effects.

Native failures and unknown native outcomes never fall back to ViteApp.
Do not replay a native action. Retain
the existing UI-only plugin logging policy; show native readiness, blocked reasons,
sanitized results, and timing without introducing credential-bearing session logs.

## Historical implementation sequence

The sequence below records the original staged migration. Stages 0–5 and the
remaining currently exposed Add Partial/Swap/exposure workflows are implemented.
The routing policy above supersedes the original per-stage enablement plan.

Each numbered step is a separate reviewable change in the affected repositories.
Coordinate protocol changes across ViteApp and bookmap-plugin; commits remain
separate per repository. Initial direct execution needs no ProxyServer changes.

### 0. Foundation

- Supported-action allowlist, shared engine, central native router.
- Native feature status and token updates over the existing WebSocket.
- Versioned broker/config/trading-state snapshots without age or conflict gating.
- Direct Schwab HTTP adapter tested against a fake local broker.
- Pure decision fixtures using the same normalized inputs for TS and Java.

Deliverable: native infrastructure can be inspected and tested with fixtures and
a fake broker without sending live orders.

### 1. Cancel / C: first migrated action

Mirror `KeyboardHandler.handleKeyPressed` -> `Handler.cancelKeyPressed` ->
`Broker.cancelAllEntryOrders` / `Broker.cancelBreakoutEntryOrders` ->
Schwab `Api.cancelOrders` / `Api.cancelOrderBase`.

Preserve the current selection exactly:

- If exit-pair count is below `TakeProfit.BatchCount * 0.4`, cancel all entry orders.
- Otherwise cancel only STOP entry orders.
- Preserve protective exit orders and clear the pending-order timer.

The snapshot must include the authoritative exit-pair count, batch count, entry
order IDs/types and state revision. Clear the pending-entry timer. Refresh after
each attempted cancellation and show
individual results. Cover floating Cancel and chart C through the same router.

Deliverable: enable only `cancel_pending_entries`; Java sends the selected DELETE
requests directly, and ViteApp applies the state/UI side effects without sending
the broker's cancellation response, without blocking repeated clicks.

### 2. Market Out 1 / M / Numpad1

Port selection of the first pair tied for smallest share quantity, single-partial
exit rules, core-target protection, split-partial handling, and relevant stop
discipline transitions. Preserve the current Schwab behavior of replacing an
existing exit leg with a closing market order and reconcile its OCO sibling.
Start with existing split pairs; unsplit configurations remain explicitly outside
the action capability until their split behavior is ported and verified.

Required extra inputs include tradebook state/identity, original partial numbering,
active plan, bid/ask values, and rule settings. Existing display snapshots alone
are insufficient. Add remaining Numpad selections one at a time after this slice.

### 3. One hovered stop/target adjustment

Choose one existing digit action, port its pair selection, rounding, limit/stop
rules, core-target checks, and post-adjustment state effects. Verify replacement
IDs and protective sibling behavior. Expand to other single adjustments afterward.

### 4. Flatten / F

Port flatten rules, the uncovered-shares branch, per-pair market replacement,
leftover closing quantity, and pending-state cleanup. Reconcile partial completion
and fills between requests; do not close the original quantity again after a
concurrent fill. This is more complex than one ordinary market order.

### 5. One wall-reversal stop entry

Start with the existing long wall-reversal hover action B. Port its tradebook,
global/strategy entry rules, attendance gating, session cutoff, retest behavior,
risk/buying-power calculations, entry/stop price calculation, profit targets,
multi-bracket allocation, and trading-state transitions. Validate broker-side
protective orders and exact payload parity. Then migrate short S separately.

### 6. Further execution families

Migrate one market-entry button, then additional entry methods/tradebooks, half
exits, batch adjustments, and reload/add-partial operations individually. Expand
broker support only after Schwab coverage is established. Revisit independent
account streaming/state ownership as a separate architecture decision.

## Gate for each action

- Compare TS and Java decisions, selected orders, price/quantity rounding, payloads,
  rejection reasons, and state transitions using captured sanitized fixtures.
- Test plugin shutdown, both extended-flag settings, unsupported actions, independent repeated clicks, reconnect,
  and inputs without age limits. Verify expired tokens reach the broker and
  refreshed tokens do not interrupt execution.
- Fake-broker tests cover actual HTTP requests, empty success bodies, rejection,
  ambiguous timeout and partial completion.
- Verify protective-order handling and both long/short behavior where supported.
- Run ViteApp type checking/build and focused protocol/decision tests; run plugin
  unit tests and its build/release checks against the obfuscated JAR.
- No test calls the live broker. Intentional live trials are a separate rollout
  step, limited to the single enabled action after review.
- Record decision/dispatch/response timing to measure the removed hops and remaining
  broker latency; do not assume a latency improvement without measurement.

## First implementation milestone

Complete foundation plus Cancel / C with an explicit cancel-only capability.
Review its fixtures, failure behavior,
and mirrored structure before beginning the next execution migration.
