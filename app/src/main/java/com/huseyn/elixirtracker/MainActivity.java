package com.huseyn.elixirtracker;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_OVERLAY = 1001;
    private static final int REQUEST_NOTIFICATIONS = 1002;

    private TextView permissionStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(24, 18, 37));
        getWindow().setNavigationBarColor(Color.rgb(24, 18, 37));
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(24, 18, 37));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(32), dp(22), dp(32));
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("ELIXIR OVERLAY");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        content.addView(title, matchWrap(dp(8)));

        TextView subtitle = new TextView(this);
        subtitle.setText("A lightweight manual live counter for Clash Royale");
        subtitle.setTextColor(Color.rgb(202, 190, 224));
        subtitle.setTextSize(16);
        subtitle.setGravity(Gravity.CENTER);
        content.addView(subtitle, matchWrap(dp(28)));

        LinearLayout infoCard = new LinearLayout(this);
        infoCard.setOrientation(LinearLayout.VERTICAL);
        infoCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        infoCard.setBackground(roundRect(Color.rgb(42, 32, 60), 18));
        content.addView(infoCard, matchWrap(dp(18)));

        TextView how = new TextView(this);
        how.setText("How it works");
        how.setTextColor(Color.WHITE);
        how.setTextSize(19);
        how.setTypeface(Typeface.DEFAULT_BOLD);
        infoCard.addView(how, matchWrap(dp(10)));

        TextView details = new TextView(this);
        details.setText("1. Grant display-over-other-apps permission.\n"
                + "2. Start the overlay and open Clash Royale.\n"
                + "3. Tap the cost of every card your opponent plays.\n"
                + "4. Switch between 1×, 2× and 3× regeneration as the match changes.");
        details.setTextColor(Color.rgb(225, 218, 238));
        details.setTextSize(15);
        details.setLineSpacing(0, 1.25f);
        infoCard.addView(details, matchWrap(0));

        permissionStatus = new TextView(this);
        permissionStatus.setTextSize(15);
        permissionStatus.setTypeface(Typeface.DEFAULT_BOLD);
        permissionStatus.setGravity(Gravity.CENTER);
        permissionStatus.setPadding(dp(14), dp(12), dp(14), dp(12));
        content.addView(permissionStatus, matchWrap(dp(12)));

        Button grant = actionButton("Grant overlay permission", Color.rgb(126, 78, 194));
        grant.setOnClickListener(v -> requestOverlayPermission());
        content.addView(grant, matchHeight(dp(54), dp(12)));

        Button start = actionButton("Start live overlay", Color.rgb(92, 55, 150));
        start.setOnClickListener(v -> startOverlay());
        content.addView(start, matchHeight(dp(54), dp(12)));

        Button openGame = actionButton("Open Clash Royale", Color.rgb(56, 116, 174));
        openGame.setOnClickListener(v -> openClashRoyale());
        content.addView(openGame, matchHeight(dp(54), dp(12)));

        Button stop = actionButton("Stop overlay", Color.rgb(111, 48, 68));
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, OverlayService.class));
            Toast.makeText(this, "Overlay stopped", Toast.LENGTH_SHORT).show();
        });
        content.addView(stop, matchHeight(dp(54), dp(24)));

        TextView disclaimer = new TextView(this);
        disclaimer.setText("Manual counter only. It does not read the game screen, access your Supercell account, or connect to an external server. Not affiliated with Supercell.");
        disclaimer.setTextColor(Color.rgb(164, 153, 184));
        disclaimer.setTextSize(12);
        disclaimer.setGravity(Gravity.CENTER);
        content.addView(disclaimer, matchWrap(0));

        setContentView(scroll);
        updatePermissionStatus();
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission already granted", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivityForResult(intent, REQUEST_OVERLAY);
    }

    private void startOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant overlay permission first", Toast.LENGTH_LONG).show();
            requestOverlayPermission();
            return;
        }

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }

        Intent serviceIntent = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Toast.makeText(this, "Live overlay started", Toast.LENGTH_SHORT).show();
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

    private void updatePermissionStatus() {
        if (permissionStatus == null) {
            return;
        }
        boolean granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        permissionStatus.setText(granted ? "Overlay permission: GRANTED" : "Overlay permission: REQUIRED");
        permissionStatus.setTextColor(granted ? Color.rgb(153, 239, 178) : Color.rgb(255, 184, 184));
        permissionStatus.setBackground(roundRect(
                granted ? Color.rgb(32, 83, 55) : Color.rgb(91, 43, 53), 14));
    }

    private Button actionButton(String text, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackground(roundRect(color, 16));
        return button;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
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
