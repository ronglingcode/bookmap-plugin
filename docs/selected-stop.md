# Select a stop before every entry

## One-action fixed-share entry while flat

On the Bookmap chart, hover the desired stop and press a **top-row 1–5** key.
The key selects that symbol's corresponding fixed-share button, in configured
order (risk buttons are not counted), and submits a **market entry** using the
hovered price as its stop. No preceding Z selection is needed; the stop is
selected and consumed in the same action. Shift is unnecessary.

For example, `[500, 1000, 3000, 5000]` maps keys 1–4 to those share quantities.
Key 5 blocks when there is no fifth button. No missing share slot falls back to
risk sizing. A stop below the current bid selects long; above the ask selects
short; a stop inside the spread is rejected. The matching enabled tradebook and
all existing entry, retest, participation and risk checks still apply. Ambiguous
same-side tradebook selection requires using the intended button instead.

This shortcut applies only when flat with no exit pairs and no pending entry.
With an existing position or exit pairs, the number keys keep their existing
exit-adjustment behavior. Numpad keys and top-row 6–0 are unchanged. Adds and
reloads still require the Z workflow below. The shortcut never reuses an older
Z-selected stop and does not change the hotkey risk/share preference.

## Existing Z workflow

Hover the desired stop price on the Bookmap chart, press **Z**, then use your
normal entry button or hotkey within **30 seconds**. A thin solid line labeled
**Stop {price} ({seconds remaining})** counts down from 30, updating once per
displayed second. It disappears on expiry or consumption. The floating Logs panel shows
the highlighted symbol, hover price and selected stop on three separate lines. The stop
includes the same countdown and shows `n/a` when no selection is active for that symbol.
Modifier keys do not change Z's behavior.

The selection is local, single-use and symbol-specific. It disappears when an
entry attempt consumes it, 30 seconds elapse, the highlighted chart symbol
changes, Space or Cancel Entry is pressed, or native trading restarts. Rejected
entry attempts also require a fresh selection. Repeated entry presses cannot
reuse one selection. Missing, expired and wrong-side stops block entry before
any broker request. A queued entry is checked again before dispatch.

All other native opening paths require this selection: entry buttons, chart/generic
B/S, fixed-share entries, same-direction adds, opposite-direction entries,
A/partial reloads, and W/Swap when it re-enters. W's existing exit-only branch
with a pending entry needs no selected stop. Exits, flatten, partial exits and
existing stop adjustments do not require a selection.

The selected cent-rounded price is the protective bracket's stop price; native
entry sizing and targets use that stop, with existing quantity/liquidity/risk
rules still applied. The old day-high/day-low entry fallback is unavailable in
the native Bookmap runtime. Entry Inputs now contains only entry price and fixed
quantity; it cannot arm a stop. Z only prepares the next entry and does not move
working stops or alter an existing position's invalidation reference.

An accepted pending entry retains its captured stop after selection expiry.
Automatic pending-entry stop widening is disabled. Cancel/replacement opening
actions require a fresh selection. Selected prices do not guarantee execution
prices, and a fresh selection does not replace the normal setup/entry gates.

This is a Bookmap-native policy attached by TradingRuntime to the executor.
The reusable mirrored controller fixtures retain historical behavior outside
that policy; changing the separate ViteApp execution UI is outside this change.
