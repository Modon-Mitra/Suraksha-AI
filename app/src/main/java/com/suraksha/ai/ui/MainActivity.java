package com.suraksha.ai.ui;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import android.content.pm.PackageManager;

import com.suraksha.ai.R;
import com.suraksha.ai.service.LocationHeartbeatWorker;
import com.suraksha.ai.service.MonitoringService;
import com.suraksha.ai.utils.BatteryOptimizationHelper;
import com.suraksha.ai.utils.FirebaseLocationHelper;
import com.suraksha.ai.utils.FirebaseTest;
import com.suraksha.ai.utils.PrefsManager;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQ_PERMS = 100;
    private static final int REQ_BACKGROUND_LOCATION = 101;

    // Panel state
    private boolean isPanelOpen = false;
    private static final int ANIM_DURATION_MS = 320;  // slide animation length

    // Views
    private View navPanel;
    private View scrimOverlay;
    private int currentDestinationId = R.id.homeFragment;

    // Nav controller
    private NavController navController;

    // ─────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        FirebaseTest.test();
        setupNavigation();

        boolean locationAlreadyGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;

        if (locationAlreadyGranted) {
            requestBackgroundLocation();
        } else {
            requestNeededPermissions();
        }
    }

    // ─────────────────────────────────────────────────
    // NAVIGATION SETUP (replaces BottomNavigationView)
    // ─────────────────────────────────────────────────

    private void setupNavigation() {
        NavHostFragment navHost =
                (NavHostFragment) getSupportFragmentManager()
                        .findFragmentById(R.id.nav_host_fragment);

        if (navHost == null) return;
        navController = navHost.getNavController();

        navPanel     = findViewById(R.id.nav_panel);
        scrimOverlay = findViewById(R.id.scrim_overlay);

        // Scrim tap → close panel
        scrimOverlay.setOnClickListener(v -> closeNavPanel());
        View floatingBtn = findViewById(R.id.floating_shield_btn);
        if (floatingBtn != null) {
            floatingBtn.setOnClickListener(v -> toggleNavPanel());
        }

        // Wire up each nav item
        setupNavItem(R.id.nav_item_home,     R.id.homeFragment);
        setupNavItem(R.id.nav_item_tracking, R.id.trackingFragment);
        setupNavItem(R.id.nav_item_friends,  R.id.friendsFragment);
        setupNavItem(R.id.nav_item_settings, R.id.settingsFragment);

        // Highlight the default (Home) item
        updateActiveNavItem(R.id.homeFragment);
    }

    private void setupNavItem(int viewId, int destId) {
        View item = findViewById(viewId);
        if (item == null) return;
        item.setOnClickListener(v -> {
            navigateTo(destId);
            closeNavPanel();
        });
    }

    private void navigateTo(int destId) {
        if (destId == currentDestinationId) return;
        currentDestinationId = destId;
        navController.navigate(destId);
        updateActiveNavItem(destId);
    }

    /**
     * Updates icon tint + label colour to mark the active destination.
     * Called after every navigation action.
     */
    private void updateActiveNavItem(int activeDestId) {
        int[][] items = {
                {R.id.nav_item_home,     R.id.nav_icon_home,     R.id.nav_label_home,     R.id.homeFragment},
                {R.id.nav_item_tracking, R.id.nav_icon_tracking, R.id.nav_label_tracking, R.id.trackingFragment},
                {R.id.nav_item_friends,  R.id.nav_icon_friends,  R.id.nav_label_friends,  R.id.friendsFragment},
                {R.id.nav_item_settings, R.id.nav_icon_settings, R.id.nav_label_settings, R.id.settingsFragment},
        };

        int activeColor   = getColor(R.color.brand_blue);
        int inactiveColor = getColor(R.color.text_secondary);

        for (int[] row : items) {
            boolean isActive = (row[3] == activeDestId);
            int color = isActive ? activeColor : inactiveColor;

            ImageView icon  = findViewById(row[1]);
            TextView  label = findViewById(row[2]);

            if (icon  != null) icon.setColorFilter(color);
            if (label != null) {
                label.setTextColor(color);
                label.setTypeface(null, isActive
                        ? android.graphics.Typeface.BOLD
                        : android.graphics.Typeface.NORMAL);
            }
        }

        // Hide top bar on Home, show on others
        View globalTopBar = findViewById(R.id.global_top_bar);
        if (globalTopBar != null) {
            globalTopBar.setVisibility(activeDestId == R.id.homeFragment ? View.GONE : View.VISIBLE);
        }
    }

    // ─────────────────────────────────────────────────
    // PANEL OPEN / CLOSE  (called from HomeFragment)
    // ─────────────────────────────────────────────────

    /**
     * Public entry-point called by the shield ImageView in HomeFragment's top-bar.
     * Toggles the panel open/closed with a smooth spring-like slide.
     */
    public void toggleNavPanel() {
        if (isPanelOpen) {
            closeNavPanel();
        } else {
            openNavPanel();
        }
    }

    public void openNavPanel() {
        if (isPanelOpen) return;
        isPanelOpen = true;

        // Make scrim visible before animation begins
        scrimOverlay.setVisibility(View.VISIBLE);

        // Panel slides in from the left
        ObjectAnimator panelSlide = ObjectAnimator.ofFloat(
                navPanel, "translationX", -dpToPx(220), 0f);
        panelSlide.setDuration(ANIM_DURATION_MS);
        panelSlide.setInterpolator(new DecelerateInterpolator(1.6f));

        // Scrim fades in
        ObjectAnimator scrimFade = ObjectAnimator.ofFloat(scrimOverlay, "alpha", 0f, 1f);
        scrimFade.setDuration(ANIM_DURATION_MS);

        // Shield icon glows (scale pulse + tint handled in HomeFragment via callback)
        AnimatorSet set = new AnimatorSet();
        set.playTogether(panelSlide, scrimFade);
        set.start();
    }

    public void closeNavPanel() {
        if (!isPanelOpen) return;
        isPanelOpen = false;

        // Panel slides back out
        ObjectAnimator panelSlide = ObjectAnimator.ofFloat(
                navPanel, "translationX", 0f, -dpToPx(220));
        panelSlide.setDuration(ANIM_DURATION_MS);
        panelSlide.setInterpolator(new AccelerateDecelerateInterpolator());

        // Scrim fades out
        ObjectAnimator scrimFade = ObjectAnimator.ofFloat(scrimOverlay, "alpha", 1f, 0f);
        scrimFade.setDuration(ANIM_DURATION_MS);

        AnimatorSet set = new AnimatorSet();
        set.playTogether(panelSlide, scrimFade);
        set.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                scrimOverlay.setVisibility(View.GONE);
            }
        });
        set.start();
    }

    public boolean isPanelOpen() {
        return isPanelOpen;
    }

    /** Handle system back-press: close panel first if open */
    @Override
    public void onBackPressed() {
        if (isPanelOpen) {
            closeNavPanel();
        } else {
            super.onBackPressed();
        }
    }

    // ─────────────────────────────────────────────────
    // PERMISSIONS (unchanged from original)
    // ─────────────────────────────────────────────────

    private void requestNeededPermissions() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            };
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            };
        } else {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA,
                    Manifest.permission.READ_PHONE_STATE
            };
        }
        ActivityCompat.requestPermissions(this, permissions, REQ_PERMS);
    }

    private void requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            boolean alreadyGranted = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED;

            if (alreadyGranted) {
                startMonitoringService();
                return;
            }

            new AlertDialog.Builder(this)
                    .setTitle("Allow location all the time")
                    .setMessage(
                            "Suraksha AI needs to track your location in the " +
                                    "background to keep you safe even when the app is closed.\n\n" +
                                    "On the next screen, please select \"Allow all the time\"."
                    )
                    .setPositiveButton("Continue", (dialog, which) ->
                            ActivityCompat.requestPermissions(
                                    this,
                                    new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                                    REQ_BACKGROUND_LOCATION
                            )
                    )
                    .setCancelable(false)
                    .show();
        } else {
            startMonitoringService();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            boolean fineLocationGranted = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED;

            if (fineLocationGranted) {
                requestBackgroundLocation();
            } else {
                Toast.makeText(this,
                        "Location permission is required for safety tracking.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // ─────────────────────────────────────────────────
    // SERVICE + FIREBASE (unchanged from original)
    // ─────────────────────────────────────────────────

    private void startMonitoringService() {
        LocationHeartbeatWorker.schedule(this);
        BatteryOptimizationHelper.checkAndPrompt(this);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                registerPhoneInFirebase();
                Intent serviceIntent = new Intent(MainActivity.this, MonitoringService.class);
                serviceIntent.putExtra("cmd", MonitoringService.CMD_START);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to start MonitoringService", e);
            }
        }, 1500);
    }

    private void stopMonitoringService() {
        LocationHeartbeatWorker.cancel(this);
        Intent serviceIntent = new Intent(MainActivity.this, MonitoringService.class);
        serviceIntent.putExtra("cmd", MonitoringService.CMD_STOP);
        startService(serviceIntent);
    }

    private void registerPhoneInFirebase() {
        try {
            PrefsManager prefs = new PrefsManager(this);
            String myId = prefs.getUserId();
            String savedPhone = prefs.getMyPhone();
            if (!savedPhone.isEmpty()) {
                FirebaseLocationHelper.registerPhone(myId, savedPhone);
                Log.d(TAG, "Phone re-registered: " + savedPhone);
            } else {
                Log.w(TAG, "No phone saved yet — user needs to save in Settings");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to register phone", e);
        }
    }

    // ─────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
