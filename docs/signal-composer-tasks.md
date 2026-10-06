# SignalComposer: sequential coding tasks

Prepared 2026-10-06. This checklist breaks the [implementation plan](signal-composer-implementation-plan.md) into small, ordered tasks. The plan remains the specification for behavior and numerical defaults.

## How to use this checklist

Work from SC-01 onward, one task at a time. Each task assumes the earlier tasks are complete. Finish its implementation and focused verification before moving to the next. Include the relevant tests with the behavior they verify; do not postpone them to a final testing task.

Mark a checkbox only when its completion criteria are met. After each task, append a short entry to `docs/signal-composer-progress.md` containing the task ID, changed files, checks/results, and any limitation. Do not claim a test passed when it could not run. Keep new code compiling at every step; add complete, usable components rather than empty production stubs for future tasks.

An agent working through the entire checklist can continue to the next task after recording completion. An agent assigned a specific task ID should finish that task and report its result without expanding the scope.

All Java paths below are relative to `src/main/java/com/bookmap/plugin/rong/`; matching unit tests go under `src/test/java/com/bookmap/plugin/rong/`. Use Java 11 syntax and reuse `patterns.Direction`. Tasks that add a class include its focused tests in the same task.

Scope throughout: advisory composition only; preserve legacy scores/badges, Cairo contracts, and manual/native execution. Keep the new detection floor independent of legacy wall thresholds. Use market event time for rules. No additive scoring, broker actions, new wire protocol, or cross-repository changes.

To assign one task, use this prompt and replace the ID:

> Read `docs/signal-composer-implementation-plan.md`, `docs/signal-composer-tasks.md`, and the current progress log in bookmap-plugin. Implement SC-XX only after checking its prerequisites. Run its focused verification, update its checkbox and progress entry accurately, and report the result. Preserve the plan's advisory-only scope and existing behavior.

## A. Foundations

Specification: implementation plan sections 2, 5, and 7.

- [x] **SC-01 — Establish the code and test baseline.** Read the repository instructions and current implementation; record HEAD, working-tree changes, and JDK/Gradle availability in the new progress log. Run the existing pattern tests and `OrderWallChangeTrackerTest` when a compatible JDK is available. **Complete when:** the baseline results or exact environment limitation are recorded. The planning shell had no configured Java; locate/configure an existing suitable JDK if available, and record any remaining blocker without inventing a passing baseline or changing the project's toolchain.

- [x] **SC-02 — Define normalized pattern events.** Add `patterns/PatternEventType.java`, `PatternSide.java`, `PatternMeaning.java`, and immutable `PatternEvent.java`, including typed evidence, size basis, alias/epoch, canonical interaction ID, stable episode/event IDs, revision, integer price, occurrence/observation times, and timestamp provenance. **Complete when:** tests cover immutable evidence, side/meaning validation, price/size validation, stable IDs across revisions, and unknown aggressor/evidence states. Leave legacy `PatternType` unchanged.

- [x] **SC-03 — Define the composer configuration and defaults.** Add immutable `signal/SignalComposerConfig.java` with the exact size, time, price, strength, detector, and capacity defaults from plan section 7. Model settings in typed fields rather than ad hoc map lookups. **Complete when:** tests demonstrate disabled defaults, separate trigger/confirmation baselines, the 3K observation floor, and all six threshold-policy entries. No local-file loading yet.

- [x] **SC-04 — Validate and parse configuration.** Implement explicit Gson parsing and validation in `SignalComposerConfig`, including ordered bands, consistent unrelaxed requirements, hard minimums, detector ratios, observation floor, retention/window relationships, and bounded capacities. Compute an effective configuration revision. **Complete when:** supplied JSON objects cover valid custom values, malformed types, conflicting thresholds, and invalid ranges; invalid config returns an actionable disabled result. JSON field names must remain explicit for obfuscation.

- [x] **SC-05 — Load local configuration and isolate tests.** Add the bounded-file loader for `%USERPROFILE%/bmtrader/signal-composer.json` and `bmtrader.signalComposerConfig`. Extend `build.gradle` test properties to prevent tests inheriting the user's local composer config. **Complete when:** temporary-file tests cover missing, valid, oversized, and invalid files; missing config gives disabled usable defaults; invalid config cannot be enabled. Production plugin startup is not connected yet.

