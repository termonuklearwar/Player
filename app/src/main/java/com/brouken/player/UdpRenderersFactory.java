package com.brouken.player;

import android.content.Context;
import android.util.Log;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.audio.AudioCapabilities;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;

@UnstableApi
public class UdpRenderersFactory extends DefaultRenderersFactory {

    private static final String TAG = "UdpRenderersFactory";
    private final AudioProcessor[] extraProcessors;

    public UdpRenderersFactory(Context context, AudioProcessor[] extraProcessors) {
        super(context);
        this.extraProcessors = extraProcessors;
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_OFF);
        Log.e(TAG, "=== CONSTRUCTOR, processors=" + extraProcessors.length);
    }

    @Override
    protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput,
                                       boolean enableAudioTrackPlaybackParams) {
        Log.e(TAG, "=== buildAudioSink() called");

        DefaultAudioSink.DefaultAudioProcessorChain chain =
                new DefaultAudioSink.DefaultAudioProcessorChain(extraProcessors);

        // Только PCM — это должно отключить passthrough
        AudioCapabilities pcmOnly = new AudioCapabilities(
                new int[] { C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT },
                2
        );

        // КРИТИЧНО: передаём null вместо context,
        // чтобы sink использовал наши capabilities, а не системные
        return new DefaultAudioSink.Builder(null)
                .setAudioProcessorChain(chain)
                .setAudioCapabilities(pcmOnly)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build();
    }
}