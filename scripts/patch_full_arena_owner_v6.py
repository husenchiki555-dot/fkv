from pathlib import Path

SOURCE = Path("app/src/main/java/com/huseyn/elixircollector/IconCaptureService.java")
text = SOURCE.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected exactly one Java match, found {count}: {old[:120]!r}")
    text = text.replace(old, new, 1)


replace_once(
    "    private static final long TRACK_MATCH_WINDOW_MS = 1200L;\n"
    "    private static final long TRACK_EXPIRE_MS = 2300L;\n"
    "    private static final long AUTOMATIC_SPEND_GAP_MS = 750L;",
    "    private static final long TRACK_MATCH_WINDOW_MS = 1450L;\n"
    "    private static final long TRACK_EXPIRE_MS = 2600L;\n"
    "    private static final long AUTOMATIC_SPEND_GAP_MS = 650L;\n"
    "    private static final long OWNERSHIP_LOOKBACK_MS = 950L;\n"
    "    private static final long OWNERSHIP_DECISION_DELAY_MS = 520L;\n"
    "    private static final int HAND_SLOT_SAMPLE_W = 8;\n"
    "    private static final int HAND_SLOT_SAMPLE_H = 8;\n"
    "    private static final double HAND_CHANGE_THRESHOLD = 18.5;"
)

replace_once(
    "    private long lastAutomaticSpendAtMs;",
    "    private long lastAutomaticSpendAtMs;\n"
    "    private byte[] previousHandGray;\n"
    "    private long lastHandChangeAtMs;"
)

replace_once(
    "            Frame frame = new Frame(image);\n"
    "            boolean battleUiVisible = detectBattleUi(frame);",
    "            Frame frame = new Frame(image);\n"
    "            updatePlayerHandActivity(frame, now);\n"
    "            boolean battleUiVisible = detectBattleUi(frame);"
)

replace_once(
    "                previousMaskHeight = 0;\n"
    "                iconTracks.clear();",
    "                previousMaskHeight = 0;\n"
    "                previousHandGray = null;\n"
    "                lastHandChangeAtMs = 0L;\n"
    "                iconTracks.clear();"
)

replace_once(
    "        lastAutomaticSpendAtMs = 0L;\n"
    "        iconTracks.clear();\n"
    "        previousArenaGray = null;",
    "        lastAutomaticSpendAtMs = 0L;\n"
    "        lastHandChangeAtMs = 0L;\n"
    "        previousHandGray = null;\n"
    "        iconTracks.clear();\n"
    "        previousArenaGray = null;"
)

replace_once(
    "        lastAutomaticSpendAtMs = 0L;\n"
    "        iconTracks.clear();",
    "        lastAutomaticSpendAtMs = 0L;\n"
    "        lastHandChangeAtMs = 0L;\n"
    "        previousHandGray = null;\n"
    "        iconTracks.clear();"
)

replace_once(
    "    /**\n"
    "     * Scans only the opponent deployment half, ending just above the river.\n"
    "     * This intentionally ignores the player's lower deployment half so friendly placements\n"
    "     * cannot be charged against the opponent estimate. Cross-arena spells may need correction.\n"
    "     */",
    "    /**\n"
    "     * Scans the full playable arena. Ownership is decided from the player's hand animation:\n"
    "     * a matching hand change means the placement was ours and is ignored; otherwise it is\n"
    "     * treated as an opponent placement. This permits enemy spells on the player's half.\n"
    "     */"
)

replace_once(
    "        // Opponent-only safety boundary: do not scan the player's deployment half.\n"
    "        int bottom = (int) (height * 0.49);",
    "        // Full arena, but still excludes the hand and player elixir bar.\n"
    "        int bottom = (int) (height * 0.76);"
)

replace_once(
    "            if (result != null && result.confidence >= 0.58) {",
    "            if (result != null && result.confidence >= 0.55) {"
)

replace_once(
    "                if (!nearest.processed && nearest.hits >= 3) {\n"
    "                    nearest.processed = true;\n"
    "                    final int cost = nearest.cost;\n"
    "                    final double confidence = nearest.confidence;\n"
    "                    mainHandler.post(() -> {\n"
    "                        if (spendDetectedElixir(cost, now)) {\n"
    "                            setStatus(\"OPP −\" + cost + \" • \"\n"
    "                                    + String.format(Locale.US, \"%.0f%%\", confidence * 100.0), 2);\n"
    "                        }\n"
    "                    });\n"
    "                }",
    "                int requiredHits = nearest.y >= screenHeight * 0.49 ? 2 : 3;\n"
    "                if (!nearest.processed && !nearest.awaitingOwnership\n"
    "                        && nearest.hits >= requiredHits) {\n"
    "                    nearest.awaitingOwnership = true;\n"
    "                    nearest.decisionAt = now + OWNERSHIP_DECISION_DELAY_MS;\n"
    "                }"
)

