/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Callable test-APK sidecar; invoke on the instrumentation worker AFTER any active soak:
 *
 * SlideshowSourceDeviceChecks.run(instrumentation, automation, instrumentation.targetContext)
 *
 * Returns only aggregate booleans, milliseconds and error class names. No screenshots, URLs,
 * paths, credentials, exception messages, preference dumps or external image downloads.
 * A 48-second work deadline leaves eight seconds for cleanup (total target <60 seconds).
 *
 * Uses actual PhotoFrameSource selection, DavSource/ImmichSource listing and auth, controller
 * remoteAsset/fetchRemoteImage/cancelNativeRequest/stop, SlideshowLoader, MediaCache and native
 * SlideshowStage. Private seams deliberately bypass start(): no settings writes, Activity,
 * weather/calendar/feed fallback, cache purge, presence or Dream changes. Storage is isolated
 * with a ContextWrapper; caller-owned settings snapshots need no additional restoration.
 *
 * "Navigation" below means the real cancelNativeRequest hook plus loader.load supersession
 * used by native navigation. Public next/prev dispatch, start() discovery wiring, source-scan
 * cancellation and attached-window/GPU presentation remain explicitly uncovered. The retained
 * photo check draws ONLY the detached test-owned native stage into an ephemeral bitmap.
 */
internal object SlideshowSourceDeviceChecks {
  fun run(instrumentation: Instrumentation, automation: UiAutomation, context: Context): JSONObject =
      Probe(instrumentation, automation, context).run()

  private const val WORK_MS = 48_000L
  private const val TOTAL_MS = 56_000L
  private const val WAIT_MS = 3_000L
  private const val CANCEL_MS = 2_000L // Well below the production HTTP read timeout (12 seconds).
  private const val USER = "fixture-user"
  private const val PASSWORD = "fixture-pass"
  private const val KEY = "slideshow-fixture-key"
  private val SEED = Color.rgb(25, 180, 70)
  private val LATEST = Color.rgb(210, 35, 190)
  private val CORNERS = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
  private class CheckFailure : RuntimeException()
  private enum class Backend { DAV, IMMICH }
  private enum class Asset { READY, LATEST, EXIF, MIRROR, CORRUPT, HEADERS, BODY, DENIED }

