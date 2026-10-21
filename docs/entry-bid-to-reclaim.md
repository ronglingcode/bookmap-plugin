# Entry bid to reclaim

For an active short, hover the bid whose breakdown you shorted and press **I**.
This key was unused in the plugin's chart hotkey map. Bookmap's own shortcuts
and other add-ons have separate bindings; check those if I is already assigned
in your installation. Chart hotkeys must be enabled.

The chart draws a thick yellow horizontal line labeled **entry bid to reclaim**
at the hovered price, independently of automatic pattern monitors. Press I at
another hovered price to replace it. There is no manual clear hotkey;
**Shift+I** does nothing. Each symbol has its own marker, which clears when
the account snapshot shows that the short position is gone.

Before marking, a yellow reminder at the position's average entry says
**SET ENTRY BID: hover + I before target #4+**. This reminder's location is only
a display anchor; it does not set the entry bid for you.

For shorts, profit target adjustments for original partials **#1–#3** work
without a marker. Adjusting original partial **#4 or later** requires one,
even before any fills. This also applies to batch target adjustments and Reset
Targets when they include later partials. A remaining original partial #4 still
requires the marker when it becomes the first remaining exit pair. Existing
partial numbering is inferred from the captured sizing count and remaining exit
pairs, as with the other exit rules.

Once the entry bid is marked, all short profit targets can be adjusted
immediately, including targets #4 onward and batch moves. You do not need to
wait for a reclaim. The older core-target buffer and missing-core-plan checks
do not block these marked-short profit-target moves. Normal order validation
still applies. A reclaim warning does not lock the targets again.

Stop adjustments, market partial exits and flatten remain available. Long
positions are unaffected.

When a valid **Bookmap Level 1 bid reaches or exceeds** the marked price, the
line turns red and reads **entry bid to reclaim - RECLAIMED: STOP OUT**.
The warning stays visible after the bid drops back below the level. Invalid
quotes, trades and ask quotes do not trigger it. Setting a level already at or
below the current bid warns immediately. This is an advisory reminder; marking
and reclaiming never submit a stop or any other broker order.

The marked price is saved with the current day's short trade state. It clears
when the account snapshot shows the short is closed or becomes long, or when a
new trade replaces that captured state. New-day trade state also starts clear.
The red reclaim latch is local to the current chart session.
