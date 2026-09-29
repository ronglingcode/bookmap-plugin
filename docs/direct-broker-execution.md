# Experimental direct Schwab execution

The Java `miniviteapp` engine mirrors ViteApp's handler, core target rules, and
Schwab order factory. One experimental setting defaults to **false**.
ViteApp still supplies current OAuth tokens, account observations, quotes, and
plan metadata. Native mutations go straight to Schwab without ProxyServer.

## Setup

1. Run ViteApp with a live Schwab equity profile and a successful token refresh.
   Connect it to Bookmap using the existing local WebSocket.
2. Enable **Experimental: Native Broker Execution (Schwab)** in the addon settings.
   This single switch enables cancel, exits, and supported initial entries together.
   The stored key remains `experimentalDirectBrokerExecution`.
   Install matching ViteApp/plugin builds;
   the simplified integration uses execution protocol version 3.

This is a personal MVP with one ViteApp, one Bookmap, and one account. ViteApp
pushes inputs when direct execution is enabled. There is no ownership handshake,
session ID, origin allowlist, or account-matching check. Full ViteApp and Lite both
publish the latest account and quote values without an execution fence.

## Migrated actions

| Action | Native behavior |
| --- | --- |
| C / cancel | Cancel pending entries using the existing exit-pair threshold; otherwise cancel only STOP entries. Clear the pending-entry timer. |
| M / Numpad1 | Market out the first smallest exit pair. |
| Other numpad keys | Market out the corresponding exit partial (0 means tenth). |
| Shift G / H | Market out the first half of exit pairs, rounding the pair count up. |
| Digit keys at chart price | Adjust the selected STOP or LIMIT leg. |
| G / H / T at chart price | Adjust half / all exit pairs. |
| F / flatten | Preserve ViteApp's uncovered-shares branch; otherwise market out the exit pairs and any remainder. |

Core target protection, equity price rounding, bid/ask stop clamping, pair
ordering, and closing direction follow ViteApp. Native partial market exits and
target adjustments require exits already split in ViteApp. Unsupported rules
block the action. Exits do not read the broker position or protective orders
before submission. Input age is not checked.
### First entry workflow

Native wall-reversal buttons (1 R / 0.1 R, market / breakout) and chart B/S
hover entries are available when the symbol is flat and has no pending entry
or exit orders. Hover entries preserve the STOP/LIMIT path even with Shift.
The supported tradebooks are the existing `BookmapWallReversal` family,
including range-bound bid/offer reversals and the gap reversal buttons.

Java applies the tradebook entry area, attendance and watchlist gates, daily
loss limit, liquidity scale, opposing watch/VWAP levels, and no-trade zones.
It computes equity risk sizing, reduced pair counts, ATR quantity caps,
one-cent slippage, Bookmap wall targets followed by 3R targets, and even share
splits. Fixed quantity and ViteApp's buying-power half allocation are preserved.
If the half-sized entry still exceeds estimated buying power, native execution
warns and sends it for the broker to decide. There is no additional balance
preflight or whole-share validation. Entries require the regular session so a
broker-accepted NORMAL order cannot silently become an entry in a later session.

One POST contains the opening order and every protective OCO pair. The plugin
checks the actual broker position and pending orders before posting. Acceptance
must include a broker order ID. ViteApp initializes the corresponding trade plan
and core-target state after acceptance. Other actions do not wait for this UI
initialization or an account refresh. An initialization failure requires review.

With native execution enabled, attempts on a symbol with existing exposure are
blocked. Same-direction adds, replacement of pending entries, opposite-position
reversals, reload A, and swap W are future migration stages. Generic B/S actions
without a wall-reversal chart tradebook still use ViteApp. Disable native execution
to use the existing ViteApp workflows for cancel, exits, and entries together.

## Updates and reconciliation

ViteApp sends `execution_market_data` synchronously after applying each accepted
time-and-sales update and each level-one quote update. One message bundles the
symbol, current price, bid, ask, high of day, and low of day in real price units.
Lite publishes the same bundle after applying a worker market snapshot, including
quote-only snapshots. The main app's upstream trade worker still batches incoming
prints every 100 ms; forwarding adds no timer, account read, or acknowledgement wait.

Java keeps this per-symbol market bundle separately from account/plan snapshots.
Each click overlays the latest price, bid/ask, and entry day range while building
its action. A subsequent account snapshot cannot replace the streaming values;
already-built actions keep their captured inputs. Account/plan updates still run
about every three seconds and after account events. Market data does not wait
for that polling cycle. Enabling native execution also sends the current bundle.

The plugin uses the latest supplied inputs with no age cutoff. It does not wait
for another action or broker reconciliation, and ViteApp mutations do not acquire
a native execution fence. Missing or expired tokens warn without blocking; the broker determines
authorization. The plugin uses the supplied account hash and latest token.
Bookmap provider identity, live/replay mode, historical loading, and the Bookmap
clock are not checked for execution. The engine builds Schwab equity
STOP/LIMIT/MARKET orders and trusts broker-supplied fields and its own builders.

