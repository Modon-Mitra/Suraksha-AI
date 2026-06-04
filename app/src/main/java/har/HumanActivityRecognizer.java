package com.suraksha.ai.har;

import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * HumanActivityRecognizer
 * ────────────────────────────────────────────────────────────────────────────
 * Classifies user activity in real-time using the accelerometer + gyroscope.
 *
 * Recognised activities
 * ──────────────────────
 *  STILL    – user is stationary / sitting
 *  WALKING  – normal walking pace
 *  RUNNING  – fast movement / jogging
 *  CYCLING  – rhythmic mid-frequency oscillation
 *  VEHICLE  – high-frequency vibration, low variance (in a car/bus)
 *  FALLING  – sudden free-fall impulse followed by impact spike
 *
 * Algorithm
 * ──────────
 * 128-sample sliding window (~2.56 s at 50 Hz) over accel magnitude.
 * Four statistical features: mean, std-dev, peak-to-peak, gyro average.
 * Rule-based decision tree → label + confidence.  No ML model file needed.
 *
 * Integration
 * ────────────
 * 1. Construct in MonitoringService.onCreate().
 * 2. Call start() / stop().
 * 3. Set ActivityCallback OR listen for ACTION_ACTIVITY broadcasts.
 */
public class HumanActivityRecognizer implements SensorEventListener {

    public static final String TAG              = "HumanActivityRecognizer";
    public static final String ACTION_ACTIVITY  = "com.suraksha.ACTION_ACTIVITY";
    public static final String EXTRA_ACTIVITY   = "activity";
    public static final String EXTRA_CONFIDENCE = "confidence";

    private static final int   WINDOW_SIZE        = 128;
    private static final long  CLASSIFY_INTERVAL  = 1_000L; // ms
    // Stricter fall detection to avoid false alarms from setting the phone
    // down, tossing it, or it shifting in a bag. Require deeper free-fall,
    // more free-fall frames, and a harder impact.
    private static final float FREE_FALL_G        = 0.3f;   // was 0.4 (deeper free-fall)
    private static final int   FREE_FALL_FRAMES   = 10;     // was 6   (longer free-fall)
    private static final float IMPACT_G           = 4.5f;   // was 3.5 (harder impact)
    // Minimum gap between two fall dispatches — prevents repeated triggers
    private static final long  FALL_COOLDOWN_MS   = 120_000L; // 2 minutes

    // ── Activity enum ────────────────────────────────────────────────────────

    public enum Activity {
        STILL, WALKING, RUNNING, CYCLING, VEHICLE, FALLING, UNKNOWN
    }

    public interface ActivityCallback {
        void onActivityChanged(Activity activity, float confidence);
        void onFallDetected();
    }

    // ── Fields ───────────────────────────────────────────────────────────────

    private final Context       context;
    private final SensorManager sensorManager;
    private       Sensor        accelSensor;
    private       Sensor        gyroSensor;

    private ActivityCallback callback;

    private final Deque<Float> window = new ArrayDeque<>(WINDOW_SIZE + 1);
    private float gyroMagSum  = 0f;
    private int   gyroSamples = 0;

    private long     lastClassifyTime = 0;
    private Activity lastActivity     = Activity.UNKNOWN;

    private int     freeFallFrames = 0;
    private boolean inFreeFall     = false;
    private long    lastFallMs     = 0;   // for fall cooldown

    // ── Constructor ──────────────────────────────────────────────────────────