- [x] **SC-06 — Define composition state and immutable outputs.** Add `signal/ConfirmationStrength.java`, `SignalState.java`, `SignalCandidate.java`, `TradingSignal.java`, `DevelopingContext.java`, `CompositionUpdate.java`, and reset reasons. Distinguish mutable candidate state from immutable public snapshots, including an immutable first-validation record and subsequent evidence. **Complete when:** model tests verify defensive copies, revisions, before/after timing fields, and absence of score/order-command fields. Keep this layer independent of the Bookmap API.

## B. History and pure rule components

Specification: implementation plan section 6.

- [x] **SC-07 — Store and revise semantic events.** Add `signal/PatternEventStore.java` with alias/epoch scoping, time ordering, event/episode lookup, and revision upserts. Handle a late-observed event whose occurrence timestamp is earlier than the current watermark. **Complete when:** tests show correct ordering, duplicate suppression, revision replacement, and rejection of a foreign alias/epoch without treating delayed occurrence as replay seek.

- [x] **SC-08 — Bound and prune event history.** Add retention, event-count caps, epoch clearing, and explicit eviction results to `PatternEventStore`. Pruning must work from market timestamp updates even without a new semantic event. **Complete when:** tests cover 120-second retention, the 2,048-event limit, exact expiry boundaries, and identifiable eviction of a trigger record.

- [x] **SC-09 — Match local confirmation evidence.** Add `signal/ConfirmationMatcher.java`. Match the correct offer meaning, alias/epoch, occurrence window, already-observed watermark, current evidence age, tick proximity, and directional price relationship. Return all valid matches with signed time deltas and before/after/simultaneous ordering. **Complete when:** tests cover both directions, evidence before and after a trigger, 20/21-tick boundaries, stale/unobserved evidence, and wrong-side/location rejection.

- [x] **SC-10 — Classify strength and select trigger requirements.** Add `signal/ConfirmationStrengthClassifier.java` and `TriggerRequirementPolicy.java`. Choose the strongest valid confirmation with deterministic tie-breaking, then apply 5K/4K/3K requirements according to configuration. **Complete when:** tests cover exact 1x/2x/5x/10x bands, fractional division, large `long` quantities, trigger equality/below-threshold cases, and several normal confirmations never becoming exceptional by summation.

- [x] **SC-11 — Build factual signal explanations.** Add `signal/SignalExplanationBuilder.java`. Explain direction, actual trigger behavior/size basis, real prices, confirmation timing/locality, normal/applied thresholds, and inferred attribution. Support later-confirmation wording without rewriting initial validation. **Complete when:** deterministic tests cover the 60K-offer/3K-bid example, a normal trigger with no confirmation, and confirmation arriving after validation. Do not invent executed absorption from quote-defense patterns.

## C. Composer state machine

Specification: implementation plan section 6 and required design cases in section 10.

- [x] **SC-12 — Evaluate newly arriving bid triggers.** Add `signal/SignalComposer.java` using the completed store, matcher, classifier, policy, and explanation components. Accept BID_HOLD for LONG and BID_FAIL for SHORT; create candidates and immediately validate sufficient triggers. **Complete when:** tests show 5K bid hold alone produces LONG, 6K bid breakdown produces SHORT, offer-only events produce neither, and below-minimum triggers cannot be rescued.

- [x] **SC-13 — Promote pending triggers using later confirmation.** Reevaluate active candidates on relevant offer events. A 3K trigger can become valid later; a previously valid 5K trigger receives evidence under the same signal ID. **Complete when:** tests cover exceptional confirmation before/after a small trigger, normal confirmation failing to promote it, multiple affected candidates, and validation time being the actual observation time when eligibility becomes known.

- [x] **SC-14 — Deduplicate interactions and freeze initial validation.** Key candidates by alias, epoch, direction, and canonical bid interaction ID. Merge reappear/step observations for one wall, retain the first usable primary trigger, and suppress unchanged revisions. Preserve initial validation time/evidence/threshold and original display creation time. **Complete when:** tests show one signal for simultaneous reappear/step, no new opportunity from a repeated step update, and later exceptional evidence cannot retroactively justify an earlier decision.

- [x] **SC-15 — Expire and invalidate active candidates.** Add market-time and market-price handling, 64-candidate capacity, trigger-eviction handling, local opposing-bid invalidation, drift invalidation, and reset/epoch clearing. **Complete when:** tests cover timestamp-only expiry, inclusive window boundaries, candidate cap behavior, evicted triggers, opposing meaning, 20-tick drift, and immutable historical emitted results after invalidation.

