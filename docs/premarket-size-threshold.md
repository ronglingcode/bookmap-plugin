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

The policy uses Schwab's price-history endpoint with one-minute candles and extended-hours data enabled. It shares the native trading runtime's OAuth token and refresh handling. Requests cover 04:00 Eastern until 09:30 Eastern exclusive. Returned candles are deduplicated by timestamp and filtered to the requested range, because Schwab may return additional session candles. Empty or malformed responses are unavailable, rather than zero volume.

The background worker replaces the sum every minute before open. A successful fetch at or after open freezes the session's total; failed final fetches retry. The threshold does not use Massive history or Massive stream volume. Massive remains the source for other native market-data features.

Schwab's observed CRWV response on October 14, 2026 begins at 07:00 Eastern despite the 04:00 request and sums to 3,109,249 shares (15K threshold). Earlier Thinkorswim chart candles are not necessarily included by this endpoint. The threshold reflects the candles Schwab returns, without substituting another provider's early-session volume.

Charts on a different Eastern session date and explicitly configured replay mode use the 5K fallback. A delayed same-day chart is accepted; requests are bounded by chart time to avoid later premarket volume. A date change invalidates yesterday's total. Missing authorization or failed requests use 5K. Fetch failures are logged without credentials; the UI shows Schwab as the source or the unavailable reason.

The UI shows the threshold and premarket shares or the fallback reason. Percentile, top-five size, manual floor, and the former 10K alert cap do not affect attached charts. Size remains in the existing Bookmap depth/share units; no round-lot conversion is performed. Existing strict-greater-than size comparisons in the specialized monitors remain; labels and entry liquidity use greater-than-or-equal. Material alerts still qualify by crossing the threshold or an absolute delta strictly exceeding it.

Canonical observation (1K) and Composer trigger/confirmation policies are unchanged.
