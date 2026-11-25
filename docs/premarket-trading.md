# Manual premarket and after-hours trading

In addon settings, enable **Enable Premarket / After-hours Trading**. The preference is saved
and applies to every live chart immediately and on subsequent loads. The
additional panel does not require a selected watchlist entry, trading plan,
Firestore state, Massive history, or strategy sizing inputs. Opening orders
require a selected local stop; closing orders, Cancel All, and Flatten do not.
Local broker credentials are still required. Replay and observer modes do not
activate live trading.

Enter a whole-share quantity. The editable **Offset (%)** defaults to **1**:

| Control | Limit price |
| --- | --- |
| Buy Now | Ask * (1 + offset / 100), rounded outward |
| Buy Bid | Current bid |
| Sell Now | Bid * (1 - offset / 100), rounded outward |
| Sell Ask | Current ask |
| Buy Limit | Exact price from the Limit price textbox |
| Sell Limit | Exact price from the Limit price textbox |
| Cancel All | Cancel every working order for this chart symbol; do not close the position |
| Flatten | Full remaining long position below bid, or full remaining short position above ask |

Every new order and hover-price replacement is a standalone equity `LIMIT`
with Schwab `session: SEAMLESS`, `duration: DAY` (extended-hours eligible),
and no protective bracket. Aggressive limits improve execution likelihood;
acceptance does not mean a fill, and partial or unfilled orders can remain working.
There is no market-order fallback or automatic chasing. Inspect the Action Log
for broker results and the chart/account display for working orders and fills.

## Local stop loss (premarket and after-hours)

Before entry, hover the intended stop level on this chart and press **Z**, or
enter a price next to **Set Stop** in the extended-hours ticket. A long entry
requires a stop below its entry price; a short entry requires a stop above it.
The chart displays a separate **PM LOCAL STOP** line and the ticket shows its
current status. This is local monitoring, not a working broker stop order.

After entry, hover another price and press **Z** again, or use **Set Stop**, to
move the monitored level. It does not expire after 30 seconds, and switching
charts or pressing Space does not erase protection on a held position. Each
symbol has its own stop.

A fresh live trade **at or below stop minus $0.01** triggers a long exit; a trade
**at or above stop plus $0.01** triggers a short exit. Prints from Bookmap or the
live Massive stream can trigger it; stale, future-dated, and out-of-order prints
are ignored. An accepted entry is monitored immediately, even before the next
account refresh. A breach also cancels its unfilled entry shares.

On a trigger, the plugin cancels every working order for the symbol, confirms
cancellation, rereads the broker position, and submits only its remaining shares:
**SELL at bid minus $0.01**, or **BUY_TO_COVER at ask plus $0.01**. The stop exit
uses this fixed penny offset regardless of the ticket's Offset (%). No order
is sent if cancellation is unconfirmed, the quote is unavailable, or the
position changed to the opposite direction. A triggered stop is latched to
prevent duplicate submissions. Acceptance does not guarantee a fill; if its
closing limit remains working or the exit is blocked, inspect the Action Log
and broker, then use Flatten to reconcile and replace it.

Keep the plugin, native runtime, live data, and broker connection running.
Local stops are not saved across a restart or converted into broker stops.
They clear when the position is confirmed flat with no working orders, when
the feature is disabled, or when that extended-hours session ends. Morning
stops do not carry into after-hours or past the 09:28 regular-order handover.

For a fixed price, enter **Shares** and **Limit price**, then press **Buy Limit**
or **Sell Limit**. These actions do not use the offset and do not require a live
quote. Prices use cents at or above $1 and up to four decimals below $1; invalid
prices are rejected rather than silently changed. Enter in either field does
not submit an order.

For a quick chart order, enter Shares once, move the pointer onto the intended
chart price, and press **L**. Above the live bid/ask midpoint creates a sell limit;
below it creates a buy limit. Equality, missing/stale quotes, invalid shares, or
a disabled/missing ticket produce no order. The hovered price is snapped to a supported price increment. The price and Shares
for that exact chart are captured on the key press. Holding L does not repeat orders. There is
no percentage offset on hover limits. Moving from a premarket numeric field to
the chart transfers keyboard focus; ordinary text editors remain protected.

