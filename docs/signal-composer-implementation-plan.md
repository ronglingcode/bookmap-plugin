# SignalComposer implementation plan

Prepared 2026-10-06 against `bookmap-plugin` commit `fdbbeaa7b76940d01bc386f2fb11417a734c9c1a`.

Design input: `C:/Users/lingr/Downloads/SignalComposer_Design.md`. This document incorporates the design and the actual Java implementation. It is a coding-agent handoff, not an instruction to implement or enable trading during the planning task.

Execution checklist: [44 small tasks to implement sequentially](signal-composer-tasks.md). Use that checklist for task order, per-task completion criteria, and progress tracking; use this document as the behavior specification.

## 1. Intended result and scope

Add a deterministic, explainable, real-time SignalComposer to bmtrader. A LONG requires a bid-hold event; a SHORT requires a bid-failure event. Relevant offer evidence can lower the required bid-event size, but cannot produce a composed signal alone. Use the strongest qualifying confirmation, with no additive score.

V1 produces advisory chart markers, developing-context state, and local explanations. It does not place orders, dispatch trading actions, select tradebooks, or change broker execution, sizing, stops, exits, or retest eligibility. The design calls its output a trading signal; this plan treats that as a market-analysis result, not an execution command.

Preserve existing scored pattern badges and Cairo observation/evidence contracts as separate outputs. The new composer never reads their quality scores. Removing legacy scoring or integrating composed signals with execution would be separate changes.

Implementation is confined to `bookmap-plugin`. No ViteApp, Cairo, ProxyServer, or TradingData changes are required for this V1.

## 2. What the current code already does

Paths below are relative to the repository root. Line references describe the audited commit and can move during implementation.

| Existing location | Actual behavior | Consequence for this implementation |
| --- | --- | --- |
| `README.md` | The addon now trades natively using Schwab, Massive, and Firestore. WebSocket port 8765 is optional for trading. | Do not build around the older assumption that ViteApp owns execution. |
| `RongPlugin.java:219`, under `src/main/java/com/bookmap/plugin/rong/` | Creates one `BookmapPatternEngine` per attachment. Eligibility depends on the legacy display toggle plus enabled matching tradebooks, or the Cairo observer filter. | Composer detection needs independent enablement and must collect both sides even without a matching tradebook. |
| `RongPlugin.java:556` | Routes depth, trades, timestamps, BBO, and snapshot/realtime readiness to trackers. Depth updates the shared `OrderBookState` before calling the pattern engine. | This is the integration point for the new observation/composition path. |
| `patterns/PatternType.java` | Only `OFFER_REAPPEAR`, `BID_REAPPEAR`, `OFFER_STEP_DOWN`, and `BID_STEP_UP` exist. Direction is embedded in this enum. | Bid cancellation, bid breakdown, offer rejection, and offer growth-plus-rejection are not existing `PatternDefinition` implementations. |
| `patterns/BookmapPatternEngine.java:248`, `:289` | Uses market event time; gates on snapshot readiness and 09:30-16:00 New York; tracks qualified wall phases. Minimum wall lifetime is 500 ms. | Reuse these lifecycle conventions for composition. |
| `patterns/BookmapPatternEngine.java:360` and `OrderBookState.java:289` | Wall qualification uses the maximum of the absolute wall floor and a book percentile; the plugin passes percentile 97. Default absolute floor is 5,000. | A 3,000-share wall may never reach a detector. Lowering only the composer threshold cannot solve this. |
| `patterns/BookmapPatternEngine.java:413` | `emit(PatternCandidate)` scores a detection and emits `BookmapPatternSignal`. | Normalize observations before scoring, using a separate output path. |
| `patterns/PatternCandidate.java`, `WallSnapshot.java` | A candidate contains both the historical reference wall and the new trigger wall. | Measure the observed event from the relevant current interaction, not the historical reference wall's peak. |
| `patterns/ReappearPatternDefinition.java`, `StepPatternDefinition.java` | Stateful detector callbacks; step episodes can emit again after one second. Both definitions also expose legacy scoring methods. | Reuse independent instances of their detection callbacks; repeated emissions must become revisions, not new trigger opportunities. |
| `patterns/AbstractDirectionalPatternDefinition.java` | Defense is inferred from quotes remaining on the defended side. It is not proof of executed absorption. | Explanations must describe the actual wall/quote behavior observed. |
| `orderwall/OrderWallChangeTracker.java:227`, `:273`, `:493` | Separately detects material changes, inferred moves, and trade-consumption breakdown/breakout labels. Attribution and survival delays use wall-clock time and a scheduler; labels also depend on settings. | Useful attribution/reference logic exists, but these alert callbacks are unsuitable as the composer's event-time source. |
| `patterns/PatternSignalStore.java`, `PatternSignalPainter.java` | Stores up to 20 legacy signals per alias, updates by episode, displays scored badges for 30 seconds using creation time. | Add an equivalent composed-signal store/painter without inventing a score or changing the legacy schema. |
| `patterns/CairoObservationExport.java` | Exports only the existing bid reappear/step-up observations. | Do not insert composed signals into `cairo_observation`. |
| `patterns/CairoEvidenceRecorder.java` | Records bounded wall/trade/BBO evidence, with epochs and explicit replay/live/unknown mode. Wall capture still uses the existing dynamic threshold. | Preserve its protocol. Existing captures can omit the small walls needed by the new composer. |
| `PluginLog.java` | Queued local file logging, concise screen summaries, and file-only details. | Use this for explanations; no callback-thread file I/O or new persistence service is needed. |
| `build.gradle`, `src/releaseTest/java/release/ReleaseJarTest.java` | Java 11 source target, JUnit 5, independent native-engine compilation, obfuscated-JAR verification. | New classes must work in the actual release JAR, including enum/config/JSON behavior. |

