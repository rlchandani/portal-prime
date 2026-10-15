/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import java.io.ByteArrayInputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`

class RemoteVolumeTest {
  @Test fun setAcceptsZeroAndBothRangeBoundariesWithoutAnExtraUnmute() {
    for (level in listOf(0, 7, 15)) {
      val fixture = Fixture()
      val response = fixture.request("""{"dir":"set","level":$level}""")
      assertEquals(200, response.status)
      val json = response.json()
      assertTrue(json.getBoolean("ok"))
      assertEquals("set", json.getString("dir"))
      Mockito.verify(fixture.audio).setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
      Mockito.verify(fixture.audio, Mockito.never()).adjustStreamVolume(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())
    }
  }

  @Test fun setRejectsMissingWrongTypedFractionalAndOutOfRangeLevels() {
    for (level in listOf(null, "null", "true", "false", "\"7\"", "{}", "[]", "0.5", "7.5", "7.0", "1e0", "-1", "16", "2147483648", "4294967296", "1e100")) {
      val fixture = Fixture()
      val field = if (level == null) "" else ",\"level\":$level"
      val response = fixture.request("{\"dir\":\"set\"$field}")
      assertEquals("level=$level", 400, response.status)
      assertFalse(response.json().getBoolean("ok"))
      Mockito.verify(fixture.audio, Mockito.never()).setStreamVolume(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())
      Mockito.verify(fixture.audio, Mockito.never()).adjustStreamVolume(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())
    }
  }

  @Test fun fixedVolumeRejectsEveryDirection() {
    for (dir in listOf("set", "up", "down", "mute")) {
      val fixture = Fixture()
      `when`(fixture.audio.isVolumeFixed).thenReturn(true)
      val response = fixture.request("""{"dir":"$dir","level":7}""")
      assertEquals(dir, 409, response.status)
      assertFalse(response.json().getBoolean("ok"))
      Mockito.verify(fixture.audio, Mockito.never()).setStreamVolume(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())
      Mockito.verify(fixture.audio, Mockito.never()).adjustStreamVolume(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())
    }
  }

  @Test fun zeroMaximumStreamCannotBeControlled() {
    val fixture = Fixture()
    `when`(fixture.audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).thenReturn(0)
    assertEquals(409, fixture.request("""{"dir":"set","level":0}""").status)
    Mockito.verify(fixture.audio, Mockito.never()).setStreamVolume(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())
  }

  @Test fun legacyDirectionsStillAdjustMusicSilentlyWithoutSystemUi() {
    for ((dir, adjustment) in listOf("up" to AudioManager.ADJUST_RAISE, "down" to AudioManager.ADJUST_LOWER, "mute" to AudioManager.ADJUST_TOGGLE_MUTE)) {
      val fixture = Fixture()
      val response = fixture.request("""{"dir":"$dir"}""")
      assertEquals(200, response.status)
      assertTrue(response.json().getBoolean("ok"))
      assertEquals(dir, response.json().getString("dir"))
      Mockito.verify(fixture.audio).adjustStreamVolume(AudioManager.STREAM_MUSIC, adjustment, 0)
      Mockito.verify(fixture.audio, Mockito.never()).setStreamVolume(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())
    }
  }

  @Test fun commandFailuresNeverReturnOk() {
    for (dir in listOf("set", "up", "down", "mute")) {
      val fixture = Fixture()
      Mockito.doThrow(SecurityException("Synthetic volume denial")).`when`(fixture.audio)
          .adjustStreamVolume(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())
      Mockito.doThrow(SecurityException("Synthetic volume denial")).`when`(fixture.audio)
          .setStreamVolume(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())
      val response = fixture.request("""{"dir":"$dir","level":7}""")
      assertEquals(dir, 500, response.status)
      assertFalse(response.json().getBoolean("ok"))
      assertEquals("volume_failed", response.json().getString("error"))
    }
  }

  @Test fun unavailableAudioServiceIsReported() {
    val fixture = Fixture()
    `when`(fixture.context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(null)
    assertEquals(503, fixture.request("""{"dir":"up"}""").status)
  }

  @Test fun invalidDirectionAndMalformedJsonDoNotTouchAudio() {
    for (body in listOf("{", "{}", """{"dir":"loud"}""", """{"dir":7}""")) {
      val fixture = Fixture()
      assertEquals(400, fixture.request(body).status)
      Mockito.verifyNoInteractions(fixture.audio)
    }
  }

  @Test fun volumeRequiresPostAndPairing() {
    val fixture = Fixture()
    assertEquals(405, fixture.request("""{"dir":"up"}""", method = "GET").status)
    assertEquals(401, fixture.request("""{"dir":"up"}""", authorized = false).status)
    Mockito.verifyNoInteractions(fixture.audio)
  }

  @Test fun nowplayingIncludesLiveMusicVolumeWithoutAnActiveSession() {
    val fixture = Fixture()
    val json = fixture.nowplaying().json()
    assertTrue(json.getBoolean("ok"))
    val np = json.getJSONObject("np")
    assertFalse(np.getBoolean("active"))
    assertFalse(np.getBoolean("canSeek"))
    val volume = np.getJSONObject("volume")
    assertEquals(7, volume.getInt("level"))
    assertEquals(15, volume.getInt("max"))
    assertFalse(volume.getBoolean("muted"))
    assertTrue(volume.getBoolean("canSet"))
  }

  @Test fun volumeCapabilityAndMuteComeFromAudioManager() {
    val fixture = Fixture()
    `when`(fixture.audio.isStreamMute(AudioManager.STREAM_MUSIC)).thenReturn(true)
    `when`(fixture.audio.isVolumeFixed).thenReturn(true)
    val fixed = fixture.nowplaying().json().getJSONObject("np").getJSONObject("volume")
    assertTrue(fixed.getBoolean("muted"))
    assertFalse(fixed.getBoolean("canSet"))
    `when`(fixture.audio.isVolumeFixed).thenReturn(false)
    `when`(fixture.audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).thenReturn(0)
    val zero = fixture.nowplaying().json().getJSONObject("np").getJSONObject("volume")
    assertFalse(zero.getBoolean("canSet"))
  }

  @Test fun statusReadFailureIsReported() {
    val fixture = Fixture()
    `when`(fixture.audio.getStreamVolume(AudioManager.STREAM_MUSIC)).thenThrow(IllegalStateException("Synthetic audio failure"))
    val response = fixture.nowplaying()
    assertEquals(500, response.status)
    assertFalse(response.json().getBoolean("ok"))
  }

  private class Fixture {
    val context = Mockito.mock(Context::class.java)
    val audio = Mockito.mock(AudioManager::class.java)
    private val prefs = Mockito.mock(SharedPreferences::class.java)
    private val routes = RemoteRoutes(context)

    init {
      `when`(context.getSharedPreferences("remote_pairing", Context.MODE_PRIVATE)).thenReturn(prefs)
      `when`(prefs.getString("sessions", null)).thenReturn("synthetic-media-session")
      `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audio)
      `when`(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).thenReturn(7)
      `when`(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).thenReturn(15)
    }

    fun request(body: String, method: String = "POST", authorized: Boolean = true): FleetHttpServer.Response =
        dispatch("/remote/volume", method, body, authorized)

    fun nowplaying(): FleetHttpServer.Response = dispatch("/remote/nowplaying", "GET", "", true)

    private fun dispatch(path: String, method: String, body: String, authorized: Boolean): FleetHttpServer.Response {
      val bytes = body.toByteArray(Charsets.UTF_8)
      return routes.handle(FleetHttpServer.Request(method, path, "",
          if (authorized) mapOf("authorization" to "Bearer synthetic-media-session") else emptyMap(),
          ByteArrayInputStream(bytes), bytes.size))
    }
  }

  private fun FleetHttpServer.Response.json(): JSONObject = JSONObject(String(requireNotNull(jsonBytes), Charsets.UTF_8))
}
