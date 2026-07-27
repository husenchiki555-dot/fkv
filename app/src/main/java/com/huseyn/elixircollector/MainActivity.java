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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(16, 10, 24));
        getWindow().setNavigationBarColor(Color.rgb(16, 10, 24));
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionState();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(16, 10, 24));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(20), dp(24), dp(20), dp(30));
        scroll.addView(page, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER);
        hero.setPadding(dp(18), dp(20), dp(18), dp(20));
        hero.setBackground(panel(Color.rgb(43, 24, 60), 24, Color.rgb(142, 79, 184)));
        page.addView(hero, matchWrap(dp(18)));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_elixir_collector);
        icon.setContentDescription("Elixir collector icon");
        hero.addView(icon, new LinearLayout.LayoutParams(dp(112), dp(112)));

        TextView title = text("ELIXIR COLLECTOR", 27, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        hero.addView(title, matchWrap(dp(5)));

        TextView subtitle = text("Manual floating elixir tracker", 15,
                Color.rgb(226, 207, 239), false);
        subtitle.setGravity(Gravity.CENTER);
        hero.addView(subtitle, matchWrap(0));

        LinearLayout setup = new LinearLayout(this);
        setup.setOrientation(LinearLayout.VERTICAL);
        setup.setPadding(dp(16), dp(16), dp(16), dp(16));
        setup.setBackground(panel(Color.rgb(31, 24, 43), 20, Color.rgb(78, 62, 95)));
        page.addView(setup, matchWrap(dp(14)));

        TextView setupTitle = text("QUICK SETUP", 16, Color.rgb(231, 198, 255), true);
        setup.addView(setupTitle, matchWrap(dp(10)));

        setup.addView(step("1", "Allow display over other apps"), matchWrap(dp(8)));
        setup.addView(step("2", "Start the floating tracker"), matchWrap(dp(8)));
        setup.addView(step("3", "Open Clash Royale and tap each card cost"), matchWrap(dp(12)));

        permissionBadge = text("", 14, Color.WHITE, true);
        permissionBadge.setGravity(Gravity.CENTER);
        permissionBadge.setPadding(dp(12), dp(11), dp(12), dp(11));
        setup.addView(permissionBadge, matchWrap(0));

        Button permission = button("ALLOW FLOATING WINDOW", Color.rgb(104, 54, 142));
        permission.setOnClickListener(v -> requestOverlayPermission());
        page.addView(permission, matchHeight(dp(56), dp(10)));

        startButton = button("START OVERLAY", Color.rgb(170, 73, 214));
        startButton.setOnClickListener(v -> startOverlay(false));
        page.addView(startButton, matchHeight(dp(58), dp(10)));

        Button startGame = button("START OVERLAY + OPEN GAME", Color.rgb(50, 112, 174));
        startGame.setOnClickListener(v -> startOverlay(true));
        page.addView(startGame, matchHeight(dp(56), dp(10)));

        LinearLayout smallActions = new LinearLayout(this);
        smallActions.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(smallActions, matchHeight(dp(50), dp(18)));

        Button openGame = button("OPEN GAME", Color.rgb(48, 57, 78));
        openGame.setTextSize(13);
        openGame.setOnClickListener(v -> openClashRoyale());
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(50), 1f);
        half.rightMargin = dp(6);
        smallActions.addView(openGame, half);

        Button stop = button("STOP OVERLAY", Color.rgb(92, 42, 58));
        stop.setTextSize(13);
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, OverlayService.class));
            Toast.makeText(this, "Overlay stopped", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams secondHalf = new LinearLayout.LayoutParams(0, dp(50), 1f);
        secondHalf.leftMargin = dp(6);
        smallActions.addView(stop, secondHalf);

        TextView note = text(
                "This version is fully offline. It does not read the game screen or access your Supercell account. "
                        + "The counter changes only when you tap a card cost.",
                12, Color.rgb(161, 148, 177), false);
        note.setGravity(Gravity.CENTER);
        note.setLineSpacing(0, 1.2f);
        page.addView(note, matchWrap(0));

        setContentView(scroll);
        updatePermissionState();
    }

    private LinearLayout step(String number, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = text(number, 14, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(panel(Color.rgb(121, 65, 159), 20, Color.TRANSPARENT));
        row.addView(badge, new LinearLayout.LayoutParams(dp(32), dp(32)));

        TextView body = text(label, 14, Color.rgb(226, 219, 234), false);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bodyParams.leftMargin = dp(10);
        row.addView(body, bodyParams);
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
            Toast.makeText(this, "Floating tracker started", Toast.LENGTH_SHORT).show();
            if (openGameAfter) {
                getWindow().getDecorView().postDelayed(this::openClashRoyale, 350L);
            }
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
        if (permissionBadge == null) {
            return;
        }
        boolean granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        permissionBadge.setText(granted ? "✓ FLOATING WINDOW ENABLED" : "! FLOATING WINDOW REQUIRED");
        permissionBadge.setTextColor(granted
                ? Color.rgb(178, 255, 198)
                : Color.rgb(255, 196, 204));
        permissionBadge.setBackground(panel(
                granted ? Color.rgb(31, 83, 51) : Color.rgb(89, 40, 51),
                14,
                granted ? Color.rgb(76, 148, 94) : Color.rgb(145, 69, 82)));
        if (startButton != null) {
            startButton.setAlpha(granted ? 1f : 0.72f);
        }
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private Button button(String value, int color) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(panel(color, 17, Color.argb(80, 255, 255, 255)));
        return button;
    }

    private GradientDrawable panel(int fill, int radiusDp, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (stroke != Color.TRANSPARENT) {
            drawable.setStroke(dp(1), stroke);
        }
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = bottomMargin;
        return params;
    }

    private LinearLayout.LayoutParams matchHeight(int height, int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height);
        params.bottomMargin = bottomMargin;
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
