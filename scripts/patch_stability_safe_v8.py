from pathlib import Path

SOURCE = Path("app/src/main/java/com/huseyn/elixircollector/IconCaptureService.java")
text = SOURCE.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected exactly one Java match, found {count}: {old[:160]!r}")
    text = text.replace(old, new, 1)


# Prevent brief UI flicker from repeatedly starting/stopping and resetting the counter.
replace_once(
    "    private static final int BATTLE_LOCK_FRAMES = 3;\n"
    "    private static final int BATTLE_LOST_FRAMES = 12;",
    "    private static final int BATTLE_LOCK_FRAMES = 5;\n"
    "    private static final int BATTLE_LOST_FRAMES = 30;"
)

# Roll back the overly permissive v7 thresholds. Keep evidence fusion, but require stable OCR.
replace_once(
    "    private static final long TRACK_MATCH_WINDOW_MS = 1600L;\n"
    "    private static final long TRACK_EXPIRE_MS = 3100L;\n"
    "    private static final long AUTOMATIC_SPEND_GAP_MS = 220L;\n"
    "    private static final long OWNERSHIP_LOOKBACK_MS = 1350L;\n"
    "    private static final long OWNERSHIP_DECISION_DELAY_MS = 720L;\n"
    "    private static final long SELF_EVIDENCE_EXPIRE_MS = 2800L;\n"
    "    private static final long BAR_DROP_DEBOUNCE_MS = 260L;\n"
    "    private static final int HAND_SLOT_SAMPLE_W = 7;\n"
    "    private static final int HAND_SLOT_SAMPLE_H = 7;\n"
    "    private static final int DRAG_SAMPLE_W = 20;\n"
    "    private static final int DRAG_SAMPLE_H = 5;\n"
    "    private static final double HAND_CHANGE_THRESHOLD = 13.5;",
    "    private static final long TRACK_MATCH_WINDOW_MS = 1450L;\n"
    "    private static final long TRACK_EXPIRE_MS = 2400L;\n"
    "    private static final long AUTOMATIC_SPEND_GAP_MS = 520L;\n"
    "    private static final long OWNERSHIP_LOOKBACK_MS = 1650L;\n"
    "    private static final long OWNERSHIP_DECISION_DELAY_MS = 900L;\n"
    "    private static final long SELF_EVIDENCE_EXPIRE_MS = 3400L;\n"
    "    private static final long BAR_DROP_DEBOUNCE_MS = 350L;\n"
    "    private static final int HAND_SLOT_SAMPLE_W = 8;\n"
    "    private static final int HAND_SLOT_SAMPLE_H = 8;\n"
    "    private static final int DRAG_SAMPLE_W = 20;\n"
    "    private static final int DRAG_SAMPLE_H = 5;\n"
    "    private static final double HAND_CHANGE_THRESHOLD = 15.0;"
)

replace_once(
    "            if (area < 4 || area > 900 || boxW < 2 || boxH < 2 || boxW > 52 || boxH > 58) {",
    "            if (area < 6 || area > 560 || boxW < 3 || boxH < 3 || boxW > 40 || boxH > 44) {"
)

replace_once(
    "            if (aspect < 0.22 || aspect > 2.40 || fill < 0.07 || motion < 5.0) {",
    "            if (aspect < 0.30 || aspect > 2.05 || fill < 0.10 || motion < 8.0) {"
)

replace_once(
    "            if (result != null && result.confidence >= 0.35) {",
    "            if (result != null && result.confidence >= 0.48) {"
)

# Never deduct from a one-frame badge. Those were the main source of random jumps.
replace_once(
    "                if (!track.processed && !track.awaitingOwnership\n"
    "                        && track.hits == 1 && track.currentConfidence() >= 0.64) {\n"
    "                    resolveTrack(track, now);\n"
    "                }\n"
    "                iterator.remove();",
    "                iterator.remove();"
)

replace_once(
    "        boolean singleFrameClear = track.hits == 1 && confidence >= 0.76;\n"
    "        boolean multiFrameStable = track.hits >= 2\n"
    "                && confidence >= 0.40\n"
    "                && voteShare >= 0.46;\n"
    "        if (singleFrameClear || multiFrameStable) {\n"
    "            track.awaitingOwnership = true;\n"
    "            track.decisionAt = now + (singleFrameClear ? 320L : OWNERSHIP_DECISION_DELAY_MS);\n"
    "        }",
    "        boolean highQualityPair = track.hits >= 2\n"
    "                && confidence >= 0.72\n"
    "                && voteShare >= 0.76;\n"
    "        boolean stableMultiFrame = track.hits >= 3\n"
    "                && confidence >= 0.56\n"
    "                && voteShare >= 0.66;\n"
    "        if (highQualityPair || stableMultiFrame) {\n"
    "            track.awaitingOwnership = true;\n"
    "            track.decisionAt = now + OWNERSHIP_DECISION_DELAY_MS;\n"
    "        }"
)

# Prefer missing an opponent play over charging the user's own play.
replace_once(
    "        if (best != null && bestScore >= 6.0) {",
    "        if (best != null && bestScore >= 4.5) {"
)

# Restrict the visible-elixir measurement to the actual lower bar region.
replace_once(
    "        int left = (int) (frame.width * 0.04);\n"
    "        int right = (int) (frame.width * 0.98);\n"
    "        int top = (int) (frame.height * 0.82);\n"
    "        int bottom = (int) (frame.height * 0.975);",
    "        int left = (int) (frame.width * 0.08);\n"
    "        int right = (int) (frame.width * 0.92);\n"
    "        int top = (int) (frame.height * 0.90);\n"
    "        int bottom = (int) (frame.height * 0.985);"
)

replace_once(
    "            if (span >= sampledWidth * 0.24) {",
    "            if (span >= sampledWidth * 0.34) {"
)

# Sample the four hand cards around their actual central layout with slightly larger crops.
replace_once(
    "        double centerRatio = 0.20 + slot * 0.20;\n"
    "        int left = (int) (frame.width * (centerRatio - 0.078));\n"
    "        int right = (int) (frame.width * (centerRatio + 0.078));\n"
    "        int top = (int) (frame.height * 0.785);\n"
    "        int bottom = (int) (frame.height * 0.915);",
    "        double centerRatio = 0.23 + slot * 0.18;\n"
    "        int left = (int) (frame.width * (centerRatio - 0.088));\n"
    "        int right = (int) (frame.width * (centerRatio + 0.088));\n"
    "        int top = (int) (frame.height * 0.775);\n"
    "        int bottom = (int) (frame.height * 0.94);"
)

SOURCE.write_text(text, encoding="utf-8")

GRADLE = Path("app/build.gradle")
gradle = GRADLE.read_text(encoding="utf-8")
old_version = "        versionCode 700\n        versionName '7.0.0-evidence-fusion'"
new_version = "        versionCode 800\n        versionName '8.0.0-stability-safe'"
if gradle.count(old_version) != 1:
    raise RuntimeError("Expected v7 version block exactly once")
GRADLE.write_text(gradle.replace(old_version, new_version, 1), encoding="utf-8")

print("Applied v8 conservative stability patch")
