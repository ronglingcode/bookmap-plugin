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

The background worker caches merged minute history. Chart-minute, market-open, and session-date changes queue a refresh off the Bookmap callback thread; a minute timer retries unavailable history. A successful fetch through the open freezes that session's full history. Backfill uses Massive REST minute aggregates, not stream volume. Runtime logs report Schwab shares, Massive backfill shares, and the number of added minutes.

Schwab's observed CRWV response on October 14, 2026 began at 07:00 Eastern despite the then-configured 04:00 request. On October 16, requests starting at 01:00 for TSLA and SPY also returned no candles before 07:00; Massive returned candles starting at 04:00. This missing coverage is now backfilled while retaining Schwab's volume for overlapping minutes. The mixed-provider total can still differ from Thinkorswim.

Live and replay charts both use the date and market time supplied by Bookmap. Historical replay fetches premarket history for its own Eastern session date; explicit replay mode no longer forces a 5K threshold. Requests end at the last completed chart minute, capped at 09:30 Eastern and wall time, so later premarket volume cannot leak into a replay. Rewinding reprojects cached candles through the earlier chart time, even after the full session was fetched. Changing dates immediately hides the old session's total until matching history loads. Unknown chart time, missing authorization, or failed/unavailable history uses 5K. Fetch failures are logged without credentials; the UI identifies the Schwab + Massive backfill policy or shows the unavailable reason.

The UI shows the threshold and premarket shares or the fallback reason. Percentile, top-five size, manual floor, and the former 10K alert cap do not affect attached charts. Size remains in the existing Bookmap depth/share units; no round-lot conversion is performed. Existing strict-greater-than size comparisons in the specialized monitors remain; labels and entry liquidity use greater-than-or-equal. Material alerts still qualify by crossing the threshold or an absolute delta strictly exceeding it.

Canonical observation (1K) and Composer trigger/confirmation policies are unchanged.

## Entry liquidity rule

The programmer-only constant `BookmapSizeCap.ACTIVE_MODE` selects **STRICT** (current setting) or the retained **PROPORTIONAL** algorithm. Changing it requires editing the source and rebuilding the plugin. Traders cannot change the mode through the UI or a runtime setter. The active rule applies to all entry buttons, chart/directional hotkeys, adds/reloads, swaps/reentries, and automatic pending-entry replacements. The UI reports the active mode and qualification status without offering a switch. Existing entry/sizing rules still apply in both modes. Exits and protective-stop adjustments remain available.

Strict blocks the entire opening action when its largest qualifying order is below the premarket threshold. Equality qualifies. Once qualified, Strict leaves the requested size and protective brackets unchanged; it does not apply the proportional R ceiling. Smaller R requests, fixed shares, low-risk reload overrides, and favorable VWAP conditions cannot bypass qualification. When blocked, the UI highlights **STRICT: ENTRY BLOCKED in current range** in red and shows both the largest counted size and the required size. It also explains that stop entries are checked through their trigger price. Once qualified, it shows **liquidity threshold met**, which refers only to this rule, not all trading eligibility checks.

Proportional preserves the earlier policy:

`maximum risk in R = largest qualifying session depth size / premarket size threshold`

For a 15K threshold and a largest qualifying 5K depth level, Proportional caps total risk at one third of R. This is a ceiling, not a multiplier on the selected button: a smaller request stays smaller. Ratios above 1R are allowed when the existing rules also permit that size. Existing same-direction position risk and pending-entry risk consume this budget. A PUT replacement excludes the pending order it replaces. Entries submitted before old entries are canceled still reserve those old entries' risk. Swap reentries are sized after their preceding position-close stage. Whole-share reductions update all protective brackets and captured trade state consistently.

The denominator in both modes uses the existing tier table above, including boundary rules and the unavailable-volume fallback. The display refreshes every second. Its tooltip identifies the winning order's exact size, side, price, and observation time in Eastern. In Proportional mode it also shows the calculation and identifies the cap as a shared total risk ceiling.

The tracker observes both bids and offers from 09:30 Eastern through the regular session, using Bookmap market timestamps. It retains the maximum size observed at each price even after that level is canceled, reduced, or traded through. Resting depth still present at the open is included; depth removed before the open is excluded. Market and limit entries qualify only prices inside the current regular-session low/high, including equality. Expanding the actual range can qualify an earlier cleared level.

A **buy stop** submitted above HOD instead qualifies prices in `[LOD, submitted buy-stop trigger]`. A **sell stop** submitted below LOD qualifies `[submitted sell-stop trigger, HOD]`. The trigger is the final broker-order price, including any entry price buffer. This applies in both modes, and only to that particular opening order. It never changes the tracked HOD/LOD or unlocks a market/limit entry; a protective exit stop cannot grant this exception. Each opening in a multi-order action must qualify independently. In Proportional mode, the most restrictive qualifying ratio among the openings bounds their shared risk budget. Outward record-breaking observations are included in the execution snapshot so removed/cleared orders and orders beyond the current range can still be checked accurately without sending every historical depth level.

When chart timestamps are available, HOD/LOD and eligibility use that chart timeline for both live and replay. Minute history contributes extremes only after each minute closes; the current minute's extremes come from Bookmap prints observed so far. Live runtime levels cannot widen a replay range. A backward replay seek clears the observed range and order maxima, reapplies only completed history as of the new time, and rebuilds resting-depth evidence from fresh replay callbacks rather than stale shared depth. Session changes reset maxima; detaching the chart removes them. Only depth actually delivered to this plugin can be tracked; minute-candle history cannot reconstruct unseen historical depth.

Unavailable session range or a stale execution session blocks openings in either mode. Proportional also blocks when there is no budget for one whole share. Repeated opening actions wait for a broker account read started after the preceding opening action finishes. Broker account visibility and market-order fill prices can lag: Proportional calculates planned risk from observed positions/orders and estimated entry prices, rather than guaranteeing realized loss or an eventual fill price.
