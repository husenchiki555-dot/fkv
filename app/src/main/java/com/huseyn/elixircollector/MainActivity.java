package com.huseyn.elixircollector;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_LIVE_CAPTURE = 4103;

    private TextView permissionBadge;
    private Button liveButton;
    private boolean openGameAfterCapture;

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
        hero.setPadding(dp(18), dp(18), dp(18), dp(18));
        hero.setBackground(panel(Color.rgb(43, 24, 60), 24, Color.rgb(142, 79, 184)));
        page.addView(hero, matchWrap(dp(16)));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_elixir_collector);
        icon.setContentDescription("Elixir collector icon");
        hero.addView(icon, new LinearLayout.LayoutParams(dp(96), dp(96)));

        TextView title = text("ELIXIR COLLECTOR", 26, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        hero.addView(title, matchWrap(dp(3)));

        TextView subtitle = text("Tiny live practice counter", 15,
                Color.rgb(226, 207, 239), false);
        subtitle.setGravity(Gravity.CENTER);
        hero.addView(subtitle, matchWrap(0));

        LinearLayout liveCard = new LinearLayout(this);
        liveCard.setOrientation(LinearLayout.VERTICAL);
        liveCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        liveCard.setBackground(panel(Color.rgb(31, 24, 43), 20, Color.rgb(78, 62, 95)));
        page.addView(liveCard, matchWrap(dp(12)));

        TextView liveTitle = text("LIVE PRACTICE MODE", 17,
                Color.rgb(232, 193, 255), true);
        liveCard.addView(liveTitle, matchWrap(dp(8)));

        TextView liveInfo = text(
                "Android asks for screen-capture permission each time. The app watches the opponent side and shows only a small movable number. When it sees an unknown deployment, tap the number once and label the card cost. Similar visual patterns can then be counted automatically.",
                13, Color.rgb(222, 214, 231), false);
        liveInfo.setLineSpacing(0, 1.18f);
        liveCard.addView(liveInfo, matchWrap(dp(12)));

        permissionBadge = text("", 14, Color.WHITE, true);
        permissionBadge.setGravity(Gravity.CENTER);
        permissionBadge.setPadding(dp(12), dp(11), dp(12), dp(11));
        liveCard.addView(permissionBadge, matchWrap(0));

        Button permission = button("ALLOW FLOATING WINDOW", Color.rgb(104, 54, 142));
        permission.setOnClickListener(v -> requestOverlayPermission());
        page.addView(permission, matchHeight(dp(54), dp(9)));

        liveButton = button("START LIVE PRACTICE + OPEN GAME", Color.rgb(173, 73, 216));
        liveButton.setOnClickListener(v -> requestLiveCapture(true));
        page.addView(liveButton, matchHeight(dp(60), dp(9)));

        Button liveOnly = button("START LIVE PRACTICE", Color.rgb(115, 58, 157));
        liveOnly.setOnClickListener(v -> requestLiveCapture(false));
        page.addView(liveOnly, matchHeight(dp(54), dp(9)));

        Button manual = button("MANUAL MODE", Color.rgb(50, 107, 162));
        manual.setOnClickListener(v -> startManualOverlay());
        page.addView(manual, matchHeight(dp(52), dp(9)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(actions, matchHeight(dp(48), dp(15)));

        Button stop = button("STOP EVERYTHING", Color.rgb(105, 42, 59));
        stop.setTextSize(13);
        stop.setOnClickListener(v -> stopEverything());
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(48), 1f);
        half.rightMargin = dp(5);
        actions.addView(stop, half);

        Button forget = button("FORGET LEARNING", Color.rgb(91, 68, 45));
        forget.setTextSize(13);
        forget.setOnClickListener(v -> {
            getSharedPreferences(LiveCaptureService.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply();
            Toast.makeText(this, "Learned deployment visuals cleared", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        half2.leftMargin = dp(5);
        actions.addView(forget, half2);

        TextView warning = text(
                "Experimental friendly-practice tool. Visual matching will not recognize every card reliably and may need corrections. It does not access your account, modify Clash Royale, or automate gameplay. Unsupported third-party software can still conflict with Supercell's fair-play rules, so do not use it in ranked or competitive matches.",
                12, Color.rgb(171, 155, 185), false);
        warning.setGravity(Gravity.CENTER);
        warning.setLineSpacing(0, 1.18f);
        page.addView(warning, matchWrap(0));

        setContentView(scroll);
        updatePermissionState();
    }

    private void requestLiveCapture(boolean openGame) {
        if (!hasOverlayPermission()) {
            Toast.makeText(this, "Enable the floating-window permission first",
                    Toast.LENGTH_LONG).show();
            requestOverlayPermission();
            return;
        }

        stopService(new Intent(this, OverlayService.class));
        stopService(new Intent(this, LiveCaptureService.class));
        openGameAfterCapture = openGame;

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_LIVE_CAPTURE);
        } catch (RuntimeException error) {
            Toast.makeText(this,
                    "Could not open screen-capture permission: "
                            + error.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_LIVE_CAPTURE) {
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Live screen analysis was cancelled", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent service = new Intent(this, LiveCaptureService.class);
        service.putExtra(LiveCaptureService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(LiveCaptureService.EXTRA_RESULT_DATA, data);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
            Toast.makeText(this, "Live practice analyzer started", Toast.LENGTH_SHORT).show();
            if (openGameAfterCapture) {
                getWindow().getDecorView().postDelayed(this::openClashRoyale, 450L);
            }
        } catch (RuntimeException error) {
            Toast.makeText(this,
                    "Could not start live mode: " + error.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void startManualOverlay() {
        if (!hasOverlayPermission()) {
            Toast.makeText(this, "Enable the floating-window permission first",
                    Toast.LENGTH_LONG).show();
            requestOverlayPermission();
            return;
        }
        stopService(new Intent(this, LiveCaptureService.class));
        Intent service = new Intent(this, OverlayService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
            Toast.makeText(this, "Manual overlay started", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException error) {
            Toast.makeText(this,
                    "Could not start manual overlay: " + error.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void stopEverything() {
        stopService(new Intent(this, OverlayService.class));
        stopService(new Intent(this, LiveCaptureService.class));
        Toast.makeText(this, "All Elixir Collector services stopped", Toast.LENGTH_SHORT).show();
    }

    private void requestOverlayPermission() {
        if (hasOverlayPermission()) {
            Toast.makeText(this, "Floating-window permission is already enabled",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private boolean hasOverlayPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(this);
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
        boolean granted = hasOverlayPermission();
        permissionBadge.setText(granted
                ? "✓ FLOATING WINDOW ENABLED"
                : "! FLOATING WINDOW REQUIRED");
        permissionBadge.setTextColor(granted
                ? Color.rgb(178, 255, 198)
                : Color.rgb(255, 196, 204));
        permissionBadge.setBackground(panel(
                granted ? Color.rgb(31, 83, 51) : Color.rgb(89, 40, 51),
                14,
                granted ? Color.rgb(76, 148, 94) : Color.rgb(145, 69, 82)));
        if (liveButton != null) {
            liveButton.setAlpha(granted ? 1f : 0.7f);
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
