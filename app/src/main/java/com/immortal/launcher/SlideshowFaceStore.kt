/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Process
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.immortal.launcher.SlideshowFaceMetadata.Box
import com.immortal.launcher.SlideshowFaceMetadata.Entry
import com.immortal.launcher.SlideshowFaceMetadata.Status
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Offline metadata for future presentations. There is deliberately no rendering callback.
 *
 * [regionsFor] is a memory-only lookup: null means missing/pending/failed/stale, an empty list
 * means a successful no-face analysis. Both require conservative whole-photo framing.
 * Regions are raw credible detector boxes clamped to normalized image bounds.
 * SlideshowGeometry.plan is the sole padding/headroom policy; do not pad again here.
 *
 * [analyzeLater] must be called off the UI thread while the caller still owns its upright
 * bitmap. It synchronously draws an independent <=640px ARGB derivative; after return the
 * caller may release its display bitmap. No URL fetch, decode, EXIF or display-bitmap retention.
 * Supplied keys must include source revision/validator information when available.
 *
 * New instances supersede earlier sessions. All sessions share one low-priority worker and
 * at most one detector. Cache hydration/writes never run in the constructor or lookup.
 */
class SlideshowFaceStore(context: Context) : AutoCloseable {
  private val session = Worker.activate(File(context.applicationContext.filesDir, "slideshow_faces/metadata.bin"))

  fun regionsFor(assetKey: String): List<SlideshowGeometry.Region>? =
      Worker.lookup(session, SlideshowFaceMetadata.key(assetKey))?.let { entry ->
        when (entry.status) {
          Status.FACES -> entry.regions.map {
            SlideshowGeometry.Region(it.left, it.top, it.right, it.bottom)
          }
          Status.NO_FACE -> emptyList()
          else -> null
        }
      }

  fun analyzeLater(assetKey: String, bitmap: Bitmap) {
    Worker.offer(session, SlideshowFaceMetadata.key(assetKey), bitmap)
  }

  override fun close() { Worker.close(session) }

