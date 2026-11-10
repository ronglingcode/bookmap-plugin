# Dedicated pattern events

Bookmap detects patterns and exports observations. There is no Signal Composer,
LONG/SHORT decision engine, confirmation scoring, waiting context, composer chart
badge, settings checkbox or Trade-window composer panel. The dedicated pattern
monitors and their existing chart annotations remain. No AI consumer is built here.

## Detection and lifecycle

`PatternObservationEngine` runs the existing dedicated detectors for wall breaks,
withdrawals, bid/offer interactions, reappearance and stepping. `PatternEventPipeline`
passes their `PatternEvent` observations directly to the event snapshot and local
log. It does not impose trigger-size relaxation or combine events into trade signals.
Existing detector thresholds and classification meanings are unchanged.

Per-chart epochs, readiness, regular-session market time, coverage-gap handling,
replay seeks and stop/reattach cleanup remain. Missing or fallback timestamps do
not establish observations. Seek/gap clears events and requires fresh readiness;
old depth is not reused to repair missing coverage.

## Order fast growth: continuous additions on either side

`OrderFastGrowthDetector` emits `BID_FAST_GROWTH` or `OFFER_FAST_GROWTH`
for a wall that keeps growing over several seconds. A single jump followed by
unchanged size does not qualify. It measures displayed aggregate quantity at one
side/price, not an identifiable individual order. No price touch or bounce is required.

Initial configurable defaults (to be tuned in replay):

| Detector setting | Default |
| --- | --- |
| `fastGrowthMinUpdates` | 5 increases at distinct market timestamps |
| `fastGrowthMinDurationMs` | 2,000 ms between first and latest increase |
| `fastGrowthMaxGapMs` | 500 ms between successive increases |
| `fastGrowthMaxSingleStepRatio` | At most 80% of total growth in one timestamp's additions |
| `fastGrowthRatio` | At least 50% total growth |
| `fastGrowthMinIncrease` | At least 5,000 added shares |
| `fastGrowthWindowMs` | Complete the growing sequence within 5,000 ms |

Detection processes **every Bookmap depth callback**; there is no one-second or
half-second sampling timer. The 500-ms setting limits gaps between actual
increases. The separate aggregate feed delivers snapshots every 500 ms.

All conditions must pass at the same level, side and wall phase. The baseline
must be at least 3,000 shares and is the quantity before the first increase.
Duration measures actual increases, not how long the baseline or final size sat
unchanged. Identical depth updates and timer callbacks do not add to the count
or extend continuity. Additions at the same timestamp are grouped as one step,
including when checking whether one jump dominates the sequence.

Examples using market-time offsets:

- **Qualifies:** 172K baseline → 210K at 0s → 240K at 0.5s → 270K at 1s → 300K at 1.5s → 334K at 2s.
- **Does not qualify:** 172K → 334K once, followed by seconds of unchanged size.
- **Does not qualify:** 172K → 332K at 0s → 332.5K at 0.5s → 333K at 1s → 333.5K at 1.5s → 334K at 2s; one jump dominates.
- **Does not qualify:** three additions all within 200 ms, or additions separated
  by gaps longer than the configured maximum.

Emit immediately when the continuous sequence qualifies; do not wait for growth
to stop. Continuing qualified additions revise the same episode ID. A decrease,
pull, phase change, excessive gap or an update beyond the growth window starts
fresh observation. State is constant-size per tracked level. Replay/session/gap
resets clear growth state. The former `fastGrowthConfirmationMs` plateau setting
is retired and ignored; it is not evidence of continuous growth.

Evidence includes baseline and measured current size, added quantity, growth
fraction, `growthIncreaseCount`, `growthLargestStepSize`, and the first/latest
increase timestamps, duration and `growthMaxGapNs` (nanoseconds exported as strings).
`eventTime` is when the full pattern first qualifies; `observedAt` records detection
or a later revision. Growth has neutral `UNKNOWN` meaning, not a trade decision.

The existing `OFFER_SIZE_INCREASE` observation remains distinct: its 25% baseline
growth rule does not establish a continuous-growth sequence. Both observations
may reference the same wall; consumers should not count them as independent evidence.

## Configuration

`PatternObservationConfig` contains only detector enablement, symbol selection,
episode/wall/trade buffer limits and detector thresholds. Defaults are compiled
and documented in [the template](../config/pattern-observation.template.json).
An optional explicit `bmtrader.patternObservationConfig` JVM property selects a
JSON file. The former `bmtrader.signalComposerConfig` property and composer rules
are retired; migrate detector overrides to the new template if needed. Invalid
configuration disables observation. Settings load once per activation; stop all
attachments and reattach to reload.

Detection is independent of manual tradebook eligibility. The separately configured
Bid Breakdown, False Breakout and Reclaim Second Bid chart monitors retain their
existing enablement, algorithms and drawings.

## Event delivery

The existing loopback WebSocket and optional JSONL capture remain. Enable export
through the existing Cairo observation configuration; no new transport or model
call has been introduced. See [the wire contract](cairo-evidence.md).

`cairo_patterns` is now **version 2**. It contains only pattern observations,
detector rules, lifecycle metadata and bounded displayed liquidity. Removed fields:
`signals`, `contexts` and `candidateStates`. A version-1 consumer must be updated
before it can consume version 2. No external Cairo/AI consumer was changed here.

The snapshot retains the latest revision of up to 64 episodes for ten market
minutes. Delivery is at most every 500 ms, with reconnect snapshots and optional
rotating captures. It is bounded recent context, not a lossless archive of every
occurrence or raw depth/trade stream. Future AI should deduplicate by source,
epoch, event ID and revision and respect market/detection time plus readiness.

## Verification

Run `gradlew.bat build`. Callback tests cover event-only export, observation without
trading dispatch, replay/gap resets, configuration and raw detector behavior.
Release tests exercise the obfuscated event contract. Automated checks do not
verify actual Bookmap layout or the downstream consumer; use a recording to
validate the installed plugin before relying on its observations.
