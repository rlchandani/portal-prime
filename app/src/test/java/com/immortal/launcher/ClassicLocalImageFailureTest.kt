/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import java.io.File
import java.nio.file.Files
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import sun.misc.Unsafe

/** Runs the production local-image worker and its owned Main callback, with no feed IO. */
class ClassicLocalImageFailureTest {
  private class Worker : AbstractExecutorService() {
    val tasks = ArrayDeque<Runnable>()
    private var closed = false
    override fun execute(command: Runnable) { check(!closed); tasks.addLast(command) }
    override fun shutdown() { closed = true }
    override fun shutdownNow(): MutableList<Runnable> {
      closed = true
      return tasks.toMutableList().also { tasks.clear() }
    }
    override fun isShutdown() = closed
    override fun isTerminated() = closed && tasks.isEmpty()
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    fun one() { assertTrue("Expected one queued production worker", tasks.isNotEmpty()); tasks.removeFirst().run() }
  }

  private class Fixture(root: File, count: Int) {
    val worker = Worker()
    val scheduled = mutableListOf<Pair<Runnable, Long>>()
    val main = PausableFrameCallbacks({ 0L },
        { runnable, delay -> scheduled.add(runnable to delay); true },
        { runnable -> scheduled.removeAll { it.first === runnable } })
    // Constructor-free allocation avoids unrelated Android views, executors and source startup.
    val controller = unsafe.allocateInstance(PhotoFrameController::class.java) as PhotoFrameController
    val files = (0 until count).map { File(root, "$it.jpg").apply { createNewFile() } }
    val photo = Mockito.mock(ImageView::class.java)
    val back = Mockito.mock(ImageView::class.java)
    val bitmap = Mockito.mock(Bitmap::class.java)

    init {
      set("io", worker)
      set("ui", main)
      set("settings", ScreensaverConfig.Settings(mode = ScreensaverConfig.MODE_CLASSIC,
          fit = ScreensaverConfig.FIT_FIT, intervalSec = 17))
      set("photosOnly", true)
      set("hostActive", true)
      set("viewerControls", Mockito.mock(SlideshowViewerControls::class.java))
      set("photo", photo)
      set("photoBack", back)
      set("faceRendererDelegate", lazyOf(Mockito.mock(FaceRenderer::class.java)))
      set("playlist", files.map { MediaItem(it.path, false) })
      set("localMode", true)
      set("localIndex", -1)
      set("history", ArrayList<Bitmap>())
      set("index", -1)
      for (name in listOf("localTick", "rotate", "remoteTick", "remoteRefresh")) {
        val type = PhotoFrameController::class.java.getDeclaredField(name).type
        val callback = type.getDeclaredConstructor(PhotoFrameController::class.java).apply {
          isAccessible = true
        }.newInstance(controller)
        set(name, callback)
      }
    }

    fun set(name: String, value: Any?) {
      PhotoFrameController::class.java.getDeclaredField(name).apply {
        isAccessible = true; set(controller, value)
      }
    }
    fun value(name: String): Any? = PhotoFrameController::class.java.getDeclaredField(name).run {
      isAccessible = true; get(controller)
    }
    fun advance(automatic: Boolean = true) {
      PhotoFrameController::class.java.getDeclaredMethod("advanceLocal",
          Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply {
        isAccessible = true
      }.invoke(controller, 1, automatic)
    }
    fun dispatch() {
      val at = scheduled.indexOfFirst { it.second == 0L }
      assertTrue("Expected one owned production Main callback", at >= 0)
      scheduled.removeAt(at).first.run()
    }
    fun complete() { worker.one(); dispatch() }
    fun assertLocal() = assertEquals(true, value("localMode"))
    fun assertFallback() {
      assertEquals("An exhausted local pass must enter the existing feed fallback", false, value("localMode"))
      assertEquals("Fallback queues one feed worker; the fixture never executes it", 1, worker.tasks.size)
      assertEquals("Fallback retains the configured hold", listOf(17_000L), scheduled.map { it.second })
    }
  }

