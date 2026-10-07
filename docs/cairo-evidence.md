# Cairo setup evidence and replay testing

The new evidence recorder defaults to disabled. Opt in with `evidenceEnabled: true` to recognize setups and forward their evidence to Cairo. When enabled, it is independent of existing detector buttons and indicator visibility, and records all attached equity charts unless `evidenceSymbols` restricts it.

Preferences are read at plugin attachment from `%USERPROFILE%\bmtrader\cairo-observation.json`:

```json
{
  "enabled": true,
  "observerOnly": true,
  "evidenceEnabled": true,
  "evidenceSymbols": [],
  "captureEvidence": true,
  "sourceMode": "replay",
  "symbols": ["PCVX"],
  "detectors": ["BID_STEP_UP", "BID_REAPPEAR"]
}
```

- `evidenceEnabled`: **false by default**. Set true explicitly to enable the new recorder. Set false and reattach/restart to skip wall/history buffers, raw trade/BBO observation work, capture I/O and evidence exports. Set the separate older observer flag `enabled` to false as well to stop its pattern exports. Existing trading retains its own settings.
- `evidenceSymbols`: optional chart restriction; empty/missing means all attached charts. It is separate from the older pattern detector's `symbols` list.
- `captureEvidence`: true by default; false keeps the live stream/history but skips market-evidence files.
- `sourceMode`: `replay`, `live`, or `unknown` (default). This is an explicit operator setting, not automatic detection. Bookmap's callbacks in use do not establish provider mode. Set replay for replay testing and live only when running live market data.
- `observerOnly`: use true for replay to avoid starting the native broker runtime. For normal native trading, restore your original value (normally false).

## Restart and test

1. Build with `gradlew.bat build`; select `build/libs/lingrong1988_bmtrader_1.33.jar` in Bookmap API Plugins Configuration. Older registered JAR paths do not automatically follow the new build.
2. Apply replay settings above, restart Bookmap in replay mode, attach bmtrader, and replay a recording with large bid/offer walls. Snapshot completion establishes depth readiness.
3. Rebuild/restart Cairo (`npm run build`, then its normal launcher). Open live chat or Bookmap observations. The setup assistant shows replay, market time, coverage, recognized before/after bounces, measured highs, ask confirmation and prior offer rejection.
4. Automatic AI explanations default on while Cairo's model is connected and idle. Turn off “AI explanations on setup changes” on the card to suppress them, or click “Explain with AI” for a focused review. Chat cancellation pauses automatic explanations. Replay remains advisory and cannot be accepted as a real-position tag.
5. Replay backward/seek and reconnect: evidence should enter a new epoch or warmup and must not retain the old setup as current. When ready callbacks are absent after a seek, reattach the addon to establish a fresh snapshot.
6. Compare recognized bounces with Bookmap. Correct through chat (“use the earlier bounce at ...”) and inspect the evidence. Initial filters are two ticks, 200 ms and five seconds of covered history for no-bounce; these are configurable engineering defaults, not validated trading definitions.

Before returning to live trading, set `sourceMode` to `live` and restore your original `observerOnly` value, then restart/reattach.

## Data and limits

The plugin keeps up to 20,000 evidence events or ten minutes per chart. Trades and BBO transitions are retained individually; qualifying wall changes are recorded with stable IDs. Non-qualifying full-depth changes are not exported. Qualification uses the existing dynamic wall threshold; trade attribution is a bounded two-second estimate and cannot identify individual orders.

Callback methods perform no file/network I/O. A background worker emits batches of up to 128 events. Pending queues are bounded; overflow or backward event time opens a new epoch and reports a drop. Reconnect sends bounded snapshots without allocating fresh event sequence numbers. Live delivery and snapshot delivery are distinct.

Capture uses dedicated `%USERPROFILE%\bmtrader\evidence\evidence-*.jsonl` files, rotating at 10 MiB and retaining approximately 100 MiB total. The recorder reports capture failures on the wire and in local action logs; file failures do not block trading. An abrupt exit can lose pending data. These are observed wall/trade/quote captures, not complete raw-depth recordings.

The wire protocol is `cairo_evidence`, version 1, with per-recorder source identity, epoch, event sequence, nanosecond event time, USD prices/tick size, source mode, readiness and coverage/drop evidence. Its contract is implemented in `CairoEvidenceRecorder` and Cairo's `BookmapEvidence`; the earlier `cairo_observation` pattern protocol remains supported separately.

## Replay without Bookmap

From Cairo's repository, with Bookmap's server stopped:

```powershell
node scripts/replay-bookmap-evidence.mjs --file C:\Users\lingr\bmtrader\evidence\evidence-SESSION-0.jsonl --speed 1
```

The replay server uses `127.0.0.1:8765`; playback begins when Cairo connects. `--port` chooses another port and `--speed` changes speed. All captured modes are overridden to replay. The tool has no broker dependencies. At end of playback it keeps context available until Ctrl+C.

Verification: `gradlew.bat build` covers native/release tests. `CairoEvidenceRecorderTest` generates `build/fixtures/cairo-evidence.jsonl`. From Cairo, `node --experimental-strip-types scripts/verify-bookmap-bridge.mjs` exercises the real Java output through the replay WebSocket, Cairo recognition, timeline tools and validated interpretation contract. It makes no model/broker calls.
