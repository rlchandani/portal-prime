/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import android.widget.ImageView
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import sun.misc.Unsafe

/** Actual controller handoff and caption Main callback; Android rendering is captured, no IO. */
class NativeCaptionOwnershipTest {
  private data class Caption(val place: String?, val date: String?, val memory: String?)
  private val old = Caption("Previous place", "Previous date", "Previous memory")
  private val empty = Caption(null, null, null)

  private inner class Fixture(mode: String = ScreensaverConfig.MODE_WHOLE,
      paused: Boolean = false, photosOnly: Boolean = false) {
    val controller = unsafe.allocateInstance(PhotoFrameController::class.java) as PhotoFrameController
    var caption = old
    val events = mutableListOf<String>()
    val shownCaptions = mutableListOf<Caption>()
    val shownPhotos = mutableListOf<List<SlideshowPhoto>>()
    val shownAutomatic = mutableListOf<Boolean>()
    val scheduled = mutableListOf<Runnable>()
    val main = PausableFrameCallbacks({ 0L },
        { task, _ -> scheduled.add(task); true },
        { task -> scheduled.removeAll { it === task } })
    var captionInitializations = 0
    val face = Mockito.mock(FaceRenderer::class.java) { call ->
      if (call.method.name == "setCaption") {
        caption = Caption(call.arguments[0] as String?, call.arguments[1] as String?,
            call.arguments[2] as String?)
        events.add("caption")
      }
      Mockito.RETURNS_DEFAULTS.answer(call)
    }
    val stage = Mockito.mock(SlideshowStage::class.java) { call ->
      when (call.method.name) {
        "show" -> {
          shownCaptions.add(caption)
          @Suppress("UNCHECKED_CAST")
          shownPhotos.add(call.arguments[0] as List<SlideshowPhoto>)
          shownAutomatic.add(call.arguments[7] as Boolean)
          events.add("show")
        }
        "pause", "resume" -> events.add(call.method.name)
      }
      Mockito.RETURNS_DEFAULTS.answer(call)
    }
    val captionDelegate: Lazy<FaceRenderer> = if (photosOnly) lazy {
      captionInitializations++
      face
    } else lazyOf(face)
    val photos = listOf(photo("generated-a"), photo("generated-b"))

    init {
      Mockito.`when`(face.photoOcclusions()).thenReturn(emptyList())
      set("ui", main)
      set("settings", ScreensaverConfig.Settings(mode = mode, intervalSec = 17))
      set("photosOnly", photosOnly)
      set("paused", paused)
      set("gen", 7)
      set("nativeStage", stage)
      set("photo", Mockito.mock(ImageView::class.java))
      set("photoBack", Mockito.mock(ImageView::class.java))
      set("viewerControls", Mockito.mock(SlideshowViewerControls::class.java))
      set("faceRendererDelegate", captionDelegate)
    }

    private fun photo(key: String): SlideshowPhoto {
      val bitmap = Mockito.mock(Bitmap::class.java)
      Mockito.`when`(bitmap.width).thenReturn(64)
      Mockito.`when`(bitmap.height).thenReturn(32)
      return SlideshowPhoto(key, bitmap)
    }
    fun set(name: String, value: Any?) {
      PhotoFrameController::class.java.getDeclaredField(name).apply {
        isAccessible = true; set(controller, value)
      }
    }
    fun present(automatic: Boolean = true) {
      PhotoFrameController::class.java.getDeclaredMethod("presentNative", List::class.java,
          Boolean::class.javaPrimitiveType).apply { isAccessible = true }
          .invoke(controller, photos, automatic)
    }
    fun publish(date: Long, generation: Int = 7) {
      PhotoFrameController::class.java.getDeclaredMethod("publishCaption",
          PhotoCaption.Meta::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
          .invoke(controller, PhotoCaption.Meta(date, null, null), generation)
    }
    fun dispatch() {
      assertEquals("One actual production caption Main callback", 1, scheduled.size)
      scheduled.removeAt(0).run()
    }
    fun assertClearedBeforeShow(automatic: Boolean, paused: Boolean = false) {
      assertEquals(empty, caption)
      assertEquals(listOf(empty), shownCaptions)
      assertEquals(listOf(photos), shownPhotos)
      assertEquals(listOf(automatic), shownAutomatic)
      assertEquals(listOf("caption", if (paused) "pause" else "resume", "show"), events)
      assertTrue("Handoff clears synchronously, without queuing metadata work", scheduled.isEmpty())
    }
  }

  private fun fixture(mode: String = ScreensaverConfig.MODE_WHOLE,
      paused: Boolean = false, photosOnly: Boolean = false, test: (Fixture) -> Unit) {
    Mockito.mockStatic(Color::class.java).use {
      Mockito.mockStatic(Log::class.java).use {
        val f = Fixture(mode, paused, photosOnly)
        try { test(f) } finally { f.main.close() }
      }
    }
  }

  @Test fun automaticWholePhotoClearsOldCaptionBeforeStageShow() = fixture {
    it.present()
    it.assertClearedBeforeShow(automatic = true)
  }

  @Test fun pausedManualPhotoClearsOldCaptionBeforeStageShow() =
      fixture(ScreensaverConfig.MODE_SMART, paused = true) {
        it.present(automatic = false)
        it.assertClearedBeforeShow(automatic = false, paused = true)
      }

  @Test fun galleryPrintClearsOldCaptionBeforeStageShow() =
      fixture(ScreensaverConfig.MODE_GALLERY_PRINT) {
        it.present()
        it.assertClearedBeforeShow(automatic = true)
      }

  @Test fun gallerySpreadClearsOldCaptionBeforeTwoPhotoStageShow() =
      fixture(ScreensaverConfig.MODE_GALLERY_SPREAD) {
        it.present(automatic = false)
        it.assertClearedBeforeShow(automatic = false)
      }

  @Test fun currentGeneratedDateRepopulatesOnlyAfterMainDispatch() = withDefaults {
    fixture {
      it.present()
      it.assertClearedBeforeShow(automatic = true)
      // Future generated date cannot produce the wall-clock-dependent "years ago" badge.
      it.publish(Instant.parse("2060-01-02T12:00:00Z").toEpochMilli())
      assertEquals(empty, it.caption)
      assertEquals(1, it.main.pendingCount)
      it.dispatch()
      assertEquals(Caption(null, "January 2, 2060", null), it.caption)
      assertEquals(listOf("caption", "resume", "show", "caption"), it.events)
    }
  }

  @Test fun photosOnlyHandoffNeverInitializesCaptionRenderer() = fixture(photosOnly = true) {
    assertFalse(it.captionDelegate.isInitialized())
    it.present()
    assertFalse(it.captionDelegate.isInitialized())
    assertEquals(0, it.captionInitializations)
    assertEquals(old, it.caption)
    assertEquals(listOf(old), it.shownCaptions)
    assertEquals(listOf("resume", "show"), it.events)
  }

  @Test fun stoppedHandoffLeavesOldCaptionAndStageUntouched() = fixture {
    it.set("stopped", true)
    it.present(automatic = false)
    assertEquals(old, it.caption)
    assertTrue(it.events.isEmpty())
    assertTrue(it.shownCaptions.isEmpty())
    assertTrue(it.scheduled.isEmpty())
  }

  @Test fun staleGeneratedCaptionIsDroppedAtActualMainDispatch() = withDefaults {
    fixture {
      it.publish(Instant.parse("2060-01-02T12:00:00Z").toEpochMilli())
      val newer = Caption("Newer place", "Newer date", null)
      it.caption = newer
      it.set("gen", 8)
      it.dispatch()
      assertEquals(newer, it.caption)
      assertTrue(it.events.isEmpty())
      assertEquals(0, it.main.pendingCount)
    }
  }

  private fun withDefaults(test: () -> Unit) {
    val zone = TimeZone.getDefault()
    val locale = Locale.getDefault()
    val format = Locale.getDefault(Locale.Category.FORMAT)
    val display = Locale.getDefault(Locale.Category.DISPLAY)
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
      Locale.setDefault(Locale.US)
      test()
    } finally {
      TimeZone.setDefault(zone)
      Locale.setDefault(locale)
      Locale.setDefault(Locale.Category.FORMAT, format)
      Locale.setDefault(Locale.Category.DISPLAY, display)
    }
  }

  companion object {
    private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
      isAccessible = true; get(null) as Unsafe
    }
  }
}
