/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Legacy engine focal points and people weights. Detection runs on a bounded, shared worker;
 * each frame owns a cancellable handle. Results still enter the engine's next-start fx/fy/pw
 * snapshot. face_focus.json retains URL -> comma-separated metadata / ""; new face results
 * append the raw normalized bounds to the existing "fx,fy,weight" fields.
 */
class FaceFocusStore(context: Context) : AutoCloseable {
  // A stalled SDK task may retain this store, but must never retain its Activity/Dream host.
  private val cacheFile = File(context.filesDir, "face_focus.json")
  private val cache by lazy { FaceFocusCache(cacheFile) }
  private val work = FaceFocusWork()

  /** The cached focal point for [url], or null if unanalyzed / no faces found. */
  fun focusFor(url: String): FaceFocus.Focus? {
    val v = cache.entry(url) ?: return null
    if (v.isEmpty()) return null
    val parts = v.split(",")
    if (parts.size < 2) return null
    val fx = parts[0].toFloatOrNull() ?: return null
    val fy = parts[1].toFloatOrNull() ?: return null
    return FaceFocus.Focus(fx, fy, cachedFaceBounds(parts))
  }

  /** The cached people-weight (0..1) for [url]; 0 if unanalyzed / no faces. */
  fun weightFor(url: String): Float {
    val v = cache.entry(url) ?: return 0f
    if (v.isEmpty()) return 0f
    return v.split(",").getOrNull(2)?.toFloatOrNull() ?: 0f
  }

  fun startWarmUp(urls: List<String>, authHeaders: Map<String, String> = emptyMap()) {
    val photos = urls.toList()
    val headers = authHeaders.toMap()
    work.start { request ->
      runCatching { warmUp(photos, headers, request) }
          .onFailure { Log.w(TAG, "face warm-up failed: ${it.javaClass.simpleName}") }
    }
  }

  /** Suspend new analysis and reject late results while the retained photo host is covered. */
  fun setActive(active: Boolean) { work.setActive(active) }

  override fun close() { work.close() }

  /** Blocking worker entry point. Close/supersession rejects late results, including no-face. */
  fun warmUp(urls: List<String>, authHeaders: Map<String, String> = emptyMap()) =
      warmUp(urls, authHeaders, work.request)

  private fun warmUp(
      urls: List<String>, authHeaders: Map<String, String>, request: FaceFocusRequest,
  ) {
    if (!request.current()) return
    // Old focal/weighted entries get a bounded re-pass for face bounds. No-face stays final.
    val pending = urls.distinct().filter { cache.needsAnalysis(it) }
    if (pending.isEmpty() || !request.current()) return
    val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.06f)
            .build())
    var failStreak = 0
    var dirty = 0
    try {
      for (url in pending) {
        if (!request.current()) break
        if (!cache.needsAnalysis(url)) continue
        val entry = runCatching { detect(detector, url, authHeaders, request) }.getOrNull()
        if (!request.current()) break
        if (entry == null) {
          if (++failStreak >= MAX_FAIL_STREAK) break
          continue // Failed work remains retryable and does not rewrite JSON.
        }
        failStreak = 0
        // Only the short in-memory insertion shares the cancellation lock. Disk I/O does not.
        if (request.commit { cache.record(url, entry) } && ++dirty >= FLUSH_EVERY) {
          cache.persist()
          dirty = 0
        }
      }
    } finally {
      // detect() retains unfinished SDK inputs until Task completion, before closing the client.
      runCatching { detector.close() }
      cache.persist() // Flush only results accepted before cancellation; merge the latest state.
    }
  }

  private fun detect(
      detector: com.google.mlkit.vision.face.FaceDetector,
      url: String,
      authHeaders: Map<String, String>,
      request: FaceFocusRequest,
  ): String? {
    val bmp = decodeSmall(url, authHeaders, request) ?: return null
    var task: Task<List<Face>>? = null
    try {
      if (!request.current()) return null
      task = detector.process(InputImage.fromBitmap(bmp, 0)) // SlideshowDecoder makes it upright.
      val faces = try {
        Tasks.await(task, DETECT_TIMEOUT_S, TimeUnit.SECONDS)
      } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw interrupted
      }
      if (!request.current()) return null
      val boxes = faces.map {
        FaceFocus.Box(it.boundingBox.left, it.boundingBox.top, it.boundingBox.right, it.boundingBox.bottom)
      }
      val focus = FaceFocus.focalPoint(boxes, bmp.width, bmp.height) ?: return ""
      val weight = FaceFocus.peopleWeight(boxes, bmp.width, bmp.height)
      val point = "${focus.fx},${focus.fy},$weight"
      val bounds = FaceFocus.boundsFor(boxes, bmp.width, bmp.height) ?: return ""
      return "$point,${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
    } finally {
      // Timeout/interruption does not cancel ML Kit. Park this one worker for safe disposal;
      // a stuck Task retains one bounded bitmap/client and at most one latest pending frame.
      task?.let { awaitFaceFocusTask(it) }
      bmp.recycle()
    }
  }

  /** Shared decode caps encoded input/pixels and honors EXIF, with interruption checkpoints. */
  private fun decodeSmall(
      url: String, authHeaders: Map<String, String>, request: FaceFocusRequest,
  ): Bitmap? {
    if (!request.current()) return null
    val bitmap = when {
      url.startsWith("file://") -> SlideshowDecoder.file(url.removePrefix("file://"), TARGET_PX)
      url.startsWith("http://") || url.startsWith("https://") -> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 12000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", Http.DEFAULT_UA)
        authHeaders.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        val transport = AutoCloseable { connection.disconnect() }
        request.own(transport) // Track before the first blocking connect/read.
        var input: java.io.InputStream? = null
        try {
          val stream = connection.inputStream
          input = stream
          request.own(stream)
          SlideshowDecoder.stream(stream, TARGET_PX)
        } finally {
          request.release(transport)
          input?.let { request.release(it) }
        }
      }
      else -> null
    }
    return if (request.current()) bitmap else { bitmap?.recycle(); null }
  }

  companion object {
    private const val TAG = "FaceFocusStore"
    private const val TARGET_PX = 320
    private const val DETECT_TIMEOUT_S = 10L
    private const val MAX_FAIL_STREAK = 8
    private const val FLUSH_EVERY = 8
  }
}

