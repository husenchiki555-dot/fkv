package com.huseyn.elixircollector;

import android.content.Context;
import android.content.SharedPreferences;

/** SharedPreferences transport between capture and overlay services. */
public final class SnapshotStore {
    public static final String PREFS = "royalevision_v6_runtime";
    public static final String KEY_SNAPSHOT = "snapshot_json";
    public static final String KEY_CAPTURE_ACTIVE = "capture_active";
    public static final String KEY_AUDIO_AVAILABLE = "audio_available";
    public static final String KEY_LAST_ERROR = "last_error";

    private final SharedPreferences preferences;

    public SnapshotStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void publish(SessionSnapshot snapshot) {
        if (snapshot == null) return;
        preferences.edit()
                .putString(KEY_SNAPSHOT, snapshot.toJson())
                .putBoolean(KEY_CAPTURE_ACTIVE, snapshot.captureActive)
                .putBoolean(KEY_AUDIO_AVAILABLE, snapshot.audioAvailable)
                .apply();
    }

    public SessionSnapshot read() {
        return SessionSnapshot.fromJson(preferences.getString(KEY_SNAPSHOT, null));
    }

    public boolean captureActive() { return preferences.getBoolean(KEY_CAPTURE_ACTIVE, false); }

    public void setCaptureActive(boolean active) {
        preferences.edit().putBoolean(KEY_CAPTURE_ACTIVE, active).apply();
    }

    public void clearForStart() {
        preferences.edit().clear().putBoolean(KEY_CAPTURE_ACTIVE, false).apply();
    }

    public void stopped(String reason) {
        preferences.edit().putBoolean(KEY_CAPTURE_ACTIVE, false)
                .putBoolean(KEY_AUDIO_AVAILABLE, false)
                .putString(KEY_LAST_ERROR, reason == null ? "Stopped" : reason).apply();
    }

    public void reportNonFatalError(String reason) {
        preferences.edit().putString(KEY_LAST_ERROR, reason == null ? "" : reason).apply();
    }

    public String lastError() { return preferences.getString(KEY_LAST_ERROR, ""); }
}
