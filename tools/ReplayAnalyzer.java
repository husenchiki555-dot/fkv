package com.huseyn.elixircollector;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

/** Offline frame-sequence diagnostics for captured matches. */
public final class ReplayAnalyzer {
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("Usage: ReplayAnalyzer <frames-directory> [fps]");
        }
        Path directory = Path.of(args[0]);
        double fps = args.length == 2 ? Double.parseDouble(args[1]) : 10.0;
        if (!Files.isDirectory(directory) || fps <= 0.0) {
            throw new IllegalArgumentException("A frame directory and positive fps are required");
        }
        List<Path> frames;
        try (java.util.stream.Stream<Path> stream = Files.list(directory)) {
            frames = stream.filter(ReplayAnalyzer::isImage)
                    .sorted(Comparator.comparing(Path::getFileName))
                    .collect(Collectors.toList());
        }
        if (frames.isEmpty()) throw new IllegalArgumentException("No PNG/JPEG frames found");

        HudLayoutTracker hudTracker = new HudLayoutTracker();
        ElixirBarTracker elixirTracker = new ElixirBarTracker();
        BattleCueDetector cues = new BattleCueDetector();
        BattleStateMachine battle = new BattleStateMachine();
        ArenaMotionDetector arena = new ArenaMotionDetector();
        System.out.println("frame,time_ms,battle,battle_conf,hud,hand_score,rail,elixir,elixir_conf,timer,crown,arena,motion,deployment");
        long frameDuration = Math.max(1L, Math.round(1000.0 / fps));
        for (int i = 0; i < frames.size(); i++) {
            BufferedImage image = read(frames.get(i));
            PixelFrame frame = new BufferedFrame(image);
            long now = i * frameDuration;
            HudLayoutTracker.Observation hud = hudTracker.update(frame);
            ElixirBarTracker.Reading elixir = elixirTracker.update(frame, hud, now);
            BattleCueDetector.Signals signal = cues.detect(frame, hud, elixir, false);
            BattleStateMachine.Update state = battle.update(signal, now);
            ArenaMotionDetector.Observation motion = arena.update(frame, now);
            System.out.printf(Locale.US,
                    "%d,%d,%s,%.3f,%s,%.3f,%s,%s,%.3f,%.3f,%.3f,%.3f,%.3f,%s%n",
                    i, now, state.state, state.confidence, hud.state, signal.handScore,
                    elixir.state, number(elixir.value), elixir.confidence,
                    signal.timerScore, signal.crownScore, signal.arenaScore,
                    motion.changedFraction, motion.deploymentLike);
        }
    }

    private static BufferedImage read(Path file) throws IOException {
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) throw new IOException("Unsupported image: " + file);
        return image;
    }

    private static boolean isImage(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    private static String number(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.US, "%.3f", value);
    }

    private static final class BufferedFrame implements PixelFrame {
        private final BufferedImage image;
        BufferedFrame(BufferedImage image) { this.image = image; }
        @Override public int width() { return image.getWidth(); }
        @Override public int height() { return image.getHeight(); }
        @Override public int rgb(int x, int y) { return image.getRGB(x, y) & 0x00ffffff; }
    }

    private ReplayAnalyzer() {}
}
