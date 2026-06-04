package com.suraksha.ai.utils;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

/**
 * BatteryOptimizationHelper — detects battery optimization restrictions
 * and guides the user to fix them for their specific device brand.
 *
 * Most Android OEMs add a SECOND layer of battery restriction on top of
 * the standard Android system. The standard dialog only handles the base
 * Android layer. This class handles both layers for:
 *
 *   Vivo / FuntouchOS
 *   Xiaomi / MIUI
 *   Samsung / One UI
 *   OnePlus / OxygenOS
 *   Oppo / ColorOS
 *   Realme / RealmeUI
 *   Huawei / EMUI
 *   All others (standard Android fallback)
 */
public class BatteryOptimizationHelper {

    private static final String TAG   = "BatteryOptHelper";
    private static final String PREFS = "battery_opt_prefs";
    private static final String KEY_PROMPTED_AT = "prompted_at";

    // Only re-prompt after 7 days if user dismissed
    private static final long REPROMPT_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000;

    /**
     * Call this from MainActivity after permissions are granted.
     * Shows a friendly explanation dialog and opens the correct
     * settings page for the user's specific device brand.
     * Won't spam — only shows once every 7 days if dismissed.
     */
    public static void checkAndPrompt(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;

        // Check standard Android battery optimization
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        boolean alreadyExempt = pm != null
                && pm.isIgnoringBatteryOptimizations(context.getPackageName());

        if (alreadyExempt) {
            Log.d(TAG, "Already exempt from battery optimization — no prompt needed");
            return;
        }

        // Don't re-prompt if user dismissed recently
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long lastPromptedAt = prefs.getLong(KEY_PROMPTED_AT, 0);
        if (System.currentTimeMillis() - lastPromptedAt < REPROMPT_INTERVAL_MS) {
            Log.d(TAG, "Battery opt prompt skipped — prompted recently");
            return;
        }

        showDialog(context, prefs);
    }

    // ── Dialog ────────────────────────────────────────────────────────────

    private static void showDialog(Context context, SharedPreferences prefs) {
        String brand        = Build.MANUFACTURER;
        String instructions = getInstructions(brand);

        new AlertDialog.Builder(context)
                .setTitle("🔋 Keep Suraksha AI Always Active")
                .setMessage(
                        "Your phone's battery saver is stopping Suraksha AI from "
                                + "tracking your location in the background.\n\n"
                                + "This means your emergency contacts won't see your live "
                                + "location during an SOS.\n\n"
                                + instructions
                                + "\n\nThis takes less than 30 seconds and keeps you safe.")
                .setPositiveButton("Fix it now ✓", (dialog, which) -> {
                    // Mark as prompted
                    prefs.edit().putLong(KEY_PROMPTED_AT,
                            System.currentTimeMillis()).apply();

                    // Step 1: Standard Android exemption
                    requestStandardExemption(context);

                    // Step 2: OEM-specific settings (with 500ms delay so
                    // the standard dialog has time to appear first)
                    new android.os.Handler(
                            android.os.Looper.getMainLooper())
                            .postDelayed(() -> openOemSettings(context), 800);
                })
                .setNegativeButton("Later", (dialog, which) -> {
                    prefs.edit().putLong(KEY_PROMPTED_AT,
                            System.currentTimeMillis()).apply();
                })
                .setCancelable(false)
                .show();
    }

    // ── Brand-specific instructions ───────────────────────────────────────

    private static String getInstructions(String manufacturer) {
        if (manufacturer == null) return getGenericInstructions();

        switch (manufacturer.toLowerCase()) {
            case "vivo":
                return "On your Vivo phone:\n"
                        + "Settings → Battery → Background app management "
                        + "→ Suraksha AI → No restrictions";

            case "xiaomi":
            case "redmi":
            case "poco":
                return "On your Xiaomi/MIUI phone:\n"
                        + "Settings → Apps → Suraksha AI → Battery saver "
                        + "→ No restrictions\n\n"
                        + "Also: Settings → Apps → Permissions → Autostart "
                        + "→ Enable for Suraksha AI";

            case "samsung":
                return "On your Samsung phone:\n"
                        + "Settings → Battery → Background usage limits "
                        + "→ Remove Suraksha AI from sleeping apps";

            case "oneplus":
            case "oppo":
                return "On your OnePlus/Oppo phone:\n"
                        + "Settings → Battery → Battery optimization "
                        + "→ Suraksha AI → Don't optimize";

            case "realme":
                return "On your Realme phone:\n"
                        + "Settings → Battery → Background app management "
                        + "→ Suraksha AI → No restrictions";

            case "huawei":
            case "honor":
                return "On your Huawei phone:\n"
                        + "Phone Manager → App Launch → Suraksha AI "
                        + "→ Turn off Auto-manage → Enable all three options";

            case "motorola":
                return "On your Motorola phone:\n"
                        + "Settings → Battery → Battery optimization "
                        + "→ All apps → Suraksha AI → Don't optimize";

            default:
                return getGenericInstructions();
        }
    }

    private static String getGenericInstructions() {
        return "Go to:\n"
                + "Settings → Battery → Battery optimization "
                + "→ Suraksha AI → Don't optimize";
    }

    // ── Standard Android exemption ────────────────────────────────────────

    private static void requestStandardExemption(Context context) {
        try {
            Intent intent = new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Standard exemption dialog failed: " + e.getMessage());
            // Fall back to battery settings page
            try {
                Intent intent = new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception ex) {
                Log.e(TAG, "Cannot open battery settings", ex);
            }
        }
    }

    // ── OEM-specific deep settings ────────────────────────────────────────

    private static void openOemSettings(Context context) {
        String mfr = Build.MANUFACTURER.toLowerCase();

        // Each manufacturer has a different hidden settings activity.
        // We try silently — if it fails we don't crash, standard dialog is enough.
        try {
            Intent intent = null;

            switch (mfr) {
                case "vivo":
                    intent = new Intent();
                    intent.setComponent(new ComponentName(
                            "com.vivo.permissionmanager",
                            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
                    break;

                case "xiaomi":
                case "redmi":
                case "poco":
                    intent = new Intent();
                    intent.setComponent(new ComponentName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity"));
                    break;

                case "samsung":
                    intent = new Intent();
                    intent.setComponent(new ComponentName(
                            "com.samsung.android.lool",
                            "com.samsung.android.lool.packages.PackagesActivity"));
                    break;

                case "oneplus":
                    intent = new Intent();
                    intent.setComponent(new ComponentName(
                            "com.oneplus.security",
                            "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"));
                    break;

                case "huawei":
                case "honor":
                    intent = new Intent();
                    intent.setComponent(new ComponentName(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"));
                    break;

                case "oppo":
                case "realme":
                    intent = new Intent();
                    intent.setComponent(new ComponentName(
                            "com.coloros.safecenter",
                            "com.coloros.safecenter.permission.startup.FakeActivity"));
                    break;

                default:
                    // No OEM-specific page — standard dialog is sufficient
                    Log.d(TAG, "No OEM-specific settings for: " + mfr);
                    return;
            }

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }

        } catch (Exception e) {
            // OEM settings activity doesn't exist on this device version
            // Standard dialog already shown — user can navigate manually
            Log.d(TAG, "OEM settings not available on this device: " + e.getMessage());
        }
    }
}
