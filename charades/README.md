# Party Charades (offline Android)

Offline Android charades game with 50 built-in topics and 10,000 generated cards/prompts, tilt controls, custom timers up to 10:00, Easy/Normal/Hard/Mixed difficulty, age/theme filters, search, custom comma/newline-separated decks, same-phone team play, and local-network room-code scoring on the same Wi-Fi/hotspot.

## Build notes
The large Java/content sources are stored losslessly under `embedded/` as gzip+base64 text and unpacked by the GitHub Actions build before compilation. The APK is debug-signed for sideload testing using the dedicated Party Charades test key stored on this build branch, so later test builds can use the same signing identity for in-place updates.

Build marker: independent vocabulary bundle diagnostics.
