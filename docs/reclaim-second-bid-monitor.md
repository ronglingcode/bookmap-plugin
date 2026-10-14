# Reclaim 2nd bid

Enable with `"bookmapPatternsToMonitor": ["reclaim2ndBid"]`. The legacy saved
value `falseBreakdown` also enables this renamed monitor.

The nearest unfilled bid is shown as **next bid**. It must be strictly above
5,000 and remain there continuously for more than one second of market time.
The monitor runs during regular US equity hours after snapshot readiness.

A selling execution at a qualifying bid counts immediately, including a partial
fill. Depth cancellation or reduction alone does not count. Unknown aggressor
trades count only when printed at the current Level 1 bid. A 250ms allowance
retains qualification when the depth depletion callback arrives before the trade.

Keep the two most recent distinct newly filled bid levels, labeled **fill 1**
(older) and **fill 2** (newer), with compact labels on the right of the heatmap.
When another qualifying bid fills, remove the oldest level, renumber the newer
one to fill 1, and label the new one fill 2. Subsequent fills at either tracked
level accumulate without changing its position or consuming another slot.
There is no session-wide three-bid limit.

This monitor only tracks bids. It produces no long or entry markers and submits
no orders. Level 1 quotes still locate the next bid and attribute unknown-side fills.

The two fill lines update as tracking rolls. Daily resets clear tracking. Replay seeks also clear
depth. Quotes can come from BBO callbacks or valid depth-derived best bid/ask.
