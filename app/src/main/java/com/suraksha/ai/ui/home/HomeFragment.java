package com.suraksha.ai.ui.home;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.suraksha.ai.R;
import com.suraksha.ai.ble.BleDevicePickerDialog;
import com.suraksha.ai.ble.BleHeartRateManager;
import com.suraksha.ai.ble.CameraHeartRateAnalyzer;
import com.suraksha.ai.databinding.FragmentHomeBinding;
import com.suraksha.ai.har.HumanActivityRecognizer;
import com.suraksha.ai.model.SafetyStatus;
import com.suraksha.ai.service.MonitoringService;
import com.suraksha.ai.ui.MainActivity;
import com.suraksha.ai.ui.SurakshaAgentChatActivity;
import com.suraksha.ai.utils.PrefsManager;

public class HomeFragment extends Fragment {

    private FragmentHomeBinding binding;

    // BLE
    private BleHeartRateManager bleManager;
    private BleDevicePickerDialog picker;

    // Camera PPG
    private CameraHeartRateAnalyzer cameraAnalyzer;
    private boolean cameraRunning = false;

    // =====================================================
    // SENSOR BROADCAST RECEIVER
    // =====================================================

    private final BroadcastReceiver readingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateUiFromBroadcast(intent);
        }
    };
    private final BroadcastReceiver activityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String actName = intent.getStringExtra("activity");
            float conf = intent.getFloatExtra("confidence", 0f);
            if (actName == null) return;
            updateActivityCard(actName, conf);
        }
    };

    // =====================================================
    // LIFECYCLE
    // =====================================================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setupMonitoringToggle();
        setupSosButton();
        setupBleButton();
        setupCameraButton();
        setupSurakshaFab();          // ← Suraksha agent button

        boolean isActive = new PrefsManager(requireContext()).isMonitoringActive();
        updateMonitoringStatus(isActive);
        // ─────────────────────────────────────────────────────────────────────────
//  ADD THESE LINES inside HomeFragment.onViewCreated(), after the existing
//  view setup (findViewByIds, click listeners, etc.)
// ─────────────────────────────────────────────────────────────────────────

// 1. Grab references
        ImageView ivShield = view.findViewById(R.id.iv_shield_logo);
        TextView tvAppName = view.findViewById(R.id.tv_app_name);

// 2. Helper lambda: animate shield (scale pulse + glow), then toggle panel
        Runnable togglePanel = () -> {
            MainActivity activity = (MainActivity) requireActivity();

            // ── Scale-pulse animation on the shield icon ──────────────────────────
            // Grows to 1.25× then springs back to 1× while the panel slides open.
            android.animation.AnimatorSet pulse = new android.animation.AnimatorSet();
            android.animation.ObjectAnimator scaleUpX   = android.animation.ObjectAnimator.ofFloat(ivShield, "scaleX", 1f, 1.30f, 1f);
            android.animation.ObjectAnimator scaleUpY   = android.animation.ObjectAnimator.ofFloat(ivShield, "scaleY", 1f, 1.30f, 1f);
            android.animation.ObjectAnimator alphaFlash = android.animation.ObjectAnimator.ofFloat(ivShield, "alpha", 1f, 0.6f, 1f);
            pulse.playTogether(scaleUpX, scaleUpY, alphaFlash);
            pulse.setDuration(300);
            pulse.setInterpolator(new android.view.animation.OvershootInterpolator(2f));
            pulse.start();

            // ── Toggle the panel AFTER the pulse begins ───────────────────────────
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(activity::toggleNavPanel, 80);
        };

// 3. Attach to both the shield icon and the app-name label
        if (ivShield  != null) ivShield.setOnClickListener(v -> togglePanel.run());
        if (tvAppName != null) tvAppName.setOnClickListener(v -> togglePanel.run());

