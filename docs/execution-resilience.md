# Execution resilience

The production runtime uses bounded command lanes per symbol. Conflicting actions
run in sequence; independent symbols can run concurrently. Cancel, flatten and
missing-stop safety closes run ahead of queued management actions and discard
queued entries. An in-flight broker mutation is allowed to finish: interrupting
it would not establish whether the broker received it.

Cancel/flatten always obtain a new broker observation before planning. Other
commands refresh after any mutation, when the account snapshot is older than
three seconds, or while a submission is unconfirmed. Concurrent refreshes share
an observation, but a command cannot reuse a read started before its required
reconciliation. Position management uses account-derived inputs without requiring
an entry plan, liquidity provider or cloud configuration refresh.

Opening and uncovered-position closing POSTs are never retried automatically.
If a response is lost, the relevant symbol's subsequent POSTs are restricted
until a new broker order uniquely matches the captured payload and was absent
from the pre-submission order set. Broker polling can resolve this without a
manual reset. Unrelated symbols and working-order controls remain available.
An absent or ambiguous broker record is not treated as proof that nothing was
submitted. If the broker never exposes that record, resolution still requires
checking the broker; the program cannot safely manufacture a definitive outcome.
This intent tracking is local to the running process, not a durable broker
idempotency guarantee. Restart always reads account/order state before trading.

Queued entries retain the selected-stop generation and original expiration.
Selecting another stop while they wait invalidates the older command. Local
validation releases a reservation; the first opening dispatch consumes it.
Independent requests within a workflow stage still run concurrently, with bounded
workers; dependent stages wait for preceding results.

## Failure isolation

Market ingestion remains synchronous and ordered. A failed component no longer
turns off subsequent callbacks. Price, session-level and liquidity consumers are
isolated so that one cannot prevent the others from receiving the event. Core
exceptions keep an explicit incomplete-evidence flag: a restart with a fresh
source snapshot is required before those counters can be trusted again. Continuing
to receive events does not reconstruct a lost trade or depth update.

Stateless display projections retry after a cooldown. Raw trades/depth updates
are never sampled or skipped by that cooldown. Logging and display callbacks are
isolated from order submission and broker-result classification; their failure
counts remain visible in runtime/execution status. Periodic jobs schedule their
next tick even when a callback throws. A locally blocked or definitively rejected
missing-stop safety close becomes eligible for another check without resetting
the original grace period; ambiguous submissions retain reconciliation protection.

## Background work and diagnostics

Production uses separate bounded worker pools for broker services, independent
account HTTP requests, history/configuration work, cloud writes and display work.
Account and order reads run concurrently, then publish one combined observation.
This reduces waiting between requests; it does not make the broker's separate
endpoints an atomic snapshot. Caller-supplied single-thread executors retain
sequential reads so they cannot deadlock waiting for their own queue.

Replaceable projections and order/fill diagnostic snapshots coalesce while a
consumer is slow. Raw market events and execution commands are excluded from
this queue. Notifications, sound and history-display callbacks run on display
capacity. Repeated mutations share pending 500 ms and 1 s follow-up reads.

Cloud persistence retains the latest complete snapshot per profile and writes
one at a time. Failed writes retry after 1, 2, 4, 8, 16 and then 30 seconds,
superseding stale pending snapshots without replaying old state. Shutdown cancels
the retry timer and attempts the dirty write directly. A failed final write
completes exceptionally rather than leaving workers waiting indefinitely.
These are cloud snapshots, not an execution-command journal.

Execution events expose queue wait, command-to-submit, broker round-trip and total
command duration. Runtime views expose observer failures and coalesced projection
and persistence counts. These identify where latency occurs; they are not a claim
of measured live-broker speedup.
