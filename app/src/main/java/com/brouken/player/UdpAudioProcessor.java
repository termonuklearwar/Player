package com.brouken.player;

import android.util.Log;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@UnstableApi
public class UdpAudioProcessor implements AudioProcessor {
    private static final String TAG = "UdpAudioProcessor";

    private UdpAudioSender sender;
    private int qCount = 0;
    private boolean active = false;
    private ByteBuffer outputBuffer = AudioProcessor.EMPTY_BUFFER;
    private boolean inputEnded = false;

    public UdpAudioProcessor() {
        Log.e(TAG, "=== CONSTRUCTOR");
    }

    public void setTarget(String ip, int port) {
        Log.e(TAG, "=== setTarget " + ip + ":" + port);
        if (sender != null) sender.stop();
        sender = new UdpAudioSender(ip, port);
        sender.start();
        active = true;
    }

    public void stop() {
        if (sender != null) { sender.stop(); sender = null; }
        active = false;
    }

    @Override
    public AudioFormat configure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
        Log.e(TAG, "=== configure enc=" + inputAudioFormat.encoding
                + " sr=" + inputAudioFormat.sampleRate + " ch=" + inputAudioFormat.channelCount);
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            Log.e(TAG, "=== configure: NOT PCM -> throwing");
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        return inputAudioFormat;
    }

    @Override
    public boolean isActive() {
        Log.e(TAG, "=== isActive -> " + active);
        return active;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int size = inputBuffer.remaining();
        if (qCount < 20) { Log.e(TAG, "=== queueInput size=" + size); qCount++; }
        if (size <= 0) return;

        byte[] data = new byte[size];
        inputBuffer.get(data);
        if (sender != null) sender.send(data);

        if (outputBuffer.capacity() < size) {
            outputBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
        } else {
            outputBuffer.clear();
        }
        outputBuffer.put(data);
        outputBuffer.flip();
    }

    @Override
    public void queueEndOfStream() { inputEnded = true; }

    @Override
    public ByteBuffer getOutput() {
        ByteBuffer out = outputBuffer;
        outputBuffer = AudioProcessor.EMPTY_BUFFER;
        return out;
    }

    @Override
    public boolean isEnded() { return inputEnded && outputBuffer == AudioProcessor.EMPTY_BUFFER; }

    @Override
    public void flush() { outputBuffer = AudioProcessor.EMPTY_BUFFER; inputEnded = false; }

    @Override
    public void reset() { flush(); active = false; }
}