- [x] **SC-16 — Expose developing directional context.** Compute immutable LONG/SHORT waiting context from current offer evidence, including missing bid meaning, required size, locality, and event-time expiry. **Complete when:** tests show 60K bearish evidence produces a SHORT context waiting for BID_FAIL >= 3K without a signal, both directions can have separate context, and expiry/reset removes it.

## D. Event-time observations and detectors

Specification: implementation plan sections 4 and 5. These tasks use callback fixtures, without a running Bookmap or broker.

- [x] **SC-17 — Implement the observation clock and readiness gate.** Add a small event-time lifecycle helper for callback watermark, timestamp provenance, readiness, New York regular-session/date boundaries, and epochs. **Complete when:** tests cover pre-snapshot disarming, 09:30/16:00 boundaries, new-session clearing, genuine backward callback time requiring fresh readiness, and fallback wall-clock timestamps being unusable. Delayed detector occurrence time must not reset this clock.

- [x] **SC-18 — Track independent wall qualification.** Add `patterns/EventTimeWallTracker.java` with absolute depth updates, stable phase IDs, snapshot seeding, active-size lookup, a 3K observation floor, 500 ms qualification, and the wall-phase cap. It must not modify `OrderBookState` or use its legacy percentile gate. **Complete when:** fixtures qualify a 3K persistent wall under a book whose legacy threshold is higher, reject flash walls, and handle seeding and phase-cap diagnostics.

- [x] **SC-19 — Track pending wall clears and measured loss.** Extend `EventTimeWallTracker` with immediately pre-clear size, remaining ratio, pending-clear occurrence time, decision interval, reload cancellation, and typed clear evidence/outcomes. **Complete when:** event-time tests show a stable 90% loss becomes a clear observation, a temporary loss/reload does not, and removed size comes from the actual clear rather than an old peak. Classification into withdrawal/consumption follows in later tasks.

- [x] **SC-20 — Attribute trades to observed wall loss.** Add a bounded event-time trade-attribution helper. Preserve known/unknown aggressor side, sum matching volume using `long`, apply the lookback/decision windows, and surface buffer discontinuity/coverage status. Connect it to typed clear evidence. **Complete when:** tests cover buy/sell side, same-price attribution, excluded old trades, unknown-side trades, consumption ratios, overflow, and missing coverage never appearing as proof of cancellation.

- [x] **SC-21 — Recognize probable liquidity relocation.** Add event-time pairing of same-side reductions/increases using the configured 500 ms window and 10% size tolerance. Attach relocation status to clear evidence. **Complete when:** fixtures recognize a likely move, reject mismatched side/size/time, and prevent a matched move from becoming a cancellation trigger. Do not use the wall-change alert scheduler.

- [x] **SC-22 — Normalize the four existing wall patterns.** Add `patterns/PatternObservationEngine.java` and `PatternEventNormalizer.java`. Assemble the completed clock/tracker/attribution pieces, instantiate independent reappear/step definitions, implement their runtime context, and convert detection callbacks to normalized events. **Complete when:** fixtures cover bid reappear/step-up and offer reappear/step-down, current replacement size, stale/absent wall rejection, canonical interaction IDs, and repeated step revisions. Never invoke legacy scoring or change the existing `BookmapPatternEngine`.

- [x] **SC-23 — Detect inferred bid withdrawal.** Add the cancellation behavior of `patterns/BidFailureDetector.java` and register it in the observation engine. Require a qualified persistent bid, stable clear, low explained trade volume, usable coverage, and no probable relocation. **Complete when:** fixtures emit a correctly sized `BIDS_CANCELLED` event for a 3K clear, preserve inferred-withdrawal wording, and reject flash/reload/unknown-attribution/move cases.

- [x] **SC-24 — Detect confirmed bid breakdown.** Extend `BidFailureDetector` with consumed bid loss followed by a below-level trade inside the configured breakout window. **Complete when:** fixtures emit BID_FAIL only after the actual below-level print; consumption alone, insufficient attribution, wrong direction, expired print, or reset produce no breakdown. Keep mini-bounce subtypes out of scope.

