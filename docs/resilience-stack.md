# Local resilience review stack

Base: `04b168d` on `main`. Each branch below includes the previous branch.
No branch or commit has been pushed and no remote PR has been opened.

| Layer | Local branch | Review focus |
| --- | --- | --- |
| 1 | `codex/resilience-1-inputs` | Preserve selected stops on local validation failures; consume at dispatch. |
| 2 | `codex/resilience-2-execution` | Per-symbol command serialization, urgent cancel/flatten, broker reconciliation and bounded mutation workers. |
| 3 | `codex/resilience-3-isolation` | Keep ingestion running after component errors; isolate observers, logs and safety jobs. |
| 4 | `codex/resilience-4-background` | Separate worker capacity; coalesce cloud writes, projections and refresh follow-ups; add latency diagnostics. |
| 5 | `codex/resilience-5-extended` | Recover extended-hours submissions/cancellations without a restart; tighten broker matching and preserve held-position digit controls. |

For reviewing the original stack, compare each branch with its predecessor,
and layer 1 with base `04b168d`. The final branch contains the complete stack.

The stack was merged into local `main` with merge commit `f5e6bea`. The original
uncommitted premarket/local-stop work was saved in safety stash
`3848b7e9d7413e4c6a19621014b18dc42522fc42`, then restored. The stash is retained.
Overlaps in `PremarketTrading` and `TradingRuntime` were resolved to retain local
stops, broker reconciliation, deferred work and the regular-session boundaries.
The local stop's one-attempt exit policy is preserved, and an exit waiting on an
unconfirmed entry can proceed after the broker confirms that entry.

The restored edits and their overlap resolutions remain uncommitted and unstaged.
Only the stack merge and this integration record are committed. The final build
therefore reflects the combined working tree, including the preserved version
1.49 and local-stop changes; those edits are required to reproduce this JAR.
The running Bookmap plugin was not replaced or restarted.

Validation command: `gradlew.bat check --offline --console=plain`. This includes
the complete unit/integration suite, standalone native compilation, and loading
the actual obfuscated release JAR with verification enabled. Failure injection
covers lost responses, ambiguous submissions, concurrent symbols, priority and
queue overload, slow consumers, logger/display failures, cloud retry and shutdown.

Stack-only offline result: 639 unit/integration tests and 12 release-JAR tests passed,
with no failures or skipped tests. Standalone native compilation also passed.

Combined-main validation: `gradlew.bat build --offline --console=plain` passed
with 659 unit/integration tests and 12 release-JAR tests, no failures or skips,
and successful standalone native compilation. This includes a new integration
regression for a triggered local stop waiting on entry reconciliation and then
submitting exactly one closing order.

Rebuilt artifact: `build/libs/lingrong1988_bmtrader_1.49.jar` (1,011,088 bytes).
SHA-256: `422b6ad9e0b66d76e709e0136c7c33d676c52b1117519924c9225a60ffd617f7`.

See [execution-resilience.md](execution-resilience.md) for operating behavior and
limits. Live broker latency, real outages and a source resnapshot after lost
market evidence require live validation; passing offline tests does not establish
those guarantees.
