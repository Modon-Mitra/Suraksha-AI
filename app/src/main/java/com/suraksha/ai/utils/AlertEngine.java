package com.suraksha.ai.utils;

import com.suraksha.ai.model.SafetyStatus;
import com.suraksha.ai.model.SensorReading;

public class AlertEngine {

    private final int hrHigh;
    private final int hrLow;

    // How many consecutive danger readings
    // before triggering SOS — prevents false alarms
    private static final int DANGER_COUNT_THRESHOLD = 10;
    private static final int WARNING_COUNT_THRESHOLD = 5;

    // Raised from 2.5G to 4.0G — filters out
    // walking, picking up phone, casual movement
    private static final float ACCEL_DANGER_G = 4.0f;

    private int dangerCount = 0;
    private int warningCount = 0;

    public AlertEngine(int hrHigh, int hrLow) {
        this.hrHigh = hrHigh;
        this.hrLow  = hrLow;
    }

    public SafetyStatus evaluate(SensorReading r) {

        boolean hrDanger = r.heartRateValid
                && (r.heartRate > hrHigh || r.heartRate < hrLow);

        boolean motionDanger = r.accelerationG > ACCEL_DANGER_G;

        if (hrDanger || motionDanger) {

            dangerCount++;
            warningCount = 0;

            // Only trigger DANGER after sustained readings
            if (dangerCount >= DANGER_COUNT_THRESHOLD) {
                return SafetyStatus.DANGER;
            }

            // Show WARNING while building up
            if (dangerCount >= WARNING_COUNT_THRESHOLD) {
                return SafetyStatus.WARNING;
            }

        } else {

            // Reset counters when readings return to normal
            dangerCount = 0;
            warningCount = 0;
        }

        return SafetyStatus.SAFE;
    }

    public void reset() {
        dangerCount = 0;
        warningCount = 0;
    }
}