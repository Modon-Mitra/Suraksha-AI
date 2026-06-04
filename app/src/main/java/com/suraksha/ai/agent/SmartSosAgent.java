package com.suraksha.ai.agent;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.util.Log;

import android.telephony.SmsManager;

import androidx.core.content.ContextCompat;

import com.suraksha.ai.model.EmergencyContact;
import com.suraksha.ai.utils.FirebaseLocationHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * SmartSosAgent — sends intelligent, context-rich emergency alerts.
 *
 * Richer than a plain SMS: includes live map URL, battery level,
 * evidence audio link (when EvidenceCollectionAgent is running),
 * and a timestamp. Sent to ALL trusted contacts simultaneously.
 */
public class SmartSosAgent extends BaseAgent {

    private static final String TAG = "SmartSosAgent";


    private boolean alertAlreadySent = false;

    // ── Constructor ───────────────────────────────────────────────────────

    public SmartSosAgent(Context context) {
        super(context);
    }

    // ── BaseAgent identity ────────────────────────────────────────────────

    @Override public String getName()  { return "Smart SOS Agent"; }
    @Override public String getEmoji() { return "🚨"; }

    // ── Chat entry point ──────────────────────────────────────────────────

    @Override
    public void handleMessage(String userMessage, ChatCallback callback) {
        String lower = userMessage.toLowerCase();

        if (lower.contains("cancel") || lower.contains("stop") || lower.contains("false alarm")) {
            alertAlreadySent = false;
            callback.onAgentMessage(getName(), getEmoji(),
                    "SOS cancelled. Contacts will not be alerted. Stay safe! 💚");
            return;
        }

        if (lower.contains("sos") || lower.contains("help")
                || lower.contains("emergency") || lower.contains("send alert")) {
            callback.onAgentMessage(getName(), getEmoji(),
                    "Sending emergency alert to your contacts now...");
            double[] loc = getFreshLocation();
            sendAlert(loc[0], loc[1], null);
            return;
        }

        if (lower.contains("status")) {
            List<EmergencyContact> contacts = prefs.getContacts();
            callback.onAgentMessage(getName(), getEmoji(),
                    "Ready to alert " + contacts.size() + " contact(s).\n"
                            + "Alert sent this session: " + alertAlreadySent);
            return;
        }

        callback.onAgentMessage(getName(), getEmoji(),
                "I send emergency alerts with your live location, battery level, "
                        + "and audio evidence to all your trusted contacts. Say 'send alert' to trigger.");
    }

    // ── Autonomous trigger ────────────────────────────────────────────────

    @Override
    public void onAutonomousTrigger(ThreatContext ctx) {
        // Use passed coordinates if present, otherwise fetch fresh GPS
        double lat = ctx.latitude;
        double lng = ctx.longitude;
        if (lat == 0.0 && lng == 0.0) {
            double[] loc = getFreshLocation();
            lat = loc[0];
            lng = loc[1];
        }

        if (ctx.threatLevel == ThreatContext.ThreatLevel.HIGH) {
            // Full emergency alert
            postToChatAsAgent("🚨 Sending emergency alert to your contacts...");
            sendAlert(lat, lng, null);

        } else if (ctx.triggerReason != null
                && (ctx.triggerReason.contains("location share")
                || ctx.triggerReason.contains("Manual location")
                || ctx.triggerReason.contains("check-in"))) {
            // Manual "share my location" — allow repeat sends
            alertAlreadySent = false;
            sendLocationShare(lat, lng,
                    ctx.triggerReason.contains("check-in"));
        }
    }

    // ── Location-share (lighter than full SOS) ────────────────────────────

    private void sendLocationShare(double lat, double lng, boolean safeCheckIn) {
        List<EmergencyContact> contacts = prefs.getContacts();
        if (contacts.isEmpty()) {
            postToChatAsAgent("⚠️ No trusted contacts saved! Add them in Settings.");
            return;
        }

        // SMS permission check
        if (ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            postToChatAsAgent("⚠️ SMS permission not granted.\n"
                    + "Please allow SMS in Settings → Apps → Suraksha AI → Permissions.");
            return;
        }

        String userName = prefs.getMyName().isEmpty() ? "I" : prefs.getMyName();

        StringBuilder msg = new StringBuilder();
        if (safeCheckIn) {
            msg.append("✅ ").append(userName)
                    .append(" has reached safely.\n\n");
        } else {
            msg.append("📍 ").append(userName)
                    .append(" is sharing their live location with you.\n\n");
        }
        if (lat != 0.0 || lng != 0.0) {
            msg.append("Location: https://maps.google.com/?q=")
                    .append(lat).append(",").append(lng).append("\n");
        }
        msg.append("\nSent by Suraksha AI safety app.");

        String finalMsg = msg.toString();
        SmsManager smsManager = SmsManager.getDefault();
        int sent = 0;
        for (EmergencyContact contact : contacts) {
            try {
                ArrayList<String> parts = smsManager.divideMessage(finalMsg);
                if (parts.size() == 1) {
                    smsManager.sendTextMessage(contact.phone, null, finalMsg, null, null);
                } else {
                    smsManager.sendMultipartTextMessage(contact.phone, null, parts, null, null);
                }
                sent++;
                Log.d(TAG, "Location shared with " + contact.name);
            } catch (Exception e) {
                Log.e(TAG, "Failed to send to " + contact.name, e);
            }
        }

        // Upload to Firebase for the live web dashboard
        if (lat != 0.0 || lng != 0.0) {
            FirebaseLocationHelper.uploadLocation(
                    prefs.getUserId(), lat, lng,
                    safeCheckIn ? "SAFE" : "SHARING");
        }

        postToChatAsAgent("✅ Location " + (safeCheckIn ? "check-in" : "")
                + " sent to " + sent + " contact(s).");
    }

