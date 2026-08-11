# ROYALEVISION v6

ROYALEVISION is an on-device Android visual overlay for Clash Royale. Version 6 replaces the stacked v5 patch scripts with a stateful computer-vision pipeline and an explicit hidden-state model.

The app is unofficial and is not affiliated with or endorsed by Supercell.

## What v6 does

- Finds and tracks the four-card hand with the measured 1080×2400 layout only as a weak first-search prior.
- Locates the visible local Elixir rail relative to the detected hand, then tracks it through `SEARCHING → CANDIDATE → LOCKED → TEMPORARILY_LOST → REACQUIRE`.
- Reads the actual visible local fill. It does not initialize local or opponent Elixir to 5.
- Detects battles with independent hand, rail, timer, crown/tower, arena, layout-stability, and optional-audio cues.
- Keeps match detection separate from Elixir lock, so `MATCH FOUND • ELIXIR CALIBRATING` is a valid state.
- Classifies each live hand slot only against the user's eight calibrated cards using spatial colour, histogram, and gradient features with global unique assignment.
- Fuses stable hand transitions and sharp local-Elixir drops to mark local plays and suppress false opponent events.
- Requires arena motion plus a cost-badge observation before committing an opponent play. Audio can strengthen an event but cannot mutate state by itself.
- Models opponent Elixir as `best/min/max/confidence`; it is never presented as directly observed.
- Keeps exactly eight opponent deck slots, advances cycle on unknown plays, and leaves uncertain identities as `?`.
- Continues visual tracking if Android playback-audio capture is denied, unavailable, silent, or fails at runtime.

## Calibration and special forms

Deck calibration requires exactly eight base cards. Normal, Evolution, Hero, and Champion selections remain the same base identity for deck rotation. Where the bundled RoyaleAPI asset set contains exact Evolution or Hero art, that art is included in the local eight-way recognizer; otherwise the recognizer safely falls back to base art.

The current calibration rules expose one Evolution slot, one Hero/Champion slot, and one Wild slot. A card can occupy only one of the eight base-card positions.

## Architecture

The important ownership boundary is deliberate:

1. `FrameAnalyzer` produces independent observations.
2. `EventFusionEngine` groups observations into local/opponent events.
3. `GameSessionEngine` is the only component allowed to mutate `OpponentStateTracker`.
4. `AutoOverlayService` renders immutable `SessionSnapshot` data only.

This prevents a purple pixel, sound transient, hand change, or motion blob from directly changing opponent Elixir or cycle.

## Build and verification

The `royalevision-v6` GitHub Actions workflow:

1. bundles current 75px RoyaleAPI card artwork;
2. restores the existing release key so upgrades remain signature-compatible;
3. runs deterministic unit tests;
4. builds a signed release APK;
5. checks package/version, ZIP alignment, APK signatures, and bundled assets; and
6. installs and launches the APK on an Android 15 emulator.

For a local pure-Java check without Android SDK tooling, compile the core plus `tools/CoreSmokeHarness.java`. The same scenarios are represented as JUnit tests under `app/src/test`.

For footage replay diagnostics:

```bash
tools/replay_video.sh match.mp4 royalevision-replay.csv
```

The replay emits per-frame match state and HUD/rail/timer/crown/arena diagnostics. It intentionally does not claim phone-level reliability from synthetic tests; real captured matches should be replayed before changing thresholds.

## Privacy and limits

Analysis runs locally from the user-approved MediaProjection stream. No game account or network service is required at runtime.

Opponent card identity is conservative. Exact identity may remain `?` when battlefield appearance, learned audio, cost, and cycle constraints do not establish a sufficiently strong result. That is preferable to inventing a card.
