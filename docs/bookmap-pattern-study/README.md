> Historical pre-consolidation audit/harness. Legacy classes have been removed; current observer/pipeline tests and the [bridge guide](../cairo-evidence.md) describe the implemented system.

# Reproduce the Bookmap pattern study

`PatternStudy.java` compares compiled production classes without starting Bookmap, sockets, capture, or a broker. `study.init.gradle` exports the test runtime classpath without editing the repository build. `results.json` contains the thirteen recorded comparison results at the study baseline.

The fixture uses regular-session timestamps on October 6, 2026, cent ticks, default Composer rules and material-change policy, seeded session extremes, explicit enabled legacy/break eligibility, and small background book levels. The scenario named `large_wall_floor_twenty_thousand_bid_step_six_thousand` changes only the legacy/display floor to 20K. The slow receipt scenario keeps market timestamps identical but inserts receipt-time delays. Quality details, callback timing, and history are synthetic; no claim of market frequency or profitability follows.

Run from the plugin repository root in PowerShell after the ordinary test classes have compiled:

```powershell
.\gradlew.bat test --tests 'com.bookmap.plugin.rong.patterns.*' --tests 'com.bookmap.plugin.rong.signal.*' --tests 'com.bookmap.plugin.rong.orderwall.*' --tests 'com.bookmap.plugin.rong.SignalComposer*'
New-Item -ItemType Directory -Force -Path build/private/pattern-study | Out-Null
.\gradlew.bat -q -I docs/bookmap-pattern-study/study.init.gradle patternStudyClasspath
$studyClasspath = Get-Content -LiteralPath build/private/pattern-study/classpath.txt -Raw
javac --release 11 -cp $studyClasspath -d build/private/pattern-study/classes docs/bookmap-pattern-study/PatternStudy.java
if ($LASTEXITCODE -ne 0) { throw 'Study compilation failed' }
java -cp ('build/private/pattern-study/classes;' + $studyClasspath) com.bookmap.plugin.rong.orderwall.PatternStudy | Set-Content -LiteralPath build/private/pattern-study/results.json -Encoding utf8
if ($LASTEXITCODE -ne 0) { throw 'Study execution failed' }
```

Use the generated result for comparisons; the checked-in `results.json` is the original study output and is not overwritten by these commands. The harness waits for real 500 ms stability decisions and ordinarily finishes in under thirty seconds.

`composerUpdates` includes revisions of accepted signals, so its length is not the number of distinct signals. `wallChanges` contains internal events; `visibleLiquidityAlertCount` calls the production alert visibility filter. The size-label exclusion described in the report was traced in source separately.

The study's additional threshold/hotkey and retest/routing checks were:

```powershell
.\gradlew.bat test --tests 'com.bookmap.plugin.rong.OrderbookWallThresholdTest' --tests 'com.bookmap.plugin.rong.OrderbookWallThresholdFreshnessTest' --tests 'com.bookmap.plugin.rong.EntryWallSnapshotTest' --tests 'com.bookmap.plugin.rong.pricelines.ChartHoverHotkeyHandlerTest' --tests 'com.bookmap.plugin.rong.tradebuttons.HotkeyButtonActionTest' --tests 'com.bookmap.plugin.rong.WallThresholdConfigTest'
.\gradlew.bat test --tests 'com.bookmap.plugin.rong.NativeTradingActionRoutingTest' --tests 'com.bookmap.plugin.rong.tradebuttons.TradeButtonWindowFormatTest' --tests 'com.bookmap.plugin.rong.KeyLevelConfigParsingTest'
```

Recorded baseline: `091aaa68cfcdc26d49e9b5425a60df3365e2d22c`. Results describe that code, not a proposed future implementation. The behavioral study and revised consolidation plan live one directory above this companion.
