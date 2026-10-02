# Current ViteApp features absent from Bookmap

Follow-up: G2 (breakout-close percentage alert) and G5 (VWAP continuation status)
are now removed; G7 (execution exports) is implemented natively. The audit below
records the earlier baseline. See [completed changes and validation](execution-exports-and-alert-cleanup.md).

Reviewed 2026-10-01 against ViteApp `09781e9` and bookmap-plugin `60a2aa1`.
The working trees were checked as well: ViteApp's uncommitted
`docs/inactive-strategies-review.md` was preserved. This review changes no
application source, executes no live vendor requests, and authorizes no removals
or feature ports. This document supersedes older broad lists of migration gaps.

## Result and scope

The current six constructed wall-reversal tradebooks and their normal Schwab
entry/add/reload/swap/cancel/flatten/partial/stop/target commands have native
counterparts. Massive data, Firestore config/state/audits, Schwab account/orders,
OAuth renewal and vendor streams are already local in Bookmap. The remaining
differences are manual UI access, alerts/analysis, diagnostic state and browser
tools. This is a source review of current call sites and UI wiring, not a claim
of exhaustive live behavior equivalence or a new live trading smoke test.

Reference implementations:

- ViteApp factory: `src/trading/core/configuration/tradingConfig.ts` and
  `src/tradebooks/tradebooksManager.ts#createAllTradebooks`.
- Bookmap factory: `miniviteapp/core/configuration/TradingConfig.java`.
- ViteApp commands: `src/controllers/keyboardHandler.ts` and `handler.ts`.
- Bookmap commands: `miniviteapp/core/controllers/{KeyboardHandler,Handler,
  WorkflowHandler,ExtendedHandler,EntryHandler}.java`, `TradingRuntime.dispatch`
  and `tradebuttons/TradeButtonWindow.java`.

Java paths below are relative to `src/main/java/com/bookmap/plugin/rong/`;
TypeScript paths are relative to the sibling ViteApp repository.

## Remaining user-facing and analytical differences

