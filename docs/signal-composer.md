# SignalComposer operator guide

SignalComposer combines Bookmap depth and trade observations into advisory LONG/SHORT markers. It does not submit orders, calculate position size, select tradebooks, play an entry sound, or send a new WebSocket message. Native/manual execution, legacy scored badges, and Cairo export retain their existing behavior.

## Enable and configure

The default is enabled. **SignalComposer (advisory)** in addon settings is a shared switch across eligible charts, independent of **Bookmap Pattern Automation** and tradebook eligibility. All default rules are defined in the tracked `SignalComposerConfig.java` and compiled into the plugin JAR. No rule file needs to be created or copied between machines. The [tracked template](../config/signal-composer.template.json) documents these defaults and is tested against the compiled values.

The loader does not automatically read `%USERPROFILE%\bmtrader\signal-composer.json`. An optional file is used only when explicitly selected with `-Dbmtrader.signalComposerConfig=C:\absolute\path\signal-composer.json`; a blank property uses the compiled defaults. Uncheck the advisory checkbox to disable the feature. An explicitly selected malformed file disarms it, with the reason in the log and tooltip. Optional files are limited to 16 KiB. Empty `symbols` means all attached symbols; a list filters symbols after normalization. Quantities are equity shares, prices are integer Bookmap ticks, percentage hold distances use wall price, and durations are milliseconds.

Rules load once at the first attachment. Later attachments share that immutable configuration revision and retain the user's current checkbox state. **Stop every bmtrader attachment, then reattach them to reload the rules.** Restart Native Trading does not reload composer rules. This feature does not write your configuration or secrets.

## Signal rules and defaults

LONG requires a bid hold: price tests a persistent bid and rebounds, or a persistent bid reappears or steps up. SHORT requires a bid failure: inferred withdrawal or consumption followed by a breakdown. Offer evidence alone shows waiting context and creates no signal. Bare offer growth has UNKNOWN meaning until an actual offer hold completes.

Bid Reappear, Bid Step Up, Offer Reappear, and Offer Step Down all require the replacement wall price to be strictly above the observed session low and strictly below the observed session high. Both bounds must be known; walls at either extreme or outside the range do not qualify. Delayed step revisions use the same check. These bounds come from trades observed by the engine, so starting or resetting observation during the day does not recover earlier extremes.

Confirmation is optional for a normal 5K trigger. The strongest compatible local offer observation determines the requirement; sizes are never added together.

| Confirmation strength | Size versus 5K confirmation baseline | Required bid trigger |
| --- | --- | --- |
| NONE / BELOW_NORMAL | Absent / less than 5K | 5K |
| NORMAL | At least 5K | 5K |
| STRONG | At least 10K | 5K |
| VERY_STRONG | At least 25K | 4K |
| EXCEPTIONAL | At least 50K | 3K |

The absolute minimum is 3K regardless of confirmation. A 60K bearish offer observation can therefore qualify a nearby 3K bid failure as SHORT. A 3K bid failure can also wait for sufficient confirmation arriving later. A 5K trigger validated first keeps its original decision and receives later evidence as a revision of the same signal.

Matching uses the same chart/epoch, up to 300 seconds (five minutes) before or after the trigger, already-observed evidence no older than five minutes, and at most 20 ticks of separation. The offer must be at or above the bid trigger, allowing two ticks of price noise. Active candidates expire after five minutes, or invalidate on opposing local bid behavior, trigger-record eviction, or drift greater than 20 ticks. History is retained for 300 seconds (five minutes), capped at 2,048 events and 64 candidates. Retention is fixed in code; any existing `historyRetentionMs` JSON value is ignored and needs no edit.

## Pattern catalog and meanings

The current observer emits the following **11 pattern types**. The identifiers match
[`PatternEventType`](../src/main/java/com/bookmap/plugin/rong/patterns/PatternEventType.java).
A pattern is an observation of displayed liquidity and trades; its meaning determines
how SignalComposer uses it, rather than guaranteeing the next price move.

