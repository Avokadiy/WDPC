package dev.wdpc.remote;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * WDPC Remote — пульт для ПК в стиле Watch Dogs.
 * Работает по Bluetooth SPP без подтверждения на стороне ПК.
 */
public class MainActivity extends Activity implements BtClient.Listener {

    private static final int REQ_PERMS = 1;

    // Linux input keycodes (input-event-codes.h)
    private static final int KEY_D = 2, KEY_M = 46, KEY_TAB = 15,
            KEY_F4 = 59, KEY_LEFTALT = 56, KEY_LEFTMETA = 125,
            KEY_RIGHT = 106, KEY_LEFT = 105, KEY_ENTER = 28;

    private BluetoothAdapter adapter;
    private BtClient client;
    private Spinner deviceSpinner;
    private TextView statusView;
    private boolean discovering = false;

    @SuppressLint("MissingPermission")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        adapter = BluetoothAdapter.getDefaultAdapter();
        deviceSpinner = findViewById(R.id.device_spinner);
        statusView = findViewById(R.id.status_text);

        if (adapter == null) {
            Toast.makeText(this, "Bluetooth недоступен", Toast.LENGTH_LONG).show();
        } else if (!adapter.isEnabled()) {
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        }

        checkPermsAndRefresh();

        findViewById(R.id.refresh_btn).setOnClickListener(v -> {
            checkPermsAndRefresh();
            startDiscovery();
        });
        findViewById(R.id.connect_btn).setOnClickListener(v -> connectSelected());
        findViewById(R.id.disconnect_btn).setOnClickListener(v -> {
            if (client != null) client.close();
            setStatus("Отключено");
        });
        findViewById(R.id.ping_btn).setOnClickListener(v -> send("PING"));

        // ---- переключение окон ----
        findViewById(R.id.alt_tab_btn).setOnClickListener(v -> altTab(false));
        findViewById(R.id.alt_tab_rev_btn).setOnClickListener(v -> altTab(true));
        findViewById(R.id.task_view_btn).setOnClickListener(v -> send("KEY 376")); // Win+Tab
        findViewById(R.id.win_btn).setOnClickListener(v -> send("KEY " + KEY_LEFTMETA));
        findViewById(R.id.close_win_btn).setOnClickListener(v -> send("CHORD " + KEY_LEFTALT + " " + KEY_F4 + " UP"));
        findViewById(R.id.min_all_btn).setOnClickListener(v -> send("CHORD " + KEY_LEFTMETA + " " + KEY_M + " UP"));
        findViewById(R.id.desktop_btn).setOnClickListener(v -> send("CHORD " + KEY_LEFTMETA + " " + KEY_D + " UP"));

