package com.suraksha.ai.agent;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import android.location.Location;
import android.location.LocationManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * NearbyGuardianAgent — finds nearest safety resources using OpenStreetMap.
 *
 * ── WHY OPENSTREETMAP ─────────────────────────────────────────────────────
 *  Google Places requires billing (₹1,000 prepayment).
 *  OpenStreetMap Overpass API is 100% free — no key, no account, no billing.
 *  Same data quality, works anywhere in the world including all Indian cities.
 *
 * ── WHAT IT FINDS ─────────────────────────────────────────────────────────
 *  👮 Police stations    🏥 Hospitals
 *  💊 Pharmacies         🚒 Fire stations
 *  🏦 Banks/ATMs         🏪 24hr shops (safe to enter)
 */
public class NearbyGuardianAgent extends BaseAgent {

    private static final String TAG = "NearbyGuardianAgent";

    // Overpass API — free, no key needed
    private static final String OVERPASS_URL = "https://overpass-api.de/api/interpreter";
    private static final int    RADIUS_M     = 2000; // 2 km
    private static final int    MAX_RESULTS  = 3;

    public NearbyGuardianAgent(Context context) {
        super(context);
    }

    @Override public String getName()  { return "Nearby Guardian Agent"; }
    @Override public String getEmoji() { return "🏥"; }

    // ── Chat entry point ──────────────────────────────────────────────────

    @Override
    public void handleMessage(String userMessage, ChatCallback callback) {
        String lower = userMessage.toLowerCase();

        double[] loc = getLastLocation();
        double lat = loc[0];
        double lng = loc[1];

        if (lower.contains("hospital") || lower.contains("doctor")
                || lower.contains("medical") || lower.contains("hurt")
                || lower.contains("injured") || lower.contains("ambulance")) {
            callback.onAgentMessage(getName(), getEmoji(), "Finding nearest hospital 🏥...");
            searchNearby("amenity", "hospital", "🏥", "Hospital", lat, lng, callback);

        } else if (lower.contains("pharmacy") || lower.contains("medicine")
                || lower.contains("chemist")) {
            callback.onAgentMessage(getName(), getEmoji(), "Finding nearest pharmacy 💊...");
            searchNearby("amenity", "pharmacy", "💊", "Pharmacy", lat, lng, callback);

        } else if (lower.contains("fire") || lower.contains("fire station")) {
            callback.onAgentMessage(getName(), getEmoji(), "Finding nearest fire station 🚒...");
            searchNearby("amenity", "fire_station", "🚒", "Fire Station", lat, lng, callback);

        } else if (lower.contains("atm") || lower.contains("bank")
                || lower.contains("cash")) {
            callback.onAgentMessage(getName(), getEmoji(), "Finding nearest ATM 🏦...");
            searchNearby("amenity", "atm", "🏦", "ATM", lat, lng, callback);

        } else {
            // Default: search police + hospital together
            callback.onAgentMessage(getName(), getEmoji(),
                    "Finding all nearby safety resources...");
            searchAllTypes(lat, lng, callback);
        }
    }

    // ── Autonomous trigger ────────────────────────────────────────────────

    @Override
    public void onAutonomousTrigger(ThreatContext ctx) {
        double[] loc = getLastLocation();
        double lat = ctx.latitude  != 0 ? ctx.latitude  : loc[0];
        double lng = ctx.longitude != 0 ? ctx.longitude : loc[1];
        postToChatAsAgent("🏥 Finding nearest safe places for you...");
        searchAllTypes(lat, lng, chatCallback);
    }

    // ── Search all types ──────────────────────────────────────────────────

    private void searchAllTypes(double lat, double lng, ChatCallback callback) {
        if (callback == null) return;
        // Search police and hospital — most critical in emergency
        searchNearby("amenity", "police",   "👮", "Police Station", lat, lng, callback);
        searchNearby("amenity", "hospital", "🏥", "Hospital",       lat, lng, callback);
    }

    // ── Overpass API query ────────────────────────────────────────────────