/** Shared by file: a new frame cannot hydrate or later persist an independent stale snapshot. */
internal class FaceFocusCache(file: File) {
  private val state = states.computeIfAbsent(file.absoluteFile.normalize().path) { State(file) }
  fun entry(url: String): String? = state.entry(url)
  fun needsAnalysis(url: String): Boolean = state.needsAnalysis(url)
  fun record(url: String, entry: String): Boolean = state.record(url, entry)
  fun persist() { state.persist() }

  private class State(private val file: File) {
    private val entries = ConcurrentHashMap<String, String>()
    @Volatile private var loaded = false
    private var version = 0L
    private var written = 0L
    private val diskLock = Any()
    // A successful no-region re-pass must not erase legacy framing or repeat every warm-up.
    // Shared for this process lifetime; decode/SDK failures remain retryable.
    private val noRegionAttempts = HashSet<String>()

    @Synchronized private fun load() {
      if (loaded) return
      runCatching {
        val json = JSONObject(file.readText())
        json.keys().forEach { entries[it] = json.optString(it) }
      }
      loaded = true
    }

    fun entry(url: String): String? {
      if (!loaded) load()
      return entries[url]
    }

    @Synchronized fun needsAnalysis(url: String): Boolean {
      if (!loaded) load()
      return url !in noRegionAttempts && incomplete(entries[url])
    }

    @Synchronized fun record(url: String, entry: String): Boolean {
      if (!loaded) load()
      val previous = entries[url]
      if (previous != null && (previous.isEmpty() || previous.split(",").size >= 3)) {
        val previousParts = previous.split(",")
        val parts = entry.split(",")
        val repairable = previousParts.size == 3 ||
            (previousParts.size == 7 && !validFaceEntry(previousParts))
        // Valid regions always win. Only another valid region may upgrade/repair metadata.
        if (!repairable) return false
        if (!validFaceEntry(parts)) {
          if (entry.isEmpty()) noRegionAttempts.add(url)
          return false // Preserve the point/weight, including against stale three-field work.
        }
      }
      entries[url] = entry
      noRegionAttempts.remove(url)
      version++
      return true
    }

