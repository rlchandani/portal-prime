/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Existing engine identity is preserved; display metadata never becomes a preference value. */
internal data class InstalledVoiceChoice(val name: String, val label: String, val localeLabel: String = "")

private val voiceSearchWhitespace = Regex("\\s+")

/** The default choice is pinned separately. Search never reorders or rewrites installed choices. */
internal fun filterInstalledVoices(voices: List<InstalledVoiceChoice>, query: String): List<InstalledVoiceChoice> {
  val words = query.trim().split(voiceSearchWhitespace).filter { it.isNotBlank() }
  return voices.filter { voice -> voice.name.isNotBlank() && words.all { word ->
    voice.label.contains(word, ignoreCase = true) || voice.localeLabel.contains(word, ignoreCase = true) ||
        voice.name.contains(word, ignoreCase = true)
  } }
}

/** An unavailable saved selection remains saved; it must never be presented as engine default. */
internal fun installedVoiceLabel(current: String, voices: List<InstalledVoiceChoice>, loading: Boolean): String =
    if (current.isBlank()) "Default voice"
    else voices.firstOrNull { it.name == current }?.label ?: if (loading) "Loading voice…" else "Saved voice unavailable"
