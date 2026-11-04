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
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Actual PCM startup methods; Android constructors and sinks are inert generated doubles. */
class AlarmPcmStartupTest {
  @Test fun timerIncompleteWriteDoesNotPublishOrPlay() = check("TimerAlarm", Fault.SHORT_WRITE)
  @Test fun timerZeroWriteDoesNotPublishOrPlay() = check("TimerAlarm", Fault.ZERO_WRITE)
  @Test fun timerNegativeWriteDoesNotPublishOrPlay() = check("TimerAlarm", Fault.NEGATIVE_WRITE)
  @Test fun timerRejectedLoopDoesNotPublishOrPlay() = check("TimerAlarm", Fault.LOOP_RESULT)
  @Test fun timerFullStartupPublishesWithoutEarlyRelease() = check("TimerAlarm", Fault.NONE)

  @Test fun alarmIncompleteWriteDoesNotPublishOrPlay() = check("AlarmSound", Fault.SHORT_WRITE)
  @Test fun alarmZeroWriteDoesNotPublishOrPlay() = check("AlarmSound", Fault.ZERO_WRITE)
  @Test fun alarmNegativeWriteDoesNotPublishOrPlay() = check("AlarmSound", Fault.NEGATIVE_WRITE)
  @Test fun alarmRejectedLoopDoesNotPublishOrPlay() = check("AlarmSound", Fault.LOOP_RESULT)
  @Test fun alarmFullStartupPublishesWithoutEarlyRelease() = check("AlarmSound", Fault.NONE)

  private enum class Fault {
    NONE, SHORT_WRITE, ZERO_WRITE, NEGATIVE_WRITE, LOOP_RESULT,
  }

  private fun check(ownerName: String, fault: Fault) {
    Mockito.mockStatic(Log::class.java).use {
      Mockito.mockStatic(Looper::class.java).use { loops ->
        loops.`when`<Looper> { Looper.getMainLooper() }
            .thenReturn(Mockito.mock(Looper::class.java))
        Mockito.mockConstruction(Handler::class.java).use {
          Mockito.mockConstruction(AudioAttributes.Builder::class.java,
              Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)) { builder, _ ->
            Mockito.`when`(builder.build()).thenReturn(Mockito.mock(AudioAttributes::class.java))
          }.use {
            Mockito.mockConstruction(AudioFormat.Builder::class.java,
                Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)) { builder, _ ->
              Mockito.`when`(builder.build()).thenReturn(Mockito.mock(AudioFormat::class.java))
            }.use {
              val allocated = mutableListOf<AudioTrack>()
              val builtTrack = Mockito.mock(AudioTrack::class.java)
              configure(builtTrack, fault)
              Mockito.mockConstruction(AudioTrack.Builder::class.java,
                  Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)) { builder, _ ->
                Mockito.`when`(builder.build()).thenAnswer {
                  allocated.add(builtTrack)
                  builtTrack
                }
              }.use {
                // The JVM Android stub may select the legacy constructor. Both acquisition
                // branches are inert; this does not claim native API-level execution.
                Mockito.mockConstruction(AudioTrack::class.java) { track, _ ->
                  allocated.add(track)
                  configure(track, fault)
                }.use {
                  val ownerClass = Class.forName("com.immortal.launcher.$ownerName")
                  val owner = ownerClass.getField("INSTANCE").get(null)
                  val published = ownerClass.getDeclaredField("track").apply { isAccessible = true }
                  assertNull("Fixture begins with no retained track", published.get(owner))
                  try {
                    val startup = ownerClass.getDeclaredMethod("startChime").apply { isAccessible = true }
                    val result = startup.invoke(owner) as Boolean
                    assertEquals("One actual track acquisition", 1, allocated.size)
                    val track = allocated.single()
                    if (fault == Fault.NONE) {
                      assertTrue(result)
                      assertSame(track, published.get(owner))
                      Mockito.verify(track, Mockito.times(1)).play()
                      Mockito.verify(track, Mockito.never()).release()
                    } else {
                      assertFalse("A rejected PCM startup cannot report success", result)
                      assertNull("A rejected PCM startup cannot publish a track", published.get(owner))
                      Mockito.verify(track, Mockito.times(1)).release()
                      Mockito.verify(track, Mockito.never()).play()
                      if (fault in listOf(Fault.SHORT_WRITE, Fault.ZERO_WRITE,
                              Fault.NEGATIVE_WRITE)) {
                        Mockito.verify(track, Mockito.never())
                            .setLoopPoints(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())
                      }
                    }
                  } finally {
                    // Reclaim inert mocks after an intentionally failing baseline assertion.
                    // Cleanup is not substituted for any production release assertion above.
                    allocated.forEach { track ->
                      if (Mockito.mockingDetails(track).invocations.none { it.method.name == "release" })
                        track.release()
                    }
                    published.set(owner, null)
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  private fun configure(track: AudioTrack, fault: Fault) {
    Mockito.`when`(track.write(
        Mockito.any(ShortArray::class.java), Mockito.anyInt(), Mockito.anyInt())).thenAnswer { call ->
      val samples = call.getArgument<ShortArray>(0)
      val offset = call.getArgument<Int>(1)
      val size = call.getArgument<Int>(2)
      assertTrue("Actual synthesized PCM is nonempty", samples.isNotEmpty())
      assertEquals(0, offset)
      assertEquals(samples.size, size)
      when (fault) {
        Fault.SHORT_WRITE -> size - 1
        Fault.ZERO_WRITE -> 0
        Fault.NEGATIVE_WRITE -> -1
        else -> size
      }
    }
    Mockito.`when`(track.setLoopPoints(
        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())).thenAnswer {
      when (fault) {
        Fault.LOOP_RESULT -> -1
        else -> 0
      }
    }
  }
}
