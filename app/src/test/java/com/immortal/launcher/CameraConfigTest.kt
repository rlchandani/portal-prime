/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class CameraConfigTest {
  /** Only Android persistence is replaced; the real config parser and mutations run. */
  private fun context(initial: List<CameraConfig.Camera> = emptyList()): Context {
    val values = ConcurrentHashMap<String, String>()
    val array = JSONArray()
    initial.forEach { camera ->
      array.put(JSONObject().put("id", camera.id).put("name", camera.name).put("url", camera.url))
    }
    values["cameras"] = array.toString()
    val context = mock(Context::class.java)
    val prefs = mock(SharedPreferences::class.java)
    `when`(context.getSharedPreferences("immortal_cameras", Context.MODE_PRIVATE)).thenReturn(prefs)
    `when`(prefs.getString("cameras", null)).thenAnswer { values["cameras"] }
    `when`(prefs.edit()).thenAnswer {
      // Each transaction gets the editor it would get from Android.
      val transaction = mock(SharedPreferences.Editor::class.java)
      val pending = HashMap<String, String>()
      `when`(transaction.putString(anyString(), anyString())).thenAnswer { call ->
        pending[call.getArgument<String>(0)] = call.getArgument<String>(1)
        transaction
      }
      org.mockito.Mockito.doAnswer { values.putAll(pending); null }.`when`(transaction).apply()
      transaction
    }
    return context
  }

  @Test
  fun appendReturnsPersistedSnapshotAndPreservesExistingCredentials() {
    val saved = CameraConfig.Camera(42L, "Entry", "rtsp://user:secret@camera.local/live")
    val context = context(listOf(saved))

    val snapshot = CameraConfig.addAndLoad(context, "", "  rtsp://other.local/stream  ")

    assertEquals(saved, snapshot.first())
    assertEquals("Camera", snapshot.last().name)
    assertEquals("rtsp://other.local/stream", snapshot.last().url)
    assertEquals(snapshot, CameraConfig.load(context))
  }

  @Test
  fun removeFromLargeCollectionReturnsOnlyTheRequestedChange() {
    val saved = (1L..1_000L).map { CameraConfig.Camera(it, "Camera $it", "rtsp://camera$it.local/live") }
    val context = context(saved)

    val snapshot = CameraConfig.removeAndLoad(context, 501L)

    assertEquals(saved.filterNot { it.id == 501L }, snapshot)
    assertEquals(snapshot, CameraConfig.load(context))
    assertEquals(snapshot, CameraConfig.removeAndLoad(context, -1L))
  }

  @Test
  fun concurrentLegacyAddsAndRemovesPreserveEveryUnrelatedCamera() {
    val existing = (1L..24L).map { CameraConfig.Camera(it, "Old $it", "rtsp://old$it.local/live") }
    val context = context(existing)
    val executor = Executors.newFixedThreadPool(8)
    val start = CountDownLatch(1)
    try {
      val tasks = (1..48).map { number ->
        executor.submit(Runnable {
          check(start.await(5, TimeUnit.SECONDS))
          if (number <= 24) CameraConfig.remove(context, number.toLong())
          else CameraConfig.add(context, "New $number", "rtsp://new$number.local/live")
        })
      }
      start.countDown()
      tasks.forEach { it.get(10, TimeUnit.SECONDS) }
      val result = CameraConfig.load(context)
      assertEquals((25..48).map { "New $it" }.toSet(), result.map { it.name }.toSet())
      assertEquals(24, result.size)
      assertEquals(result.size, result.map { it.id }.toSet().size)
      assertTrue(result.none { it.id in 1L..24L })
    } finally {
      executor.shutdownNow()
    }
  }
}
