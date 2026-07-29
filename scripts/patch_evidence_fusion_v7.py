from pathlib import Path
import re

SOURCE = Path("app/src/main/java/com/huseyn/elixircollector/IconCaptureService.java")
text = SOURCE.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected exactly one Java match, found {count}: {old[:140]!r}")
    text = text.replace(old, new, 1)


def replace_expected(old: str, new: str, expected: int) -> None:
    global text
    count = text.count(old)
    if count != expected:
        raise RuntimeError(
            f"Expected {expected} Java matches, found {count}: {old[:140]!r}"
        )
    text = text.replace(old, new)


def regex_once(pattern: str, replacement: str) -> None:
    global text
    updated, count = re.subn(
        pattern,
        lambda _match: replacement,
        text,
        count=1,
        flags=re.DOTALL,
    )
    if count != 1:
        raise RuntimeError(f"Expected one regex match, found {count}: {pattern[:160]!r}")
    text = updated


replace_once(
    "    private static final long TRACK_MATCH_WINDOW_MS = 1450L;\n"
    "    private static final long TRACK_EXPIRE_MS = 2600L;\n"
    "    private static final long AUTOMATIC_SPEND_GAP_MS = 650L;\n"
    "    private static final long OWNERSHIP_LOOKBACK_MS = 950L;\n"
    "    private static final long OWNERSHIP_DECISION_DELAY_MS = 520L;\n"
    "    private static final int HAND_SLOT_SAMPLE_W = 8;\n"
    "    private static final int HAND_SLOT_SAMPLE_H = 8;\n"
    "    private static final double HAND_CHANGE_THRESHOLD = 18.5;",
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
    "    private static final double HAND_CHANGE_THRESHOLD = 13.5;"
)

replace_once(
    "    private long lastAutomaticSpendAtMs;\n"
    "    private byte[] previousHandGray;\n"
    "    private long lastHandChangeAtMs;",
    "    private long lastAutomaticSpendAtMs;\n"
    "    private final List<SelfPlayEvidence> selfPlayEvidence = new ArrayList<>();\n"
    "    private byte[][] stableHandSlots = new byte[4][];\n"
    "    private int[] handChangeStreak = new int[4];\n"
    "    private long[] lastHandSlotEventAtMs = new long[4];\n"
    "    private long lastAnyHandChangeAtMs;\n"
    "    private byte[] previousDragBandGray;\n"
    "    private long lastDragMotionAtMs;\n"
    "    private double previousPlayerBarSignal = -1.0;\n"
    "    private double playerBarPeakSignal;\n"
    "    private int pendingBarDropCost;\n"
    "    private long pendingBarDropAtMs;\n"
    "    private double pendingBarDropSignal;\n"
    "    private long lastBarDropAtMs;"
)

replace_once(
    "            updatePlayerHandActivity(frame, now);",
    "            updatePlayerEvidence(frame, now);"
)

replace_once(
    "                previousMaskHeight = 0;\n"
    "                previousHandGray = null;\n"
    "                lastHandChangeAtMs = 0L;\n"
    "                iconTracks.clear();",
    "                previousMaskHeight = 0;\n"
    "                resetPlayerEvidence();\n"
    "                iconTracks.clear();"
)

replace_expected(
    "        lastAutomaticSpendAtMs = 0L;\n"
    "        lastHandChangeAtMs = 0L;\n"
    "        previousHandGray = null;\n"
    "        iconTracks.clear();\n"
    "        previousArenaGray = null;",
    "        lastAutomaticSpendAtMs = 0L;\n"
    "        resetPlayerEvidence();\n"
    "        iconTracks.clear();\n"
    "        previousArenaGray = null;",
    2
)

replace_once(
    "            if (area < 6 || area > 420 || boxW < 3 || boxH < 3 || boxW > 34 || boxH > 38) {",
    "            if (area < 4 || area > 900 || boxW < 2 || boxH < 2 || boxW > 52 || boxH > 58) {"
)

replace_once(
    "            if (aspect < 0.34 || aspect > 1.85 || fill < 0.12 || motion < 12.0) {",
    "            if (aspect < 0.22 || aspect > 2.40 || fill < 0.07 || motion < 5.0) {"
)

replace_once(
    "            if (result != null && result.confidence >= 0.55) {",
    "            if (result != null && result.confidence >= 0.35) {"
)

