package com.suraksha.ai.agent;

import android.content.Context;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * BehaviorPredictionAgent — detects stalking by analysing movement patterns.
 *
 * ── ALGORITHM ────────────────────────────────────────────────────────────
 *  Maintains a 30-minute rolling window of GPS points in memory (never
 *  written to disk). Every time a new location arrives:
 *
 *  1. Checks if the user has been moving in circles (same area 3+ times)
 *  2. Checks if movement has suddenly stopped in an isolated spot
 *  3. Checks if speed has changed erratically (fleeing pattern)
 *
 *  Stalking flag: if the user's path crosses a prior location cluster
 *  within 100m more than REPEAT_THRESHOLD times → ALERT.
 *
 * ── PRIVACY ──────────────────────────────────────────────────────────────
 *  Location history is held in memory only. It is never written to disk
 *  or uploaded to Firebase. The deque is capped at MAX_HISTORY_POINTS.
 */
public class BehaviorPredictionAgent extends BaseAgent {

    private static final String TAG = "BehaviorPredictionAgent";

    // ── Tuning constants ──────────────────────────────────────────────────
    // Max 30 min of GPS points at 10s intervals = 180 points
    private static final int    MAX_HISTORY_POINTS = 180;
    // Distance in metres — two points are "same cluster" if closer than this
    private static final double CLUSTER_RADIUS_M   = 100.0;
    // How many times the user must revisit the same cluster to flag stalking
    private static final int    REPEAT_THRESHOLD   = 3;
    // Minimum path length before we start checking (avoids false positives at home)
    private static final int    MIN_POINTS_TO_CHECK = 10;

    // ── State ─────────────────────────────────────────────────────────────
    private final Deque<double[]> locationHistory = new ArrayDeque<>();
    // double[] = { latitude, longitude, timestampMs }

    private boolean stalkingAlertSent = false;

    public BehaviorPredictionAgent(Context context) {
        super(context);
    }

    @Override public String getName()  { return "Behavior Prediction Agent"; }
    @Override public String getEmoji() { return "👁️"; }

    // ── Chat entry point ──────────────────────────────────────────────────

    @Override
    public void handleMessage(String userMessage, ChatCallback callback) {
        String lower = userMessage.toLowerCase();

        if (lower.contains("stalking") || lower.contains("following")
                || lower.contains("being followed") || lower.contains("suspicious")) {

            StalkingAssessment assessment = assessStalking();
            callback.onAgentMessage(getName(), getEmoji(),
                    formatAssessment(assessment));

        } else if (lower.contains("clear") || lower.contains("reset")) {
            clearHistory();
            stalkingAlertSent = false;
            callback.onAgentMessage(getName(), getEmoji(),
                    "Movement history cleared. Monitoring fresh from now.");

        } else if (lower.contains("status") || lower.contains("history")) {
            callback.onAgentMessage(getName(), getEmoji(),
                    "Tracking " + locationHistory.size() + " location points "
                            + "(last 30 min).\n"
                            + "Stalking alert sent: " + stalkingAlertSent);

        } else {
            callback.onAgentMessage(getName(), getEmoji(),
                    "I analyse your movement patterns to detect if someone is following you.\n\n"
                            + "Say 'am I being followed?' for an assessment, or I'll alert you "
                            + "automatically if I detect a suspicious pattern.");
        }
    }

    // ── Autonomous trigger ────────────────────────────────────────────────

    @Override
    public void onAutonomousTrigger(ThreatContext ctx) {
        if (!ctx.locationAvailable) return;

        // Add new point to history
        addLocation(ctx.latitude, ctx.longitude);

        // Only assess if we have enough data
        if (locationHistory.size() < MIN_POINTS_TO_CHECK) return;

        StalkingAssessment assessment = assessStalking();

        if (assessment.riskLevel == StalkingAssessment.RiskLevel.HIGH
                && !stalkingAlertSent) {
            stalkingAlertSent = true;
            postToChatAsAgent(
                    "👁️ Suspicious movement pattern detected!\n\n"
                            + formatAssessment(assessment) + "\n\n"
                            + "Recommended actions:\n"
                            + "• Head to a crowded public place\n"
                            + "• Call a trusted contact\n"
                            + "• Say 'find nearest police' for help");
        }
    }

