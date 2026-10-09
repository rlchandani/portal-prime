/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.OutputStream
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.MockedStatic
import org.mockito.Mockito

class RemoteArtTest {
  private lateinit var logs: MockedStatic<Log>
  private var previous: NowPlayingState? = null
  private val context = Mockito.mock(Context::class.java)

  @Before fun setup() {
    logs = Mockito.mockStatic(Log::class.java)
    previous = NowPlayingHub.current
  }
  @After fun restore() { NowPlayingHub.publish(previous); logs.close() }

  @Test fun staleRequestedVersionCannotCacheTheNextTracksCoverUnderTheOldVersion() {
    val old = track("Old fixture")
    val next = track("Next fixture")
    val oldVersion = RemoteMedia.stateJson(old).getInt("artVersion").toString()
    NowPlayingHub.publish(next)
    val response = art("v=$oldVersion")
    assertEquals(409, response.status)
    assertEquals("stale_art", org.json.JSONObject(String(response.jsonBytes!!)).getString("error"))
    Mockito.verify(next.artBitmap!!, Mockito.never()).compress(Mockito.any(), Mockito.anyInt(), Mockito.any())
  }

  @Test fun matchingVersionAndLegacyVersionlessRequestsStillRelayTheCover() {
    val state = track("Current fixture")
    NowPlayingHub.publish(state)
    for (query in listOf("", "v=${RemoteMedia.stateJson(state).getInt("artVersion")}")) {
      val response = art(query)
      assertEquals(200, response.status)
      val out = java.io.ByteArrayOutputStream()
      response.streamWriter!!(out)
      assertArrayEquals(byteArrayOf(1, 2, 3), out.toByteArray())
    }
  }

  @Test fun resolvingACapturedSnapshotNeverSubstitutesTheNewLiveTrack() {
    val captured = track("Captured fixture", byteArrayOf(11))
    val live = track("New live fixture", byteArrayOf(22))
    NowPlayingHub.publish(live)
    assertArrayEquals(byteArrayOf(11), RemoteMedia.artPng(context, captured))
    Mockito.verify(live.artBitmap!!, Mockito.never()).compress(Mockito.any(), Mockito.anyInt(), Mockito.any())
  }

  private fun track(title: String, bytes: ByteArray = byteArrayOf(1, 2, 3)): NowPlayingState {
    val bitmap = Mockito.mock(Bitmap::class.java)
    Mockito.doAnswer { invocation ->
      invocation.getArgument<OutputStream>(2).write(bytes); true
    }.`when`(bitmap).compress(Mockito.any(), Mockito.anyInt(), Mockito.any())
    return NowPlayingState(PlaybackState.PLAYING, title = title, artBitmap = bitmap)
  }
  private fun art(query: String) = RemoteRoutes(context).handle(FleetHttpServer.Request(
      "GET", "/remote/art", query, emptyMap(), ByteArrayInputStream(byteArrayOf()), 0))
}