| Pattern (event identifier) | What was observed | Meaning and composer role |
| --- | --- | --- |
| **Bid Hold** (`BID_HOLD`) | Price approaches a persistent bid from above within 0.05% of wall price, then rebounds at least 0.1% of wall price from the test low. Subsequent trades confirm 500 ms at/above the rebound threshold within a 15-second interaction. The bid remains present and unbroken. | `BID_HOLD`: tested support with a confirmed rebound; can trigger LONG. |
| **Bid Reappear** (`BID_REAPPEAR`) | After a bid wall is cleared by probable consumption, a persistent bid wall appears at the same or higher price within five minutes, with at least 50% of the reference size. The best ask remains above the new wall. | `BID_HOLD`: bullish quote defense; can trigger LONG. |
| **Bid Step Up** (`BID_STEP_UP`) | A persistent bid wall appears above a reference bid wall from the preceding five minutes, with at least 50% of its size. The new wall is above the observed session low and the best ask remains above it. The reference can be active or previously cleared by probable consumption. | `BID_HOLD`: support moves higher; can trigger LONG. |
| **Bids Cancelled** (`BIDS_CANCELLED`) | A qualified bid wall loses at least 90% of its immediately pre-clear size, the loss remains stable for 500 ms, known sell trades explain at most 10%, coverage is usable, and no likely relocation is identified. | `BID_FAIL`: inferred support withdrawal; can trigger SHORT. The name does not prove an exchange cancellation. |
| **Bid Breakdown** (`BID_BREAKDOWN`) | Known sell trades explain at least 70% of a qualified bid wall's loss, followed by an actual trade at least one tick below within three seconds. | `BID_FAIL`: support is consumed and price breaks below; can trigger SHORT. |
| **Offer Reappear** (`OFFER_REAPPEAR`) | After an offer wall is cleared by probable consumption, a persistent offer wall appears at the same or lower price within five minutes, with at least 50% of the reference size. The best bid remains below the new wall. | `OFFER_BEARISH_CONFIRMATION`: renewed resistance; confirms a compatible SHORT bid trigger or shows SHORT waiting context. |
| **Offer Step Down** (`OFFER_STEP_DOWN`) | A persistent offer wall appears below a reference offer wall from the preceding five minutes, with at least 50% of its size. The new wall is below the observed session high and the best bid remains below it. The reference can be active or previously cleared by probable consumption. | `OFFER_BEARISH_CONFIRMATION`: resistance moves lower; confirms a compatible SHORT bid trigger or shows SHORT waiting context. |
| **Offer Hold** (`OFFER_HOLD`) | Price approaches a persistent offer from below within 0.05% of wall price, then retreats at least 0.1% of wall price from the test high. Subsequent trades confirm 500 ms at/below the retreat threshold within a 15-second interaction. The offer remains present and unbroken. | `OFFER_BEARISH_CONFIRMATION`: tested resistance with a confirmed retreat; confirms a compatible SHORT bid trigger or shows SHORT waiting context. |
| **Offer Size Increasing Hold** (`OFFER_SIZE_INCREASING_HOLD`) | An offer grows at least 25% relative to its interaction baseline, then meets the offer-hold conditions with fresh retreat confirmation after growth in the same interaction. | `OFFER_BEARISH_CONFIRMATION`: growing resistance followed by a completed hold; upgrades the same hold episode. |
| **Offer Breakout** (`OFFER_BREAKOUT`) | Known buy trades explain at least 70% of a qualified offer wall's loss, followed by an actual trade at least one tick above within three seconds. | `OFFER_BULLISH_CONFIRMATION`: resistance is consumed and price breaks above; confirms a compatible LONG bid trigger or shows LONG waiting context. |
| **Offer Size Increase** (`OFFER_SIZE_INCREASE`) | A persistent offer grows at least 25% relative to its baseline; no hold is required for this observation. | `UNKNOWN`: recorded in history and inspection, with no directional confirmation or bid-size reduction. Growth alone does not establish bearish meaning. |

