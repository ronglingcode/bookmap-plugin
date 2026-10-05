# Standalone native Schwab trading

Implemented 2026-10-01. The former extended-execution flag and ViteApp execution
bridge are removed. **Every supported operation below is always native. No
operation uses the old flag or falls back to ViteApp.** Bookmap needs neither
ViteApp nor ProxyServer running. Setup is in [config/README.md](../config/README.md).
The earlier flag-based rollout is historical; this document supersedes it.

## Operation coverage

| Operation | Native behavior |
| --- | --- |
| Wall-reversal entry buttons | Market or breakout entry for enabled gap-and-go, gap-and-crap, gap-down-and-go-up/down and range-bound bid/offer reversal definitions; numeric risk labels select sizing. |
| Chart B / S | Long/short breakout at the hovered real price using the matching wall-reversal definition. Shift keeps the breakout path. Existing before-10:00 New York chart-entry cutoff remains. |
| Generic non-chart B / S | Choose the enabled matching definition from locally read plans. |
| Same-direction add | Existing-risk sizing; retain captured active/core state and allow protective exits. |
| Entry with pending orders | Submit the protected entry and cancel old same-direction entries; acceptance remains captured if a later cancellation fails. |
| Opposite-position market entry | Market replacement of old exit pairs before submitting the new protected entry. |
| Opposite-position breakout | Adjust old protective stops to the new entry price, then submit the new protected entry. |
| A / Add Partial / Shift+A | Protected partial reload at hover/custom price or market with Shift; retain browser low-risk override and hard entry boundary. |
| W / Swap | Close/reenter in the original direction. A pending same-direction entry instead closes all but the last exit pair. Preserves 500ms close/reentry or 750ms cancel/close workflow delays. |
| C / Cancel Entry | Refresh broker orders, cancel STOP breakout entries only and clear pending timer. |
| F / Flatten | Preserve uncovered-share branch, otherwise market out exit pairs and any remainder. Core-target protection does not prevent Flatten. |
| M / Numpad1 / Market Out 1 | Market out the first pair tied for smallest share quantity. |
| Other numpad digits | Market out the indexed partial; 0 means tenth. |
| Top-row digits | Move the selected stop/limit to hover price; Digit1 uses the first smallest pair, remaining digits are positional. |
| G / H / T | Move half / half / all exit pairs at hover price; Shift+G/H markets out the first half, rounding pair count up. |
| P / Reset Targets | Cancel current exit legs and rebuild captured profit targets with the captured stop. Reverse target order as in the browser, cap to actual remaining position and reject insufficient captured coverage. |
| Z | Set long/short custom stops to hover price and update the captured invalidation reference. |
| Space | Clear manual entry and stop prices; preserve fixed quantity, matching browser price-line clearing. |
| Entry Inputs dialog | Set custom entry, long/short stop and fixed share quantity locally; zero restores automatic selection. |
| Update Plan / third-partial reminder | Edit and persist active core target/count locally, with success/error feedback. |
| Pending-entry stop job | During the first five minutes, widen a single unfilled entry's protective stop to a new day extreme and recalculate the protected entry via PUT. Suppress another replacement of the old order ID while it remains in the account snapshot. |

Local risk policy mirrors ViteApp entry-area boundaries, watchlist/startup
eligibility, liquidity, daily loss and no-trade-zone rules. Native sizing includes
ATR caps, one-cent slippage, fixed quantity, risk-method partial counts and even
splits. If estimated buying power is insufficient, both apps halve targets once;
if the half allocation remains insufficient they warn and submit for the broker
to decide. This intentionally aligns the browser with the established native
policy. There is no second balance preflight.

Core protection is a separate optional trading rule, controlled by
`tradingPolicy.coreTargetEnabled` (false by default, matching the browser feature
setting). Protected original partials cannot exit early or be tightened before the
90%-of-planned-profit buffered target. Core target/count edits preserve captured
entry state; the first three partials remain unrestricted. This setting never
changes which executor handles an operation.

## Direct data dependencies