regex_once(
    r"    private void updateIconTracks\(List<IconDetection> detections, long now,\n"
    r"                                  int screenWidth, int screenHeight\) \{.*?\n"
    r"    \}\n\n"
    r"    private void updatePlayerHandActivity",
    r'''    private void updateIconTracks(List<IconDetection> detections, long now,
                                  int screenWidth, int screenHeight) {
        double radius = Math.max(20.0, Math.min(screenWidth, screenHeight) * 0.045);
        for (IconDetection detection : detections) {
            IconTrack nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            for (IconTrack track : iconTracks) {
                if (track.processed || now - track.lastSeen > TRACK_MATCH_WINDOW_MS) {
                    continue;
                }
                double dx = track.x - detection.x;
                double dy = track.y - detection.y;
                double distance = Math.sqrt(dx * dx + dy * dy);
                if (distance < radius && distance < nearestDistance) {
                    nearest = track;
                    nearestDistance = distance;
                }
            }

            IconTrack target;
            if (nearest == null) {
                target = new IconTrack(detection, now);
                iconTracks.add(target);
            } else {
                target = nearest;
                target.addDetection(detection, now);
            }
            armTrackIfReady(target, now);
        }

        for (IconTrack track : iconTracks) {
            if (!track.processed && track.awaitingOwnership && now >= track.decisionAt) {
                resolveTrack(track, now);
            }
        }

        Iterator<IconTrack> iterator = iconTracks.iterator();
        while (iterator.hasNext()) {
            IconTrack track = iterator.next();
            if (now - track.lastSeen > TRACK_EXPIRE_MS) {
                if (!track.processed && !track.awaitingOwnership
                        && track.hits == 1 && track.currentConfidence() >= 0.64) {
                    resolveTrack(track, now);
                }
                iterator.remove();
            }
        }
        cleanupSelfEvidence(now);
    }

    private void armTrackIfReady(IconTrack track, long now) {
        if (track.processed || track.awaitingOwnership) {
            return;
        }
        double confidence = track.currentConfidence();
        double voteShare = track.currentVoteShare();
        boolean singleFrameClear = track.hits == 1 && confidence >= 0.76;
        boolean multiFrameStable = track.hits >= 2
                && confidence >= 0.40
                && voteShare >= 0.46;
        if (singleFrameClear || multiFrameStable) {
            track.awaitingOwnership = true;
            track.decisionAt = now + (singleFrameClear ? 320L : OWNERSHIP_DECISION_DELAY_MS);
        }
    }

    private void resolveTrack(IconTrack track, long now) {
        if (track.processed) {
            return;
        }
        track.processed = true;
        track.awaitingOwnership = false;
        final int cost = track.currentCost();
        final double confidence = track.currentConfidence();
        final SelfPlayEvidence selfEvidence = claimSelfEvidence(track, now);
        mainHandler.post(() -> {
            if (selfEvidence != null) {
                String source = selfEvidence.cost > 0
                        ? "YOUR −" + cost + " • BAR/HAND"
                        : "YOUR −" + cost + " • HAND";
                setStatus(source + " IGNORED", 0);
            } else if (spendDetectedElixir(cost, now)) {
                setStatus("OPP −" + cost + " • "
                        + String.format(Locale.US, "%.0f%%", confidence * 100.0), 2);
            }
        });
    }

    private SelfPlayEvidence claimSelfEvidence(IconTrack track, long decisionAt) {
        cleanupSelfEvidence(decisionAt);
        SelfPlayEvidence best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        long windowStart = track.firstSeen - OWNERSHIP_LOOKBACK_MS;
        long windowEnd = decisionAt + 380L;
        int trackCost = track.currentCost();

        for (SelfPlayEvidence evidence : selfPlayEvidence) {
            if (evidence.claimed
                    || evidence.timeMs < windowStart
                    || evidence.timeMs > windowEnd) {
                continue;
            }
            long timeDistance = Math.abs(evidence.timeMs - track.firstSeen);
            double score = evidence.strength * 2.0;
            if (timeDistance <= 450L) {
                score += 3.0;
            } else if (timeDistance <= 850L) {
                score += 1.5;
            }
            if (evidence.cost > 0) {
                int costDifference = Math.abs(evidence.cost - trackCost);
                if (costDifference == 0) {
                    score += 6.0;
                } else if (costDifference == 1) {
                    score += 3.0;
                } else if (costDifference >= 3) {
                    score -= 4.0;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = evidence;
            }
        }

        if (best != null && bestScore >= 6.0) {
            best.claimed = true;
            return best;
        }
        return null;
    }

    private void cleanupSelfEvidence(long now) {
        Iterator<SelfPlayEvidence> iterator = selfPlayEvidence.iterator();
        while (iterator.hasNext()) {
            SelfPlayEvidence evidence = iterator.next();
            if (now - evidence.timeMs > SELF_EVIDENCE_EXPIRE_MS) {
                iterator.remove();
            }
        }
    }

    private void updatePlayerHandActivity'''
)

