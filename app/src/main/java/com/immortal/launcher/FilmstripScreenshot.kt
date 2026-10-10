/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.dreams.DreamService
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import androidx.annotation.MainThread
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Generated-fixture-only Window pixels. Main binds after admission and clears before teardown.
 * No fallback drawing, navigation, file export, or source strings in diagnostics.
 */
internal object FilmstripScreenshot {
  private const val TIMEOUT_MS = 4_000L
  private const val MAX_RAW_BYTES = 24L * 1024 * 1024
  private const val MAX_PNG_BYTES = 4 * 1024 * 1024
  private const val MAX_EDGE = 1080
  private const val MAX_SOURCES = 256
  private const val CACHE = "/data/data/com.immortal.launcher.debug/cache"
  private const val FIXTURE_PREFIX = ".perf-slideshow-observer-"
  private val main by lazy { Handler(Looper.getMainLooper()) }
  private val encoder by lazy { Executors.newSingleThreadExecutor { task ->
    Thread(task, "filmstrip-png").apply { isDaemon = true }
  } }
  // Also reserves perf-start commands, so a Fleet collector cannot start during isolation/copy.
  private val reservation = AtomicReference<Any?>(null)
  private class Binding(owner: View, val urls: List<String>) {
    val owner = WeakReference(owner)
  }
  @Volatile private var binding: Binding? = null
  @Volatile private var capture: Capture? = null

  @MainThread fun bind(owner: View, photoUrls: List<String>) {
    check(Looper.myLooper() == Looper.getMainLooper()) { "filmstrip_main_required" }
    capture?.finish(409, "filmstrip_owner_changed")
    binding = if (owner is FilmstripEngine && photoUrls.size in 1..MAX_SOURCES)
      Binding(owner, photoUrls.toList()) else null
  }

  @MainThread fun clear(owner: View) {
    check(Looper.myLooper() == Looper.getMainLooper()) { "filmstrip_main_required" }
    if (binding?.owner?.get() !== owner) return
    binding = null
    capture?.finish(409, "filmstrip_owner_changed")
  }

  /** Null means a screenshot (or another start) owns the reservation. Do not hold a monitor
   * while FramePerformanceCapture marshals to Main.
   */
  internal fun withPerformanceStart(action: () -> Boolean): Boolean? {
    val slot = Any()
    if (!reservation.compareAndSet(null, slot)) return null
    return try { action() } finally { reservation.compareAndSet(slot, null) }
  }

  private fun collectorBusy(): Boolean {
    val state = FramePerformanceCapture.stateSnapshot()
    return state.optBoolean("active", true) || state.optBoolean("collectorRunning", true)
  }

  private fun error(status: Int, code: String) =
      FleetHttpServer.Response(status, """{"ok":false,"error":"$code"}""")

  /** Called only by a Fleet worker. The returned writer owns a private, never-mutated PNG. */
  fun render(context: Context): FleetHttpServer.Response {
    if (Looper.myLooper() == Looper.getMainLooper()) return error(503, "filmstrip_worker_required")
    if (!BuildConfig.PORTAL_NATIVE_FILMSTRIP) return error(403, "filmstrip_disabled")
    if (!DevMode.isEnabled(context)) return error(403, "dev_mode_required")
    if (Build.VERSION.SDK_INT < 26) return error(503, "filmstrip_pixelcopy_unavailable")
    val slot = Any()
    if (!reservation.compareAndSet(null, slot)) return error(409, "filmstrip_busy")
    var handedOff = false
    val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
    try {
      if (collectorBusy()) return error(409, "filmstrip_perf_busy")
      val owned = binding ?: return error(409, "filmstrip_unbound")
      if (!generatedOnly(owned.urls, deadline)) return error(403, "filmstrip_fixture_required")
      if (SystemClock.elapsedRealtime() >= deadline) return error(503, "filmstrip_timeout")
      val job = Capture(owned, context.applicationContext, deadline, slot)
      capture = job
      if (!main.post { job.begin() }) {
        if (capture === job) capture = null
        return error(503, "filmstrip_main_unavailable")
      }
      handedOff = true
      val remaining = (job.deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
      val completed = try { job.done.await(remaining, TimeUnit.MILLISECONDS) }
      catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        job.cancelled.set(true)
        main.post { job.finish(503, "filmstrip_interrupted") }
        return error(503, "filmstrip_interrupted")
      }
      if (!completed) {
        job.cancelled.set(true)
        // Restoration is independent of the late PixelCopy callback; the bitmap is not.
        main.post { job.finish(503, "filmstrip_timeout") }
        return error(503, "filmstrip_timeout")
      }
      val png = job.png ?: return error(job.status, job.code)
      return FleetHttpServer.Response.stream(200, "image/png", png.size.toLong(),
          headers = mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff")) {
        it.write(png)
      }
    } catch (_: Exception) {
      return error(503, "filmstrip_capture_failed")
    } finally {
      if (!handedOff) reservation.compareAndSet(slot, null)
    }
  }

