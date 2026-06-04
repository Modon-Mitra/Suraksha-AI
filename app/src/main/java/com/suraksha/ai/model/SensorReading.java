package com.suraksha.ai.model;

/**
 * SensorReading – snapshot of the user's biometric + motion data.
 *
 * NOTE: Blood-pressure *cannot* be measured reliably by a phone's built-in
 * sensors alone. The bp field is reserved for future BLE cuff / smartwatch
 * integration. Until then it is left as 0.
 */
public class SensorReading {

    // ── Heart-rate (BPM) – from camera PPG or wearable ──────────────────
    public float heartRate;          // 0 = unavailable
    public boolean heartRateValid;

    // ── Blood-pressure (mmHg) – reserved for BLE peripheral ─────────────
    public int bpSystolic;           // 0 = unavailable
    public int bpDiastolic;

    // ── Motion / accelerometer ───────────────────────────────────────────
    public float accelerationG;      // magnitude in G
    public boolean rapidMotion;      // spike detected

    // ── Location ─────────────────────────────────────────────────────────
    public double latitude;
    public double longitude;
    public boolean locationAvailable;

    // ── Derived ──────────────────────────────────────────────────────────
    public SafetyStatus derivedStatus;
    public long timestamp;           // System.currentTimeMillis()

    public SensorReading() {
        timestamp = System.currentTimeMillis();
        derivedStatus = SafetyStatus.SAFE;
    }
}