The table gives current default detector thresholds. Configurable detector values
come from the [rule template](../config/signal-composer.template.json). Reappear/step
reference windows and their 50% comparison are fixed by their pattern definitions.
The session extremes used by step patterns come from trades observed by this
attachment since its last reset; they are not independently fetched daily extremes.

The enum also defines `UNKNOWN_BID_LOSS` and `UNKNOWN_OFFER_LOSS`, both with
`UNKNOWN` meaning. **The current observer does not emit these two types.** Ambiguous
wall losses can produce diagnostics, but do not establish a directional pattern.

### Price ranges tracked by each pattern

These are detector eligibility ranges, before SignalComposer applies its separate
signal-matching and price-drift checks. “All tracked levels” means there is no
additional daily-range or percentage-distance filter; size, persistence,
attribution, coverage, and buffer limits still apply. Tick distances below are the
current defaults and can be changed in the rule template.

For the observer, `L` and `H` are the lowest and highest trade prices observed by
this attachment since its last reset during the regular session. They can differ
from the full day's low and high. `W` is the pattern's wall price, `R` the reference
wall price, and `T` a subsequent trade price.

| Pattern | Price range tracked / required price interaction | Minimum tracked size |
| --- | --- | --- |
| **Bid Hold** (`BID_HOLD`) | All tracked bid levels; approach trade in `[W, W + 0.0005 * W]`, then rebound at least `0.001 * W` from the test low. A trade below `W` or best ask at/below `W` breaks the interaction. | 3K displayed shares, inclusive |
| **Bid Reappear** (`BID_REAPPEAR`) | Replacement wall strictly inside `L < W < H`, with `W >= R` and best ask above `W`. No maximum distance from the reference wall or current price at detection. | 3K displayed shares, inclusive |
| **Bid Step Up** (`BID_STEP_UP`) | New wall strictly inside `L < W < H`, with `W > R` and best ask above `W`. The same range applies to delayed revisions. No maximum step distance at detection. | 3K displayed shares, inclusive |
| **Bids Cancelled** (`BIDS_CANCELLED`) | All tracked bid levels. This independent observer event has no day-high/day-low or 2% filter; the chart's BID PULL filter below does not apply to it. | 3K shares removed, inclusive |
| **Bid Breakdown** (`BID_BREAKDOWN`) | All tracked bid levels; requires a subsequent print `T <= W - 1 tick`. No daily-range filter or maximum distance below the wall. | 1K shares removed, inclusive |
| **Offer Reappear** (`OFFER_REAPPEAR`) | Replacement wall strictly inside `L < W < H`, with `W <= R` and best bid below `W`. No maximum distance from the reference wall or current price at detection. | 3K displayed shares, inclusive |
| **Offer Step Down** (`OFFER_STEP_DOWN`) | New wall strictly inside `L < W < H`, with `W < R` and best bid below `W`. The same range applies to delayed revisions. No maximum step distance at detection. | 3K displayed shares, inclusive |
| **Offer Hold** (`OFFER_HOLD`) | All tracked offer levels; approach trade in `[W - 0.0005 * W, W]`, then retreat at least `0.001 * W` from the test high. A trade above `W` or best bid at/above `W` breaks the interaction. | 3K displayed shares, inclusive |
| **Offer Size Increasing Hold** (`OFFER_SIZE_INCREASING_HOLD`) | Same price range and approach/retreat requirements as Offer Hold; growth does not widen the range. | 3K displayed shares, inclusive |
| **Offer Breakout** (`OFFER_BREAKOUT`) | All tracked offer levels; requires a subsequent print `T >= W + 1 tick`. No daily-range filter or maximum distance above the wall. | 1K shares removed, inclusive |
| **Offer Size Increase** (`OFFER_SIZE_INCREASE`) | All qualified tracked offer levels. No approach to current price, daily-range filter, or percentage-distance limit is required. | 3K displayed shares, inclusive |
| **Unknown Bid Loss** (`UNKNOWN_BID_LOSS`) | Not emitted by the current observer; no active tracking range. | Not emitted (3K policy if admitted) |
| **Unknown Offer Loss** (`UNKNOWN_OFFER_LOSS`) | Not emitted by the current observer; no active tracking range. | Not emitted (3K policy if admitted) |

