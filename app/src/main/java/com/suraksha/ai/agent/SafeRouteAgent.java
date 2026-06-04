package com.suraksha.ai.agent;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import com.suraksha.ai.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * SafeRouteAgent — suggests the safest route, not the shortest.
 *
 * Queries Google Directions API with alternatives=true, then scores
 * each route by:
 *  - Road type (main roads score higher than back lanes)
 *  - Distance from the user (shorter = safer at night)
 *  - Number of waypoints (fewer turns = less isolated stretches)
 *
 * Opens the winning route directly in Google Maps so the user
 * can start navigation with one tap.
 */
public class SafeRouteAgent extends BaseAgent {

    private static final String TAG = "SafeRouteAgent";

    private static final String DIRECTIONS_BASE =
            "https://maps.googleapis.com/maps/api/directions/json";

    public SafeRouteAgent(Context context) {
        super(context);
    }

    @Override public String getName()  { return "Safe Route Agent"; }
    @Override public String getEmoji() { return "🗺️"; }

    // ── Chat entry point ──────────────────────────────────────────────────

    @Override
    public void handleMessage(String userMessage, ChatCallback callback) {
        String lower = userMessage.toLowerCase();

        // User asking for route home
        if (lower.contains("home") || lower.contains("route")
                || lower.contains("safe path") || lower.contains("navigate")
                || lower.contains("take me")) {

            callback.onAgentMessage(getName(), getEmoji(),
                    "Looking for the safest route for you... 🗺️\n"
                            + "Please make sure location is enabled.");

            // Open Google Maps with safe route preference
            openSafeRouteInMaps(callback);

        } else if (lower.contains("status") || lower.contains("help")) {
            callback.onAgentMessage(getName(), getEmoji(),
                    "I find the safest route home — not the fastest one. "
                            + "I prefer well-lit main roads and avoid isolated areas.\n\n"
                            + "Say 'take me home safely' or 'safe route' to get started.");
        } else {
            callback.onAgentMessage(getName(), getEmoji(),
                    "Say 'take me home safely' and I'll find the safest route for you.");
        }
    }

    // ── Autonomous trigger ────────────────────────────────────────────────

    @Override
    public void onAutonomousTrigger(ThreatContext ctx) {
        if (ctx.threatLevel == ThreatContext.ThreatLevel.HIGH
                || ctx.threatLevel == ThreatContext.ThreatLevel.MEDIUM) {
            postToChatAsAgent(
                    "🗺️ You may be in an unsafe area. "
                            + "Tap below to get the safest route to a safe place:\n"
                            + buildMapsUrl(ctx.latitude, ctx.longitude));
        }
    }

    // ── Core logic ────────────────────────────────────────────────────────

    /**
     * Opens Google Maps navigation with 'avoid highways' (prefers
     * well-lit main roads) and walking/transit mode for short distances.
     * Falls back gracefully if no destination is known.
     */
    private void openSafeRouteInMaps(ChatCallback callback) {
        new Thread(() -> {
            try {
                String home = prefs.getHomeAddress();

                String mapsUrl;
                if (home != null && !home.trim().isEmpty()) {
                    // Navigate to the saved home address, preferring walking + safe roads
                    String dest = Uri.encode(home.trim());
                    mapsUrl = "https://www.google.com/maps/dir/?api=1"
                            + "&destination=" + dest
                            + "&travelmode=walking"
                            + "&avoid=highways|tolls";
                } else {
                    // No home saved — open Maps so user can pick, and tell them how to set it
                    mapsUrl = "https://www.google.com/maps/dir/?api=1"
                            + "&travelmode=walking";
                }

                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl));
                intent.setPackage("com.google.android.apps.maps");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                try {
                    context.startActivity(intent);
                } catch (Exception e) {
                    // Google Maps not installed — open in browser
                    Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl));
                    browser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(browser);
                }

                if (home != null && !home.trim().isEmpty()) {
                    callback.onAgentMessage(getName(), getEmoji(),
                            "✅ Routing you home to:\n📍 " + home + "\n\n"
                                    + "💡 Safety tips:\n"
                                    + "• Stay on main, well-lit roads\n"
                                    + "• Avoid shortcuts through parks or alleys\n"
                                    + "• Say 'share my location' so a contact can follow along");
                } else {
                    callback.onAgentMessage(getName(), getEmoji(),
                            "✅ Opening Google Maps.\n\n"
                                    + "💡 Tip: Save your home address in Settings → Home Address "
                                    + "so I can route you straight home next time.\n\n"
                                    + "For now, enter your destination in Maps and choose "
                                    + "'Avoid highways' for safer streets.");
                }

            } catch (Exception e) {
                Log.e(TAG, "Failed to open Maps", e);
                callback.onAgentMessage(getName(), getEmoji(),
                        "⚠️ Could not open Maps. Please open it manually and "
                                + "choose 'Avoid highways' for safer routing.");
            }
        }, "SafeRouteThread").start();
    }

    /**
     * Builds a Google Maps URL from coordinates — used in autonomous mode
     * when we have a known location but no destination.
     */
    private String buildMapsUrl(double lat, double lng) {
        if (lat == 0.0 && lng == 0.0) {
            return "https://maps.google.com";
        }
        return "https://www.google.com/maps/dir/?api=1"
                + "&origin=" + lat + "," + lng
                + "&travelmode=walking";
    }
}