regex_once(
    r"    private void updatePlayerHandActivity\(Frame frame, long now\) \{.*?\n"
    r"    \}\n\n"
    r"    private boolean wasPlayerHandUsedNear\(long placementFirstSeen, long decisionAt\) \{.*?\n"
    r"    \}\n\n"
    r"    private static boolean isBarPurple",
    r'''    private void updatePlayerEvidence(Frame frame, long now) {
        if (!inBattle) {
            resetPlayerEvidence();
            return;
        }
        updatePlayerBarEvidence(frame, now);
        updateDragBandActivity(frame, now);
        updateHandSlotActivity(frame, now);
        cleanupSelfEvidence(now);
    }

    private void updatePlayerBarEvidence(Frame frame, long now) {
        double signal = samplePlayerBarSignal(frame);
        if (signal < 0.0) {
            return;
        }

        playerBarPeakSignal = Math.max(signal, playerBarPeakSignal * 0.999);
        if (pendingBarDropCost > 0 && now - pendingBarDropAtMs >= ANALYZE_INTERVAL_MS) {
            double reboundAllowance = Math.max(0.010, playerBarPeakSignal * 0.025);
            if (signal <= pendingBarDropSignal + reboundAllowance) {
                recordSelfEvidence(now, pendingBarDropCost, 4);
                lastBarDropAtMs = now;
            }
            pendingBarDropCost = 0;
            pendingBarDropAtMs = 0L;
            pendingBarDropSignal = 0.0;
        }

        if (previousPlayerBarSignal >= 0.0
                && pendingBarDropCost == 0
                && now - lastBarDropAtMs >= BAR_DROP_DEBOUNCE_MS
                && playerBarPeakSignal >= 0.08) {
            double drop = previousPlayerBarSignal - signal;
            double minimumDrop = Math.max(0.018, playerBarPeakSignal * 0.055);
            if (drop >= minimumDrop) {
                int estimatedCost = clamp(
                        (int) Math.round((drop / playerBarPeakSignal) * 10.0),
                        1,
                        9);
                pendingBarDropCost = estimatedCost;
                pendingBarDropAtMs = now;
                pendingBarDropSignal = signal;
            }
        }
        previousPlayerBarSignal = signal;
    }

    private double samplePlayerBarSignal(Frame frame) {
        int step = clamp(frame.width / 420, 2, 5);
        int left = (int) (frame.width * 0.04);
        int right = (int) (frame.width * 0.98);
        int top = (int) (frame.height * 0.82);
        int bottom = (int) (frame.height * 0.975);
        int sampledWidth = Math.max(1, (right - left) / step);
        double best = -1.0;

        for (int y = top; y < bottom; y += step) {
            int purpleCount = 0;
            int first = Integer.MAX_VALUE;
            int last = -1;
            int sampleIndex = 0;
            for (int x = left; x < right; x += step) {
                int r = frame.r(x, y);
                int g = frame.g(x, y);
                int b = frame.b(x, y);
                if (isBarPurple(r, g, b)) {
                    purpleCount++;
                    first = Math.min(first, sampleIndex);
                    last = Math.max(last, sampleIndex);
                }
                sampleIndex++;
            }
            int span = last >= first ? last - first + 1 : 0;
            if (span >= sampledWidth * 0.24) {
                best = Math.max(best, purpleCount / (double) sampledWidth);
            }
        }
        return best;
    }

    private void updateHandSlotActivity(Frame frame, long now) {
        for (int slot = 0; slot < 4; slot++) {
            byte[] current = sampleHandSlot(frame, slot);
            byte[] stable = stableHandSlots[slot];
            if (stable == null || stable.length != current.length) {
                stableHandSlots[slot] = current;
                handChangeStreak[slot] = 0;
                continue;
            }

            long totalDiff = 0L;
            int strongValues = 0;
            for (int i = 0; i < current.length; i++) {
                int diff = Math.abs((current[i] & 0xff) - (stable[i] & 0xff));
                totalDiff += diff;
                if (diff >= 24) {
                    strongValues++;
                }
            }
            double meanDiff = totalDiff / (double) current.length;
            boolean largeImmediateChange = meanDiff >= 23.0
                    && strongValues >= current.length * 0.20;
            boolean sustainedChange = meanDiff >= HAND_CHANGE_THRESHOLD
                    && strongValues >= current.length * 0.13;

            if (largeImmediateChange || sustainedChange) {
                handChangeStreak[slot]++;
            } else {
                handChangeStreak[slot] = Math.max(0, handChangeStreak[slot] - 1);
                if (meanDiff < 6.5) {
                    stableHandSlots[slot] = current;
                }
            }

            if ((largeImmediateChange || handChangeStreak[slot] >= 2)
                    && now - lastHandSlotEventAtMs[slot] >= 550L) {
                lastHandSlotEventAtMs[slot] = now;
                lastAnyHandChangeAtMs = now;
                int strength = now - lastDragMotionAtMs <= 850L ? 3 : 2;
                recordSelfEvidence(now, -1, strength);
                stableHandSlots[slot] = current;
                handChangeStreak[slot] = 0;
            }
        }
    }

    private byte[] sampleHandSlot(Frame frame, int slot) {
        double centerRatio = 0.20 + slot * 0.20;
        int left = (int) (frame.width * (centerRatio - 0.078));
        int right = (int) (frame.width * (centerRatio + 0.078));
        int top = (int) (frame.height * 0.785);
        int bottom = (int) (frame.height * 0.915);
        byte[] sample = new byte[HAND_SLOT_SAMPLE_W * HAND_SLOT_SAMPLE_H * 3];
        int index = 0;

        for (int sy = 0; sy < HAND_SLOT_SAMPLE_H; sy++) {
            int y = top + sy * Math.max(1, bottom - top - 1)
                    / Math.max(1, HAND_SLOT_SAMPLE_H - 1);
            for (int sx = 0; sx < HAND_SLOT_SAMPLE_W; sx++) {
                int x = left + sx * Math.max(1, right - left - 1)
                        / Math.max(1, HAND_SLOT_SAMPLE_W - 1);
                sample[index++] = (byte) frame.r(x, y);
                sample[index++] = (byte) frame.g(x, y);
                sample[index++] = (byte) frame.b(x, y);
            }
        }
        return sample;
    }

    private void updateDragBandActivity(Frame frame, long now) {
        int left = (int) (frame.width * 0.08);
        int right = (int) (frame.width * 0.92);
        int top = (int) (frame.height * 0.68);
        int bottom = (int) (frame.height * 0.80);
        byte[] current = new byte[DRAG_SAMPLE_W * DRAG_SAMPLE_H];
        int index = 0;
        for (int sy = 0; sy < DRAG_SAMPLE_H; sy++) {
            int y = top + sy * Math.max(1, bottom - top - 1)
                    / Math.max(1, DRAG_SAMPLE_H - 1);
            for (int sx = 0; sx < DRAG_SAMPLE_W; sx++) {
                int x = left + sx * Math.max(1, right - left - 1)
                        / Math.max(1, DRAG_SAMPLE_W - 1);
                current[index++] = (byte) ((frame.r(x, y) * 30
                        + frame.g(x, y) * 59
                        + frame.b(x, y) * 11) / 100);
            }
        }

        if (previousDragBandGray != null
                && previousDragBandGray.length == current.length) {
            long totalDiff = 0L;
            int strongPixels = 0;
            for (int i = 0; i < current.length; i++) {
                int diff = Math.abs((current[i] & 0xff)
                        - (previousDragBandGray[i] & 0xff));
                totalDiff += diff;
                if (diff >= 25) {
                    strongPixels++;
                }
            }
            double meanDiff = totalDiff / (double) current.length;
            if (meanDiff >= 11.0 && strongPixels >= 18) {
                lastDragMotionAtMs = now;
            }
        }
        previousDragBandGray = current;
    }

    private void recordSelfEvidence(long now, int cost, int strength) {
        for (int i = selfPlayEvidence.size() - 1; i >= 0; i--) {
            SelfPlayEvidence evidence = selfPlayEvidence.get(i);
            if (!evidence.claimed && Math.abs(now - evidence.timeMs) <= 430L) {
                evidence.timeMs = Math.max(evidence.timeMs, now);
                if (cost > 0) {
                    evidence.cost = cost;
                }
                evidence.strength = Math.min(7, evidence.strength + strength);
                return;
            }
        }
        selfPlayEvidence.add(new SelfPlayEvidence(now, cost, strength));
    }

    private void resetPlayerEvidence() {
        selfPlayEvidence.clear();
        stableHandSlots = new byte[4][];
        handChangeStreak = new int[4];
        lastHandSlotEventAtMs = new long[4];
        lastAnyHandChangeAtMs = 0L;
        previousDragBandGray = null;
        lastDragMotionAtMs = 0L;
        previousPlayerBarSignal = -1.0;
        playerBarPeakSignal = 0.0;
        pendingBarDropCost = 0;
        pendingBarDropAtMs = 0L;
        pendingBarDropSignal = 0.0;
        lastBarDropAtMs = 0L;
    }

    private static boolean isBarPurple'''
)