The broker position determines `BUY`, `BUY_TO_COVER`, `SELL`, or `SELL_SHORT`.
Multiple compatible resting orders are allowed. Working closing quantities
reserve shares so another click cannot over-close. Opposing working orders and
quantities that cross through flat are blocked: use Flatten to cancel and close,
then open the opposite side once flat. Flatten ignores the quantity textbox,
cancels the symbol's working orders, waits for confirmed cancellation, rereads
the position, and submits only the remaining closing quantity. If cancellation
is not confirmed, no closing order is sent. A later Flatten cancels/reconciles any
unfilled closing order before replacing it.

Working standalone extended-hours limits appear as **1:PM BUY LIMIT**,
**2:PM SELL LIMIT**, etc. Hover the chart at the desired price and press top-row
**1**, **2**, or **3** (supported through **9**) to move that numbered order.
The move cancels the selected order, waits for confirmation, then uses its final
cumulative fills to submit only the unfilled quantity at the hovered price. It
preserves side, limit type, extended session, and chart number. A fill during
cancellation reduces the resubmission; a full fill produces no new order. The
order temporarily loses its queue position while being moved.
Finished number slots can be reused without shifting other working orders. A
hotkey captures the displayed broker order ID so stale labels cannot move a new
order that reused the slot. Restarting the runtime rebuilds numbering from broker orders.
The existing numpad market-out shortcuts are not premarket order edits.

The manual entry/Flatten buttons and local stops operate on weekdays at
07:00–09:28 and 16:00–20:00 Eastern. At exactly 09:28, the extended-hours ticket,
local stops, and extended-hours hotkeys stop operating, and regular trading
controls take over. Regular orders keep `session: NORMAL`, `duration: DAY`,
including stop, limit, and market orders; there is no extra market-order block
during 09:28–09:30. The broker handles their activation and execution.
Market-data session boundaries and automatic missing-stop protection still use
the actual 09:30 open. The broker determines holiday availability.
The existing missing-broker-stop protection runs only during the regular
session and starts with a fresh grace period at the open, according to its
existing protection setting. A local extended-hours stop does not count as a
broker stop after the open. With manual extended-hours mode enabled, regular
strategy commands are blocked during premarket and after-hours; use the
separate limit controls.

During the regular-order window (09:28–16:00), the extended-hours ticket is hidden and its mutations,
number-key interception, local monitoring, and order projection are disabled.
Z and the number keys use their regular-session handlers. Queued cancellation
or replacement work rechecks the session before any new mutation, and cannot
submit an extended-hours exit or replacement at or after 09:28. Previously
accepted SEAMLESS orders can still exist at the broker; this does not cancel
or transform them automatically at 09:28. Normal broker account, transport,
market-data, and drawing infrastructure remains shared. Regular-session
startup retains its required plan validation, market-data credentials, and
saved trade-state loading; regular strategy order builders are unchanged.

Extended-hours broker account startup is independent of optional plan loading. Invalid individual
plans suppress their normal entry buttons; valid plans can still load. Chart
market data can load for attached symbols without a plan. Missing market-data
credentials/history do not prevent manual broker orders. Missing local secrets
retain the plugin's existing activation behavior.

If cancellation is rejected because the order has already finished, its broker
state and the remaining exposure are reconciled. Definite broker rejections do
not permanently lock the symbol. An unfinished extended-hours action does not
block regular-session commands or regular missing-stop protection.

Mutations are never automatically retried. A lost submission response restricts
new submissions for that symbol while polling looks for a unique new broker order
matching its payload and submission time. Cancel All remains available, and
confirmation resumes normal operation without a runtime restart. An absent or
ambiguous broker record remains unconfirmed. Cancellation errors are reconciled
against current broker order status on the next explicit action; they do not
permanently disable the symbol. See [execution resilience](execution-resilience.md).

Hover tooltips are disabled in the manual ticket. Button clicks and input errors
are logged, and the ticket displays runtime submission, acceptance, or rejection
messages directly. An accepted order is still not a confirmed fill.

**Cancel All** ignores the Shares, Offset, and Limit price inputs. It is available
outside the order-entry session except during the regular-order window (09:28–16:00), confirms cancellation, and never submits
a closing order. An order can still fill while cancellation is in flight.

Account state normally refreshes every three seconds; manual order actions request
an immediate refresh and follow-ups at 500 ms and 1,000 ms. Account-stream events
also trigger refreshes. Chart display updates are batched at approximately 200 ms.
Order-query boundaries cover midnight-to-midnight in America/New_York, including
after-hours orders whose UTC timestamp falls on the following date. Confirmed
fills use the existing execution-marker pipeline; confirmed terminal orders remove
their pending chart lines. Broker fill and cancellation reports also update the
ticket status instead of leaving the initial acceptance message indefinitely.