| ID | ViteApp behavior still present | What Bookmap currently has / lacks | Source evidence |
| --- | --- | --- | --- |
| G1 | Manual **Refresh** beside the plan controls widens the protective stop of a single unfilled entry to the latest LOD/HOD and recalculates/replaces the bracket. | The native `RefreshEntryStop` handler and automatic first-five-minute job exist. There is no equivalent user button/hotkey. The browser per-symbol function invoked by its button has no five-minute cutoff; the browser automatic timer does. This is primarily a UI exposure gap, not an absent broker implementation. | `src/ui/chart.ts#getHtmlContentsAndTradebooks` refresh listener; `src/algorithms/autoTrader.ts#refreshEntryStopLossForSymbol`; Java `WorkflowHandler.handle` and `TradingRuntime.pendingJobs`; native button panel. |
| G2 | Records a one-time breakout close percentage: directional gain from entry to a newly closed candle divided by initial entry-to-stop risk. Stores `closedOutsideRatio`; logs/speaks closed-inside or closed-outside percentage and prepares for the first pullback. | Native state defines/restores the field but no native runtime code updates it or emits these alerts. The browser function is called on realtime candle closes with a nonzero position; it does not itself prove that the candle is the original entry candle. | `src/algorithms/autoTrader.ts#onMinuteClosed` and `getBreakoutEntryClosePercentage`; Java `TradeState.defaultBreakout`, runtime notification code. |
| G3 | Opening-session prompts: check the first-minute close at 6:30:50 local; check for a new high/low ten seconds before each of the first five five-minute closes. With the user's Pacific clock those are 9:30:50 Eastern and 9:34:50 through 9:54:50 Eastern. | No corresponding native scheduled notifications. Bookmap has sound support, so these messages could be ported without browser speech. The ViteApp schedule uses local fixed clock hours; a port should explicitly use the market clock. | `src/algorithms/autoTrader.ts#scheduleFirstMinuteCloseEvent`, `schedule5MinutePreCheckEvent`, called from `scheduleEvents`; native timer/notification inventory. |
| G4 | Warns at startup about existing positions before market open; on realtime premarket candle closes with a position, speaks the fresh-premarket-news reminder. Also logs that the second candle has just closed. | Native account/market state exists but these specific messages are not emitted. The overnight warning condition is simply pre-open with existing positions, not a broker determination of when a position was opened. | `src/tosClient.ts#setInitialAccount`; `src/algorithms/autoTrader.ts#onMinuteClosed`; Java runtime startup/price notifications. |
| G5 | For a long position with the specific premarket-high/open/VWAP/key-level layout, displays continuation status at the current price: testing/confirmed above premarket high, testing/confirmed below VWAP, or consolidation between VWAP and premarket high. | Bookmap draws VWAP and premarket levels, but does not calculate/display these browser continuation labels. This is a retained analysis overlay, not a separate active VWAP entry strategy. | `src/algorithms/autoTrader.ts#getChartAnalysis`, called on price updates; `src/algorithms/vwapPatterns.ts#getStatusForVwapContinuationLongWithPremarketHigh`; `src/ui/chart.ts#updateToolTipPriceLine`; Java `NativeViews` and runtime. |
| G6 | Classifies premarket volume as TooLow / Ok / Elevated, including dollar-volume and ticker-specific thresholds, and logs the quality, dollar volume, shares, RVOL and median. | Native premarket dollar/share/RVOL calculations and eligibility rules exist. The extra quality classifier and matching summary are absent. The browser's nominal low-quality early-entry restriction currently sets `allowed` back to true, so this classifier is not a missing live 15-minute entry block. | `src/algorithms/setupQuality.ts`; `src/api/marketData.ts#setPreviousDayPremarketVolume`; `src/algorithms/rules.ts#shouldAllowEarlyEntry`; Java `PremarketVolume` and `EntryRulesChecker`. |
| G7 | **Show executions** and **Show exec more** generate ThinkScript `AddChartBubble` code, aggregating fills per minute/side and optionally per price. **Export trades** generates account/order/trade CSV text. These print text to the browser console. | Native fills, fill labels, ledger and account display exist. Matching ThinkScript/CSV generation tools do not. Do not describe the absence of exporters as missing fill tracking. | `src/main.ts` button listeners; `src/api/broker.ts#generateExecutionScript`; `src/tools/tradingview.ts#exportTrades`; Bookmap execution rendering and `TradeLedger`. |
| G8 | Manual **Check quantity** reports shares lacking a protective stop. **Update acct UI** triggers an account read/display refresh. | Bookmap has cached risk/uncovered-share calculations, uncovered-share flattening, automatic account polling and activity-driven refresh. It has no matching dedicated check/refresh buttons. Restarting the runtime is a different control. | `src/main.ts` listeners for `check_quantity` and `update_account_ui`; Java `Handler.flattenPostionKeyPressed`, `TradingRuntime.refreshAccount`, native controls. |
| G9 | The a20/a50/a80 controls invoke market partial reload two/five/eight times respectively. | Native single partial reload/Add Partial/Shift+A exists, but those multi-reload convenience controls do not. This is a UI preset gap; repeated partial reload is not a new missing core strategy. | `src/ui/chart.ts#setupAddCountButtons`; `src/controllers/handler.ts#reloadPartialAtMarket`; Java `TradeButtonWindow` Add Partial and `ExtendedHandler.reload`. |
| G10 | Browser chart presentation includes one-minute candle/volume charts, MA5/MA9, opening-price series, 1R/2R/3R profit lines, and labeled planned final-target lines. Candles can be hidden until the configured delay after open. | The addon does not implement those extra browser overlays/chart controls. Bookmap itself supplies its charting environment; the addon retains its existing subset. VWAP, premarket/previous-day levels, Camarilla, configured levels/zones, stops/orders and fill annotations already exist. These differences were intentionally excluded from the port at the user's request. | `src/ui/chart.ts#createTimeFrameChart`, `createChartWidget`, `drawTradeManagementInChart`, `drawProfitRatio`, visibility helpers; `src/data/db.ts` MA history/live updates; native rendering managers and `NativeViews`. |
| G11 | Browser notification cards can be dismissed; reminders can blink chart backgrounds and use spoken messages, including repeated over-risk speech. | Native notifications log text and optionally play a Bookmap alert tone. There is no speech synthesis or browser-style card/blink UI. The substantive first-VWAP-touch, volume, over-risk, stop-discipline, retest/new-position and core-plan notifications already exist natively. | `src/ui/notificationCenter.ts`, `src/notifications/notificationDispatcher.ts`, `src/controllers/partialStopDisciplineController.ts`; Java `NativeTradingAdapter.notifyUser`, runtime notification jobs. |

## Diagnostic state differences

These fields exist in both state schemas; preserving a field is different from
implementing the browser's updates to it.

- `maxPullbackReached`: ViteApp updates the maximum adverse pullback relative to
  captured entry/stop on each price update with an active trade. Bookmap currently
  initializes/restores this field but has no matching updater. The old associated
  automatic target/breakeven action and deep-pullback speech are commented out in
  ViteApp, so those are not active missing execution features. Source:
  `autoTrader.updatePullbackDepth` and its price-tick caller.
