package dev.wdpc.remote;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Клиент Bluetooth SPP (RFCOMM) для связи с wdpc_server на ПК.
 * Подключение фоновым потоком, отправка команд через очередь.
 */
public class BtClient {

    // Стандартный UUID профиля Serial Port (использует сервер)
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public interface Listener {
        void onStatus(String s);
        void onResponse(String s); // ответы от сервера (PONG и т.п.)
    }

    private final BluetoothDevice device;
    private final Listener listener;
    private volatile BluetoothSocket socket;
    private volatile OutputStream out;
    private volatile boolean connecting = false;
    private Thread rxThread;

    public BtClient(BluetoothDevice device, Listener listener) {
        this.device = device;
        this.listener = listener;
    }

    public boolean isConnected() {
        BluetoothSocket s = socket;
        return s != null && s.isConnected();
    }

    public synchronized void connectAsync() {
        if (isConnected() || connecting) return;
        connecting = true;
        listener.onStatus("Подключение к " + name() + " ...");
        new Thread(() -> {
            try {
                BluetoothSocket s = device.createRfcommSocketToServiceRecord(SPP_UUID);
                BluetoothAdapter.getDefaultAdapter().cancelDiscovery();
                s.connect();
                socket = s;
                out = s.getOutputStream();
                connecting = false;
                listener.onStatus("Подключено: " + name());
                startRx(s);
            } catch (IOException e) {
                connecting = false;
                listener.onStatus("Ошибка подключения: " + e.getMessage());
                close();
            }
        }, "bt-connect").start();
    }

    private void startRx(BluetoothSocket s) {
        rxThread = new Thread(() -> {
            byte[] buf = new byte[512];
            StringBuilder sb = new StringBuilder();
            try (InputStream in = s.getInputStream()) {
                while (!Thread.currentThread().isInterrupted()) {
                    int n = in.read(buf);
                    if (n <= 0) break;
                    sb.append(new String(buf, 0, n));
                    int i;
                    while ((i = sb.indexOf("\n")) >= 0) {
                        String line = sb.substring(0, i).trim();
                        sb.delete(0, i + 1);
                        if (!line.isEmpty()) listener.onResponse(line);
                    }
                }
            } catch (IOException ignored) {
            } finally {
                listener.onStatus("Соединение потеряно");
                close();
            }
        }, "bt-rx");
        rxThread.start();
    }

    /** Поставить команду в поток (вызывается из UI-потока, неблокирующе). */
    public void send(String cmd) {
        OutputStream o = out;
        if (o == null || !isConnected()) {
            listener.onStatus("Нет соединения с ПК");
            return;
        }
        new Thread(() -> {
            try {
                o.write((cmd + "\n").getBytes("UTF-8"));
                o.flush();
            } catch (IOException e) {
                listener.onStatus("Ошибка отправки: " + e.getMessage());
                close();
            }
        }, "bt-tx").start();
    }

    public synchronized void close() {
        try {
            if (rxThread != null) rxThread.interrupt();
            BluetoothSocket s = socket;
            socket = null;
            out = null;
            if (s != null) s.close();
        } catch (IOException ignored) {
        }
    }

    public String name() {
        try {
            String n = device.getName();
            return (n != null && !n.isEmpty()) ? n : device.getAddress();
        } catch (SecurityException e) {
            return device.getAddress();
        }
    }
}
