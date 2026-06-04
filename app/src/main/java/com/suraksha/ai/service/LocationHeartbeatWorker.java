package com.suraksha.ai.service;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.suraksha.ai.utils.PrefsManager;

import java.util.concurrent.TimeUnit;

/**
 * LocationHeartbeatWorker — runs every 15 minutes via WorkManager.
 *
 * Purpose: if Android's battery optimizer kills MonitoringService
 * (which happens after a few hours on most devices), this worker
 * acts as a watchdog and restarts it automatically.
 *
 * WorkManager is battery-safe and survives Doze mode, making it
 * far more reliable than alarms or raw threads for this use case.
 *
 * Usage: call LocationHeartbeatWorker.schedule(context) once,
 * right after you first start MonitoringService (e.g. in MainActivity).
 */
public class LocationHeartbeatWorker extends Worker {

    private static final String WORK_TAG = "location_heartbeat";

    public LocationHeartbeatWorker(@NonNull Context context,
                                   @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();

        // Only restart if user had monitoring active before the service died.
        if (!new PrefsManager(ctx).isMonitoringActive()) {
            return Result.success();
        }

        Intent svc = new Intent(ctx, MonitoringService.class);
        svc.putExtra("cmd", MonitoringService.CMD_START);

        // startForegroundService() required on Android O+ for foreground services.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(svc);
        } else {
            ctx.startService(svc);
        }

        return Result.success();
    }

    /**
     * Call once from MainActivity after starting MonitoringService.
     *
     * ExistingPeriodicWorkPolicy.KEEP means if the work is already
     * scheduled (e.g. app restarted), it won't reset the timer —
     * safe to call multiple times.
     *
     * Minimum interval for PeriodicWorkRequest is 15 minutes
     * (Android OS enforced limit — cannot go lower).
     */
    public static void schedule(Context ctx) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                LocationHeartbeatWorker.class,
                15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                request);
    }

    /**
     * Call this if the user explicitly stops monitoring,
     * so the watchdog doesn't restart the service unexpectedly.
     */
    public static void cancel(Context ctx) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_TAG);
    }
}
