package com.suraksha.ai.ui.tracking;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.firebase.firestore.FirebaseFirestore;

import com.suraksha.ai.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LiveTrackingActivity
        extends AppCompatActivity
        implements OnMapReadyCallback {

    private GoogleMap map;
    private Marker marker;
    private FirebaseFirestore db;
    private String trackingId;
    private String contactName;

    private Double pendingLat = null;
    private Double pendingLon = null;

    private boolean hasShownLocation = false;

    // Timestamp TextView — shown below the map
    private TextView tvLastUpdated;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_tracking);

        trackingId  = getIntent().getStringExtra("trackingId");
        contactName = getIntent().getStringExtra("name");

        if (trackingId == null || trackingId.isEmpty()) {
            Toast.makeText(this, "Invalid Tracking ID", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        db = FirebaseFirestore.getInstance();

        // Find the last updated TextView in the layout
        tvLastUpdated = findViewById(R.id.tvLastUpdated);

        SupportMapFragment mapFragment =
                (SupportMapFragment) getSupportFragmentManager()
                        .findFragmentById(R.id.map);

        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        } else {
            Toast.makeText(this, "Map failed to load", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        listenForLocation();
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {

        map = googleMap;
        map.getUiSettings().setZoomControlsEnabled(true);

        if (pendingLat != null && pendingLon != null) {
            updateMarker(pendingLat, pendingLon);
        }
    }

    private void listenForLocation() {

        db.collection("users")
                .document(trackingId)
                .addSnapshotListener(this, (value, error) -> {

                    if (error != null) {
                        Toast.makeText(this,
                                "Connection error: " + error.getMessage(),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (value == null || !value.exists()) {
                        if (!hasShownLocation) {
                            Toast.makeText(this,
                                    "Waiting for " + (contactName != null ? contactName : "contact") +
                                            " to come online...",
                                    Toast.LENGTH_SHORT).show();
                        }
                        return;
                    }

                    Double lat    = value.getDouble("latitude");
                    Double lon    = value.getDouble("longitude");
                    Boolean hasGps = value.getBoolean("hasGps");
                    Long timestamp = value.getLong("timestamp");

                    // Document exists but no GPS fix yet
                    if (hasGps == null || !hasGps
                            || lat == null || lon == null
                            || (lat == 0.0 && lon == 0.0)) {

                        if (!hasShownLocation) {
                            Toast.makeText(this,
                                    (contactName != null ? contactName : "Contact") +
                                            " is online — waiting for their GPS fix...",
                                    Toast.LENGTH_SHORT).show();
                        }
                        return;
                    }

                    hasShownLocation = true;

                    // Update "Last updated" label
                    updateTimestamp(timestamp);

                    if (map == null) {
                        pendingLat = lat;
                        pendingLon = lon;
                    } else {
                        updateMarker(lat, lon);
                    }
                });
    }

    private void updateMarker(double lat, double lon) {

        LatLng pos = new LatLng(lat, lon);

        if (marker == null) {
            marker = map.addMarker(new MarkerOptions()
                    .position(pos)
                    .title(contactName != null ? contactName : "Live Location"));
        } else {
            marker.setPosition(pos);
        }

        map.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 16f));
    }

    /**
     * Updates the "Last updated" label below the map.
     * Converts the Unix millisecond timestamp from Firestore
     * into a human-readable "dd MMM yyyy, hh:mm:ss a" string.
     *
     * Examples:
     *   "Last updated: 18 May 2026, 09:32:14 AM"
     *   "Last updated: just now"  (if timestamp is within 10 seconds)
     */
    // Stale threshold — warn if location is older than 5 minutes
    private static final long STALE_THRESHOLD_MS = 5 * 60 * 1000;

    private void updateTimestamp(Long timestamp) {

        if (tvLastUpdated == null) return;

        tvLastUpdated.setVisibility(View.VISIBLE);

        if (timestamp == null) {
            tvLastUpdated.setText("⚠️ Last updated: unknown");
            tvLastUpdated.setTextColor(0xFFFF6B6B); // red
            return;
        }

        long now    = System.currentTimeMillis();
        long diffMs = now - timestamp;

        String timeStr;
        if (diffMs < 10_000) {
            timeStr = "just now";
        } else if (diffMs < 60_000) {
            timeStr = (diffMs / 1000) + "s ago";
        } else if (diffMs < 3_600_000) {
            timeStr = (diffMs / 60_000) + " min ago";
        } else {
            SimpleDateFormat sdf = new SimpleDateFormat(
                    "dd MMM yyyy, hh:mm a", Locale.getDefault());
            timeStr = sdf.format(new Date(timestamp));
        }

        // Warn visually if location is stale (> 5 minutes old)
        if (diffMs > STALE_THRESHOLD_MS) {
            tvLastUpdated.setText("⚠️ Location may be outdated — " + timeStr);
            tvLastUpdated.setTextColor(0xFFFF6B6B); // red warning
        } else {
            tvLastUpdated.setText("● Live — updated " + timeStr);
            tvLastUpdated.setTextColor(0xFF4CAF50); // green = fresh
        }
    }
}