package com.suraksha.ai.ui.tracking;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import com.suraksha.ai.databinding.FragmentTrackingBinding;

public class TrackingFragment extends Fragment {

    private FragmentTrackingBinding binding;
    private FusedLocationProviderClient fusedLocation;
    private LocationCallback locationCallback;
    private WebView webView;
    private double currentLat = 0;
    private double currentLon = 0;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            ViewGroup container,
            Bundle savedInstanceState) {

        binding = FragmentTrackingBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public void onViewCreated(
            @NonNull View view,
            @Nullable Bundle savedInstanceState) {

        super.onViewCreated(view, savedInstanceState);

        webView = binding.webMap;
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);

        fusedLocation = LocationServices.getFusedLocationProviderClient(requireActivity());

        binding.btnShare.setOnClickListener(v -> {

            String mapLink = "https://maps.google.com/?q=" + currentLat + "," + currentLon;

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, "My Live Location:\n" + mapLink);
            startActivity(Intent.createChooser(shareIntent, "Share Location"));
        });

        startLocationUpdates();
    }

    private void startLocationUpdates() {

        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        LocationRequest request = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setMinUpdateIntervalMillis(3000)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {

                Location location = result.getLastLocation();
                if (location == null) return;

                currentLat = location.getLatitude();
                currentLon = location.getLongitude();

                binding.tvCoords.setText(currentLat + ", " + currentLon);
                binding.tvShare.setText("https://maps.google.com/?q=" + currentLat + "," + currentLon);
                loadMap(currentLat, currentLon);

                // NOTE: Firebase upload is intentionally removed from here.
                // MonitoringService handles all location uploads to Firestore.
                // Having two upload paths caused incorrect location data —
                // TrackingFragment was overwriting the correct MonitoringService
                // data with its own uploads, sometimes with the wrong userId.
            }
        };

        fusedLocation.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
    }

    private void loadMap(double lat, double lon) {

        String html =
                "<!DOCTYPE html><html><head>" +
                        "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                        "<link rel='stylesheet' href='https://unpkg.com/leaflet/dist/leaflet.css'/>" +
                        "<script src='https://unpkg.com/leaflet/dist/leaflet.js'></script>" +
                        "<style>html, body, #map { height:100%; margin:0; padding:0; }</style>" +
                        "</head><body><div id='map'></div><script>" +
                        "var map = L.map('map').setView([" + lat + "," + lon + "], 17);" +
                        "L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19}).addTo(map);" +
                        "L.marker([" + lat + "," + lon + "]).addTo(map).bindPopup('You are here').openPopup();" +
                        "</script></body></html>";

        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (fusedLocation != null && locationCallback != null) {
            fusedLocation.removeLocationUpdates(locationCallback);
        }
        binding = null;
    }
}