  /** Reject authorities, credentials, query/fragment data, traversal and symlink escapes.
   * Canonicalization is on the Fleet worker, including Android's /data/data alias.
   * Main is responsible for verifying generated fixture hashes before binding/requesting.
   */
  private fun generatedOnly(urls: List<String>, deadline: Long): Boolean = try {
    val cache = File(CACHE).canonicalFile
    urls.isNotEmpty() && urls.size <= MAX_SOURCES && urls.all { url ->
      if (SystemClock.elapsedRealtime() >= deadline || url.length > 4096 || !url.startsWith("file:///")) false else {
        val uri = URI(url)
        val path = uri.path.orEmpty()
        val relative = path.removePrefix("$CACHE/")
        val directory = relative.substringBefore('/')
        uri.scheme == "file" && uri.rawAuthority.isNullOrEmpty() && uri.rawQuery == null &&
            uri.rawFragment == null && path.startsWith("$CACHE/") &&
            directory.startsWith(FIXTURE_PREFIX) && directory.length > FIXTURE_PREFIX.length &&
            relative.contains('/') && relative.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
            File(cache, directory).canonicalFile == File(cache, directory).absoluteFile &&
            File(path).canonicalPath.startsWith(File(cache, directory).path + "/") && File(path).isFile
      }
    }
  } catch (_: Exception) { false }

  private fun ownWindow(owner: View): Window? {
    var context: Context = owner.context
    repeat(16) {
      when (val candidate = context) {
        is Activity -> return if (candidate.isFinishing || candidate.isDestroyed) null else candidate.window
        is DreamService -> return candidate.window
        is ContextWrapper -> {
          val next = candidate.baseContext
          if (next === candidate) return null
          context = next
        }
        else -> return null
      }
    }
    return null
  }

  private class Branch(group: ViewGroup, selected: View) {
    val group = WeakReference(group)
    val selected = WeakReference(selected)
    val children = (0 until group.childCount).map { WeakReference(group.getChildAt(it)) }
  }
  private class Hidden(view: View) {
    val view = WeakReference(view)
    val visibility = view.visibility
  }