  private class Probe(
      private val instrumentation: Instrumentation,
      private val automation: UiAutomation,
      private val context: Context,
  ) {
    private val started = SystemClock.elapsedRealtime()
    private val assertions = JSONObject()
    private val timings = JSONObject()
    private val errors = JSONArray()
    private val root = File(context.cacheDir, "slideshow-source-${UUID.randomUUID()}")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val calls = pool("source-check-adapters", 1, 2)
    private val harnesses = ArrayList<Harness>()
    private var fixture: HttpFixture? = null
    private var failed = false
    private var mainExpired = false
    private var interrupted = false

    fun run(): JSONObject {
      try {
        demand("called_off_main_thread", Looper.myLooper() != Looper.getMainLooper())
        // Use the caller's existing automation connection; no input or window capture is needed.
        demand("caller_automation_session",
            instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) === automation)
        demand("target_package_context", context.packageName == instrumentation.targetContext.packageName)
        demand("owned_storage_created", root.mkdirs())
        fixture = HttpFixture(images(root), started + WORK_MS)
        val server = checkNotNull(fixture)
        val discoveries = Backend.values().associateWith { discover(server, it) }
        for (backend in Backend.values()) {
          val discovered = discoveries.getValue(backend)
          val harness = harness(backend, discovered.second, cached = false)
          load(harness, discovered.first.getValue(Asset.READY), "seed_${backend.name}")
          rejected(harness, discovered.first.getValue(Asset.CORRUPT), "corrupt_${backend.name}")
          rejected(harness, discovered.first.getValue(Asset.DENIED), "http_error_${backend.name}")
          for (asset in listOf(Asset.EXIF, Asset.MIRROR)) {
            val photo = load(harness, discovered.first.getValue(asset), "remote_${backend.name}_${asset.name}")
            upright(photo.bitmap, asset, "remote_${backend.name}_${asset.name}")
          }
          harness.close()
          for (asset in listOf(Asset.HEADERS, Asset.BODY)) {
            cancellation(server, backend, discovered, asset, close = false)
            cancellation(server, backend, discovered, asset, close = true)
          }
        }
        val cached = Backend.values().map { backend ->
          val discovered = discoveries.getValue(backend)
          val harness = harness(backend, discovered.second, cached = true)
          val url = discovered.first.getValue(Asset.EXIF)
          val photo = load(harness, url, "cache_cold_${backend.name}")
          upright(photo.bitmap, Asset.EXIF, "cache_cold_${backend.name}")
          demand("owned_encoded_cache_populated_${backend.name}", harness.cache!!.isCached(url, false))
          Triple(harness, url, photo.bitmap)
        }
        demand("all_fixture_requests_have_expected_headers", server.badHeaders.get() == 0)
        demand("adapter_and_image_headers_observed", server.goodHeaders.get() >= 16)
        server.close()
        demand("server_stopped_before_offline_decode", server.terminated())
        cached.forEach { (harness, url, original) ->
          val begin = SystemClock.elapsedRealtime()
          val photo = load(harness, url, "cache_offline_${harness.backend.name}")
          upright(photo.bitmap, Asset.EXIF, "cache_offline_${harness.backend.name}")
          demand("offline_redecoded_from_encoded_bytes_${harness.backend.name}", photo.bitmap !== original)
          timings.put("offlineDecode${harness.backend.name}Ms", SystemClock.elapsedRealtime() - begin)
          harness.close()
        }
      } catch (error: Throwable) {
        failed = true
        interrupted = error is InterruptedException
        errorType(error)
      } finally {
        // An interrupted caller still gets bounded transport/worker cleanup; preserve its signal.
        val hadInterrupt = Thread.interrupted()
        interrupted = interrupted || hadInterrupt
        cleanup("fixture_transport_workers_closed") { fixture?.close(); check(fixture?.terminated() != false) }
        cleanup("adapter_worker_closed") {
          calls.shutdownNow()
          check(calls.awaitTermination(remaining(total = true).coerceAtMost(1000), TimeUnit.MILLISECONDS))
        }
        cleanup("controller_workers_and_pixels_released") { harnesses.forEach { it.close(total = true) } }
        cleanup("fixture_errors_absent") {
          val types = fixture?.errors?.toList().orEmpty()
          types.forEach { errors.put(it) }
          check(types.isEmpty())
        }
        cleanup("owned_storage_removed") {
          check(!mainExpired && harnesses.all { it.closed })
          check(!root.exists() || root.deleteRecursively())
        }
      }
      timings.put("totalMs", SystemClock.elapsedRealtime() - started)
      demandWithoutThrow("under_60_seconds", SystemClock.elapsedRealtime() - started < 60_000L)
      if (interrupted) Thread.currentThread().interrupt()
      return JSONObject().put("completed", !failed).put("assertions", assertions)
          .put("timingsMs", timings).put("errorTypes", errors)
          .put("coverage", JSONObject()
              .put("realDavAndImmichListingAndHeaders", true)
              .put("realControllerRemoteAssetTransportCacheAndClose", true)
              .put("realNativeLoaderSupersession", true)
              .put("detachedStagePixelsOnly", true)
              .put("publicNextPreviousAndStartDispatch", false)
              .put("sourceDiscoveryCancellation", false)
              .put("attachedWindowAndGpu", false))
    }

