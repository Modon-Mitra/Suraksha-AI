package com.suraksha.ai.ble;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.UUID;

public class BleHeartRateManager {

    private static final String TAG = "BleHR";

    public static final UUID SERVICE_HEART_RATE          = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_HEART_RATE_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    public static final UUID DESCRIPTOR_CLIENT_CONFIG    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final long SCAN_TIMEOUT_MS = 20_000;

    public interface BleCallback {
        void onDeviceFound(BluetoothDevice device, String name, int rssi);
        void onConnected(String deviceName);
        void onDisconnected();
        void onHeartRateReceived(int bpm);
        void onScanFinished();
        void onError(String message);
    }

    private final Context ctx;
    private final BleCallback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt gatt;

    private boolean scanning  = false;
    private boolean connected = false;

    public BleHeartRateManager(Context context, BleCallback callback) {
        this.ctx      = context.getApplicationContext();
        this.callback = callback;
        BluetoothManager bm =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) bluetoothAdapter = bm.getAdapter();
    }

    public void startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            post(() -> callback.onError("Please enable Bluetooth and try again"));
            return;
        }
        if (!hasScanPermission()) {
            post(() -> callback.onError(
                    "Bluetooth permission missing.\nGo to Settings → Apps → Suraksha AI → Permissions → Nearby devices → Allow"));
            return;
        }
        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bleScanner == null) {
            post(() -> callback.onError("BLE scanner not available"));
            return;
        }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            bleScanner.startScan(null, settings, scanCallback);
            scanning = true;
            Log.d(TAG, "Scan started — ALL BLE devices");
        } catch (SecurityException e) {
            post(() -> callback.onError("Bluetooth scan permission denied"));
            return;
        }
        mainHandler.postDelayed(() -> {
            stopScan();
            post(callback::onScanFinished);
        }, SCAN_TIMEOUT_MS);
    }

    public void stopScan() {
        if (!scanning || bleScanner == null) return;
        try { bleScanner.stopScan(scanCallback); } catch (SecurityException ignored) {}
        scanning = false;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            int rssi = result.getRssi();
            String name = "Unknown Device";
            try {
                if (result.getScanRecord() != null && result.getScanRecord().getDeviceName() != null) {
                    name = result.getScanRecord().getDeviceName();
                } else if (hasConnectPermission() && device.getName() != null) {
                    name = device.getName();
                }
            } catch (SecurityException ignored) {}
            // Skip unnamed weak-signal devices (beacons, earbuds)
            if (name.equals("Unknown Device") && rssi < -85) return;
            final String finalName = name;
            // FIX: always post to main thread — scan fires on background thread
            post(() -> callback.onDeviceFound(device, finalName, rssi));
        }
        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            String msg = errorCode == 2
                    ? "Scan failed — toggle Bluetooth off/on and try again"
                    : "BLE scan failed (code " + errorCode + ")";
            post(() -> callback.onError(msg));
        }
    };

    public void connect(BluetoothDevice device) {
        if (!hasConnectPermission()) {
            post(() -> callback.onError("Bluetooth connect permission not granted"));
            return;
        }
        stopScan();
        try {
            gatt = device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            post(() -> callback.onError("Bluetooth connect permission denied"));
        }
    }

    public void disconnect() {
        connected = false;
        if (gatt == null) return;
        try { gatt.disconnect(); gatt.close(); } catch (SecurityException ignored) {}
        gatt = null;
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                try { g.discoverServices(); }
                catch (SecurityException e) { post(() -> callback.onError("Service discovery denied")); }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                post(callback::onDisconnected);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                post(() -> callback.onError("Service discovery failed — try reconnecting"));
                return;
            }
            // Log all services so you can debug proprietary watches
            for (BluetoothGattService s : g.getServices()) {
                Log.d(TAG, "Service: " + s.getUuid());
                for (BluetoothGattCharacteristic c : s.getCharacteristics())
                    Log.d(TAG, "  Char: " + c.getUuid());
            }
            String deviceName = getDeviceName(g);
            BluetoothGattService hrService = g.getService(SERVICE_HEART_RATE);
            if (hrService == null) {
                // Proprietary protocol — connected but no standard HR data
                post(() -> {
                    callback.onConnected(deviceName + " ✓");
                    callback.onError(deviceName + " connected but uses proprietary protocol.\n" +
                            "Standard BLE heart rate not supported. Use camera measurement instead.");
                });
                return;
            }
            BluetoothGattCharacteristic hrChar = hrService.getCharacteristic(CHAR_HEART_RATE_MEASUREMENT);
            if (hrChar == null) {
                post(() -> callback.onError("HR characteristic not found on " + deviceName));
                return;
            }
            try {
                g.setCharacteristicNotification(hrChar, true);
                BluetoothGattDescriptor d = hrChar.getDescriptor(DESCRIPTOR_CLIENT_CONFIG);
                if (d != null) {
                    d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(d);
                }
                post(() -> callback.onConnected(deviceName));
            } catch (SecurityException e) {
                post(() -> callback.onError("Permission denied enabling HR notifications"));
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (!CHAR_HEART_RATE_MEASUREMENT.equals(c.getUuid())) return;
            byte[] data = c.getValue();
            if (data == null || data.length < 2) return;
            int flags = data[0] & 0xFF;
            int bpm = ((flags & 0x01) == 0)
                    ? (data[1] & 0xFF)
                    : ((data[1] & 0xFF) | ((data[2] & 0xFF) << 8));
            post(() -> callback.onHeartRateReceived(bpm));
        }
    };

    private void post(Runnable r) { mainHandler.post(r); }

    private String getDeviceName(BluetoothGatt g) {
        try {
            String n = g.getDevice().getName();
            return (n != null && !n.isEmpty()) ? n : "BLE Device";
        } catch (SecurityException e) { return "BLE Device"; }
    }

    public boolean isConnected() { return connected; }

    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            return ContextCompat.checkSelfPermission(ctx,
                    Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        return ContextCompat.checkSelfPermission(ctx,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            return ContextCompat.checkSelfPermission(ctx,
                    Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        return true;
    }
}
