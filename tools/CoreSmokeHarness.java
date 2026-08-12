package com.huseyn.elixircollector;

/**
 * Dependency-free desktop smoke harness for the pure-Java tracking core.
 * Android-specific integration is exercised by the GitHub Actions build.
 */
public final class CoreSmokeHarness {
    public static void main(String[] args) {
        hudAndElixir();
        battleLifecycle();
        opponentUncertaintyAndCycle();
        regenAndFeatures();
        System.out.println("RoyaleVision v6 core smoke tests passed");
    }

    private static void hudAndElixir() {
        SyntheticFrame frame = new SyntheticFrame(1080, 2400);
        HudLayoutTracker hudTracker = new HudLayoutTracker();
        HudLayoutTracker.Observation hud = null;
        for (int i = 0; i < 5; i++) hud = hudTracker.update(frame);
        require(hud != null && hud.state == HudLayoutTracker.State.LOCKED,
                "HUD did not lock on measured phone profile");
        require(Math.abs(hud.layout.handCenters[0] - 0.313) <= 0.045,
                "first hand slot was not located adaptively");
        require(Math.abs(hud.layout.handCenters[3] - 0.867) <= 0.055,
                "fourth hand slot was not located adaptively");

        ElixirBarTracker tracker = new ElixirBarTracker();
        ElixirBarTracker.Reading reading = null;
        long time = 1_000;
        for (int i = 0; i < 7; i++) reading = tracker.update(frame, hud, time += 100);
        require(reading != null && reading.locked(), "Elixir rail did not lock");
        require(Math.abs(reading.value - 7.0) <= 0.85,
                "Elixir value did not come from the visible fill");

        frame.setFill(0.30);
        boolean drop = false;
        for (int i = 0; i < 3; i++) {
            reading = tracker.update(frame, hud, time += 100);
            drop |= reading.sharpDrop;
        }
        require(drop && reading.value < 4.2, "sharp spend was over-smoothed");

        frame.setFill(0.0);
        frame.setLowerLabelNoise(true);
        frame.setBattleMessageNoise(true);
        for (int i = 0; i < 4; i++) reading = tracker.update(frame, hud, time += 100);
        require(reading.locked() && reading.value <= 0.20,
                "empty rail or HUD text was reported as Elixir");
        require(reading.rail != null && reading.rail.centerY() <= 0.986,
                "rail drifted into the Max:10 label");

        frame.setDeploymentFlash(true);
        for (int i = 0; i < 2; i++) reading = tracker.update(frame, hud, time += 100);
        require(reading.hasValue() && reading.value <= 0.35,
                "pale deployment flash was reported as Elixir");

        SyntheticFrame shifted = new SyntheticFrame(1080, 2400);
        shifted.setRailOffsetY(-0.012);
        HudLayoutTracker shiftedHudTracker = new HudLayoutTracker();
        HudLayoutTracker.Observation shiftedHud = null;
        for (int i = 0; i < 5; i++) shiftedHud = shiftedHudTracker.update(shifted);
        ElixirBarTracker shiftedTracker = new ElixirBarTracker();
        ElixirBarTracker.Reading shiftedReading = null;
        for (int i = 0; i < 7; i++) {
            shiftedReading = shiftedTracker.update(shifted, shiftedHud, time += 100);
        }
        require(shiftedReading != null && shiftedReading.locked()
                        && Math.abs(shiftedReading.value - 7.0) <= 0.85,
                "vertically shifted rail did not calibrate");
        require(shiftedReading.rail != null
                        && Math.abs(shiftedReading.rail.centerY() - 0.9655) <= 0.007,
                "rail remained fixed to the phone-profile seed");

        SyntheticFrame emptyStart = new SyntheticFrame(1080, 2400);
        emptyStart.setFill(0.0);
        HudLayoutTracker emptyHudTracker = new HudLayoutTracker();
        HudLayoutTracker.Observation emptyHud = null;
        for (int i = 0; i < 5; i++) emptyHud = emptyHudTracker.update(emptyStart);
        ElixirBarTracker emptyTracker = new ElixirBarTracker();
        ElixirBarTracker.Reading emptyReading = null;
        for (int i = 0; i < 10; i++) {
            emptyReading = emptyTracker.update(emptyStart, emptyHud, time += 100);
        }
        require(emptyReading != null && !emptyReading.locked() && !emptyReading.hasValue(),
                "empty loading/deck strip initialized a fake rail");
        emptyStart.setFill(0.70);
        for (int i = 0; i < 7; i++) {
            emptyReading = emptyTracker.update(emptyStart, emptyHud, time += 100);
        }
        require(emptyReading.locked() && Math.abs(emptyReading.value - 7.0) <= 0.85,
                "rail did not acquire after real fill appeared");
    }

