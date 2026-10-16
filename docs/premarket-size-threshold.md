# Premarket size threshold

Wall labels and exported liquidity summaries always use a fixed minimum of 5,000 shares. Attached charts use the per-symbol premarket threshold below for entry wall snapshots/profit targets, wall-change alerts/retest evidence, the False Breakout offer stage, and the Reclaim Second Bid monitor. False Breakout bid qualification always uses a fixed 5,000-share threshold. The trade-window threshold display shows the premarket-based value.

| Premarket shares | Threshold |
| --- | --- |
| Below 1,000,000 | 2,000 |
| 1,000,000 through 2,000,000 | 5,000 |
| Above 2,000,000 through 3,000,000 | 10,000 |
| Above 3,000,000 through 4,000,000 | 15,000 |
| Above 4,000,000 | 20,000 |
| Unavailable | 5,000 |

The policy first uses Schwab's price-history endpoint with one-minute candles and extended-hours data enabled. It shares the native trading runtime's OAuth token and refresh handling. Requests cover 01:00 Eastern until 09:30 Eastern exclusive, matching the Thinkorswim study's start time. Returned candles are deduplicated by timestamp and filtered to the requested range, because Schwab may return additional session candles.

If any minute in that window is absent from Schwab, the runtime fetches bounded one-minute aggregates from Massive using the existing local API key. It adds only timestamps missing from Schwab, including leading, internal, and trailing gaps. Schwab always wins overlapping timestamps, including zero-volume candles. Duplicate candles and regular-session candles cannot add volume twice. Minutes absent from both feeds remain absent; they may have no eligible trades. A valid empty Schwab response can be backfilled entirely. Malformed responses, Schwab request failures, failed required Massive requests, and no candles from either provider use the unavailable fallback instead of publishing a partial total.

The background worker replaces the merged sum every minute before open. A successful fetch at or after open freezes the session's total; failed final fetches retry. Backfill uses Massive REST minute aggregates, not stream volume. Runtime logs report Schwab shares, Massive backfill shares, and the number of added minutes.

Schwab's observed CRWV response on October 14, 2026 began at 07:00 Eastern despite the then-configured 04:00 request. On October 16, requests starting at 01:00 for TSLA and SPY also returned no candles before 07:00; Massive returned candles starting at 04:00. This missing coverage is now backfilled while retaining Schwab's volume for overlapping minutes. The mixed-provider total can still differ from Thinkorswim.

Charts on a different Eastern session date and explicitly configured replay mode use the 5K fallback. A delayed same-day chart is accepted; requests are bounded by chart time to avoid later premarket volume. A date change invalidates yesterday's total. Missing authorization or failed requests use 5K. Fetch failures are logged without credentials; the UI identifies the Schwab + Massive backfill policy or shows the unavailable reason.

The UI shows the threshold and premarket shares or the fallback reason. Percentile, top-five size, manual floor, and the former 10K alert cap do not affect attached charts. Size remains in the existing Bookmap depth/share units; no round-lot conversion is performed. Existing strict-greater-than size comparisons in the specialized monitors remain; labels and entry liquidity use greater-than-or-equal. Material alerts still qualify by crossing the threshold or an absolute delta strictly exceeding it.

Canonical observation (1K) and Composer trigger/confirmation policies are unchanged.

## Absolute entry size cap

Every opening order from the native Bookmap adapter is capped after the button/hotkey's requested sizing and all entry rules:

`maximum risk in R = largest qualifying session depth size / premarket size threshold`

The trade window shows `Max counted: 5K | Cap: 0.33R` on a separate line beneath the premarket-volume/threshold display. It refreshes every second using the sizing tracker, including changes from range expansion and cleared orders. The tooltip shows the exact share-size numerator/denominator and the ratio to six decimal places, and identifies the cap as a total risk ceiling shared with existing positions and pending entries. Before any order qualifies, it shows `Max counted: 0 | Cap: 0.00R`.

The denominator uses the existing tier table above, including its boundary rules and unavailable-volume fallback. For a 15K threshold and a largest qualifying 5K depth level, the cap is exactly one third of R. This is a ceiling, not a multiplier on the selected button: a smaller request stays smaller. Ratios above 1R are allowed when the existing rules also permit that size.

The tracker observes both bids and offers from 09:30 Eastern through the regular session, using Bookmap market timestamps. It retains the maximum size observed at each price even after that level is canceled, reduced, or traded through. Resting depth still present at the open is included; depth removed before the open is excluded. At sizing time, only prices inside the current regular-session low/high, including equality, qualify. Expanding the range can therefore qualify an earlier cleared level. Tracking resets on the next session date and is removed when the chart detaches. Only depth actually delivered to this plugin can be tracked; minute-candle history cannot reconstruct unseen historical depth.

The cap applies to risk-sized and fixed-share buttons, chart/directional hotkeys, partial reloads, swaps/reentries, and automatic pending-entry replacements. Existing same-direction position risk and pending-entry risk consume the budget. A PUT replacement excludes the pending order it replaces. Entries submitted before old entries are canceled still reserve those old entries' risk. Swap reentries are sized after their preceding position-close stage. Low-risk reload overrides and favorable VWAP conditions cannot bypass the ceiling.

The final protected order quantities are rounded down to whole shares, with matching quantities in their stop/target brackets and captured trade state. No qualifying level, unavailable session range, a stale session date, or insufficient budget for one share blocks the opening action; exits remain available. Repeated opening actions wait for a broker account read started after the preceding opening action finishes, so repeated buttons/hotkeys cannot reuse the same cached risk budget while a submission is in flight. Broker account visibility and market-order fill prices can lag: the cap is calculated from observed positions/orders and estimated entry prices, rather than guaranteeing realized loss or an eventual fill price.
