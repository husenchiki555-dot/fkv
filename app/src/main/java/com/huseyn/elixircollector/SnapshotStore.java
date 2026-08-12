package com.huseyn.elixircollector;

import android.content.Context;
import android.content.SharedPreferences;

/** In-process snapshot transport with low-rate SharedPreferences recovery checkpoints. */
public final class SnapshotStore {
    public static final String PREFS = "royalevision_v6_runtime";
    public static final String KEY_SNAPSHOT = "snapshot_json";
    public static final String KEY_CAPTURE_ACTIVE = "capture_active";
    public static final String KEY_AUDIO_AVAILABLE = "audio_available";
    public static final String KEY_LAST_ERROR = "last_error";
    public static final String KEY_OVERLAY_ACTIVE = "overlay_active";
    public static final String KEY_OVERLAY_HEARTBEAT = "overlay_heartbeat";
    public static final String KEY_CAPTURE_HEARTBEAT = "capture_heartbeat";

    private final SharedPreferences preferences;
    private static volatile SessionSnapshot memorySnapshot;
    private static volatile long lastSnapshotPersistMs;

    public SnapshotStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void publish(SessionSnapshot snapshot) {
        if (snapshot == null) return;
        memorySnapshot = snapshot;
        long now = System.currentTimeMillis();
        // Capture and overlay services share a process, so memory is the live
        // transport. Persist only a recovery checkpoint instead of writing a
        // JSON preferences file ten times per second.
        if (now - lastSnapshotPersistMs < 600L) return;
        lastSnapshotPersistMs = now;
        preferences.edit()
                .putString(KEY_SNAPSHOT, snapshot.toJson())
                .putBoolean(KEY_CAPTURE_ACTIVE, snapshot.captureActive)
                .putBoolean(KEY_AUDIO_AVAILABLE, snapshot.audioAvailable)
                .putLong(KEY_CAPTURE_HEARTBEAT, now)
                .apply();
    }

    public SessionSnapshot read() {
        SessionSnapshot snapshot = memorySnapshot;
        if (snapshot == null) {
            snapshot = SessionSnapshot.fromJson(preferences.getString(KEY_SNAPSHOT, null));
            memorySnapshot = snapshot;
        }
        if (snapshot != null && !captureActive()
                && System.currentTimeMillis() - snapshot.timeMs > 4_500L) return null;
        return snapshot;
    }

    public boolean captureActive() {
        if (!preferences.getBoolean(KEY_CAPTURE_ACTIVE, false)) return false;
        long heartbeat = preferences.getLong(KEY_CAPTURE_HEARTBEAT, 0L);
        return heartbeat == 0L || System.currentTimeMillis() - heartbeat < 4_500L;
    }

    public void setCaptureActive(boolean active) {
        preferences.edit().putBoolean(KEY_CAPTURE_ACTIVE, active)
                .putLong(KEY_CAPTURE_HEARTBEAT, active ? System.currentTimeMillis() : 0L).apply();
    }

    public void touchCapture() {
        preferences.edit().putBoolean(KEY_CAPTURE_ACTIVE, true)
                .putLong(KEY_CAPTURE_HEARTBEAT, System.currentTimeMillis()).apply();
    }

    public void clearForStart() {
        memorySnapshot = null;
        lastSnapshotPersistMs = 0L;
        preferences.edit().remove(KEY_SNAPSHOT).remove(KEY_LAST_ERROR)
                .putBoolean(KEY_CAPTURE_ACTIVE, false)
                .putBoolean(KEY_AUDIO_AVAILABLE, false)
                .putLong(KEY_CAPTURE_HEARTBEAT, 0L).apply();
    }

    public void stopped(String reason) {
        memorySnapshot = null;
        lastSnapshotPersistMs = 0L;
        preferences.edit().remove(KEY_SNAPSHOT)
                .putBoolean(KEY_CAPTURE_ACTIVE, false)
                .putBoolean(KEY_AUDIO_AVAILABLE, false)
                .putLong(KEY_CAPTURE_HEARTBEAT, 0L)
                .putString(KEY_LAST_ERROR, reason == null ? "Stopped" : reason).apply();
    }

    public void reportNonFatalError(String reason) {
        preferences.edit().putString(KEY_LAST_ERROR, reason == null ? "" : reason).apply();
    }

    public void clearErrorIf(String expected) {
        if (expected != null && expected.equals(lastError())) {
            preferences.edit().remove(KEY_LAST_ERROR).apply();
        }
    }

    public String lastError() { return preferences.getString(KEY_LAST_ERROR, ""); }

    public void setOverlayActive(boolean active) {
        preferences.edit().putBoolean(KEY_OVERLAY_ACTIVE, active)
                .putLong(KEY_OVERLAY_HEARTBEAT, active ? System.currentTimeMillis() : 0L).apply();
    }

    public void touchOverlay() {
        preferences.edit().putBoolean(KEY_OVERLAY_ACTIVE, true)
                .putLong(KEY_OVERLAY_HEARTBEAT, System.currentTimeMillis()).apply();
    }

    public boolean overlayActive() {
        if (!preferences.getBoolean(KEY_OVERLAY_ACTIVE, false)) return false;
        long heartbeat = preferences.getLong(KEY_OVERLAY_HEARTBEAT, 0L);
        return heartbeat > 0L && System.currentTimeMillis() - heartbeat < 3_000L;
    }
}
