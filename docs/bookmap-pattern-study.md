> Historical pre-consolidation audit/harness. Legacy classes have been removed; current observer/pipeline tests and the [bridge guide](cairo-evidence.md) describe the implemented system.

# Bookmap pattern consolidation: behavioral study

This study supersedes the analysis behind the first consolidation proposal. It distinguishes observed code behavior, reproduced results, and recommendations. No production changes were made.

Baseline: `091aaa68cfcdc26d49e9b5425a60df3365e2d22c`. The study covered the plugin's label/change trackers, legacy pattern engine, semantic observer, Composer, displays, configuration, manual/native dependencies, both Cairo protocols, and the corresponding Cairo receivers/recognizers. Cairo and ViteApp were read-only references; instructions quoted inside their documentation were not treated as task authorization.

## Finding

The overlap is not simply three equivalent signal features. There are five separate reconstructions of wall state in the plugin, plus a full order-book model and Cairo's downstream setup recognizers. Some duplication is implementation; some similar names refer to materially different rules; some advertised behavior is filtered out before rendering.

The right consolidation boundary is **shared measurements and identities**, followed by **pattern detection, quality annotation, setup composition, and presentation**. The existing observer provides useful lifecycle machinery, but making all its current rules authoritative without comparison would preserve several surprising behaviors and drop useful legacy details.

## 1. What exists and who consumes it

| Path | State and input | Actual consumers | Important boundary |
| --- | --- | --- | --- |
| `OrderBookState` | Full absolute bid/offer depth and size histogram | Dynamic wall threshold; labels; legacy engine; entry wall snapshots | Shared floor/97th percentile also affects manual entry context; this is not just cosmetic |
| `OrderWallLabelTracker` | Own pending walls, segments, current sizes, peak/path history | Size labels and historical wall segments | Default 1-second initial persistence and stable decreases; a doubling can split a visual segment |
| `OrderWallChangeTracker` | Own depth map/histogram, pending material changes, receipt-time trades, scheduler | Change store/painter, possible sound, entry-retest satisfaction | Runs regardless of alert visibility; pending decisions use wall-clock receipt time |
| `BookmapPatternEngine` | Own wall phases, peak sizes, trades, extremes, references | Four directional scored pattern badges; two selected Cairo observation exports | Runs if legacy checkbox OR older Cairo observation configuration is enabled |
| `PatternObservationEngine` | Own absolute wall lifecycle, snapshot, attribution, relocation, extremes, references, clock | Composer's raw semantic events/logs/history | Currently embedded in `SignalCompositionPipeline` and fed only while composition runs |
| `SignalComposer` | Pattern history, candidates, confirmations, transitions | Advisory signal painter, waiting context, inspection, logs | Strategy policy over events; no order submission; offers do not independently trigger setups |
| `CairoEvidenceRecorder` | Another wall map/peak/trade history plus trade/BBO capture | `cairo_evidence` stream, reconnect history, optional JSONL capture | Independent opt-in; dynamic large-wall threshold; export wall-end is not observer stable-clear |
| Cairo `BookmapEvidence` / `OfferObservations` | Touches, before/after swings, crossing/return/hold state over evidence | Setup cards, entry association/archive, explanations, liquidity target context | Additional pattern system with different definitions; not an alias for Composer |

`ReappearPatternDefinition` and `StepPatternDefinition` are already reused by both pattern engines. Consolidation should not start by rewriting their four patterns. Duplicate orchestration supplies these shared definitions with different snapshots, size bases, qualification times, eligibility, and reset behavior.

The selected Cairo observation export carries only Bid Step Up and Bid Reappear. The separate evidence stream enables broader recognition and current liquidity views in Cairo. Removing the legacy engine without handling both paths would leave part of this architecture unaccounted for.

### Semantic catalog and composition roles

