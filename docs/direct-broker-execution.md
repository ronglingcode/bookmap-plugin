# Experimental direct Schwab execution

The Java `miniviteapp` engine mirrors ViteApp's handler, core target rules, and
Schwab order factory. All three experimental settings default to **false**.
ViteApp still supplies current OAuth tokens, account observations, quotes, and
plan metadata. Native mutations go straight to Schwab without ProxyServer.

## Setup

1. Run ViteApp with a live Schwab equity profile and a successful token refresh.
   Connect it to Bookmap. Supported origins are the existing Firebase app origins
   and `http://localhost:5173` / `http://127.0.0.1:5173`.
2. Enable **Experimental: Direct Broker Cancel (Schwab)** in the addon settings.
   Enable **Experimental: Direct Exit Orders** to migrate exits too.
   Enable **Experimental: Direct Initial Wall-Reversal Entries** separately
   for the first entry workflow. Install matching ViteApp/plugin builds;
   entry support uses execution protocol version 2.

One allowed browser connection owns execution. Full ViteApp and Lite both
publish account/quote provenance and use the mutation fence. ViteApp claims the
local session automatically when direct execution is enabled.

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
target adjustments require exits already split in ViteApp. Unsupported rules,
stale decision inputs, and changed positions or protective orders block the action.
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
must include a broker order ID. Execution stays blocked until a fresh account
read observes that ID and ViteApp acknowledges initializing the corresponding
trade plan and core-target state. An initialization failure requires review.

With the entry flag enabled, attempts on a symbol with existing exposure are
blocked. Same-direction adds, replacement of pending entries, opposite-position
reversals, reload A, and swap W are future migration stages. Generic B/S actions
without a wall-reversal chart tradebook still use ViteApp. Disable the entry
flag to use the existing ViteApp entry workflow.

## Session and reconciliation

The plugin requires fresh account inputs (10 seconds), and fresh quotes only
for entries, partial exits, and price adjustments. Cancel and flatten do not use
quotes. Missing or expired tokens warn without blocking; the broker determines
authorization. Each request uses the latest token for the same session/account.
Bookmap provider identity, live/replay mode, historical loading, and the Bookmap
clock are not checked for execution. The engine builds Schwab equity
STOP/LIMIT/MARKET orders and trusts broker-supplied fields and its own builders.

Before entries/exits, Java reads the broker position and, when needed, protective
orders. Cancel sends DELETE directly without an order preflight. Requests
execute sequentially on a worker thread. Browser mutations acquire a shared
execution fence. An accepted action remains blocked until a later account read
confirms the replaced/cancelled IDs disappeared. Lifecycle messages update the
ViteApp UI and trade state without repeating the broker mutation.

Unknown HTTP outcomes or an interrupted in-flight session require broker review.
There is no automatic retry or fallback after native dispatch. Review the actual
broker orders and positions, then use **Reset Native Execution After Broker
Review** in addon settings. Disabling a flag does not clear unresolved execution.
Tokens are cleared on revocation/disconnect and are never written to plugin
settings or action logs.

## Principle for local execution checks

Block only realistic cases where the broker could accept an order that does not
match the intended trade. Examples are duplicate exposure, closing too many
shares after a fill, overwriting an exit edited elsewhere, or violating an active
trading rule. A condition that only predicts a broker rejection can at most warn.
Do not add speculative validation of broker-provided fields or values generated
by our own code. The broker handles authorization and order payload validation.

## Checks that still block

These checks apply to the native route. Turning off an action's native flag
normally returns it to ViteApp; unresolved native work continues to block it.

| Scope | Blocking conditions |
| --- | --- |
| Session | No connected ViteApp owner; unsupported app origin or protocol; another browser tab owns execution; ViteApp is not using a live Schwab equity profile; plugin stopped or session revoked. |
| Concurrent work / review | Native work or a browser broker mutation is in progress; a previous native result has not reconciled; an uncertain response, interrupted operation, or entry-state initialization failure requires broker review. Browser fence acknowledgement timeout is 3 seconds. |
| Decision inputs | Account observation is older than 10 seconds or predates the last mutation; missing account state or changed account. Entry/partial/adjustment quotes must be current, because they affect price, order type, or trading rules. Cancel/flatten need no quote. |
| Execution plan | Duplicate order IDs or closing quantity exceeds the position; exit legs disagree on side/quantity. These can submit duplicate closes or leave unintended exposure. |
| Flatten | No position; custom flatten/exit rules not mirrored in Java; exit side disagrees with position; exit quantity exceeds position. Flatten bypasses core-target protection and does not require split partials. |
| Partial exits / adjustments | No active exit pairs or current price; unsupported exit rules; selected partial does not exist; partial exits or target adjustments need exits split in ViteApp; required exit legs unavailable. |
| Core-target protection | Earlier exits lack original partial identity or protected partials lack an active plan, original entry price, or valid core target; proposed exit has not reached the 90% buffered core target. Applies to partial exits and adjustments, not Flatten. |
| Entry eligibility | Existing position or pending orders; Bookmap retest blocks entry; disabled/unavailable supported tradebook; wrong side or entry method; unsupported price units; unavailable day levels or protective stop; entry outside tradebook boundary; quotes cross the stop. |
| Entry rules | Stale/missing entry context; outside regular market hours; attendance or watchlist restriction; daily loss limit reached; invalid/zero liquidity scale; missing rule inputs; opposing watch level too close; entry inside a no-trade zone. VWAP proximity and low volume reduce size rather than block by themselves. |
| Entry sizing | Missing risk/sizing inputs or protective brackets. Buying power determines half allocation; insufficient funds and fractional legs do not block dispatch. |
| Broker preflight | Position or protective order fills/quantity/price changed, or a required broker read fails. Protective siblings must still be active because flatten sizing depends on their coverage. Initial entries require a flat position and no pending symbol orders; a truncated order read cannot establish that. Cancel has no preflight. |
| During dispatch | Flags/session/account revoked; entry inputs expire during preflight; position direction or available shares change between requests. A broker rejection stops the remaining requests; network timeout or ambiguous acceptance requires review. Token refresh does not interrupt execution. No automatic resend occurs. |

The log reports the first failed condition. A fresh successful account read
clears normal reconciliation waits; uncertain outcomes require reviewing the
broker and using **Reset Native Execution After Broker Review**.

## Warnings and delegated checks

- Missing/expired access token: warn, then use the supplied token; no 30-second
  expiry margin. Schwab decides authorization.
- Estimated buying power still insufficient after half sizing: warn, then submit
  that half-sized protected entry. No second broker buying-power comparison.
- Broker rejection: record the HTTP status in the native result and action log.

There are no extra warnings/checks for hypothetical malformed symbols, numeric
order IDs, whole shares, batch bounds, broker instrument/side/shape changes, or
factory price/type validation. Trust the existing producer/calculation. Cancel
does not fetch an order to predict whether the broker will reject its deletion.

## Verification

Run `npm run build` and `npm run test:direct-execution` in ViteApp, and
`./gradlew.bat build` in the plugin. Shared sanitized fixtures cover order
payload parity; fake local HTTP tests cover fencing, reconciliation, stale-state
checks, and ambiguous responses. Release tests exercise the obfuscated JAR.
Entry fixtures can be regenerated in ViteApp with
`node --experimental-strip-types scripts/generateDirectEntryFixtures.mjs`.
No live order was used to verify this implementation. The broker's actual OCO
replacement lifecycle still needs observation in the running application.
