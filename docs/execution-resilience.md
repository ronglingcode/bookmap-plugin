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