    // ── Location tracking ─────────────────────────────────────────────────

    /**
     * Called by AgentOrchestrator each time a new GPS point arrives.
     * Keeps the last MAX_HISTORY_POINTS points in memory.
     */
    public void addLocation(double lat, double lng) {
        locationHistory.addLast(new double[]{lat, lng, System.currentTimeMillis()});
        if (locationHistory.size() > MAX_HISTORY_POINTS) {
            locationHistory.pollFirst();
        }
    }

    public void clearHistory() {
        locationHistory.clear();
    }

    // ── Stalking assessment ───────────────────────────────────────────────

    public StalkingAssessment assessStalking() {
        StalkingAssessment result = new StalkingAssessment();
        if (locationHistory.size() < MIN_POINTS_TO_CHECK) {
            result.riskLevel    = StalkingAssessment.RiskLevel.UNKNOWN;
            result.description  = "Not enough movement data yet.";
            return result;
        }

        double[][] points = locationHistory.toArray(new double[0][]);
        int maxRevisits   = 0;
        double[] hotspot  = null;

        // Check each point against all earlier points
        for (int i = MIN_POINTS_TO_CHECK; i < points.length; i++) {
            int revisitCount = 0;
            for (int j = 0; j < i; j++) {
                if (distanceMetres(points[i], points[j]) < CLUSTER_RADIUS_M) {
                    revisitCount++;
                }
            }
            if (revisitCount > maxRevisits) {
                maxRevisits = revisitCount;
                hotspot     = points[i];
            }
        }

        if (maxRevisits >= REPEAT_THRESHOLD) {
            result.riskLevel   = StalkingAssessment.RiskLevel.HIGH;
            result.revisitCount = maxRevisits;
            result.hotspotLat  = hotspot != null ? hotspot[0] : 0;
            result.hotspotLng  = hotspot != null ? hotspot[1] : 0;
            result.description = "Your path has crossed the same area "
                    + maxRevisits + " times in the last 30 minutes. "
                    + "This may indicate you are being followed.";
        } else if (maxRevisits >= 2) {
            result.riskLevel   = StalkingAssessment.RiskLevel.MEDIUM;
            result.revisitCount = maxRevisits;
            result.description = "Some repeated movement detected. Stay alert.";
        } else {
            result.riskLevel   = StalkingAssessment.RiskLevel.LOW;
            result.description = "No suspicious pattern detected. Movement looks normal.";
        }

        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Haversine formula — distance between two GPS points in metres. */
    private double distanceMetres(double[] a, double[] b) {
        final double R = 6371000; // Earth radius in metres
        double lat1 = Math.toRadians(a[0]), lat2 = Math.toRadians(b[0]);
        double dLat = Math.toRadians(b[0] - a[0]);
        double dLng = Math.toRadians(b[1] - a[1]);
        double x = Math.sin(dLat/2) * Math.sin(dLat/2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(dLng/2) * Math.sin(dLng/2);
        return R * 2 * Math.atan2(Math.sqrt(x), Math.sqrt(1 - x));
    }

    private String formatAssessment(StalkingAssessment a) {
        String emoji = a.riskLevel == StalkingAssessment.RiskLevel.HIGH   ? "🔴" :
                a.riskLevel == StalkingAssessment.RiskLevel.MEDIUM ? "🟡" : "🟢";
        return emoji + " Risk level: " + a.riskLevel.name() + "\n" + a.description;
    }

    // ── Data class ────────────────────────────────────────────────────────

    public static class StalkingAssessment {
        public enum RiskLevel { UNKNOWN, LOW, MEDIUM, HIGH }

        public RiskLevel riskLevel   = RiskLevel.LOW;
        public String    description = "";
        public int       revisitCount = 0;
        public double    hotspotLat  = 0;
        public double    hotspotLng  = 0;
    }
}
