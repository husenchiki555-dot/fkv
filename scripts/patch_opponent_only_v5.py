from pathlib import Path

SOURCE = Path("app/src/main/java/com/huseyn/elixircollector/IconCaptureService.java")
text = SOURCE.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected exactly one Java match, found {count}: {old[:100]!r}")
    text = text.replace(old, new, 1)


replace_once(
    "    private static final long TRACK_MATCH_WINDOW_MS = 560L;\n"
    "    private static final long TRACK_EXPIRE_MS = 1050L;",
    "    private static final long TRACK_MATCH_WINDOW_MS = 1200L;\n"
    "    private static final long TRACK_EXPIRE_MS = 2300L;\n"
    "    private static final long AUTOMATIC_SPEND_GAP_MS = 750L;"
)

replace_once(
    "    private long lastTickNanos;",
    "    private long lastTickNanos;\n"
    "    private long lastAutomaticSpendAtMs;"
)

replace_once(
    "    /**\n"
    "     * Scans every playable arena position, including the lower arena for Miner/Drill/spells.\n"
    "     * The hand and player elixir bar are excluded so their permanent cost badges are ignored.\n"
    "     */",
    "    /**\n"
    "     * Scans only the opponent deployment half, ending just above the river.\n"
    "     * This intentionally ignores the player's lower deployment half so friendly placements\n"
    "     * cannot be charged against the opponent estimate. Cross-arena spells may need correction.\n"
    "     */"
)

replace_once(
    "        int bottom = (int) (height * 0.76);",
    "        // Opponent-only safety boundary: do not scan the player's deployment half.\n"
    "        int bottom = (int) (height * 0.49);"
)

replace_once(
    "            if (result != null && result.confidence >= 0.43) {",
    "            if (result != null && result.confidence >= 0.58) {"
)

replace_once(
    "                if (!nearest.processed && nearest.hits >= 2) {",
    "                if (!nearest.processed && nearest.hits >= 3) {"
)

replace_once(
    "                    mainHandler.post(() -> {\n"
    "                        spendElixir(cost);\n"
    "                        setStatus(\"ICON −\" + cost + \" • \"\n"
    "                                + String.format(Locale.US, \"%.0f%%\", confidence * 100.0), 2);\n"
    "                    });",
    "                    mainHandler.post(() -> {\n"
    "                        if (spendDetectedElixir(cost, now)) {\n"
    "                            setStatus(\"OPP −\" + cost + \" • \"\n"
    "                                    + String.format(Locale.US, \"%.0f%%\", confidence * 100.0), 2);\n"
    "                        }\n"
    "                    });"
)

replace_once(
    "        lastTickNanos = System.nanoTime();\n"
    "        iconTracks.clear();\n"
    "        previousArenaGray = null;",
    "        lastTickNanos = System.nanoTime();\n"
    "        lastAutomaticSpendAtMs = 0L;\n"
    "        iconTracks.clear();\n"
    "        previousArenaGray = null;"
)

replace_once(
    "        regenerationRunning = false;\n"
    "        iconTracks.clear();",
    "        regenerationRunning = false;\n"
    "        lastAutomaticSpendAtMs = 0L;\n"
    "        iconTracks.clear();"
)

replace_once(
    "                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE\n"
    "                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,",
    "                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE\n"
    "                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS\n"
    "                        | WindowManager.LayoutParams.FLAG_SECURE,"
)

replace_once(
    "    private void spendElixir(int cost) {\n"
    "        estimatedElixir = Math.max(0.0, estimatedElixir - cost);\n"
    "        updateDisplay();\n"
    "    }",
    "    private boolean spendDetectedElixir(int cost, long detectedAtMs) {\n"
    "        if (!inBattle || cost < 1 || cost > 9) {\n"
    "            return false;\n"
    "        }\n"
    "        if (lastAutomaticSpendAtMs > 0L\n"
    "                && detectedAtMs - lastAutomaticSpendAtMs < AUTOMATIC_SPEND_GAP_MS) {\n"
    "            return false;\n"
    "        }\n"
    "        // Reject OCR results that are impossible for the current estimate.\n"
    "        if (cost > estimatedElixir + 1.0) {\n"
    "            setStatus(\"IGNORED OPP −\" + cost + \" • estimate too low\", 1);\n"
    "            return false;\n"
    "        }\n"
    "        estimatedElixir = Math.max(0.0, Math.min(10.0, estimatedElixir - cost));\n"
    "        lastAutomaticSpendAtMs = detectedAtMs;\n"
    "        updateDisplay();\n"
    "        return true;\n"
    "    }\n\n"
    "    private void spendElixir(int cost) {\n"
    "        estimatedElixir = Math.max(0.0, Math.min(10.0, estimatedElixir - cost));\n"
    "        updateDisplay();\n"
    "    }"
)

SOURCE.write_text(text, encoding="utf-8")

GRADLE = Path("app/build.gradle")
gradle = GRADLE.read_text(encoding="utf-8")
old_version = "        versionCode 400\n        versionName '4.0.0-icon-vision'"
new_version = "        versionCode 500\n        versionName '5.0.0-opponent-only'"
if gradle.count(old_version) != 1:
    raise RuntimeError("Expected v4 version block exactly once")
GRADLE.write_text(gradle.replace(old_version, new_version, 1), encoding="utf-8")

print("Applied opponent-only v5 safety patch")
