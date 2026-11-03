/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Real camera parsing and mutations; generated preferences are the only replaced boundary. */
class CameraConfigLegacyIdentityTest {
  @Test fun selectingTheLaterDuplicateResolvesThatCameraAcrossReload() = fixture(duplicates()) {
    val selected = CameraConfig.load(it.context)[1]
    val resolved = CameraConfig.load(it.context).firstOrNull { camera -> camera.id == selected.id }
    assertEquals("Back", resolved?.name)
    assertEquals("rtsp://back.local/live", resolved?.url)
    it.assertReadOnly()
  }

  @Test fun removingTheSecondDuplicatePreservesTheFirstCamera() = fixture(duplicates()) {
    val before = CameraConfig.load(it.context)
    val after = CameraConfig.removeAndLoad(it.context, before[1].id)
    assertEquals(listOf(before[0]), after)
    assertEquals(after, CameraConfig.load(it.context))
    assertEquals(1, it.applies)
  }

  @Test fun removingTheFirstDuplicatePreservesTheSecondCamera() = fixture(duplicates()) {
    val before = CameraConfig.load(it.context)
    val after = CameraConfig.removeAndLoad(it.context, before[0].id)
    assertEquals(listOf(before[1]), after)
    assertEquals(after, CameraConfig.load(it.context))
    assertEquals(1, it.applies)
  }

  @Test fun replacementIdsReserveAllLaterExistingIdsIncludingLongBoundaries() = fixture(listOf(
      camera(42L, "First"), camera(42L, "Duplicate"), camera(43L, "Later"),
      camera(0L, "Zero"), camera(-1L, "Negative"),
      camera(Long.MIN_VALUE, "Minimum"), camera(Long.MAX_VALUE, "Maximum"),
  )) {
    val loaded = CameraConfig.load(it.context)
    assertDistinct(loaded)
    for (index in listOf(0, 2, 3, 4, 5, 6)) {
      assertEquals(listOf(42L, 42L, 43L, 0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE)[index],
          loaded[index].id)
    }
    assertEquals(loaded, CameraConfig.load(it.context))
    it.assertReadOnly()
  }

  @Test fun duplicateMaximumIdDoesNotStealTheReservedMinimumId() = fixture(listOf(
      camera(Long.MAX_VALUE, "Maximum"), camera(Long.MAX_VALUE, "Duplicate maximum"),
      camera(Long.MIN_VALUE, "Reserved minimum"), camera(0L, "Reserved zero"),
  )) {
    val loaded = CameraConfig.load(it.context)
    assertDistinct(loaded)
    assertEquals(Long.MAX_VALUE, loaded[0].id)
    assertEquals(Long.MIN_VALUE, loaded[2].id)
    assertEquals(0L, loaded[3].id)
    assertEquals(loaded, CameraConfig.load(it.context))
    it.assertReadOnly()
  }

  @Test fun multipleDuplicateGroupsPreserveNamesUrlsOrderAndFirstIds() = fixture(listOf(
      camera(-5L, "A \"quoted\"", "rtsp://a.local/one?x=1&y=2"),
      camera(-5L, "B", "rtsp://b.local/two"),
      camera(9L, "C", "rtsp://c.local/three"),
      camera(9L, "D", "rtsp://d.local/four"),
  )) {
    val loaded = CameraConfig.load(it.context)
    assertDistinct(loaded)
    assertEquals(listOf("A \"quoted\"", "B", "C", "D"), loaded.map { camera -> camera.name })
    assertEquals(listOf("rtsp://a.local/one?x=1&y=2", "rtsp://b.local/two",
        "rtsp://c.local/three", "rtsp://d.local/four"), loaded.map { camera -> camera.url })
    assertEquals(-5L, loaded[0].id)
    assertEquals(9L, loaded[2].id)
    assertEquals(loaded, CameraConfig.load(it.context))
    it.assertReadOnly()
  }

  @Test fun identicalDuplicateRecordsRemainTwoIndependentlyAddressableCameras() =
      fixture(listOf(camera(7L, "Same"), camera(7L, "Same"))) {
        val before = CameraConfig.load(it.context)
        assertEquals(2, before.size)
        assertDistinct(before)
        assertEquals(listOf(before[0]), CameraConfig.removeAndLoad(it.context, before[1].id))
      }

  @Test fun missingNullAndInvalidIdsAreStableWithoutPreferenceWrites() = fixture(listOf(
      camera(null, "Missing"), camera(JSONObject.NULL, "Null"),
      camera("not-a-long", "Invalid"), camera(0L, "Existing zero"),
  )) {
    val first = CameraConfig.load(it.context)
    assertEquals(4, first.size)
    assertDistinct(first)
    assertEquals(0L, first.last().id)
    repeat(4) { _ -> assertEquals(first, CameraConfig.load(it.context)) }
    it.assertReadOnly()
  }

