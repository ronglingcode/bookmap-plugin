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
settings. `sourceMode` is explicit (`live`, `replay`, `unknown`); callbacks do not
prove live data. `observerOnly` omits native broker startup. Reattach to reload.

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