After detection, bid trigger candidates use `maxTriggerDriftTicks` (default 20
ticks) relative to current market price. Compatible offer confirmation must be
within `maxPriceDistanceTicks` (default 20 ticks) of the bid trigger and at or
above it, with `directionalPriceToleranceTicks` (default two ticks) allowed below
it. Offer waiting context uses the same 20-tick/two-tick limits relative to
current market price. These checks do not restrict the underlying depth tracking
or remove distant raw observations from history.

### Chart order-wall alerts and cancellation ranges

The chart's order-wall change tracker is separate from the observer catalog above.
It displays bid/offer cancellations as **BID PULL / OFFER PULL** and does not emit
a SignalComposer `OFFERS_CANCELLED` event.

Here `dayLow` and `dayHigh` are the shared regular-session day levels, which can
include minute history, and `P` is the latest traded stock price. The cancellation
band is the inclusive interval `[dayLow - 0.02 * P, dayHigh + 0.02 * P]`.
Eligibility is frozen when the pending depth change starts. Cancellation alerts
are suppressed if a valid stock price or day range is unavailable; an excluded
change is not saved for later admission when the band expands. The underlying
book remains tracked for size thresholds, trade attribution, and move pairing.

| Chart pattern / event | Price range tracked for emitted alerts | Minimum / material-size rule |
| --- | --- | --- |
| **Bid canceled / BID PULL** (`REDUCED` or `REPLACED_SMALLER`, without trade consumption) | Only the inclusive cancellation band above. | Material-change rule at `Q` (floor 5K); see below. |
| **Offer canceled / OFFER PULL** (`REDUCED` or `REPLACED_SMALLER`, without trade consumption) | Only the inclusive cancellation band above. | Material-change rule at `Q` (floor 5K); see below. |
| **Bid added** (`ADDED`) | All tracked bid levels; no cancellation-band filter. | Material-change rule at `Q` (floor 5K); see below. |
| **Offer added** (`ADDED`) | All tracked offer levels; no cancellation-band filter. | Material-change rule at `Q` (floor 5K); see below. |
| **Bid increased** (`INCREASED`) | All tracked bid levels; no cancellation-band filter. | Material-change rule at `Q` (floor 5K); see below. |
| **Offer increased** (`INCREASED`) | All tracked offer levels; no cancellation-band filter. | Material-change rule at `Q` (floor 5K); see below. |
| **Bid moved up** (`BID_MOVED_UP`) | Any paired tracked bid levels with destination above source; no maximum price distance or cancellation-band filter. | Material-change rule at `Q` (floor 5K); see below. |
| **Bid moved down** (`BID_MOVED_DOWN`) | Any paired tracked bid levels with destination below source; no maximum price distance or cancellation-band filter. | Material-change rule at `Q` (floor 5K); see below. |
| **Offer moved up** (`OFFER_MOVED_UP`) | Any paired tracked offer levels with destination above source; no maximum price distance or cancellation-band filter. | Material-change rule at `Q` (floor 5K); see below. |
| **Offer moved down** (`OFFER_MOVED_DOWN`) | Any paired tracked offer levels with destination below source; no maximum price distance or cancellation-band filter. | Material-change rule at `Q` (floor 5K); see below. |
| **Bid breakdown** (`BID_BREAKDOWN`) | All tracked bid levels where trade-driven loss meets the existing enabled wall-break rule; no cancellation-band filter. | Material-change rule at `Q` (floor 5K); see below. |
| **Offer breakout** (`OFFER_BREAKOUT`) | All tracked offer levels where trade-driven loss meets the existing enabled wall-break rule; no cancellation-band filter. | Material-change rule at `Q` (floor 5K); see below. |
| **Trade-driven size decrease** (`REDUCED` or `REPLACED_SMALLER`, with trade consumption) | All tracked levels on either side; excluded from the cancellation filter even if displayed with a pull label. | Material-change rule at `Q` (floor 5K); see below. |