Prefix all abbreviated Java paths in this table with `src/main/java/com/bookmap/plugin/rong/`.

## 3. Resolve the design's open details before coding

Use these explicit V1 decisions. They resolve ambiguities in the design without claiming that the numerical defaults have been validated as trading parameters.

1. **Confirmation is optional for a normal-size trigger.** A valid bid event of at least 5,000 shares can generate a signal immediately with confirmation strength `NONE`.
2. **Late confirmation revisits active triggers.** Keep a smaller trigger pending for the post-trigger window. If exceptional evidence arrives later, it may become valid then. An already valid signal receives an evidence revision under the same ID, rather than a second signal.
3. **Use six named confirmation bands:** `NONE`, `BELOW_NORMAL`, `NORMAL`, `STRONG`, `VERY_STRONG`, `EXCEPTIONAL`. The document's shorter list and longer example table are reconciled here.
4. **Use an independent detection floor.** Start at 3,000 shares, at or below the smallest accepted trigger size. Do not apply the legacy percentile filter to composer observations.
5. **Use market event time for rules.** System time is only for receipt timestamps, logging, and display TTL. Replay speed must not change eligibility.
6. **Keep signal decisions local.** Chart/file output is sufficient for V1. New WebSocket exports, external consumers, and durable event datasets are deferred.
7. **Start with existing bid-defense patterns for LONG.** Bid reappear and step-up provide the current definition of bid hold. Do not label them absorption or executed rejection. Dedicated bid absorption/reload/bounce detectors can follow later.
8. **Implement basic breakdown now.** Mini-bounce/no-mini-bounce subtypes are deferred. Do not label a sequence as 'without bounce' merely because a callback contained no bounce.

## 4. Recommended architecture

Create a separate event-time observation path so the 3K requirement does not alter legacy wall thresholds, pattern scoring, or tradebook eligibility.

```text
Bookmap callbacks in RongPlugin
    |
    +--> existing trackers / BookmapPatternEngine / CairoEvidenceRecorder
    |       existing behavior and output contracts
    |
    +--> PatternObservationEngine (one instance per attachment)
            |
            +--> event-time wall/trade/quote lifecycle
            +--> independent ReappearPatternDefinition / StepPatternDefinition instances
            +--> BidFailureDetector / OfferInteractionDetector
            |
            v
         immutable PatternEvent
            |
            v
         SignalComposer
            +--> bounded PatternEventStore
            +--> active SignalCandidate records
            +--> ConfirmationMatcher
            +--> ConfirmationStrengthClassifier
            +--> TriggerRequirementPolicy
            +--> SignalExplanationBuilder
            |
            v
         immutable CompositionUpdate
            +--> composed signal / context store and painter
            +--> PluginLog
```

Keep `PatternObservationEngine` in the existing `patterns` package so it can reuse package-private definitions, `PatternRuntimeContext`, `PatternCandidate`, and `WallSnapshot`. It implements the detection context and converts `emit(PatternCandidate)` directly into a normalized event. It must not implement `PatternScoringContext` or invoke `PatternDefinition.score()`/`BookmapPatternScorer`.

Use new `EventTimeWallTracker` and bounded trade-attribution helpers for this path. Follow the existing engine's qualification/consumed-clear conventions and cover them with fixtures. Keep the legacy `BookmapPatternEngine` unchanged in V1; do not begin by replacing its private lifecycle implementation. Reuse existing pattern detection classes, price conversion, and logging rather than duplicating them.

The extra tracker runs only while composition is enabled. It shares the current `OrderBookState`, but owns its wall phases, detector episodes, history, and resets. All mutable observation/composition state is serialized under one per-attachment lock. UI reads immutable snapshots; it never holds that lock while painting or doing I/O.

## 5. Event model and pattern semantics

Add `PatternEvent`, `PatternEventType`, `PatternSide`, and `PatternMeaning` in `patterns`. Keep existing `PatternType` intact: its directional and wall-family helpers also participate in manual tradebook routing.

