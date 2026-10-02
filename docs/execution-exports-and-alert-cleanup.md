# Execution exports and alert cleanup

Progress checkpoint, 2026-10-01. This follow-up resolves G2, G5 and G7 in
[the feature-gap review](current-viteapp-feature-gaps.md). ViteApp changes are
included in `6a75bfd` together with its other retired-workflow cleanup. The
Bookmap companion commit contains the native exports, clipboard UI, state
cleanup, tests and these documents.

## Completed changes

- Removed ViteApp's breakout-close percentage calculation, speech/log messages,
  `closedOutsideRatio` type/default, and corresponding native default field.
  Regenerated both repositories' shared state fixtures. Other candle-close
  reminders and trading decisions remain.
- Removed ViteApp's VWAP continuation status calculation/caller, dedicated
  `vwapPatterns.ts` remainder, tooltip price-line rendering, and chart field.
  VWAP indicators, shared VWAP helpers, trading logic and risk-line updates remain.
- Extracted browser execution exporters into pure TypeScript
  `src/trading/core/account/executionExports.ts`; existing browser buttons and
  exported broker helper names delegate to that module.
- Added matching Java `miniviteapp/core/account/ExecutionExports.java`.
  Runtime takes a detached snapshot of the cached account's executions and formats
  it outside the state lock. No broker/API reads, trading-state changes or order
  submissions are performed by export. All cached symbols are included, even
  without chart/watchlist membership. Only today's Eastern market session is
  exported; absent runtime/account/fills produce a clear error.
- Added one **Export** button beside the existing account status in **bmtrader
  Logs**. No additional toolbar row or persistent panel. A three-item menu copies
  the selected format directly to the system clipboard. Generation and copying
  run outside the Swing event thread; completion is logged and failures display
  an error dialog. This replaces the initial file-save workflow at the user's
  request.

## Using the exports

Open **bmtrader Logs → Export**, select a format, then paste the copied text:

| Format | Output |
| --- | --- |
| ThinkScript summary | Text: one bubble per symbol, market minute and buy/sell side, quantity-weighted price |
| ThinkScript details | Text: same grouping plus the existing price buckets (2 cents above $25, 3 above $50, 4 above $100, 5 above $200; exact rounded cents at/below $25) |
| Trade CSV | CSV text: the existing account-statement template with order/trade execution rows, signed quantities and open/close effects |

ThinkScript output preserves the ViteApp snippet convention: the receiving study
must already define `time` as seconds since the 9:30 Eastern open and the global
colors `BubbleGreen`/`BubbleRed`. It is not a complete standalone study. Grouping
uses Eastern Time with historical DST; premarket minutes are negative.

CSV timestamps use the computer's local timezone, as in the original browser
export, now with an explicit 24-hour clock. Both implementations replace the old
hardcoded account-number/owner header with an anonymous statement header and
escape CSV fields. Legacy CSV section layout and `ETF`/`MKT` placeholders are
preserved for compatibility: this remains a fills export/template, not a complete
broker statement with original order types, commissions or balances.

Exports reflect the latest completed native account refresh. They do not request
a refresh or retrieve earlier trading days. Browser exports still print to the
console through the existing controls; Bookmap copies to the clipboard and logs
**Copied [format] to clipboard** without a confirmation popup or file dialog.

## Verification and resume notes

- `npm run build`: passed (existing Vite asset/chunk warnings).
- `npm run test:execution-exports`: 15 production-generated cases, with explicit
  checks for weighted grouping, side/symbol/minute separation, bucket thresholds,
  premarket/DST, CSV timestamps, signed quantities, effects and escaping.
- `npm run test:state`: 85 shared cases; `test:core-target-exits`: six passed.
- Java `ExecutionExportsTest`: exact string parity against those 15 TS cases.
- Java `TradingRuntimeTest`: verifies all-symbol cached export after watchlist
  removal, no HTTP calls/state changes, absent data and stale-session rejection.
- Full Gradle build passed: 211 normal tests, native-only compilation and nine
  release JAR tests. Both repositories passed `git diff --check`.
  No live broker/Firestore calls were used. Clipboard use inside Bookmap and
  importing the exports into external chart applications were not exercised.

For future formatter changes, edit both core modules, regenerate fixtures with
`node --experimental-strip-types scripts/generateExecutionExportFixtures.mjs`
in ViteApp, and run the export checks plus Bookmap's build. Source generation
writes matching fixture JSON into both repositories. No remaining implementation
work for these three selected items; unrelated gaps remain separate decisions.