| Emitted pattern | Detection family | Current Composer role | Legacy overlap |
| --- | --- | --- | --- |
| Bid Bounce | Persistent wall test, percentage rebound and trade confirmation | Bid hold; LONG trigger | No corresponding legacy four-pattern detector |
| Bid Reappear | Consumed reference followed by same/higher defended replacement | Bid hold; LONG trigger | Shared reappear definition, different reference/qualification/quality context |
| Bid Step Up | Higher defended replacement relative to qualified/cleared reference | Bid hold; LONG trigger | Shared step definition, different size/revision context |
| Bids Cancelled | Stable loss, inferred withdrawal, usable coverage and no likely move | Bid failure; SHORT trigger | Material pull alerts use looser/different classification and range policy |
| Bid Breakdown | Probable consumed clear plus below-wall print | Bid failure; SHORT trigger | Old internal breakdown enum does not require the print |
| Offer Reappear | Consumed reference followed by same/lower defended replacement | Bearish offer confirmation / SHORT context | Shared definition; legacy gives a standalone SHORT quality badge |
| Offer Step Down | Lower defended replacement relative to qualified/cleared reference | Bearish offer confirmation / SHORT context | Shared definition; legacy gives a standalone SHORT quality badge |
| Offer Bounce | Persistent wall test, percentage retreat and trade confirmation | Bearish offer confirmation / SHORT context | Distinct from Cairo's tick bounce and cross-return rejection |
| Offer Size Increasing Hold | Offer growth plus completed hold, revising same hold interaction | Bearish offer confirmation / SHORT context | Material increase alerts do not establish a hold |
| Offer Breakout | Probable consumed clear plus above-wall print | Bullish offer confirmation / LONG context | Old internal breakout enum and Cairo price crossing use different rules |
| Offer Size Increase | Qualified offer grows at least 25% | Unknown meaning; history/diagnostics only | Material increase uses threshold/delta, not percentage growth |

`UNKNOWN_BID_LOSS` and `UNKNOWN_OFFER_LOSS` exist in the enum but are not currently emitted. There is no semantic `OFFERS_CANCELLED` event, although material-change alerts support offer pulls. Additions and four directional moves also remain liquidity observations rather than Composer triggers. This asymmetry is existing strategy scope, not an accidental omission that a structural merge should fill automatically.

## 2. The breakout checkbox currently has no effective breakout rendering path

The current code produces dedicated `BID_BREAKDOWN` / `OFFER_BREAKOUT` types when a qualifying consumed drop leaves size below the wall-change threshold, and the checkbox plus matching tradebook gate permits it. But:

1. The generated events are material changes and have `tradeConsumption = true`.
2. `OrderWallChangePainter.visibleEvents` and its badge builder require `isActiveLiquidityAlert()`.
3. That predicate explicitly excludes trade consumption.
4. `OrderWallLabelPainter.findRecentChange` skips material changes entirely, despite retaining an older wall-break formatting function.
5. `RongPlugin.playWallChangeSound` also requires `isActiveLiquidityAlert()`.

Therefore, normal dedicated break events are retained internally but excluded from both current rendering routes and sound. The comparison harness also calls the alert visibility filter: generated break events have zero visible liquidity alerts. The size-label conclusion is a source trace, not a live Bookmap screenshot test.

This corrects the first explanation and plan: the checkbox is currently an effectively dormant display classification, not a reliable second visible breakout system. No detection callback submits an entry. Retest behavior reads materiality/consumption/size, not the dedicated break enum, so deleting that enum classification is different from deleting retest detection.

Sources: `OrderWallChangeTracker.java:363,527`; `OrderWallChangeEvent.java:214,220`; `OrderWallChangePainter.java:405`; `OrderWallLabelPainter.java:423`; `RongPlugin.java:832,853,961`.

## 3. Thresholds and size bases are not interchangeable

