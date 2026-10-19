/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as SessionState
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class MultiRoomServiceArtworkTest {
  @Test fun trackBPublishesImmediatelyWithoutBorrowingTrackAsCover() = fixture { f ->
    f.state(f.b)
    assertEquals(f.b, f.value("lastPlaying"))
    assertNull(f.value("lastCover"))
    f.assertPresentation("B", SessionState.STATE_PLAYING, null)
  }

  @Test fun deferredTrackBThenPauseAndIdleHoldsBWithoutTrackAsCover() = fixture { f ->
    f.state(f.b)
    f.pause()
    f.state(NowPlayingState(PlaybackState.IDLE))
    // B's deferred load is superseded before it starts; no fixture URL is opened.
    f.executor.drain()
    f.drainCallbacks()
    assertEquals(f.b, f.value("lastPlaying"))
    assertNull(f.value("lastCover"))
    assertEquals(true, f.value("userPaused"))
    f.assertPresentation("B", SessionState.STATE_PAUSED, null)
  }

  @Test fun latePlayingArtworkEnrichesBWithoutUndoingTheUsersPause() = fixture { f ->
    val cover = Mockito.mock(Bitmap::class.java)
    f.state(f.b)
    f.pause()
    f.complete(f.b, cover)
    assertEquals(true, f.value("userPaused"))
    assertEquals(f.b, f.value("lastPlaying"))
    assertSame(cover, f.value("lastCover"))
    f.assertPresentation("B", SessionState.STATE_PAUSED, cover)
  }

  @Test fun olderArtworkCannotRestoreMetadataAfterTrackCIsPublished() = fixture { f ->
    f.state(f.b)
    val c = f.b.copy(title = "C", artUrl = "http://fixture/C")
    f.state(c)
    val publications = f.metadata.size
    f.complete(f.b, Mockito.mock(Bitmap::class.java))
    assertEquals(publications, f.metadata.size)
    assertEquals(c, f.value("lastPlaying"))
    assertNull(f.value("lastCover"))
    f.assertPresentation("C", SessionState.STATE_PLAYING, null)
  }

  @Test fun coherentSameTrackCoverIsRetainedWhileItsRefreshIsDeferred() = fixture { f ->
    f.state(NowPlayingState(PlaybackState.PLAYING, title = "A", artUrl = "http://fixture/A"))
    assertSame(f.aCover, f.value("lastCover"))
    f.assertPresentation("A", SessionState.STATE_PLAYING, f.aCover)
  }

  private class Fixture(
      val service: MultiRoomService,
      val metadata: MutableList<MediaMetadata.Builder>,
      val playback: MutableList<SessionState.Builder>,
  ) {
    val executor = DeferredExecutor()
    private val callbacks = mutableListOf<Runnable>()
    val lifecycle = ServiceArtworkLifecycle(executor, { callbacks += it }, { callbacks.clear() })
    val aCover = Mockito.mock(Bitmap::class.java)
    val b = NowPlayingState(PlaybackState.PLAYING, title = "B", artUrl = "http://fixture/B")
    init {
      field("artworkLifecycle").set(service, lifecycle)
      field("session").set(service, Mockito.mock(MediaSession::class.java))
      field("lastPlaying").set(service,
          NowPlayingState(PlaybackState.PLAYING, title = "A", artUrl = "http://fixture/A"))
      field("lastCover").set(service, aCover)
    }
    fun value(name: String): Any? = field(name).get(service)
    fun field(name: String) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }
    fun state(state: NowPlayingState) {
      method("onState", NowPlayingState::class.java).invoke(service, state)
    }
    fun pause() { method("onTransport", String::class.java).invoke(service, "pause") }
    fun complete(state: NowPlayingState, cover: Bitmap) {
      method("updateArtwork", NowPlayingState::class.java, Bitmap::class.java).invoke(service, state, cover)
    }
    fun method(name: String, vararg types: Class<*>) =
        service.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }
    fun drainCallbacks() { while (callbacks.isNotEmpty()) callbacks.removeAt(0).run() }
    fun assertPresentation(title: String, state: Int, cover: Bitmap?) {
      Mockito.verify(metadata.last()).putString(MediaMetadata.METADATA_KEY_TITLE, title)
      Mockito.verify(playback.last()).setState(state, 0L, if (state == SessionState.STATE_PLAYING) 1f else 0f)
      if (cover == null) Mockito.verify(metadata.last(), Mockito.never()).putBitmap(Mockito.anyString(), Mockito.any())
      else Mockito.verify(metadata.last()).putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover)
      Mockito.verify(aCover, Mockito.never()).recycle()
    }
  }

  private fun fixture(action: (Fixture) -> Unit) {
    val service = Mockito.mock(MultiRoomService::class.java, Mockito.CALLS_REAL_METHODS)
    val context = Mockito.mock(Context::class.java)
    val prefs = Mockito.mock(SharedPreferences::class.java)
    Mockito.doReturn(context).`when`(service).applicationContext
    Mockito.`when`(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(prefs)
    Mockito.doReturn(null).`when`(service).getSystemService(NotificationManager::class.java)
    val metadata = mutableListOf<MediaMetadata.Builder>()
    val playback = mutableListOf<SessionState.Builder>()
    Mockito.mockStatic(Log::class.java).use {
      Mockito.mockStatic(Looper::class.java).use {
        Mockito.mockConstruction(Handler::class.java).use {
          Mockito.mockConstruction(MediaMetadata.Builder::class.java,
              Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)) { builder, _ -> metadata += builder }.use {
            Mockito.mockConstruction(SessionState.Builder::class.java,
                Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)) { builder, _ -> playback += builder }.use {
              val fixture = Fixture(service, metadata, playback)
              try { action(fixture) } finally { fixture.lifecycle.close() }
            }
          }
        }
      }
    }
  }

  private class DeferredExecutor : AbstractExecutorService() {
    private val tasks = mutableListOf<Runnable>()
    private var stopped = false
    fun drain() { while (tasks.isNotEmpty()) tasks.removeAt(0).run() }
    override fun execute(command: Runnable) { check(!stopped); tasks += command }
    override fun shutdown() { stopped = true }
    override fun shutdownNow(): MutableList<Runnable> {
      stopped = true
      return tasks.toMutableList().also { tasks.clear() }
    }
    override fun isShutdown() = stopped
    override fun isTerminated() = stopped && tasks.isEmpty()
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
  }
}