  private class Capture(val owned: Binding, val context: Context, val deadline: Long, val slot: Any) {
    val done = CountDownLatch(1)
    val cancelled = AtomicBoolean(false)
    @Volatile var png: ByteArray? = null
    @Volatile var status = 503
    @Volatile var code = "filmstrip_capture_failed"
    private var terminal = false // Everything below is Main-owned.
    private var released = false
    private var copyPending = false
    private var encodingPending = false
    private var target: Bitmap? = null
    private var window = WeakReference<Window>(null)
    private var decor = WeakReference<View>(null)
    private var bounds = Rect()
    private val branches = ArrayList<Branch>()
    private val hidden = ArrayList<Hidden>()
    private var observer: ViewTreeObserver? = null
    private var frameTime = Long.MAX_VALUE
    private val timeout = Runnable { cancelled.set(true); finish(503, "filmstrip_timeout") }
    private val frame = Choreographer.FrameCallback { time ->
      if (!terminal) { frameTime = time; decor.get()?.invalidate() }
    }
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
      if (!terminal && !safe()) finish(409, "filmstrip_scene_changed")
      true
    }
    private val metrics = Window.OnFrameMetricsAvailableListener { _, value, _ ->
      if (!terminal && !copyPending && !encodingPending &&
          value.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) >= frameTime) copy()
    }

    fun begin() {
      if (terminal) return
      try {
        if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline) {
          finish(503, "filmstrip_timeout"); return
        }
        if (collectorBusy()) { finish(409, "filmstrip_perf_busy"); return }
        val owner = owned.owner.get()
        val host = owner?.let(::ownWindow)
        val root = host?.peekDecorView()
        if (owner == null || host == null || root == null || owner.rootView !== root) {
          finish(409, "filmstrip_window_unavailable"); return
        }
        window = WeakReference(host)
        decor = WeakReference(root)
        bounds = stageBounds() ?: run { finish(409, "filmstrip_bounds_invalid"); return }
        if (bounds.width().toLong() * bounds.height() * 4 > MAX_RAW_BYTES) {
          finish(409, "filmstrip_pixel_limit"); return
        }
        var child: View = owner
        while (child !== root) {
          val parent = child.parent as? ViewGroup ?: run {
            finish(409, "filmstrip_bounds_invalid"); return
          }
          branches.add(Branch(parent, child))
          if (branches.size > 32 || hidden.size + parent.childCount > 512) {
            finish(409, "filmstrip_hierarchy_limit"); return
          }
          for (i in 0 until parent.childCount) {
            val sibling = parent.getChildAt(i)
            if (sibling !== child) hidden.add(Hidden(sibling))
          }
          child = parent
        }
        host.addOnFrameMetricsAvailableListener(metrics, main)
        observer = root.viewTreeObserver.also { it.addOnPreDrawListener(preDraw) }
        main.postDelayed(timeout, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        hidden.forEach { entry -> if (entry.visibility == View.VISIBLE) entry.view.get()?.visibility = View.INVISIBLE }
        Choreographer.getInstance().postFrameCallback(frame)
        root.invalidate()
      } catch (_: Exception) { finish(503, "filmstrip_capture_failed") }
      catch (_: OutOfMemoryError) { finish(503, "filmstrip_pixel_limit") }
    }

    private fun stageBounds(): Rect? {
      val owner = owned.owner.get() ?: return null
      val root = decor.get() ?: return null
      if (binding !== owned || owner !is FilmstripEngine || !owner.isAttachedToWindow ||
          !root.isAttachedToWindow || owner.rootView !== root || !owner.isHardwareAccelerated ||
          !owner.isShown || !owner.hasWindowFocus() || owner.windowVisibility != View.VISIBLE ||
          owner.windowToken !== root.windowToken ||
          owner.width <= 0 || owner.height <= 0 || ownWindow(owner) !== window.get()) return null
      var view: View = owner
      repeat(33) {
        if (view.alpha != 1f || !view.matrix.isIdentity || view.visibility != View.VISIBLE) return null
        if (view === root) {
          val visible = Rect()
          if (!owner.getLocalVisibleRect(visible) || visible != Rect(0, 0, owner.width, owner.height)) return null
          val location = IntArray(2)
          owner.getLocationInWindow(location)
          val result = Rect(location[0], location[1], location[0] + owner.width, location[1] + owner.height)
          return result.takeIf { it.left >= 0 && it.top >= 0 && it.right <= root.width && it.bottom <= root.height }
        }
        view = view.parent as? View ?: return null
      }
      return null
    }

    private fun safe(): Boolean = try {
      !cancelled.get() && SystemClock.elapsedRealtime() < deadline &&
          BuildConfig.PORTAL_NATIVE_FILMSTRIP && DevMode.isEnabled(context) && !collectorBusy() &&
          stageBounds() == bounds && branches.all { branch ->
            val parent = branch.group.get()
            parent != null && branch.selected.get()?.parent === parent && parent.childCount == branch.children.size &&
                branch.children.indices.all { parent.getChildAt(it) === branch.children[it].get() }
          } && hidden.all { entry -> entry.view.get()?.visibility ==
              if (entry.visibility == View.VISIBLE) View.INVISIBLE else entry.visibility }
    } catch (_: Exception) { false }

    private fun copy() {
      try {
        if (!safe()) { finish(409, "filmstrip_scene_changed"); return }
        val host = window.get() ?: run { finish(409, "filmstrip_window_unavailable"); return }
        val bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        target = bitmap
        copyPending = true
        PixelCopy.request(host, Rect(bounds), bitmap, { result -> copied(result) }, main)
      } catch (_: Exception) {
        copyPending = false
        target?.recycle(); target = null
        finish(503, "filmstrip_pixelcopy_failed")
      } catch (_: OutOfMemoryError) {
        copyPending = false
        target?.recycle(); target = null
        finish(503, "filmstrip_pixel_limit")
      }
    }

    private fun copied(result: Int) {
      copyPending = false
      val bitmap = target
      target = null
      if (terminal || result != PixelCopy.SUCCESS || bitmap == null || !safe()) {
        bitmap?.recycle()
        if (!terminal) finish(503, "filmstrip_pixelcopy_failed") else releaseIfSafe()
        return
      }
      restore()
      encodingPending = true
      try {
        encoder.execute {
          val bytes = try { encode(bitmap, cancelled, deadline) } catch (_: Exception) { null }
          catch (_: OutOfMemoryError) { null }
          finally { bitmap.recycle() }
          main.post {
            encodingPending = false
            if (!terminal) {
              // Isolation has ended, but ownership, bounds, flags and perf exclusion must still hold.
              val publishable = runCatching {
                bytes != null && !cancelled.get() && SystemClock.elapsedRealtime() < deadline &&
                    stageBounds() == bounds && DevMode.isEnabled(context) && !collectorBusy()
              }.getOrDefault(false)
              if (publishable) {
                png = bytes
                finish(200, "")
              } else finish(503, "filmstrip_encode_failed")
            } else releaseIfSafe()
          }
        }
      } catch (_: Exception) {
        encodingPending = false
        bitmap.recycle()
        finish(503, "filmstrip_encode_failed")
      }
    }

    private fun restore() {
      // Always Main; restore even detached/replaced siblings, independently of copy completion.
      runCatching { Choreographer.getInstance().removeFrameCallback(frame) }
      window.get()?.let { host -> runCatching { host.removeOnFrameMetricsAvailableListener(metrics) } }
      observer?.let { tree -> if (tree.isAlive) runCatching { tree.removeOnPreDrawListener(preDraw) } }
      observer = null
      hidden.forEach { entry -> entry.view.get()?.let { view -> runCatching { view.visibility = entry.visibility } } }
      hidden.clear()
      branches.clear()
    }

    fun finish(resultStatus: Int, resultCode: String) {
      if (!terminal) {
        terminal = true
        if (resultStatus != 200) { cancelled.set(true); png = null }
        status = resultStatus
        code = resultCode
        main.removeCallbacks(timeout)
        restore()
        done.countDown()
      }
      releaseIfSafe()
    }

    private fun releaseIfSafe() {
      if (terminal && !copyPending && !encodingPending && !released) {
        released = true
        if (capture === this) capture = null
        reservation.compareAndSet(slot, null)
      }
    }
  }

  private fun encode(bitmap: Bitmap, cancelled: AtomicBoolean, deadline: Long): ByteArray? {
    val ratio = minOf(1.0, MAX_EDGE.toDouble() / maxOf(bitmap.width, bitmap.height))
    val scaled = if (ratio < 1.0) Bitmap.createScaledBitmap(bitmap,
        (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
        (bitmap.height * ratio).roundToInt().coerceAtLeast(1), true) else bitmap
    try {
      val bytes = ByteArrayOutputStream(64 * 1024)
      val bounded = object : OutputStream() {
        private fun admit(count: Int) {
          if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline || count > MAX_PNG_BYTES - bytes.size())
            throw java.io.IOException("filmstrip_png_limit")
        }
        override fun write(value: Int) { admit(1); bytes.write(value) }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
          admit(length); bytes.write(buffer, offset, length)
        }
      }
      if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, bounded) || bytes.size() == 0) return null
      return bytes.toByteArray()
    } finally { if (scaled !== bitmap) scaled.recycle() }
  }
}