The chart tracker uses a separate effective threshold `Q`: the larger of 5K
shares and the fifth-largest displayed level size, capped at 10K. A material
change crosses `Q` or changes size by more than `Q`; there is no independent
minimum on the reported delta, so a threshold crossing may report a smaller
change. Moves must pair material changes of similar size. These chart thresholds
remain unchanged by the observer's 1K/3K pattern minimums.
The chart also marks an event as an active-liquidity alert only when its bid price
is strictly above `dayLow`, or its offer price is strictly below `dayHigh`, and
the change is material and not trade consumption. This existing classification
does not replace the cancellation band and does not restrict all emitted events.

Only `BID_HOLD` and `BID_FAIL` create signal candidates. Directional offer meanings
provide confirmation and waiting context; they cannot create completed signals on
their own. `UNKNOWN` events carry no directional meaning. All triggers still need
the size, locality, readiness, and timing rules above. An opposing local bid trigger
of at least 3K can invalidate an active candidate.

Implementation references: [observer wiring](../src/main/java/com/bookmap/plugin/rong/patterns/PatternObservationEngine.java),
[reappear](../src/main/java/com/bookmap/plugin/rong/patterns/ReappearPatternDefinition.java),
[step](../src/main/java/com/bookmap/plugin/rong/patterns/StepPatternDefinition.java),
[bid failure](../src/main/java/com/bookmap/plugin/rong/patterns/BidFailureDetector.java),
[offer interaction](../src/main/java/com/bookmap/plugin/rong/patterns/OfferInteractionDetector.java),
and [composer](../src/main/java/com/bookmap/plugin/rong/signal/SignalComposer.java).

## What the detectors establish

The observer retains raw depth from 1K shares so it can detect small Offer Breakout
and Bid Breakdown events. Those two completed patterns require at least 1K removed
shares; every other emitted observer pattern requires at least 3K shares, using
displayed wall size or removed size according to its size basis. The per-pattern
minimums are inclusive and listed in the range table above. They are fixed in code,
independent of the legacy percentile threshold and of signal-validation thresholds;
existing `observationFloorSize` JSON values are ignored. Walls qualify after 500 ms.
Snapshot levels still need persistence; a snapshot itself is not a signal. Events
below their pattern's minimum are not emitted or admitted to pattern history.

Every event has an absolute size category, independent of directional meaning and the configurable confirmation baseline:

| Pattern size category | Shares |
| --- | --- |
| Least significant | 1,000–2,999 |
| Below normal | 3,000–4,999 |
| Normal | 5,000–9,999 |
| Strong | 10,000–24,999 |
| Very strong | 25,000–49,999 |
| Exceptional | 50,000+ |

Only Offer Breakout and Bid Breakdown can enter the Least significant band and
appear in five-minute history, semantic logs, and per-stock inspection counts.
They cannot become bid candidates, invalidate candidates, or lower the required
bid size. Small Offer Breakout evidence can show LONG waiting context with the
normal bid requirement. Other pattern episodes first enter history once their
measured size reaches 3K; normal signal rules still apply. The 2,048-event cap may
evict older records sooner as observation volume increases.

