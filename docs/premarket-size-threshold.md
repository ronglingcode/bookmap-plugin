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