        // ---- навигация по окнам стрелками (Alt+Tab удерживаем + стрелки) ----
        Button hold = findViewById(R.id.hold_alt_btn);
        hold.setOnClickListener(v -> {
            if (hold.isSelected()) {
                hold.setSelected(false);
                hold.setText("Держать Alt");
                send("KEY " + KEY_LEFTALT + " UP");
            } else {
                hold.setSelected(true);
                hold.setText("Отпустить Alt");
                send("KEY " + KEY_LEFTALT + " DOWN");
            }
        });
        findViewById(R.id.nav_left_btn).setOnClickListener(v -> send("KEY " + KEY_LEFT));
        findViewById(R.id.nav_right_btn).setOnClickListener(v -> send("KEY " + KEY_RIGHT));
        findViewById(R.id.nav_enter_btn).setOnClickListener(v -> send("KEY " + KEY_ENTER));
    }

    /** Alt+Tab / Alt+Shift+Tab: зажать Alt, тапнуть Tab, отпустить Alt. */
    private void altTab(boolean reverse) {
        send("KEY " + KEY_LEFTALT + " DOWN");
        send("KEY " + KEY_TAB + (reverse ? " DOWN" : ""));
        if (reverse) send("KEY " + KEY_TAB + " DOWN");
        send("KEY " + KEY_TAB);           // тап Tab
        if (reverse) send("KEY " + KEY_TAB + " UP");
        send("KEY " + KEY_LEFTALT + " UP");
    }

    private void send(String cmd) {
        if (client == null || !client.isConnected()) {
            setStatus("Сначала подключитесь к ПК");
            return;
        }
        client.send(cmd);
    }

    @SuppressLint("MissingPermission")
    private void connectSelected() {
        Object item = deviceSpinner.getSelectedItem();
        if (!(item instanceof DeviceItem)) {
            Toast.makeText(this, "Выберите устройство", Toast.LENGTH_SHORT).show();
            return;
        }
        String addr = ((DeviceItem) item).address;
        BluetoothDevice dev = adapter.getRemoteDevice(addr);
        if (client != null) client.close();
        client = new BtClient(dev, this);
        client.connectAsync();
    }

    @SuppressLint("MissingPermission")
    private void startDiscovery() {
        if (adapter == null || discovering) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
                && Build.VERSION.SDK_INT <= 30) {
            return; // discovery требует разрешений выше
        }
        try {
            discovering = adapter.startDiscovery();
            setStatus(discovering ? "Поиск устройств..." : "Поиск невозможен");
        } catch (SecurityException e) {
            setStatus("Нет прав на поиск");
        }
    }

    @SuppressLint("MissingPermission")
    private void checkPermsAndRefresh() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            for (String p : new String[]{Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN}) {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                    needed.add(p);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQ_PERMS);
            return;
        }
        refreshPaired();
    }

    @SuppressLint("MissingPermission")
    private void refreshPaired() {
        List<DeviceItem> items = new ArrayList<>();
        if (adapter != null) {
            Set<BluetoothDevice> paired = adapter.getBondedDevices();
            if (paired != null) {
                for (BluetoothDevice d : paired) {
                    String n;
                    try { n = d.getName(); } catch (SecurityException e) { n = null; }
                    items.add(new DeviceItem((n == null || n.isEmpty() ? "Устройство" : n)
                            + "  [" + d.getAddress() + "]", d.getAddress()));
                }
            }
        }
        devices.clear();
        devices.addAll(items);
        adapterNotify();
    }

    private final List<DeviceItem> devices = new ArrayList<>();
    private ArrayAdapter<DeviceItem> spinnerAdapter;

    private void adapterNotify() {
        runOnUiThread(() -> {
            if (spinnerAdapter == null) {
                spinnerAdapter = new ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_dropdown_item, devices);
                spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                deviceSpinner.setAdapter(spinnerAdapter);
            } else {
                spinnerAdapter.notifyDataSetChanged();
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void addFoundDevice(BluetoothDevice d) {
        String addr = d.getAddress();
        for (DeviceItem it : devices) if (it.address.equals(addr)) return;
        String n;
        try { n = d.getName(); } catch (SecurityException e) { n = null; }
        devices.add(new DeviceItem((n == null || n.isEmpty() ? "Новое" : n) + "  [" + addr + "]", addr));
        adapterNotify();
    }

    private final android.content.BroadcastReceiver btReceiver = new android.content.BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(a)) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null) addFoundDevice(d);
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(a)) {
                discovering = false;
                setStatus("Поиск завершён");
            }
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        registerReceiver(btReceiver, new android.content.IntentFilter(
                BluetoothDevice.ACTION_FOUND));
        registerReceiver(btReceiver, new android.content.IntentFilter(
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED));
    }

    @Override
    protected void onStop() {
        super.onStop();
        try { unregisterReceiver(btReceiver); } catch (Exception ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        checkPermsAndRefresh();
    }

    private void setStatus(String s) {
        runOnUiThread(() -> statusView.setText(s));
    }

    @Override
    public void onStatus(String s) { setStatus(s); }

    @Override
    public void onResponse(String s) { setStatus("ПК: " + s); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (client != null) client.close();
        if (adapter != null && discovering) {
            try { adapter.cancelDiscovery(); } catch (Exception ignored) {}
        }
    }

    static class DeviceItem {
        final String label, address;
        DeviceItem(String l, String a) { label = l; address = a; }
        @Override public String toString() { return label; }
    }
}
