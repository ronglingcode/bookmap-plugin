# October 26 execution lag investigation

Reviewed local `bmtrader-2026-10-26.log` and `bmtrader-2026-10-26-1.log`
through 07:07 Pacific. No live broker requests or account mutations were used.

## Evidence

- 51 mutation HTTP errors: 44 `Order in status REPLACED cannot be replaced`
  and seven `Order in status CANCELED cannot be replaced`.
- At 07:00:08.739, Flatten submitted 12 replacements. Nine were acknowledged
  by 07:00:09.843; three were rejected as already canceled. A second Flatten
  at 07:00:17.413 reused the same 12 IDs and all were rejected. The next
  changed order snapshot was logged at 07:00:18.417.
- Within 73 logged order snapshots, 39 ID/snapshot combinations contain both
  a terminal and a live status. For example, the 07:00:05.314 snapshot contains
  both `CANCELED` and `WORKING` copies of order `1008427899151`.
- Changed order snapshots during active trading are often about 10–14 seconds
  apart. Identical snapshots are suppressed, so these intervals alone cannot
  measure every poll's duration. No account-refresh failures, HTTP 429s, or
  Schwab disconnects appear in these logs.
- Other local blocks include missing exit pairs, closing quantity exceeding
  the cached position, and the existing requirement to mark an entry bid with I.
  The I-key restriction is a trading rule and is not a refresh failure.

## Code findings and changes

The recent pagination expansion is commit `929d40e1e6009863389fe2d718c36e3b3436524f`
(October 21), "fix paginated order history and duplicate execution exports".
Earlier native reads already had a top-level cap fallback; this change added
conservative nested-record counting and millisecond subdivision. A normal live
poll could consequently trigger many sequential order requests even with fewer
than 500 root orders.

Version 1.38 splits the paths explicitly. Live `getOrders` performs one GET for
today's range, with `maxResults=500`, and never subdivides it. Startup, periodic
and manual refresh, post-mutation/activity refresh, and entry cancellation all
use this path. Complete pagination is retained as `getOrderHistory`, called only
on demand by the CSV and both ThinkScript export formats. Export fetches and
projection run outside the live account lock; no history results replace live
account data. The preliminary subdivision-boundary cache from 1.37 was removed.

Order projection previously selected working copies before deduplicating legs.
A stale working leg could survive even when another copy of the same ID was
already terminal. The read now merges duplicate roots across overlapping pages,
preserving terminal observations and replacement children. Projection reconciles
IDs across all roots and nested trees before selecting active entries/exits.
The most advanced filled quantity controls remaining live quantities; broker
input objects are not changed.

Account-activity stream hints now bypass the ordinary polling throttle, like
post-mutation refreshes. Hints arriving during an in-flight read request one
urgent follow-up. Broker backoff remains enforced. File-only diagnostics record
order-query counts, live/journal mode, and read/refresh duration.

Regression tests cover terminal/live conflicts in either root order, fully
terminal brackets, partial-fill quantities, overlapping pages, fresh journal
reads, growing history, read failures, and activity hints during a read. Additional
checks prove single-page live reads at the cap, complete exports for all formats,
no live-cache replacement by exports, and live refresh during a blocked export.
Existing shared Java/TypeScript fixtures remain unchanged. The corresponding
ViteApp modules were inspected; these fixes are local to the native Bookmap
runtime and its broker-state projection.

## Verification and remaining limits

Build and test results are recorded in Gradle's local reports. Version 1.38 is
the release containing these fixes. Live improvement requires loading that JAR
and observing its new duration/query-count diagnostics. REST snapshots can still
lag broker activity, and reads are not atomic with subsequent fills or user
actions; the changes do not promise zero stale-order rejections. No automatic
resubmission of a rejected or uncertain mutation was added.