replace_once(
    "        Iterator<IconTrack> iterator = iconTracks.iterator();",
    "        for (IconTrack track : iconTracks) {\n"
    "            if (!track.processed && track.awaitingOwnership && now >= track.decisionAt) {\n"
    "                track.processed = true;\n"
    "                track.awaitingOwnership = false;\n"
    "                final int cost = track.cost;\n"
    "                final double confidence = track.confidence;\n"
    "                final boolean playerOwned = wasPlayerHandUsedNear(track.firstSeen, track.decisionAt);\n"
    "                mainHandler.post(() -> {\n"
    "                    if (playerOwned) {\n"
    "                        setStatus(\"YOUR −\" + cost + \" IGNORED\", 0);\n"
    "                    } else if (spendDetectedElixir(cost, now)) {\n"
    "                        setStatus(\"OPP −\" + cost + \" • \"\n"
    "                                + String.format(Locale.US, \"%.0f%%\", confidence * 100.0), 2);\n"
    "                    }\n"
    "                });\n"
    "            }\n"
    "        }\n\n"
    "        Iterator<IconTrack> iterator = iconTracks.iterator();"
)

replace_once(
    "    private static boolean isBarPurple(int r, int g, int b) {",
    "    private void updatePlayerHandActivity(Frame frame, long now) {\n"
    "        if (!inBattle) {\n"
    "            previousHandGray = null;\n"
    "            return;\n"
    "        }\n"
    "        byte[] current = new byte[4 * HAND_SLOT_SAMPLE_W * HAND_SLOT_SAMPLE_H];\n"
    "        int index = 0;\n"
    "        for (int slot = 0; slot < 4; slot++) {\n"
    "            double centerRatio = 0.20 + slot * 0.20;\n"
    "            int left = (int) (frame.width * (centerRatio - 0.075));\n"
    "            int right = (int) (frame.width * (centerRatio + 0.075));\n"
    "            int top = (int) (frame.height * 0.795);\n"
    "            int bottom = (int) (frame.height * 0.915);\n"
    "            for (int sy = 0; sy < HAND_SLOT_SAMPLE_H; sy++) {\n"
    "                int y = top + sy * Math.max(1, bottom - top) / HAND_SLOT_SAMPLE_H;\n"
    "                for (int sx = 0; sx < HAND_SLOT_SAMPLE_W; sx++) {\n"
    "                    int x = left + sx * Math.max(1, right - left) / HAND_SLOT_SAMPLE_W;\n"
    "                    int gray = (frame.r(x, y) * 30 + frame.g(x, y) * 59\n"
    "                            + frame.b(x, y) * 11) / 100;\n"
    "                    current[index++] = (byte) gray;\n"
    "                }\n"
    "            }\n"
    "        }\n"
    "        if (previousHandGray != null && previousHandGray.length == current.length) {\n"
    "            int slotPixels = HAND_SLOT_SAMPLE_W * HAND_SLOT_SAMPLE_H;\n"
    "            for (int slot = 0; slot < 4; slot++) {\n"
    "                long totalDiff = 0L;\n"
    "                int strongPixels = 0;\n"
    "                int offset = slot * slotPixels;\n"
    "                for (int i = 0; i < slotPixels; i++) {\n"
    "                    int diff = Math.abs((current[offset + i] & 0xff)\n"
    "                            - (previousHandGray[offset + i] & 0xff));\n"
    "                    totalDiff += diff;\n"
    "                    if (diff >= 28) {\n"
    "                        strongPixels++;\n"
    "                    }\n"
    "                }\n"
    "                double meanDiff = totalDiff / (double) slotPixels;\n"
    "                if (meanDiff >= HAND_CHANGE_THRESHOLD && strongPixels >= 11) {\n"
    "                    lastHandChangeAtMs = now;\n"
    "                    break;\n"
    "                }\n"
    "            }\n"
    "        }\n"
    "        previousHandGray = current;\n"
    "    }\n\n"
    "    private boolean wasPlayerHandUsedNear(long placementFirstSeen, long decisionAt) {\n"
    "        return lastHandChangeAtMs > 0L\n"
    "                && lastHandChangeAtMs >= placementFirstSeen - OWNERSHIP_LOOKBACK_MS\n"
    "                && lastHandChangeAtMs <= decisionAt + 120L;\n"
    "    }\n\n"
    "    private static boolean isBarPurple(int r, int g, int b) {"
)

replace_once(
    "        long lastSeen;\n"
    "        boolean processed;",
    "        final long firstSeen;\n"
    "        long lastSeen;\n"
    "        long decisionAt;\n"
    "        boolean awaitingOwnership;\n"
    "        boolean processed;"
)

replace_once(
    "            confidence = detection.confidence;\n"
    "            lastSeen = now;",
    "            confidence = detection.confidence;\n"
    "            firstSeen = now;\n"
    "            lastSeen = now;"
)

SOURCE.write_text(text, encoding="utf-8")

GRADLE = Path("app/build.gradle")
gradle = GRADLE.read_text(encoding="utf-8")
old_version = "        versionCode 500\n        versionName '5.0.0-opponent-only'"
new_version = "        versionCode 600\n        versionName '6.0.0-full-arena-owner-filter'"
if gradle.count(old_version) != 1:
    raise RuntimeError("Expected v5 version block exactly once")
GRADLE.write_text(gradle.replace(old_version, new_version, 1), encoding="utf-8")

print("Applied full-arena ownership filter v6 patch")
