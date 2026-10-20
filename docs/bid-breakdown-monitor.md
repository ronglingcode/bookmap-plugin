# Bid breakdown monitor

Enable per symbol with `"bookmapPatternsToMonitor": ["bidBreakdown"]`.
It runs independently of SignalComposer and only displays advisory labels.
It never submits an entry or stop order.

The monitor waits for snapshot readiness, regular US equity hours
(09:30 inclusiveâ€“16:00 exclusive, America/New_York, weekdays), and the existing
strict entry liquidity condition: accumulated executions at one bid/offer wall
since market open must meet the symbol's premarket size threshold. It uses the
same filled-wall ledger as the entry rules, including premarket tiers, fallback,
and equality. Canceled displayed size and later price crossings cannot qualify.
Different wall episodes, prices, or sides are never added together. No prospective
stop-trigger or session-range exception applies.
See [the entry liquidity rule](premarket-size-threshold.md#entry-liquidity-rule).

`bidBreakdown` and `falseBreakout` use the same `BidBreakdownMonitor` bid engine
and the same chart renderer. False Breakout adds only its offer-breakout starting
condition; Bid Breakdown uses the entry-liquidity starting condition above.

Once eligible, both patterns follow these rules:

1. Label the nearest unused bid at or below Level 1 bid as **next bid**. If no
   Level 1 quote is available, use the latest trade as the selection ceiling.
   Displayed size must stay **at least 4,500 shares continuously for more than
   one second** of Bookmap market time. Exactly 4,500 and exactly 5,000 qualify.
   Before qualification, updates at or above 4,500 preserve persistence and a drop
   below 4,500 resets it. Once selected, a bid can shrink through partial fills
   without being treated as cleared.
2. The tracked bid's **depth clearing to zero** starts a fixed **15-second
   breakdown window**, measured in Bookmap market time. Quote movement and
   partial size reductions do not start this timer or permit an entry. Keep
   tracking that original level even if the ask is still at/above it, its wall
   disappears, or a closer bid appears. A recovery above the level and another
   breakdown do not restart or extend this window.
   Within that window, a valid Level 1 ask must stay **strictly below the old
   bid for one continuous second**. Confirmation at exactly the 15-second
   deadline qualifies; confirmation after it does not. An ask at/above the
   level or an invalid quote resets the one-second confirmation, while the
   original 15-second deadline remains fixed. If the ask was already below
   before the clear, confirmation starts at the clear time; earlier time does
   not count. No trade print is required. Duplicate clear callbacks, refills,
   and subsequent clears cannot extend an already-running window.
   An unconfirmed attempt expires after 15 seconds and selection resumes.
   If the old wall is still present, it must establish fresh persistence before
   it can be selected for a new attempt. Expiry does not consume an entry slot.
3. At confirmation, place **entry 1** through **entry 5** at the old bid price
   and confirmation time. Immediately begin locating the next unused bid.
4. For each signaled level, the first valid Level 1 bid at or above it adds
   **reclaim N · stop out**, anchored at that level and reclaim time. This
   remains active for all five signaled levels after entry scanning stops.

Bid persistence can accumulate from observed depth before the starting condition
is met, but no bid is highlighted and no ask-confirmation timer starts until the
pattern is eligible. Completed initial snapshots alone do not imply persistence.

Each price can generate only one entry per session. After five distinct entries,
there is no next-bid line. Entry/reclaim markers remain for the session. Disabling
hides labels and clears pending confirmation, while preserving completed entries
and the five-entry limit. A new session date clears entries; a backward replay
seek also clears depth and quotes and requires fresh snapshot readiness.
Pre-gate price breaks and elapsed time cannot produce retroactive entries.
