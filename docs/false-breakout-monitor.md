# False breakout monitor

Enable it per symbol in the trading plan:

```json
"bookmapPatternsToMonitor": ["falseBreakout"]
```

The field is required in trading plans; use an empty list to disable the monitor.
Older saved plans without it are treated as having an empty list. Both the Vite socket and
the native plan loader forward the list. This monitor runs independently of the
Signal Composer setting and never submits orders.

The monitor uses Bookmap market timestamps and regular US equity hours
(09:30–16:00 America/New_York, weekdays). Premarket depth is available at the
open; signals require the initial snapshot to be complete. The threshold is
strictly greater than 5,000 in Bookmap's raw depth/trade size units.

1. Highlight the closest qualifying offer strictly above the last traded price.
   Recompute the highlight as depth and price change. Buying executions at the
   tracked offer accumulate until its captured size is filled; an execution
   above it after executions at it also completes the breakout. A cancellation
   alone does not advance the phase. Once execution starts at the offer, allow
   its fill to finish even if its remaining size drops below the threshold.
2. A bid qualifies only after staying strictly above 5,000 continuously for
   more than one second of market time. Size updates above 5,000 preserve the
   timer; dropping to or below 5,000 restarts it. Highlight the closest qualified
   bid at or below the last traded price.
   A trade strictly below the tracked bid produces a compact **bid 1** through
   **bid 5** marker pointing at that trade's time and price. These mark bid
   breakdowns; they do not mean the trader must enter a position. Partial fills
   below the size threshold briefly retain a mature bid while selling continues.
   Removal allows 250ms for depth/trade callback ordering; selling at the tracked
   bid allows one second for continued partial fills. Canceled bids then expire
   instead of creating entries on unrelated later price crossings.
   A depth removal alone does not signal.
   Each bid price can signal only once during the session, even if it refills.
   After five distinct bid breakdowns the highlight disappears and no more markers are added.

Markers remain on the chart for that session. A new market date resets the
pattern and count; a backwards replay timestamp also discards the old depth.
Repeated plan updates do not restart the monitor. Disabling it hides its display;
reenabling it on the same date preserves the phase and five-bid limit.
