/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito

/**
 * Deliver the exact production Runnable after controlled public start/stop calls.
 * Thread construction and Android audio boundaries are inert; no real render thread
 * or native playback runs. These checks do not establish the 500 ms join behavior.
 */
class SoundscapeOwnershipTest {
  @Test fun stoppedDeferredWorkerCannotBeginPlayback() = withFixture { f ->
    f.player.start(ScreensaverConfig.SOUND_WHITE, 50)
    f.assertOneStartedWorker()
    f.player.stop()

    f.deliver(0)

    Mockito.verify(f.track, Mockito.never()).play()
    Mockito.verify(f.track, Mockito.never())
        .write(Mockito.any(ShortArray::class.java), Mockito.anyInt(), Mockito.anyInt())
  }

  @Test fun retiredWorkerCannotUseTheNextRunsPlayingFlag() = withFixture { f ->
    f.player.start(ScreensaverConfig.SOUND_WHITE, 50)
    f.player.stop()
    f.player.start(ScreensaverConfig.SOUND_RAIN, 60)
    assertEquals(2, f.workers.size)
    f.workers.forEach { Mockito.verify(it.thread).start() }

    f.deliver(0)

    Mockito.verify(f.track, Mockito.never()).play()
    Mockito.verify(f.track, Mockito.never())
        .write(Mockito.any(ShortArray::class.java), Mockito.anyInt(), Mockito.anyInt())
  }

  @Test fun negativeWriteReleasesTheAcquiredTrackWithoutAnotherStop() = withFixture { f ->
    f.player.start(ScreensaverConfig.SOUND_WHITE, 50)
    f.assertOneStartedWorker()

    f.deliver(0)

    Mockito.verify(f.track).play()
    Mockito.verify(f.track).write(Mockito.any(ShortArray::class.java), Mockito.eq(0), Mockito.eq(8820))
    Mockito.verify(f.track, Mockito.times(1)).release()
  }

  @Test fun playFailureReleasesTheAcquiredTrackWithoutAnotherStop() = withFixture { f ->
    Mockito.doThrow(IllegalStateException("generated play failure")).`when`(f.track).play()
    f.player.start(ScreensaverConfig.SOUND_WHITE, 50)
    f.assertOneStartedWorker()

    f.deliver(0)

    Mockito.verify(f.track).play()
    Mockito.verify(f.track, Mockito.never())
        .write(Mockito.any(ShortArray::class.java), Mockito.anyInt(), Mockito.anyInt())
    Mockito.verify(f.track, Mockito.times(1)).release()
  }

  @Test fun writeFailureReleasesTheAcquiredTrackWithoutAnotherStop() = withFixture { f ->
    Mockito.doThrow(IllegalStateException("generated write failure")).`when`(f.track)
        .write(Mockito.any(ShortArray::class.java), Mockito.anyInt(), Mockito.anyInt())
    f.player.start(ScreensaverConfig.SOUND_WHITE, 50)
    f.assertOneStartedWorker()

    f.deliver(0)

    Mockito.verify(f.track).play()
    Mockito.verify(f.track).write(Mockito.any(ShortArray::class.java), Mockito.eq(0), Mockito.eq(8820))
    Mockito.verify(f.track, Mockito.times(1)).release()
  }

  @Test fun currentPlaybackExplicitStopReleasesExactlyOnce() = withFixture { f ->
    f.stopOnWrite()
    f.player.start(ScreensaverConfig.SOUND_WHITE, 50)
    f.assertOneStartedWorker()

    f.deliver(0)
    f.player.stop()

    Mockito.verify(f.track).play()
    Mockito.verify(f.track).write(Mockito.any(ShortArray::class.java), Mockito.eq(0), Mockito.eq(8820))
    Mockito.verify(f.track, Mockito.times(1)).release()
  }

  @Test fun zeroVolumeCreatesNoWorkerOrTrack() = withFixture { f ->
    f.player.start(ScreensaverConfig.SOUND_WHITE, 0)
    f.player.stop()

    assertEquals(0, f.workers.size)
    assertEquals(0, f.trackBuilders)
    Mockito.verifyNoInteractions(f.track)
  }

  @Test fun offStartPreservesCurrentRunUntilItsExplicitStop() = withFixture { f ->
    f.stopOnWrite()
    f.player.start(ScreensaverConfig.SOUND_WHITE, 50)
    f.player.start(ScreensaverConfig.SOUND_OFF, 50)
    f.assertOneStartedWorker()

    f.deliver(0)
    f.player.stop()

    Mockito.verify(f.track).play()
    Mockito.verify(f.track, Mockito.times(1)).release()
  }

  private data class Worker(val runnable: Runnable, val thread: Thread)

  private class Fixture {
    val player = SoundscapePlayer()
    val track = Mockito.mock(AudioTrack::class.java)
    val workers = mutableListOf<Worker>()
    var trackBuilders = 0

    init {
      Mockito.`when`(track.write(
          Mockito.any(ShortArray::class.java), Mockito.anyInt(), Mockito.anyInt())).thenReturn(-1)
    }

    fun assertOneStartedWorker() {
      assertEquals("Capture the actual Runnable from public start", 1, workers.size)
      Mockito.verify(workers.single().thread).start()
    }

    fun deliver(index: Int) {
      workers[index].runnable.run()
    }

    fun stopOnWrite() {
      Mockito.doAnswer {
        player.stop()
        8820
      }.`when`(track).write(
          Mockito.any(ShortArray::class.java), Mockito.anyInt(), Mockito.anyInt())
    }
  }

  private fun withFixture(operation: (Fixture) -> Unit) {
    val f = Fixture()
    Mockito.mockStatic(Log::class.java).use {
      Mockito.mockStatic(AudioTrack::class.java).use { audio ->
        audio.`when`<Int> {
          AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO,
              AudioFormat.ENCODING_PCM_16BIT)
        }.thenReturn(4096)
        Mockito.mockConstruction(AudioAttributes.Builder::class.java,
            Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)) { builder, _ ->
          Mockito.`when`(builder.build()).thenReturn(Mockito.mock(AudioAttributes::class.java))
        }.use {
          Mockito.mockConstruction(AudioFormat.Builder::class.java,
              Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)) { builder, _ ->
            Mockito.`when`(builder.build()).thenReturn(Mockito.mock(AudioFormat::class.java))
          }.use {
            Mockito.mockConstruction(AudioTrack.Builder::class.java,
                Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)) { builder, _ ->
              f.trackBuilders++
              Mockito.`when`(builder.build()).thenReturn(f.track)
            }.use {
              Mockito.mockConstruction(Thread::class.java) { thread, construction ->
                val arguments = construction.arguments()
                check(arguments.size == 1 && arguments[0] is Runnable) {
                  "Unexpected Thread constructor at the inert worker boundary"
                }
                f.workers.add(Worker(arguments[0] as Runnable, thread))
              }.use {
                try {
                  operation(f)
                } finally {
                  f.player.stop()
                }
              }
            }
          }
        }
      }
    }
  }
}
