# Premarket size threshold

Wall labels and exported liquidity summaries always use a fixed minimum of 5,000 shares. Attached charts use the per-symbol premarket threshold below for entry wall snapshots/profit targets, wall-change alerts/retest evidence, the False Breakout offer stage, and the Reclaim Second Bid monitor. False Breakout and Bid Breakdown share a fixed bid minimum of 4,500 shares (inclusive), sustained for more than one second; their entry labels require Level 1 ask below the bid for one second. The trade-window threshold display shows the premarket-based value.

| Premarket shares | Threshold |
| --- | --- |
| Below 1,000,000 | 2,000 |
| 1,000,000 to below 2,000,000 | 5,000 |
| 2,000,000 to below 3,000,000 | 10,000 |
| 3,000,000 to below 4,000,000 | 15,000 |
| 4,000,000 to below 5,000,000 | 20,000 |
| 5,000,000 to below 6,000,000 | 25,000 |
| 10,000,000 to below 11,000,000 | 50,000 |
| Unavailable | 5,000 |

At or above one million premarket shares, the threshold is `floor(premarket shares / 1,000,000) * 5,000`: each completed million adds 5K, with no 20K cap. Exact million boundaries enter the new tier. For example, 10,709,318 shares requires 50K. The integer size API saturates at `Integer.MAX_VALUE` for extreme totals.

The policy first uses Schwab's price-history endpoint with one-minute candles and extended-hours data enabled. It shares the native trading runtime's OAuth token and refresh handling. Requests cover 01:00 Eastern until 09:30 Eastern exclusive, matching the Thinkorswim study's start time. Returned candles are deduplicated by timestamp and filtered to the requested range, because Schwab may return additional session candles.

If any minute in that window is absent from Schwab, the runtime fetches bounded one-minute aggregates from Massive using the existing local API key. It adds only timestamps missing from Schwab, including leading, internal, and trailing gaps. Schwab always wins overlapping timestamps, including zero-volume candles. Duplicate candles and regular-session candles cannot add volume twice. Minutes absent from both feeds remain absent; they may have no eligible trades. A valid empty Schwab response can be backfilled entirely. Malformed responses, Schwab request failures, failed required Massive requests, and no candles from either provider use the unavailable fallback instead of publishing a partial total.

The background worker caches merged minute history. Chart-minute, market-open, and session-date changes queue a refresh off the Bookmap callback thread; a minute timer retries unavailable history. A successful fetch through the open freezes that session's full history. Backfill uses Massive REST minute aggregates, not stream volume. Runtime logs report Schwab shares, Massive backfill shares, and the number of added minutes.

Schwab's observed CRWV response on October 14, 2026 began at 07:00 Eastern despite the then-configured 04:00 request. On October 16, requests starting at 01:00 for TSLA and SPY also returned no candles before 07:00; Massive returned candles starting at 04:00. This missing coverage is now backfilled while retaining Schwab's volume for overlapping minutes. The mixed-provider total can still differ from Thinkorswim.

Live and replay charts both use the date and market time supplied by Bookmap. Historical replay fetches premarket history for its own Eastern session date; explicit replay mode no longer forces a 5K threshold. Requests end at the last completed chart minute, capped at 09:30 Eastern and wall time, so later premarket volume cannot leak into a replay. Rewinding reprojects cached candles through the earlier chart time, even after the full session was fetched. Changing dates immediately hides the old session's total until matching history loads. Unknown chart time, missing authorization, or failed/unavailable history uses 5K. Fetch failures are logged without credentials; the UI identifies the Schwab + Massive backfill policy or shows the unavailable reason.

The UI shows the threshold and premarket shares or the fallback reason. Percentile, top-five size, manual floor, and the former 10K alert cap do not affect attached charts. Size remains in the existing Bookmap depth/share units; no round-lot conversion is performed. The False Breakout offer stage and Reclaim Second Bid retain strict-greater-than size comparisons. Shared False Breakout/Bid Breakdown bid qualification accepts equality at 4,500 shares; labels and entry liquidity also use greater-than-or-equal. Material alerts still qualify by crossing the threshold or an absolute delta strictly exceeding it.

Canonical observation (1K) and Composer trigger/confirmation policies are unchanged.

## Entry liquidity rule

The programmer-only constant `BookmapSizeCap.ACTIVE_MODE` selects **STRICT** (current setting), **PROPORTIONAL**, or **NORMAL_1R**. Changing it requires editing the source and rebuilding the plugin. Traders cannot change the mode through the UI or a runtime setter. The active rule applies to all entry buttons, chart/directional hotkeys, adds/reloads, swaps/reentries, and automatic pending-entry replacements. The UI reports the active mode and qualification status without offering a switch. Existing entry/sizing rules still apply in all modes. Exits and protective-stop adjustments remain available.

Strict blocks the entire opening action until accumulated executions at one bid/offer wall or a single historical trade meet the premarket threshold. Equality qualifies. Once qualified, Strict leaves the requested size and protective brackets unchanged; it does not apply the proportional R ceiling. Smaller R requests, fixed shares, low-risk reload overrides, and favorable VWAP conditions cannot bypass qualification. When blocked, the UI highlights **STRICT: ENTRY BLOCKED until qualifying fills** in red and shows both the largest filled amount and the required size. Market, limit, STOP, and STOP_LIMIT entries all use the same executed evidence. Once qualified, it shows **liquidity threshold met**, which refers only to this rule, not all trading eligibility checks.

