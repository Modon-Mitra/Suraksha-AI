package com.suraksha.ai;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

/**
 * SurakshaApp – Application entry point.
 * Creates notification channels used throughout the app.
 */
public class SurakshaApp extends Application {

    // ── Notification Channel IDs ──────────────────────────────────────────
    public static final String CHANNEL_MONITORING = "ch_monitoring";   // silent persistent
    public static final String CHANNEL_ALERT       = "ch_alert";        // high-priority
    public static final String CHANNEL_SOS         = "ch_sos";          // max-priority

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationManager nm = getSystemService(NotificationManager.class);

        // Silent ongoing channel for the foreground service
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_MONITORING,
                "Suraksha Monitoring",
                NotificationManager.IMPORTANCE_MIN));

        // Warning / suspicious-activity alerts
        NotificationChannel alertCh = new NotificationChannel(
                CHANNEL_ALERT,
                "Safety Alerts",
                NotificationManager.IMPORTANCE_HIGH);
        alertCh.enableVibration(true);
        nm.createNotificationChannel(alertCh);

        // SOS – maximum importance, full-screen intent
        NotificationChannel sosCh = new NotificationChannel(
                CHANNEL_SOS,
                "SOS Emergency",
                NotificationManager.IMPORTANCE_MAX);
        sosCh.enableVibration(true);
        sosCh.setBypassDnd(true);
        nm.createNotificationChannel(sosCh);
    }
}
