package com.suraksha.ai.agent;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.suraksha.ai.har.HumanActivityRecognizer;
import com.suraksha.ai.har.HumanActivityRecognizer.Activity;
import com.suraksha.ai.model.SensorReading;
import com.suraksha.ai.model.SafetyStatus;

/**
 * HumanActivityAgent
 * ────────────────────────────────────────────────────────────────────────────
 * Wraps HumanActivityRecognizer inside the existing BaseAgent / AgentOrchestrator
 * framework so the recognised activity can:
 *
 *  1. Be queried via the in-app AI chat ("What am I doing right now?")
 *  2. Feed into ThreatDetectionAgent (e.g. sudden fall → escalate SOS)
 *  3. Enrich ThreatContext so SmartSosAgent can mention the user's state
 *
 * Usage (from AgentOrchestrator or MonitoringService)
 * ─────────────────────────────────────────────────────
 *     activityAgent = new HumanActivityAgent(context);
 *     activityAgent.start();
 *
 *     // When current activity is needed:
 *     Activity act = activityAgent.getCurrentActivity();
 *
 *     // Inject into SensorReading before threat evaluation:
 *     reading.currentActivity = act;
 */
public class HumanActivityAgent extends BaseAgent
        implements HumanActivityRecognizer.ActivityCallback {

    private static final String TAG = "HumanActivityAgent";

    private final HumanActivityRecognizer recognizer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Latest state – read by other agents
    private volatile Activity currentActivity = Activity.UNKNOWN;
    private volatile float    confidence      = 0f;

    // Callback to notify orchestrator of a fall (wired in onCreate)
    private FallAlertCallback fallAlertCallback;

    public interface FallAlertCallback {
        void onFallDetected();
    }

    // ── Constructor ──────────────────────────────────────────────────────────

    public HumanActivityAgent(Context context) {
        super(context);
        recognizer = new HumanActivityRecognizer(context);
        recognizer.setCallback(this);
    }

    // ── BaseAgent identity ────────────────────────────────────────────────────

    @Override public String getName()  { return "Activity Agent"; }
    @Override public String getEmoji() { return "🏃"; }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void start() {
        boolean ok = recognizer.start();
        Log.i(TAG, ok ? "HAR started" : "HAR unavailable (no accelerometer)");
    }

    public void stop() {
        recognizer.stop();
    }

    public void setFallAlertCallback(FallAlertCallback cb) {
        this.fallAlertCallback = cb;
    }

    // ── Public accessors ──────────────────────────────────────────────────────

    public Activity getCurrentActivity() { return currentActivity; }
    public float    getConfidence()       { return confidence; }

    /** Human-readable label for display in Home screen or chat. */
    public String getActivityLabel() {
        switch (currentActivity) {
            case STILL:   return "Stationary";
            case WALKING: return "Walking";
            case RUNNING: return "Running";
            case CYCLING: return "Cycling";
            case VEHICLE: return "In a vehicle";
            case FALLING: return "⚠️ Falling!";
            default:      return "Unknown";
        }
    }

    /** Emoji icon matching the current activity (for the UI card). */
    public String getActivityEmoji() {
        switch (currentActivity) {
            case STILL:   return "🧍";
            case WALKING: return "🚶";
            case RUNNING: return "🏃";
            case CYCLING: return "🚴";
            case VEHICLE: return "🚗";
            case FALLING: return "⚠️";
            default:      return "❓";
        }
    }

    // ── HumanActivityRecognizer.ActivityCallback ──────────────────────────────

    @Override
    public void onActivityChanged(Activity activity, float conf) {
        currentActivity = activity;
        confidence      = conf;
        Log.d(TAG, "Activity → " + activity + " (" + (int)(conf*100) + "%)");
    }

    @Override
    public void onFallDetected() {
        Log.w(TAG, "Fall detected – alerting orchestrator");
        if (fallAlertCallback != null) {
            mainHandler.post(() -> fallAlertCallback.onFallDetected());
        }
    }

    // ── BaseAgent required method ─────────────────────────────────────────────

    @Override
    public void onAutonomousTrigger(ThreatContext ctx) {
        // If a fall was detected, the fallAlertCallback handles it directly.
        // Nothing extra needed here.
    }

    // ── Chat entry point ──────────────────────────────────────────────────────

    @Override
    public void handleMessage(String userMessage, ChatCallback callback) {
        String lower = userMessage.toLowerCase();

        if (lower.contains("activity") || lower.contains("doing")
                || lower.contains("moving") || lower.contains("walking")
                || lower.contains("running")) {

            String reply = String.format(
                    "%s  You appear to be **%s** right now (confidence: %d%%).\n\n" +
                            "I'm monitoring your movement continuously in the background. " +
                            "If I detect a fall or sudden impact, I'll trigger an alert automatically.",
                    getActivityEmoji(),
                    getActivityLabel(),
                    (int)(confidence * 100));
            callback.onAgentMessage(getName(), getEmoji(), reply);

        } else if (lower.contains("fall") || lower.contains("detect")) {
            callback.onAgentMessage(getName(), getEmoji(),
                    "🛡️ Fall detection is **active**. I watch for sudden free-fall " +
                            "followed by an impact spike. If triggered, your emergency contacts " +
                            "will be notified automatically.");
        } else {
            callback.onAgentMessage(getName(), getEmoji(),
                    "I track your real-time activity (walking, running, cycling, still, " +
                            "vehicle, or fall). Ask me *\"what am I doing?\"* or *\"is fall detection on?\"*");
        }
    }
}