| Purpose | Current threshold / basis | Consequence |
| --- | --- | --- |
| Labels, legacy wall qualification, Cairo evidence walls, entry wall snapshot | Greater of user floor (default 5K) and 97th percentile across both sides | Raising the visible wall floor can change manual entry context and exported liquidity, but does not change Composer admission |
| Material changes and retest fills | Greater of 5K and fifth-largest level, capped at 10K | Material means crossing this threshold OR absolute delta strictly greater than it; a crossing can report a much smaller delta |
| Semantic wall tracking | 1K absolute floor | Tracks references below the size required to emit most semantic patterns |
| Reappear, step, bounce, growth, inferred bid withdrawal events | At least 3K, inclusive | Step references can qualify at 1K, while a replacement first emits at 3K |
| Semantic confirmed bid/offer break events | At least 1K removed size, inclusive | 1K–2,999 events enter history but cannot become bid trigger candidates |
| Composer trigger validation | Normal 5K; absolute minimum 3K | Compatible exceptional offer evidence can permit 3K; very strong permits 4K |
| Legacy reappear comparison | Replacement peak >= 50% of cleared reference peak | Prior peak growth changes eligibility/quality |
| Observer reappear comparison | Replacement qualification snapshot >= 50% of immediately pre-clear snapshot | Not the same reference size as legacy, even though the definition class is shared |

The “Wall threshold floor” control therefore does not govern all Bookmap analysis. A reproduced case sets it to 20K: 6K Bid Step Up still becomes a Composer LONG while the legacy engine produces no pattern. A consolidated interface must identify the purpose of each threshold; silently collapsing them to one number would alter strategy and manual-entry behavior.

## 4. Attribution and lifecycle comparison

| Behavior | Material-change tracker | Legacy pattern engine | Semantic observer |
| --- | --- | --- | --- |
| Decision time | Receipt-time scheduled stability, normally 500 ms | Market time, but allows fallback timestamps | Proven market timestamps, readiness, regular hours, epochs |
| Loss start | First material pending change, aggregates later callbacks before scheduled evaluation | Remaining size <= 10% of historical peak | One depth update leaves <= 10% of immediately preceding size |
| Consumed loss | >=70% matching-aggressor volume OR, below threshold, >=10% same-price volume on either known side | >=70% same-price matching-aggressor volume relative to peak loss | >=70% same-price matching-aggressor volume relative to stable pre-clear loss; usable coverage required |
| Consumption completion | At receipt-time stability decision | Can complete immediately when sufficient volume is available; 500 ms is abandonment delay otherwise | Requires stable clear delay before attribution |
| Withdrawal | Non-consumed material loss gets pull-like presentation, within configured cancellation band | Does not produce a withdrawal pattern | <=10% matching volume, usable coverage, no likely relocation; emits bid withdrawal only |
| Confirmed price break | No subsequent price print required | No explicit bid/offer break pattern in its four-type catalog | Actual print beyond wall within 3 seconds; one tick by default |
| Moves | Pairs material pending increases/decreases; no consumption move pairing | No independent move evidence | Pairs same-side increases with clears; probable relocation can override consumption attribution |
| Session | No regular-hours detector gate | Regular session; resets date/session transitions, not same-day backwards time | Regular session; detects backwards callback time, clears state, requires fresh readiness |
| Evidence loss | Receipt-time trade retention; no equivalent usable/gap/warmup classification | Unknown aggressor ignored in consumed-volume total | Explicit coverage checks and bounded buffer resets |

The semantic tracker does not accumulate every gradual decrease into a whole-wall loss. Its clear condition compares consecutive depth sizes. Its emitted `removedSize` is the size just before the final qualifying drop, not necessarily the original wall size. This is a rule/data-model decision to resolve, rather than assuming the observer is an already-complete replacement for all other measurements.

## 5. Thirteen executed comparisons

The companion harness feeds compiled production classes with synthetic Bookmap-equivalent callbacks. It uses 1-cent ticks, regular-session market time, seeded extremes, default thresholds/configuration, enabled legacy eligibility and break predicates, and the production 500 ms change delay. Small background levels keep the 97th percentile below the 5K floor. Outputs are detector results, not evidence of trading profitability.

Rows labeled “old break” mean internal enum classification; section 2 explains why these events are not currently painted. Revisions are updates to an episode/candidate, not necessarily additional distinct signals.

