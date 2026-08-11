package com.huseyn.elixircollector;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TextView permissionBadge;
    private Button startButton;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(14, 10, 20));
        getWindow().setNavigationBarColor(Color.rgb(14, 10, 20));
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        updatePermissionState();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(14, 10, 20));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(20), dp(22), dp(20), dp(30));
        scroll.addView(page, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER);
        hero.setPadding(dp(18), dp(18), dp(18), dp(18));
        hero.setBackground(panel(Color.rgb(41, 24, 57), 24, Color.rgb(139, 73, 187)));
        page.addView(hero, matchWrap(dp(14)));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_elixir_collector);
        icon.setContentDescription("RoyaleVision icon");
        hero.addView(icon, new LinearLayout.LayoutParams(dp(96), dp(96)));

        TextView title = text("ROYALEVISION MANUAL", 25, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        hero.addView(title, matchWrap(dp(4)));

        TextView subtitle = text("AI-less • offline opponent tracker", 14,
                Color.rgb(226, 207, 239), false);
        subtitle.setGravity(Gravity.CENTER);
        hero.addView(subtitle, matchWrap(0));

        LinearLayout features = section();
        page.addView(features, matchWrap(dp(12)));
        features.addView(sectionTitle("WHAT THIS BUILD DOES"), matchWrap(dp(8)));
        features.addView(line("• Tap the opponent's actual card in a floating card picker"), matchWrap(dp(5)));
        features.addView(line("• Calculates opponent Elixir from an immutable timed event ledger"), matchWrap(dp(5)));
        features.addView(line("• Learns the 8-card deck and shows card-cycle return distance"), matchWrap(dp(5)));
        features.addView(line("• Handles Mirror cost, Spirit Empress 3/6 forms, abilities and resource gains"), matchWrap(dp(5)));
        features.addView(line("• Automatic 1× → 2× → 3× standard-match regeneration"), matchWrap(dp(5)));
        features.addView(line("• Undo + timer sync + persistent state if Android restarts the service"), matchWrap(0));

        LinearLayout setup = section();
        page.addView(setup, matchWrap(dp(12)));
        setup.addView(sectionTitle("QUICK SETUP"), matchWrap(dp(8)));
        setup.addView(step("1", "Allow display over other apps"), matchWrap(dp(7)));
        setup.addView(step("2", "Start the floating tracker and open Clash Royale"), matchWrap(dp(7)));
        setup.addView(step("3", "Tap START MATCH when the battle begins"), matchWrap(dp(7)));
        setup.addView(step("4", "Whenever the opponent commits a card: CARDS → cost → card"), matchWrap(dp(11)));

        permissionBadge = text("", 13, Color.WHITE, true);
        permissionBadge.setGravity(Gravity.CENTER);
        permissionBadge.setPadding(dp(12), dp(10), dp(12), dp(10));
        setup.addView(permissionBadge, matchWrap(0));

        Button permission = button("ALLOW FLOATING WINDOW", Color.rgb(99, 52, 136));
        permission.setOnClickListener(v -> requestOverlayPermission());
        page.addView(permission, matchHeight(dp(55), dp(9)));

        startButton = button("START OVERLAY", Color.rgb(160, 70, 207));
        startButton.setOnClickListener(v -> startOverlay(false));
        page.addView(startButton, matchHeight(dp(58), dp(9)));

        Button startGame = button("START OVERLAY + OPEN CLASH ROYALE", Color.rgb(48, 104, 165));
        startGame.setOnClickListener(v -> startOverlay(true));
        page.addView(startGame, matchHeight(dp(56), dp(9)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(actions, matchHeight(dp(50), dp(15)));

        Button openGame = button("OPEN GAME", Color.rgb(46, 54, 72));
        openGame.setTextSize(13);
        openGame.setOnClickListener(v -> openClashRoyale());
        LinearLayout.LayoutParams half1 = new LinearLayout.LayoutParams(0, dp(50), 1f);
        half1.rightMargin = dp(5);
        actions.addView(openGame, half1);

        Button stop = button("STOP OVERLAY", Color.rgb(91, 42, 58));
        stop.setTextSize(13);
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, OverlayService.class));
            Toast.makeText(this, "Overlay stopped", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(50), 1f);
        half2.leftMargin = dp(5);
        actions.addView(stop, half2);

        LinearLayout noteBox = section();
        page.addView(noteBox, matchWrap(0));
        noteBox.addView(sectionTitle("IMPORTANT"), matchWrap(dp(7)));
        TextView note = text(
                "This is the AI-less build. It does not record the screen, inspect game memory, use an API, " +
                "or identify cards automatically. Accuracy depends on you tapping the opponent's real card/ability events. " +
                "Standard Elixir timing is modeled at 2.8 seconds per Elixir with the current official 1×/2×/3× phase schedule. " +
                "Special modes can use different rules; use MODE overrides or do not rely on the standard cycle model there.",
                12, Color.rgb(183, 168, 194), false);
        note.setLineSpacing(0, 1.18f);
        noteBox.addView(note, matchWrap(0));

        setContentView(scroll);
        updatePermissionState();
    }

    private LinearLayout section() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(15), dp(14), dp(15), dp(14));
        box.setBackground(panel(Color.rgb(29, 23, 38), 18, Color.rgb(68, 56, 80)));
        return box;
    }

    private TextView sectionTitle(String value) {
        return text(value, 14, Color.rgb(227, 195, 248), true);
    }

    private TextView line(String value) {
        TextView t = text(value, 13, Color.rgb(223, 215, 230), false);
        t.setLineSpacing(0, 1.12f);
        return t;
    }

    private LinearLayout step(String number, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = text(number, 13, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(panel(Color.rgb(116, 62, 153), 18, Color.TRANSPARENT));
        row.addView(badge, new LinearLayout.LayoutParams(dp(30), dp(30)));

        TextView body = text(label, 13, Color.rgb(225, 217, 231), false);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bp.leftMargin = dp(9);
        row.addView(body, bp);
        return row;
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Floating-window permission is already enabled", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void startOverlay(boolean openGameAfter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Enable the floating-window permission first", Toast.LENGTH_LONG).show();
            requestOverlayPermission();
            return;
        }
        Intent service = new Intent(this, OverlayService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
            else startService(service);
            Toast.makeText(this, "RoyaleVision overlay started", Toast.LENGTH_SHORT).show();
            if (openGameAfter) getWindow().getDecorView().postDelayed(this::openClashRoyale, 350L);
        } catch (RuntimeException error) {
            Toast.makeText(this, "Could not start overlay: " + error.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void openClashRoyale() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.supercell.clashroyale");
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=com.supercell.clashroyale")));
        } catch (ActivityNotFoundException ignored) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=com.supercell.clashroyale")));
        }
    }

    private void updatePermissionState() {
        if (permissionBadge == null) return;
        boolean granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        permissionBadge.setText(granted ? "✓ FLOATING WINDOW ENABLED" : "! FLOATING WINDOW REQUIRED");
        permissionBadge.setTextColor(granted ? Color.rgb(176, 255, 197) : Color.rgb(255, 190, 200));
        permissionBadge.setBackground(panel(
                granted ? Color.rgb(29, 78, 48) : Color.rgb(84, 39, 49),
                13,
                granted ? Color.rgb(70, 136, 87) : Color.rgb(137, 65, 78)));
        if (startButton != null) startButton.setAlpha(granted ? 1f : 0.72f);
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(sizeSp); view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private Button button(String value, int color) {
        Button button = new Button(this);
        button.setText(value); button.setTextColor(Color.WHITE); button.setTextSize(14);
        button.setAllCaps(false); button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER); button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackground(panel(color, 16, Color.argb(75, 255, 255, 255)));
        return button;
    }

    private GradientDrawable panel(int fill, int radiusDp, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill); d.setCornerRadius(dp(radiusDp));
        if (stroke != Color.TRANSPARENT) d.setStroke(dp(1), stroke);
        return d;
    }

    private LinearLayout.LayoutParams matchWrap(int bottomMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = bottomMargin; return p;
    }

    private LinearLayout.LayoutParams matchHeight(int height, int bottomMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height);
        p.bottomMargin = bottomMargin; return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
