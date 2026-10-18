/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher;

import android.media.AudioFormat;
import android.os.SystemClock;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;

/** Test APK only. All samples are zero; every client additionally requests volume zero. */
public final class OneShotSpeechFixtureService extends TextToSpeechService {
    private final Object stopLock = new Object();
    private boolean stopped;

    @Override protected String[] onGetLanguage() { return new String[] { "eng", "USA", "" }; }
    @Override protected int onIsLanguageAvailable(String language, String country, String variant) {
        return "eng".equals(language) ? TextToSpeech.LANG_COUNTRY_AVAILABLE : TextToSpeech.LANG_NOT_SUPPORTED;
    }
    @Override protected int onLoadLanguage(String language, String country, String variant) {
        return onIsLanguageAvailable(language, country, variant);
    }
    @Override protected void onStop() {
        synchronized (stopLock) { stopped = true; stopLock.notifyAll(); }
    }
    @Override protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        // The callback is used only synchronously in this method and never retained.
        String command = request.getCharSequenceText().toString();
        if ("public_error".equals(command)) {
            callback.error(TextToSpeech.ERROR_SYNTHESIS);
            callback.done();
            return;
        }
        synchronized (stopLock) { stopped = false; }
        if (callback.start(8000, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return;
        byte[] silence = new byte[320];
        if (callback.audioAvailable(silence, 0, silence.length) != TextToSpeech.SUCCESS) return;
        if ("public_stop".equals(command)) {
            long deadline = SystemClock.elapsedRealtime() + 4000;
            synchronized (stopLock) {
                while (!stopped && SystemClock.elapsedRealtime() < deadline) {
                    try { stopLock.wait(Math.max(1, deadline - SystemClock.elapsedRealtime())); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                }
                if (stopped) return;
            }
        }
        callback.done();
    }
}
