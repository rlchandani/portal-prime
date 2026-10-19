/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// SPDX-License-Identifier: MIT
// In this upstream tree the MIT text is in LICENSE-MIT. The combined
// Snapdroid application remains governed by upstream GPL-3.0-or-later.
package de.badaix.snapcast;

import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import java.util.concurrent.atomic.AtomicBoolean;

/** Android streaming backend; native FilePlayer has already applied volume to these PCM bytes. */
public final class AndroidPcmOutput implements ConnectionPcmSink.Output {
    public static final int SAMPLE_RATE = 48000;
    public static final int BURST_BYTES = 9600; // 50 ms, 16-bit stereo.
    public static final int MAX_BUFFER_BYTES = 65536;
    private final AudioTrack track;
    private final AtomicBoolean closed = new AtomicBoolean();

    public AndroidPcmOutput() {
        int minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0 || minimum > MAX_BUFFER_BYTES)
            throw new IllegalStateException("Unsupported PCM buffer");
        int size = Math.max(BURST_BYTES, minimum);
        size = (size + 3) & ~3; // Complete stereo frames, still within the allocation bound.
        track = new AudioTrack(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                new AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
                size, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            throw new IllegalStateException("PCM output not initialized");
        }
    }
    @Override public void play() {
        if (closed.get()) throw new IllegalStateException("PCM output closed");
        track.play();
    }
    @Override public int write(byte[] data, int offset, int length) {
        if (closed.get()) return AudioTrack.ERROR_INVALID_OPERATION;
        // This call is made exclusively by the continuously running PCM reader.
        return Build.VERSION.SDK_INT >= 23
                ? track.write(data, offset, length, AudioTrack.WRITE_BLOCKING)
                : track.write(data, offset, length); // API 21/22 byte-array writes are blocking.

    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        // No monitor is shared with write: teardown must be able to interrupt a blocking write.
        try { track.pause(); } catch (RuntimeException ignored) { }
        try { track.flush(); } catch (RuntimeException ignored) { }
        try { track.stop(); } catch (RuntimeException ignored) { }
        track.release(); // A failed release propagates so the sink cannot start a second output.
    }
}