    private fun discover(server: HttpFixture, backend: Backend): Pair<Map<Asset, String>, Map<String, String>> {
      val settings = settings(server, backend)
      val source = PhotoFrameSource.from(settings)
      val begin = SystemClock.elapsedRealtime()
      val result = worker {
        when (source) {
          is PhotoFrameSource.WebDav -> DavSource.listMedia(source.url, source.user, source.pass,
              includeVideo = false, cap = Asset.values().size)?.map { it.url to it.isVideo } to
              DavSource.authHeaders(source.user, source.pass)
          is PhotoFrameSource.Immich -> ImmichSource.listMedia(source.url, source.key, source.albumId,
              includeVideo = false, cap = Asset.values().size)?.map { it.url to it.isVideo } to
              ImmichSource.authHeaders(source.key)
          else -> throw CheckFailure()
        }
      }
      val media = result.first.orEmpty()
      demand("real_adapter_listing_${backend.name}", media.size == Asset.values().size &&
          media.none { it.second } && media.map { it.first }.toSet().size == media.size)
      val urls = Asset.values().associateWith { asset ->
        val expected = server.url(backend, asset)
        demand("adapter_owned_endpoint_${backend.name}_${asset.name}", media.any { it.first == expected })
        expected
      }
      timings.put("adapterListing${backend.name}Ms", SystemClock.elapsedRealtime() - begin)
      return urls to result.second
    }

    private fun settings(server: HttpFixture, backend: Backend): ScreensaverConfig.Settings =
        ScreensaverConfig.Settings(
            source = if (backend == Backend.DAV) ScreensaverConfig.SOURCE_DAV else ScreensaverConfig.SOURCE_IMMICH,
            mode = ScreensaverConfig.MODE_WHOLE, fit = ScreensaverConfig.FIT_FIT,
            photoMotion = false, keepPeopleInFrame = false, includeVideo = false,
            davUrl = server.base + "/dav/", davUser = USER, davPass = PASSWORD,
            immichUrl = server.base, immichKey = KEY, intervalSec = 3600)

    private fun harness(backend: Backend, headers: Map<String, String>, cached: Boolean): Harness {
      val storage = File(root, "controller-${harnesses.size}")
      check(storage.mkdirs())
      val scoped = object : ContextWrapper(context) {
        override fun getFilesDir(): File = storage
        override fun getCacheDir(): File = storage
      }
      return main {
        val frame = PhotoFrameController(scoped)
        val harness = Harness(frame, backend,
            if (cached) MediaCache(File(storage, "encoded"), 2L * 1024 * 1024) else null)
        harnesses += harness // Register before setup so a partial setup is still stopped in finally.
        setField(frame, "settings", settings(checkNotNull(fixture), backend))
        setField(frame, "nativePhotoMode", true)
        setField(frame, "remoteHeaders", headers)
        setField(frame, "mediaCache", harness.cache)
        frame.view // Build only; start() would launch unrelated background services.
        harness.stage = field(frame, "nativeStage") as SlideshowStage
        harness.loader = property(frame, "nativeLoader") as SlideshowLoader
        harness.stage.onPresented = { harness.presentations.incrementAndGet() }
        harness.layout()
        harness
      }
    }

