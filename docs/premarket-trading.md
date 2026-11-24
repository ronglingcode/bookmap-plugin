# Manual premarket and after-hours trading

In addon settings, enable **Enable Premarket / After-hours Trading**. The preference is saved
and applies to every live chart immediately and on subsequent loads. The
additional panel does not require a selected watchlist entry, trading plan,
Firestore state, Massive history, a selected stop, or strategy sizing inputs.
Local broker credentials are still required. Replay and observer modes do not
activate live trading.

Enter a whole-share quantity. The editable **Offset (%)** defaults to **1**:

| Control | Limit price |
| --- | --- |
| Buy Now | Ask Ã— (1 + offset / 100), rounded outward |
| Buy Bid | Current bid |
| Sell Now | Bid Ã— (1 âˆ’ offset / 100), rounded outward |
| Sell Ask | Current ask |
| Buy Limit | Exact price from the Limit price textbox |
| Sell Limit | Exact price from the Limit price textbox |
| Flatten | Full remaining long position below bid, or full remaining short position above ask |

Every new order and hover-price replacement is a standalone equity `LIMIT`
with Schwab `session: SEAMLESS`, `duration: DAY` (extended-hours eligible),
and no protective bracket. Aggressive limits improve execution likelihood;
acceptance does not mean a fill, and partial or unfilled orders can remain working.
There is no market-order fallback or automatic chasing. Inspect the Action Log
for broker results and the chart/account display for working orders and fills.

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

The manual entry/Flatten buttons operate on weekdays at 07:00–09:30 and
16:00–20:00 Eastern. The broker determines holiday availability. Automatic
stop-loss protection does not observe, warn, or close positions outside the regular
session, regardless of the toggle. At the open, its regular-session behavior resumes
according to the existing protection setting, with a fresh grace period. With
manual extended-hours mode enabled, regular strategy trading commands are blocked
during premarket and after-hours; use the separate limit controls. Existing numbered premarket limits
can still be moved after the open while the feature remains enabled.

Broker account startup is independent of optional plan loading. Invalid individual
plans suppress their normal entry buttons; valid plans can still load. Chart
market data can load for attached symbols without a plan. Missing market-data
credentials/history do not prevent manual broker orders. Missing local secrets
retain the plugin's existing activation behavior.

If cancellation is rejected because the order has already finished, its broker
state and the remaining exposure are reconciled. Definite broker rejections do
not permanently lock the symbol. Automatic protection deferred by an in-progress
premarket edit is retried after the open without restarting its grace period.

Mutations are never automatically retried. A lost/uncertain mutation response
blocks further premarket mutations for that symbol until broker review and a
native-runtime restart, to avoid duplicate orders.

Hover tooltips are disabled in the manual ticket. Button clicks and input errors
are logged, and the ticket displays runtime submission, acceptance, or rejection
messages directly. An accepted order is still not a confirmed fill.
