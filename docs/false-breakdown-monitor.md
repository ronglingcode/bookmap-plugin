# False breakdown monitor

Enable per symbol with "bookmapPatternsToMonitor": ["falseBreakdown"].
Both patterns can be enabled with ["falseBreakout", "falseBreakdown"].
Each owns independent state and a separate count. No orders are submitted.

The monitor uses regular US equity hours, market timestamps, and the initial
snapshot readiness gate. A bid qualifies after staying strictly above 5,000
continuously for more than one second.

Each of up to three distinct bid prices has its own lifecycle:

1. Highlight the nearest qualified bid with a compact, right-aligned bid 1,
   bid 2, or bid 3 label. Partial fills retain an already-qualified bid.
2. When depth reports that tracked level empty, remove its live label and
   immediately track the next qualified bid. Clearing alone produces no marker.
3. A subsequent valid Level 1 quote whose ask is strictly below that cleared
   bid's price confirms breakdown. Leave a breakdown N marker at the bid price
   and the quote's timestamp. An equal ask or a trade print below does not qualify.
4. After confirmed breakdown, a subsequent Level 1 bid strictly above the level
   confirms long reclaim. Leave a green long N marker at the level and reclaim
   timestamp. An ask above, a bid equal, or a trade print above does not qualify.

Cleared levels wait for quotes independently of the currently highlighted bid.
After three clears, no new bids are tracked, but pending breakdowns and reclaims
still complete. Each level produces its breakdown and reclaim marker only once.
A refilled bid price cannot consume another slot in the same session.

Daily resets clear the phases and count. Backwards replay timestamps also clear
the old depth. Disabling hides the display; reenabling preserves recorded phases.
