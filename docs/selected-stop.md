# Select a stop before every entry

Hover the desired stop price on the Bookmap chart, press **Z**, then use your
normal entry button or hotkey within **five seconds**. A thin solid line labeled
**Stop** shows the selected price. No stop information is added to the floating
panel. Modifier keys do not change Z's behavior.

The selection is local, single-use and symbol-specific. It disappears when an
entry attempt consumes it, five seconds elapse, the highlighted chart symbol
changes, Space or Cancel Entry is pressed, or native trading restarts. Rejected
entry attempts also require a fresh selection. Repeated entry presses cannot
reuse one selection. Missing, expired and wrong-side stops block entry before
any broker request. A queued entry is checked again before dispatch.

All native opening paths require this selection: entry buttons, chart/generic
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
