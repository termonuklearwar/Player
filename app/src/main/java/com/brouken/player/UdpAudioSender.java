package com.brouken.player;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class UdpAudioSender {
    private static final String TAG = "UdpAudioSender";

    private final String host;
    private final int port;
    private DatagramSocket socket;
    private Thread senderThread;
    private volatile boolean running;
    private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(300);

    private final AtomicLong packetsSent = new AtomicLong(0);
    private final AtomicLong bytesSent = new AtomicLong(0);

    public UdpAudioSender(String host, int port) {
        this.host = host;
        this.port = port;
        Log.e(TAG, "=== CONSTRUCTOR: " + host + ":" + port);
    }

    public void start() {
        try {
            socket = new DatagramSocket();
            socket.setSendBufferSize(1024 * 1024);
        } catch (Exception e) {
            Log.e(TAG, "=== socket create FAILED", e);
            return;
        }
        running = true;
        senderThread = new Thread(this::run);
        senderThread.setPriority(Thread.MAX_PRIORITY);
        senderThread.setDaemon(true);
        senderThread.start();
        Log.e(TAG, "=== sender thread started -> " + host + ":" + port);
    }

    public void send(byte[] data) {
        if (!running || data == null) return;
        if (!queue.offer(data)) {
            queue.poll();
            queue.offer(data);
        }
    }

    private void run() {
        Log.e(TAG, "=== run() entered");
        try {
            InetAddress addr = InetAddress.getByName(host);
            Log.e(TAG, "=== resolved " + host + " -> " + addr.getHostAddress());

            long lastStatTime = System.currentTimeMillis();

            while (running) {
                byte[] data = queue.poll(100, TimeUnit.MILLISECONDS);
                if (data == null) continue;

                try {
                    int offset = 0;
                    int total = data.length;
                    while (offset < total && running) {
                        int chunk = Math.min(1400, total - offset);
                        DatagramPacket packet = new DatagramPacket(
                                data, offset, chunk, addr, port);
                        socket.send(packet);
                        offset += chunk;
                        packetsSent.incrementAndGet();
                        bytesSent.addAndGet(chunk);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "=== send FAILED: " + e.getMessage());
                }

                long now = System.currentTimeMillis();
                if (now - lastStatTime > 3000) {
                    Log.e(TAG, "=== stats: " + packetsSent.get() + " packets, "
                            + (bytesSent.get() / 1024) + " KB, queue=" + queue.size());
                    packetsSent.set(0);
                    bytesSent.set(0);
                    lastStatTime = now;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "=== sender loop FAILED", e);
        }
        Log.e(TAG, "=== run() exited");
    }

    public void stop() {
        Log.e(TAG, "=== stop()");
        running = false;
        if (senderThread != null) senderThread.interrupt();
        if (socket != null) socket.close();
        socket = null;
        senderThread = null;
        queue.clear();
    }
}