    private inner class Harness(
        val frame: PhotoFrameController, val backend: Backend, val cache: MediaCache?,
    ) {
      lateinit var stage: SlideshowStage
      lateinit var loader: SlideshowLoader
      val presentations = AtomicInteger()
      private val pixels = LinkedHashSet<Bitmap>()
      private var stopped = false
      var closed = false
        private set

      fun layout() {
        stage.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
        stage.layout(0, 0, 640, 480)
      }

      fun request(url: String, ready: (SlideshowPhoto) -> Unit, failure: () -> Unit) {
        val asset = invoke(frame, "remoteAsset", arrayOf(String::class.java), url) as SlideshowAsset
        loader.load(asset, emptyList(), null, pair = false, people = false, onReady = { photos, _ ->
          pixels.addAll(photos.map { it.bitmap })
          invoke(frame, "presentNative", arrayOf(List::class.java), photos)
          ready(photos.single())
        }, onFailure = failure)
      }

      fun stop() {
        if (!stopped) { frame.stop(); stopped = true }
      }

      fun close(total: Boolean = false) {
        if (closed) return
        val executors = main(total) {
          val list = mutableListOf<ExecutorService>()
          listOf("io", "metaIo", "transcodeIo", "photoCancelIo").forEach {
            list += field(frame, it) as ExecutorService
          }
          list += field(field(frame, "faceRenderer")!!, "io") as ExecutorService
          if (this::loader.isInitialized)
            list += field(field(loader, "work")!!, "executor") as ExecutorService
          stop()
          list
        }
        val until = SystemClock.elapsedRealtime() + minOf(WAIT_MS, remaining(total))
        executors.forEach {
          check(it.awaitTermination((until - SystemClock.elapsedRealtime()).coerceAtLeast(1),
              TimeUnit.MILLISECONDS))
        }
        main(total) {
          check((field(frame, "photoConnections") as Set<*>).isEmpty())
          check((field(frame, "photoStreams") as Set<*>).isEmpty())
          check((field(frame, "pixelBudget") as SlideshowPixelBudget).used() == 0L)
          if (this::stage.isInitialized) check(stage.currentPhoto == null && stage.photoCount == 0)
          pixels.forEach { if (!it.isRecycled) it.recycle() }
          pixels.clear()
          closed = true
        }
      }
    }

    private fun load(harness: Harness, url: String, label: String): SlideshowPhoto {
      val done = CountDownLatch(1)
      var photo: SlideshowPhoto? = null
      main { harness.request(url, { photo = it; done.countDown() }, { done.countDown() }) }
      demand("${label}_callback_bounded", done.await(minOf(WAIT_MS, remaining()), TimeUnit.MILLISECONDS))
      demand("${label}_decoded", photo != null)
      val ready = checkNotNull(photo)
      demand("${label}_pixels_bounded", !ready.bitmap.isRecycled &&
          maxOf(ready.bitmap.width, ready.bitmap.height) <= 2400 &&
          ready.bitmap.allocationByteCount <= 12 * 1024 * 1024)
      return ready
    }

    private fun rejected(harness: Harness, url: String, label: String) {
      val original = main { harness.stage.currentPhoto }
      val count = harness.presentations.get()
      val done = CountDownLatch(1)
      val rejected = AtomicBoolean()
      main {
        harness.request(url, { done.countDown() }, { rejected.set(true); done.countDown() })
      }
      demand("${label}_callback_bounded", done.await(minOf(WAIT_MS, remaining()), TimeUnit.MILLISECONDS))
      demand("${label}_no_stale_or_empty_publication", rejected.get() &&
          harness.presentations.get() == count && main { harness.stage.currentPhoto === original })
    }

