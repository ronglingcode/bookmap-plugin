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

For eventual stacked PRs, target each branch at its predecessor, with layer 1
targeting `main`. The final branch contains the complete implementation.

The original checkout's uncommitted premarket/local-stop work is untouched and
is not included in these commits. Reconcile that work with this stack before
deployment. The build artifact is confined to this isolated worktree; the running
Bookmap plugin was not replaced or restarted.

Validation command: `gradlew.bat check --offline --console=plain`. This includes
the complete unit/integration suite, standalone native compilation, and loading
the actual obfuscated release JAR with verification enabled. Failure injection
covers lost responses, ambiguous submissions, concurrent symbols, priority and
queue overload, slow consumers, logger/display failures, cloud retry and shutdown.

Final offline result: 639 unit/integration tests and 12 release-JAR tests passed,
with no failures or skipped tests. Standalone native compilation also passed.

See [execution-resilience.md](execution-resilience.md) for operating behavior and
limits. Live broker latency, real outages and a source resnapshot after lost
market evidence require live validation; passing offline tests does not establish
those guarantees.