    fun persist() = synchronized(diskLock) {
      val snapshot = synchronized(this) {
        if (version == written) return
        version to HashMap(entries)
      }
      // Encoding/writing never holds the state or frame-cancellation lock.
      runCatching {
        val json = JSONObject()
        snapshot.second.forEach { (key, value) -> json.put(key, value) }
        val temp = File.createTempFile("${file.name}.", ".tmp", file.parentFile)
        try {
          temp.writeText(json.toString())
          if (!temp.renameTo(file)) throw IOException("face cache replacement failed")
          synchronized(this) { written = snapshot.first }
        } finally { temp.delete() }
      }
      Unit
    }
  }

  private companion object {
    val states = ConcurrentHashMap<String, State>()
    fun incomplete(entry: String?): Boolean {
      if (entry == null) return true
      if (entry.isEmpty()) return false
      val parts = entry.split(",")
      return parts.size <= 3 || (parts.size == 7 && !validFaceEntry(parts))
    }
  }
}

private fun validFacePoint(parts: List<String>): Boolean =
    parts.size >= 3 && parts.take(3).all { field ->
      field.toFloatOrNull()?.let { it.isFinite() && it in 0f..1f } == true
    }

private fun validFaceEntry(parts: List<String>): Boolean =
    validFacePoint(parts) && cachedFaceBounds(parts) != null

/** Invalid region fields never invalidate the separately persisted legacy point/weight. */
private fun cachedFaceBounds(parts: List<String>): FaceFocus.Bounds? {
  if (parts.size != 7) return null
  val left = parts[3].toFloatOrNull() ?: return null
  val top = parts[4].toFloatOrNull() ?: return null
  val right = parts[5].toFloatOrNull() ?: return null
  val bottom = parts[6].toFloatOrNull() ?: return null
  return FaceFocus.Bounds(left, top, right, bottom).takeIf { it.isValid() }
}

/** Logical cancellation is synchronous. Physical transport cleanup is exclusively off-thread. */
internal class FaceFocusRequest {
  @Volatile var cancelled = false
    private set
  private val resources = LinkedHashSet<AutoCloseable>()
  private var cleanupComplete = CountDownLatch(0)
  fun current(): Boolean = !cancelled && !Thread.currentThread().isInterrupted

  /** [update] must only update memory; it cannot run disk/network I/O under this lock. */
  @Synchronized fun commit(update: () -> Boolean): Boolean = current() && update()

  fun own(resource: AutoCloseable) {
    val accepted = synchronized(this) { if (current()) resources.add(resource) else false }
    if (!accepted) {
      runCatching { resource.close() } // Called by the worker, including late-opened streams.
      throw InterruptedIOException("face request canceled")
    }
  }

  fun release(resource: AutoCloseable) {
    val owned = synchronized(this) { resources.remove(resource) }
    if (owned) runCatching { resource.close() }
  }

  fun cancel() {
    val (closing, complete) = synchronized(this) {
      if (cancelled) return
      cancelled = true
      val closing = resources.toList().also { resources.clear() }
      cleanupComplete = CountDownLatch(if (closing.isEmpty()) 0 else 1)
      closing to cleanupComplete
    }
    if (closing.isNotEmpty()) cleanup.execute {
      try { closing.forEach { runCatching { it.close() } } }
      finally { complete.countDown() }
    }
  }

  /** The worker may wait for cleanup; screen exit only invalidates and enqueues it. */
  fun awaitCleanup() {
    val complete = synchronized(this) { cleanupComplete }
    awaitFaceFocusCompletion(complete) { complete.count == 0L }
  }

  private companion object {
    val cleanup = Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "legacy-faces-close").apply { isDaemon = true }
    }
  }
}

/** Worker-only safe SDK disposal. Cancellation never means the Task surrendered its bitmap. */
internal fun awaitFaceFocusTask(task: Task<*>) {
  if (task.isComplete) return
  val completed = CountDownLatch(1)
  runCatching { task.addOnCompleteListener(Executor { it.run() }) { completed.countDown() } }
  awaitFaceFocusCompletion(completed) { task.isComplete }
}

private fun awaitFaceFocusCompletion(completed: CountDownLatch, complete: () -> Boolean) {
  var interrupted = Thread.interrupted()
  try {
    while (!complete()) {
      try { completed.await(100, TimeUnit.MILLISECONDS) }
      catch (_: InterruptedException) { interrupted = true }
    }
  } finally {
    if (interrupted) Thread.currentThread().interrupt()
  }
}