`PatternMeaning` contains `BID_HOLD`, `BID_FAIL`, `OFFER_BEARISH_CONFIRMATION`, `OFFER_BULLISH_CONFIRMATION`, and `UNKNOWN`. Role is derived by the composer from side, meaning, and requested direction; it is not inferred from size.

An immutable event contains:

- Stable ID, episode key, revision, instrument alias, and observation epoch.
- Type, side, meaning, measured `long size`, and a documented size basis.
- Integer `priceTick` as the matching/painting price; tick size and converted real price for explanations/JSON.
- Market `eventTimeNs` and derived milliseconds; separate `observedAtNs` when delayed detection completes, plus receipt time for diagnostics.
- Immutable evidence metadata: wall/phase IDs, previous/current size, removed size, observed trade volume, attribution status, growth/approach/rejection times, and quote/trade evidence where applicable.
- Timestamp provenance and evidence-readiness information. Unknown aggressor side must remain unknown, not become selling by default.

Use explicit typed evidence fields for the rules; an immutable metadata map can hold supplemental diagnostics. Do not make the composer depend on free-form strings to establish a trigger or confirmation. Use `BookmapPriceNormalizer` at all tick/real-price boundaries.

Derive IDs from alias, epoch, detector, and episode identity, with revision stored separately. Repeated emission must not allocate a new random event ID. Reuse the existing standalone `patterns.Direction` enum for LONG/SHORT; direction belongs to the composed result, independently of the legacy `PatternType` routing helpers.

### Mapping existing detections

| Source candidate | Normalized type | Side / meaning | Size basis |
| --- | --- | --- | --- |
| `BID_REAPPEAR` | `BID_REAPPEAR` | BID / BID_HOLD | Current persistent replacement bid size at detection |
| `BID_STEP_UP` | `BID_STEP_UP` | BID / BID_HOLD | Current persistent stepped-up bid size at detection |
| `OFFER_REAPPEAR` | `OFFER_REAPPEAR` | OFFER / OFFER_BEARISH_CONFIRMATION | Current persistent replacement offer size at detection |
| `OFFER_STEP_DOWN` | `OFFER_STEP_DOWN` | OFFER / OFFER_BEARISH_CONFIRMATION | Current persistent stepped-down offer size at detection |

Normalize from `candidate.triggerWall`, and verify the phase is still present in the new tracker at emission. Do not use `BookmapPatternSignal.getReferenceWallPeakSize()` as the new event's size. Preserve the old reference wall as evidence only.

The reused definitions retain their existing same/better-price, replacement-size, quote-defense, and session-extreme rules. Their five-minute reference lookback is detector context; it does not make a composed confirmation five minutes old or override the composer's 30-second matching window.

### Missing detections to implement

These are proposed engineering definitions where the design describes examples but does not specify exact microstructure rules. Keep their thresholds configurable and expose the assumptions in explanations.

| New event | Minimum observed behavior | Meaning / event size |
| --- | --- | --- |
| `BIDS_CANCELLED` | A bid level previously persistent for 500 ms loses at least 90% of its immediately pre-clear displayed size; the loss survives 500 ms; at most 10% of the loss is explained by known sell-aggressor volume in the attribution window; no matched same-side relocation is observed. Require usable trade-side evidence/coverage. | BID_FAIL; displayed size removed in that clear. Attribution is inferred withdrawal, not proof of an exchange cancellation. |
| `BID_BREAKDOWN` | Qualified bid loss with at least 70% matching sell-aggressor attribution, followed by an observed trade below the level by at least one tick within three seconds. | BID_FAIL; displayed size removed, with traded volume retained separately. A size reduction alone does not establish a price breakdown. |
| `OFFER_REJECTION` | Persistent offer; price approaches from below within two ticks; subsequently trades at least two ticks below the offer and remains below for 200 ms, within a five-second interaction window. Offer remains present and has not been consumed/broken above. | OFFER_BEARISH_CONFIRMATION; persistent offer size when rejection completes. |
| `OFFER_SIZE_INCREASING_REJECTION` | The same offer episode grows by at least 25% during the relevant interaction, then meets the offer-rejection rule. | OFFER_BEARISH_CONFIRMATION; persistent offer size at completed rejection. Preserve growth size separately. |
| `OFFER_BREAKOUT` | Qualified offer is consumed with at least 70% matching buy-aggressor attribution, then an observed trade occurs at least one tick above the level within three seconds. | OFFER_BULLISH_CONFIRMATION; displayed size removed, with traded volume separately recorded. |

Record bare offer growth as `OFFER_SIZE_INCREASE` / `UNKNOWN`: growth without price interaction must not become exceptional bearish confirmation merely because its size is large. Ambiguous losses, insufficient attribution, and probable moves remain `UNKNOWN` or diagnostic outcomes, not bid-failure triggers. A generic offer pull is not automatically bullish confirmation.

