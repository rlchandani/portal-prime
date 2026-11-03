/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetFileDescriptor
import android.content.res.Resources
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.FileDescriptor
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Actual cue methods; all players, resources and queued Android work are inert doubles. */
class ChimePlayerResourceTest {
  private enum class Cue { CHIME, TIMER, PING, SUNSET }

  @Test fun nullAssetRetiresEveryAllocatedCuePlayer() = eachCue("null") {
    Mockito.verify(it.player, Mockito.times(1)).release()
    Mockito.verify(it.player, Mockito.never()).start()
  }

  @Test fun assetOpenExceptionRetiresEveryAllocatedCuePlayer() = eachCue("open") {
    Mockito.verify(it.player, Mockito.times(1)).release()
  }

  @Test fun attributesExceptionRetiresEveryAllocatedCuePlayer() = eachCue("attributes") {
    Mockito.verify(it.player, Mockito.times(1)).release()
  }

  @Test fun dataSourceExceptionRetiresPlayerAndClosesAsset() = eachCue("source") {
    Mockito.verify(it.player, Mockito.times(1)).release()
    Mockito.verify(it.asset, Mockito.times(1)).close()
  }

  @Test fun volumeExceptionRetiresVolumeControlledPlayers() {
    assertEvery(listOf(Cue.CHIME, Cue.PING, Cue.SUNSET)) { cue ->
      Harness("volume").withPlatform {
        it.play(cue)
        Mockito.verify(it.player, Mockito.times(1)).release()
      }
    }
  }

  @Test fun listenerInstallationExceptionsRetirePlayers() {
    assertEvery(listOf("completion", "error")) { stage ->
      eachCue(stage) { Mockito.verify(it.player, Mockito.times(1)).release() }
    }
  }

  @Test fun prepareExceptionRetiresEveryAllocatedCuePlayer() = eachCue("prepare") {
    Mockito.verify(it.player, Mockito.times(1)).release()
    Mockito.verify(it.player, Mockito.never()).start()
  }

  @Test fun startExceptionRetiresEveryAllocatedCuePlayer() = eachCue("start") {
    Mockito.verify(it.player, Mockito.times(1)).release()
  }

  @Test fun successfulPlaybackWaitsForCompletionThenReleasesOnlyOnce() = eachCue {
    Mockito.verify(it.player, Mockito.never()).release()
    Mockito.verify(it.player, Mockito.times(1)).prepare()
    Mockito.verify(it.player, Mockito.times(1)).start()
    it.completion!!.onCompletion(it.player)
    it.completion!!.onCompletion(it.player)
    assertTrue(it.error!!.onError(it.player, 1, 2))
    Mockito.verify(it.player, Mockito.times(1)).release()
  }

  @Test fun errorCallbackThenThrowAndDuplicateCallbacksReleaseOnlyOnce() = eachCue("errorThenThrow") {
    assertTrue(it.error!!.onError(it.player, 1, 2))
    it.completion!!.onCompletion(it.player)
    Mockito.verify(it.player, Mockito.times(1)).release()
  }

  @Test fun mutedVolumeControlledCuesAllocateNothing() {
    for (cue in listOf(Cue.CHIME, Cue.PING, Cue.SUNSET)) Harness(volume = 0).withPlatform {
      it.play(cue)
      assertTrue(it.players.isEmpty())
      assertTrue(it.jobs.isEmpty())
      Mockito.verifyNoInteractions(it.resources)
    }
  }

  @Test fun resourceVolumeAndQueuedRepeatContractsRemain() {
    Harness(volume = 40).withPlatform {
      it.play(Cue.CHIME)
      Mockito.verify(it.resources).openRawResourceFd(R.raw.chime)
      Mockito.verify(it.player).setVolume(.4f, .4f)
      Mockito.verify(it.asset).close()
    }
    Harness(volume = 0).withPlatform {
      ChimePlayer.playTimerRing(it.context)
      assertEquals(listOf(0L, 1400L, 2800L), it.jobs.map { job -> job.second })
      assertTrue(it.players.isEmpty())
      it.jobs.forEach { job -> job.first.run() }
      assertEquals(3, it.players.size)
      it.players.forEach { player -> Mockito.verify(player, Mockito.never()).setVolume(Mockito.anyFloat(), Mockito.anyFloat()) }
      Mockito.verify(it.resources, Mockito.times(3)).openRawResourceFd(R.raw.chime)
    }
    Harness(volume = 40).withPlatform {
      ChimePlayer.playPing(it.context)
      assertEquals(listOf(0L, 1400L), it.jobs.map { job -> job.second })
      assertTrue(it.players.isEmpty())
      it.jobs.forEach { job -> job.first.run() }
      assertEquals(2, it.players.size)
      it.players.forEach { player -> Mockito.verify(player).setVolume(.4f, .4f) }
      Mockito.verify(it.resources, Mockito.times(2)).openRawResourceFd(R.raw.ping)
    }
    Harness().withPlatform {
      ChimePlayer.playSunsetTone(it.context)
      Mockito.verify(it.resources).openRawResourceFd(R.raw.golden_sunset)
      Mockito.verify(it.player).setVolume(.7f, .7f)
    }
    Harness(variant = 1).withPlatform {
      ChimePlayer.playSunriseTone(it.context)
      Mockito.verify(it.resources).openRawResourceFd(R.raw.golden_sunrise_2)
    }
  }

