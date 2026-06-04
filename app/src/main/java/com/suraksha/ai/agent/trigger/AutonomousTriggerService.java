package com.suraksha.ai.agent.trigger;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.suraksha.ai.SurakshaApp;
import com.suraksha.ai.utils.PrefsManager;

public class AutonomousTriggerService extends Service {

    private static final String TAG = "AutonomousTriggerService";

    public static final String CMD_START = "START";
    public static final String CMD_STOP  = "STOP";

    private CodeWordDetector detector;
    private PrefsManager     prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs    = new PrefsManager(this);
        detector = new CodeWordDetector(this);

        Notification notification = new NotificationCompat.Builder(
                this, SurakshaApp.CHANNEL_MONITORING)
                .setContentTitle("Suraksha AI — Active")
                .setContentText("Safety monitoring running")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setSilent(true)
                .build();

        // ── FIX: must pass ServiceInfo type on targetSdk 34 ──────────────
        // Without this, Android 14 throws SecurityException even with the
        // FOREGROUND_SERVICE_MICROPHONE permission declared in manifest.
        // Also need RECORD_AUDIO granted at runtime before this call.
        boolean hasAudio = ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasAudio) {
                startForeground(2, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                // Fall back to plain foreground service if mic not granted yet
                // Code word detection won't run but app won't crash
                startForeground(2, notification);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Could not start with microphone type — falling back: " + e.getMessage());
            try {
                startForeground(2, notification);
            } catch (Exception ex) {
                Log.e(TAG, "startForeground failed entirely", ex);
                stopSelf();
                return;
            }
        }

        Log.i(TAG, "AutonomousTriggerService created. hasAudio=" + hasAudio);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String cmd = intent != null ? intent.getStringExtra("cmd") : CMD_START;
        if (cmd == null) cmd = CMD_START;

        switch (cmd) {
            case CMD_STOP:
                detector.stop();
                stopSelf();
                break;
            default:
                if (prefs.isCodeWordEnabled()) {
                    detector.start();
                    Log.i(TAG, "Code word detection started in background");
                }
                break;
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (detector != null) detector.stop();
        super.onDestroy();
        Log.i(TAG, "AutonomousTriggerService destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