Use a two-second event-time attribution lookback plus the clear-decision interval, with known aggressor direction at the same price. Trade volume uses `long` sums. Coverage means continuity of the callbacks actually received, readiness, and intact local buffers; it does not prove the feed observed every exchange action. Unknown-side trades relevant to a clear, buffer drops, or reset/warmup gaps make withdrawal attribution unusable. No matching trade in an otherwise usable interval is still only evidence of inferred withdrawal. Pair probable relocations using the existing alert code's 500 ms / 10% size-tolerance idea, implemented with event time. Do not feed scheduled `OrderWallChangeEvent` notifications directly into the composer.

Prefer one completed rejection event per interaction, upgraded to the composite type when growth is established. Do not count growth, rejection, and composite rejection as three independent confirmations of the same interaction. A truly new approach/rejection creates a new episode.

## 6. History, candidates, and composer behavior

`PatternEventStore` is per attachment and epoch. Keep a time-ordered deque and an ID/episode index. Prune on every event and timestamp callback. Retain 300 seconds (five minutes), fixed in code with no local override, bounded to 2,048 semantic events. Keep at most 64 active bid candidates. Report eviction/overflow in file diagnostics; do not retain candidates whose required trigger record was evicted. Bound detector wall phases to 4,096 and attribution trades to 8,192 entries as initial limits, with normal time-based pruning. A detector-buffer overflow invalidates dependent attribution and pending candidates; never silently treat missing trades as a bid cancellation.

Events can be detected after their underlying market action. Keep `observedAtNs` as the monotonic processing clock and `eventTimeNs` as the occurrence clock; insert delayed occurrences into history in time order. History queries must only use evidence already observed at the current processing watermark. Do not reset an epoch merely because a delayed detector outcome refers to an earlier occurrence.

Create a `SignalCandidate` for each usable BID_HOLD/BID_FAIL interaction at or above the absolute minimum accepted trigger. It holds its trigger, direction, state, matched evidence, expiry, emitted signal ID, and revision. Smaller events may remain diagnostic history; they cannot become a signal under any confirmation band.

Carry a canonical interaction ID from the detector wall/clear episode. Key candidates by alias, epoch, direction, and that interaction ID, not just pattern type: the current engine can detect reappear and step for the same new wall. Preserve both raw observations, but choose the first completed usable bid event as the primary trigger and attach the other as supporting bid evidence. Do not emit two signals or sum their sizes. A new physical wall/clear/interaction episode can create a new candidate.

States:

- `CANDIDATE`: not yet sufficient; may be upgraded by confirmation during its active window.
- `VALID`: threshold met; retain until the update window closes so later evidence can revise the explanation.
- `INVALID`: contradictory bid behavior, explicit price invalidation, or unusable evidence prevents further promotion.
- `EXPIRED`: update window ended without promotion, or the active observation context was reset.

Recommended API shape:

```java
CompositionUpdate onPatternEvent(PatternEvent event);
CompositionUpdate onMarketTime(long eventTimeNs);
CompositionUpdate onMarketPrice(int bestBidTick, int bestAskTick, int lastTradeTick);
void reset(ResetReason reason, long nextEpoch);
```

Return a structured update containing zero or more signal creations/revisions, context changes, and candidate transitions. The design's `Optional<TradingSignal>` is too restrictive: one confirmation can affect multiple active triggers and also alter developing context. `SignalComposer` owns the history rather than accepting an unrelated store on each call.

On each newly observed event:

1. Validate identity, side/meaning consistency, positive size/price, epoch, and timestamp provenance.
2. Prune history/candidates using the processing watermark, then insert/upsert the event.
3. Create or revise a bid-trigger candidate if appropriate. Repeated detector revisions do not create a new trigger or extend its occurrence time.
4. Reevaluate affected active candidates, including when the arriving event is offer-side evidence.
5. Match confirmations, choose the strongest valid one, apply the threshold table, and generate a deterministic explanation.
6. Emit one new signal when a candidate first becomes valid. Revise the same ID only when evidence or the explanation materially changes. Preserve its first validation time, original validation evidence, and applied threshold; describe later confirmation as arriving after validation, rather than changing why the original signal was accepted.
7. Recompute developing LONG/SHORT context and its expiry from actual evidence timestamps.

For a trigger at `t`, confirmations must fall in `[t - beforeWindow, t + afterWindow]`, be observed by now, and remain current: their age relative to the processing watermark must not exceed `beforeWindow`. The trigger itself must remain within its post-trigger active window. Boundaries are inclusive; expiry is strictly beyond the boundary.

Default both windows to 30 seconds. A stored event from 90 seconds ago can be present under 300-second retention and still be ineligible. Store retention and matching validity are separate concepts.

