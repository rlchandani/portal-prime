/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChimePreviewsTest {
    private val off = ChimeConfig.Settings()

    @Test fun pingIsAlwaysOfferedAndOtherSoundsFollowTheirSwitches() {
        assertEquals(listOf(ChimePreviews.PING), ChimePreviews.available(off).map { it.id })
        val all = off.copy(hourlyChimeOn = true, spokenTimeOn = true, goldenHourOn = true)
        assertEquals(listOf(ChimePreviews.CHIME, ChimePreviews.VOICE, ChimePreviews.SUNRISE, ChimePreviews.SUNSET, ChimePreviews.PING),
            ChimePreviews.available(all).map { it.id })
    }

    @Test fun theMocksTwoButtonsComeFirst() {
        val labels = ChimePreviews.available(off.copy(hourlyChimeOn = true, spokenTimeOn = true)).map { it.label }
        assertEquals(listOf("Play chime", "Test voice", "Play ping"), labels)
    }

    @Test fun voiceUsesSpeechAndTonesUseTheirResources() {
        val previews = ChimePreviews.available(off.copy(hourlyChimeOn = true, spokenTimeOn = true, goldenHourOn = true))
            .associateBy { it.id }
        assertNull(previews.getValue(ChimePreviews.VOICE).resource)
        assertEquals(R.raw.chime, previews.getValue(ChimePreviews.CHIME).resource)
        assertEquals(R.raw.golden_sunrise, previews.getValue(ChimePreviews.SUNRISE).resource)
        val rooster = ChimePreviews.available(off.copy(goldenHourOn = true, sunriseVariant = 1)).first { it.id == ChimePreviews.SUNRISE }
        assertEquals(R.raw.golden_sunrise_2, rooster.resource)
    }

    @Test fun previewsNeedVolumeAndNoSaveInProgress() {
        assertTrue(ChimePreviews.enabled(saving = false, volume = 70))
        assertFalse(ChimePreviews.enabled(saving = true, volume = 70))
        assertFalse(ChimePreviews.enabled(saving = false, volume = 0))
        assertTrue(ChimePreviews.footer(0).startsWith("Preview volume is 0%"))
    }

    @Test fun messagesFollowThePreviewPhase() {
        assertEquals("Choose a sound to preview.", ChimePreviews.message(SoundPreviewState()))
        assertEquals("Playing chime", ChimePreviews.message(SoundPreviewState("chime", SoundPreviewPhase.PLAYING)))
        assertTrue(ChimePreviews.isPlaying(SoundPreviewState("ping", SoundPreviewPhase.STARTING)))
        assertFalse(ChimePreviews.isPlaying(SoundPreviewState("ping", SoundPreviewPhase.FAILED)))
    }

    @Test fun aPageOpenedWithNothingInFlightStartsEnabled() {
        assertFalse(openedSaveState(writesIdle = true).saving)
        assertTrue(openedSaveState(writesIdle = false).saving)
        // An edit on the quiet start still follows the save state machine.
        val edit = openedSaveState(writesIdle = true).beginEdit(0)
        assertTrue(edit.saving)
        assertFalse(edit.completeRead(edit.revision, 0).saving)
    }
}
