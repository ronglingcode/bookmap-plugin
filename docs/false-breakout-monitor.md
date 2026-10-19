# False breakout monitor

Enable it per symbol with `"bookmapPatternsToMonitor": ["falseBreakout"]`.
An empty or omitted list disables it. Both the Vite socket and native plan loader
forward the list. It runs independently of SignalComposer and never submits orders.

The monitor uses Bookmap market timestamps and regular US equity hours
(09:30 inclusive–16:00 exclusive, America/New_York, weekdays), after initial
snapshot readiness. Premarket depth still present at the open remains available.

Its starting condition is buying through a qualifying offer:

1. Highlight the nearest qualifying offer strictly above the last traded price.
   Offer size must be strictly greater than the existing per-symbol premarket
   threshold (5K fallback). This offer-stage rule is separate from the shared
   4.5K bid cutoff.
2. Buying executions at the offer accumulate until its captured size is filled.
   A buying execution above it after executions at it also completes the breakout.
   Cancellation alone cannot arm bid monitoring. Once buying starts, remaining
   size may drop below the threshold while the captured fill completes.
3. After that breakout, use the exact same bid detection and rendering as
   `bidBreakdown`: **at least 4,500 displayed shares for more than one second**,
   **Level 1 ask strictly below the bid for one second** for an entry label,
   and **Level 1 bid at or above the old bid** for a reclaim/stop-out label.
   A trade print below the bid is not required. Label the next qualifying bid
   and stop entry scanning after **five distinct bid prices**. Reclaim tracking
   continues for all five levels.

See [shared bid rules](bid-breakdown-monitor.md) for boundary comparisons, timer
resets, callback ordering, and marker placement. The entry-liquidity gate used by
`bidBreakdown` does not also gate False Breakout; only the offer-breakout start differs.

Entry/reclaim markers remain for the session. A new market date resets the pattern
and count; a backward replay timestamp also discards depth and quotes and requires
fresh snapshot readiness. Repeated plan updates preserve progress. Disabling hides
the display and discards pending bid confirmation; reenabling preserves the offer
breakout state, completed entries, and the five-bid limit for the same date.