Initial entries still read the broker position and pending symbol orders to avoid
adding exposure in the initial-entry workflow. Exits and cancel submit directly
without any position or protective-order GET preflight. Requests
within each action execute sequentially; separate clicks run independently.
There is no in-progress or post-action reconciliation gate. Lifecycle messages update the
ViteApp UI and trade state without repeating the broker mutation.

Unknown broker outcomes require broker review. A WebSocket reconnect by itself
does not require review and does not stop a running native action.
There is no automatic retry or fallback after native dispatch. Review the actual
broker orders and positions, then use **Reset Native Execution After Broker
Review** in addon settings. Disabling the switch does not clear an unknown broker outcome.
Tokens are cleared when disabling native execution or stopping the plugin, and are never written to plugin
settings or action logs.

## Principle for local execution checks

Block only realistic cases where the broker could accept an order that does not
match the intended trade. Retained examples are initial-entry exposure, a plan
closing more than its supplied position, or violating an active trading rule.
A condition that only predicts a broker rejection can at most warn.
Do not add speculative validation of broker-provided fields or values generated
by our own code. The broker handles authorization and order payload validation.

## Checks that still block

These checks apply to the native route. Turning off the native execution flag
normally returns it to ViteApp; unresolved native work continues to block it.

| Scope | Blocking conditions |
| --- | --- |
| Enabled workflow | Plugin stopped or native execution disabled; ViteApp publishes native inputs only with a live Schwab equity profile and matching protocol version. There are no session/account identity checks. |
| Broker review | An uncertain broker response or entry-state initialization failure requires review. Running actions and account refreshes do not block another click. |
| Decision inputs | Missing state needed to build the requested action. No input age or timestamp comparison blocks execution. |
| Execution plan | Duplicate order IDs or closing quantity exceeds the position; exit legs disagree on side/quantity. These can submit duplicate closes or leave unintended exposure. |
| Flatten | No position; custom flatten/exit rules not mirrored in Java; exit side disagrees with position; exit quantity exceeds position. Flatten bypasses core-target protection and does not require split partials. |
| Partial exits / adjustments | No active exit pairs or current price; unsupported exit rules; selected partial does not exist; partial exits or target adjustments need exits split in ViteApp; required exit legs unavailable. |
| Core-target protection | Earlier exits lack original partial identity or protected partials lack an active plan, original entry price, or valid core target; proposed exit has not reached the 90% buffered core target. Applies to partial exits and adjustments, not Flatten. |
| Entry eligibility | Existing position or pending orders; Bookmap retest blocks entry; disabled/unavailable supported tradebook; wrong side or entry method; unsupported price units; unavailable day levels or protective stop; entry outside tradebook boundary; quotes cross the stop. |
| Entry rules | Missing entry context; outside regular market hours; attendance or watchlist restriction; daily loss limit reached; invalid/zero liquidity scale; missing rule inputs; opposing watch level too close; entry inside a no-trade zone. VWAP proximity and low volume reduce size rather than block by themselves. |
| Entry sizing | Missing risk/sizing inputs or protective brackets. Buying power determines half allocation; insufficient funds and fractional legs do not block dispatch. |
| Initial entry preflight | Initial entries require a flat broker position and no pending symbol orders; a failed or truncated entry order read cannot establish that. Exits and cancel have no broker read preflight. |
| During dispatch | Native execution disabled or plugin stopped. A broker rejection stops the remaining requests; network timeout or ambiguous acceptance requires review. Token refresh/reconnect does not interrupt execution. No automatic resend occurs. |

The log reports the first failed condition. Uncertain outcomes require reviewing
the broker and using **Reset Native Execution After Broker Review**.

## How the remaining checks work

These are actual comparisons in the current implementation. Initial entry
broker reads and mutation are separate requests; those comparisons are not atomic.