    private void searchNearby(String key, String value, String emoji,
                              String label, double lat, double lng,
                              ChatCallback callback) {
        new Thread(() -> {
            try {
                // No location available — open Google Maps as fallback
                if (lat == 0.0 && lng == 0.0) {
                    fallbackToMaps(value, label, emoji, lat, lng, callback);
                    return;
                }

                // Overpass QL query — finds nodes + ways within radius
                String query = "[out:json][timeout:10];\n"
                        + "(\n"
                        + "  node[\"" + key + "\"=\"" + value + "\"]"
                        + "(around:" + RADIUS_M + "," + lat + "," + lng + ");\n"
                        + "  way[\"" + key + "\"=\"" + value + "\"]"
                        + "(around:" + RADIUS_M + "," + lat + "," + lng + ");\n"
                        + ");\n"
                        + "out center " + MAX_RESULTS + ";";

                HttpURLConnection conn =
                        (HttpURLConnection) new URL(OVERPASS_URL).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type",
                        "application/x-www-form-urlencoded");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(12000);

                String postData = "data=" + java.net.URLEncoder.encode(query, "UTF-8");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(postData.getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                if (code != 200) {
                    Log.e(TAG, "Overpass error: " + code);
                    fallbackToMaps(value, label, emoji, lat, lng, callback);
                    return;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }

                JSONObject root     = new JSONObject(sb.toString());
                JSONArray  elements = root.getJSONArray("elements");

                if (elements.length() == 0) {
                    callback.onAgentMessage(getName(), getEmoji(),
                            emoji + " No " + label + " found within 2km.\n"
                                    + "Tap to search: https://www.google.com/maps/search/"
                                    + value + "/@" + lat + "," + lng + ",15z");
                    return;
                }

                StringBuilder reply = new StringBuilder();
                reply.append(emoji).append(" **Nearest ").append(label).append(":**\n\n");

                int count = Math.min(MAX_RESULTS, elements.length());
                for (int i = 0; i < count; i++) {
                    JSONObject el = elements.getJSONObject(i);

                    // Name — OSM nodes have a "tags" object
                    String name = "Unknown";
                    if (el.has("tags")) {
                        JSONObject tags = el.getJSONObject("tags");
                        if (tags.has("name")) name = tags.getString("name");
                        else if (tags.has("name:en")) name = tags.getString("name:en");
                    }

                    // Location — node has lat/lon directly; way has "center"
                    double pLat, pLng;
                    if (el.has("center")) {
                        pLat = el.getJSONObject("center").getDouble("lat");
                        pLng = el.getJSONObject("center").getDouble("lon");
                    } else {
                        pLat = el.optDouble("lat", lat);
                        pLng = el.optDouble("lon", lng);
                    }

                    double dist = distanceMetres(lat, lng, pLat, pLng);
                    int    walk = Math.max(1, (int)(dist / 80));

                    reply.append(i + 1).append(". **").append(name).append("**\n");
                    reply.append("   📍 ").append((int) dist).append("m away");
                    reply.append(" (~").append(walk).append(" min walk)\n");
                    reply.append("   🗺️ https://maps.google.com/?q=")
                            .append(pLat).append(",").append(pLng).append("\n\n");
                }

                // Add emergency numbers at the bottom
                reply.append(getEmergencyNumbers(value));

                callback.onAgentMessage(getName(), getEmoji(), reply.toString().trim());

            } catch (Exception e) {
                Log.e(TAG, "Overpass search failed for " + value + ": " + e.getMessage());
                fallbackToMaps(value, label, emoji, lat, lng, callback);
            }
        }, "NearbySearch_" + value).start();
    }

    // ── Fallback: open Google Maps ────────────────────────────────────────

    private void fallbackToMaps(String value, String label, String emoji,
                                double lat, double lng, ChatCallback callback) {
        String mapsUrl = lat != 0
                ? "https://www.google.com/maps/search/" + value
                + "/@" + lat + "," + lng + ",15z"
                : "https://www.google.com/maps/search/" + value + "/";
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            callback.onAgentMessage(getName(), getEmoji(),
                    emoji + " Opening " + label + " search in Google Maps.");
        } catch (Exception e) {
            callback.onAgentMessage(getName(), getEmoji(),
                    emoji + " Search for '" + label + "' in Google Maps near you.\n\n"
                            + getEmergencyNumbers(value));
        }
    }

    // ── Emergency numbers by category ────────────────────────────────────

    private String getEmergencyNumbers(String type) {
        switch (type) {
            case "police":
                return "📞 Emergency: 100  |  Women Helpline: 1091";
            case "hospital":
                return "📞 Ambulance: 108  |  Emergency: 112";
            case "fire_station":
                return "📞 Fire: 101  |  Emergency: 112";
            default:
                return "📞 Emergency: 112";
        }
    }

    // ── Get last known GPS location ─────────────────────────────────────

    private double[] getLastLocation() {
        try {
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
            Log.e(TAG, "Could not get location: " + e.getMessage());
            return new double[]{0, 0};
        }
    }

    // ── Haversine distance formula ────────────────────────────────────────

    private double distanceMetres(double lat1, double lng1,
                                  double lat2, double lng2) {
        final double R    = 6371000;
        double       dLat = Math.toRadians(lat2 - lat1);
        double       dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}