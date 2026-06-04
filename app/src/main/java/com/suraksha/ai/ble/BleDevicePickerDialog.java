package com.suraksha.ai.ble;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BleDevicePickerDialog {

    public interface OnDeviceSelectedListener {
        void onDeviceSelected(BluetoothDevice device);
    }

    private final Context context;
    private final OnDeviceSelectedListener listener;

    private AlertDialog dialog;
    private DeviceAdapter adapter;
    private TextView tvMessage;

    private final List<DeviceItem> items = new ArrayList<>();
    private final Map<String, DeviceItem> deviceMap = new HashMap<>();

    public BleDevicePickerDialog(@NonNull Context context,
                                 @NonNull OnDeviceSelectedListener listener) {
        this.context  = context;
        this.listener = listener;
    }

    public void show() {
        adapter = new DeviceAdapter(context, items);

        dialog = new AlertDialog.Builder(context)
                .setTitle("Select Heart Rate Device")
                .setMessage("Scanning for nearby BLE devices…")
                .setAdapter(adapter, (d, which) -> {
                    DeviceItem item = items.get(which);
                    listener.onDeviceSelected(item.device);
                })
                .setNegativeButton("Cancel", null)
                .create();

        // Save reference to message TextView for live updates
        dialog.setOnShowListener(dlg -> {
            int messageId = context.getResources().getIdentifier(
                    "message", "id", "android");
            tvMessage = dialog.findViewById(messageId);
        });

        dialog.show();
    }

    public void dismiss() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    public void addDevice(BluetoothDevice device, String name, int rssi) {
        String address = device.getAddress();
        if (deviceMap.containsKey(address)) {
            DeviceItem existing = deviceMap.get(address);
            if (existing != null) existing.rssi = rssi;
        } else {
            DeviceItem item = new DeviceItem(device, name, rssi);
            items.add(item);
            deviceMap.put(address, item);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (tvMessage != null && items.size() == 1)
            tvMessage.setVisibility(View.GONE);
    }

    public void setScanFinished() {
        if (dialog == null || !dialog.isShowing()) return;
        if (items.isEmpty() && tvMessage != null) {
            tvMessage.setVisibility(View.VISIBLE);
            tvMessage.setText("No devices found. Make sure your watch's heart rate is active and Bluetooth is on.");
        }
    }

    private static class DeviceItem {
        final BluetoothDevice device;
        final String name;
        int rssi;

        DeviceItem(BluetoothDevice d, String n, int r) {
            device = d; name = n; rssi = r;
        }

        String signal() {
            if (rssi > -60) return "Strong";
            if (rssi > -75) return "Good";
            if (rssi > -85) return "Weak";
            return "Very Weak";
        }
    }

    private static class DeviceAdapter extends ArrayAdapter<DeviceItem> {

        DeviceAdapter(Context ctx, List<DeviceItem> items) {
            super(ctx, 0, items);
        }

        @NonNull
        @Override
        public View getView(int pos, @Nullable View convertView, @NonNull ViewGroup parent) {
            if (convertView == null)
                convertView = LayoutInflater.from(getContext())
                        .inflate(android.R.layout.two_line_list_item, parent, false);
            DeviceItem item = getItem(pos);
            if (item == null) return convertView;
            ((TextView) convertView.findViewById(android.R.id.text1)).setText(item.name);
            ((TextView) convertView.findViewById(android.R.id.text2))
                    .setText(item.device.getAddress() + "  •  " + item.signal() + " signal");
            return convertView;
        }
    }
}