| Behavior | Required observation |
| --- | --- |
| Bid withdrawal | At least 90% loss of immediately pre-clear size, stable for 500 ms; known sell volume explains at most 10%; intact coverage and no likely relocation |
| Bid breakdown | Known sell volume explains at least 70% of loss, then an actual trade at least one tick below within three seconds |
| Bid hold | Persistent bid approached from above within 0.05% of wall price; rebound at least 0.1% of wall price from the test low, confirmed by trades over 500 ms within 15 seconds; bid remains present and unbroken |
| Offer hold | Persistent offer approached from below within 0.05% of wall price; retreat at least 0.1% of wall price from the test high, confirmed by trades over 500 ms within 15 seconds; offer remains present and unbroken |
| Offer growth plus hold | At least 25% growth, followed by fresh offer-hold retreat confirmation in the same interaction; upgrades one hold episode |
| Offer breakout | Known buy volume explains at least 70% of loss, then an actual trade at least one tick above within three seconds |

Trade attribution uses the same price, known aggressor side, a two-second lookback, and the clear-decision interval. Probable moves pair same-side replacement within 500 ms and 10% size tolerance. Unknown aggressor, ambiguous losses, pruned evidence, or buffer discontinuities do not prove withdrawal. “Inferred withdrawal” is an observation-based attribution, not proof of an exchange cancellation; quote defense is not proof of executed absorption.

Wall phases and relocation/reference data are bounded at 4,096; trade attribution is bounded at 8,192 trades. Observation buffer overflow clears dependent composition state, begins a new epoch, and requires fresh readiness. Diagnostics identify the reason.

### Percentage hold rules and migration

Bid Hold and Offer Hold share `detectors.holdApproachRatio = 0.0005` (0.05%),
`holdRetreatRatio = 0.001` (0.1%), `holdConfirmationMs = 500`, and
`interactionWindowMs = 15000`. Percentage distances use the wall price, with no
fixed tick minimum. Prices are still observed on the instrument's trade grid:
at $650 with cent ticks, a $0.325 approach limit admits $0.32 but not $0.33;
a $0.65 retreat is measured from the test extreme, not from the wall.
For a $650 offer tested at $649.90, trades must reach $649.25 or below and confirm
the 500 ms interval. The bid mirror tested at $650.10 must reach $650.75 or above.

A new test high/low or a return inside the required retreat/rebound threshold
resets confirmation. Time or depth callbacks alone cannot confirm a hold; a
later qualifying trade must establish the full interval. A completed hold
rearms after price leaves the approach zone and returns. Removal, a drop below
3K, quote/trade crossing, replay seek, or coverage reset discards pending tests.
No session-high/session-low restriction applies to these hold observations.

`OFFER_REJECTION` and `OFFER_SIZE_INCREASING_REJECTION` are now `OFFER_HOLD` and
`OFFER_SIZE_INCREASING_HOLD`. Update consumers of these event names. Legacy
`approachDistanceTicks`, `rejectionDistanceTicks`, and `rejectionHoldMs` settings
are ignored. Normal startup uses the compiled defaults on every machine. If
you explicitly opt into a custom file, use the new keys; its existing
`interactionWindowMs` overrides still apply.
The evidence field `rejectionTimeNs` retains its serialized name and now records
the start of retreat/rebound confirmation. `testExtremeTick` in evidence metadata
records the actual test high/low. Existing Bid Reappear and Bid Step Up still
provide their own BID_HOLD evidence independently of the new tested Bid Hold.

The composer's separate 20-tick confirmation-distance and trigger-drift defaults
still apply. A raw hold observation can therefore appear in retained history
while being outside the current local signal/context range.

## Read the chart and logs

