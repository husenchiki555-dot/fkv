package com.huseyn.elixircollector;

import org.junit.Test;

import static org.junit.Assert.*;

public class BattleStateMachineTest {
    private static BattleCueDetector.Signals strong() {
        return new BattleCueDetector.Signals(0.80, 0.72, 0.68, 0.58, 0.72,
                0.82, 0.0, 0.72, 5);
    }
    private static BattleCueDetector.Signals absent() {
        return new BattleCueDetector.Signals(0.05, 0.0, 0.08, 0.06, 0.10,
                0.0, 0.0, 0.05, 0);
    }

    @Test public void requiresVerificationAndToleratesMissedFrames() {
        BattleStateMachine machine = new BattleStateMachine();
        assertEquals(BattleStateMachine.State.BATTLE_CANDIDATE,
                machine.update(strong(), 1_000).state);
        assertEquals(BattleStateMachine.State.VERIFYING,
                machine.update(strong(), 1_500).state);
        BattleStateMachine.Update entered = machine.update(strong(), 2_200);
        assertEquals(BattleStateMachine.State.IN_BATTLE, entered.state);
        assertTrue(entered.enteredBattle);

        assertEquals(BattleStateMachine.State.IN_BATTLE,
                machine.update(absent(), 2_300).state);
        assertEquals(BattleStateMachine.State.IN_BATTLE,
                machine.update(absent(), 3_800).state);
        assertEquals(BattleStateMachine.State.END_CANDIDATE,
                machine.update(absent(), 4_300).state);
        assertEquals(BattleStateMachine.State.IN_BATTLE,
                machine.update(strong(), 4_500).state);
    }

    @Test public void sustainedHudLossEndsBattle() {
        BattleStateMachine machine = new BattleStateMachine();
        machine.update(strong(), 1_000);
        machine.update(strong(), 1_500);
        machine.update(strong(), 2_200);
        machine.update(absent(), 2_300);
        machine.update(absent(), 4_300);
        BattleStateMachine.Update ended = machine.update(absent(), 8_000);
        assertEquals(BattleStateMachine.State.OUTSIDE_BATTLE, ended.state);
        assertTrue(ended.exitedBattle);
    }

    @Test public void handAndPurpleMenuAccentCannotStartAMatchWithoutTowers() {
        BattleStateMachine machine = new BattleStateMachine();
        BattleCueDetector.Signals deckScreen = new BattleCueDetector.Signals(
                0.66, 0.84, 0.67, 0.21, 0.70, 0.76, 0.0, 0.65, 2, 0);
        for (int i = 0; i < 40; i++) {
            assertEquals(BattleStateMachine.State.OUTSIDE_BATTLE,
                    machine.update(deckScreen, 1_000 + i * 100L).state);
        }
    }

    @Test public void recognizedHandCanStartWithoutRailWhenClockAndTowersAgree() {
        BattleStateMachine machine = new BattleStateMachine();
        BattleCueDetector.Signals recognized = new BattleCueDetector.Signals(
                0.78, 0.0, 0.82, 0.86, 0.72, 0.78, 0.0, 0.64, 3, 3);
        assertEquals(BattleStateMachine.State.BATTLE_CANDIDATE,
                machine.update(recognized, 1_000).state);
        assertEquals(BattleStateMachine.State.VERIFYING,
                machine.update(recognized, 1_500).state);
        assertTrue(machine.update(recognized, 2_200).enteredBattle);
    }
}