| Source | Data read or written | Uses |
| --- | --- | --- |
| Massive adjusted aggregates | Today's 1-minute bars, filtered from 1am Eastern; daily bars for the existing approximately three-year lookback; 30-minute bars for the last 20 calendar days | Seed OHLCV/VWAP, day/premarket levels, previous-day levels, Camarilla pivots, consolidation, historical premarket shares/dollars and relative volume. |
| Massive REST trades | Paginated prints from the incomplete-minute boundary and reconnect interval | Backfill without double-counting aggregate volume; deduplicate buffered/live prints by sequence. |
| Massive reference ticker | Weighted shares outstanding, then share-class fallback | Implied market capitalization/startup eligibility. |
| Massive stock trade stream | Timestamp, price, size, conditions and sequence | Local candles, VWAP, day range, liquidity, sizing/rules and volume notifications. Every print is retained through the browser worker batch. |
| Firestore latest configDataSnapshot | Timestamp-descending latest snapshot: stockSelections, plans, activeProfileName, tradingSettings | Tradebook definitions, enabled sides, areas/zones/levels, targets, ATR, risk inputs, VWAP corrections, retest configuration and existing display subset. |
| Firestore state-{profile}/tradingState | Same-day saved per-symbol/captured trade state, updated locally | Restore/capture accepted entry plans and size, add/core state, VWAP notification and discipline state. |
| Local `%USERPROFILE%\bmtrader\logs` | Screen action/runtime/notification messages and broker order method/status; timestamp, session, symbol and source | Review via Open Logs Folder. Daily UTF-8 files rotate at 10 MiB, with 30-day/100 MiB retention. No Firestore log, order-audit or breakout-snapshot writes. |
| Schwab account and daily orders | Balance, positions, pending entries, protective legs, partial/full execution activities | Net exposure, remaining quantities, risk, P&L, add/reload/exit selection and display. Capped reads subdivide time windows; unresolved truncation fails explicitly. |
| Schwab preferences/stream | Stream credentials, level-one bid/ask and account activity | Stop clamping, fallback prices, account-refresh triggers and reconnects. One consumer. |
| Schwab OAuth | Access-token expiry, refresh/rotation and manual callback exchange | Authenticate direct HTTPS/WSS; persist current credentials in local JSON. |
| Bookmap | Order book, retest observations, hover price, selected chart and API sound/rendering | Integration only; no vendor clients depend on the Bookmap API. |

## Lifecycle and notifications

One runtime starts on the first addon attachment and closes after the final one.
Startup restores config/account/state and buffers live data while history loads. Closed-minute VWAP history is seeded locally; subsequent display
updates use the last completed minute and ignore repeated unchanged points.
OAuth checks every 30s, account polling every 15s, config refresh every 60s,
and market projections every 100ms. Account events and accepted mutations request
a refresh, coalesced with a minimum three-second read interval. HTTP 429 defers
reads using Retry-After seconds (60s fallback). A GET 401 refreshes once and retries
the read; broker mutations are never automatically retried.

Day rollover rebuilds market/state inputs. Removed watchlist symbols lose their
entry context/manual inputs and stop loading history; held symbols keep exit
inputs. Cleared positions/orders publish clearing views. Async history from an
old load cannot overwrite replacement data.

Notifications cover local retest completion, first VWAP touch per captured position, live
and closed-entry-candle volume, over-risk exposure, stop-tightening discipline
and the third-partial core-plan reminder. They appear in bmtrader Logs; optional
**Native Trading Notification Sound** uses the existing Bookmap sound API. Browser
speech and DOM blinking stay in the browser.

## Architecture and development

TS `src/trading` mirrors Java `com.bookmap.plugin.rong.miniviteapp`:
`libraries/{massive,firestore,broker/schwab}`, `core/{marketdata,account,state,
configuration,controllers}`, `runtime`, `ports` and `adapters`.
Keep changes to trading decisions in matching core modules, then regenerate the
shared fixtures and run both test suites. Bookmap coordinate/render/sound code
stays outside the engine in NativeTradingAdapter and existing plugin components.

ViteApp retains its chart/global adapters and existing browser bootstrap. Its
production market, broker reads, ledger, state, configuration validation, payloads,
risk/target and workflow decisions consume extracted core/libraries. The TS
TradingRuntime is a headless mirror tested independently; it does not replace
main.ts or duplicate all browser UI callbacks.

Local domain views pass through existing display parsers in process. The optional
WebSocket server no longer accepts external state/config/credential updates or
forwards user actions. Legacy message names within the engine are internal
contracts, not a dependency on ViteApp executionEntryContext.

## Intentional exclusions and operational limits

- J/K/L and Shift+J/K/L trailing-stop/conditional market-exit workflows were
  removed from both applications. Bookmap no longer consumes those chart keys;
  direct native requests for them are rejected before broker access.
- R and E are disabled in the active browser profile; V has no constructed active
  VWAP-bounce/fail tradebook; U is unused. They were not reenabled during this port.
- Partial market/target actions retain the split-protective-order requirement.
  New entries already use multiple brackets; legacy single-order state can rebuild
  captured multi-target exits with P.
- Other brokers/futures and inactive historical strategy classes were not ported.
  Native startup explicitly accepts Schwab equity profiles only.
- Additional browser indicators, browser chart/DOM/export tools, speech synthesis,
  log-maintenance UI and Firebase sign-in were not duplicated. Replay and AI/chat
  were removed from ViteApp before porting.
- No active Schwab equity trade operation was identified as impossible to port.
- Real vendor credentials, actual Bookmap attachment, Firestore writes and live
  order acceptance are not exercised by automated checks. They remain a manual
  smoke check after you provide the local JSON. Existing Firestore rules apply.

Entries and exits submit from the current local account cache without experimental
position/pending-order preflight GETs. Trading restrictions follow ViteApp; see
[viteapp-rule-parity-audit.md](viteapp-rule-parity-audit.md). There are no input-age
cutoffs, session ownership, action coordination fences or waits
for reconciliation. Broker rejections stop the remaining requests. An ambiguous
mutation outcome requires broker review; use **Reset After Broker Review** only
after checking the account. It never triggers automatic resend or a browser fallback.
