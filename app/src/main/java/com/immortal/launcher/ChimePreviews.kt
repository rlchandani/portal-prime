/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** One preview button on Sounds; [resource] is null for the spoken time (TTS). */
internal data class ChimePreview(val id: String, val label: String, val resource: Int?)

/**
 * Which sounds Sounds › Preview sounds offers, from the saved chime settings: the chime and
 * spoken time first (as in the mock), the golden-hour tones when they are on, and the ping
 * that other rooms hear. Pure, so the selection is tested without audio.
 */
internal object ChimePreviews {
    const val CHIME = "chime"
    const val VOICE = "voice"
    const val SUNRISE = "sunrise"
    const val SUNSET = "sunset"
    const val PING = "ping"
    private const val ROOSTER_VARIANT = 1

    fun available(settings: ChimeConfig.Settings): List<ChimePreview> = buildList {
        if (settings.hourlyChimeOn) add(ChimePreview(CHIME, "Play chime", R.raw.chime))
        if (settings.spokenTimeOn) add(ChimePreview(VOICE, "Test voice", null))
        if (settings.goldenHourOn) {
            add(ChimePreview(SUNRISE, "Test sunrise",
                if (settings.sunriseVariant == ROOSTER_VARIANT) R.raw.golden_sunrise_2 else R.raw.golden_sunrise))
            add(ChimePreview(SUNSET, "Test sunset", R.raw.golden_sunset))
        }
        add(ChimePreview(PING, "Play ping", R.raw.ping))
    }

    /** Previews play at the saved cue volume, so 0% (or a save in progress) disables them. */
    fun enabled(saving: Boolean, volume: Int): Boolean = !saving && volume > 0

    fun message(state: SoundPreviewState): String = when (state.phase) {
        SoundPreviewPhase.STARTING -> "Preparing ${state.label}…"
        SoundPreviewPhase.PLAYING -> "Playing ${state.label}"
        SoundPreviewPhase.FAILED -> "${state.label} couldn’t play. Try again."
        SoundPreviewPhase.IDLE -> "Choose a sound to preview."
    }

    fun isPlaying(state: SoundPreviewState): Boolean =
        state.phase == SoundPreviewPhase.STARTING || state.phase == SoundPreviewPhase.PLAYING

    fun footer(volume: Int): String = if (volume == 0) {
        "Preview volume is 0%. Raise Volume to hear the sounds."
    } else {
        "Previews use your saved volume, including during quiet hours. A new preview replaces the previous one; leaving Sounds stops it."
    }
}
