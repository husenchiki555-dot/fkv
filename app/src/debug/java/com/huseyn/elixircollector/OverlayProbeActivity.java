package com.huseyn.elixircollector;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/** Debug-only Android integration probe used by the API 35 CI emulator. */
public final class OverlayProbeActivity extends Activity {
    private static final String TAG = "RoyaleVisionProbe";
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        SnapshotStore store = new SnapshotStore(this);
        store.setOverlayActive(false);
        try {
            Intent overlay = new Intent(this, AutoOverlayService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(overlay);
            else startService(overlay);
            poll(store, SystemClock.elapsedRealtime() + 5_000L);
        } catch (RuntimeException error) {
            Log.e(TAG, "OVERLAY_FAILED " + error.getClass().getSimpleName());
            finish();
        }
    }

    private void poll(SnapshotStore store, long deadline) {
        if (store.overlayActive()) {
            Log.i(TAG, "OVERLAY_ACTIVE");
            finish();
        } else if (SystemClock.elapsedRealtime() >= deadline) {
            Log.e(TAG, "OVERLAY_FAILED " + store.lastError());
            finish();
        } else {
            handler.postDelayed(() -> poll(store, deadline), 150L);
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