    private static void battleLifecycle() {
        BattleStateMachine machine = new BattleStateMachine();
        BattleCueDetector.Signals strong = new BattleCueDetector.Signals(
                0.80, 0.72, 0.68, 0.58, 0.72, 0.82, 0.0, 0.72, 5);
        BattleCueDetector.Signals absent = new BattleCueDetector.Signals(
                0.05, 0.0, 0.08, 0.06, 0.10, 0.0, 0.0, 0.05, 0);
        require(machine.update(strong, 1_000).state == BattleStateMachine.State.BATTLE_CANDIDATE,
                "battle candidate stage was skipped");
        require(machine.update(strong, 1_500).state == BattleStateMachine.State.VERIFYING,
                "battle verification stage was skipped");
        require(machine.update(strong, 2_200).enteredBattle,
                "stable multi-sensor evidence did not enter battle");
        require(machine.update(absent, 2_300).state == BattleStateMachine.State.IN_BATTLE,
                "one missed frame ended battle");
        machine.update(absent, 4_300);
        require(machine.update(absent, 8_000).exitedBattle,
                "sustained HUD loss did not end battle");
    }

    private static void opponentUncertaintyAndCycle() {
        OpponentStateTracker tracker = new OpponentStateTracker();
        tracker.start(1_000);
        OpponentStateTracker.Estimate initial = tracker.estimate(1_000);
        require(Double.isNaN(initial.best) && initial.min == 0.0 && initial.max == 10.0,
                "hidden opponent Elixir was invented at match start");
        require(tracker.anchorFromLocal(1_000, 7.2, 0.90, true),
                "clean local observation was not accepted as an opening anchor");
        tracker.setRegenRate(new RegenRateEstimator.Estimate(0.50, 0.47, 0.53, 0.90));
        require(Math.abs(tracker.estimate(3_000).best - 8.2) <= 0.08,
                "opponent interval did not regenerate");
        tracker.onOpponentCard(3_000, null, 4, 0.0, 0.82);
        require(Math.abs(tracker.estimate(3_000).best - 4.2) <= 0.12,
                "observed opponent spend did not update the interval");

        tracker.onOpponentCard(3_100, "miner", 3, 0.95, 0.85);
        tracker.onOpponentCard(3_200, null, 2, 0.0, 0.80);
        tracker.onOpponentCard(3_300, null, 4, 0.0, 0.80);
        tracker.onOpponentCard(3_400, null, 3, 0.0, 0.80);
        require(tracker.knownCount() == 1, "unknown events invented opponent cards");
        require(tracker.deckSlots().size() == 8, "opponent deck is not exactly eight slots");
    }

    private static void regenAndFeatures() {
        RegenRateEstimator estimator = new RegenRateEstimator();
        for (int i = 0; i < 10; i++) {
            double value = 3.0 + i * 0.10;
            estimator.update(new ElixirBarTracker.Reading(ElixirBarTracker.State.LOCKED,
                    value, value, 0.90, 0.90, null, false, 0.0, 1_000 + i * 280L));
        }
        require(estimator.current().confidence > 0.35,
                "visible regeneration slope was not learned");

        FrameRect full = new FrameRect(0, 0, 1, 1);
        CardVisualFeatures.Feature a = CardVisualFeatures.extract(pattern(false), full);
        CardVisualFeatures.Feature b = CardVisualFeatures.extract(pattern(false), full);
        CardVisualFeatures.Feature c = CardVisualFeatures.extract(pattern(true), full);
        require(Math.abs(CardVisualFeatures.similarity(a, b) - 1.0) <= 0.0001,
                "identical card artwork did not match");
        require(CardVisualFeatures.similarity(a, c) < 0.88,
                "spatially different artwork was not separated");
    }

