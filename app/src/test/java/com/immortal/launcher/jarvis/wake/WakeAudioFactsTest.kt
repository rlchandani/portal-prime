/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

import android.media.AudioManager
import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/** The mic facts mirror Jarvis's MicGuard: mute, a call or ringtone mode, another app's call capture. */
class WakeAudioFactsTest {
  @Test fun callAndRingtoneModesHoldTheMicNormalDoesNot() {
    assertTrue(WakeAudioFacts.isCallMode(AudioManager.MODE_IN_CALL))
    assertTrue(WakeAudioFacts.isCallMode(AudioManager.MODE_IN_COMMUNICATION))
    assertTrue(WakeAudioFacts.isCallMode(AudioManager.MODE_RINGTONE))
    assertFalse(WakeAudioFacts.isCallMode(AudioManager.MODE_NORMAL))
  }

  @Test fun onlyAVoiceCommunicationCaptureIsACall() {
    assertTrue(WakeAudioFacts.hasCallCapture(listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION)))
    // Hearthpane's own wake capture, and a plain mic recording, are not calls.
    assertFalse(WakeAudioFacts.hasCallCapture(listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.MIC)))
    assertFalse(WakeAudioFacts.hasCallCapture(emptyList()))
  }

  @Test fun systemCallIsEitherSignal() {
    assertFalse(WakeAudioFacts.FREE.systemCall)
    assertTrue(WakeAudioFacts.FREE.copy(callAudioMode = true).systemCall)
    assertTrue(WakeAudioFacts.FREE.copy(callCaptureActive = true).systemCall)
    assertFalse(WakeAudioFacts.FREE.copy(micMuted = true).systemCall)
  }

  @Test fun noAudioServiceReadsAsFree() {
    assertEquals(WakeAudioFacts.FREE, WakeAudioFacts.read(null))
  }

  @Test fun readsTheMuteAndModeFromTheAudioManager() {
    val audio = Mockito.mock(AudioManager::class.java)
    Mockito.`when`(audio.isMicrophoneMute).thenReturn(true)
    Mockito.`when`(audio.mode).thenReturn(AudioManager.MODE_NORMAL)
    Mockito.`when`(audio.activeRecordingConfigurations).thenReturn(emptyList())
    assertEquals(WakeAudioFacts(micMuted = true, callAudioMode = false, callCaptureActive = false),
        WakeAudioFacts.read(audio))
  }

  @Test fun aFailingRecordingQueryStillReadsTheMute() {
    val audio = Mockito.mock(AudioManager::class.java)
    Mockito.`when`(audio.isMicrophoneMute).thenReturn(false)
    Mockito.`when`(audio.mode).thenReturn(AudioManager.MODE_IN_COMMUNICATION)
    Mockito.`when`(audio.activeRecordingConfigurations).thenThrow(SecurityException("denied"))
    assertEquals(WakeAudioFacts(micMuted = false, callAudioMode = true, callCaptureActive = false),
        WakeAudioFacts.read(audio))
  }
}
