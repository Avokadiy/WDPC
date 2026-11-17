package dev.wdpc.remote;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Клиент Wi-Fi пульта: TCP-соединение с wdpc_server.exe (порт 8721).
 * Тот же текстовый протокол, что и по Bluetooth. Подключение фоновым потоком.
 */
public class WifiClient {

    public static final int DEFAULT_PORT = 8721;

    public interface Listener {
        void onStatus(String s);
        void onResponse(String s);
    }

    private final String host;
    private final int port;
    private final Listener listener;
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile boolean connecting = false;
    private Thread rxThread;

    public WifiClient(String host, int port, Listener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    public boolean isConnected() {
        Socket s = socket;
        return s != null && s.isConnected();
    }

    public synchronized void connectAsync() {
        if (isConnected() || connecting) return;
        connecting = true;
        listener.onStatus("Подключение к " + host + ":" + port + " ...");
        new Thread(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(host, port), 5000);
                s.setKeepAlive(true);
                socket = s;
                out = s.getOutputStream();
                connecting = false;
                listener.onStatus("Wi-Fi: подключено к " + host);
                startRx(s);
            } catch (IOException e) {
                connecting = false;
                listener.onStatus("Ошибка Wi-Fi: " + e.getMessage());
                close();
            }
        }, "wifi-connect").start();
    }

    private void startRx(Socket s) {
        rxThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.trim().isEmpty()) listener.onResponse(line.trim());
                }
            } catch (IOException ignored) {
            } finally {
                listener.onStatus("Wi-Fi: соединение потеряно");
                close();
            }
        }, "wifi-rx");
        rxThread.start();
    }

    public void send(String cmd) {
        OutputStream o = out;
        if (o == null || !isConnected()) {
            listener.onStatus("Нет соединения с ПК");
            return;
        }
        new Thread(() -> {
            try {
                o.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
                o.flush();
            } catch (IOException e) {
                listener.onStatus("Ошибка отправки: " + e.getMessage());
                close();
            }
        }, "wifi-tx").start();
    }

    public synchronized void close() {
        try {
            if (rxThread != null) rxThread.interrupt();
            Socket s = socket;
            socket = null;
            out = null;
            if (s != null) s.close();
        } catch (IOException ignored) {
        }
    }
}