  private fun fixture(count: Int, goodIndex: Int? = null, test: (Fixture) -> Unit) {
    val root = Files.createTempDirectory("classic-local-failures").toFile()
    // Only an explicitly generated success is adapted. Empty files still run real preflight.
    val original = SlideshowDecoder
    val decoder = Mockito.spy(original)
    val instance = SlideshowDecoder::class.java.getDeclaredField("INSTANCE")
    val base = unsafe.staticFieldBase(instance)
    val offset = unsafe.staticFieldOffset(instance)
    var active: Fixture? = null
    try {
      val fixture = Fixture(root, count).also { active = it }
      goodIndex?.let {
        Mockito.doReturn(fixture.bitmap).`when`(decoder).file(fixture.files[it].path, 2400)
      }
      unsafe.putObjectVolatile(base, offset, decoder)
      Mockito.mockConstruction(AnimatorSet::class.java).use {
        Mockito.mockConstruction(AccelerateDecelerateInterpolator::class.java).use {
          Mockito.mockStatic(ObjectAnimator::class.java).use { test(fixture) }
        }
      }
    } finally {
      active?.main?.close()
      active?.worker?.shutdownNow()
      unsafe.putObjectVolatile(base, offset, original)
      root.deleteRecursively()
    }
  }

  @Test fun singleEmptyImageFallsBackAfterItsAcceptedFailure() = fixture(1) {
    it.advance()
    it.worker.one()
    it.assertLocal() // Worker completion alone cannot mutate Main-owned playback.
    it.dispatch()
    it.assertFallback()
  }

  @Test fun threeEmptyImagesFallBackOnlyAfterOneCompletePass() = fixture(3) {
    it.advance()
    repeat(2) { _ -> it.complete(); it.assertLocal() }
    it.complete()
    it.assertFallback()
  }

  @Test fun manualAdvanceAlsoSettlesOnTheExistingFallback() = fixture(1) {
    it.advance(automatic = false)
    it.complete()
    it.assertFallback()
  }

  @Test fun acceptedGoodImageResetsFailuresAcrossRepeatedMixedPasses() = fixture(3, goodIndex = 2) {
    repeat(3) { _ ->
      it.advance()
      repeat(3) { _ -> it.complete() }
      it.assertLocal()
      assertTrue(it.worker.tasks.isEmpty())
      assertEquals(listOf(17_000L), it.scheduled.map { task -> task.second })
    }
    Mockito.verify(it.back, Mockito.times(3)).setImageBitmap(it.bitmap)
    Mockito.verify(it.bitmap, Mockito.never()).recycle()
  }

  @Test fun staleNullMainCallbackNeitherCountsNorAdvances() = fixture(1) {
    it.advance()
    it.worker.one()
    it.set("gen", 9)
    it.dispatch()
    it.assertLocal()
    assertEquals(9, it.value("gen"))
    assertEquals(0, it.value("localIndex"))
    assertTrue(it.worker.tasks.isEmpty())
  }

  @Test fun stoppedNullMainCallbackNeitherCountsNorAdvances() = fixture(1) {
    it.advance()
    it.worker.one()
    it.set("stopped", true)
    it.dispatch()
    it.assertLocal()
    assertTrue(it.worker.tasks.isEmpty())
    assertTrue(it.scheduled.isEmpty())
  }

  @Test fun staleQueuedWorkerDoesNotPostOrAdvance() = fixture(1) {
    it.advance()
    it.set("gen", 11)
    it.worker.one()
    it.assertLocal()
    assertTrue(it.scheduled.isEmpty())
    assertTrue(it.worker.tasks.isEmpty())
  }

  @Test fun pausedFailureIsCountedOnlyWhenItsOwnedCallbackResumes() = fixture(1) {
    it.advance()
    it.main.setActive(false)
    it.worker.one()
    it.assertLocal()
    assertTrue(it.scheduled.isEmpty())
    assertEquals(1, it.main.pendingCount)
    it.main.setActive(true)
    it.dispatch()
    it.assertFallback()
  }

  @Test fun discardedNullCallbackDoesNotAdvanceOrFallback() = fixture(1) {
    it.advance()
    it.worker.one()
    val detached = it.scheduled.single().first
    it.main.close()
    detached.run()
    it.assertLocal()
    assertTrue(it.worker.tasks.isEmpty())
    assertEquals(0, it.main.pendingCount)
  }

  @Test fun discardedSuccessfulBitmapIsRecycledOnceWithoutPublication() = fixture(1, goodIndex = 0) {
    it.advance()
    it.worker.one()
    val detached = it.scheduled.single().first
    it.main.close()
    detached.run()
    Mockito.verify(it.bitmap, Mockito.times(1)).recycle()
    Mockito.verify(it.back, Mockito.never()).setImageBitmap(Mockito.any())
    it.assertLocal()
    assertTrue(it.worker.tasks.isEmpty())
  }

  companion object {
    private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
      isAccessible = true; get(null) as Unsafe
    }
  }
}
