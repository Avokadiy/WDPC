package dev.wdpc.remote;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
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
 * Основной транспорт: Wi-Fi (TCP) + wdpc_server.py на Windows — без установки чего-либо ещё.
 * Дополнительный: Bluetooth SPP для Linux-сервера (без подтверждения пары).
 */
public class MainActivity extends Activity implements BtClient.Listener, WifiClient.Listener {

    private static final int REQ_PERMS = 1;
    private static final String PREFS = "wdpc";
    private static final String PREF_IP = "last_ip";

    // Windows Virtual-Key codes
    private static final int VK_LWIN = 0x5B, VK_MENU = 0x12, VK_TAB = 0x09,
            VK_F4 = 0x73, VK_D = 0x44, VK_M = 0x4D,
            VK_LEFT = 0x25, VK_RIGHT = 0x27, VK_RETURN = 0x0D,
            VK_SHIFT = 0x10;

    private BluetoothAdapter adapter;
    private BtClient bt;
    private WifiClient wifi;

    private Spinner deviceSpinner;
    private EditText ipEdit;
    private TextView statusView;
    private boolean discovering = false;

    @SuppressLint("MissingPermission")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        deviceSpinner = findViewById(R.id.device_spinner);
        ipEdit = findViewById(R.id.ip_edit);
        statusView = findViewById(R.id.status_text);

        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ipEdit.setText(sp.getString(PREF_IP, ""));

        adapter = BluetoothAdapter.getDefaultAdapter();
        checkPermsAndRefresh();

        // ---- Wi-Fi ----
        findViewById(R.id.wifi_connect_btn).setOnClickListener(v -> connectWifi());
        findViewById(R.id.wifi_disconnect_btn).setOnClickListener(v -> {
            if (wifi != null) wifi.close();
            setStatus("Wi-Fi отключено");
        });
        findViewById(R.id.ping_btn).setOnClickListener(v -> send("PING"));

        // ---- Bluetooth (Linux-сервер) ----
        findViewById(R.id.refresh_btn).setOnClickListener(v -> {
            checkPermsAndRefresh();
            startDiscovery();
        });
        findViewById(R.id.connect_btn).setOnClickListener(v -> connectSelected());
        findViewById(R.id.disconnect_btn).setOnClickListener(v -> {
            if (bt != null) bt.close();
            setStatus("BT отключено");
        });

        // ---- переключение окон ----
        findViewById(R.id.alt_tab_btn).setOnClickListener(v -> altTab(false));
        findViewById(R.id.alt_tab_rev_btn).setOnClickListener(v -> altTab(true));
        findViewById(R.id.task_view_btn).setOnClickListener(v ->
                send("CHORD " + VK_LWIN + " " + VK_TAB + " UP"));
        findViewById(R.id.win_btn).setOnClickListener(v -> send("KEY " + VK_LWIN));
        findViewById(R.id.close_win_btn).setOnClickListener(v ->
                send("CHORD " + VK_MENU + " " + VK_F4 + " UP"));
        findViewById(R.id.min_all_btn).setOnClickListener(v ->
                send("CHORD " + VK_LWIN + " " + VK_M + " UP"));
        findViewById(R.id.desktop_btn).setOnClickListener(v ->
                send("CHORD " + VK_LWIN + " " + VK_D + " UP"));

        // ---- навигация по окнам (Alt удерживаем + стрелки) ----
        Button hold = findViewById(R.id.hold_alt_btn);
        hold.setOnClickListener(v -> {
            if (hold.isSelected()) {
                hold.setSelected(false);
                hold.setText("Держать Alt");
                send("KEY " + VK_MENU + " UP");
            } else {
                hold.setSelected(true);
                hold.setText("Отпустить Alt");
                send("KEY " + VK_MENU + " DOWN");
            }
        });
        findViewById(R.id.nav_left_btn).setOnClickListener(v -> send("KEY " + VK_LEFT));
        findViewById(R.id.nav_right_btn).setOnClickListener(v -> send("KEY " + VK_RIGHT));
        findViewById(R.id.nav_enter_btn).setOnClickListener(v -> send("KEY " + VK_RETURN));
    }

    /** Alt+Tab / Alt+Shift+Tab. */
    private void altTab(boolean reverse) {
        if (reverse) {
            send("CHORD " + VK_MENU + " " + VK_SHIFT + " " + VK_TAB + " UP");
        } else {
            send("CHORD " + VK_MENU + " " + VK_TAB + " UP");
        }
    }

    private boolean anyLink() {
        return (wifi != null && wifi.isConnected()) || (bt != null && bt.isConnected());
    }

    private void send(String cmd) {
        if (wifi != null && wifi.isConnected()) {
            wifi.send(cmd);
        } else if (bt != null && bt.isConnected()) {
            bt.send(cmd);
        } else {
            setStatus("Сначала подключитесь к ПК (Wi-Fi или BT)");
        }
    }

    private void connectWifi() {
        String ip = ipEdit.getText().toString().trim();
        if (ip.isEmpty()) {
            Toast.makeText(this, "Введите IP компьютера", Toast.LENGTH_SHORT).show();
            return;
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_IP, ip).apply();
        if (wifi != null) wifi.close();
        wifi = new WifiClient(ip, WifiClient.DEFAULT_PORT, this);
        wifi.connectAsync();
    }

    @SuppressLint("MissingPermission")
    private void connectSelected() {
        Object item = deviceSpinner.getSelectedItem();
        if (!(item instanceof DeviceItem)) {
            Toast.makeText(this, "Выберите устройство", Toast.LENGTH_SHORT).show();
            return;
        }
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth недоступен", Toast.LENGTH_SHORT).show();
            return;
        }
        BluetoothDevice dev = adapter.getRemoteDevice(((DeviceItem) item).address);
        if (bt != null) bt.close();
        bt = new BtClient(dev, this);
        bt.connectAsync();
    }

    @SuppressLint("MissingPermission")
    private void startDiscovery() {
        if (adapter == null || discovering) return;
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
        registerReceiver(btReceiver, new android.content.IntentFilter(BluetoothDevice.ACTION_FOUND));
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
        if (bt != null) bt.close();
        if (wifi != null) wifi.close();
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