// ─────────────────────────────────────────────────────────────────────────
//  ALSO: remove / comment-out the old BottomNavigationView import and any
//  code in HomeFragment that references bottom_nav or BottomNavigationView.
// ─────────────────────────────────────────────────────────────────────────

    }

    @Override
    public void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(requireContext())
                .registerReceiver(readingReceiver,
                        new IntentFilter(MonitoringService.ACTION_READING));

        LocalBroadcastManager.getInstance(requireContext())
                .registerReceiver(activityReceiver,
                        new IntentFilter(HumanActivityRecognizer.ACTION_ACTIVITY));
    }
    @Override
    public void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(requireContext())
                .unregisterReceiver(readingReceiver);
        LocalBroadcastManager.getInstance(requireContext())
                .unregisterReceiver(activityReceiver);
        stopCamera();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (bleManager != null) bleManager.disconnect();
        stopCamera();
        binding = null;
    }

    // =====================================================
    // MONITORING TOGGLE
    // =====================================================

    private void setupMonitoringToggle() {
        binding.btnToggleMonitoring.setOnClickListener(v -> {
            PrefsManager prefs = new PrefsManager(requireContext());
            if (prefs.isMonitoringActive()) {
                Intent i = new Intent(requireContext(), MonitoringService.class);
                i.putExtra("cmd", MonitoringService.CMD_STOP);
                requireContext().startService(i);
                prefs.setMonitoringActive(false);
                updateMonitoringStatus(false);
            } else {
                startMonitoringService();
                updateMonitoringStatus(true);
            }
        });
    }

    private void updateActivityCard(String actName, float conf) {
        if (binding == null) return;
        String emoji, label;
        switch (actName) {
            case "WALKING":  emoji = "🚶"; label = "Walking";      break;
            case "RUNNING":  emoji = "🏃"; label = "Running";      break;
            case "CYCLING":  emoji = "🚴"; label = "Cycling";      break;
            case "VEHICLE":  emoji = "🚗"; label = "In a vehicle"; break;
            case "STILL":    emoji = "🧍"; label = "Stationary";   break;
            case "FALLING":  emoji = "⚠️"; label = "Fall Detected!"; break;
            default:         emoji = "❓"; label = "Detecting..."; break;
        }
        binding.tvActivityEmoji.setText(emoji);
        binding.tvActivityLabel.setText(label);
        binding.tvActivityConfidence.setText(
                String.format("Confidence: %d%%", (int)(conf * 100)));
    }
    private void startMonitoringService() {
        Intent i = new Intent(requireContext(), MonitoringService.class);
        i.putExtra("cmd", MonitoringService.CMD_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            requireContext().startForegroundService(i);
        else
            requireContext().startService(i);
        new PrefsManager(requireContext()).setMonitoringActive(true);
    }

    // =====================================================
    // SOS
    // =====================================================

    private void setupSosButton() {
        binding.btnSos.setOnClickListener(v -> {
            Intent i = new Intent(requireContext(), MonitoringService.class);
            i.putExtra("cmd", MonitoringService.CMD_MANUAL_SOS);
            ContextCompat.startForegroundService(requireContext(), i);
        });
    }

    // =====================================================
    // SURAKSHA AGENT FAB
    // =====================================================

    private void setupSurakshaFab() {
        binding.fabSuraksha.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), SurakshaAgentChatActivity.class);
            startActivity(intent);
        });
    }

    // =====================================================
    // BLE — Connect smartwatch
    // ID in layout: btn_connect_watch → binding.btnConnectWatch
    // =====================================================

    private void setupBleButton() {
        binding.btnConnectWatch.setOnClickListener(v -> {
            if (bleManager != null && bleManager.isConnected()) {
                bleManager.disconnect();
                updateBleStatus("○ No watch connected", false);
                return;
            }
            startBleScan();
        });
    }

    private void startBleScan() {
        if (!hasBlePermission()) {
            Toast.makeText(requireContext(),
                    "Allow 'Nearby devices' permission:\nSettings → Apps → Suraksha AI → Permissions",
                    Toast.LENGTH_LONG).show();
            return;
        }

        picker = new BleDevicePickerDialog(requireActivity(),
                device -> {
                    updateBleStatus("Connecting…", false);
                    bleManager.connect(device);
                });

        bleManager = new BleHeartRateManager(requireContext(),
                new BleHeartRateManager.BleCallback() {

                    @Override
                    public void onDeviceFound(BluetoothDevice device, String name, int rssi) {
                        if (picker != null) picker.addDevice(device, name, rssi);
                    }

                    @Override
                    public void onConnected(String deviceName) {
                        if (binding == null) return;
                        updateBleStatus("● " + deviceName, true);
                        Toast.makeText(requireContext(),
                                "Connected: " + deviceName, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onDisconnected() {
                        if (binding == null) return;
                        updateBleStatus("○ Disconnected", false);
                        Toast.makeText(requireContext(), "Watch disconnected", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onHeartRateReceived(int bpm) {
                        if (binding == null) return;
                        binding.tvHeartRate.setText(String.valueOf(bpm));
                        binding.tvHrStat.setText(String.valueOf(bpm));
                        binding.tvHrSource.setText("via Watch");
                    }

                    @Override
                    public void onScanFinished() {
                        if (picker != null) picker.setScanFinished();
                    }

                    @Override
                    public void onError(String message) {
                        if (binding == null) return;
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                    }
                });

        picker.show();
        bleManager.startScan();
    }

    private void updateBleStatus(String text, boolean connected) {
        if (binding == null) return;
        binding.tvBleStatus.setText(text);
        binding.tvBleStatus.setTextColor(requireContext().getColor(
                connected ? R.color.safe_green : R.color.text_secondary));
        binding.btnConnectWatch.setText(
                connected ? "✕ Disconnect watch" : "🔗 Connect Watch (BLE)");
    }

    // =====================================================
    // VITALS THRESHOLD CHECK
    // Compares a freshly measured heart rate (and stress) against the
    // user's saved thresholds and shows a warning if outside the safe band.
    // =====================================================

    private void checkVitalsAgainstThresholds(int bpm, String stress) {
        if (binding == null) return;

        PrefsManager prefs = new PrefsManager(requireContext());
        int high = prefs.getHrHighThreshold();
        int low  = prefs.getHrLowThreshold();

        StringBuilder warning = new StringBuilder();

        if (bpm > high) {
            warning.append("⚠️ Your heart rate (").append(bpm)
                    .append(" bpm) is ABOVE your high threshold of ")
                    .append(high).append(" bpm.\n");
        } else if (bpm < low) {
            warning.append("⚠️ Your heart rate (").append(bpm)
                    .append(" bpm) is BELOW your low threshold of ")
                    .append(low).append(" bpm.\n");
        }

        if ("High".equals(stress)) {
            warning.append("⚠️ Your stress level is HIGH.\n");
        }

        if (warning.length() > 0) {
            // Out of safe range — show a clear warning dialog with quick actions
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("⚠️ Vitals Alert")
                    .setMessage(warning.toString()
                            + "\nAre you okay? If you feel unsafe or unwell, "
                            + "I can help right away.")
                    .setPositiveButton("I'm fine", (d, w) -> d.dismiss())
                    .setNegativeButton("Get help", (d, w) -> {
                        // Open Suraksha chat so agents can assist
                        startActivity(new android.content.Intent(
                                requireContext(), SurakshaAgentChatActivity.class));
                    })
                    .setCancelable(false)
                    .show();
        } else {
            // Within safe range — gentle confirmation
            Toast.makeText(requireContext(),
                    "Heart rate: " + bpm + " bpm — within your safe range ✓",
                    Toast.LENGTH_SHORT).show();
        }
    }

    // =====================================================
    // CAMERA — PPG heart rate measurement
    // ID in layout: btn_camera_hr → binding.btnCameraHr
    // =====================================================

    private void setupCameraButton() {
        binding.btnCameraHr.setOnClickListener(v -> {
            if (cameraRunning) {
                stopCamera();
            } else {
                startCamera();
            }
        });
    }

    private void startCamera() {
        if (!hasCameraPermission()) {
            Toast.makeText(requireContext(),
                    "Camera permission required", Toast.LENGTH_SHORT).show();
            return;
        }

        cameraRunning = true;
        binding.btnCameraHr.setText("⏹ Stop measuring");
        binding.tvHrSource.setText("Place finger on camera…");

        cameraAnalyzer = new CameraHeartRateAnalyzer(requireContext(),
                new CameraHeartRateAnalyzer.Callback() {

                    @Override
                    public void onProgress(int secondsRemaining) {
                        if (binding == null) return;
                        binding.tvHrSource.setText("Measuring… " + secondsRemaining + "s");
                    }

                    @Override
                    public void onResult(int bpm) {
                        if (binding == null) return;
                        cameraRunning = false;
                        binding.btnCameraHr.setText("📷 Measure via Camera");

                        if (bpm == -1) {
                            binding.tvHrSource.setText("Phone sensor");
                            Toast.makeText(requireContext(),
                                    "Could not detect pulse — keep finger still on the camera lens",
                                    Toast.LENGTH_LONG).show();
                        } else {
                            binding.tvHeartRate.setText(String.valueOf(bpm));
                            binding.tvHrStat.setText(String.valueOf(bpm));
                            binding.tvHrSource.setText("via Camera");

                            // Show stress level from HRV
                            String stress = "--";
                            if (cameraAnalyzer != null) {
                                stress = cameraAnalyzer.getLastStressLevel();
                                int sdnn = cameraAnalyzer.getLastSdnnMs();
                                binding.tvStress.setText(stress);
                                binding.tvHrv.setText("HRV: " + sdnn + " ms");

                                int colour;
                                switch (stress) {
                                    case "Low":      colour = 0xFF4CAF50; break;
                                    case "High":     colour = 0xFFFF6B6B; break;
                                    case "Moderate": colour = 0xFFFFA726; break;
                                    default:         colour = 0xFFFFFFFF; break;
                                }
                                binding.tvStress.setTextColor(colour);
                            }

                            // ── Check BPM + stress against saved thresholds ──
                            checkVitalsAgainstThresholds(bpm, stress);
                        }
                    }

                    @Override
                    public void onError(String msg) {
                        if (binding == null) return;
                        cameraRunning = false;
                        binding.btnCameraHr.setText("📷 Measure via Camera");
                        binding.tvHrSource.setText("Phone sensor");
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show();
                    }
                });

        cameraAnalyzer.start();
    }

    private void stopCamera() {
        cameraRunning = false;
        if (cameraAnalyzer != null) {
            cameraAnalyzer.stop();
            cameraAnalyzer = null;
        }
        if (binding != null) {
            binding.btnCameraHr.setText("📷 Measure via Camera");
            binding.tvHrSource.setText("Phone sensor");
        }
    }

    // =====================================================
    // UPDATE UI FROM MonitoringService BROADCAST
    // =====================================================

    private void updateUiFromBroadcast(Intent intent) {
        if (binding == null) return;

        float hr      = intent.getFloatExtra("heartRate", 0f);
        String status = intent.getStringExtra("status");

        boolean bleActive    = bleManager != null && bleManager.isConnected();
        boolean cameraActive = cameraRunning;

        if (!bleActive && !cameraActive && hr > 0) {
            binding.tvHeartRate.setText(String.valueOf((int) hr));
            binding.tvHrStat.setText(String.valueOf((int) hr));
            binding.tvHrSource.setText("Phone sensor");
        }

        if (status == null) return;

        binding.tvStatus.setText(status);

        try {
            switch (SafetyStatus.valueOf(status)) {
                case SAFE:
                    binding.cardStatus.setCardBackgroundColor(
                            requireContext().getColor(R.color.safe_green_dim));
                    binding.tvStatus.setTextColor(
                            requireContext().getColor(R.color.safe_green));
                    break;
                case WARNING:
                    binding.cardStatus.setCardBackgroundColor(
                            requireContext().getColor(R.color.warning_yellow_dim));
                    binding.tvStatus.setTextColor(
                            requireContext().getColor(R.color.warning_yellow));
                    break;
                case DANGER:
                case SOS_ACTIVE:
                    binding.cardStatus.setCardBackgroundColor(
                            requireContext().getColor(R.color.danger_red_dim));
                    binding.tvStatus.setTextColor(
                            requireContext().getColor(R.color.danger_red));
                    break;
            }
        } catch (IllegalArgumentException ignored) {}
    }

    // =====================================================
    // MONITORING STATUS UI
    // =====================================================

    private void updateMonitoringStatus(boolean active) {
        if (binding == null) return;
        if (active) {
            binding.btnToggleMonitoring.setText("● ACTIVE");
            binding.btnToggleMonitoring.setBackgroundTintList(
                    requireContext().getColorStateList(R.color.safe_green));
            binding.btnToggleMonitoring.setTextColor(
                    requireContext().getColor(R.color.bg_dark));
            binding.tvMonitoringSub.setText("Monitoring your safety");
            binding.tvConnectedBadge.setText("● Connected");
            binding.tvConnectedBadge.setTextColor(
                    requireContext().getColor(R.color.safe_green));
        } else {
            binding.btnToggleMonitoring.setText("○ OFF");
            binding.btnToggleMonitoring.setBackgroundTintList(
                    requireContext().getColorStateList(R.color.danger_red));
            binding.btnToggleMonitoring.setTextColor(
                    requireContext().getColor(android.R.color.white));
            binding.tvMonitoringSub.setText("Tap to start monitoring");
            binding.tvConnectedBadge.setText("○ Not Monitoring");
            binding.tvConnectedBadge.setTextColor(
                    requireContext().getColor(R.color.danger_red));
        }
    }

    // =====================================================
    // PERMISSIONS
    // =====================================================

    private boolean hasBlePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }
}