- [x] **SC-25 — Detect basic offer rejection.** Add `patterns/OfferInteractionDetector.java` with persistent offer, approach from below, downward rejection distance, hold duration, and interaction expiry. Register it in the observer. **Complete when:** fixtures produce bearish confirmation from a completed approach/rejection and reject distant, incomplete, expired, removed, or broken-above offers. Size is the persistent offer at completed rejection.

- [x] **SC-26 — Add growth-plus-rejection confirmation.** Extend `OfferInteractionDetector` to track relevant offer growth and upgrade a completed rejection to `OFFER_SIZE_INCREASING_REJECTION` under the same interaction identity. Represent bare growth as UNKNOWN evidence. **Complete when:** fixtures show 60K growth plus actual rejection becomes exceptional-sized evidence; growth alone creates no directional confirmation; growth/rejection/composite are not counted as three separate confirmations.

- [x] **SC-27 — Detect bullish offer breakout.** Extend `OfferInteractionDetector` with attributed offer consumption followed by an above-level trade inside the configured window. **Complete when:** fixtures emit OFFER_BULLISH_CONFIRMATION with the actual removed-size basis, while a generic offer pull, consumption without breakout, unknown attribution, or expired print does not become bullish evidence.

- [x] **SC-28 — Verify the callback-to-composition pipeline.** Connect normalized observer output to the completed composer using a deterministic fixture harness. Exercise depth, trades, BBO, and timestamps together. **Complete when:** raw callback sequences prove 60K growth/rejection + 3K withdrawal produces one explained SHORT, a qualifying bid hold + offer breakout produces LONG, and replay timing cannot change decisions. Include the legacy-high-threshold/3K case and no-trigger case; synthetic `PatternEvent` tests alone do not satisfy this task.

## E. Plugin wiring and settings

Specification: implementation plan section 7.

- [x] **SC-29 — Store composed signals and context for display.** Add `signal/TradingSignalStore.java` with immutable per-alias snapshots, change listeners, 20-signal capacity, same-ID revisions, original receipt-time TTL, separate developing context, and epoch clearing. **Complete when:** tests cover alias separation, revision without TTL extension, 30-second marker expiry, context updates/removal, and concurrent snapshot reads.

- [x] **SC-30 — Construct composer state during plugin activation.** Add the shared first-attachment config snapshot and `IndicatorConfig.SIGNAL_COMPOSER` master state, plus per-attachment observer/composer instances in `RongPlugin.java`. Initialize the master once from valid config, respecting symbol eligibility. **Complete when:** lifecycle tests show disabled-by-default behavior, invalid-config disarming, a second attachment retaining the user's switch/rules, and observer-only construction without starting the native trading runtime. Do not route callbacks yet.

- [x] **SC-31 — Route market callbacks and output updates.** Add `shouldRunSignalComposition()` and forward depth/trade/BBO/timestamps/readiness to the new per-attachment path. Update the shared book once, carry one timestamp/provenance per callback, and publish immutable composition updates to the display store. **Complete when:** plugin fixtures show composition works with legacy automation off and without an enabled pattern tradebook; pre-snapshot and fallback timestamps cannot create signals.

- [x] **SC-32 — Complete plugin resets and teardown.** Handle genuine replay seek, session transitions, disable/enable, attachment stop, final shared-state release, listeners, and clean reattachment. Feed fresh snapshot readiness before arming after a seek. **Complete when:** lifecycle tests show no old candidate/context survives those transitions, disabled composition consumes no observation callbacks, and unrelated legacy/native state is preserved.

- [x] **SC-33 — Add the independent settings toggle.** Add the `SignalComposer (advisory)` checkbox in `IndicatorSettingsPanel.java` and apply the shared switch to each eligible attachment. Valid missing-file defaults can be enabled through UI; invalid config remains disarmed. **Complete when:** settings tests cover user enable/disable, symbol filtering, no coupling to legacy pattern toggles/tradebooks, and a new attachment not resetting the switch.

- [x] **SC-34 — Support settings in credential-free observer mode.** Update `getCustomSettingsPanels()` and panel construction to expose observation/indicator settings in existing observer-only mode without local secrets, while omitting native trading/account actions. **Complete when:** activation/settings tests show observer-only replay works without credentials and ordinary missing-secrets activation remains inactive. Do not add a second observer-mode flag.

## F. Chart presentation and local explanations

Specification: implementation plan section 8.