- `peakRiskMultiple`: ViteApp writes the current risk multiple when chart profit
  lines are rebuilt after quantity increases. Despite its name, the current
  updater assigns the value rather than computing a running maximum. Bookmap
  initializes/restores the field but does not perform this chart-driven write.
  No current checked-in entry/exit rule consumes this field. Source:
  `chart.drawProfitRatio`, `tradingState.updatePeakRisk`.
- `closedOutsideRatio`: see G2; native schema coverage does not include calculation
  or its alert.
- `lowestExitBatchCount`: the browser initializes/lowers this saved value during
  reload and order drawing; native state retains the field without those writes.
  Its old use for calculating add count is commented out. Both current paths use
  the fill-derived added-partial stack, so this is a legacy state-write difference,
  not a missing active add-count rule. Sources: `handler.reloadPartialPressed`,
  `chart.drawWorkingOrders`, `tradingState.getAddCount`.

## Optional developer APIs and inactive leftovers

These are present in the browser source but are not normal active-trading gaps:

- Bulk log/collection deletion helpers remain callable through
  `window.HybridApp.Firestore`. Native audit/state persistence exists; deletion
  utilities do not. Sources: `src/firestore.ts#deleteDailyLogs`,
  `deleteMonthlyLogs`, `deleteLogsAndOrders`, `deleteCollectionByName` and
  `src/main.ts` namespace exposure. Do not execute these during review.
- `R` still calls the browser's manual risk-line drawing function, while
  `enableRiskLevel=false` disables the additional risk-level policy and automatic
  risk lines. Native custom stop/entry/fixed quantity are supported; an independent
  custom risk-line policy/control is not. Treat this as optional disabled policy
  and residual drawing, not a missing active sizing rule.
- First-new-high/current-candle/tight-stop entry parameters survive in chart APIs,
  but the active wall-reversal buttons use the current two risk methods with those
  options false. No extra currently constructed strategy is missing on that basis.
- The alternate `GapAndCrapBreakdownBidSwingLow` and
  `GapDownAndGoDownBreakdownBidSwingLow` IDs/branches remain in ViteApp, but its
  factory never constructs them. Other open-drive/VWAP analysis helpers and
  disconnected historical modules need their own cleanup review, not a port.
- `Patterns.checkWave(false)` is scheduled, but it neither changes state nor emits
  logs in this mode. Do not list an active wave alert/enforcement as missing.
- `raiseTargetsIfWasLess` and other exposed convenience/developer handlers still
  exist without a normal checked-in UI caller. Ordinary target adjustment exists
  in both apps; this review does not propose bulk/developer API parity.
- Legacy TD Ameritrade helpers remain exposed/imported, including a quote helper
  and Schwab fundamentals utilities. The active broker selector returns Schwab,
  both selectable profiles are Schwab equity, and current history/streams use
  Massive/Schwab. Their existence is not evidence of another active broker or a
  missing futures trading profile.
- Earlier references to Firebase sign-in as a migration gap do not describe a
  currently wired sign-in flow found in this source review.

## Removed features and corrected earlier descriptions

- Short VWAP bounce failure and long pushdown failure were removed in ViteApp
  `7237b16`. Those strategies were not constructed in Bookmap; no removal port
  is needed. Retain the independent active continuation analysis in G5 until a
  separate decision is made.
- Historical offer-wall breakout/bid-wall breakdown IDs and orphaned setting
  were removed from ViteApp in `c2e4846`. Those inactive strategies were not
  native Bookmap strategy classes. Active wall-reversal breakout *order entries*
  remain in both applications and are a distinct feature.
- TradeStation was removed from ViteApp in `110032e`. Do not carry forward the old
  multi-broker gap as a current active feature gap. Replay and AI/chat are also
  absent from the current live applications.
- Trailing stops and their Shift market-exit variants were removed in ViteApp
  `09781e9` and Bookmap `60a2aa1`. They are no longer migration work.
- The browser's current widget constructs the one-minute chart only, and
  `showChartForTimeframe` ignores its requested timeframe and displays M1. Do not
  list the older automatic 1m-to-5m-to-15m chart switching as a current feature gap.
- Some old comments/status strings in Bookmap still say "ViteApp", for example
  the initial account waiting label. That is stale text, not a running dependency.
- Old documentation/test scenario counts can be stale; the trailing-removal
  shared state fixtures now contain 85 cases rather than the previous 99.

## Review next steps

Review G1 first if manual pending-entry refresh is desired. G2-G6 and diagnostic
state are independent decisions about alerts/analysis/tracking. G7-G9 concern
reporting and manual convenience. G10-G11 remain intentional presentation
differences. No implementation or deletion was performed by this audit, and
existing uncommitted ViteApp work was left untouched.
