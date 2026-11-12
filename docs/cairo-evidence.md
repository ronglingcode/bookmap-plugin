# Cairo pattern-event contract

Bookmap is the pattern detector. `PatternEventPipeline` forwards detector events
directly, without combining them into trade decisions. Signal Composer is removed.
See [pattern events](pattern-events.md) for detector settings and lifecycle.

## Existing export settings

Preferences load at attachment from `%USERPROFILE%\bmtrader\cairo-observation.json`:

```json
{
  "evidenceEnabled": true,
  "evidenceSymbols": ["NVDA"],
  "captureEvidence": true,
  "sourceMode": "replay",
  "observerOnly": true
}
```

`evidenceEnabled` defaults to false; empty/missing `evidenceSymbols` covers all
eligible attached charts. `captureEvidence` defaults to true. Historical detector
lists do not filter the feed. Detector observation must also be enabled with valid
settings. The plugin reads Bookmap's run-mode flags at attachment. A configured
`sourceMode: "replay"` is a fallback for hosts without mode information. An unknown
mode leaves broker execution unavailable. `observerOnly` also omits native broker
startup. Reattach to reload settings.

## Replay fills

The replay Trade window shows a **REPLAY** banner and a **Buy (simulated)** control.
The control fills the entered share count at the latest recorded trade price and
publishes `cairo_replay_fill` version 1 over the loopback connection. The event
carries `mode`, `sourceInstanceId`, `symbol`, `epoch`, `sequence`, `eventTime`
(Bookmap cursor in nanoseconds as a string), `executionId`, `positionId`, `side`, `price` (USD),
`quantity`, `positionQuantity`, `positionAveragePrice`, and `opening`. On rewind,
`cairo_replay_reset` marks the new epoch and the replay window waits for a new trade.
New subscribers receive the latest replay position or reset with `delivery: "snapshot"`.

## Cairo Live data bridge

The loopback connection also sends version 1 `cairo_chart`, `cairo_trade`, and
`cairo_account` records. Chart records identify attached symbols and data mode.
Trades use normalized USD prices and Bookmap market timestamps. Consumers can
detect dropped ticks via gaps in the per-symbol `tradeSequence`. Account records
carry the completed native account projection's `asOf` timestamp, net position,
average price, working stops, and normalized executions with stable opaque IDs.
Connect and reconnect snapshots carry `delivery: "snapshot"` and are baselines.
The bridge has no order submission endpoint or credentials.

## Version 2 snapshot

`type: "cairo_patterns"`, `version: 2` is a complete bounded per-chart snapshot,
delivered at most every 500 ms. Metadata includes source identity, symbol, epoch,
sequence, nanosecond market time as a string, USD tick size, mode, readiness,
coverage, detector/config revisions and stream/snapshot delivery. Reading a
snapshot does not advance market time.

- `patterns`: latest revisions of up to 64 canonical pattern episodes, retained
  for ten market minutes. Includes measured evidence, size basis, semantic type,
  event/detection times and timestamp provenance.
- Fast-growth events (`BID_FAST_GROWTH`, `OFFER_FAST_GROWTH`) include baseline,
  measured current size, added quantity, growth fraction, increase count, largest
  step, and first/latest increase timestamps, duration and maximum inter-increase
  gap (nanoseconds as strings). They require continuous additions over seconds;
  a single jump plus unchanged liquidity is insufficient. Meaning is `UNKNOWN`.
- `rules`: detector-only settings, observation floor and buffer limits.
- `liquidity`: up to eight displayed levels of at least 5K shares per side, ordered
  by price, plus BBO and as-of time. It is not the full order book.

`signals`, `contexts`, `candidateStates` and all composer scoring/threshold fields
are removed. Version-1 receivers need an explicit version-2 update; no receiver
migration or AI consumer is included in this plugin change.

Resets clear the epoch's events. Chart removal sends an empty not-ready terminal
snapshot. Consumers must deduplicate revisions and discard obsolete sources,
epochs and sequences; historical/reconnect records are context rather than fresh
occurrences. Gaps and bounded retention preclude a complete occurrence history.

Background delivery/capture avoids file/network I/O on Bookmap callbacks.
Capture remains `%USERPROFILE%\bmtrader\patterns\patterns-*.jsonl`, rotating at
10 MiB with bounded retention. Capture errors are reported without stopping
detection. Existing files retain their original version and are not rewritten.

## Verification

`gradlew.bat build` checks callback-driven detection, reset/reconnect metadata,
event-only exports and release-JAR serialization. The fixture is generated at
`build/fixtures/cairo-patterns.jsonl`. Actual Bookmap replay and external receiver
compatibility require separate verification.