| Case | Observed result | Consolidation implication |
| --- | --- | --- |
| 1. 8K offer consumed down to 3K; no trade above | Old `OFFER_BREAKOUT`; observer emits nothing; no composed setup; no break sound/alert | Partial consumption and confirmed break must remain distinguishable facts |
| 2. 8K offer removed, only 800 same-price sell-aggressor shares, then trade above | Old `OFFER_BREAKOUT` via 10% fallback; observer emits no consumed breakout | The label is not equivalent to strong buy-consumption evidence |
| 3. 8K offer removed with 8K buy volume and an above-wall print | Observer emits `OFFER_BREAKOUT`; Composer shows LONG waiting context without a bid trigger | Composer adds a bid-first strategy rule; it is not a complete replacement for individual pattern information |
| 4. Bid 8K -> 4K -> 2K -> 0 with matching trades and below-wall print | Old break aggregates 8K -> 0; observer emits a 2K breakdown; Composer rejects it as below minimum | Whole-wall loss and final-drop size can lead to different accepted setups |
| 5. 8K offer reference, new 6K offer one tick lower | Legacy SHORT step badges/revision; observer step/revision; Composer only SHORT waiting context | Replacing legacy badges with Composer alone removes an independently useful offer-pattern view |
| 6. 8K bid consumed, new 6K bid one tick higher | Both engines detect reappear AND step; Composer publishes one LONG with three revisions | Interaction-level dedup already exists for composition; do not remove distinct observations to solve badge duplication |
| 7. New 6K bid step qualifies, then disappears before delayed update | Legacy emits another step badge at the 1-second update; observer rejects that delayed emission | Shared definitions are insufficient; runtime validation must check current wall identity/presence |
| 8. New bid step first 4K, grows to 6K before its delayed update | Observer revises size from 4K to 6K; Composer emits no setup; legacy qualifies later at 6K | Current candidate retains its initial 4K trigger; pending growth policy needs a separate explicit decision |
| 9. Same-day backward seek, then new bid step | Legacy uses pre-seek reference and emits; observer emits nothing while unready | Canonical seek/readiness must govern all semantic consumers |
| 10. User wall floor raised to 20K, 8K reference and 6K bid step | Legacy emits nothing; observer/Composer still produce LONG at 6K | A single “wall floor” label obscures separate thresholds |
| 11. Bid step 50 ticks above reference, >20 ticks from quote midpoint | Legacy emits low-score 30/35 step badges; observer retains step/revision; Composer emits no setup | Observation existence, quality score, and local setup eligibility are different decisions |
| 12. Bid grows 8K -> 16K, shrinks to 8K, then final 8K consumed; new 6K bid | Legacy emits step but not reappear; observer emits both and Composer combines them | Historical peak and pre-clear size must be retained explicitly; same names do not guarantee same eligibility |
| 13. Repeat case 4 with slower receipt intervals but identical market timestamps | Old break now aggregates 8K -> 4K; semantic result remains a 2K breakdown | Receipt-time aggregation depends on callback delivery/replay speed; switching clock domains requires a declared policy change |

Complete machine-readable results, source, and reproduction instructions are in [the study companion](bookmap-pattern-study/README.md). The harness deliberately avoids APIs, WebSocket startup, broker calls, paid inference, capture writes, and production configuration.

## 6. Quality scoring is separate value, not duplicate composition

Legacy scoring begins at 40 and clamps to 0–100. Quality tiers are <40, 40–59, 60–79, and 80+. The score does not gate detection or send orders. Contributions include:

| Factor | Contribution |
| --- | --- |
| Wall size relative to its effective dynamic threshold | +5 at 1.5x, +10 at 2x |
| Persistence | +5 at 3 seconds, +10 at 10 seconds |
| Growth | +5 at 25%, +10 at 100% |
| Alignment with configured non-order price lines/zones | +10; two-tick tolerance |
| Nearby opposing wall relative to reference | -10 at 1.5x, -20 at 2x |
| Trigger too far in the intended direction from reference | -10 beyond nearby-liquidity distance |
| Reappear same/better defensive price | +5 / +10 |
| Reappear replacement >=75% / >=100% of reference | +5 / +15 |
| Step near quote, at least reference size, defended for a second | +10, +10, +5 respectively |

