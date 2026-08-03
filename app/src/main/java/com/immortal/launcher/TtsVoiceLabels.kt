/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.speech.tts.Voice
import java.util.Locale

/**
 * THE TTS voice name prettifier + sort — one copy, shared by every voice picker (Chime's spoken
 * time, the Welcome greeting; formerly copy-pasted into each with renamed functions).
 *
 * Ordering: on-device Sherpa voices first (Kokoro, then the Romanian Piper special-case, then
 * other Pipers), then engine voices — highest quality first, then by language, then name.
 */
object TtsVoiceLabels {

  val sort: Comparator<Voice> =
      compareBy<Voice> { group(it) }
          .thenByDescending { it.quality }
          .thenBy { it.locale.displayLanguage }
          .thenBy { it.name }

  private fun group(v: Voice): Int {
    val n = v.name.lowercase()
    return when {
      n.startsWith("sherpa-kokoro-") -> 0
      n.startsWith("sherpa-piper-ro_ro-") -> 1
      n.startsWith("sherpa-piper-") -> 2
      else -> 3
    }
  }

  private fun localeLabel(v: Voice): String {
    val language = v.locale.getDisplayLanguage(Locale.US).ifBlank { v.locale.language }
    val country = v.locale.country
    return if (country.isBlank()) language else "$language (${country.uppercase()})"
  }

  private fun qualityLabel(v: Voice): String? =
      when {
        v.quality >= Voice.QUALITY_VERY_HIGH -> "HQ+"
        v.quality >= Voice.QUALITY_HIGH -> "HQ"
        else -> null
      }

  /** Turn an engine voice name like "en-us-x-sfg#female_1-local" into something readable. */
  fun label(v: Voice): String {
    val n = v.name.lowercase()
    if (n.startsWith("sherpa-kokoro-")) {
      val voice = v.name.removePrefix("sherpa-kokoro-")
      return listOfNotNull("Kokoro $voice", localeLabel(v), qualityLabel(v)).joinToString(" - ")
    }
    if (n == "sherpa-piper-ro_ro-mihai-medium") {
      return listOfNotNull("Romanian Mihai", localeLabel(v), qualityLabel(v)).joinToString(" - ")
    }
    if (n.startsWith("sherpa-piper-")) {
      val voice = v.name.removePrefix("sherpa-piper-").replace('-', ' ')
      return listOfNotNull("Piper $voice", localeLabel(v), qualityLabel(v)).joinToString(" - ")
    }
    val gender = when {
      n.contains("female") -> "Female"
      n.contains("male") -> "Male"
      else -> null
    }
    val num = Regex("(\\d+)").find(n)?.value
    val base = listOfNotNull(gender, num?.let { "voice $it" }).joinToString(" ").ifBlank { v.name }
    val region = v.locale.country.ifBlank { v.locale.language }.uppercase()
    return listOfNotNull(
            base.takeIf { it.isNotBlank() },
            region.takeIf { it.isNotBlank() },
            qualityLabel(v))
        .joinToString(" · ")
  }
}
