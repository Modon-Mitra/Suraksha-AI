package com.suraksha.ai.utils;

import android.util.Log;

import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

public class FirebaseLocationHelper {

    private static final String TAG = "FirebaseLocation";

    // =========================
    // Upload location
    // =========================

    public static void uploadLocation(
            String userId,
            double lat,
            double lon,
            String status
    ) {

        // BUG FIX (defense-in-depth): Reject uploads when coordinates are (0,0).
        // This is the Java default for uninitialized doubles and indicates the app
        // does not yet have a real GPS fix.  Writing (0,0) to Firestore causes
        // contacts' tracking screens to show a location in the Gulf of Guinea
        // (0°N 0°E) instead of the user's actual position.
        if (lat == 0.0 && lon == 0.0) {
            Log.w(TAG, "Skipping upload — no GPS fix yet (lat/lon are 0,0)");
            return;
        }

        boolean hasGps = true; // guaranteed by the guard above

        FirebaseFirestore db = FirebaseFirestore.getInstance();

        Map<String, Object> data = new HashMap<>();
        data.put("userId", userId);
        data.put("latitude", lat);
        data.put("longitude", lon);
        data.put("hasGps", hasGps);
        data.put("status", status);
        data.put("timestamp", System.currentTimeMillis());

        db.collection("users")
                .document(userId)
                .set(data)
                .addOnSuccessListener(unused ->
                        Log.d(TAG, "Uploaded — hasGps:" + hasGps + " " + lat + "," + lon))
                .addOnFailureListener(e ->
                        Log.e(TAG, "Upload failed", e));
    }

    // =========================
    // Normalize to 10 digits
    // =========================

    private static String normalize(String phone) {

        // Strip everything except digits
        String digits = phone.replaceAll("[^0-9]", "");

        // If 12 digits starting with 91, strip country code
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        }

        // If 13 digits starting with 091, strip
        if (digits.length() == 13 && digits.startsWith("091")) {
            digits = digits.substring(3);
        }

        return digits; // always returns 10-digit number
    }

    // =========================
    // Register phone → userId
    // Saves under 10-digit key
    // so lookup always matches
    // =========================

    public static void registerPhone(
            String userId,
            String phone
    ) {

        if (phone == null || phone.isEmpty()) return;

        String tenDigit = normalize(phone);

        if (tenDigit.length() < 10) {
            Log.w(TAG, "Phone number too short: " + phone);
            return;
        }

        FirebaseFirestore db = FirebaseFirestore.getInstance();

        Map<String, Object> data = new HashMap<>();
        data.put("userId", userId);
        data.put("phone", tenDigit);

        // Save under 10-digit format — e.g. "9876543210"
        db.collection("phones")
                .document(tenDigit)
                .set(data)
                .addOnSuccessListener(unused ->
                        Log.d(TAG, "Phone registered: " + tenDigit))
                .addOnFailureListener(e ->
                        Log.e(TAG, "Phone registration failed", e));
    }

    // =========================
    // Look up userId by phone
    // Normalizes before lookup
    // so format doesn't matter
    // =========================

    public interface TrackingIdCallback {
        void onFound(String trackingId);
        void onNotFound();
    }

    public static void lookupByPhone(
            String phone,
            TrackingIdCallback callback
    ) {

        String tenDigit = normalize(phone);

        if (tenDigit.length() < 10) {
            callback.onNotFound();
            return;
        }

        FirebaseFirestore db = FirebaseFirestore.getInstance();

        // Always look up by 10-digit format — matches how registerPhone saves
        db.collection("phones")
                .document(tenDigit)
                .get()
                .addOnSuccessListener(doc -> {

                    if (doc.exists()) {
                        String trackingId = doc.getString("userId");
                        if (trackingId != null && !trackingId.isEmpty()) {
                            callback.onFound(trackingId);
                            return;
                        }
                    }

                    callback.onNotFound();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Lookup failed", e);
                    callback.onNotFound();
                });
    }
}