Nearby-liquidity distance is `max(10 ticks, round(0.25% of reference tick price))`, not Composer's default 20-tick matching limit. Current delayed step scoring uses a captured `WallSnapshot` rather than a fully refreshed current wall, which helps explain case 7.

The observer normalizer keeps current size, a reference phase ID, and behavior, but discards most candidate/scoring context: reference size/price, replacement ratio, initial/peak sizes, threshold, defended duration, and level/opposing-liquidity details. Keeping scores without a second detector requires extending the evidence contract. Deleting scores because they are not Composer requirements is an unsupported product choice.

Sources: `BookmapPatternScorer.java`; `BookmapPatternEngine.java:448`; `PatternEventNormalizer.java:21`; `ReappearPatternDefinition.java:29`; `StepPatternDefinition.java:33`.

## 7. Composition, identity, and visibility

- Only `BID_HOLD` and `BID_FAIL` create candidates; offers supply complementary confirmation/context. A normal 5K bid trigger can validate without any offer confirmation. Strength is selected from the strongest compatible offer; sizes are not summed.
- Matching uses same alias, epoch, tick size, market provenance and usable coverage; five-minute before/after windows; 20 ticks of locality and two ticks of directional tolerance. Price drift can invalidate an active candidate.
- Candidate identity is alias + epoch + direction + interaction. Event identity adds a pattern episode. Thus one replacement can legitimately yield reappear and step evidence but one composed setup, as case 6 demonstrates.
- `SignalCandidate.trigger` is immutable from initial candidate creation, not merely from first validation. Higher-size revisions enter supporting evidence but do not replace the trigger used by validation (case 8). The existing validated-revision test intentionally freezes already validated trigger/decision history; it does not settle pre-validation growth semantics.
- Legacy pattern revisions get a fresh UUID/receipt time and can refresh their 30-second display deadline and change their chart anchor. Composer revisions preserve first validation time and original receipt deadline. These are different rendering contracts.
- Composer retains five-minute history (2,048 events) and up to 64 candidates; the inspection panel displays only two newest events per direction/side group. Unknown meaning remains in history/logs/diagnostics but is omitted from directional groups. “Inspectable” does not currently mean all events have a full browsable UI.
- The global indicator map is in-memory. Composer rules load at first attachment and share a revision; Cairo configuration loads on attachment; broker configuration supplies tradebooks. Checkbox migrations must not invent persistent settings that do not exist.

## 8. Cairo contains further similar names with different meanings

The legacy `cairo_observation` protocol accepts only Bid Reappear/Bid Step Up, with source/symbol/episode revision dedup and bounded reconnect snapshots. It has no equivalent Composer semantic history/epoch model. Its current producer reports mode as unknown.

The separate `cairo_evidence` protocol exports selected wall starts/updates/ends, all retained trades and BBO transitions, explicit epoch/sequence/coverage/mode, and capture status. It is the input to Cairo's additional recognition and liquidity target context. It is not a complete raw-depth recording.

Cairo's bid setup recognizer classifies before/no/after-bounce breakdown histories. Its default bounce is two ticks over 200 ms, with five seconds of continuous history required to assert no bounce. A trade below a touched bid starts a setup; this does not require the plugin observer's 70% consumed stable clear. Ask-below and new-observed-low facts are tracked separately.

Cairo's offer observations start from a price crossing of an observed large offer; the code explicitly does not claim order consumption. A return below within five seconds followed by one second of observed below-price/quote behavior can become a confirmed offer rejection; overshoot above 1% becomes extended. This is a cross-and-return pattern, whereas Composer's Offer Bounce is a test from below and retreat while the wall remains intact (0.05% approach, 0.1% retreat, 500 ms confirmation).

