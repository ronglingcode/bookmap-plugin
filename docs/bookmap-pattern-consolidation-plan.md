# Consolidated Bookmap pattern architecture

Implemented according to the October 7 request: one plugin detector pipeline, all events through SignalComposer, Cairo as an aggregate consumer. See [bridge contract and migration](cairo-evidence.md) and [Composer rules](signal-composer.md).

```
Bookmap callbacks → PatternObservationEngine → SignalComposer
                  → aggregate patterns/signals/context/liquidity → Cairo
```

## Decisions

1. One `WallBreakDetector` handles both directions. `OFFER_BREAKOUT` and `BID_BREAKDOWN` remain directional event identifiers for the same consumed-wall rule. Qualified stable clear, known-side trade attribution and a confirming print beyond the wall are required. Consumption or quote crossing alone is insufficient.
2. Removed the wall-break checkbox/key, order-change breakout enum/classifier/drawing branches and tradebook matcher. Ordinary liquidity-change labels/sounds and execution-based retest fills retain their existing purposes; a partial retest is not treated as a confirmed break.
3. Removed the separate legacy engine, scored signals/scorer/store/painter and Pattern Automation checkbox. Shared reappear/step definitions now run only inside the canonical observer. Composer owns the resulting signals and display.
4. Every canonical event reaches Composer before event logging/export. Offer breakout remains bullish confirmation context, waiting for compatible bid hold; bid breakdown is bid-failure trigger evidence. Existing Composer thresholds and matching policies are preserved.
5. Removed raw evidence recording/export and the old two-pattern exporter. The complete bounded feed carries canonical events, composition/lifecycle, context, effective rules and large-level liquidity summaries. Display-liquidity thresholds do not change pattern classification.
6. Removed Cairo's independent mini-bounce/bid-breakdown and offer crossing/return/rejection detectors and their runtime configuration. Cairo rejects old/raw protocols; AI annotates plugin decisions rather than reclassifying setups.
7. Manual tradebook tagging remains a human workflow. No mapping from generic breakdown to a bounce-variant tradebook is invented. Existing archives stay historical; new long/short fill associations freeze causal plugin compositions.

## Verification and limits

Tests cover mirrored breaks, wrong/unknown aggressor, confirming prints, exactly one event, offer context versus composed bid signal, readiness/reset behavior, lifecycle state/time/revision export, terminal delivery, complete snapshot validation/recovery, independent chart sources/epochs, replay/reconnect suppression, causal archives, immutable frozen fills, constrained annotations and aggregate target liquidity. The actual Java fixture is passed through a real WebSocket into Cairo and its tools; release/native and UI builds are checked separately. No model/broker calls or remote pushes are part of verification.

Transport is bounded, not a full-session archive. A signal leaves the exported window if its referenced events are evicted. Source mode is explicit. Live replay/visual verification requires loading the rebuilt plugin and Cairo. Unrelated strategy issues from the study (gradual-drain measurement basis and pending-trigger growth) are not changed here.

[The deeper study](bookmap-pattern-study.md) and historical harness/results record the prior checkout. Removed legacy source locations are historical; current callback/aggregate tests validate the implemented architecture.
