# SignalComposer implementation handoff

Prepared 2026-10-06. All implementation changes are in `bookmap-plugin`, on `main`, with one `SC-XX` commit per task. SC-01 through SC-42 are complete. SC-43 has a commit recording the environment limitation and manual procedure, but **remains unchecked**. SC-44 completes this evidence-based handoff, not the outstanding manual replay verification.

Review the [checklist](signal-composer-tasks.md), [per-task progress](signal-composer-progress.md), [operator guide](signal-composer.md), and [manual replay check](signal-composer-replay-smoke.md). The original [implementation plan](signal-composer-implementation-plan.md) describes the design/code mapping.

## What is implemented

- Independent market-time observation with a 3K wall floor, persistence/readiness/session gates, bounded buffers, explicit trade coverage and probable-relocation evidence.
- Normalized bid/offer reappear and step observations; inferred bid withdrawal, consumed-bid breakdown, offer rejection, growth-plus-rejection, and consumed-offer breakout.
- Mandatory directional bid triggers, strongest compatible offer evidence, exact strength bands, 5K/4K/3K trigger policy with hard 3K minimum, pending promotion, same-ID revisions, and immutable first validation.
- Scoped history/candidates, expiry/opposing/drift invalidation, fresh epochs after seek/gap, and lifecycle clearing across shared toggles and attachment teardown.
- Shared immutable configuration per activation, an independent advisory checkbox, symbol filtering, credential-free observer settings, immutable display store, validation-anchored marker badges, and separate waiting context.
- Queued detailed semantic/explanation/state logs with one first-validation summary and no composer entry sound.
- A disabled template, production-loader validation, operator documentation, raw callback fixtures, fake canvas checks, and obfuscated-JAR smoke verification.

The composer never calls trading dispatch, order APIs, sizing logic, or a new WebSocket export. The isolation fixture observes zero fake trading dispatch/broadcast from composition, verifies identical legacy pattern output on/off, and confirms existing manual dispatch still works.

## Changed areas

| Area | Files / purpose |
| --- | --- |
| Observation | New event/clock/tracker/normalizer/detector classes in `src/main/java/com/bookmap/plugin/rong/patterns/` |
| Composition/presentation | New pure rules, models, pipeline, store, painter, and log adapter in `src/main/java/com/bookmap/plugin/rong/signal/` |
| Integration | `RongPlugin.java`, `IndicatorConfig.java`, `IndicatorSettingsPanel.java` |
| Verification | Focused tests in `src/test/java`, release reflection smoke in `src/releaseTest/java/release/ReleaseJarTest.java`, isolated test properties in `build.gradle` |
| Operator/handoff | `config/signal-composer.template.json`, configuration/README updates, and SignalComposer documents under `docs/` |

The actual implementation base is `be2f03aeb6cee7f22604b39f7dc6f00392b34045`, immediately before SC-01. Use `git diff be2f03a..HEAD` or the `SC-XX` commits to review this work. The earlier planning snapshot `fdbbeaa` predates six existing repository commits; a diff from it also includes unrelated prior native/Cairo changes. This implementation does not change `miniviteapp`, `NativeTradingAdapter`, `SignalWebSocketServer`, the legacy pattern engine/definitions/scorer, or `PluginVersion`.

## Build evidence and artifact

An existing JDK was used through process-local `JAVA_HOME`:

```powershell
$env:JAVA_HOME = 'C:\Users\lingr\.codex\tmp\bmtrader-execution\jdk\jdk-21.0.12.1+1'
.\gradlew.bat build
```

The build passed in 20 seconds with **373 unit tests and 10 release-JAR tests**, zero failures/errors/skips, and successful `compileNativeExecution`. The targeted affected regression run passed 139 tests before the final template verification. Java 11 source/target and existing packaging/obfuscation rules remain in place; Gradle reports its existing deprecation warnings.

Artifact: `build/libs/lingrong1988_bmtrader_1.32.jar`, 906,481 bytes. SHA256:

```text
7165A011A2C9FF2F9F07D813BDCC51FA857B4ABBAC2E6794772B0EBAF8F59E06
```

The release smoke loads the actual obfuscated artifact without original implementation classes and verifies enums, explicit shaded-Gson parsing, the disabled template, deterministic 3K/60K composition, validation/output fields, explanations, and raster badge rendering. A test-only headless `OpenGlHelper` fixture is absent from the release JAR.

## Outstanding verification and operational state

**SC-43: actual observer-only Bookmap replay remains outstanding.** This session found no accessible Bookmap process/common installation/registry entry, has no native desktop control, and was not given a recording. Actual chart layout, OpenGL upload, replay waiting context/late evidence/expiry/toggle/seek/reattach behavior therefore remain manually unverified. Follow the manual replay document and record observed results before completing that task. Old Cairo captures may lack 3K callbacks.

No local configuration/secrets were edited or enabled, no live broker runtime was started, and no artifact was published/pushed/deployed by this work. The shipped composer template remains disabled. All application code and tests requested by the checklist are complete; the manual check is visibly pending.