    private static PixelFrame pattern(final boolean swapped) {
        return new PixelFrame() {
            @Override public int width() { return 160; }
            @Override public int height() { return 200; }
            @Override public int rgb(int x, int y) {
                boolean block = ((x / 20) + (y / 20)) % 2 == 0;
                if (swapped) block = !block;
                return block ? 0xe2364f : 0x286fdd;
            }
        };
    }

    private static final class SyntheticFrame implements PixelFrame {
        private final int width;
        private final int height;
        private final double[] centers = {0.313, 0.4975, 0.682, 0.8665};
        private double fill = 0.70;
        private boolean lowerLabelNoise;
        private boolean battleMessageNoise;
        private boolean deploymentFlash;
        private double railOffsetY;

        SyntheticFrame(int width, int height) {
            this.width = width;
            this.height = height;
        }

        void setFill(double value) {
            fill = Math.max(0.0, Math.min(1.0, value));
        }

        void setLowerLabelNoise(boolean enabled) { lowerLabelNoise = enabled; }
        void setBattleMessageNoise(boolean enabled) { battleMessageNoise = enabled; }
        void setDeploymentFlash(boolean enabled) { deploymentFlash = enabled; }
        void setRailOffsetY(double offset) { railOffsetY = offset; }

        @Override public int width() { return width; }
        @Override public int height() { return height; }

        @Override public int rgb(int x, int y) {
            double nx = x / (double)Math.max(1, width - 1);
            double ny = y / (double)Math.max(1, height - 1);
            if (lowerLabelNoise && ny >= 0.987 && ny <= 0.994
                    && nx >= 0.276 && nx <= 0.385) {
                return rgb(205, 88, 214);
            }
            if (battleMessageNoise && ny >= 0.962 && ny <= 0.976
                    && nx >= 0.300 && nx <= 0.760) {
                int stripe = ((int)(nx * width / 13.0) + (int)(ny * height / 7.0)) & 3;
                return stripe == 0 ? rgb(24, 32, 88) : rgb(112, 34, 88);
            }
            if (deploymentFlash && ny >= 0.978 && ny <= 0.985
                    && nx >= 0.276 && nx <= 0.420) {
                return rgb(255, 232, 255);
            }
            if (ny >= 0.972 + railOffsetY && ny <= 0.983 + railOffsetY
                    && nx >= 0.276 && nx <= 0.972) {
                double position = (nx - 0.276) / 0.696;
                return position <= fill ? rgb(190, 42, 226) : rgb(5, 55, 123);
            }
            if (ny >= 0.70) {
                for (int i = 0; i < centers.length; i++) {
                    if (Math.abs(nx - centers[i]) <= 0.082 && ny >= 0.742 && ny <= 0.930) {
                        int gx = (int)(nx * width / 15.0);
                        int gy = (int)(ny * height / 18.0);
                        int shift = (gx + gy + i * 3) & 3;
                        if (shift == 0) return rgb(225 - i * 22, 72 + i * 25, 105 + i * 19);
                        if (shift == 1) return rgb(55 + i * 31, 155 - i * 13, 222 - i * 24);
                        if (shift == 2) return rgb(225, 186 - i * 20, 53 + i * 17);
                        return rgb(56, 45, 76);
                    }
                }
                return rgb(17, 14, 24);
            }
            int wave = (int)((Math.sin(nx * 31.0) + Math.cos(ny * 37.0)) * 18.0);
            return rgb(70 + wave, 112 + wave / 2, 78 - wave / 3);
        }

        private static int rgb(int r, int g, int b) {
            r = Math.max(0, Math.min(255, r));
            g = Math.max(0, Math.min(255, g));
            b = Math.max(0, Math.min(255, b));
            return (r << 16) | (g << 8) | b;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private CoreSmokeHarness() {}
}