Match only the same alias and epoch, the appropriate offer meaning, and absolute price distance at most 20 ticks. For initial matching use the event's interaction/wall price, consistently across types; preserve the separate latest market price for diagnostics. Require compatible local structure: bearish offer resistance must be at or above the short bid interaction (allow two ticks of noise); bullish offer-breakout evidence must be at or above the long bid interaction. Make this small tolerance configurable.

The 20-tick default includes the design's $51.20/$51.05 example for a $0.01 tick. It is an initial replay setting, not a universal distance for every instrument.

Choose the highest eligible strength band. Ties choose larger measured size, then smaller absolute time delta, then event ID for stable replay. Preserve all relevant confirmations, each with signed `timeDeltaMs`, before/after/simultaneous ordering, price distance, and size basis. Several normal confirmations never sum into exceptional confirmation.

Invalidate unpromoted/updateable candidates on an opposing bid meaning within the same local interaction, or when current usable market price moves more than a configurable 20 ticks from the trigger. Do not retract or rewrite the historical fact that an earlier advisory signal was emitted; record subsequent invalidation as state. This is deliberately a simple freshness policy, not a stop or exit model.

### Threshold policy

Classify each qualified confirmation using `size / (double) normalConfirmationSize`:

| Multiple of normal confirmation size | Band | Required bid trigger size, initial defaults |
| --- | --- | --- |
| No relevant confirmation | NONE | 5,000 |
| Below 1x | BELOW_NORMAL | 5,000 |
| 1x to below 2x | NORMAL | 5,000 |
| 2x to below 5x | STRONG | 5,000 |
| 5x to below 10x | VERY_STRONG | 4,000 |
| At least 10x | EXCEPTIONAL | 3,000 |

Default `normalConfirmationSize = 5,000` and `normalTriggerSize = 5,000`. These are separate parameters. Compare trigger size with `>= appliedThreshold`. Never use legacy score/tier, points, summed sizes, or a weighted aggregate.

### Developing context and signal output

Expose a per-direction immutable context containing strongest recent offer evidence, its strength, required bid meaning/size, local price, and event-time expiry. It should say, for example, `SHORT context: 60K offer rejection; waiting for BID_FAIL >= 3K`. A context is not a valid signal. If both directional contexts exist, show both separately.

`TradingSignal` contains stable ID, revision, alias/epoch, direction, original trigger, all matched evidence, selected strongest evidence, strength, normal/applied trigger thresholds, config revision, validation event time, creation time, and explanation. Preserve an immutable first-validation snapshot; additional confirmations are recorded separately on revisions so later strength cannot retroactively justify an earlier decision. No score, broker quantity, order type, or execution command fields.

When later confirmation completes a small trigger, validation time is the time eligibility became known, not the earlier trigger time. Chart markers anchor at validation event time and trigger interaction price; preserve both times in the details. Do not backdate a signal onto a candle before the confirming evidence was available.

Explanations identify observed behavior, size basis, real prices, signed timing, locality checks, threshold applied, and the exact reason for any reduction. Describe withdrawal/consumption attribution as inferred where appropriate. Log candidate rejection/expiry reasons as file-only details.

## 7. Configuration and lifecycle integration

Add `signal/SignalComposerConfig.java` and `config/signal-composer.template.json`. Load `%USERPROFILE%/bmtrader/signal-composer.json` once at first attachment for the shared plugin activation, with test override property `bmtrader.signalComposerConfig`. Give each observation engine the same immutable rules snapshot. Use existing Gson and explicit parsing; do not add YAML dependencies.

```json
{
  "enabled": false,
  "symbols": [],
  "normalTriggerSize": 5000,
  "minimumTriggerSize": 3000,
  "normalConfirmationSize": 5000,
  "observationFloorSize": 3000,
  "beforeWindowMs": 30000,
  "afterWindowMs": 30000,
  "maxPriceDistanceTicks": 20,
  "directionalPriceToleranceTicks": 2,
  "maxTriggerDriftTicks": 20,
  "maxEvents": 2048,
  "maxCandidates": 64,
  "strengthMultiples": { "normal": 1, "strong": 2, "veryStrong": 5, "exceptional": 10 },
  "triggerRequirements": { "none": 5000, "belowNormal": 5000, "normal": 5000, "strong": 5000, "veryStrong": 4000, "exceptional": 3000 },
  "detectors": {
    "wallLifetimeMs": 500,
    "clearRemainingRatio": 0.1,
    "clearDecisionMs": 500,
    "attributionLookbackMs": 2000,
    "consumptionRatio": 0.7,
    "withdrawalMaxTradeRatio": 0.1,
    "movePairWindowMs": 500,
    "moveSizeToleranceRatio": 0.1,
    "approachDistanceTicks": 2,
    "rejectionDistanceTicks": 2,
    "rejectionHoldMs": 200,
    "interactionWindowMs": 5000,
    "growthRatio": 0.25,
    "breakoutDistanceTicks": 1,
    "breakoutWindowMs": 3000
  }
}
```

