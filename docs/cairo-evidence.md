# Cairo aggregate patterns and replay

Bookmap is the sole pattern detector. Every event goes through SignalComposer before export. Cairo consumes canonical classifications, composed signals, developing contexts, rule metadata and bounded large-level liquidity. Cairo's wall/trade/BBO, mini-bounce and offer-rejection detectors are removed. The old `cairo_evidence` and `cairo_observation` protocols are removed and rejected by the new receiver.

## Settings and migration

Preferences load at chart attachment from `%USERPROFILE%\bmtrader\cairo-observation.json`:

```json
{
  "evidenceEnabled": true,
  "evidenceSymbols": ["PCVX"],
  "captureEvidence": true,
  "sourceMode": "replay",
  "observerOnly": true
}
```

The preference names are retained for migration. `evidenceEnabled` now enables aggregate export and defaults to false; empty/missing `evidenceSymbols` covers all eligible attached charts. Existing `enabled: true` plus `symbols` also enables export for those charts. Old `detectors` lists no longer filter the feed: every Composer pattern is exported. `captureEvidence` defaults to true and now captures aggregate snapshots.

Composer must be enabled with valid rules and the chart must satisfy its optional symbol filter. Disabling it resets the pipeline and leaves the feed not ready. The dynamic wall display threshold only controls liquidity summaries; it does not change pattern or signal thresholds.

`sourceMode` remains an explicit operator setting (`live`, `replay`, `unknown`; default unknown). Readiness callbacks cannot prove live data. `observerOnly: true` keeps native broker initialization off for replay. Stop and reattach all charts to reload preferences/rules.

## Aggregate contract

`cairo_patterns`, version 1, is a complete per-chart snapshot every 500 ms. Metadata includes source identity, epoch, delivery sequence, nanosecond market time as a string, USD prices/tick size, mode, readiness/coverage, detector/config revisions and stream/snapshot delivery. Heartbeats never advance market time. A complete snapshot repairs skipped deliveries.

- `patterns`: latest revisions of up to 64 canonical events with aggregate measured evidence and occurrence/detection times. Retained for at most ten market minutes; active context references stay within this bound.
- `signals`: up to 32 composed records whose referenced evidence remains in the exported window. Direction, trigger, first validation time, latest evidence time, thresholds, strength, lifecycle state/change time and evidence IDs are included. Wire revision changes on composition or lifecycle change; `compositionRevision` preserves the underlying decision revision.
- `contexts`: up to two directional waiting contexts with confirmation ID, strength, required bid behavior/size and market-time expiry.
- `candidateStates` and `rules`: bounded Composer state and effective observation/detector thresholds for explanation.
- `liquidity`: up to eight qualifying displayed levels per side, dynamic threshold, current quote summary and as-of time. This is a bounded summary, not raw depth updates or a complete order book.

Individual trades, quote transitions, wall-start/update/end records and full depth are not sent. Plugin callbacks perform no file/network I/O; the background worker reads snapshots under the composition lock and handles delivery/capture. Resets clear the epoch's history/context/signals. Chart removal sends an empty not-ready terminal snapshot.

Cairo validates the whole snapshot before mutation and tracks source/epoch per symbol. Old sources, epochs, sequences and revisions are rejected. Disconnect clears live data. Replay and reconnect snapshots remain context; a following heartbeat cannot promote their signals into new entry evidence. Live liquidity requires fresh receipt and market times plus live/ready/continuous status.

## Cards, annotations and archives

Cairo displays the plugin's events separately from composed signals and waiting context. AI may explain recorded IDs/prices, but cannot substitute a different classification or synthesize a bounce variant. Manual `/bookmap-pattern` tagging remains available. A generic `BID_BREAKDOWN` is not automatically mapped to one of the user's manual bounce tradebooks.

Live buy/sell fills associated with current long/short holdings can freeze a plugin composition available at the fill. Later revisions do not rewrite it. Replay, snapshot history, future evidence and invalidated signals cannot supply that assessment. Existing entry archives remain readable as historical data. Association still depends on broker timestamps and the available aggregate window.

Optional capture writes `%USERPROFILE%\bmtrader\patterns\patterns-*.jsonl`, rotates at 10 MiB and retains about 100 MiB of dedicated files. Errors appear in feed/UI status and do not stop detection. Existing raw evidence files are untouched and cannot be replayed through the new contract.

## Verify and run

1. `gradlew.bat build` produces `build/libs/lingrong1988_bmtrader_1.34.jar`; select that JAR in Bookmap and reattach charts.
2. Rebuild/restart Cairo with `npm run build`. Keep `sourceMode: replay` for replay tests; restore the intended mode/native-trading preference before live use.
3. `CairoPatternSnapshotTest` generates `build/fixtures/cairo-patterns.jsonl` from actual callback-driven detection/composition. From Cairo, `node --experimental-strip-types scripts/verify-bookmap-bridge.mjs` verifies Java output through a loopback WebSocket, Cairo tools and annotation validation without model/broker calls.
4. `node scripts/replay-bookmap-evidence.mjs --file <patterns-SESSION-0.jsonl> --speed 1` replays aggregates on `127.0.0.1:8765`; `--port` chooses another port. Captured modes are overridden to replay.

Builds do not install, restart or alter live application settings automatically.