The per-stock floating **Trade** window includes a scrollable **Signal Composer** panel at the bottom. It refreshes once per second and separates **Long** and **Short**, each with **Bid patterns** and **Offer patterns**. Each group shows its two newest retained semantic events, ordered by occurrence time, as compact selectable text lines in the form `09:31:52: 3.4K @ 648, Bids cancelled`, using New York market time and full pattern names. Quantities use compact lots, truncated to one decimal for K/M/B suffixes. There are no tables or column headers; lines fit the viewport and wrap only when needed in a narrow window. Event revisions replace the existing event rather than adding a row. Expired events are greyed out without a repeated Expired label; hover to inspect their status and exact quantity in lots. Hover over an event for its status, including invalid, out-of-range, below-minimum, pending, or validated states. Unknown-direction events are not assigned to Long or Short. Current directional requirements and market-time countdowns appear above the event groups. Normal operation has no Scanning label; disabled, unavailable, readiness, and session restrictions appear as notices. Expand **Diagnostics** for enablement, symbol/config eligibility, configured source mode, rule revision, market timestamp, epoch, latest raw observation (including unknown meaning), retained evidence counts, all retained candidate details, and reset/diagnostic information. Candidate details are retained within the configured 64-candidate capacity until reset or eviction, even after the chart badge disappears; event rows use the configured history retention. Diagnostic text can be selected and copied, and event text can be selected and copied with Ctrl+C. Inspection never advances market time or expires state, so pausing replay freezes the displayed market-time countdowns. The configured Cairo `sourceMode` label does not automatically detect Bookmap replay. Observer-only attachments do not create the floating Trade window.

Completed badges show direction, trigger behavior and size, the confirmation band at first validation, and normal/applied requirements. They anchor horizontally at **validation time**, vertically at the trigger's tick price. Full explanations distinguish trigger occurrence, observation/validation time, confirmation ordering, locality, and attribution.

The separate amber **waiting context** area shows LONG and/or SHORT evidence, the missing bid meaning and required quantity, local price, and remaining market-time window. It is not a completed signal. Pausing replay freezes this window. Marker visibility instead uses 30 seconds of receipt time, with at most 20 markers per chart. Later evidence replaces one marker without refreshing its original visibility deadline or rewriting initial acceptance.

The existing bmtrader log queue records one concise **Advisory LONG/SHORT** summary per first validation. Full explanations, raw semantic observations, revisions, pending/invalid/expired states, context changes, and reset diagnostics go to the local log file. Startup details identify the rule revision and the existing Cairo `sourceMode`. Composer output is not exported over Cairo/WebSocket in this version.

## Credential-free replay

Use the existing `%USERPROFILE%\bmtrader\cairo-observation.json` observer mode, for example:

```json
{
  "enabled": true,
  "observerOnly": true,
  "sourceMode": "replay",
  "symbols": ["TEST"],
  "detectors": []
}
```

This allows addon attachment and indicator/composer settings without `secrets.json`. Native broker startup, native account/action settings, chart action registration, and the floating trade-button window are omitted for that attachment. Use the actual recording symbol in your local settings. `sourceMode` comes from Cairo configuration; realtime-start alone does not imply a live provider. Ordinary attachment with missing secrets remains inactive.

Signals require a completed Bookmap snapshot/realtime readiness callback, usable market timestamps, and New York regular hours from 09:30 inclusive to 16:00 exclusive. They expire/reset outside regular hours and at session changes. Fallback wall-clock timestamps cannot establish rules.

A genuine backward callback timestamp is a replay seek: candidates, events, detector state, and visible context/markers clear. Fresh readiness is required. Pre-seek legacy depth is not reused as a snapshot; after a seek/gap, only newly received depth can seed this attachment's observer, including after toggles. If Bookmap does not deliver fresh readiness/depth, stop and reattach the addon. Disabling clears composition and consumes no observation callbacks while disabled.

Current Cairo captures can omit 3K walls because their older evidence floor is higher. Do not interpret a capture lacking those callbacks as a test of the 3K detector. Unit fixtures cover the raw callback path; actual Bookmap layout, OpenGL upload, and replay behavior still require the manual smoke check in the [task checklist](signal-composer-tasks.md).

## Build and verification

Run `gradlew.bat build` with a compatible JDK for the Gradle wrapper. Source/target remain Java 11. The release filename convention is `build/libs/lingrong1988_bmtrader_<version>.jar`; current project version is 1.32. The build runs unit tests, independent native-engine compilation, and smoke tests against the obfuscated artifact. It does not publish the artifact or enable local composer/trading configuration.
