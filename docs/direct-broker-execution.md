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
order shapes, stale observations, and unmatched broker state block the action.
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
splits. Fixed quantity and ViteApp's buying-power half sizing are preserved;
fractional share legs from half sizing are rejected. A broker buying-power
check and a regular-session gate add conservative checks for this experiment.

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

The plugin requires Bookmap's realtime-start callback, non-delayed provider
features without an additional replay time source, and a provider clock within
10 seconds behind / 1 second ahead of the system clock. Built-in providers and
wrappers do not need an external live-addon class marker. Missing features,
historical loading, delayed feeds, and replay or stale clocks block execution.
It also requires fresh successful broker reads and quotes (10 seconds), and a
token with more than 30 seconds left.
Only Schwab equity STOP/LIMIT/MARKET orders with integral quantities are supported.

Before mutation, Java reads the broker position and affected orders. Requests
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

## What can block execution

These checks apply to the native route. Turning off an action's native flag
normally returns it to ViteApp; unresolved native work continues to block it.

| Scope | Blocking conditions |
| --- | --- |
| Session | No connected ViteApp owner; unsupported app origin or protocol; another browser tab owns execution; ViteApp is not using a live Schwab equity profile; plugin stopped or session revoked. |
| Concurrent work / review | Native work or a browser broker mutation is in progress; a previous native result has not reconciled; an uncertain response, interrupted operation, or entry-state initialization failure requires broker review. Browser fence acknowledgement timeout is 3 seconds. |
| Bookmap data | Historical loading is incomplete; provider/features cannot be read; provider is delayed or has an additional replay time source; provider clock is over 10 seconds behind or 1 second ahead. These checks run at click time and again before each broker mutation. |
| Freshness / credentials | Missing token or at most 30 seconds until expiry; session heartbeat, account observation, or quote is older than 10 seconds; account observation predates the last mutation; invalid or changed account. Quotes are required for every migrated action except cancel. |
| Wire inputs | Malformed symbol, position, batch count, order IDs, quantities, exit pairs, or prices; unsupported order types; mismatched exit legs; missing original partial numbers; duplicate IDs in an execution plan. Only integral equity shares are supported. |
| Flatten | No position; custom flatten/exit rules not mirrored in Java; exit side disagrees with position; exit quantity exceeds position. Flatten bypasses core-target protection and does not require split partials. |
| Partial exits / adjustments | No active exit pairs or current price; unsupported exit rules; selected partial does not exist; partial exits or target adjustments need exits split in ViteApp; missing adjustment price, bid/ask, or required exit legs. |
| Core-target protection | Earlier exits of protected partials lack an active plan, original entry price, or valid core target; proposed exit has not reached the 90% buffered core target. Applies to partial exits and adjustments, not Flatten. |
| Entry eligibility | Existing position or pending orders; Bookmap retest blocks entry; disabled/unavailable supported tradebook; wrong side or entry method; unsupported price units; unavailable day levels or protective stop; entry outside tradebook boundary; quotes cross the stop. |
| Entry rules | Stale/missing entry context; outside regular market hours; attendance or watchlist restriction; daily loss limit reached; invalid/zero liquidity scale; missing rule inputs; opposing watch level too close; entry inside a no-trade zone. VWAP proximity and low volume reduce size rather than block by themselves. |
| Entry sizing | Invalid risk/quantity/target inputs; unavailable or insufficient buying power; half sizing creates fractional share legs; missing protective brackets. |
| Broker preflight | Broker read fails; actual position differs from snapshot; an affected order is no longer working, has partially filled, or differs in instrument, side, type, quantity, or price. Entry also checks actual buying power, flat position, absence of pending symbol orders, and that the order read is not truncated. |
| During dispatch | Flags/session/token/live status change during preflight; entry inputs expire during preflight; position direction or available shares change between requests; broker rejects a request; network timeout or ambiguous acceptance. No automatic resend occurs. |

The log reports the first failed condition. A fresh successful account read
clears normal reconciliation waits; uncertain outcomes require reviewing the
broker and using **Reset Native Execution After Broker Review**.

## Verification

Run `npm run build` and `npm run test:direct-execution` in ViteApp, and
`./gradlew.bat build` in the plugin. Shared sanitized fixtures cover order
payload parity; fake local HTTP tests cover fencing, reconciliation, stale/live
checks, and ambiguous responses. Release tests exercise the obfuscated JAR.
Entry fixtures can be regenerated in ViteApp with
`node --experimental-strip-types scripts/generateDirectEntryFixtures.mjs`.
No live order was used to verify this implementation. The running feed's
realtime lifecycle/clock and the broker's actual OCO replacement lifecycle still
need observation in the running application.