    private fun cancellation(server: HttpFixture, backend: Backend,
        discovered: Pair<Map<Asset, String>, Map<String, String>>, asset: Asset, close: Boolean) {
      val label = "${backend.name}_${asset.name}_${if (close) "close" else "navigation"}"
      val harness = harness(backend, discovered.second, cached = false)
      val seed = load(harness, discovered.first.getValue(Asset.READY), "${label}_seed")
      val before = server.slowEvents.size
      val stale = AtomicInteger()
      main { harness.request(discovered.first.getValue(asset), { stale.incrementAndGet() }, { stale.incrementAndGet() }) }
      poll("${label}_real_http_blocked") { server.slowEvents.size > before &&
          server.slowEvents.last().blocked.count == 0L }
      val slow = server.slowEvents.last()
      demand("${label}_server_not_released", !slow.release.get() && !slow.peerClosed.get())
      val retentionUntil = SystemClock.elapsedRealtime() + 350
      while (SystemClock.elapsedRealtime() < retentionUntil) {
        demand("${label}_valid_photo_retained", main {
          harness.stage.visibility == View.VISIBLE && harness.stage.currentPhoto === seed &&
              !seed.bitmap.isRecycled && renderedColor(harness, SEED)
        })
        pause(50)
      }
      val begin = SystemClock.elapsedRealtime()
      if (close) {
        main { harness.stop() }
        timings.put("${label}MainStopMs", SystemClock.elapsedRealtime() - begin)
      }
      else {
        main { invoke(harness.frame, "cancelNativeRequest", emptyArray()) }
        load(harness, discovered.first.getValue(Asset.LATEST), "${label}_latest")
      }
      poll("${label}_peer_disconnect_before_read_timeout", CANCEL_MS) {
        slow.peerClosed.get() && !slow.release.get() && slow.peerClosedAt - begin in 0..CANCEL_MS
      }
      timings.put("${label}CancelMs", slow.peerClosedAt - begin)
      demand("${label}_owned_http_worker_finished",
          slow.finished.await(minOf(WAIT_MS, remaining()), TimeUnit.MILLISECONDS))
      slow.release.set(true) // A late body is now harmless; the client already closed the real socket.
      val publications = harness.presentations.get()
      val watchUntil = SystemClock.elapsedRealtime() + 350
      while (SystemClock.elapsedRealtime() < watchUntil) {
        demand("${label}_obsolete_pixels_never_published", stale.get() == 0 &&
            harness.presentations.get() == publications && main {
              val current = harness.stage.currentPhoto
              if (close) current == null
              else current?.sourceKey == "remote:${if (backend == Backend.DAV)
                ScreensaverConfig.SOURCE_DAV else ScreensaverConfig.SOURCE_IMMICH}:${discovered.first.getValue(Asset.LATEST)}"
            })
        pause(50)
      }
      if (!close) poll("${label}_latest_pixels_visible") { main { renderedColor(harness, LATEST) } }
      harness.close()
    }

    /** This reads only generated pixels from a detached, owned stage; never a window screenshot. */
    private fun renderedColor(harness: Harness, color: Int): Boolean {
      harness.layout()
      val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
      try {
        harness.stage.draw(Canvas(bitmap))
        return near(bitmap.getPixel(320, 240), color)
      } finally { bitmap.recycle() }
    }

    private fun upright(bitmap: Bitmap, asset: Asset, label: String) {
      demand("${label}_upright_dimensions", bitmap.width == 240 && bitmap.height == 320)
      // Independent expected corner mapping: orientation 6 rotates clockwise; 7 is transverse.
      val expected = if (asset == Asset.EXIF)
        intArrayOf(Color.YELLOW, Color.RED, Color.GREEN, Color.BLUE)
      else intArrayOf(Color.BLUE, Color.GREEN, Color.RED, Color.YELLOW)
      val points = arrayOf(1 to 1, 7 to 1, 7 to 7, 1 to 7)
      demand("${label}_upright_pixels", points.indices.all {
        near(bitmap.getPixel(bitmap.width * points[it].first / 8,
            bitmap.height * points[it].second / 8), expected[it])
      })
    }

    private fun images(storage: File): Map<Asset, ByteArray> {
      fun encoded(color: Int?, orientation: Int?): ByteArray {
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        try {
          if (color != null) bitmap.eraseColor(color)
          else {
            val canvas = Canvas(bitmap)
            val paint = android.graphics.Paint()
            arrayOf(floatArrayOf(0f, 0f, 160f, 120f), floatArrayOf(160f, 0f, 320f, 120f),
                floatArrayOf(160f, 120f, 320f, 240f), floatArrayOf(0f, 120f, 160f, 240f))
                .forEachIndexed { i, rect ->
                  paint.color = CORNERS[i]
                  canvas.drawRect(rect[0], rect[1], rect[2], rect[3], paint)
                }
          }
          val output = ByteArrayOutputStream()
          check(bitmap.compress(if (orientation == null) Bitmap.CompressFormat.PNG
              else Bitmap.CompressFormat.JPEG, 95, output))
          if (orientation == null) return output.toByteArray()
          val file = File(storage, "generated-$orientation.jpg")
          file.writeBytes(output.toByteArray())
          ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
          }
          return file.readBytes()
        } finally { bitmap.recycle() }
      }
      val exif = encoded(null, 6)
      return mapOf(Asset.READY to encoded(SEED, null), Asset.LATEST to encoded(LATEST, null),
          Asset.EXIF to exif, Asset.MIRROR to encoded(null, 7), Asset.HEADERS to exif,
          Asset.BODY to exif, Asset.CORRUPT to byteArrayOf(0, 1, 2, 3))
    }

    private fun <T> worker(block: () -> T): T {
      val future: Future<T> = calls.submit<T> { block() }
      try { return future.get(minOf(4000, remaining()), TimeUnit.MILLISECONDS) }
      finally { if (!future.isDone) future.cancel(true) }
    }

    private fun <T> main(total: Boolean = false, block: () -> T): T {
      val done = CountDownLatch(1)
      val expired = AtomicBoolean()
      var result: T? = null
      var error: Throwable? = null
      val task = Runnable {
        try { if (!expired.get()) result = block() } catch (failure: Throwable) { error = failure }
        finally { done.countDown() }
      }
      val wait = minOf(WAIT_MS, remaining(total))
      check(mainHandler.post(task))
      val completed = try { done.await(wait, TimeUnit.MILLISECONDS) }
      catch (failure: InterruptedException) {
        expired.set(true)
        mainHandler.removeCallbacks(task)
        mainExpired = true
        Thread.currentThread().interrupt()
        throw failure
      }
      if (!completed) {
        expired.set(true)
        mainHandler.removeCallbacks(task)
        mainExpired = true
        throw CheckFailure()
      }
      error?.let { throw it }
      @Suppress("UNCHECKED_CAST")
      return result as T
    }

    private fun poll(label: String, millis: Long = WAIT_MS, condition: () -> Boolean) {
      val until = SystemClock.elapsedRealtime() + minOf(millis, remaining())
      while (SystemClock.elapsedRealtime() < until) {
        if (condition()) { demand(label, true); return }
        pause(25)
      }
      demand(label, false)
    }

    private fun remaining(total: Boolean = false): Long {
      val remaining = started + (if (total) TOTAL_MS else WORK_MS) - SystemClock.elapsedRealtime()
      if (remaining <= 0 || Thread.currentThread().isInterrupted) throw CheckFailure()
      return remaining
    }

    private fun pause(millis: Long) { Thread.sleep(minOf(millis, remaining())) }
    private fun demand(label: String, pass: Boolean) {
      assertions.put(label, pass)
      if (!pass) throw CheckFailure()
    }
    private fun demandWithoutThrow(label: String, pass: Boolean) {
      assertions.put(label, pass)
      if (!pass) failed = true
    }
    private fun errorType(error: Throwable) {
      errors.put((if (error is java.util.concurrent.ExecutionException ||
          error is java.lang.reflect.InvocationTargetException) error.cause ?: error else error).javaClass.simpleName)
    }
    private fun cleanup(label: String, block: () -> Unit) {
      try { block(); assertions.put(label, true) }
      catch (error: Throwable) { failed = true; assertions.put(label, false); errorType(error) }
    }
  }

  private class Slow {
    val blocked = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val release = AtomicBoolean()
    val peerClosed = AtomicBoolean()
    @Volatile var peerClosedAt = 0L
  }

  /** Small HTTP/1.1 fixture: loopback only, bounded header/body/connection counts, no keep-alive. */
  private class HttpFixture(private val bytes: Map<Asset, ByteArray>, private val deadline: Long) : AutoCloseable {
    private val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val base = "http://127.0.0.1:${listener.localPort}"
    private val closed = AtomicBoolean()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val handlers = pool("source-check-http", 4, 8)
    private val requests = AtomicInteger()
    val goodHeaders = AtomicInteger()
    val badHeaders = AtomicInteger()
    val slowEvents = ConcurrentLinkedQueue<Slow>()
    val errors = ConcurrentLinkedQueue<String>()
    private val acceptor = Thread({
      try {
        while (!closed.get() && SystemClock.elapsedRealtime() < deadline) {
          val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
          if (closed.get()) { socket.close(); break }
          sockets.add(socket)
          if (requests.incrementAndGet() > 96 || sockets.size > 8) {
            socket.close(); sockets.remove(socket); throw CheckFailure()
          }
          try { handlers.execute { serve(socket) } }
          catch (error: Throwable) { socket.close(); sockets.remove(socket); throw error }
        }
      } catch (error: Throwable) { if (!closed.get()) errors.add(error.javaClass.simpleName) }
    }, "source-check-accept").apply { isDaemon = true }

    init { listener.soTimeout = 200; acceptor.start() }

    fun url(backend: Backend, asset: Asset): String = base + if (backend == Backend.DAV)
      "/dav/${asset.name.lowercase()}.jpg"
    else "/api/assets/${asset.name.lowercase()}/thumbnail?size=preview"

    private fun serve(socket: Socket) {
      var slow: Slow? = null
      try {
        socket.soTimeout = 500
        val input = socket.getInputStream()
        var headerBytes = 0
        fun line(): String {
          val out = ByteArrayOutputStream()
          while (out.size() < 2048 && ++headerBytes <= 8192) {
            val next = input.read()
            if (next < 0) throw CheckFailure()
            if (next == 10) return out.toString("US-ASCII").trimEnd('\r')
            out.write(next)
          }
          throw CheckFailure()
        }
        val request = line().split(' ')
        check(request.size == 3 && request[2] == "HTTP/1.1")
        val headers = HashMap<String, String>()
        while (true) {
          val row = line()
          if (row.isEmpty()) break
          val colon = row.indexOf(':')
          check(colon > 0)
          headers[row.substring(0, colon).lowercase()] = row.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        check(length in 0..4096 && !headers.containsKey("transfer-encoding"))
        val body = ByteArray(length)
        var offset = 0
        while (offset < body.size) {
          val count = input.read(body, offset, body.size - offset)
          check(count > 0)
          offset += count
        }
        val path = URI(request[1]).path
        val dav = path.startsWith("/dav/")
        // Expected bytes are independent of production authHeaders implementations.
        val auth = if (dav) headers["authorization"] == "Basic Zml4dHVyZS11c2VyOmZpeHR1cmUtcGFzcw=="
            else headers["x-api-key"] == KEY
        if (!auth) {
          badHeaders.incrementAndGet()
          response(socket, "401 Unauthorized", "text/plain", byteArrayOf())
          return
        }
        goodHeaders.incrementAndGet()
        if (request[0] == "PROPFIND" && path == "/dav/") {
          check(headers["depth"] == "1")
          val xml = "<d:multistatus xmlns:d=\"DAV:\">" + Asset.values().joinToString("") {
            "<d:response><d:href>/dav/${it.name.lowercase()}.jpg</d:href>" +
                "<d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat></d:response>"
          } + "</d:multistatus>"
          response(socket, "207 Multi-Status", "application/xml", xml.toByteArray())
          return
        }
        if (request[0] == "POST" && path == "/api/search/metadata") {
          check(headers["content-type"]?.startsWith("application/json") == true)
          val search = JSONObject(body.toString(Charsets.UTF_8))
          check(search.getInt("page") == 1 && search.getString("type") == "IMAGE")
          val items = JSONArray()
          Asset.values().forEach { items.put(JSONObject().put("id", it.name.lowercase()).put("type", "IMAGE")) }
          val json = JSONObject().put("assets", JSONObject().put("items", items).put("nextPage", JSONObject.NULL))
          response(socket, "200 OK", "application/json", json.toString().toByteArray())
          return
        }
        check(request[0] == "GET")
        val backend = if (dav) Backend.DAV else Backend.IMMICH
        val asset = Asset.values().single { URI(url(backend, it)).rawPath == path }
        if (!dav) check(URI(request[1]).rawQuery == "size=preview")
        if (asset == Asset.DENIED) {
          response(socket, "401 Unauthorized", "text/plain", byteArrayOf())
          return
        }
        val encoded = bytes.getValue(asset)
        if (asset == Asset.HEADERS || asset == Asset.BODY) {
          slow = Slow()
          slowEvents.add(slow)
          if (asset == Asset.BODY) {
            responseHeaders(socket, "200 OK", "image/jpeg", encoded.size)
            socket.getOutputStream().write(encoded, 0, 64)
            socket.getOutputStream().flush()
          }
          socket.soTimeout = 100
          slow.blocked.countDown()
          observeDisconnect(socket, input, slow)
          if (slow.release.get() && !slow.peerClosed.get() && !closed.get()) {
            if (asset == Asset.HEADERS) response(socket, "200 OK", "image/jpeg", encoded)
            else { socket.getOutputStream().write(encoded, 64, encoded.size - 64); socket.getOutputStream().flush() }
          }
        } else response(socket, "200 OK", if (asset in setOf(Asset.READY, Asset.LATEST))
          "image/png" else "image/jpeg", encoded)
      } catch (error: Throwable) {
        if (!closed.get()) errors.add(error.javaClass.simpleName)
      } finally {
        runCatching { socket.close() }
        sockets.remove(socket)
        slow?.finished?.countDown()
      }
    }

    private fun observeDisconnect(socket: Socket, input: InputStream, slow: Slow) {
      while (!closed.get() && !slow.release.get() && SystemClock.elapsedRealtime() < deadline) {
        try {
          if (input.read() < 0) {
            slow.peerClosedAt = SystemClock.elapsedRealtime()
            slow.peerClosed.set(true)
            return
          }
          throw CheckFailure() // No pipelining/body remainder was requested.
        } catch (_: SocketTimeoutException) {
          // Keep the response blocked until a real client FIN/reset, release, or total deadline.
        } catch (_: java.net.SocketException) {
          if (!closed.get() && !socket.isClosed) {
            slow.peerClosedAt = SystemClock.elapsedRealtime()
            slow.peerClosed.set(true)
          }
          return
        }
      }
    }

    private fun responseHeaders(socket: Socket, status: String, type: String, length: Int) {
      socket.getOutputStream().write(
          "HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: $length\r\nConnection: close\r\n\r\n"
              .toByteArray(Charsets.US_ASCII))
      socket.getOutputStream().flush()
    }
    private fun response(socket: Socket, status: String, type: String, bytes: ByteArray) {
      responseHeaders(socket, status, type, bytes.size)
      socket.getOutputStream().write(bytes)
      socket.getOutputStream().flush()
    }
    override fun close() {
      if (closed.compareAndSet(false, true)) {
        runCatching { listener.close() }
        sockets.toList().forEach { runCatching { it.close() } }
        handlers.shutdownNow()
      }
      acceptor.join(500)
      handlers.awaitTermination(1500, TimeUnit.MILLISECONDS)
    }
    fun terminated(): Boolean = closed.get() && listener.isClosed && !acceptor.isAlive &&
        handlers.isTerminated && sockets.isEmpty()
  }

  private fun pool(name: String, size: Int, queue: Int) = ThreadPoolExecutor(
      size, size, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(queue),
      { action -> Thread(action, name).apply { isDaemon = true } })
  private fun field(owner: Any, name: String): Any? =
      owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
  private fun setField(owner: Any, name: String, value: Any?) {
    owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
  }
  private fun property(owner: Any, name: String): Any? =
      owner.javaClass.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercaseChar() })
          .apply { isAccessible = true }.invoke(owner)
  private fun invoke(owner: Any, name: String, types: Array<Class<*>>, vararg args: Any?): Any? =
      owner.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(owner, *args)
  private fun near(actual: Int, expected: Int): Boolean =
      kotlin.math.abs(Color.red(actual) - Color.red(expected)) <= 18 &&
          kotlin.math.abs(Color.green(actual) - Color.green(expected)) <= 18 &&
          kotlin.math.abs(Color.blue(actual) - Color.blue(expected)) <= 18
}