  @Test fun uniqueIdsIncludingZeroAndNegativeRemainExactlyUnchangedAndReadOnly() =
      fixture(listOf(camera(0L, "Zero"), camera(-1L, "Negative"),
          camera(Long.MIN_VALUE, "Minimum"), camera(Long.MAX_VALUE, "Maximum"))) {
        val first = CameraConfig.load(it.context)
        assertEquals(listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE), first.map { camera -> camera.id })
        assertEquals(first, CameraConfig.load(it.context))
        it.assertReadOnly()
      }

  @Test fun blankUrlsStillFilterOutWithoutChangingVisibleCameraFields() = fixture(listOf(
      camera(8L, "Blank", " "), camera(9L, "Empty", ""),
      camera(11L, "Visible", "  rtsp://visible.local/live  "),
  )) {
    assertEquals(listOf(CameraConfig.Camera(11L, "Visible", "  rtsp://visible.local/live  ")),
        CameraConfig.load(it.context))
    it.assertReadOnly()
  }

  @Test fun addPersistsTheNormalizedSnapshotAndKeepsAllExistingCameras() = fixture(duplicates()) {
    val before = CameraConfig.load(it.context)
    val after = CameraConfig.addAndLoad(it.context, "", "  rtsp://added.local/live  ")
    assertEquals(before, after.take(2))
    assertDistinct(after)
    assertEquals("Camera", after.last().name)
    assertEquals("rtsp://added.local/live", after.last().url)
    assertEquals(after, CameraConfig.load(it.context))
    assertEquals(1, it.applies)
  }

  @Test fun removePersistsRemainingReplacementIdsWithoutReassigningThem() = fixture(listOf(
      camera(42L, "First"), camera(42L, "Second"), camera(42L, "Third"),
  )) {
    val before = CameraConfig.load(it.context)
    val expected = listOf(before[0], before[2])
    assertEquals(expected, CameraConfig.removeAndLoad(it.context, before[1].id))
    assertEquals(expected, CameraConfig.load(it.context))
    assertDistinct(expected)
    assertEquals(1, it.applies)
  }

  private fun duplicates() = listOf(camera(42L, "Front", "rtsp://front.local/live"),
      camera(42L, "Back", "rtsp://back.local/live"))

  private fun camera(id: Any?, name: String, url: String = "rtsp://generated.local/live") =
      JSONObject().put("name", name).put("url", url).apply { if (id != null) put("id", id) }

  private fun assertDistinct(cameras: List<CameraConfig.Camera>) {
    assertEquals("Every retained row needs a distinct stable identity", cameras.size,
        cameras.map { it.id }.toSet().size)
  }

  private fun fixture(rows: List<JSONObject>, operation: (Fixture) -> Unit) {
    val fixture = Fixture(rows)
    try { operation(fixture) }
    finally { assertTrue("Unexpected fixture operations: ${fixture.forbidden}", fixture.forbidden.isEmpty()) }
  }

  private class Fixture(rows: List<JSONObject>) {
    private val original = JSONArray().apply { rows.forEach { put(it) } }.toString()
    private var saved = original
    var edits = 0
    var applies = 0
    val forbidden = mutableListOf<String>()
    private fun refuse(message: String): Nothing {
      forbidden += message
      throw AssertionError(message)
    }
    private val prefs = Mockito.mock(SharedPreferences::class.java) { call ->
      when (call.method.name) {
        "getString" -> {
          if (call.getArgument<String>(0) != "cameras" || call.getArgument<Any?>(1) != null)
            refuse("Unexpected camera preference read")
          saved
        }
        "edit" -> {
          edits++
          editor()
        }
        "toString" -> "GeneratedCameraPreferences"
        else -> refuse("Unexpected preferences method: ${call.method.name}")
      }
    }
    val context = Mockito.mock(Context::class.java) { call ->
      when (call.method.name) {
        "getSharedPreferences" -> {
          if (call.getArgument<String>(0) != "immortal_cameras" ||
              call.getArgument<Int>(1) != Context.MODE_PRIVATE) refuse("Unexpected preference store")
          prefs
        }
        "toString" -> "GeneratedCameraContext"
        else -> refuse("Unexpected Context method: ${call.method.name}")
      }
    }
    private fun editor(): SharedPreferences.Editor {
      var pending: String? = null
      return Mockito.mock(SharedPreferences.Editor::class.java) { call ->
        when (call.method.name) {
          "putString" -> {
            if (call.getArgument<String>(0) != "cameras") refuse("Unexpected saved key")
            pending = call.getArgument<String>(1)
            call.mock
          }
          "apply" -> {
            saved = pending ?: refuse("Missing camera transaction value")
            applies++
            null
          }
          "toString" -> "GeneratedCameraEditor"
          else -> refuse("Unexpected editor method: ${call.method.name}")
        }
      }
    }
    fun assertReadOnly() {
      assertEquals(original, saved)
      assertEquals(0, edits)
      assertEquals(0, applies)
    }
  }
}
