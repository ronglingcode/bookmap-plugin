package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.patterns.PatternObservationEngineTest.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.signal.SignalComposerConfig;
import com.bookmap.plugin.rong.signal.ResetReason;

class PercentageWallHoldTest {
    private static class HoldFixture {
        final boolean bid;
        final int wall;
        final List<PatternEvent> events = new ArrayList<>();
        final PatternObservationEngine engine;
        HoldFixture(boolean bid, int wall, double tickSize, long size) {
            this.bid = bid; this.wall = wall;
            engine = new PatternObservationEngine("TEST", tickSize, SignalComposerConfig.defaults(),
                    events::add, (reason, epoch) -> {}, diagnostic -> {});
            engine.markReady(); time(0);
            engine.onBbo(wall - 1, wall + 1, BASE, MARKET);
            trade(defended(100), 100);
            depth(size, 1000); time(1500);
        }
        int defended(int distance) { return wall + (bid ? distance : -distance); }
        void trade(int price, long ms) { engine.onTrade(price, 1, !bid, BASE + ms * 1_000_000L, MARKET); }
        void time(long ms) { engine.onTimestamp(BASE + ms * 1_000_000L, MARKET); }
        void depth(long size, long ms) { engine.onDepth(bid, wall, size, BASE + ms * 1_000_000L, MARKET); }
        List<PatternEvent> holds() {
            return events.stream().filter(e -> e.type == PatternEventType.BID_BOUNCE || e.type == PatternEventType.OFFER_BOUNCE
                    || e.type == PatternEventType.OFFER_SIZE_INCREASING_HOLD).collect(Collectors.toList());
        }
    }
    @Test void amd650OfferTestAndMirroredBidTestUseActualExtremeAndTradeConfirmation() {
        for (boolean bid : new boolean[] {false, true}) {
            HoldFixture f = new HoldFixture(bid, 65000, .01, 65578);
            f.trade(f.defended(10), 2000); // 649.90 offer high / 650.10 bid low
            f.trade(f.defended(74), 2100); // only 64 cents from the test extreme
            f.trade(f.defended(74), 2700); assertTrue(f.holds().isEmpty());
            f.trade(f.defended(75), 2800); // required 65-cent retreat/rebound
            f.time(3300); assertTrue(f.holds().isEmpty());
            f.trade(f.defended(75), 3300);
            PatternEvent e = f.holds().get(0);
            assertEquals(bid ? PatternEventType.BID_BOUNCE : PatternEventType.OFFER_BOUNCE, e.type);
            assertEquals(bid ? PatternMeaning.BID_HOLD : PatternMeaning.OFFER_BEARISH_CONFIRMATION, e.meaning);
            assertEquals(650, e.price); assertEquals(65578, e.size);
            assertEquals(BASE + 3_300_000_000L, e.eventTimeNs);
            assertEquals(Integer.toString(f.defended(10)), e.evidence.metadata.get("testExtremeTick"));
            f.trade(f.defended(80), 3400); assertEquals(1, f.holds().size());
        }
    }
    @Test void approachBoundaryDoesNotRoundOutwardAndExactBoundaryIsInclusive() {
        for (boolean bid : new boolean[] {false, true}) {
            HoldFixture outside = new HoldFixture(bid, 65000, .01, 6000);
            outside.trade(outside.defended(33), 2000); // .325 allows 32 cents, not 33
            outside.trade(outside.defended(100), 2100); outside.trade(outside.defended(100), 2600);
            assertTrue(outside.holds().isEmpty());
            HoldFixture inside = new HoldFixture(bid, 65000, .01, 6000);
            inside.trade(inside.defended(32), 2000); inside.trade(inside.defended(97), 2100);
            inside.trade(inside.defended(97), 2600); assertEquals(1, inside.holds().size());
            HoldFixture exact = new HoldFixture(bid, 60000, .01, 6000);
            exact.trade(exact.defended(30), 2000); exact.trade(exact.defended(90), 2100);
            exact.trade(exact.defended(90), 2600); assertEquals(1, exact.holds().size());
        }
    }
    @Test void percentageRulesScaleAcrossPricesAndTickSizesWithoutTickFloors() {
        for (boolean bid : new boolean[] {false, true}) {
            for (double tickSize : new double[] {.01, .1}) {
                HoldFixture f = new HoldFixture(bid, 1000, tickSize, 6000);
                f.trade(f.wall, 2000); f.trade(f.defended(1), 2100); f.trade(f.defended(1), 2600);
                assertEquals(1, f.holds().size()); // one tick suffices at this wall price
                assertEquals(1000 * tickSize, f.holds().get(0).price);
            }
            HoldFixture fractional = new HoldFixture(bid, 5120, .01, 6000);
            fractional.trade(fractional.wall, 2000); fractional.trade(fractional.defended(5), 2100);
            fractional.trade(fractional.defended(5), 2600); assertTrue(fractional.holds().isEmpty());
            fractional.trade(fractional.defended(6), 2700); fractional.trade(fractional.defended(6), 3200);
            assertEquals(1, fractional.holds().size()); // .0512 requires at least .06 on cent grid
        }
    }
    @Test void stationaryApproachAndSilentMarketCannotCompleteHold() {
        for (boolean bid : new boolean[] {false, true}) {
            HoldFixture f = new HoldFixture(bid, 60000, .01, 6000);
            f.trade(f.defended(30), 2000); f.trade(f.defended(30), 2600); f.time(4000);
            assertTrue(f.holds().isEmpty());
            f.trade(f.defended(90), 4100); f.time(8000); assertTrue(f.holds().isEmpty());
        }
    }
    @Test void newExtremeAndReturnAboveRetreatThresholdResetConfirmation() {
        for (boolean bid : new boolean[] {false, true}) {
            HoldFixture f = new HoldFixture(bid, 60000, .01, 6000);
            f.trade(f.defended(10), 2000); f.trade(f.defended(70), 2100);
            f.trade(f.defended(5), 2300); // new test extreme
            f.trade(f.defended(64), 2400); f.trade(f.defended(64), 2900);
            assertTrue(f.holds().isEmpty());
            f.trade(f.defended(65), 3000); f.trade(f.defended(64), 3400); // reset
            f.trade(f.defended(65), 3500); f.trade(f.defended(65), 3999);
            assertTrue(f.holds().isEmpty()); f.trade(f.defended(65), 4000);
            assertEquals(1, f.holds().size());
        }
    }
    @Test void absentSmallRemovedOrBrokenWallCannotHold() {
        for (boolean bid : new boolean[] {false, true}) {
            for (int mode = 0; mode < 5; mode++) {
                HoldFixture f = new HoldFixture(bid, 60000, .01, mode == 0 ? 2999 : 6000);
                f.trade(f.defended(10), 2000); f.trade(f.defended(70), 2100);
                if (mode == 1) f.depth(0, 2300);
                if (mode == 2) f.depth(2999, 2300);
                if (mode == 3) f.trade(f.defended(-1), 2300);
                if (mode == 4) f.engine.onBbo(bid ? f.wall - 1 : f.wall, bid ? f.wall : f.wall + 1,
                        BASE + 2_300_000_000L, MARKET);
                f.trade(f.defended(70), 2600); assertTrue(f.holds().isEmpty(), "mode=" + mode);
            }
            HoldFixture unqualified = new HoldFixture(bid, 60000, .01, 6000);
            unqualified.engine.reset(ResetReason.REPLAY_SEEK); unqualified.engine.markReady();
            unqualified.depth(6000, 2000); unqualified.trade(unqualified.defended(10), 2100);
            unqualified.trade(unqualified.defended(70), 2200); unqualified.trade(unqualified.defended(70), 2700);
            assertTrue(unqualified.holds().isEmpty());
        }
    }
    @Test void fifteenSecondWindowIsInclusiveAndExpiredInteractionsDoNotEmit() {
        for (boolean bid : new boolean[] {false, true}) {
            for (long finish : new long[] {17000, 17001}) {
                HoldFixture f = new HoldFixture(bid, 60000, .01, 6000);
                f.trade(f.defended(10), 2000); f.trade(f.defended(70), 16500); f.trade(f.defended(70), finish);
                assertEquals(finish == 17000 ? 1 : 0, f.holds().size());
            }
        }
    }
    @Test void separateRetestHasNewIdentityAndReplaySeekDiscardsPendingHold() {
        for (boolean bid : new boolean[] {false, true}) {
            HoldFixture f = new HoldFixture(bid, 60000, .01, 6000);
            f.trade(f.defended(10), 2000); f.trade(f.defended(70), 2100); f.trade(f.defended(70), 2600);
            f.trade(f.defended(80), 2700); f.trade(f.defended(10), 3000); f.trade(f.defended(70), 3100); f.trade(f.defended(70), 3600);
            assertEquals(2, f.holds().size()); assertNotEquals(f.holds().get(0).id, f.holds().get(1).id);
            f.trade(f.defended(80), 3700); f.trade(f.defended(10), 4000); f.trade(f.defended(70), 4100);
            f.engine.reset(ResetReason.REPLAY_SEEK); f.trade(f.defended(70), 4600);
            assertEquals(2, f.holds().size());
        }
    }
}