  private object Worker {
    private const val TAG = "SlideshowFaces"
    private val timeoutNanos = TimeUnit.SECONDS.toNanos(10)
    private val lock = Any()
    private val gate = SlideshowFaceRunGate(timeoutNanos)
    private var sequence = 0L
    private var active: Long? = null
    private var cacheFile: File? = null
    private var cache: SlideshowFaceCache? = null
    private var copying = false
    private var pumping = false
    private var flushRequested = false
    private var flight: SlideshowFacePending.Job<Owned>? = null
    private var detector: FaceDetector? = null
    private var failureStreak = 0
    private val direct = Executor { it.run() }
    private val executor by lazy {
      ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2), { runnable ->
        Thread({
          runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
          runnable.run()
        }, "slideshow-faces").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
      })
    }
    private data class Owned(val pixels: SlideshowFaceLease<Bitmap>, val cache: SlideshowFaceCache)
    private val pending = SlideshowFacePending<Owned>(2) { key, owned ->
      owned.cache.forgetPending(key)
      owned.pixels.finish(taskComplete = true) // Pending jobs have never entered ML Kit.
    }

    fun activate(file: File): Long = synchronized(lock) {
      val session = ++sequence
      pending.activate(session)
      flight?.let { it.value.cache.forgetPending(it.key) }
      flushRequested = true
      if (cacheFile != file) {
        cacheFile = file
        cache = SlideshowFaceCache(file)
      }
      active = session
      schedule()
      session
    }

    fun lookup(session: Long, key: String): Entry? =
        synchronized(lock) { if (active == session) cache else null }?.entry(key, System.currentTimeMillis())

    fun close(session: Long) = synchronized(lock) {
      if (active == session) {
        active = null
        pending.cancel(session)
        flight?.let { it.value.cache.forgetPending(it.key) }
        flushRequested = true
        schedule()
      }
    }

    fun offer(session: Long, key: String, source: Bitmap) {
      val destination = synchronized(lock) {
        if (!gate.canAdmit(System.nanoTime())) stopAdmission()
        if (active != session || gate.disabled || copying || flight?.key == key ||
            cache?.entry(key, System.currentTimeMillis()) != null) return
        copying = true // A single acquisition reservation across all callers/sessions.
        cache!!
      }
      // Drawing directly into the bounded destination avoids createScaledBitmap returning
      // the original or creating an unbounded source-sized copy for a small/hardware bitmap.
      val pixels = derivative(source)
      synchronized(lock) {
        copying = false
        if (pixels == null) {
          if (active == session) {
            destination.record(Entry(key, Status.FAILURE, System.currentTimeMillis(), 1, 1),
                System.currentTimeMillis())
            flushRequested = true
          }
          event("derivative_failed", session, key)
          schedule()
          return
        }
        val owned = Owned(SlideshowFaceLease(pixels) { it.recycle() }, destination)
        if (!gate.canAdmit(System.nanoTime())) stopAdmission()
        if (active != session || gate.disabled) {
          owned.pixels.finish(taskComplete = true)
          return
        }
        // Disk hydration may have delivered a result during the synchronous copy.
        if (destination.entry(key, System.currentTimeMillis()) != null) {
          owned.pixels.finish(taskComplete = true)
          return
        }
        pending.offer(session, key, owned)
        destination.record(metadata(key, owned, Status.PENDING), System.currentTimeMillis())
        schedule()
      }
    }

    private fun derivative(source: Bitmap): Bitmap? {
      var output: Bitmap? = null
      return try {
        if (source.isRecycled) return null
        val (width, height) = SlideshowFaceMetadata.targetSize(source.width, source.height)
        output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(source, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        output
      } catch (_: Exception) {
        output?.recycle()
        null
      } catch (_: OutOfMemoryError) {
        output?.recycle()
        null
      }
    }

    /** Called under lock. Only one pump runnable exists, including during hung SDK init. */
    private fun schedule() {
      if (!pumping && flight == null && !gate.disabled) {
        pumping = true
        executor.execute { pump() }
      }
    }

    private fun pump() {
      while (true) {
        val currentCache = synchronized(lock) { cache }
        currentCache?.load(System.currentTimeMillis())
        val flush = synchronized(lock) {
          if (flushRequested) {
            flushRequested = false
            currentCache
          } else null
        }
        flush?.let { persist(it) }
        val job = synchronized(lock) {
          if (gate.disabled) {
            pumping = false
            null
          } else pending.poll().also {
            if (it == null) pumping = false else flight = it
          }
        } ?: return
        val existing = job.value.cache.entry(job.key, System.currentTimeMillis())
        if (existing != null && existing.status != Status.PENDING) {
          release(job)
          continue
        }
        if (!analyze(job)) {
          synchronized(lock) { pumping = false }
          return // An incomplete Task owns the derivative; no replacement inference.
        }
      }
    }

    private fun analyze(job: SlideshowFacePending.Job<Owned>): Boolean {
      val started = System.nanoTime()
      var task: Task<List<Face>>? = null
      try {
        val accepted = synchronized(lock) {
          active == job.session && gate.start(started)
        }
        if (!accepted) return true
        persist(job.value.cache)
        if (detector == null) {
          event("init_started", job.session, job.key)
          try {
            detector = FaceDetection.getClient(FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(.06f)
                .build())
          } catch (error: Exception) {
            synchronized(lock) { stopAdmission() }
            throw error
          } catch (error: LinkageError) {
            synchronized(lock) { stopAdmission() }
            throw error
          }
          event("init_finished", job.session, job.key)
        }
        synchronized(lock) {
          if (!gate.canAdmit(System.nanoTime())) stopAdmission()
          if (gate.disabled) throw TimeoutException()
          if (active != job.session) return true
        }
        val bitmap = job.value.pixels.value
        task = detector!!.process(InputImage.fromBitmap(bitmap, 0)) // Already upright.
        val remaining = timeoutNanos - (System.nanoTime() - started)
        if (remaining <= 0) throw TimeoutException()
        val faces = Tasks.await(task, remaining, TimeUnit.NANOSECONDS)
        if (faces.size > SlideshowFaceMetadata.MAX_REGIONS) throw IllegalArgumentException()
        val regions = faces.map {
          val box = it.boundingBox
          SlideshowFaceMetadata.normalized(Box(box.left, box.top, box.right, box.bottom), bitmap.width, bitmap.height)
              ?: throw IllegalArgumentException()
        }
        synchronized(lock) {
          if (!gate.canAdmit(System.nanoTime())) stopAdmission()
          if (gate.disabled) throw TimeoutException()
          if (active == job.session && !gate.disabled) {
            job.value.cache.record(metadata(job.key, job.value,
                if (regions.isEmpty()) Status.NO_FACE else Status.FACES).copy(regions = regions),
                System.currentTimeMillis())
            failureStreak = 0
            event("analysis_complete", job.session, job.key,
                "status=${if (regions.isEmpty()) "NO_FACE" else "FACES"} faces=${regions.size}")
          }
        }
      } catch (error: Exception) {
        failed(job, error)
        if (error is InterruptedException) Thread.currentThread().interrupt()
      } catch (error: LinkageError) {
        failed(job, error)
      } catch (error: OutOfMemoryError) {
        failed(job, error)
      } finally {
        // await timing out (or session close) does NOT imply the underlying Task stopped.
        if (job.value.pixels.finish(task == null || task.isComplete)) {
          release(job)
        } else {
          synchronized(lock) { stopAdmission() }
          // A direct completion listener just schedules cleanup on our single worker.
          // It neither touches a display bitmap nor publishes/reframes a visible photo.
          runCatching {
            task!!.addOnCompleteListener(direct) {
              executor.execute {
                job.value.pixels.finish(taskComplete = true)
                release(job)
              }
            }
          }.onFailure {
            // Keep the one retained lease if even listener registration fails. We cannot
            // establish safe disposal and admission is already permanently disabled.
            event("cleanup_listener_failed", job.session, job.key, "type=${it.javaClass.simpleName}")
          }
        }
        persist(job.value.cache)
      }
      return task == null || task.isComplete
    }

    private fun failed(job: SlideshowFacePending.Job<Owned>, error: Throwable) {
      synchronized(lock) {
        if (error is TimeoutException || error is InterruptedException ||
            error is LinkageError || error is OutOfMemoryError || ++failureStreak >= 3) stopAdmission()
        if (active == job.session) {
          job.value.cache.record(metadata(job.key, job.value, Status.FAILURE), System.currentTimeMillis())
        }
      }
      // No exception messages, stack traces, URLs, keys, tokens or Context identifiers.
      event("analysis_failed", job.session, job.key, "status=FAILURE type=${error.javaClass.simpleName}")
    }

    private fun release(job: SlideshowFacePending.Job<Owned>) {
      job.value.pixels.finish(taskComplete = true)
      synchronized(lock) {
        if (flight === job) {
          job.value.cache.forgetPending(job.key)
          flight = null
          gate.complete()
        }
      }
      if (gate.disabled) {
        runCatching { detector?.close() }
        detector = null
      }
    }

    private fun stopAdmission() {
      gate.disable()
      active?.let { pending.cancel(it) }
    }

    private fun metadata(key: String, owned: Owned, status: Status) =
        Entry(key, status, System.currentTimeMillis(), owned.pixels.value.width, owned.pixels.value.height)

    private fun persist(cache: SlideshowFaceCache) {
      runCatching { cache.persist(System.currentTimeMillis()) }
          .onFailure { event("cache_write_failed", 0, "") }
    }

    private fun event(name: String, session: Long, key: String, detail: String = "") {
      Log.i(TAG, "event=$name session=$session asset=${key.take(12)} $detail")
    }
  }
}