Empty symbols means all attached eligible charts. Normalize symbol filters through `SymbolUtils`, but keep event-store identities scoped to the attachment alias/epoch. Rules apply to equity displayed quantities as shares; do not generalize the defaults to futures contracts.

Validate bounded file size, positive quantities/durations, ordered strength multiples, ratios in range, history retention covering both windows, detection floor at or below the smallest allowed trigger requirement, and requirements that decrease or remain equal as strength increases. `minimumTriggerSize` is the hard lower bound; `normalTriggerSize` must agree with the unrelaxed requirements. Reject conflicting configuration rather than silently selecting one field.

Missing config yields disabled defaults and a valid configuration that can be explicitly enabled through settings. Invalid config disables composition and reports one actionable warning. Never change trading/secret settings. Include the effective config revision in diagnostics. Parameter edits require all addon attachments to be stopped and reattached in V1; a later attachment during the same activation does not reload or replace the rules.

Extend the existing `tasks.withType(Test)` isolation in `build.gradle` to set `bmtrader.signalComposerConfig` to a nonexistent file under `build/private/tests`. Tests must not inherit the user's local enabled composer or rule overrides. Config tests should parse supplied objects or use dedicated temporary files.

In `RongPlugin`:

- Construct the observation engine/composer independently of `shouldRunPatternAutomation()` and `hasEnabledPatternTradebook()`. Add an independent `shouldRunSignalComposition()` gate.
- On depth, capture the previous size before the shared book update if the observation tracker needs it, update the book once, then send the absolute update to both independent engines as enabled. Do not let the new tracker mutate the shared book.
- Forward trade size/aggressor knownness, BBO, and timestamps using one captured callback timestamp and provenance. Seed current levels when enabled after a completed snapshot.
- Arm after `onSnapshotEnd`/`onRealtimeStart`. Use the existing regular-session policy. Expire active state when leaving regular hours and reset for a new New York session date.
- On genuine backward callback time/replay seek, reset wall/definition/history/candidate/context state and start a new epoch. Require fresh readiness; if Bookmap does not supply it after seek, instruct reattachment. Do not reuse old depth as proof of a new snapshot.
- Reset on disable/enable, attachment stop, and config replacement. Disabling clears visible composed context/signals and consumes no further composition callbacks.
- Keep source mode from `CairoObservationConfig.sourceMode`; do not infer 'live' from realtime-start alone. Reject fallback wall-clock timestamps for composition until a usable Bookmap market timestamp is available.
- Existing `observerOnly` mode must still avoid native broker startup. Composer can be enabled by its local config in that mode without credentials or a Cairo connection. No new observer flag is required.

Add `IndicatorConfig.SIGNAL_COMPOSER` with default false and a clearly named `SignalComposer (advisory)` settings toggle, independent of `BOOKMAP_PATTERN_SIGNALS`. It is the shared master switch across attachments, matching the existing settings architecture; eligibility and runtime state remain per attachment. Initialize this master once from validated `enabled` before registering the first painter. Subsequent attachments must not overwrite the user's toggle. Computation requires the master to be enabled, valid config, and a matching symbol filter. File `enabled` supplies the initial state, not a second permanent veto on UI enablement.

`getCustomSettingsPanels()` currently hides panels without local secrets even in observer-only mode. Provide an observer-only indicator/composer panel in that case and omit native trading/account actions there. Preserve the existing inactive-without-secrets behavior outside observer mode. Test both activation paths.

## 8. Chart output, logging, and compatibility

Add `signal/TradingSignalStore.java` and `signal/TradingSignalPainter.java`, following the existing painter registration/listener/teardown conventions. Keep bounded signal history at 20 per alias and receipt-time display TTL at 30 seconds; evidence revisions do not extend the original signal's display expiry. Developing contexts have their own event-time expiry.

Display a concise marker such as `SHORT | Bid cancel 3K | Exceptional confirmation`, with a second line `Trigger minimum 3K (normal 5K)`. Show waiting context in a distinct chart status area with its price and remaining market-time window; it must not resemble a completed signal. Paint ticks directly. Use full explanations in local logs rather than huge chart badges.

Use `PluginLog.summary` once for a newly valid signal and `PluginLog.detail` for full evidence, normalized semantic events, meaningful revisions, rejected candidates, resets, and expiry. Logging is queued; perform no file/network I/O in Bookmap callbacks. Respect existing screen retention and repeat suppression. A later evidence revision must not play a new entry sound or appear as a fresh trade opportunity.

No changes to `SignalWebSocketServer.dispatchTradingAction`, `NativeTradingAdapter`, `EntryHandler`, `RiskManager`, or any order/exit path. No composed signal goes into `BookmapPatternSignal.toJson()`, `cairo_observation`, or `cairo_evidence`. Existing Cairo filtering, capture, sequence/epoch behavior, and tradebook matching remain intact.

