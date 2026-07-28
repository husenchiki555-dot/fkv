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

public class IconMainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 4204;

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

        TextView subtitle = text("Deployment-icon vision v4", 15,
                Color.rgb(226, 207, 239), false);
        subtitle.setGravity(Gravity.CENTER);
        hero.addView(subtitle, matchWrap(0));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(panel(Color.rgb(31, 24, 43), 20, Color.rgb(78, 62, 95)));
        page.addView(card, matchWrap(dp(12)));

        TextView cardTitle = text("LIVE FRIENDLY-PRACTICE MODE", 17,
                Color.rgb(232, 193, 255), true);
        card.addView(cardTitle, matchWrap(dp(8)));

        TextView info = text(
                "The app waits for the battle interface, resets automatically, then scans the full arena for the purple deployment-cost badge and reads its white digit. This shared badge is more reliable than trying to identify every troop skin or evolution separately.",
                13, Color.rgb(222, 214, 231), false);
        info.setLineSpacing(0, 1.18f);
        card.addView(info, matchWrap(dp(10)));

        TextView rules = text(
                "Standard 1v1 timing is automatic: 1× for two minutes, 2× for the next two minutes, then 3×. The default actionable-start estimate is 7.5; expand the bubble to switch it to the literal 5.0 starting value or override special modes manually.",
                12, Color.rgb(186, 172, 200), false);
        rules.setLineSpacing(0, 1.16f);
        card.addView(rules, matchWrap(dp(12)));

        permissionBadge = text("", 14, Color.WHITE, true);
        permissionBadge.setGravity(Gravity.CENTER);
        permissionBadge.setPadding(dp(12), dp(11), dp(12), dp(11));
        card.addView(permissionBadge, matchWrap(0));

        Button permission = button("ALLOW FLOATING WINDOW", Color.rgb(104, 54, 142));
        permission.setOnClickListener(v -> requestOverlayPermission());
        page.addView(permission, matchHeight(dp(54), dp(9)));

        liveButton = button("START ICON VISION + OPEN GAME", Color.rgb(173, 73, 216));
        liveButton.setOnClickListener(v -> requestCapture(true));
        page.addView(liveButton, matchHeight(dp(60), dp(9)));

        Button liveOnly = button("START ICON VISION", Color.rgb(115, 58, 157));
        liveOnly.setOnClickListener(v -> requestCapture(false));
        page.addView(liveOnly, matchHeight(dp(54), dp(9)));

        Button manual = button("MANUAL FALLBACK", Color.rgb(50, 107, 162));
        manual.setOnClickListener(v -> startManualOverlay());
        page.addView(manual, matchHeight(dp(52), dp(9)));

        Button stop = button("STOP EVERYTHING", Color.rgb(105, 42, 59));
        stop.setOnClickListener(v -> stopEverything());
        page.addView(stop, matchHeight(dp(50), dp(15)));

        TextView note = text(
                "The icon reader can still miss very brief badges, unusual aspect ratios, Hero/Champion ability spending, Elixir Collector production, Elixir Golem refunds, 2v2 regeneration, and special-event rules. Use the tiny bubble's +1, −1, manual-cost and speed controls to correct those cases.",
                12, Color.rgb(171, 155, 185), false);
        note.setGravity(Gravity.CENTER);
        note.setLineSpacing(0, 1.18f);
        page.addView(note, matchWrap(0));

        setContentView(scroll);
        updatePermissionState();
    }

    private void requestCapture(boolean openGame) {
        if (!hasOverlayPermission()) {
            Toast.makeText(this, "Enable the floating-window permission first",
                    Toast.LENGTH_LONG).show();
            requestOverlayPermission();
            return;
        }

        stopEverythingSilently();
        openGameAfterCapture = openGame;
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
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
        if (requestCode != REQUEST_CAPTURE) {
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Live screen analysis was cancelled", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent service = new Intent(this, IconCaptureService.class);
        service.putExtra(IconCaptureService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(IconCaptureService.EXTRA_RESULT_DATA, data);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
            Toast.makeText(this, "Icon vision started", Toast.LENGTH_SHORT).show();
            if (openGameAfterCapture) {
                getWindow().getDecorView().postDelayed(this::openClashRoyale, 450L);
            }
        } catch (RuntimeException error) {
            Toast.makeText(this,
                    "Could not start icon vision: " + error.getClass().getSimpleName(),
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
        stopEverythingSilently();
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
        stopEverythingSilently();
        Toast.makeText(this, "All Elixir Collector services stopped", Toast.LENGTH_SHORT).show();
    }

    private void stopEverythingSilently() {
        stopService(new Intent(this, OverlayService.class));
        stopService(new Intent(this, LiveCaptureService.class));
        stopService(new Intent(this, IconCaptureService.class));
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
