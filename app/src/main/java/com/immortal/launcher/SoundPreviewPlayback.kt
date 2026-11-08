/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Cancellable foreground previews; scheduled cues retain ChimePlayer's independent ownership. */
internal object SoundPreviewPlayback {
  fun tone(context: Context, resource: Int, volume: Float,
      onPlaying: () -> Unit, onFinished: (Boolean) -> Unit): AutoCloseable {
    val player = MediaPlayer()
    var closed = false
    val close = AutoCloseable {
      if (!closed) {
        closed = true
        runCatching { player.release() }
      }
    }
    try {
      player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
      val file = context.resources.openRawResourceFd(resource) ?: error("Preview sound unavailable")
      file.use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
      player.setVolume(volume, volume)
      player.setOnPreparedListener {
        if (!closed) {
          try { it.start(); onPlaying() } catch (_: Exception) { onFinished(false) }
        }
      }
      player.setOnCompletionListener { if (!closed) onFinished(true) }
      player.setOnErrorListener { _, _, _ -> if (!closed) onFinished(false); true }
      player.prepareAsync()
      return close
    } catch (error: Exception) {
      close.close()
      throw error
    }
  }

  fun voice(context: Context, voiceName: String, volume: Float,
      onPlaying: () -> Unit, onFinished: (Boolean) -> Unit): AutoCloseable {
    val handler = Handler(Looper.getMainLooper())
    var closed = false
    var engine: TextToSpeech? = null
    val id = "sounds-preview"
    val close = AutoCloseable {
      if (!closed) {
        closed = true
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
      }
    }
    fun publish(utterance: String?, update: () -> Unit) {
      handler.post { if (!closed && utterance == id) update() }
    }
    try {
      engine = TextToSpeech(context.applicationContext) { status ->
        // TTS callbacks can use binder threads, including a late initialization after Stop.
        handler.post {
          if (!closed) {
            val current = engine
            if (status != TextToSpeech.SUCCESS || current == null) onFinished(false)
            else try {
              val language = Locale.getDefault().language
              val selected = if (voiceName.isNotBlank()) current.voices?.firstOrNull { it.name == voiceName }
                  else current.voices?.filter { it.locale.language == language && !it.isNetworkConnectionRequired }
                      ?.maxByOrNull { it.quality }
              val available = current.setLanguage(selected?.locale ?: Locale.getDefault())
              if (available < TextToSpeech.LANG_AVAILABLE) error("Preview language unavailable")
              if (selected != null && current.setVoice(selected) == TextToSpeech.ERROR) error("Preview voice unavailable")
              val listener = object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = publish(utteranceId, onPlaying)
                override fun onDone(utteranceId: String?) = publish(utteranceId) { onFinished(true) }
                @Deprecated("Android compatibility callback")
                override fun onError(utteranceId: String?) = publish(utteranceId) { onFinished(false) }
                override fun onError(utteranceId: String?, errorCode: Int) = publish(utteranceId) { onFinished(false) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) = publish(utteranceId) { onFinished(true) }
              }
              if (current.setOnUtteranceProgressListener(listener) == TextToSpeech.ERROR) error("Preview listener unavailable")
              val sample = if (voiceName.contains("ro_RO", true) || voiceName.contains("ro-ro", true))
                  "Aceasta este vocea selectata pentru Calder." else "This is the selected voice for Calder."
              val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
                if (voiceName.isNotBlank()) putString("sherpa_voice_name", voiceName)
              }
              if (current.speak(sample, TextToSpeech.QUEUE_FLUSH, params, id) == TextToSpeech.ERROR) onFinished(false)
            } catch (_: Exception) { onFinished(false) }
          }
        }
      }
      return close
    } catch (error: Exception) {
      close.close()
      throw error
    }
  }
}