Proportional preserves the earlier policy:

`maximum risk in R = max(largest executed wall episode, largest historical print) / premarket size threshold`

For a 15K threshold and a largest filled wall of 5K shares, Proportional caps total risk at one third of R. This is a ceiling, not a multiplier on the selected button: a smaller request stays smaller. Ratios above 1R are allowed when the existing rules also permit that size. Existing same-direction position risk and pending-entry risk consume this budget. A PUT replacement excludes the pending order it replaces. Entries submitted before old entries are canceled still reserve those old entries' risk. Swap reentries are sized after their preceding position-close stage. Whole-share reductions update all protective brackets and captured trade state consistently.

The denominator in both modes uses the existing tier table above, including boundary rules and the unavailable-volume fallback. The display refreshes every second. Its tooltip identifies the winning wall's executed size, side, price, and fill time in Eastern. In Proportional mode it also shows the calculation and identifies the cap as a shared total risk ceiling.

The tracker counts actual Bookmap executions at observed bid/offer walls during weekdays from 09:30 inclusive to 16:00 exclusive Eastern, after snapshot readiness. Buying consumes the offer and selling consumes the bid, at the exact wall price. Unknown-aggressor prints count only when a valid Level 1 bid/ask identifies their side at that price. Raw depth size, size reductions, cancellations, price crossings, and minute history never count as executed volume.

Executions accumulate within one continuous side/price wall episode. Depth size updates keep that episode; removal followed by a new wall starts a separate episode. Bid and offer fills, different prices, and separate episodes are never added together. Bookmap depth is aggregated by price, so this measures a wall episode rather than identifying an individual exchange order. A 10K wall with 1K executed and 9K canceled contributes only 1K. Multiple partial fills of 2K and 3K in the same episode contribute 5K. A filled 5K wall meets a 5K threshold exactly.

A 250 ms grace accepts depth-before-execution callback ordering at a removed level. This can count only actual same-price executions, never the old displayed wall size. Once that grace expires, later prints cannot revive the old episode. If a replacement wall appears first, its executions start from zero. The tracker retains the largest executed amount for the session even after its wall disappears; it does not sum all session volume.

All opening order types and the Bid Breakdown pattern consume this same result. There is no current-range qualification or prospective stop-trigger exception: a canceled wall cannot unlock entries when price later trades beyond it, and an outward stop price cannot borrow unfilled liquidity. HOD/LOD tracking remains available separately for the other trading rules and chart display. The False Breakout pattern retains its separate executed-offer starting condition.

A new session date resets executed evidence. A backward replay seek clears executed evidence and wall episodes, then requires fresh callbacks rather than seeding from the stale shared book. Live and replay use Bookmap market timestamps. Detaching the chart removes its ledger.

### Loading after market open

The plugin uses Bookmap's `HistoricalModeListener` to process stored trades/depth before switching to live callbacks, and `BackfilledDataListener` to include available cloud history. Bookmap supplies the historical events through the normal timestamp/depth/trade callbacks; no separate request for minute candles is used. See the [Bookmap historical callback guide](https://bookmap.com/knowledgebase/docs/API-Tutorial#pre-subscription-market-data) and [cloud backfill listener](https://bookmap.com/addons/api/velox/api/layer1/simplified/BackfilledDataListener.html).

During this catch-up, regular-session historical wall fills accumulate under the same episode rules as live fills. A single historical executed print also proves its own share size when the historical feed has no matching depth. For example, loading at 10:30 after a 6K print at 09:45 qualifies a 5K threshold as soon as history catches up. This path does not depend on live pattern snapshot readiness. Unrelated prints are never summed without an observed continuous wall episode. Premarket, after-hours, and previous-session fills cannot qualify today's trading. The cap's `evidenceBasis` identifies `historicalTrade` or `wallExecutions`, and its recorded price, side, and timestamp identify the winning evidence.

Strict and Proportional remain unavailable while catch-up runs; the UI shows **checking fills since market open**. `onRealtimeStart` releases the history-loading gate, preserves the largest executed size and ongoing wall episode, and resumes the existing live accounting without replaying the evidence a second time. Qualification still uses the current premarket threshold. A backward seek or new session clears historical qualification as well as live evidence.

Coverage is limited to tick history that Bookmap has retained or can backfill. This cannot reconstruct fills absent from those sources, and aggregate minute candles cannot prove an individual print or wall fill. Missing history does not grant qualification.

`NORMAL_1R` uses a fixed 1R total budget without requiring fill history or a regular-session liquidity ledger. Existing same-direction positions and pending entries consume that budget; oversized requests are reduced and smaller requests remain smaller. Other entry rules remain in force.

Unavailable market time, a non-regular session, a stale execution session, or history catch-up in progress blocks openings in Strict and Proportional. Proportional and Normal 1R also block when there is no budget for one whole share. Repeated opening actions wait for a broker account read started after the preceding opening action finishes. Broker account visibility and market-order fill prices can lag: capped modes calculate planned risk from observed positions/orders and estimated entry prices, rather than guaranteeing realized loss or an eventual fill price.
