# Logging evidence audit

Action messages must describe the evidence available at the time of logging.
HTTP acknowledgment does not prove an order filled, changed, or was canceled.

Full evidence is retained in local files. The screen shows concise action outcomes,
fill-count summaries, connection/lifecycle changes and important warnings/errors.
Broker snapshots, raw/projected fill detail, execution time samples, input intent,
planning and individual request/timing diagnostics are file-only. Summary wording
still distinguishes acknowledgment from confirmed broker state. Identical recurring
screen events are suppressed for 30 seconds without filtering the file sink;
distinct action IDs keep separate trading results visible.
Multi-leg actions retain one result summary with planned exit-pair counts where
applicable. Fill-count bursts update one screen row until five seconds of inactivity;
this groups account updates without claiming they belong to a verified parent order.

| Log path | Evidence and final wording |
| --- | --- |
| Trade window and hotkey buttons | `Button clicked ...; requesting native action`, before dispatch, including Flatten. This establishes user intent only. |
| Chart hotkeys | Records the key and hover price. Cutoff and missing tradebook branches explicitly report an ignored hotkey with no action requested. |
| Native routing | Plan count and action ID before execution; local blocks explicitly say no broker requests attempted for that action. |
| Cancel preparation | Reads broker orders directly rather than relying on the account cache. Logs fetched orders, selected symbol's entry/exit counts, and the existing all-entries or STOP-only policy. Preparation failures report no cancellation attempted. |
| Native mutations | Every POST, PUT, DELETE attempt includes action ID, method, order ID, and sequence number. Every returned result includes HTTP status, acknowledgment/rejection/uncertainty, and returned new order ID when present. |
| Entry HTTP timing | Marks the HTTP attempt, returned response, or failure without a response. An attempt does not prove network delivery. |
| Action completion | Counts planned, attempted, acknowledged, rejected, unknown, and unattempted requests. Zero requests is `no_op`; earlier acknowledgments followed by rejection is `partial`. Unknown outcomes remain unknown even when earlier requests were acknowledged. |
| Broker state reads | Logs observed statuses, including `CANCELED`, `FILLED`, or `WORKING`, with order IDs and quantities. Identical snapshots may be suppressed. Removal from a returned order list is not labeled cancellation. |
| Fill projection | Calls records projected fills available for display; does not claim they were rendered on a chart. |
| Risk/partial advisories | Labels estimated risk and partial-count inference as based on the cached account. |
| Local exit-plan edits | Reports a local plan update with no broker request and persistence requested; does not claim a protective order changed or the save completed. |
| Review reset | Says the user cleared the execution block and that reset did not verify broker state. |
| Startup/streams/auth | Initialization reports loaded account/configuration and requested stream startup. Stream connected status follows login acknowledgment; authorization saved follows credential save. |
| External action/screen reports | Source marked `External report`; forwarded text is attributed rather than presented as native verification. |
| Clipboard exports | Success follows completed clipboard write; failed export uses the error dialog. |
| File logging | File-open status describes buffered writes. Queue/storage losses, retry failures, shutdown timeout, and final flush failure are reported separately. Opening a file does not prove all entries were persisted. |

The internal `accepted` protocol value means the broker HTTP response was classified
as an acknowledgment, not final completion. Human-readable action logs state this
explicitly and no longer duplicate the raw `execution_result ... accepted` message.
Unexpected successful/redirect responses and HTTP 408/5xx remain uncertain rather
than being presented as definitive rejection.

Regression coverage uses fake HTTP responses and broker snapshots: empty cancel,
STOP-only selection, stale cached orders, fresh-order read failure, full HTTP
acknowledgment, first-request rejection, partial batches, missing entry Location,
lost mutation responses, HTTP 202/408/503 uncertainty, and observed canceled status.
No live account mutations are needed to verify these logging paths.
