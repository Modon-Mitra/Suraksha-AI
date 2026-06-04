package com.suraksha.ai.agent;

/**
 * ThreatContext — snapshot of all sensor data at the moment a threat is detected.
 * Passed to every agent's onAutonomousTrigger() so they can make smart decisions.
 */
public class ThreatContext {

    public enum ThreatLevel { LOW, MEDIUM, HIGH }

    public enum MovementPattern { STATIONARY, NORMAL, ERRATIC, FLEEING }

    // ── Threat assessment ─────────────────────────────────────────────────
    public ThreatLevel      threatLevel     = ThreatLevel.LOW;
    public float            threatScore     = 0f;       // 0.0 – 1.0

    // ── Sensor readings ───────────────────────────────────────────────────
    public float            heartRate       = 0f;       // BPM
    public float            accelerationG   = 0f;       // G-force
    public float            audioAmplitude  = 0f;       // 0.0 – 1.0 normalised RMS
    public MovementPattern  movementPattern = MovementPattern.NORMAL;

    // ── Location ──────────────────────────────────────────────────────────
    public double           latitude        = 0.0;
    public double           longitude       = 0.0;
    public boolean          locationAvailable = false;

    // ── Trigger source ────────────────────────────────────────────────────
    public boolean          triggeredByCodeWord = false;
    public boolean          panicMode           = false; // screaming/loud voice
    public String           triggerReason       = "";

    // ── Timestamp ─────────────────────────────────────────────────────────
    public long             timestamp       = System.currentTimeMillis();

    // ── Constructor helpers ───────────────────────────────────────────────

    public static ThreatContext fromCodeWord(boolean panic, double lat, double lng) {
        ThreatContext ctx         = new ThreatContext();
        ctx.triggeredByCodeWord   = true;
        ctx.panicMode             = panic;
        ctx.threatLevel           = panic ? ThreatLevel.HIGH : ThreatLevel.MEDIUM;
        ctx.threatScore           = panic ? 0.9f : 0.6f;
        ctx.latitude              = lat;
        ctx.longitude             = lng;
        ctx.locationAvailable     = (lat != 0.0 || lng != 0.0);
        ctx.triggerReason         = panic ? "Code word (panic)" : "Code word";
        return ctx;
    }

    public static ThreatContext fromSensors(float hr, float accel,
                                            float audio, double lat, double lng) {
        ThreatContext ctx  = new ThreatContext();
        ctx.heartRate      = hr;
        ctx.accelerationG  = accel;
        ctx.audioAmplitude = audio;
        ctx.latitude       = lat;
        ctx.longitude      = lng;
        ctx.locationAvailable = (lat != 0.0 || lng != 0.0);
        ctx.triggerReason  = "Sensor anomaly";

        // Score: 30% audio + 25% accel + 25% HR + 20% movement
        float audioNorm  = Math.min(audio, 1.0f);
        float accelNorm  = Math.min(accel / 10f, 1.0f);   // normalise ~0-10G
        float hrNorm     = hr > 130 ? Math.min((hr - 130) / 50f, 1.0f) : 0f;
        ctx.threatScore  = 0.30f * audioNorm
                + 0.25f * accelNorm
                + 0.25f * hrNorm;

        if      (ctx.threatScore >= 0.7f) ctx.threatLevel = ThreatLevel.HIGH;
        else if (ctx.threatScore >= 0.4f) ctx.threatLevel = ThreatLevel.MEDIUM;
        else                              ctx.threatLevel = ThreatLevel.LOW;

        return ctx;
    }
}