Do not map either Cairo bounce or offer rejection directly onto Composer event names. Preserve descriptive subtype, parameters, evidence and source when cross-linking. Raw evidence must remain available for entry association, target liquidity, archived interpretation, and recognition that the plugin does not implement.

Read-only sources: `cairo/src/engine/BookmapReceiver.mts`, `BookmapEvidence.mts`, `OfferObservations.mts`, `cairo/src/shared/BookmapPatterns.mts`; plugin `CairoEvidenceRecorder.java` and `CairoObservationExport.java`.

## 9. Execution dependencies that constrain consolidation

| Dependency | What it actually uses | Constraint |
| --- | --- | --- |
| Bid/offer entry retest | Material consumed decrease; previous size strictly above material threshold; partial fills can qualify | Must not wait for 90% removal, a beyond-wall trade, Composer acceptance, or display enablement |
| B/S hover hotkeys | `getPrimaryWallReversalTradebook`, configured entry methods, explicit user action | Retain lookup/routing even if legacy detection tradebook gating is removed |
| Entry wall snapshot / liquidity sizing | User floor/97th percentile and current book | A renamed/merged floor cannot silently change execution inputs |
| Native wall-reversal strategies | Explicit supported tradebook IDs and entry-area rules | These are strategy/execution definitions, not pattern-detector classes to delete |
| Trading notification sound | Native action notifications | Distinct from non-consumption liquidity alert sound and advisory display |
| Cairo archives/position association | Raw evidence and separate setup assessment | Migrating two observation names alone does not preserve these capabilities |

The advisory paths currently submit no orders. Retest satisfaction can change whether a later manually requested entry is allowed, so it is an execution-relevant side effect even though no order is sent by the detector.

## 10. Architectural alternatives

| Option | Benefit | Cost / limitation | Assessment |
| --- | --- | --- | --- |
| Delete legacy outputs and keep current Composer pipeline as-is | Smallest removal | Loses scores/offer observation view; keeps sizing/growth surprises and separate evidence/retest measurements | Not justified by the current study |
| Keep engines, unify only controls/rendering | Fast visible cleanup | Independent wall histories, attribution, resets and sizes still diverge | Useful transition, not finished consolidation |
| Shared market facts and identities; one pattern catalog; quality and composition consume those events | Removes duplicated semantic decisions without conflating pattern quality and setup acceptance | Requires richer evidence, explicit migration policies, external compatibility checks | Recommended target |
| Replace all detectors, displays, Cairo and execution rules in one rewrite | Could reduce class count | Unbounded scope; no stable behavioral baseline for strategy changes | Reject as initial scope |

Existing classes should be extracted and improved incrementally; the target is not a new all-purpose strategy engine. Shared factual state can support multiple explicitly named policy views where clock, threshold, or lifecycle genuinely differs. A display segment, stable semantic wall phase, and export wall ID must not be forced into one lifecycle just because all are called “wall.”

## 11. Verification and limits

Executed existing tests in three focused batches: 204 pattern/Composer/wall tests across 47 suites; 34 threshold/entry-snapshot/hotkey tests across six suites; 13 retest/routing/config/format tests across three suites. **251 tests passed, zero failed/skipped.** Gradle reported existing deprecation warnings. Thirteen behavioral comparisons ran against compiled production classes; their output is saved with the harness.

The existing tests cover many contracts but did not prevent the observed gaps: dedicated-break renderer integration, same-day legacy seek reuse, gradual-loss size parity, vanished-wall delayed legacy revision, and pending-trigger size promotion need specific regression scenarios. Passing the baseline does not settle the intended trading rules.

No live Bookmap rendering test, recorded-market replay, release obfuscation build, or live Cairo integration was run for this study. Those remain migration acceptance checks. Synthetic output establishes current code differences; it does not establish frequency, usefulness, or profitability on real market data.

The [revised implementation plan](bookmap-pattern-consolidation-plan.md) makes the required decisions and completion gates concrete.
