package com.suraksha.ai.service;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import com.suraksha.ai.SurakshaApp;
import com.suraksha.ai.agent.AgentOrchestrator;
import com.suraksha.ai.agent.HumanActivityAgent;
import com.suraksha.ai.agent.trigger.CodeWordDetector;
import com.suraksha.ai.model.EmergencyContact;
import com.suraksha.ai.model.SafetyStatus;
import com.suraksha.ai.model.SensorReading;
import com.suraksha.ai.ui.alert.SosActivity;
import com.suraksha.ai.utils.AlertEngine;
import com.suraksha.ai.utils.FirebaseLocationHelper;
import com.suraksha.ai.utils.PrefsManager;
import com.suraksha.ai.utils.SmsHelper;

import java.util.List;

public class MonitoringService extends Service
        implements SensorEventListener {

    private static final String TAG = "MonitoringService";

    public static final String ACTION_READING = "com.suraksha.READING";
    public static final String CMD_START      = "START";
    public static final String CMD_STOP       = "STOP";
    public static final String CMD_MANUAL_SOS = "MANUAL_SOS";
    public static final String CMD_CODEWORD_START = "CMD_CODEWORD_START";
    public static final String CMD_CODEWORD_STOP  = "CMD_CODEWORD_STOP";

    private PowerManager.WakeLock          wakeLock;
    private SensorManager                  sensorManager;
    private Sensor                         heartRateSensor;
    private Sensor                         accelSensor;
    private FusedLocationProviderClient    fusedLocation;
    private LocationCallback               locationCallback;

    private final SensorReading current = new SensorReading();
    private AlertEngine          alertEngine;
    private PrefsManager         prefs;
    private SafetyStatus         lastStatus          = SafetyStatus.SAFE;
    private boolean              sosAlreadyTriggered = false;
    private boolean              locationToastShown  = false;

    // ── Agent system ──────────────────────────────────────────────────────
    private CodeWordDetector  codeWordDetector;
    private AgentOrchestrator orchestrator;
    private HumanActivityAgent activityAgent;

    // Upload to Firebase at most once every 15 s
    private static final long FIREBASE_UPLOAD_INTERVAL_MS = 15_000;
    private static final float MIN_ACCURACY_METRES = 50f; // ignore GPS fixes worse than 50m
    private long lastFirebaseUploadMs = 0;

    private final Handler handler    = new Handler(Looper.getMainLooper());
    private final Handler sosHandler = new Handler(Looper.getMainLooper());

    // =====================================================
    // SERVICE CREATE
    // =====================================================

    @Override
    public void onCreate() {
        super.onCreate();

        Notification notification = new NotificationCompat.Builder(
                this, SurakshaApp.CHANNEL_MONITORING)
                .setContentTitle("Suraksha AI")
                .setContentText("Safety monitoring active")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                                | ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(1, notification);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Location permission not granted — stopping service", e);
            stopSelf();
            return;
        }

        prefs            = new PrefsManager(this);
        alertEngine      = new AlertEngine(prefs.getHrHighThreshold(), prefs.getHrLowThreshold());
        sensorManager    = (SensorManager) getSystemService(SENSOR_SERVICE);
        fusedLocation    = LocationServices.getFusedLocationProviderClient(this);
        codeWordDetector = new CodeWordDetector(this);
        orchestrator     = AgentOrchestrator.getInstance(this);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        activityAgent = new HumanActivityAgent(this);
        // FIX: a detected fall now opens the cancellable SOS countdown
        // screen instead of firing the SMS instantly. False falls (setting
        // the phone down, tossing it in a bag) can be cancelled in 10s.
        activityAgent.setFallAlertCallback(() -> startSos("Fall detected"));
        activityAgent.start();
        wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "SurakshaAI::LocationWakeLock");
        wakeLock.acquire(24 * 60 * 60 * 1000L);

        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (wakeLock != null) {
                    if (wakeLock.isHeld()) wakeLock.release();
                    wakeLock.acquire(24 * 60 * 60 * 1000L);
                }
                handler.postDelayed(this, 23 * 60 * 60 * 1000L);
            }
        }, 23 * 60 * 60 * 1000L);

        Log.d(TAG, "Service created — userId: " + prefs.getUserId());
    }

    // =====================================================
    // START COMMAND
    // =====================================================

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        String cmd = (intent != null) ? intent.getStringExtra("cmd") : CMD_START;
        if (cmd == null) cmd = CMD_START;

        switch (cmd) {
            case CMD_CODEWORD_START:
                if (codeWordDetector != null) {
                    if (orchestrator != null) orchestrator.stopPassiveMonitoring();
                    codeWordDetector.stop();   // avoid double-start
                    codeWordDetector.start();
                }
                break;

            case CMD_CODEWORD_STOP:
                if (codeWordDetector != null) {
                    codeWordDetector.stop();
                    if (orchestrator != null) orchestrator.startPassiveMonitoring();
                }
                break;

            case CMD_STOP:
                codeWordDetector.stop();
                stopMonitoring();
                stopSelf();
                break;
            case CMD_MANUAL_SOS:
                triggerSos("Manual SOS");
                break;
            default: // CMD_START
                registerSensors();
                startLocationUpdates();
                prefs.setMonitoringActive(true);

                // MIC PRIORITY: a phone has one mic, so code-word detection
                // (SpeechRecognizer) and scream detection (AudioRecord/YAMNet)
                // cannot run at the same time. Code word is the higher-value
                // feature, so when it's enabled it takes the mic; otherwise
                // passive scream detection uses it.
                if (prefs.isCodeWordEnabled()) {
                    codeWordDetector.start();
                } else if (orchestrator != null) {
                    orchestrator.startPassiveMonitoring();
                }
                break;
        }

        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (codeWordDetector != null) codeWordDetector.stop();
        if (orchestrator     != null) orchestrator.destroy();
        stopMonitoring();
        prefs.setMonitoringActive(false);
        super.onDestroy();
    }

    // =====================================================
    // SENSORS
    // =====================================================

    private void registerSensors() {
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
        accelSensor     = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        if (heartRateSensor != null)
            sensorManager.registerListener(this, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL);
        else
            Log.w(TAG, "Heart-rate sensor unavailable");

        if (accelSensor != null)
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME);
    }

    private void stopMonitoring() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        sensorManager.unregisterListener(this);
        if (fusedLocation != null && locationCallback != null)
            fusedLocation.removeLocationUpdates(locationCallback);
        handler.removeCallbacksAndMessages(null);
        sosHandler.removeCallbacksAndMessages(null);
        if (orchestrator != null) orchestrator.stopPassiveMonitoring();
    }

    // =====================================================
    // LOCATION
    // =====================================================

    private void startLocationUpdates() {

        LocationRequest req = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 10000)
                .setMinUpdateIntervalMillis(5000)
                .setWaitForAccurateLocation(false)
                .setMaxUpdateDelayMillis(30000)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {

                Location loc = null;
                if (!result.getLocations().isEmpty())
                    loc = result.getLocations().get(result.getLocations().size() - 1);
                if (loc == null) return;

                // FIX: Ignore inaccurate fixes — stale network location
                // can be wildly wrong (40km+). Only trust GPS fixes ≤ 50m accuracy.
                if (loc.hasAccuracy() && loc.getAccuracy() > MIN_ACCURACY_METRES) {
                    Log.d(TAG, "Skipping inaccurate fix: " + loc.getAccuracy() + "m");
                    return;
                }

                current.latitude          = loc.getLatitude();
                current.longitude         = loc.getLongitude();
                current.locationAvailable = true;

                if (!locationToastShown) {
                    locationToastShown = true;
                    new Handler(Looper.getMainLooper()).post(() ->
                            Toast.makeText(MonitoringService.this,
                                    "Location tracking active ✓", Toast.LENGTH_SHORT).show());
                    lastFirebaseUploadMs = System.currentTimeMillis();
                    String firstStatus = (current.derivedStatus != null)
                            ? current.derivedStatus.name() : "SAFE";
                    FirebaseLocationHelper.uploadLocation(
                            prefs.getUserId(), current.latitude, current.longitude, firstStatus);
                } else {
                    long now = System.currentTimeMillis();
                    if (now - lastFirebaseUploadMs >= FIREBASE_UPLOAD_INTERVAL_MS) {
                        lastFirebaseUploadMs = now;
                        String status = (current.derivedStatus != null)
                                ? current.derivedStatus.name() : "SAFE";
                        FirebaseLocationHelper.uploadLocation(
                                prefs.getUserId(), current.latitude, current.longitude, status);
                    }
                }
                broadcastReading();
            }
        };

        try {
            fusedLocation.requestLocationUpdates(req, locationCallback, Looper.getMainLooper());
        } catch (SecurityException e) {
            Log.e(TAG, "requestLocationUpdates permission denied", e);
        }
    }

    // =====================================================
    // SENSOR EVENTS
    // =====================================================

    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_HEART_RATE:
                float hr = event.values[0];
                if (hr > 0) { current.heartRate = hr; current.heartRateValid = true; }
                break;
            case Sensor.TYPE_ACCELEROMETER:
                float ax = event.values[0], ay = event.values[1], az = event.values[2];
                float mag = (float) Math.sqrt(ax*ax + ay*ay + az*az) / 9.81f;
                current.accelerationG = mag;
                current.rapidMotion   = mag > 4.0f;
                break;
        }
        evaluateAndBroadcast();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // =====================================================
    // EVALUATION
    // =====================================================

    private void evaluateAndBroadcast() {
        SafetyStatus newStatus = alertEngine.evaluate(current);
        current.derivedStatus  = newStatus;
        current.timestamp      = System.currentTimeMillis();

        if (newStatus != lastStatus) {
            onStatusChanged(lastStatus, newStatus);
            lastStatus = newStatus;
        }
        broadcastReading();
        orchestrator.onSensorReading(current);
    }

    private void onStatusChanged(SafetyStatus from, SafetyStatus to) {
        Log.i(TAG, "Status: " + from + " -> " + to);
        if (to == SafetyStatus.DANGER && !sosAlreadyTriggered) {
            sosAlreadyTriggered = true;
            startSos("Danger detected");
        }
    }

    // =====================================================
    // SOS
    // =====================================================

    /**
     * Opens the SOS countdown screen. SosActivity shows a 10-second
     * cancellable countdown and fires the actual SOS (SMS + calls) itself
     * when the countdown completes. We do NOT trigger SMS here directly —
     * that would bypass the cancel button and cause false alarms.
     */
    private void startSos(String reason) {
        Log.i(TAG, "SOS countdown opened: " + reason);
        Intent sosIntent = new Intent(this, SosActivity.class);
        sosIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(sosIntent);
    }

    public void triggerSos(String reason) {
        current.derivedStatus = SafetyStatus.SOS_ACTIVE;
        if (current.locationAvailable) {
            lastFirebaseUploadMs = System.currentTimeMillis();
            FirebaseLocationHelper.uploadLocation(
                    prefs.getUserId(), current.latitude,
                    current.longitude, SafetyStatus.SOS_ACTIVE.name());
        }
        broadcastReading();
        Log.i(TAG, "SOS TRIGGERED: " + reason);
        SmsHelper.sendSosMessages(this, current.latitude, current.longitude);
        startSequentialCalling();
    }

    // =====================================================
    // SEQUENTIAL CALLING
    // =====================================================

    private void startSequentialCalling() {
        List<EmergencyContact> contacts = new PrefsManager(this).getContacts();
        if (contacts.isEmpty()) return;
        callNextContact(contacts, 0);
    }

    private void callNextContact(List<EmergencyContact> contacts, int index) {
        if (index >= contacts.size()) return;
        SmsHelper.callContact(this, contacts.get(index).phone);
        sosHandler.postDelayed(() -> callNextContact(contacts, index + 1), 10000);
    }

    // =====================================================
    // BROADCAST
    // =====================================================

    private void broadcastReading() {
        String safeStatus = (current.derivedStatus != null)
                ? current.derivedStatus.name() : "SAFE";

        Intent i = new Intent(ACTION_READING);
        i.putExtra("heartRate",      current.heartRate);
        i.putExtra("heartRateValid", current.heartRateValid);
        i.putExtra("bpSystolic",     current.bpSystolic);
        i.putExtra("bpDiastolic",    current.bpDiastolic);
        i.putExtra("accelerationG",  current.accelerationG);
        i.putExtra("rapidMotion",    current.rapidMotion);
        i.putExtra("latitude",       current.latitude);
        i.putExtra("longitude",      current.longitude);
        i.putExtra("status",         safeStatus);

        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }
}
