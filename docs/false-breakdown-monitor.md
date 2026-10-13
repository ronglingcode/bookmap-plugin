# False breakdown monitor

Enable per symbol with `"bookmapPatternsToMonitor": ["falseBreakdown"]`.
Both patterns can be enabled with `["falseBreakout", "falseBreakdown"]`.
Each owns independent state and a separate count.

This pattern starts directly with bid monitoring at the regular market open.
It highlights the closest bid at or below the last traded price that has remained
strictly above 5,000 continuously for more than one second of market time.
When price trades below it, a compact `bid 1`, `bid 2`, or `bid 3` marker remains
at the broken bid's price and the breakdown trade's time. The same bid price
counts only once per session. After three distinct bids, monitoring stops.

Qualification, partial-fill handling, cancellation expiry, regular-session
hours, daily reset, and replay-seek reset follow the false breakout monitor.
This monitor does not require an offer breakout and never submits orders.