    // ── Fresh GPS location ────────────────────────────────────────────────

    private double[] getFreshLocation() {
        try {
            if (ContextCompat.checkSelfPermission(context,
                    android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                return new double[]{0, 0};
            }
            LocationManager lm = (LocationManager)
                    context.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return new double[]{0, 0};

            Location best = null;
            for (String provider : new String[]{
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER}) {
                try {
                    Location l = lm.getLastKnownLocation(provider);
                    if (l != null && (best == null
                            || l.getAccuracy() < best.getAccuracy())) {
                        best = l;
                    }
                } catch (SecurityException ignored) {}
            }
            return best != null
                    ? new double[]{best.getLatitude(), best.getLongitude()}
                    : new double[]{0, 0};
        } catch (Exception e) {
            Log.e(TAG, "Location fetch failed: " + e.getMessage());
            return new double[]{0, 0};
        }
    }

    // ── Core SOS logic ────────────────────────────────────────────────────

    /**
     * Builds and sends a rich SOS message to all trusted contacts.
     *
     * @param lat        Current latitude  (0.0 if unknown)
     * @param lng        Current longitude (0.0 if unknown)
     * @param audioUrl   Firebase Storage URL of evidence clip (null if none yet)
     */
    public void sendAlert(double lat, double lng, String audioUrl) {
        if (alertAlreadySent) {
            Log.d(TAG, "Alert already sent this session — skipping duplicate");
            return;
        }
        alertAlreadySent = true;

        List<EmergencyContact> contacts = prefs.getContacts();
        if (contacts.isEmpty()) {
            postToChatAsAgent("⚠️ No emergency contacts saved! "
                    + "Please add contacts in Settings.");
            return;
        }

        // SMS permission check
        if (ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            postToChatAsAgent("⚠️ SMS permission not granted.\n"
                    + "Please allow SMS in Settings → Apps → Suraksha AI → Permissions, "
                    + "then try again.");
            return;
        }

        // If no coordinates supplied, fetch fresh GPS now
        if (lat == 0.0 && lng == 0.0) {
            double[] loc = getFreshLocation();
            lat = loc[0];
            lng = loc[1];
        }

        String userName   = prefs.getMyName().isEmpty() ? "Someone" : prefs.getMyName();
        int    battery    = getBatteryLevel();
        String timestamp  = new SimpleDateFormat(
                "dd MMM yyyy, hh:mm a", Locale.getDefault()).format(new Date());

        // Build the SMS message
        StringBuilder msg = new StringBuilder();
        msg.append("🚨 EMERGENCY — ").append(userName).append(" needs help!\n\n");

        if (lat != 0.0 || lng != 0.0) {
            msg.append("📍 Location: https://maps.google.com/?q=")
                    .append(lat).append(",").append(lng).append("\n");
        }

        msg.append("🔋 Battery: ").append(battery).append("%\n");

        if (audioUrl != null && !audioUrl.isEmpty()) {
            msg.append("🎙️ Audio: ").append(audioUrl).append("\n");
        }

        msg.append("⏱️ Time: ").append(timestamp).append("\n");
        msg.append("\nSent by Suraksha AI safety app.");

        String finalMsg = msg.toString();
        Log.i(TAG, "Sending SOS to " + contacts.size() + " contacts");

        // Send SMS to all contacts directly via SmsManager
        SmsManager smsManager = SmsManager.getDefault();
        for (EmergencyContact contact : contacts) {
            try {
                ArrayList<String> parts = smsManager.divideMessage(finalMsg);
                if (parts.size() == 1) {
                    smsManager.sendTextMessage(contact.phone, null, finalMsg, null, null);
                } else {
                    smsManager.sendMultipartTextMessage(contact.phone, null, parts, null, null);
                }
                Log.d(TAG, "SOS SMS sent to " + contact.name);
            } catch (Exception e) {
                Log.e(TAG, "Failed to send SMS to " + contact.name, e);
            }
        }

        // Push to Firebase so contacts can see it on the web dashboard
        if (lat != 0.0 || lng != 0.0) {
            FirebaseLocationHelper.uploadLocation(
                    prefs.getUserId(), lat, lng, "SOS_ACTIVE");
        }

        postToChatAsAgent("✅ Emergency alert sent to " + contacts.size()
                + " contact(s) with your live location and battery level.");

        Log.i(TAG, "SOS alert sent successfully");
    }

    /** Resets so a new alert can be sent in a fresh emergency. */
    public void resetAlertState() {
        alertAlreadySent = false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private int getBatteryLevel() {
        try {
            BatteryManager bm = (BatteryManager) context.getSystemService(
                    Context.BATTERY_SERVICE);
            return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Exception e) {
            return -1;
        }
    }
}