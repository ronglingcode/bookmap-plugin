# Experimental direct Schwab execution

The Java `miniviteapp` engine mirrors ViteApp's handler, core target rules, and
Schwab order factory. Both experimental settings default to **false**.
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
Entry actions continue through ViteApp in this stage.

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
No live order was used to verify this implementation. Provider identification
and the broker's actual OCO replacement lifecycle still need observation in the
running application before relying on this experimental route.
