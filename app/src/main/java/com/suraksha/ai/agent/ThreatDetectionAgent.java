package com.suraksha.ai.agent;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * ThreatDetectionAgent — passively monitors audio amplitude and motion
 * to detect screams, panic, sudden impacts, and unsafe situations.
 *
 * ── DETECTION SIGNALS ────────────────────────────────────────────────────
 *  1. Audio RMS > SCREAM_THRESHOLD       → possible scream/panic voice
 *  2. Accelerometer G-force > 4.0G       → sudden fall or collision
 *  3. Heart rate > 130 BPM               → extreme stress/panic
 *  4. All three together                 → HIGH threat, immediate cascade
 *
 * ── AUDIO MONITORING ─────────────────────────────────────────────────────
 *  startAudioMonitoring() runs a background thread sampling mic amplitude
 *  every 500ms. It never records or stores audio — only reads RMS level.
 *  Stop it with stopAudioMonitoring() to release the mic.
 */
public class ThreatDetectionAgent extends BaseAgent {

    private static final String TAG = "ThreatDetectionAgent";

    // ── Thresholds ────────────────────────────────────────────────────────
    // RMS amplitude (0–32767) above which we flag a possible scream
    private static final double SCREAM_RMS_THRESHOLD  = 20000.0;
    // G-force above which we flag a fall/collision
    private static final float  IMPACT_G_THRESHOLD    = 4.0f;
    // Heart rate above which we flag extreme stress
    private static final float  PANIC_HR_THRESHOLD    = 130f;

    // ── Audio monitoring ──────────────────────────────────────────────────
    private static final int    SAMPLE_RATE            = 16000;
    private static final int    SAMPLE_INTERVAL_MS     = 500;

    private AudioRecord audioRecord;
    private Thread      audioThread;
    private volatile boolean audioRunning = false;

    // ── Escalation state ──────────────────────────────────────────────────
    private boolean screamDetected  = false;
    private boolean impactDetected  = false;
    private boolean panicHrDetected = false;

    // Callback to notify orchestrator when threat escalates
    public interface ThreatEscalationCallback {
        void onThreatEscalated(ThreatContext ctx);
    }
    private ThreatEscalationCallback escalationCallback;

    // ── Constructor ───────────────────────────────────────────────────────

    public ThreatDetectionAgent(Context context) {
        super(context);
    }

    public void setEscalationCallback(ThreatEscalationCallback cb) {
        this.escalationCallback = cb;
    }

    // ── BaseAgent identity ────────────────────────────────────────────────

    @Override public String getName()  { return "Threat Detection Agent"; }
    @Override public String getEmoji() { return "🎙️"; }

    // ── Chat entry point ──────────────────────────────────────────────────

    @Override
    public void handleMessage(String userMessage, ChatCallback callback) {
        String lower = userMessage.toLowerCase();

        if (lower.contains("start") || lower.contains("monitor")) {
            startAudioMonitoring();
            callback.onAgentMessage(getName(), getEmoji(),
                    "Audio monitoring started. I'm listening for any signs of danger.");

        } else if (lower.contains("stop")) {
            stopAudioMonitoring();
            callback.onAgentMessage(getName(), getEmoji(),
                    "Audio monitoring paused.");

        } else if (lower.contains("status")) {
            callback.onAgentMessage(getName(), getEmoji(),
                    "Status: audio monitoring " + (audioRunning ? "ACTIVE 🟢" : "INACTIVE 🔴")
                            + "\nScream detected: " + screamDetected
                            + "\nImpact detected: " + impactDetected
                            + "\nPanic HR detected: " + panicHrDetected);

        } else {
            callback.onAgentMessage(getName(), getEmoji(),
                    "I continuously monitor audio and motion for signs of danger. "
                            + "Type 'start monitoring' to enable, or 'status' to check.");
        }
    }

    // ── Autonomous trigger ────────────────────────────────────────────────