  private fun eachCue(stage: String = "", check: (Harness) -> Unit) {
    assertEvery(Cue.entries) { cue ->
      Harness(stage).withPlatform {
        it.play(cue)
        assertEquals("Each unmuted invocation must construct exactly one player", 1, it.players.size)
        check(it)
      }
    }
  }

  private fun <T> assertEvery(cases: List<T>, check: (T) -> Unit) {
    val failures = mutableListOf<Pair<T, Throwable>>()
    for (case in cases) try { check(case) } catch (failure: Throwable) { failures.add(case to failure) }
    if (failures.isNotEmpty()) {
      val failure = AssertionError("Failed actual cue cases: ${failures.map { it.first }}")
      failures.forEach { failure.addSuppressed(it.second) }
      throw failure
    }
  }

  private class Harness(val stage: String = "", val volume: Int = 70, val variant: Int = 0) {
    val context = Mockito.mock(Context::class.java)
    val prefs = Mockito.mock(SharedPreferences::class.java)
    val resources = Mockito.mock(Resources::class.java)
    val asset = Mockito.mock(AssetFileDescriptor::class.java)
    val jobs = mutableListOf<Pair<Runnable, Long>>()
    val players = mutableListOf<MediaPlayer>()
    val player get() = players.single()
    var completion: MediaPlayer.OnCompletionListener? = null
    var error: MediaPlayer.OnErrorListener? = null

    init {
      Mockito.`when`(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(prefs)
      Mockito.`when`(context.resources).thenReturn(resources)
      Mockito.`when`(prefs.contains("volume")).thenReturn(true)
      Mockito.`when`(prefs.getInt(Mockito.anyString(), Mockito.anyInt())).thenAnswer {
        when (it.getArgument<String>(0)) { "volume" -> volume; "sunrise_variant" -> variant; else -> it.getArgument<Int>(1) }
      }
      Mockito.`when`(asset.fileDescriptor).thenReturn(FileDescriptor())
      Mockito.`when`(asset.startOffset).thenReturn(0L)
      Mockito.`when`(asset.length).thenReturn(4L)
      if (stage == "open") Mockito.`when`(resources.openRawResourceFd(Mockito.anyInt())).thenThrow(Resources.NotFoundException("owned missing asset"))
      else Mockito.`when`(resources.openRawResourceFd(Mockito.anyInt())).thenReturn(if (stage == "null") null else asset)
    }

    fun play(cue: Cue) {
      when (cue) {
        Cue.CHIME -> ChimePlayer.playChime(context)
        Cue.TIMER -> ChimePlayer.playTimerRing(context, 1)
        Cue.PING -> ChimePlayer.playPing(context, 1)
        Cue.SUNSET -> ChimePlayer.playSunsetTone(context)
      }
      jobs.toList().forEach { it.first.run() }
    }

    fun withPlatform(operation: (Harness) -> Unit) {
      Mockito.mockStatic(Log::class.java).use {
        Mockito.mockStatic(Looper::class.java).use { loops ->
          loops.`when`<Looper> { Looper.getMainLooper() }.thenReturn(Mockito.mock(Looper::class.java))
          Mockito.mockConstruction(AudioAttributes.Builder::class.java,
              Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)) { builder, _ ->
            Mockito.`when`(builder.build()).thenReturn(Mockito.mock(AudioAttributes::class.java))
          }.use {
            Mockito.mockConstruction(Handler::class.java) { handler, _ ->
              Mockito.doAnswer { call ->
                jobs.add(call.getArgument<Runnable>(0) to call.getArgument<Long>(1)); true
              }.`when`(handler).postDelayed(Mockito.any(Runnable::class.java), Mockito.anyLong())
            }.use {
              Mockito.mockConstruction(MediaPlayer::class.java) { mp, _ ->
                players.add(mp)
                Mockito.doAnswer { call ->
                  completion = call.getArgument(0)
                  if (stage == "completion") throw IOException("owned listener failure")
                  null
                }.`when`(mp).setOnCompletionListener(Mockito.any(MediaPlayer.OnCompletionListener::class.java))
                Mockito.doAnswer { call ->
                  error = call.getArgument(0)
                  if (stage == "error") throw IOException("owned listener failure")
                  null
                }.`when`(mp).setOnErrorListener(Mockito.any(MediaPlayer.OnErrorListener::class.java))
                when (stage) {
                  "attributes" -> Mockito.doThrow(IllegalStateException("owned attributes failure")).`when`(mp).setAudioAttributes(Mockito.any(AudioAttributes::class.java))
                  "source" -> Mockito.doThrow(IOException("owned source failure")).`when`(mp).setDataSource(Mockito.any(FileDescriptor::class.java), Mockito.anyLong(), Mockito.anyLong())
                  "volume" -> Mockito.doThrow(IllegalStateException("owned volume failure")).`when`(mp).setVolume(Mockito.anyFloat(), Mockito.anyFloat())
                  "prepare" -> Mockito.doThrow(IOException("owned prepare failure")).`when`(mp).prepare()
                  "start" -> Mockito.doThrow(IllegalStateException("owned start failure")).`when`(mp).start()
                  "errorThenThrow" -> Mockito.doAnswer {
                    assertTrue(error!!.onError(mp, 1, 2))
                    throw IllegalStateException("owned start failure after error")
                  }.`when`(mp).start()
                }
              }.use { operation(this) }
            }
          }
        }
      }
    }
  }
}
