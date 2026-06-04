package com.suraksha.ai.service;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.suraksha.ai.utils.PrefsManager;

/**
 * BootReceiver — restarts MonitoringService after device reboot.
 *
 * FIX: Added a 10-second delay before starting the foreground service.
 * Android 12+ throws ForegroundServiceStartNotAllowedException if you
 * try to start a foreground service immediately in a BroadcastReceiver
 * at boot — the system isn't ready yet (mAllowStartForeground = false).
 * Delaying by 10s gives the system time to fully initialise.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG            = "BootReceiver";
    private static final int    BOOT_DELAY_MS  = 10_000; // 10 seconds

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!new PrefsManager(ctx).isMonitoringActive()) return;

        boolean hasLocation = ContextCompat.checkSelfPermission(ctx,
                Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        if (!hasLocation) {
            Log.w(TAG, "Location permission not granted — skipping service restart at boot");
            return;
        }

        Log.d(TAG, "Boot complete — scheduling MonitoringService restart in " + BOOT_DELAY_MS + "ms");

        // ── Delay service start to avoid ForegroundServiceStartNotAllowedException ──
        // goAsync() keeps the receiver alive while we wait
        final PendingResult result = goAsync();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Intent svc = new Intent(ctx, MonitoringService.class);
                svc.putExtra("cmd", MonitoringService.CMD_START);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(svc);
                } else {
                    ctx.startService(svc);
                }
                Log.d(TAG, "MonitoringService restarted after boot");
            } catch (Exception e) {
                Log.e(TAG, "Failed to restart service at boot: " + e.getMessage());
            } finally {
                result.finish();
            }
        }, BOOT_DELAY_MS);
    }
}