- [x] **SC-35 — Format and render a composed signal badge.** Implement the pure badge rendering/formatting portion of `signal/TradingSignalPainter.java`: direction, trigger, confirmation band, normal/applied size, and concise reason. **Complete when:** headless tests verify text and non-empty rendering for LONG/SHORT, no score display, and separate trigger versus validation timestamps. This task does not register a Bookmap painter yet.

- [x] **SC-36 — Register and refresh the signal painter.** Implement the canvas factory/listeners, immutable-store reads, validation-time X anchor, tick-price Y anchor, receipt-TTL refresh, and painter registration/unregistration in `RongPlugin`. **Complete when:** fake-canvas/lifecycle tests verify anchors, revisions replacing one marker, disable removing markers, and teardown releasing shapes/listeners/scheduler without holding the observation lock during painting.

- [x] **SC-37 — Display waiting context separately.** Add the distinct developing-context status area using store snapshots, showing evidence, missing bid meaning/size, local price, and remaining market-time window. **Complete when:** tests verify offer-only evidence displays waiting context rather than a completed signal; both directions remain distinct; expiry/reset clears it; pausing replay does not advance rule time from the UI refresh timer.

- [x] **SC-38 — Log signals and meaningful state changes.** Wire `CompositionUpdate` to existing queued `PluginLog` APIs: one concise summary per first validation, file-only full explanations/semantic events/revisions/rejections/resets/expiry. **Complete when:** recording-sink tests show late evidence does not create a second entry-style summary or sound, original validation remains accurately explained, and no callback-thread file/network I/O or broker dispatch is introduced.

## G. Regression, release, and handoff

Specification: implementation plan sections 10 and 11.

- [x] **SC-39 — Verify isolation from existing features.** Add or extend focused regression tests for composer on/off versus legacy badges, Cairo observation/evidence output, tradebook eligibility, and native/manual action routing. Use existing fake services. **Complete when:** those tests and existing affected suites pass, and an end-to-end composed signal causes zero trading dispatches/orders. Review the remaining acceptance matrix and add any missing meaningful rule/detector cases here, without duplicating completed tests.

- [x] **SC-40 — Add operator configuration and usage docs.** Add `config/signal-composer.template.json` and `docs/signal-composer.md`; update `config/README.md` and `README.md` with the advisory toggle, defaults, shared config lifetime, all-attachment reload requirement, observer-only replay, explanations, and incomplete legacy capture limits. **Complete when:** the disabled template parses under the actual loader and documentation matches implemented fields/behavior and the actual build artifact naming convention.

- [x] **SC-41 — Exercise the obfuscated release artifact.** Extend `src/releaseTest/java/release/ReleaseJarTest.java` with a smoke test for the new enums, explicit config parsing, deterministic composition, and output/explanation fields loaded from the release JAR. **Complete when:** the targeted release check passes without relying on original implementation classes or relaxing existing packaging rules. Align `PluginVersion.java` and `build.gradle` only if the project's release convention calls for a version change.

- [ ] **SC-42 — Run the complete build once.** Run `gradlew.bat build` with a compatible configured JDK. This covers the full unit suite, independent native-engine compilation, and obfuscated release checks. **Complete when:** the build passes and its command/artifact path are recorded. Fix failures within the relevant completed task, rerun its focused checks, and then rerun the full build; do not relabel a toolchain failure as a code test pass.

- [ ] **SC-43 — Perform the observer-only Bookmap replay smoke test.** Use existing observer-only replay mode and a test symbol; verify actual waiting context, signal marker, explanation, expiry, late confirmation, toggles, and seek/reattach. **Complete when:** observations and limitations are recorded and replay never starts a native broker runtime. If Bookmap/recordings are unavailable, leave this task unchecked and explicitly record manual verification as outstanding; current Cairo captures may omit 3K walls.

- [ ] **SC-44 — Prepare the final implementation handoff.** Review all task statuses against the plan's definition of done. Summarize implemented behavior, changed files, test/build results, release artifact, and any outstanding manual checks in the progress log and final report. **Complete when:** every claim has evidence, incomplete tasks remain visibly incomplete, the shipped template is disabled by default, and advisory outputs remain disconnected from execution. Do not create commits, publish a release, or enable live trading unless separately requested.

## Progress-entry format

```text
SC-XX — task title
Status: complete / in progress / verification blocked
Changed files:
Verification commands and results:
Limitations or remaining work:
Next task:
```

Checklist creation does not complete any implementation task. All tasks above start unchecked.