regex_once(
    r"    private static final class IconTrack \{.*?\n"
    r"    \}\n\n"
    r"    private static final class DigitResult",
    r'''    private static final class IconTrack {
        int x;
        int y;
        int hits;
        final long firstSeen;
        long lastSeen;
        long decisionAt;
        boolean awaitingOwnership;
        boolean processed;
        private final int[] costVotes = new int[10];
        private final int[] costObservations = new int[10];
        private final double[] bestConfidenceByCost = new double[10];

        IconTrack(IconDetection detection, long now) {
            firstSeen = now;
            x = detection.x;
            y = detection.y;
            addDetection(detection, now);
        }

        void addDetection(IconDetection detection, long now) {
            hits++;
            lastSeen = now;
            x = hits <= 1 ? detection.x : (x * 3 + detection.x) / 4;
            y = hits <= 1 ? detection.y : (y * 3 + detection.y) / 4;
            int safeCost = clamp(detection.cost, 1, 9);
            int weight = Math.max(1, (int) Math.round(detection.confidence * 6.0));
            costVotes[safeCost] += weight;
            costObservations[safeCost]++;
            bestConfidenceByCost[safeCost] = Math.max(
                    bestConfidenceByCost[safeCost],
                    detection.confidence);
        }

        int currentCost() {
            int bestCost = 1;
            int bestVotes = -1;
            for (int cost = 1; cost <= 9; cost++) {
                if (costVotes[cost] > bestVotes) {
                    bestVotes = costVotes[cost];
                    bestCost = cost;
                }
            }
            return bestCost;
        }

        double currentConfidence() {
            return bestConfidenceByCost[currentCost()];
        }

        double currentVoteShare() {
            int total = 0;
            for (int cost = 1; cost <= 9; cost++) {
                total += costVotes[cost];
            }
            return total <= 0 ? 0.0 : costVotes[currentCost()] / (double) total;
        }
    }

    private static final class SelfPlayEvidence {
        long timeMs;
        int cost;
        int strength;
        boolean claimed;

        SelfPlayEvidence(long timeMs, int cost, int strength) {
            this.timeMs = timeMs;
            this.cost = cost;
            this.strength = strength;
        }
    }

    private static final class DigitResult'''
)

SOURCE.write_text(text, encoding="utf-8")

GRADLE = Path("app/build.gradle")
gradle = GRADLE.read_text(encoding="utf-8")
old_version = "        versionCode 600\n        versionName '6.0.0-full-arena-owner-filter'"
new_version = "        versionCode 700\n        versionName '7.0.0-evidence-fusion'"
if gradle.count(old_version) != 1:
    raise RuntimeError("Expected v6 version block exactly once")
GRADLE.write_text(gradle.replace(old_version, new_version, 1), encoding="utf-8")

print("Applied v7 player-evidence fusion and OCR voting patch")
