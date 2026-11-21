/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/** The first-open warm-up runs every load, survives failures; process-scoped prefs and dirs. */
class FirstOpenWarmupTest {
  @Test fun runsEveryTaskInOrderAndReportsOnlyFailures() {
    val ran = mutableListOf<String>()
    val failed = FirstOpenWarmup.run(listOf(
        FirstOpenWarmup.Task("a") { ran += "a" },
        FirstOpenWarmup.Task("b") { ran += "b"; throw IllegalStateException("keystore locked") },
        FirstOpenWarmup.Task("c") { ran += "c" },
    ))
    assertEquals(listOf("a", "b", "c"), ran)
    assertEquals(listOf("b"), failed)
  }

  @Test fun failureCallbackGetsTheTaskNameAndError() {
    val seen = mutableListOf<Pair<String, Throwable>>()
    val error = IOException("secret-bearing message")
    FirstOpenWarmup.run(listOf(FirstOpenWarmup.Task("store") { throw error })) { name, e -> seen += name to e }
    assertEquals(1, seen.size)
    assertEquals("store", seen[0].first)
    assertSame(error, seen[0].second)
  }

  @Test fun noTasksNoFailures() {
    assertEquals(emptyList<String>(), FirstOpenWarmup.run(emptyList()))
  }

  @Test fun warmsTheFirstOpenLoads() {
    val context = Mockito.mock(Context::class.java)
    assertEquals(listOf("cacheDir", "CallAutoAnswerStore", "YouTubeLibrary", "TimerStore"),
        FirstOpenWarmup.tasks(context).map { it.name })
  }

  @Test fun processPrefsReadsThroughTheApplicationContext() {
    val app = Mockito.mock(Context::class.java)
    val activity = Mockito.mock(Context::class.java)
    val prefs = Mockito.mock(SharedPreferences::class.java)
    Mockito.`when`(activity.applicationContext).thenReturn(app)
    Mockito.`when`(app.getSharedPreferences("immortal_screensaver", Context.MODE_PRIVATE)).thenReturn(prefs)
    assertSame(prefs, processPrefs(activity, "immortal_screensaver"))
    Mockito.verify(activity, Mockito.never()).getSharedPreferences(Mockito.anyString(), Mockito.anyInt())
  }

  @Test fun processPrefsFallsBackToTheGivenContext() {
    val bare = Mockito.mock(Context::class.java) // applicationContext is null before attach
    val prefs = Mockito.mock(SharedPreferences::class.java)
    Mockito.`when`(bare.getSharedPreferences("immortal_timer", Context.MODE_PRIVATE)).thenReturn(prefs)
    assertSame(prefs, processPrefs(bare, "immortal_timer"))
  }

  @Test fun memoResolvesOnceThenServesFromMemory() {
    val memo = FirstValueMemo<File>()
    var calls = 0
    val first = memo.get { calls++; File("/data/cache") }
    val second = memo.get { calls++; File("/elsewhere") }
    assertEquals(1, calls)
    assertSame(first, second)
  }

  @Test fun memoDoesNotRememberAFailedResolve() {
    val memo = FirstValueMemo<File>()
    val thrown = runCatching { memo.get { throw IllegalStateException("storage not ready") } }.exceptionOrNull()
    assertTrue(thrown is IllegalStateException)
    assertEquals(File("/data/cache"), memo.get { File("/data/cache") })
  }

  @Test fun memoAcrossThreadsServesOneValue() {
    val memo = FirstValueMemo<File>()
    val dir = File("/data/cache")
    val worker = Thread { memo.get { dir } }.apply { start(); join() }
    assertTrue(!worker.isAlive)
    assertSame(dir, memo.get { File("/other") })
  }
}
