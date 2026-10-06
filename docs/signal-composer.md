# SignalComposer operator guide

SignalComposer combines Bookmap depth and trade observations into advisory LONG/SHORT markers. It does not submit orders, calculate position size, select tradebooks, play an entry sound, or send a new WebSocket message. Native/manual execution, legacy scored badges, and Cairo export retain their existing behavior.

## Enable and configure

The default is disabled. **SignalComposer (advisory)** in addon settings is a shared switch across eligible charts, independent of **Bookmap Pattern Automation** and tradebook eligibility. A missing configuration file gives valid defaults that can be enabled through this checkbox. A malformed file disarms the feature and disables the checkbox; the log and tooltip explain the error.

Copy [the disabled template](../config/signal-composer.template.json) to `%USERPROFILE%\bmtrader\signal-composer.json` to customize rules. Java resolves this under `user.home`. An explicit JVM override is `-Dbmtrader.signalComposerConfig=C:\absolute\path\signal-composer.json`. Configuration files are limited to 16 KiB. Empty `symbols` means all attached symbols; a list filters symbols after normalization. Quantities below are equity shares, prices are integer Bookmap ticks, and configuration durations are milliseconds.

Rules load once at the first attachment. Later attachments share that immutable configuration revision and retain the user's current checkbox state. **Stop every bmtrader attachment, then reattach them to reload the rules.** Restart Native Trading does not reload composer rules. This feature does not write your configuration or secrets.

## Signal rules and defaults

LONG requires a bid hold: a persistent bid reappears or steps up. SHORT requires a bid failure: inferred withdrawal or consumption followed by a breakdown. Offer evidence alone shows waiting context and creates no signal. Bare offer growth has UNKNOWN meaning until actual rejection occurs.

Confirmation is optional for a normal 5K trigger. The strongest compatible local offer observation determines the requirement; sizes are never added together.

| Confirmation strength | Size versus 5K confirmation baseline | Required bid trigger |
| --- | --- | --- |
| NONE / BELOW_NORMAL | Absent / less than 5K | 5K |
| NORMAL | At least 5K | 5K |
| STRONG | At least 10K | 5K |
| VERY_STRONG | At least 25K | 4K |
| EXCEPTIONAL | At least 50K | 3K |

The absolute minimum is 3K regardless of confirmation. A 60K bearish offer observation can therefore qualify a nearby 3K bid failure as SHORT. A 3K bid failure can also wait for sufficient confirmation arriving later. A 5K trigger validated first keeps its original decision and receives later evidence as a revision of the same signal.

Matching uses the same chart/epoch, up to 30 seconds before or after the trigger, already-observed evidence no older than 30 seconds, and at most 20 ticks of separation. The offer must be at or above the bid trigger, allowing two ticks of price noise. Active candidates expire after 30 seconds, or invalidate on opposing local bid behavior, trigger-record eviction, or drift greater than 20 ticks. History is retained for 120 seconds, capped at 2,048 events and 64 candidates.

## What the detectors establish

The observer uses its own 3K floor, independent of the legacy percentile threshold. Walls qualify after 500 ms. Snapshot levels still need persistence; a snapshot itself is not a signal.

| Behavior | Required observation |
| --- | --- |
| Bid withdrawal | At least 90% loss of immediately pre-clear size, stable for 500 ms; known sell volume explains at most 10%; intact coverage and no likely relocation |
| Bid breakdown | Known sell volume explains at least 70% of loss, then an actual trade at least one tick below within three seconds |
| Offer rejection | Persistent offer approached from below within two ticks; subsequent trade at least two ticks below, held for 200 ms within five seconds; offer remains present and unbroken |
| Offer growth plus rejection | At least 25% growth, followed by the rejection behavior in the same interaction; upgrades one rejection episode |
| Offer breakout | Known buy volume explains at least 70% of loss, then an actual trade at least one tick above within three seconds |

Trade attribution uses the same price, known aggressor side, a two-second lookback, and the clear-decision interval. Probable moves pair same-side replacement within 500 ms and 10% size tolerance. Unknown aggressor, ambiguous losses, pruned evidence, or buffer discontinuities do not prove withdrawal. “Inferred withdrawal” is an observation-based attribution, not proof of an exchange cancellation; quote defense is not proof of executed absorption.

Wall phases and relocation/reference data are bounded at 4,096; trade attribution is bounded at 8,192 trades. Observation buffer overflow clears dependent composition state, begins a new epoch, and requires fresh readiness. Diagnostics identify the reason.

## Read the chart and logs

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
