#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: $0 <match-video> [output.csv]" >&2
  exit 2
fi

rv_video=$1
rv_output=${2:-royalevision-replay.csv}
if [[ ! -f "$rv_video" ]]; then
  echo "Video not found: $rv_video" >&2
  exit 2
fi
command -v ffmpeg >/dev/null || { echo "ffmpeg is required" >&2; exit 2; }
command -v java >/dev/null || { echo "Java 17 is required" >&2; exit 2; }

rv_repo=$(cd "$(dirname "$0")/.." && pwd)
rv_tmp=$(mktemp -d /tmp/royalevision-replay.XXXXXX)
mkdir -p "$rv_tmp/frames" "$rv_tmp/classes"

ffmpeg -hide_banner -loglevel error -i "$rv_video" -vf fps=10 "$rv_tmp/frames/frame-%08d.png"
java -m jdk.compiler/com.sun.tools.javac.Main -d "$rv_tmp/classes" \
  "$rv_repo/app/src/main/java/com/huseyn/elixircollector/PixelFrame.java" \
  "$rv_repo/app/src/main/java/com/huseyn/elixircollector/FrameRect.java" \
  "$rv_repo/app/src/main/java/com/huseyn/elixircollector/HudLayoutTracker.java" \
  "$rv_repo/app/src/main/java/com/huseyn/elixircollector/ElixirBarTracker.java" \
  "$rv_repo/app/src/main/java/com/huseyn/elixircollector/BattleCueDetector.java" \
  "$rv_repo/app/src/main/java/com/huseyn/elixircollector/BattleStateMachine.java" \
  "$rv_repo/app/src/main/java/com/huseyn/elixircollector/ArenaMotionDetector.java" \
  "$rv_repo/tools/ReplayAnalyzer.java"
java -cp "$rv_tmp/classes" com.huseyn.elixircollector.ReplayAnalyzer "$rv_tmp/frames" 10 > "$rv_output"
echo "Replay diagnostics: $rv_output"