    @Override
    public void onAutonomousTrigger(ThreatContext ctx) {
        Log.i(TAG, "Autonomous trigger: " + ctx.triggerReason
                + " level=" + ctx.threatLevel);

        switch (ctx.threatLevel) {
            case HIGH:
                // Escalate to maximum sensitivity
                postToChatAsAgent(
                        "⚠️ HIGH threat detected!\n"
                                + "Reason: " + ctx.triggerReason + "\n"
                                + "HR: " + (int)ctx.heartRate + " BPM | "
                                + "Motion: " + String.format("%.1f", ctx.accelerationG) + "G\n"
                                + "Activating emergency response...");
                startAudioMonitoring();
                break;

            case MEDIUM:
                postToChatAsAgent(
                        "⚡ Unusual activity detected. Monitoring closely.\n"
                                + "Stay safe — I'm watching your sensors.");
                startAudioMonitoring();
                break;

            case LOW:
                // Silent — no chat message for low threat
                break;
        }
    }

    // ── Audio Monitoring ──────────────────────────────────────────────────

    /**
     * Starts a background thread that samples mic amplitude every 500ms.
     * Does NOT record audio — only reads RMS level to detect screaming.
     * Requires RECORD_AUDIO permission.
     */
    public void startAudioMonitoring() {
        if (audioRunning) return;

        int bufSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize);

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord not initialised — no RECORD_AUDIO permission?");
                return;
            }

            audioRunning = true;
            audioRecord.startRecording();

            audioThread = new Thread(() -> {
                short[] buffer = new short[bufSize / 2];
                while (audioRunning) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        double rms = calculateRms(buffer, read);
                        if (rms > SCREAM_RMS_THRESHOLD) {
                            onScreamDetected(rms);
                        }
                    }
                    try { Thread.sleep(SAMPLE_INTERVAL_MS); }
                    catch (InterruptedException e) { break; }
                }
            }, "ThreatAudioMonitor");

            audioThread.start();
            Log.i(TAG, "Audio monitoring started");

        } catch (SecurityException e) {
            Log.e(TAG, "RECORD_AUDIO permission denied", e);
        }
    }

    public void stopAudioMonitoring() {
        audioRunning = false;
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception ignored) {}
            audioRecord = null;
        }
        Log.i(TAG, "Audio monitoring stopped");
    }

    // ── Sensor input from MonitoringService ──────────────────────────────

    /**
     * Called by AgentOrchestrator.onSensorReading() with live sensor data.
     * Checks accelerometer and heart rate thresholds independently.
     */
    public void onSensorUpdate(float heartRate, float accelerationG) {

        impactDetected  = accelerationG  > IMPACT_G_THRESHOLD;
        panicHrDetected = heartRate      > PANIC_HR_THRESHOLD;

        if (impactDetected) {
            Log.w(TAG, "Impact detected: " + accelerationG + "G");
        }

        // If two or more signals fire simultaneously → HIGH threat
        int signalCount = (screamDetected ? 1 : 0)
                + (impactDetected  ? 1 : 0)
                + (panicHrDetected ? 1 : 0);

        if (signalCount >= 2 && escalationCallback != null) {
            ThreatContext ctx = ThreatContext.fromSensors(
                    heartRate, accelerationG, screamDetected ? 0.9f : 0f, 0, 0);
            ctx.triggerReason = buildTriggerReason();
            escalationCallback.onThreatEscalated(ctx);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void onScreamDetected(double rms) {
        screamDetected = true;
        Log.w(TAG, "Scream detected! RMS=" + rms);
        new Handler(Looper.getMainLooper()).post(() ->
                postToChatAsAgent("🔊 Loud distress sound detected (RMS: "
                        + (int)rms + "). Checking other sensors..."));
    }

    private double calculateRms(short[] buffer, int length) {
        double sumSq = 0;
        for (int i = 0; i < length; i++) sumSq += (double) buffer[i] * buffer[i];
        return Math.sqrt(sumSq / length);
    }

    private String buildTriggerReason() {
        StringBuilder sb = new StringBuilder();
        if (screamDetected)  sb.append("Scream ");
        if (impactDetected)  sb.append("Impact ");
        if (panicHrDetected) sb.append("HighHR ");
        return sb.toString().trim();
    }
}
