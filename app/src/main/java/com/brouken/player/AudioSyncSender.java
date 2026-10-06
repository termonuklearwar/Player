package com.brouken.player;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class AudioSyncSender {
    private static final String TAG = "AudioSyncSender";

    private final String host;
    private final int port;
    private DatagramSocket socket;
    private InetAddress addr;
    private volatile boolean running;
    private String currentFile = "";
    private long currentPos = 0;
    private String currentState = "IDLE";

    // Очередь сообщений для отправки в фоне
    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(50);
    private Thread senderThread;

    public AudioSyncSender(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void start() {
        try {
            socket = new DatagramSocket();
            addr = InetAddress.getByName(host);
            running = true;
            senderThread = new Thread(this::senderLoop, "AudioSyncSender");
            senderThread.setDaemon(true);
            senderThread.start();
            Log.e(TAG, "=== started -> " + host + ":" + port
                    + " resolvedAddr=" + addr.getHostAddress()
                    + " socket=" + socket.isBound());
        } catch (Exception e) {
            Log.e(TAG, "=== start FAILED", e);
        }
    }

    public void setFile(String file) {
        this.currentFile = file != null ? file : "";
        Log.e(TAG, "=== setFile: " + currentFile);
    }

    public void update(long positionMs, boolean playing) {
        this.currentPos = positionMs;
        this.currentState = playing ? "PLAY" : "PAUSE";
    }

    public void clear() {
        this.currentFile = "";
        this.currentState = "IDLE";
    }

    public void send() {
    if (!running || socket == null || addr == null) return;

    try {
        String json = "{\"file\":\"" + escape(currentFile) + "\","
                + "\"pos\":" + currentPos + ","
                + "\"state\":\"" + currentState + "\"}";
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        if (!queue.offer(data)) {
            queue.poll();
            queue.offer(data);
        }
    } catch (Exception e) {
        Log.e(TAG, "=== enqueue FAILED", e);
    }
}

    /** Фоновый поток, который реально отправляет UDP-пакеты. */
    private void senderLoop() {
        Log.e(TAG, "=== senderLoop started");
        while (running) {
            try {
                byte[] data = queue.poll(500, TimeUnit.MILLISECONDS);
                if (data == null) continue;
                if (socket == null || addr == null) break;

                DatagramPacket packet = new DatagramPacket(data, data.length, addr, port);
                socket.send(packet);
                Log.e(TAG, "=== sent " + data.length + " bytes");
            } catch (InterruptedException ie) {
                break;
            } catch (Exception e) {
                Log.e(TAG, "=== send EXCEPTION", e);
            }
        }
        Log.e(TAG, "=== senderLoop ended");
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public void stop() {
        running = false;
        if (senderThread != null) senderThread.interrupt();
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
        socket = null;
        addr = null;
        senderThread = null;
        queue.clear();
        Log.e(TAG, "=== stopped");
    }
}