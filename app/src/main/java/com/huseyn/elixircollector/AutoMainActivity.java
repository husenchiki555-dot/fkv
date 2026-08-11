package com.huseyn.elixircollector;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

/** Setup and explicit user-consent flow for v6 capture. */
public final class AutoMainActivity extends Activity {
    private static final int REQ_CAPTURE = 4601;
    private static final int REQ_RUNTIME = 4602;
    private TextView overlayBadge, captureBadge, deckBadge, audioBadge;
    private boolean openGameAfterPermission;
    private boolean pendingStart;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(14, 10, 20));
        getWindow().setNavigationBarColor(Color.rgb(14, 10, 20));
        buildUi();
    }

    @Override protected void onResume() { super.onResume(); updateBadges(); }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(14, 10, 20));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(20), dp(18), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));

        LinearLayout hero = box(Color.rgb(42, 24, 58), Color.rgb(140, 73, 188));
        hero.setGravity(Gravity.CENTER);
        page.addView(hero, match(12));
        TextView title = text("ROYALEVISION v6", 26, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        hero.addView(title, match(4));
        TextView sub = text("adaptive hand + rail + arena + optional sound", 13,
                Color.rgb(229, 208, 241), false);
        sub.setGravity(Gravity.CENTER);
        hero.addView(sub, match(0));

        LinearLayout facts = box(Color.rgb(29, 23, 38), Color.rgb(67, 55, 79));
        page.addView(facts, match(12));
        facts.addView(text("HOW v6 DECIDES", 14, Color.rgb(230, 197, 249), true), match(7));
        facts.addView(line("• The four-card hand layout is found and tracked adaptively."), match(4));
        facts.addView(line("• Match detection fuses hand, rail, arena, timer, crown, and stability cues."), match(4));
        facts.addView(line("• Hand transition + a sharp visible Elixir drop marks your play."), match(4));
        facts.addView(line("• Opponent Elixir is hidden, so v6 keeps best/min/max/confidence instead of pretending it is observed."), match(4));
        facts.addView(line("• Opponent identity stays “?” unless fused evidence is genuinely strong."), match(4));
        facts.addView(line("• Playback-audio failure never stops visual capture."), match(0));

        LinearLayout status = box(Color.rgb(29, 23, 38), Color.rgb(67, 55, 79));
        page.addView(status, match(12));
        status.addView(text("SETUP", 14, Color.rgb(230, 197, 249), true), match(7));
        deckBadge = badge(); status.addView(deckBadge, match(5));
        overlayBadge = badge(); status.addView(overlayBadge, match(5));
        audioBadge = badge(); status.addView(audioBadge, match(5));
        captureBadge = badge(); status.addView(captureBadge, match(0));

        Button deck = button("1 • CALIBRATE MY EXACT 8-CARD DECK", Color.rgb(94, 58, 132));
        deck.setOnClickListener(v -> startActivity(new Intent(this, DeckCalibrationActivity.class)));
        page.addView(deck, height(56, 8));
        Button overlay = button("2 • ALLOW FLOATING WINDOW", Color.rgb(102, 53, 140));
        overlay.setOnClickListener(v -> requestOverlayPermission());
        page.addView(overlay, height(54, 8));
        Button start = button("3 • START v6 + OPEN CLASH ROYALE", Color.rgb(156, 67, 205));
        start.setOnClickListener(v -> startAutomatic(true));
        page.addView(start, height(61, 8));
        Button startOnly = button("START v6 TRACKING ONLY", Color.rgb(51, 104, 163));
        startOnly.setOnClickListener(v -> startAutomatic(false));
        page.addView(startOnly, height(53, 8));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(row, height(48, 0));
        Button game = button("OPEN GAME", Color.rgb(46, 54, 72));
        game.setTextSize(12); game.setOnClickListener(v -> openGame());
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        gp.rightMargin = dp(4); row.addView(game, gp);
        Button stop = button("STOP ALL", Color.rgb(91, 42, 58));
        stop.setTextSize(12); stop.setOnClickListener(v -> stopAll());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        sp.leftMargin = dp(4); row.addView(stop, sp);

        setContentView(scroll);
        updateBadges();
    }

    private void startAutomatic(boolean openGame) {
        if (DeckCalibrationActivity.loadDeck(this).size() != 8) {
            Toast.makeText(this, "Calibrate exactly 8 cards first", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, DeckCalibrationActivity.class));
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
            Toast.makeText(this, "Enable floating-window permission, then tap Start again",
                    Toast.LENGTH_LONG).show();
            return;
        }
        openGameAfterPermission = openGame;
        ArrayList<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!permissions.isEmpty()) {
            pendingStart = true;
            requestPermissions(permissions.toArray(new String[0]), REQ_RUNTIME);
            return;
        }
        beginCapture();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_RUNTIME && pendingStart) {
            pendingStart = false;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Sound unavailable • visual tracking will continue normally",
                        Toast.LENGTH_LONG).show();
            }
            beginCapture();
        }
    }

    private void beginCapture() {
        stopService(new Intent(this, AutoCaptureService.class));
        stopService(new Intent(this, AutoOverlayService.class));
        new SnapshotStore(this).clearForStart();
        MediaProjectionManager manager = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen capture permission was not granted", Toast.LENGTH_LONG).show();
            return;
        }
        Intent capture = new Intent(this, AutoCaptureService.class);
        capture.putExtra(AutoCaptureService.EXTRA_RESULT_CODE, resultCode);
        capture.putExtra(AutoCaptureService.EXTRA_RESULT_DATA, data);
        Intent overlay = new Intent(this, AutoOverlayService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(capture);
            startForegroundService(overlay);
        } else {
            startService(capture);
            startService(overlay);
        }
        Toast.makeText(this, "RoyaleVision v6 started", Toast.LENGTH_SHORT).show();
        if (openGameAfterPermission) getWindow().getDecorView().postDelayed(this::openGame, 420);
        updateBadges();
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Floating window already enabled", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
    }

    private void stopAll() {
        stopService(new Intent(this, AutoCaptureService.class));
        stopService(new Intent(this, AutoOverlayService.class));
        new SnapshotStore(this).stopped("Stopped by user");
        Toast.makeText(this, "RoyaleVision stopped", Toast.LENGTH_SHORT).show();
        updateBadges();
    }

    private void openGame() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.supercell.clashroyale");
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=com.supercell.clashroyale")));
        } catch (ActivityNotFoundException missingStore) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=com.supercell.clashroyale")));
        }
    }

    private void updateBadges() {
        if (overlayBadge == null) return;
        boolean overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        boolean capture = new SnapshotStore(this).captureActive();
        boolean deck = DeckCalibrationActivity.loadDeck(this).size() == 8;
        boolean audio = Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        setBadge(deckBadge, deck ? "✓ EXACT 8-CARD DECK SAVED" : "! CALIBRATE EXACTLY 8 CARDS", deck);
        setBadge(overlayBadge, overlay ? "✓ FLOATING WINDOW ENABLED" : "! FLOATING WINDOW REQUIRED", overlay);
        setBadge(audioBadge, audio ? "✓ OPTIONAL SOUND PERMISSION READY" : "○ SOUND OFF • VISUAL STILL WORKS", true);
        setBadge(captureBadge, capture ? "✓ v6 CAPTURE ACTIVE" : "○ v6 CAPTURE OFF", capture);
    }

    private TextView badge() {
        TextView view = text("", 12, Color.WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(8), dp(8), dp(8), dp(8));
        return view;
    }

    private void setBadge(TextView view, String value, boolean good) {
        view.setText(value);
        view.setTextColor(good ? Color.rgb(178, 255, 198) : Color.rgb(255, 196, 204));
        view.setBackground(panel(good ? Color.rgb(30, 79, 49) : Color.rgb(84, 39, 49),
                12, good ? Color.rgb(71, 139, 89) : Color.rgb(137, 65, 78)));
    }

    private LinearLayout box(int fill, int stroke) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(15), dp(14), dp(15), dp(14));
        box.setBackground(panel(fill, 18, stroke));
        return box;
    }
    private TextView line(String value) {
        TextView view = text(value, 13, Color.rgb(224, 216, 231), false);
        view.setLineSpacing(0, 1.12f);
        return view;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }
    private Button button(String value, int color) {
        Button button = new Button(this);
        button.setText(value); button.setTextColor(Color.WHITE); button.setTextSize(14);
        button.setAllCaps(false); button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER); button.setBackground(panel(color, 16, Color.argb(75,255,255,255)));
        return button;
    }
    private GradientDrawable panel(int fill, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill); drawable.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(1), stroke);
        return drawable;
    }
    private LinearLayout.LayoutParams match(int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(bottom); return p;
    }
    private LinearLayout.LayoutParams height(int height, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(height));
        p.bottomMargin = dp(bottom); return p;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