Existing evidence JSONL files are not a complete replay input for this implementation: they may omit 3K walls because of the old floor. Use deterministic raw callback fixtures for integration tests. Broader capture/export support can be designed separately after V1.

## 9. Files and implementation sequence

Suggested class names are concrete responsibilities; small adjacent value types may share files/package-private classes where appropriate. Maintain Java 11 compatibility, immutable outputs, explicit JSON field names, and no direct Bookmap dependency in the `signal` rule layer.

### Milestone 1 — Pure composition rules

Add:

- `patterns/PatternEvent.java`, `PatternEventType.java`, `PatternSide.java`, `PatternMeaning.java`.
- `signal/SignalComposerConfig.java`, `ConfirmationStrength.java`, `SignalCandidate.java`, `SignalState.java`, `TradingSignal.java`, `DevelopingContext.java`, `CompositionUpdate.java`.
- `signal/PatternEventStore.java`, `ConfirmationMatcher.java`, `ConfirmationStrengthClassifier.java`, `TriggerRequirementPolicy.java`, `SignalExplanationBuilder.java`, `SignalComposer.java`.
- Unit tests in `src/test/java/com/bookmap/plugin/rong/signal/` and event-model tests in `patterns/`.

Exit criterion: all design examples work using explicit synthetic events, including late promotion, immediate normal-size validation, and same-ID revisions. Tests inject event time; no sleeps, broker, filesystem, or Bookmap instance are required.

### Milestone 2 — Observations from real callbacks

Add `patterns/PatternObservationEngine.java`, `EventTimeWallTracker.java`, `PatternEventNormalizer.java`, `BidFailureDetector.java`, and `OfferInteractionDetector.java`. Give the observer its own `ReappearPatternDefinition`/`StepPatternDefinition` instances and independent 3K-capable lifecycle.

Retain legacy constructors/definitions/output. Add detector and pipeline fixtures using real depth/trade/BBO/timestamp sequences. Cover actual evidence needed for the 60K growth/rejection plus 3K withdrawal example; a synthetic composer event alone is insufficient.

Exit criterion: raw callback fixtures generate the correctly sized normalized events and composed output. Replaying identical callbacks at different wall-clock speeds yields identical decisions and market timestamps.

### Milestone 3 — Plugin integration and operator settings

Modify `RongPlugin.java`, `IndicatorConfig.java`, and `IndicatorSettingsPanel.java`; add the signal store/painter. Register/unregister painters and listeners, propagate market time/readiness, handle toggles/epochs, and preserve observer-only activation. Add plugin lifecycle/activation/indicator tests.

Exit criterion: enabling the composer works without legacy pattern/tradebook enablement; disabling removes its display/state; stop/reattach and session transitions cannot retain context. Existing manual/native trading behavior passes regression tests.

### Milestone 4 — Explanations, config docs, release verification

Add the config template and usage documentation in `config/README.md`, `README.md`, and `docs/signal-composer.md`. Modify `build.gradle` for composer-config test isolation. Add deterministic formatting/serialization tests and a composed-signal badge render test. Extend `ReleaseJarTest` with a new-enum/config/compose smoke case against the obfuscated artifact.

Keep explicit JSON field names so obfuscation cannot rename configuration/output fields. Update `PluginVersion.java` and `build.gradle` together only under the repository's release convention. Current build version is 1.31; an existing Cairo document mentions 1.32, so derive the actual release artifact name from the build rather than copying that document's filename.

Exit criterion: complete build passes and a Bookmap replay smoke test confirms the chart and explanations. Composer remains disabled by default in the shipped template.

## 10. Test and acceptance matrix

### Required design cases

| Case | Expected result |
| --- | --- |
| 6K bid breakdown with nearby 7K offer rejection | SHORT, normal 5K requirement |
| 3K bid cancellation with only 7K offer rejection | No signal; candidate pending until expiry |
| Nearby 60K offer rejection followed by 3K bid cancellation | SHORT; exceptional evidence lowers requirement to 3K |
| 60K offer rejection alone | No signal; SHORT context waiting for BID_FAIL >= 3K |
| Large bullish offer event without a bid-hold event | No LONG signal |
| Confirmation before trigger | Matches when current and inside both time/price limits |
| 5K bid breakdown followed by 15K offer rejection | Immediate SHORT with NONE, then evidence revision under same ID |
| 60K offer rejection, 90 seconds later 3K cancellation | No signal despite retained history |
| 3K bid cancellation followed by 60K offer rejection within the active window | Pending candidate becomes SHORT at confirmation observation time |
| 5K bid hold without offer confirmation | LONG with NONE; bid trigger remains mandatory |
| 3K bid hold plus qualified 60K offer breakout | LONG with exceptional confirmation |
| 4K bid trigger plus qualified 25K offer confirmation | Valid under the configured VERY_STRONG rule |

