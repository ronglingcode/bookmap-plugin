# Experimental direct Schwab execution

The Java `miniviteapp` engine mirrors ViteApp's handler, core target rules, and
Schwab order factory. All three experimental settings default to **false**.
ViteApp still supplies current OAuth tokens, account observations, quotes, and
plan metadata. Native mutations go straight to Schwab without ProxyServer.

## Setup

1. Generate a random pairing key of at least 32 characters. Set
   `BMTRADER_EXECUTION_PAIRING_KEY` in the environment used to launch Bookmap,
   then restart Bookmap. Keep this key private.
2. In the ViteApp browser console, set the same key:
   `localStorage.setItem('tradingscripts.bookmapExecutionPairingKey', '<your key>')`.
   This stores the pairing key; access tokens remain in memory.
3. Run ViteApp with a live Schwab equity profile and a successful token refresh.
   Connect it to Bookmap. Supported origins are the existing Firebase app origins
   and `http://localhost:5173` / `http://127.0.0.1:5173`.
4. Enable **Experimental: Direct Broker Cancel (Schwab)** in the addon settings.
   Enable **Experimental: Direct Exit Orders** to migrate exits too.
   Enable **Experimental: Direct Initial Wall-Reversal Entries** separately
   for the first entry workflow. Install matching ViteApp/plugin builds;
   entry support uses execution protocol version 2.

One authenticated browser connection owns execution. Full ViteApp and Lite both
publish account/quote provenance and use the mutation fence. Enabling the flag
requires a paired session before browser broker mutations can proceed.

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

The plugin requires positively identified live Bookmap data, fresh successful
broker reads and quotes (10 seconds), and a token with at least 30 seconds left.
Replay, embedded replay, delayed, or unidentified providers cannot execute.
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

## Verification

Run `npm run build` and `npm run test:direct-execution` in ViteApp, and
`./gradlew.bat build` in the plugin. Shared sanitized fixtures cover order
payload parity; fake local HTTP tests cover fencing, reconciliation, stale/live
checks, and ambiguous responses. Release tests exercise the obfuscated JAR.
Entry fixtures can be regenerated in ViteApp with
`node --experimental-strip-types scripts/generateDirectEntryFixtures.mjs`.
No live order was used to verify this implementation. Provider identification
and the broker's actual OCO replacement lifecycle still need observation in the
running application before relying on this experimental route.