| Check | Mechanism |
| --- | --- |
| Workflow enabled | Route supported actions through one experimental flag. Disabled normally uses ViteApp. Recheck this flag/plugin shutdown before native dispatch. ViteApp sends inputs only with a live Schwab equity profile; its message reader requires protocol 3. |
| Inputs available | Require a snapshot for the requested symbol. There is no account/quote/context age check. |
| Unknown result/review | HTTP 5xx, a network exception after mutation dispatch, or a successful entry POST missing its new order ID cannot prove the outcome. Failed entry-plan initialization also requires review. Accepted results do not create a reconciliation wait. |
| Duplicate IDs/closing size | Reject a repeated nonempty order ID within one plan. Sum outgoing closing quantities and compare against absolute snapshot position. Flatten also checks its remaining quantity never becomes negative. |
| Initial entry exposure | Require zero snapshot position and no active entry/exit pairs. Broker preflight requires zero long/short position and no pending orders for that symbol, including child orders. Terminal statuses are ignored; a list hitting 3000 orders cannot establish completeness. |
| Exit selection/shape | Require position, requested pair/index, current price where rules need it, matching closing side, and matching side/quantity across paired legs. Partial market exits and target adjustments require split pairs; price adjustments require both legs and an existing leg price. |
| Mirrored exit rules | ViteApp checks that relevant tradebook exit methods are the ported base/Bookmap methods. Java blocks unsupported custom methods rather than silently skip the strategy's rules. |
| Core-target rule | For an earlier exit with this rule enabled, use original partial number and core count to select protected partials. Require their active plan, positive entry/target on the profitable side, and proposed exit at least `entry + 0.9 * (target - entry)` for longs (at most for shorts). Missing original partial identity blocks this rule. Flatten skips core protection. |
| Entry tradebook/action | Look up a supported enabled definition with the requested side. Require B/S hover direction to agree, method to be 1 R/0.1 R/default, real price units, and no Bookmap retest-block flag. |
| Entry prices/boundary | Require available ordered day high/low, positive entry/stop, stop below a long entry or above a short entry, including after quote/estimate adjustment. Compare entry with the definition's boundary: inside its range when requested, otherwise above the long lower bound/below the short upper bound. Boundary inputs and range flag must be usable. |
| Regular session | Require ViteApp's supplied seconds since market open between 0 and 23,400. No Bookmap replay-clock or input-age check is involved. |
| Entry discipline | Require ViteApp's attendance permission and empty watchlist block reason. Compare realized P&L against negative daily loss limit; require liquidity multiplier in `(0, 1]`. These are supplied strategy inputs, not independent broker validations. |
| Entry market-area rules | With usable open/VWAP/ATR and first watch level, block entry on the opposing side within 15% ATR of that level; during the first 60 seconds also check the opening price. Block prices strictly inside a configured no-trade zone. Zone/volume inputs must be usable to evaluate these rules. VWAP proximity/low volume halve size rather than block. |
| Risk/protection inputs | Require positive risk distance, multiplier and risk dollars for risk sizing; buying-power input must be present for half sizing. Require at least one protective bracket. Trust generated quantities and payload fields; do not enforce whole shares. |

## Warnings and delegated checks

- Exits do not compare the broker position with the supplied position, recheck
  direction/available shares, or inspect protective-order status, fills, quantity,
  or price. Those GETs and their failed-read blockers are removed entirely;
  there is no warning-only GET replacement. Exits use the supplied account inputs.
- Missing/expired access token: warn, then use the supplied token; no 30-second
  expiry margin. Schwab decides authorization.
- Estimated buying power still insufficient after half sizing: warn, then submit
  that half-sized protected entry. No second broker buying-power comparison.
- Broker rejection: record the operation/order ID, HTTP status, and broker error
  response in the native result and action log.

## Error diagnostics

Native plan failures, rejected state updates, preflight reads, and order mutations
report their failed operation and actual exception type/message with nested causes
to Bookmap and ViteApp. Entry preflight errors distinguish the positions read from
the pending-orders read and state whether an order was sent. HTTP failures include
the response status and broker error body; malformed read responses retain their
parsing cause and HTTP status. Successful entry responses without an order ID
identify the missing/invalid Location header.

ViteApp also reports account refresh, trade-state initialization, and WebSocket
send failures with their causes. An initialization failure sends its diagnostic
back to Java, so the review message retains the original reason. Error objects
are formatted as text before UI/Firestore storage instead of serializing to `{}`.
Diagnostics redact known credentials and token/authorization fields and limit
response/error excerpts to 2000 characters. They do not log full successful account
responses, request headers, or inbound token payloads. Logs add no broker reads,
retries, or local execution gates.

There are no extra warnings/checks for hypothetical malformed symbols, numeric
order IDs, whole shares, batch bounds, broker instrument/side/shape changes, or
factory price/type validation. Trust the existing producer/calculation. Cancel
does not fetch an order to predict whether the broker will reject its deletion.

## Verification

Run `npm run build` and `npm run test:direct-execution` in ViteApp, and
`./gradlew.bat build` in the plugin. Shared sanitized fixtures cover order
payload parity; fake local HTTP tests cover independent clicks, inputs without
age limits, immediate market overlays, exits without GETs, initial-entry exposure,
and ambiguous responses. Release tests exercise the obfuscated JAR.
Entry fixtures can be regenerated in ViteApp with
`node --experimental-strip-types scripts/generateDirectEntryFixtures.mjs`.
No live order was used to verify this implementation. The broker's actual OCO
replacement lifecycle still needs observation in the running application.
