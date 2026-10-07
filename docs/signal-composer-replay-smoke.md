# SignalComposer manual replay smoke check

Status: **outstanding — SC-43 is not complete**. Prepared 2026-10-06.

Automated evidence: the full build passed 373 unit tests and 10 tests against the obfuscated release artifact. Fake canvases cover coordinates, revisions, teardown, and receipt expiry. The headless test fixture does not verify actual Bookmap OpenGL upload or chart layout.

Environment checks found no process named Bookmap, no installation at `C:\Bookmap`, `C:\Program Files\Bookmap`, `C:\Program Files (x86)\Bookmap`, or `C:\Users\lingr\Bookmap`, and no Bookmap entry in the standard Windows uninstall registry locations. These checks do not establish that Bookmap cannot exist elsewhere. No recording was supplied, and this session cannot control native desktop apps. No manual replay observation is claimed.

## Setup for the operator

1. Use the built artifact `build/libs/lingrong1988_bmtrader_1.34.jar` in an available Bookmap installation. Record the Bookmap version, recording path, chart alias/tick size, recording session date, artifact hash, and composer configuration revision.
2. Follow [credential-free replay setup](signal-composer.md#credential-free-replay), using existing Cairo `observerOnly: true`, `sourceMode: "replay"`, and the actual recording symbol. Enable the advisory checkbox or an isolated replay composer configuration. The shipped template remains disabled. Keep this replay attachment in observer mode.
3. Verify that no native broker runtime/account authorization or floating trade-button window starts. Settings should expose observation indicators and SignalComposer while omitting native action controls.
4. Use a regular-hours recording containing the required depth/trade interactions. Older Cairo captures may omit 3K walls; they are insufficient for the 3K case if those callbacks are absent.

## Record each actual observation

- [ ] Before snapshot readiness, no signal/context appears. After readiness and persistent walls, observation can arm.
- [ ] Bare large offer growth creates no signal or directional waiting context.
- [ ] A 60K growth-plus-rejection interaction shows SHORT waiting context, with `BID_FAIL >= 3K`, correct local price, and event-time countdown.
- [ ] A nearby qualified 3K bid withdrawal completes exactly one SHORT; bid reappear/step plus compatible bullish offer breakout completes LONG.
- [ ] Markers render legibly with direction, trigger size, confirmation band, and normal/applied requirements. X is validation time; Y is the trigger tick price. Verify actual OpenGL rendering.
- [ ] Logs show the factual full explanation and one concise advisory summary. Check occurrence versus observation/validation times and before/after evidence.
- [ ] A small pending trigger receives sufficient later confirmation and validates at the later observation time. A previously valid normal trigger gets a same-ID revision while retaining its first threshold/time.
- [ ] Marker revision replaces one marker and does not extend its original 30-second receipt lifetime. Waiting context stays visually separate from markers.
- [ ] Pausing replay freezes waiting-context rule time; marker receipt expiry may still remove markers. Timestamp progress expires stale contexts/candidates.
- [ ] Disable clears markers/context. Disabled callbacks do not create composition. Enable starts fresh observation using current depth where allowed.
- [ ] Seek backwards clears state and requires fresh readiness/depth. Without new readiness, composition stays disarmed until reattachment. Pre-seek depth must not justify a new signal.
- [ ] Close/new session and stop/reattach clear old composition. A second attachment keeps the shared toggle/rules; stopping all attachments permits edited rules to reload.
- [ ] Legacy badges/Cairo observation and existing manual/native behavior retain their usual settings and output. Composed signals cause no trading action or new wire message.

For each item, append the recording timestamp, expected/observed behavior, relevant log excerpt, and any chart screenshot reference. Do not mark SC-43 complete without actual observations. If the recording lacks an interaction, record that case as unverified rather than inferring success from unit tests.
