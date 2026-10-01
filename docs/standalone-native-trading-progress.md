# Standalone native trading migration progress

Updated: 2026-10-01. Design: [standalone-native-trading-plan.md](standalone-native-trading-plan.md).

## User decisions

- ViteApp and Bookmap run one at a time; one Schwab stream consumer.
- Both keep matching trading behavior, with mirrored TS/Java core modules.
- Native code remains independent of Bookmap API; integration stays in adapters.
- Remove replay and AI/chat from ViteApp before porting. Replay was already removed.
- Preserve Bookmap's existing indicator subset; do not port additional browser indicators.
- Bookmap supports sound (`Layer1ApiSoundAlertMessage` already used for wall changes).
  Port notification messages/sounds; skip browser speech synthesis and DOM blinking.
- User supplies `%USERPROFILE%\.bmtrader\secrets.json`; publish an empty schema/template.
  Do not copy or execute the existing secret provisioning script.
- Document anything impossible to port and skip it; no such core trade operation identified yet.
- Commit changes independently in each repository. Never test with live broker mutations.

## Phase checklist

| Phase | Status | Evidence / remaining work |
| --- | --- | --- |
| Scope cleanup | Complete | Removed unused OpenAI secret accessor, agent-response client, AI notes; regenerated dead-code inventory. `npm run build` passed. |
| 1. Contracts and parity baseline | Pending | Existing direct/extended fixtures available; add market/account/state contracts and scenarios. |
| 2. Mirrored module extraction | Pending | Java namespace already compiles without Bookmap APIs; browser decisions still mixed with globals/UI. |
| 3. Secrets, Firestore, OAuth | Pending | Read-only Firestore config REST checks passed during planning; writes/auth lifecycle not implemented. |
| 4. Massive/history/market state | Pending | Exact endpoints and calculations inventoried in design. |
| 5. Account/streams/runtime | Pending | Java mutation client exists; ongoing account sync and vendor streams still depend on ViteApp. |
| 6. State/workflow completion | Pending | Four extended routes already implemented but flagged; state ownership/additional commands/jobs missing. |
| 7. Bookmap adapter | Pending | Runtime currently owned by WebSocket server; UI/config updates still come from ViteApp. |
| 8. Remove bridge/flag, final verification | Pending | Must verify browser and proxy closed, token expiry, restore/reconnect, and obfuscated JAR. |

## Baseline and verification

- Baseline: ViteApp `7dfd5a3`; bookmap-plugin `a080dbf`.
- Existing last native build: 191 tests passed before this migration (not rerun yet).
- Native Java target remains 11. Gradle builds use per-command
  `JAVA_HOME=C:\Users\lingr\trading\.tools\jdk-21.0.12.1+1`.
- Browser validation: `npm run build`, relevant Node test scripts in package.json.
- Java validation: `./gradlew.bat build`, including standalone native compile/release-JAR tests.
- No credentials copied, no live orders sent, no real state documents overwritten.

## Resume checkpoint

The design and this progress file live in bookmap-plugin/docs. Scope cleanup is
the first implementation step. Finish the running browser build, inspect/commit
cleanup, then implement matching domain contracts and extract existing pure
decision functions before adding direct vendor clients and the Java runtime.
Use the design's source inventory; do not duplicate dead strategies or UI-only
indicators. Update this checkpoint after each meaningful completed phase.