    public HumanActivityRecognizer(Context context) {
        this.context       = context.getApplicationContext();
        this.sensorManager = (SensorManager)
                this.context.getSystemService(Context.SENSOR_SERVICE);
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public void setCallback(ActivityCallback callback) {
        this.callback = callback;
    }

    public boolean start() {
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroSensor  = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        if (accelSensor == null) {
            Log.w(TAG, "No accelerometer – HAR unavailable");
            return false;
        }
        sensorManager.registerListener(this, accelSensor,
                SensorManager.SENSOR_DELAY_GAME);
        if (gyroSensor != null) {
            sensorManager.registerListener(this, gyroSensor,
                    SensorManager.SENSOR_DELAY_GAME);
        }
        Log.i(TAG, "HAR started");
        return true;
    }

    public void stop() {
        sensorManager.unregisterListener(this);
        window.clear();
        Log.i(TAG, "HAR stopped");
    }

    public Activity getCurrentActivity() { return lastActivity; }

    // ── SensorEventListener ──────────────────────────────────────────────────

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            handleAccel(event.values);
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            handleGyro(event.values);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ── Private helpers ──────────────────────────────────────────────────────

    private void handleAccel(float[] v) {
        float mag = magnitude(v[0], v[1], v[2]) / SensorManager.GRAVITY_EARTH;

        // Fall detection state machine
        if (mag < FREE_FALL_G) {
            freeFallFrames++;
            if (freeFallFrames >= FREE_FALL_FRAMES) inFreeFall = true;
        } else {
            if (inFreeFall && mag > IMPACT_G) {
                inFreeFall     = false;
                freeFallFrames = 0;
                dispatchFall();
                return;
            }
            freeFallFrames = 0;
            inFreeFall      = false;
        }

        // Sliding window
        window.addLast(mag);
        if (window.size() > WINDOW_SIZE) window.pollFirst();

        // Throttled classification
        long now = System.currentTimeMillis();
        if (window.size() == WINDOW_SIZE
                && (now - lastClassifyTime) >= CLASSIFY_INTERVAL) {
            lastClassifyTime = now;
            classify();
        }
    }

    private void handleGyro(float[] v) {
        gyroMagSum  += magnitude(v[0], v[1], v[2]);
        gyroSamples++;
    }

    // ── Classifier ──────────────────────────────────────────────────────────

    private void classify() {
        float[] data = toArray(window);

        float mean    = mean(data);
        float std     = std(data, mean);
        float p2p     = peakToPeak(data);
        float gyroAvg = gyroSamples > 0 ? gyroMagSum / gyroSamples : 0f;

        gyroMagSum  = 0f;
        gyroSamples = 0;

        Activity activity;
        float    confidence;

        if (std < 0.04f) {
            if (mean > 0.95f && mean < 1.05f && gyroAvg < 0.3f) {
                activity   = Activity.STILL;   confidence = 0.90f;
            } else {
                activity   = Activity.VEHICLE; confidence = 0.75f;
            }
        } else if (std < 0.20f) {
            if (p2p < 0.8f) {
                activity   = Activity.WALKING;  confidence = 0.82f;
            } else {
                activity   = Activity.CYCLING;  confidence = 0.70f;
            }
        } else if (std < 0.55f) {
            if (gyroAvg > 1.0f) {
                activity   = Activity.CYCLING;  confidence = 0.78f;
            } else {
                activity   = Activity.WALKING;  confidence = 0.72f;
            }
        } else {
            activity   = Activity.RUNNING;      confidence = 0.85f;
        }

        Log.d(TAG, String.format(
                "mean=%.3f std=%.3f p2p=%.3f gyro=%.3f → %s (%.0f%%)",
                mean, std, p2p, gyroAvg, activity, confidence * 100));

        if (activity != lastActivity) {
            lastActivity = activity;
            broadcast(activity, confidence);
            if (callback != null) callback.onActivityChanged(activity, confidence);
        }
    }

    private void dispatchFall() {
        // Cooldown — don't fire repeatedly within a short window
        long now = System.currentTimeMillis();
        if (now - lastFallMs < FALL_COOLDOWN_MS) {
            Log.d(TAG, "Fall ignored — within cooldown");
            return;
        }
        lastFallMs = now;

        Log.w(TAG, "FALL DETECTED");
        lastActivity = Activity.FALLING;
        broadcast(Activity.FALLING, 0.95f);
        if (callback != null) {
            callback.onFallDetected();
            callback.onActivityChanged(Activity.FALLING, 0.95f);
        }
    }

    private void broadcast(Activity activity, float confidence) {
        Intent intent = new Intent(ACTION_ACTIVITY);
        intent.putExtra(EXTRA_ACTIVITY,   activity.name());
        intent.putExtra(EXTRA_CONFIDENCE, confidence);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    // ── Math ─────────────────────────────────────────────────────────────────

    private static float magnitude(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static float[] toArray(Deque<Float> deque) {
        float[] arr = new float[deque.size()];
        int i = 0;
        for (float f : deque) arr[i++] = f;
        return arr;
    }

    private static float mean(float[] a) {
        float s = 0; for (float v : a) s += v; return s / a.length;
    }

    private static float std(float[] a, float mean) {
        float s = 0; for (float v : a) s += (v - mean) * (v - mean);
        return (float) Math.sqrt(s / a.length);
    }

    private static float peakToPeak(float[] a) {
        float lo = a[0], hi = a[0];
        for (float v : a) { if (v < lo) lo = v; if (v > hi) hi = v; }
        return hi - lo;
    }
}