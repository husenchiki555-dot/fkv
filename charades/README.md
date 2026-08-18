# Party Charades (offline Android)

Offline Android charades game with 50 built-in topics and 10,000 audited cards/prompts, tilt controls, custom timers up to 10:00, Easy/Normal/Hard/Mixed difficulty, age/theme filters, search, custom comma/newline-separated decks, same-phone team play, and local-network room-code scoring on the same Wi-Fi/hotspot.

## Build notes
The APK now loads a fixed card database at runtime. Build validation generates that database from reviewed topic-specific phrase rules, verifies 50 topics, 200 unique cards per topic, difficulty counts, duplicates, malformed placeholders, and known nonsense patterns before compilation.

Build marker: final clean-vocabulary validation.