### Rule and history edge cases

- Exact thresholds 3,000/4,000/5,000 and band boundaries 1x/2x/5x/10x; one share below each boundary.
- Below-minimum trigger cannot be rescued; several normal confirmations cannot lower the threshold.
- Invalid BID/OFFER meaning combinations, wrong direction, alias, epoch, or price relationship are rejected.
- Price distance exactly 20 ticks accepted, 21 rejected; expired or not-yet-observed evidence rejected.
- Time-window boundary accepted, one millisecond beyond rejected; nanosecond timestamps preserved for ordering.
- Duplicate/revised step episodes do not create a new candidate, extend validity, or duplicate a signal.
- Simultaneous reappear and step detections for the same bid wall produce one candidate/signal while retaining both observations.
- Late evidence upgrades once; no revisions when evidence is unchanged. Tie-breaking is deterministic.
- A later exceptional confirmation cannot change the recorded threshold/evidence that accepted an earlier normal-size signal; its explanation identifies the later arrival.
- Delayed occurrence inserted correctly; genuine backward callback time resets the epoch.
- Event/candidate caps, trigger eviction, long size arithmetic, idle timestamp-driven pruning, and context expiry.
- Opposing bid behavior/drift invalidates an active candidate without altering a historical emitted result.

### Detector and end-to-end cases

- Qualified 3K bid cancels under a book whose legacy dynamic wall threshold exceeds 5K; composer still observes it.
- 60K offer growth, actual approach, actual rejection, and 3K withdrawal produce one explained SHORT.
- Huge offer growth alone remains UNKNOWN; stale peak/reference size cannot confer exceptional confirmation.
- A consumed bid without a trade below it produces no `BID_BREAKDOWN`; actual below-level print completes it.
- Unknown trade side or ambiguous/mixed consumption does not become definite withdrawal.
- Flash walls, temporary clear/reload, probable relocation, incomplete rejection, and offer breakout during rejection produce no false completion.
- LONG bid reappear/step-up events use the active replacement size, including when the old reference was much larger.
- Before snapshot, after close, invalid tick size/price, and fallback timestamps produce no composed signal.
- New session, replay seek, disable/enable, and detach clear old evidence/context; display TTL is separate from rule time.
- Two instruments do not share history. Concurrent painter reads see immutable snapshots.
- A new attachment does not reset the shared master toggle or load different rules during the same plugin activation; test configuration is independent of local user files.
- Composer works with legacy automation off and no enabled pattern tradebooks; legacy output stays unchanged when composer is on/off.
- Observer-only replay needs no secrets/broker startup; normal missing-secrets activation remains inactive.
- Existing Cairo observation/export tests pass unchanged. No broker action is caused by composition.
- Release-JAR enums, config parsing, explanation fields, and chart rendering survive obfuscation.

### Verification commands

Run from `C:/Users/lingr/trading/bookmap-plugin` using a configured JDK compatible with the Gradle wrapper and Java 11 target:

```powershell
.\gradlew.bat test --tests "com.bookmap.plugin.rong.patterns.*" --tests "com.bookmap.plugin.rong.signal.*"
.\gradlew.bat build
```

`build` also checks native-engine independence and the actual obfuscated release JAR. Preserve the existing execution/parity tests; tests use fake services and must not place live orders.

For manual validation, use existing `cairo-observation.json` with `observerOnly: true` and `sourceMode: "replay"`; enable the new composer config for the test symbol. Do not launch a native broker runtime for replay. Verify context expiry, late confirmation, both directions, toggles, and seek/reattach behavior in Bookmap. Inspect the concise marker and full local explanation together.

Planning-stage verification: source and existing tests were inspected. An attempt to run the existing `patterns.*` and `OrderWallChangeTrackerTest` suites failed before Gradle started because this shell has neither `JAVA_HOME` nor `java` on PATH. No test pass or build pass is claimed by this plan.

## 11. Definition of done for the coding agent

- Actual callback sequences, not only hand-constructed events, demonstrate 60K confirmation + 3K bid trigger.
- Both directions require the appropriate bid meaning, with optional offer confirmation and configured threshold relaxation.
- Confirmation before/after the trigger works with expiry, locality, revisions, and no additive scoring.
- Composed output explains measured sizes, trigger/confirmation roles, prices, sequence, normal/applied thresholds, and inference limits.
- Developing context is visible and distinguishable from a valid signal.
- Configuration is validated, disabled by default, and independent of trading secrets/tradebook enablement.
- Readiness, event-time/replay behavior, bounded memory, and attachment lifecycle are covered.
- Legacy badges, Cairo contracts, and all manual/native execution paths retain their existing behavior.
- Focused tests, full build/release tests, and observer-only replay smoke verification pass, or any unavailable manual environment is clearly reported.
- Deliver source changes, tests, operator documentation, and a concise implementation summary. Do not connect the composer to order execution as part of this task.
