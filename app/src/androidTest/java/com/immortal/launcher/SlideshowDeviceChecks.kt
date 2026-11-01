/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.animation.Animator
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import android.widget.ImageView
import android.widget.TextView
import androidx.exifinterface.media.ExifInterface
import com.immortal.launcher.settings.SettingsRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/**
 * Test-APK-only sidecar. Main wires command=slideshow and owns build/install/device execution.
 *
 * pixelsOnly=true runs real Android file/bytes/stream decodes without launching the viewer.
 * Otherwise check native modes, literal UI controls, latest-request navigation, stage/loader
 * ownership and Dream-vs-deliberate gesture policy. soakSeconds=0..7200 adds an optional soak;
 * soakMode is smartFrame/portraitPair/wholePhoto. Defaults: no soak, smartFrame.
 *
 * Long runs belong in an asynchronous CLI session (yield/poll <=60 seconds), not a blocking
 * host call. sendStatus and atomic private.json checkpoints update at most every ten seconds.
 * samples-private.jsonl appends bounded aggregate samples; no per-frame IO or busy loop.
 * Smart soak enables people framing and includes NASA in the local library, counting warm
 * cached face presentations and sampled native motion. It uses no remote photo source.
 * legacySmoke=true adds optional Ken Burns/filmstrip bridge checks on the two synthetic portraits.
 * legacyOnly=true skips native smoke and checks four legacy launches/three re-entries with a
 * 90-second probe budget, including same-mode re-entry and an independent WebView heartbeat.
 * After the first exit passes, seed a global timer pause before launch two to check startup recovery.
 *
 * Temporary globals use the registry and supported atomic source setters. Snapshot values
 * remain in memory; exact original key presence, source, secure Dream settings and presence
 * are restored. Main's final intentional mode selection happens AFTER this method returns.
 * Adjustment saves use only an owned synthetic asset and remove only that new metadata key.
 * No household image, hierarchy, preference value, URL, token or exception message is returned.
 * Pixel fixtures are generated; the test APK includes the licensed NASA/scikit-image astronaut.
 * Face inference reports its actual result within 15 seconds. Failure makes the result incomplete;
 * other smoke diagnostics still run, and soak waits for a passing smoke. Successful inference also
 * enables a real-controller cached-portrait/motion phase. A single portrait is not a detector
 * accuracy or network-isolation suite. The two-second video is a generated test color pattern.
   * dreamOnly=true verifies the normal OS Dream through the privileged SystemUI launcher.
 */
internal object SlideshowDeviceChecks {
  fun run(instrumentation: Instrumentation, automation: UiAutomation, options: Bundle): Bundle =
      Probe(instrumentation, automation, options).run()

  private const val WAIT_MS = 10_000L
  private const val POLL_MS = 80L
  private const val SMOKE_LIMIT_MS = 180_000L
  private const val LEGACY_LIMIT_MS = 90_000L
  private const val MAX_SOAK_SECONDS = 7200L
  private val modes = setOf("smartFrame", "portraitPair", "wholePhoto")
  private class ProbeFailure(val assertion: String) : RuntimeException()
  private data class ViewState(val key: String?, val count: Int, val pixels: Long, val bitmaps: Int)

  private class Probe(
      private val instrumentation: Instrumentation,
      private val automation: UiAutomation,
      private val options: Bundle,
  ) {
    private val context = instrumentation.targetContext
    private val started = SystemClock.elapsedRealtime()
    private val id = UUID.randomUUID().toString()
    private val relative = "cache/portal-ux-e2e/slideshow-$id"
    private val folder = File(context.cacheDir, "portal-ux-e2e/slideshow-$id")
    private val fixtures = SlideshowDeviceFixtures(File(folder, "fixtures"))
    private val assertions = JSONObject()
    private val skips = JSONObject()
    private val measurements = JSONObject()
    private val notCovered = JSONArray(listOf(
        "Human-face accuracy across albums and offline network-isolation coverage",
        "OS DreamService lifecycle and presence-driven sleep/redream",
        "Household/network/video sources and GPU/compositor delivered FPS",
        "Decode internal scratch peak and total GPU texture residency"))
    private val ownedBitmaps = ArrayList<Bitmap>()
    private val ownedPositionKeys = linkedSetOf<String>()
    private val ownedPositionWrites = ArrayList<Thread>()
    private val monitors = ArrayList<Instrumentation.ActivityMonitor>()
    private val touched = linkedMapOf<String, MutableSet<String>>()
    private var settingsBefore = emptyMap<String, JSONObject>()
    private var preferencesBefore: Map<String, *>? = null
    private var sourceBefore: ScreensaverConfig.Settings? = null
    private var presenceBefore: PresenceState? = null
    private var secureBefore = emptyMap<String, String?>()
    private var positionsBefore: JSONObject? = null
    private var positionsExisted = false
    private var safePositionSave = false
    private var configured = false
    private var host: Activity? = null
    private var legacyHeartbeat: WebView? = null
    private var metrics: SlideshowDeviceMetrics? = null
    private var loader: SlideshowLoader? = null
    private var phase = "setup"
    private var failed = false
    private var failureLabel: String? = null
    private var errorType: String? = null
    private var soakUntil = 0L
    private var sampleCount = 0
    private var peakPssKiB = 0L
    private var peakNativeBytes = 0L
    private var peakJavaBytes = 0L
    private var smokeDeadline = started + SMOKE_LIMIT_MS
    @Volatile private var lastTouchUpUptime = 0L

    fun run(): Bundle {
      try {
        require(options.getString("command") == "slideshow")
        val soak = optionLong("soakSeconds", 0)
        require(soak in 0..MAX_SOAK_SECONDS)
        require(options.getString("soakMode", "smartFrame") in modes)
        val legacyOnly = optionBoolean("legacyOnly")
        if (legacyOnly) {
          require(soak == 0L && !optionBoolean("pixelsOnly"))
          smokeDeadline = started + LEGACY_LIMIT_MS
        }
        check(folder.mkdirs())
        check(fixtures.root.mkdirs())
        snapshot()
        fixtures.create()
        if (!legacyOnly) fixtures.installVideo(instrumentation.context)
        if (legacyOnly) {
          skips.put("native_smoke_face_inference_video_soak", "legacyOnly=true")
          metrics = SlideshowDeviceMetrics()
          legacyBridgeChecks(reentries = true)
        } else if (optionBoolean("dreamOnly")) {
          require(soak == 0L && !optionBoolean("pixelsOnly"))
          dreamLifecycle()
        } else if (optionBoolean("startupOnly")) {
          require(soak == 0L && !optionBoolean("pixelsOnly"))
          phase = "targeted_startup"
          metrics = SlideshowDeviceMetrics()
          configure(fixtures.photos, "smartFrame", 30)
          launch("smartFrame", 1)
          closeHost()
        } else if (optionBoolean("sourceOnly")) {
          require(soak == 0L && !optionBoolean("pixelsOnly"))
          phase = "owned_http_sources"
          val sources = SlideshowSourceDeviceChecks.run(instrumentation, automation, context)
          measurements.put("ownedHttpSources", sources)
          demand("owned_http_sources_completed", sources.getBoolean("completed"))
        } else if (optionBoolean("soakOnly")) {
          require(soak > 0 && !optionBoolean("pixelsOnly"))
          metrics = SlideshowDeviceMetrics()
          soak(soak, options.getString("soakMode", "smartFrame"))
        } else {
          phase = "android_pixels"
          pixelChecks()
          phase = "bundled_face_inference"
          faceInference()
          checkpoint()
          if (!optionBoolean("pixelsOnly")) {
            metrics = SlideshowDeviceMetrics()
            phase = "smart_viewer"
            configure(fixtures.photos, "smartFrame", 30)
            launch("smartFrame", 1)
            viewerChecks()
            navigationChecks()
            editingPendingNextCheck()
            pausedCancelResizeChecks()
            stageAndLoaderChecks()
            phase = "identity_and_editor_callbacks"
            measurements.put("identityAndEditorRegressions", SlideshowContractDeviceChecks.run(
                instrumentation, context, File(folder, "contract-storage"), fixtures.tall,
                File(fixtures.photos, "02-portrait.png"), ::demand))
            dreamGesturePolicy()
            closeHost()
            releaseOwnedPixels()
            videoChecks()
            if (measurements.getJSONObject("faceInference").optBoolean("verified"))
              licensedFaceViewerChecks()
            phase = "whole_photo"
            configure(fixtures.photos, "wholePhoto", 30)
            launch("wholePhoto", 1)
            wholePhotoChecks()
            doneExits()
            phase = "portrait_pair"
            configure(fixtures.pair, "portraitPair", 30)
            launch("portraitPair", 2)
            val pair = main { stagePhotos(stage()) }
            demand("pair_uses_two_distinct_portraits", pair.size == 2 &&
                pair.map { it.key }.distinct().size == 2 &&
                pair.all { it.bitmap.height > it.bitmap.width })
            doneExits()
            phase = "singleton_pair"
            configure(fixtures.single, "portraitPair", 30)
            launch("portraitPair", 1)
            demand("singleton_never_duplicates_into_two_tiles", main { stage().photoCount == 1 })
            closeHost()
            if (optionBoolean("legacySmoke")) legacyBridgeChecks()
            if (soak > 0 && !failed) soak(soak, options.getString("soakMode", "smartFrame"))
            else if (soak > 0) skips.put("soak", "Smoke failed; rerun after fixing the reported failure")
          } else {
            require(soak == 0L)
            skips.put("viewer_stage_loader_soak", "pixelsOnly=true")
          }
        }
      } catch (error: Throwable) {
        failed = true
        failureLabel = if (error is ProbeFailure) error.assertion else phase
        errorType = error.javaClass.simpleName
        measurements.put("probeFailureFrames", JSONArray(error.stackTrace
            .filter { it.className.startsWith("com.immortal.launcher.") }
            .take(12).map { it.toString() }))
      } finally {
        cleanup()
        assertions.put("completed", !failed)
        runCatching { checkpoint() }.onFailure {
          failed = true
          assertions.put("private_report_written", false).put("completed", false)
        }
      }
      val summary = summary()
      return Bundle().apply {
        putBoolean("slideshowCompleted", !failed)
        putBoolean("slideshowFaceInferenceVerified",
            measurements.optJSONObject("faceInference")?.optBoolean("verified") == true)
        putString("assertions", assertions.toString())
        putString("slideshowReport", summary.toString())
        putString("report", "$relative/private.json")
        putString("privateArtifactPaths", JSONArray(listOf(
            "$relative/private.json", "$relative/samples-private.jsonl") +
            if (optionBoolean("legacyOnly")) emptyList() else listOf("$relative/fixtures")).toString())
      }
    }

    private fun snapshot() {
      settingsBefore = SettingsRegistry.domains.associate { it.id to it.flatJson(context) }
      preferencesBefore = context.getSharedPreferences("immortal_screensaver", Context.MODE_PRIVATE)
          .all.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
      sourceBefore = ScreensaverConfig.load(context)
      presenceBefore = PresenceHub.current.copy()
      secureBefore = listOf("screensaver_enabled", "screensaver_components",
          "screensaver_default_component").associateWith {
        Settings.Secure.getString(context.contentResolver, it)
      }
      val positions = File(context.filesDir, "slideshow_positions.json")
      positionsExisted = positions.exists()
      safePositionSave = !positionsExisted
      if (positions.isFile && positions.length() <= 128 * 1024) {
        positionsBefore = runCatching { JSONObject(positions.readText()) }.getOrNull()
        safePositionSave = positionsBefore?.length()?.let { it < 256 } == true
      }
      measurements.put("registryDomainsSnapshotted", settingsBefore.size)
    }

    private fun pixelChecks() {
      // Independent expected output TL/TR/BR/BL colors; never use the production EXIF helper.
      val r = SlideshowDeviceFixtures.RED
      val g = SlideshowDeviceFixtures.GREEN
      val b = SlideshowDeviceFixtures.BLUE
      val y = SlideshowDeviceFixtures.YELLOW
      val corners = arrayOf(intArrayOf(r, g, b, y), intArrayOf(g, r, y, b),
          intArrayOf(b, y, r, g), intArrayOf(y, b, g, r), intArrayOf(r, y, b, g),
          intArrayOf(y, r, g, b), intArrayOf(b, g, r, y), intArrayOf(g, b, y, r))
      var decoded = 0
      var largestBytes = 0L
      val decodeTimes = JSONArray()
      for ((index, file) in fixtures.orientations.withIndex()) {
        demand("fixture_exif_${index + 1}", ExifInterface(file.path)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) == index + 1)
        for (source in listOf("file", "bytes", "stream")) {
          bounded()
          val before = SystemClock.elapsedRealtime()
          val bitmap = decode(file, source)
          demand("exif_${index + 1}_${source}_decoded", bitmap != null)
          bitmap ?: continue
          try {
            pixelBound(bitmap, 2400, "exif_${index + 1}_$source")
            val swapped = index >= 4
            demand("exif_${index + 1}_${source}_upright_dimensions",
                bitmap.width == (if (swapped) 400 else 600) &&
                    bitmap.height == (if (swapped) 600 else 400))
            val points = arrayOf(1 to 1, 7 to 1, 7 to 7, 1 to 7)
            points.forEachIndexed { corner, point ->
              val actual = bitmap.getPixel(bitmap.width * point.first / 8,
                  bitmap.height * point.second / 8)
              val expected = corners[index][corner]
              demand("exif_${index + 1}_${source}_corner_$corner",
                  colorNear(actual, expected))
            }
            largestBytes = maxOf(largestBytes, bitmap.allocationByteCount.toLong())
            decoded++
          } finally { bitmap.recycle() }
          decodeTimes.put(JSONObject().put("exif", index + 1).put("source", source)
              .put("elapsedMs", SystemClock.elapsedRealtime() - before))
        }
      }
      for (source in listOf("file", "bytes", "stream")) {
        val bitmap = decode(fixtures.huge, source)
        demand("huge_${source}_decoded", bitmap != null)
        bitmap ?: continue
        try {
          pixelBound(bitmap, 2400, "huge_$source")
          demand("huge_${source}_sampled_dimensions", bitmap.width == 2000 && bitmap.height == 1500)
          largestBytes = maxOf(largestBytes, bitmap.allocationByteCount.toLong())
        } finally { bitmap.recycle() }
        demand("corrupt_${source}_rejected", decode(fixtures.corrupt, source) == null)
        val small = decode(fixtures.orientations[5], source, 120)
        demand("small_${source}_decoded", small != null)
        small?.let { try { pixelBound(it, 120, "small_$source") } finally { it.recycle() } }
      }
      demand("oversized_file_rejected", SlideshowDecoder.file(fixtures.oversized.path) == null)
      demand("oversized_stream_rejected", fixtures.oversized.inputStream().use {
        SlideshowDecoder.stream(it) == null
      })
      val png = SlideshowDecoder.file(File(fixtures.photos, "02-portrait.png").path)
      demand("upright_png_decoded", png != null)
      png?.let { try { pixelBound(it, 2400, "png") } finally { it.recycle() } }
      try {
        Thread.currentThread().interrupt()
        demand("interrupted_decode_rejected", SlideshowDecoder.file(fixtures.huge.path) == null &&
            SlideshowDecoder.bytes(fixtures.corrupt.readBytes()) == null)
        demand("decode_preserves_interrupt_flag", Thread.currentThread().isInterrupted)
      } finally { Thread.interrupted() }
      measurements.put("exifSourceCases", decoded).put("largestDecodedAllocationBytes", largestBytes)
          .put("decodeTimes", decodeTimes)
    }

    private fun decode(file: File, source: String, edge: Int = 2400): Bitmap? = when (source) {
      "file" -> SlideshowDecoder.file(file.path, edge)
      "bytes" -> SlideshowDecoder.bytes(file.readBytes(), edge)
      "stream" -> file.inputStream().use { SlideshowDecoder.stream(it, edge) }
      else -> error("Unknown fixture source")
    }

    private fun pixelBound(bitmap: Bitmap, edge: Int, label: String) {
      demand("${label}_edge_bound", bitmap.width in 1..edge && bitmap.height in 1..edge)
      demand("${label}_pixel_bound", bitmap.width.toLong() * bitmap.height <= 3L * 1024 * 1024)
      demand("${label}_allocation_bound", bitmap.allocationByteCount.toLong() in 1..(12L * 1024 * 1024))
      demand("${label}_argb_pixels", bitmap.config == Bitmap.Config.ARGB_8888)
    }

    private fun faceInference() {
      val before = SystemClock.elapsedRealtime()
      val result = JSONObject().put("verified", false).put("facesCount", 0)
          .put("fixture", "NASA Eileen Collins / scikit-image astronaut")
          .put("fixtureSha256", "88431cd9653ccd539741b555fb0a46b61558b301d4110412b5bc28b5e3ea6cb5")
          .put("inputAcquisition", "Bundled test-APK asset; no runtime image download")
          .put("model", SlideshowFaceMetadata.MODEL)
      measurements.put("faceInference", result)
      var store: SlideshowFaceStore? = null
      var input: Bitmap? = null
      try {
        // Instrumentation context owns test assets; targetContext.assets does not.
        val encoded = instrumentation.context.assets.open("slideshow/astronaut.png").use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(encoded)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        demand("licensed_face_asset_hash", hash == result.getString("fixtureSha256"))
        input = SlideshowDecoder.bytes(encoded)
        demand("licensed_face_asset_decoded", input != null && input.width == 512 && input.height == 512)
        val storage = File(folder, "face-storage").apply { check(mkdirs()) }
        val scoped = object : ContextWrapper(context) {
          override fun getFilesDir(): File = storage
          override fun getApplicationContext(): Context = this
        }
        val detector = SlideshowFaceStore(scoped)
        store = detector
        val key = "device-check:$id:astronaut:$hash:upright-512"
        val original = checkNotNull(input)
        detector.analyzeLater(key, original)
        // The real store must own its independent derivative, not retain the input bitmap.
        original.recycle()
        input = null
        val deadline = before + 15_000
        var regions: List<SlideshowGeometry.Region>? = null
        var status = "pending"
        while (SystemClock.elapsedRealtime() < deadline) {
          bounded()
          regions = detector.regionsFor(key)
          if (regions != null) { status = if (regions.isEmpty()) "no_face" else "faces"; break }
          // Atomic, bounded metadata reads distinguish a reported failure from a stalled SDK.
          val disk = SlideshowFaceCache(File(storage, "slideshow_faces/metadata.bin"))
          disk.load(System.currentTimeMillis())
          val entry = disk.entry(SlideshowFaceMetadata.key(key), System.currentTimeMillis())
          if (entry?.status == SlideshowFaceMetadata.Status.FAILURE) { status = "failure"; break }
          SystemClock.sleep(100)
        }
        if (status == "pending") status = "timeout"
        val faces = regions.orEmpty()
        val valid = faces.all { it.left.isFinite() && it.top.isFinite() &&
            it.right.isFinite() && it.bottom.isFinite() && it.left >= 0f && it.top >= 0f &&
            it.right <= 1f && it.bottom <= 1f && it.left < it.right && it.top < it.bottom }
        val verified = status == "faces" && faces.isNotEmpty() && valid
        result.put("status", status).put("facesCount", faces.size).put("validUprightBoxes", valid)
            .put("verified", verified)
        assertions.put("bundled_face_inference_verified", verified)
        if (!verified) {
          failed = true
          failureLabel = "bundled_face_inference_$status"
          result.put("requiredInferenceFailure", true)
        }
      } catch (error: Throwable) {
        if (error is ProbeFailure) throw error
        result.put("status", "error").put("errorType", error.javaClass.simpleName)
        assertions.put("bundled_face_inference_verified", false)
        failed = true
        failureLabel = "bundled_face_inference_error"
        errorType = error.javaClass.simpleName
      } finally {
        input?.let { if (!it.isRecycled) it.recycle() }
        store?.close()
        result.put("elapsedMs", SystemClock.elapsedRealtime() - before)
      }
      val fallback = SlideshowGeometry.plan(512, 512, 2160, 1440, emptyList(), true, true)
      demand("face_unavailable_whole_photo_fallback", fallback.whole &&
          fallback.start == fallback.end && fallback.start.scale > 0f)
    }

    private fun configure(directory: File, mode: String, interval: Int) {
      require(directory.canonicalPath.startsWith(fixtures.root.canonicalPath + File.separator))
      require(mode in modes || mode == "kenBurns" || mode == "filmstrip")
      configured = true // Cleanup must run even after a partially applied registry batch.
      val body = JSONObject().put("enabled", true).put("mode", mode).put("intervalSec", interval)
          .put("shuffle", false).put("includeVideo", false).put("photo_motion", true)
          .put("fit", ScreensaverConfig.FIT_FILL)
          .put("keep_people_in_frame", false).put("presenceMode", "ALWAYS_ON")
          .put("overnightEnabled", false).put("idleSleepMin", 0).put("sleepTimerEnabled", false)
          .put("welcomeEnabled", false).put("ambientDashboard", false).put("gestureWave", false)
          .put("soundscape", "off").put("showNowPlaying", false).put("antiBurnIn", false)
          // Prevent the controller's cache-disabled housekeeping from deleting household media.
          .put("cacheEnabled", true)
      apply("screensaver", body)
      apply("calendar", JSONObject().put("widgetOn", false))
      ScreensaverConfig.setFolder(context, directory.path)
      demand("fixture_source_atomic_folder", ScreensaverConfig.load(context).let {
        it.source == ScreensaverConfig.SOURCE_FOLDER && it.folderPath == directory.path && it.mode == mode
      })
    }

    private fun apply(domain: String, body: JSONObject) {
      val keys = body.keys().asSequence().toSet()
      touched.getOrPut(domain) { linkedSetOf() }.addAll(keys)
      val applied = main { SettingsRegistry.apply(context, domain, body) }
      demand("${domain}_registry_apply", applied == keys)
    }

    private fun launch(mode: String, count: Int) {
      val monitor = instrumentation.addMonitor(PhotoFramePreviewActivity::class.java.name, null, false)
      monitors += monitor
      metrics?.phase = if (mode in modes) SlideshowDeviceMetrics.Phase.STARTUP
          else SlideshowDeviceMetrics.Phase.LEGACY
      val begin = SystemClock.elapsedRealtime()
      context.startActivity(Intent(context, PhotoFramePreviewActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra(PhotoFramePreviewActivity.EXTRA_SHOW_WELCOME, false)
        putExtra(PhotoFramePreviewActivity.EXTRA_LAUNCH_DISMISS_APP, false)
        // Existing face preview extra prevents a household flip-clock WebView/network overlay.
        putExtra("face_json", """{"clock":{"mode":"none"},"weather":{"enabled":false},
          "nowPlaying":{"enabled":false},"battery":{"enabled":false},"caption":{"enabled":false}}""")
      })
      poll("${mode}_activity_created") { monitor.lastActivity != null }
      host = monitor.lastActivity
      main { metrics?.attach(checkNotNull(host).window) }
      if (mode !in modes) {
        poll("${mode}_owned_engine_created") { main {
          readField(controller(), "engineActive") == true && readField(controller(), "webView") is WebView
        } }
        return
      }
      try {
        poll("${mode}_owned_photo_ready") {
          main { stage().currentPhoto?.key?.let(::ownedKey) == true && stage().photoCount == count }
        }
      } catch (error: ProbeFailure) {
        measurements.put("launchFailureState", main {
          val c = controller()
          val settings = readField(c, "settings") as ScreensaverConfig.Settings
          val loader = readField(c, "nativeLoader")
          val work = readField(loader, "work")
          val executor = readField(work, "executor") as java.util.concurrent.ThreadPoolExecutor
          val future = readField(work, "active") as? java.util.concurrent.Future<*>
          val workerError = if (future?.isDone == true && !future.isCancelled) {
            try { future.get(); null } catch (failure: java.util.concurrent.ExecutionException) {
              failure.cause?.javaClass?.simpleName
            }
          } else null
          JSONObject().put("stopped", readField(c, "stopped"))
              .put("nativeStarting", readField(c, "nativeStarting"))
              .put("deferredSource", readField(c, "afterNativeStartup") != null)
              .put("localMode", readField(c, "localMode")).put("remoteMode", readField(c, "remoteMode"))
              .put("playlistSize", (readField(c, "playlist") as List<*>).size)
              .put("localDeckSize", (readField(c, "localDeckKeys") as List<*>).size)
              .put("controllerGeneration", readField(c, "gen"))
              .put("loaderGeneration", readField(loader, "generation"))
              .put("loaderClosed", readField(loader, "closed"))
              .put("readyQueued", readField(loader, "ready") != null)
              .put("deliveryPosted", readField(loader, "deliveryPosted"))
              .put("stageWidth", stage().width).put("stageHeight", stage().height)
              .put("stageVisible", stage().visibility == View.VISIBLE)
              .put("pendingStage", readField(stage(), "pending") != null)
              .put("photoPresent", stage().currentPhoto != null)
              .put("photoOwned", stage().currentPhoto?.key?.let(::ownedKey) == true)
              .put("photoCount", stage().photoCount)
              .put("mode", settings.mode).put("source", settings.source)
              .put("fixtureFolder", settings.folderPath?.startsWith(fixtures.root.path) == true)
              .put("hostFinishing", host?.isFinishing).put("hostDestroyed", host?.isDestroyed)
              .put("admittedBytes", (readField(c, "pixelBudget") as SlideshowPixelBudget).used())
              .put("activeWorkers", executor.activeCount).put("workerQueue", executor.queue.size)
              .put("workerDone", future?.isDone).put("workerCanceled", future?.isCancelled)
              .put("workerErrorType", workerError)
        })
        throw error
      }
      val elapsed = SystemClock.elapsedRealtime() - begin
      measurements.put("${mode}_${count}_launchToOwnedPhotoMs", elapsed)
      demand("${mode}_deliberate_preview_policy", main { controller().interactivePreview })
      state(mode)
      checkpoint()
    }

    private fun viewerChecks() {
      metrics?.phase = SlideshowDeviceMetrics.Phase.CONTROLS
      revealControls()
      listOf("Previous", "Pause", "Next", "Adjust", "Done").forEach { waitControl(it) }
      tap("Pause")
      waitControl("Resume")
      demand("pause_reaches_controller", main { readField(controller(), "paused") == true })
      val held = main { stage().currentPhoto }
      SystemClock.sleep(450)
      demand("pause_retains_photo", main { stage().currentPhoto?.key == held?.key })
      tap("Resume")
      waitControl("Pause")
      demand("resume_reaches_controller", main { readField(controller(), "paused") == false })
      tap("Pause")
      waitControl("Resume")
      val original = main { stage().currentPhoto!! }
      demand("adjust_only_owned_asset", ownedKey(original.key))
      val originalFile = checkNotNull(fixtures.photos.listFiles()?.singleOrNull {
        "local:${it.path}:${it.length()}:${it.lastModified()}" == original.key
      })
      val originalEncodedHash = sha256(originalFile)
      tap("Adjust")
      waitControl("Photo zoom")
      waitControl("Cancel")
      val zoom = waitControl("Photo zoom")
      touch(zoom.left + zoom.width() * .65f, zoom.exactCenterY())
      poll("adjust_changes_native_transform") {
        main { (stage().currentPhoto?.adjustment?.zoom ?: 1f) > 1.05f }
      }
      tap("Cancel")
      waitControl("Resume")
      demand("adjust_cancel_restores_composition", main {
        stage().currentPhoto?.bitmap === original.bitmap &&
            stage().currentPhoto?.adjustment == original.adjustment
      })
      if (safePositionSave) {
        val key = original.key
        val store = SlideshowFramingStore(context.filesDir)
        demand("owned_adjustment_key_initially_absent", store.get(key) == null)
        ownedPositionKeys += key // Register ownership before Save can enqueue an asynchronous write.
        tap("Adjust")
        waitControl("Photo zoom")
        tap("Show whole photo")
        val priorWrites = Thread.getAllStackTraces().keys.toSet()
        tap("Save position")
        ownedPositionWrites += main {
          Thread.getAllStackTraces().keys.filter { it.name == "slideshow-position" && it !in priorWrites }
        }
        poll("saved_position_available_in_store") { store.get(key) != null }
        waitLabel("Position saved")
        demand("original_file_not_modified_by_adjustment", sha256(originalFile) == originalEncodedHash)
        demand("original_bitmap_not_modified_by_adjustment", !original.bitmap.isRecycled)
      } else {
        skips.put("adjustment_save", "Existing metadata is invalid/oversized/full; Cancel still verified")
      }
    }

    private fun navigationChecks() {
      metrics?.phase = SlideshowDeviceMetrics.Phase.NAVIGATION
      val first = main { stage().currentPhoto!!.key }
      tap("Next")
      poll("ui_next_changes_owned_photo") {
        main { stage().currentPhoto?.key?.let { it != first && ownedKey(it) } == true }
      }
      tap("Previous")
      poll("ui_previous_returns_to_prior_photo") { main { stage().currentPhoto?.key == first } }
      main {
        repeat(4) { controller().next(); controller().prev() }
        controller().next()
      }
      val expected = main {
        val frame = controller()
        val index = readField(frame, "localIndex") as Int
        @Suppress("UNCHECKED_CAST")
        val playlist = readField(frame, "playlist") as List<MediaItem>
        val file = File(playlist[index].path)
        "local:${file.path}:${file.length()}:${file.lastModified()}"
      }
      poll("rapid_navigation_publishes_latest_request") { main { stage().currentPhoto?.key == expected } }
      state("rapid_navigation")
    }

    private fun stageAndLoaderChecks() {
      metrics?.phase = SlideshowDeviceMetrics.Phase.TRANSITION
      val first = testPhoto("stage-first")
      val second = testPhoto("stage-second")
      val latest = testPhoto("stage-latest")
      var oldFade: ObjectAnimator? = null
      var oldListeners = emptyList<Animator.AnimatorListener>()
      main {
        stage().show(listOf(first), false, false, 8000)
        stage().show(listOf(second), false, false, 8000)
        oldFade = readField(stage(), "fade") as? ObjectAnimator
        oldListeners = oldFade?.listeners?.toList().orEmpty()
        stage().show(listOf(latest), false, false, 8000)
        oldFade?.let { animator -> oldListeners.forEach { it.onAnimationEnd(animator) } }
      }
      demand("latest_stage_survives_old_end_callback", main {
        stage().currentPhoto?.key == latest.key && images(stage()).any { bitmap(it) === latest.bitmap }
      })
      if (oldListeners.isEmpty()) skips.put("old_animator_end_callback",
          "Platform animator disabled or no listener; latest stage retention still checked")
      else demand("obsolete_end_callback_exercised", true)
      SystemClock.sleep(SlideshowStage.DISSOLVE_MS + 100)
      state("after_old_callback")

      val storage = File(folder, "loader-storage").apply { check(mkdirs()) }
      val scoped = object : ContextWrapper(context) { override fun getFilesDir(): File = storage }
      val worker = SlideshowLoader(scoped)
      loader = worker
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      val retiredPixels = AtomicReference<Bitmap?>()
      val staleReady = AtomicInteger()
      val retained = main { stage().currentPhoto!! }
      val slow = SlideshowAsset("device-check:$id:obsolete", { edge ->
        entered.countDown()
        awaitUninterruptibly(release, 5000)
        SlideshowDecoder.file(File(fixtures.photos, "01-portrait.jpg").path, edge)
            .also { retiredPixels.set(it) }
      })
      try {
        main { worker.load(slow, emptyList(), null, false, false,
            { _, _ -> staleReady.incrementAndGet() }, { staleReady.incrementAndGet() }) }
        demand("delayed_preparation_entered", entered.await(3, TimeUnit.SECONDS))
        SystemClock.sleep(160)
        demand("pending_preparation_retains_visible_photo", main {
          stage().currentPhoto?.bitmap === retained.bitmap &&
              images(stage()).any { bitmap(it) === retained.bitmap } && !retained.bitmap.isRecycled
        })
        val fastReady = CountDownLatch(1)
        val fast = asset("latest-loader")
        main { worker.load(fast, emptyList(), null, false, false, { photos, _ ->
          ownedBitmaps.addAll(photos.map { it.bitmap })
          stage().show(photos, false, false, 8000)
          fastReady.countDown()
        }, { }) }
        release.countDown()
        demand("latest_loader_became_ready", fastReady.await(5, TimeUnit.SECONDS))
        demand("stale_loader_callback_rejected", staleReady.get() == 0)
        poll("obsolete_prepared_bitmap_released") { retiredPixels.get()?.isRecycled == true }
        val current = main { stage().currentPhoto!! }
        val failedLoad = CountDownLatch(1)
        main { worker.load(SlideshowAsset("device-check:$id:corrupt",
            { edge -> SlideshowDecoder.file(fixtures.corrupt.path, edge) }),
            emptyList(), null, false, false, { _, _ -> staleReady.incrementAndGet() },
            { failedLoad.countDown() }) }
        demand("failed_load_callback_received", failedLoad.await(4, TimeUnit.SECONDS))
        demand("failed_preparation_keeps_latest_pixels", main {
          stage().currentPhoto?.bitmap === current.bitmap && !current.bitmap.isRecycled &&
              images(stage()).any { bitmap(it) === current.bitmap }
        })
        val recovered = CountDownLatch(1)
        main { worker.load(asset("recovery"), emptyList(), null, false, false, { photos, _ ->
          ownedBitmaps.addAll(photos.map { it.bitmap })
          stage().show(photos, false, false, 8000); recovered.countDown()
        }, { }) }
        demand("valid_image_recovers_after_failure", recovered.await(4, TimeUnit.SECONDS))
      } finally {
        release.countDown()
        main { worker.close() }
        loader = null
      }
      queuedOwnershipChecks()
      closeDuringPreparationCheck()
      motionCheck()
    }

    private fun editingPendingNextCheck() {
      phase = "editing_pending_next"
      val work = main { readField(readField(controller(), "nativeLoader"), "work") as SlideshowWork }
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      val held = main { stage().currentPhoto!! }
      try {
        // Occupy the real loader's worker, so the actual Next button queues a deterministic
        // acquisition. The fixture ignores cancel until released, just like a late decoder.
        main { work.submit { entered.countDown(); awaitUninterruptibly(release, 5000) } }
        demand("pending_next_worker_gate_entered", entered.await(3, TimeUnit.SECONDS))
        tap("Next")
        tap("Adjust")
        waitControl("Cancel")
        demand("editor_owns_original_photo_while_next_pending", main {
          stage().currentPhoto?.bitmap === held.bitmap && !held.bitmap.isRecycled
        })
        release.countDown()
        poll("pending_next_worker_drained") {
          val executor = readField(work, "executor") as java.util.concurrent.ThreadPoolExecutor
          executor.activeCount == 0 && executor.queue.isEmpty()
        }
        main { } // Drain an acquisition's earlier UI handoff before checking editor ownership.
        SystemClock.sleep(200)
        demand("pending_next_cannot_replace_photo_during_edit", main {
          stage().currentPhoto?.bitmap === held.bitmap && !held.bitmap.isRecycled &&
              readField(readField(controller(), "viewerControls"), "editing") == true
        })
        waitControl("Cancel")
        tap("Cancel")
        waitControl("Resume")
        demand("pending_next_cancel_keeps_controller_paused", main {
          readField(controller(), "paused") == true
        })
      } finally { release.countDown() }
    }

    private fun pausedCancelResizeChecks() {
      phase = "paused_cancel_resize"
      val photo = testPhoto("paused-editor").copy(regions = listOf(
          SlideshowGeometry.Region(.4f, .4f, .6f, .6f)))
      main { stage().pause(); stage().show(listOf(photo), true, true, 8000) }
      SystemClock.sleep(SlideshowStage.DISSOLVE_MS + 100)
      val replacement = main { imageTransform(photo.bitmap) }
      SystemClock.sleep(200)
      demand("paused_replacement_stays_static", main { imageTransform(photo.bitmap) == replacement })
      main { stage().resume() }
      SystemClock.sleep(350)
      main { stage().pause() }
      val before = main { imageTransform(photo.bitmap) }
      SystemClock.sleep(350)
      demand("paused_mid_motion_stays_static", main { imageTransform(photo.bitmap) == before })
      tap("Adjust")
      waitControl("Photo zoom")
      tap("Cancel")
      waitControl("Resume")
      val canceled = main { imageTransform(photo.bitmap) }
      demand("paused_cancel_restores_exact_held_transform", canceled == before)
      SystemClock.sleep(350)
      demand("paused_cancel_stays_static", main {
        imageTransform(photo.bitmap) == canceled && stage().currentPhoto?.bitmap === photo.bitmap
      })
      val atResume = main { stage().resume(); imageTransform(photo.bitmap) }
      demand("resume_after_cancel_starts_at_held_pose", atResume == canceled)
      SystemClock.sleep(350)
      val resumed = main { imageTransform(photo.bitmap) }
      val enabled = Settings.Global.getFloat(context.contentResolver,
          Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
      if (enabled && android.os.Build.VERSION.SDK_INT >= 26)
        demand("resume_after_cancel_advances_from_held_point", resumed.scale > canceled.scale)
      else skips.put("resume_after_cancel", "Animations disabled or API 24/25 deliberately holds pose")
      main { stage().pause() }
      val dimensions = main { stage().width to stage().height }
      try {
        main {
          val native = stage()
          native.layout(native.left, native.top, native.left + dimensions.first - 32,
              native.top + dimensions.second - 24)
          demand("actual_stage_resize_exercised", native.width == dimensions.first - 32 &&
              native.height == dimensions.second - 24)
        }
        // A subsequent host relayout may restore fullscreen. Both size changes must preserve
        // pause, so compare after that relayout settles instead of assuming window dimensions.
        SystemClock.sleep(200)
        val resized = main { imageTransform(photo.bitmap) }
        SystemClock.sleep(350)
        demand("paused_resize_stays_static", main {
          imageTransform(photo.bitmap) == resized && stage().currentPhoto?.bitmap === photo.bitmap
        })
      } finally {
        main { stage().let { it.layout(it.left, it.top, it.left + dimensions.first,
            it.top + dimensions.second) } }
      }
      state("paused_cancel_resize")
    }

    private fun videoChecks() {
      phase = "synthetic_video"
      configure(fixtures.video, "smartFrame", 30)
      apply("screensaver", JSONObject().put("includeVideo", true))
      launch("smartFrame", 1)
      revealControls()
      tap("Pause")
      waitControl("Resume")
      SystemClock.sleep(SlideshowStage.DISSOLVE_MS + 100)
      nextVideoRetainsPhoto("valid_video_pending")
      demand("paused_video_preparation_retains_photo", main { stage().visibility == View.VISIBLE })
      tap("Resume")
      poll("synthetic_video_first_frame_rendered", 15_000) { main {
        readField(controller(), "videoReady") == true &&
          (readField(controller(), "videoView") as SlideshowVideoView).currentPosition > 0 &&
            stage().visibility != View.VISIBLE
      } }
      tap("Pause")
      demand("synthetic_video_pause_reaches_controller", main { readField(controller(), "paused") == true })
      doneExits()

      phase = "synthetic_video_error"
      configure(fixtures.videoError, "smartFrame", 30)
      apply("screensaver", JSONObject().put("includeVideo", true))
      launch("smartFrame", 1)
      revealControls()
      tap("Pause")
      waitControl("Resume")
      SystemClock.sleep(SlideshowStage.DISSOLVE_MS + 100)
      val original = main { stage().currentPhoto!!.key }
      nextVideoRetainsPhoto("corrupt_video_pending")
      poll("corrupt_video_error_recovers_to_owned_photo") { main {
        stage().visibility == View.VISIBLE && stage().currentPhoto?.key?.let {
          it != original && ownedKey(it)
        } == true && (readField(controller(), "videoView") as SlideshowVideoView).visibility != View.VISIBLE
      } }
      state("video_error_recovered")
      doneExits()
    }

    private fun nextVideoRetainsPhoto(label: String) {
      waitControl("Next")
      main {
        val held = stage().currentPhoto!!
        // Invoke the real, allowlisted native button synchronously to inspect preparation
        // before MediaPlayer's asynchronous ready/error event can reach the main looper.
        val button = descendants(checkNotNull(host).window.decorView).filterIsInstance<TextView>()
            .single { it.text.toString() == "Next" && it.isClickable && it.isShown }
        demand("${label}_actual_next_button_clicked", button.performClick())
        demand("${label}_photo_remains_visible", stage().visibility == View.VISIBLE &&
            stage().currentPhoto?.bitmap === held.bitmap && !held.bitmap.isRecycled &&
            images(stage()).any { bitmap(it) === held.bitmap && it.isShown })
      }
    }

    private fun licensedFaceViewerChecks() {
      phase = "licensed_face_native_viewer"
      configure(fixtures.licensed, "smartFrame", 30)
      apply("screensaver", JSONObject().put("keep_people_in_frame", true).put("photo_motion", true))
      launch("smartFrame", 1)
      val key = main { stage().currentPhoto!!.key }
      val analysisKey = "encoded:" + sha256(File(fixtures.licensed, "01-nasa-astronaut.png"))
      poll("native_loader_cached_real_face_regions", 15_000) { main {
        val delegate = readField(readField(controller(), "nativeLoader"), "faceDelegate") as Lazy<*>
        delegate.isInitialized() &&
            (delegate.value as SlideshowFaceStore).regionsFor(analysisKey)?.isNotEmpty() == true
      } }
      revealControls()
      val previousBitmap = main { stage().currentPhoto!!.bitmap }
      tap("Next") // Future presentations consume the cache; inference never mutates a live frame.
      poll("actual_controller_presents_cached_face_regions") { main {
        stage().currentPhoto?.let {
          it.key == key && it.bitmap !== previousBitmap && it.regions.isNotEmpty()
        } == true
      } }
      val photo = main { stage().currentPhoto!! }
      measurements.put("nativeCachedRealFaceCount", photo.regions.size)
      val plan = main { SlideshowGeometry.plan(photo.bitmap.width, photo.bitmap.height,
          stage().width, stage().height, photo.regions, true, true) }
      SystemClock.sleep(SlideshowStage.DISSOLVE_MS + 100)
      val before = main { imageTransform(photo.bitmap) }
      SystemClock.sleep(1200)
      val after = main { imageTransform(photo.bitmap) }
      val enabled = Settings.Global.getFloat(context.contentResolver,
          Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
      if (!plan.whole && plan.start != plan.end && enabled)
        demand("actual_controller_real_face_motion_advances", after.scale > before.scale)
      else {
        demand("real_face_conservative_or_disabled_motion_static", before == after)
        measurements.put("realFaceWholePhotoFallback", plan.whole)
        measurements.put("realFacePlatformAnimationsEnabled", enabled)
      }
      state("licensed_face_native_viewer")
      // Default-feed history keeps the bitmap but must consume late analysis on revisit.
      main {
        val fromHistory = photo.copy(key = analysisKey, regions = emptyList())
        controller().javaClass.getDeclaredMethod("presentNativeHistory", SlideshowPhoto::class.java)
            .apply { isAccessible = true }.invoke(controller(), fromHistory)
        demand("default_history_consumes_late_cached_face_regions",
            stage().currentPhoto?.let {
              it.bitmap === photo.bitmap && it.regions.isNotEmpty()
            } == true)
      }
      doneExits()
    }

    private fun legacyBridgeChecks(reentries: Boolean = false) {
      phase = "legacy_bridge"
      val sequence = if (reentries) listOf("kenBurns", "kenBurns", "filmstrip", "filmstrip")
          else listOf("kenBurns", "filmstrip")
      val cycles = JSONArray()
      measurements.put("legacyCycles", cycles).put("legacyCycleCount", 0)
          .put("legacyCompletedCycleCount", 0).put("legacyReentryCount", 0)
          .put("legacySeededRecoveryCount", 0)
      if (reentries) {
        val heartbeat = main {
          WebView(context).also {
            legacyHeartbeat = it
            SlideshowLegacyDeviceChecks.startHeartbeat(it)
          }
        }
        measurements.put("legacyHeartbeatBeforeEntry", SlideshowLegacyDeviceChecks.heartbeat(
            instrumentation, heartbeat, "legacy_heartbeat_before_entry", ::demand, ::bounded))
      }
      var previousActivity: Activity? = null
      var previousWeb: WebView? = null
      for ((index, mode) in sequence.withIndex()) {
        bounded()
        val label = "legacy_cycle_${index + 1}"
        phase = label
        val cycle = JSONObject().put("cycle", index + 1).put("mode", mode)
            .put("reentry", index > 0).put("ready", false).put("completed", false)
            .put("seededTimerPause", false).put("seededTimerRecovery", false)
        cycles.put(cycle)
        measurements.put("legacyCycleCount", cycles.length())
        configure(fixtures.pair, mode, 30)
        val begin = SystemClock.elapsedRealtime()
        cycle.put("readinessLatencyMs", JSONObject.NULL)
        try {
          if (reentries && index == 1) {
            // Cycle one's exit heartbeat already passed. Emulate an older build's global pause
            // only now, so it cannot hide the original regression at the first exit.
            main { checkNotNull(legacyHeartbeat).pauseTimers() }
            cycle.put("seededTimerPause", true)
          }
          launch(mode, 1)
          measurements.put("legacyReentryCount", index)
          val activity = checkNotNull(host)
          val web = main { readField(controller(), "webView") as WebView }
          demand("${label}_fresh_activity_and_webview", main {
            activity !== previousActivity && web !== previousWeb && !activity.isDestroyed
          })
          val bridge = SlideshowLegacyDeviceChecks.run(instrumentation, web, mode,
              fixtures.pair.path, { assertion, pass -> demand("${label}_$assertion", pass) },
              cycle, begin, ::bounded)
          measurements.put("${mode}_deviceBridge", bridge)
          if (cycle.getBoolean("seededTimerPause")) {
            demand("${label}_seeded_pause_scene_ready", cycle.getBoolean("ready"))
            // A JS evaluation callback alone does not prove global timers have resumed.
            cycle.put("heartbeatAfterSeededStartup", SlideshowLegacyDeviceChecks.heartbeat(
                instrumentation, checkNotNull(legacyHeartbeat), "${label}_seeded_pause_timer_recovered",
                ::demand, ::bounded))
            cycle.put("seededTimerRecovery", true)
            measurements.put("legacySeededRecoveryCount", 1)
          }
          previousActivity = activity
          previousWeb = web
          closeHost() // Await onDestroy/PhotoFrameController.stop before the next launch.
          cycle.put("exited", true)
          legacyHeartbeat?.let { heartbeat ->
            cycle.put("heartbeatAfterExit", SlideshowLegacyDeviceChecks.heartbeat(
                instrumentation, heartbeat, "${label}_other_webview_keeps_ticking", ::demand, ::bounded))
          }
          cycle.put("completed", true)
          measurements.put("legacyCompletedCycleCount", index + 1)
          checkpoint()
        } finally {
          if (cycle.isNull("readinessLatencyMs"))
            cycle.put("readinessLatencyMs", SystemClock.elapsedRealtime() - begin)
        }
      }
      if (reentries) {
        demand("legacy_three_exit_reentries_completed",
            measurements.getInt("legacyCompletedCycleCount") == 4 &&
                measurements.getInt("legacyReentryCount") == 3)
        demand("legacy_seeded_startup_recovery_completed",
            measurements.getInt("legacySeededRecoveryCount") == 1)
      }
    }

    private fun queuedOwnershipChecks() {
      val storage = File(folder, "queued-loader-storage").apply { check(mkdirs()) }
      val scoped = object : ContextWrapper(context) { override fun getFilesDir(): File = storage }
      val worker = SlideshowLoader(scoped)
      loader = worker
      val callbacks = AtomicInteger()
      val pixels = Array(3) { AtomicReference<Bitmap?>() }
      val kept = main { stage().currentPhoto!! }
      val begin = SystemClock.elapsedRealtime()
      // Deliberately hold UI delivery while workers finish. Exclude this artificial main-thread
      // backpressure from performance histograms; each wait is capped at three seconds.
      main { metrics?.detach() }
      try {
        main {
          fun submit(index: Int) {
            val asset = SlideshowAsset("device-check:$id:queued-$index", {
              Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888).apply {
                eraseColor(SlideshowDeviceFixtures.GREEN)
                pixels[index].set(this)
              }
            }, revisionVerified = true)
            worker.load(asset, emptyList(), null, false, false,
                { _, _ -> callbacks.incrementAndGet() }, { callbacks.incrementAndGet() })
            val stop = SystemClock.elapsedRealtime() + 3000
            var queued = false
            while (SystemClock.elapsedRealtime() < stop) {
              queued = synchronized(worker) {
                val ready = readField(worker, "ready") ?: return@synchronized false
                @Suppress("UNCHECKED_CAST")
                val photos = readField(ready, "photos") as List<SlideshowPhoto>
                photos.singleOrNull()?.key == asset.key
              }
              if (queued) break
              SystemClock.sleep(20)
            }
            demand("prepared_scene_${index}_waits_for_ui", queued)
          }
          submit(0)
          submit(1)
          demand("queued_replacement_releases_obsolete_pixels", pixels[0].get()?.isRecycled == true)
          demand("readiness_has_one_pending_ui_delivery", readField(worker, "deliveryPosted") == true)
          worker.cancel()
          demand("cancel_releases_queued_pixels",
              pixels[1].get()?.isRecycled == true && synchronized(worker) { readField(worker, "ready") == null })
          submit(2)
          worker.close()
          demand("close_releases_queued_pixels",
              pixels[2].get()?.isRecycled == true && synchronized(worker) { readField(worker, "ready") == null })
        }
        // A main-thread barrier runs after the previously posted readiness runnable.
        main { }
        demand("canceled_closed_queued_callbacks_suppressed", callbacks.get() == 0)
        demand("queued_failure_injection_retains_stage", main {
          stage().currentPhoto?.bitmap === kept.bitmap &&
              images(stage()).any { bitmap(it) === kept.bitmap } && !kept.bitmap.isRecycled
        })
      } finally {
        main { worker.close(); metrics?.attach(checkNotNull(host).window) }
        loader = null
        measurements.put("excludedUiBackpressureProbeMs", SystemClock.elapsedRealtime() - begin)
      }
    }

    private fun closeDuringPreparationCheck() {
      val storage = File(folder, "closed-loader-storage").apply { check(mkdirs()) }
      val scoped = object : ContextWrapper(context) { override fun getFilesDir(): File = storage }
      val worker = SlideshowLoader(scoped)
      loader = worker
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      val pixels = AtomicReference<Bitmap?>()
      val callbacks = AtomicInteger()
      try {
        val asset = SlideshowAsset("device-check:$id:closed-pending", {
          entered.countDown()
          awaitUninterruptibly(release, 5000)
          Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888).also(pixels::set)
        }, revisionVerified = true)
        main { worker.load(asset, emptyList(), null, false, false,
            { _, _ -> callbacks.incrementAndGet() }, { callbacks.incrementAndGet() }) }
        demand("close_fixture_preparation_entered", entered.await(3, TimeUnit.SECONDS))
        main { worker.close() }
        release.countDown()
        poll("close_during_decode_releases_late_pixels") { pixels.get()?.isRecycled == true }
        main { }
        demand("close_during_decode_suppresses_callback", callbacks.get() == 0)
      } finally {
        release.countDown()
        main { worker.close() }
        loader = null
      }
    }

    private fun motionCheck() {
      metrics?.phase = SlideshowDeviceMetrics.Phase.MOTION
      val photo = testPhoto("motion").copy(regions = listOf(
          SlideshowGeometry.Region(.4f, .4f, .6f, .6f)))
      main { stage().resume(); stage().show(listOf(photo), true, true, 8000) }
      SystemClock.sleep(SlideshowStage.DISSOLVE_MS + 100)
      val before = main { imageTransform(photo.bitmap) }
      SystemClock.sleep(1200)
      val after = main { imageTransform(photo.bitmap) }
      val enabled = Settings.Global.getFloat(context.contentResolver,
          Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
      if (enabled) demand("native_motion_advances", after.scale > before.scale)
      else skips.put("native_motion_advances", "Platform animator duration scale is zero")
      measurements.put("syntheticMotionScaleBefore", before.scale.toDouble())
          .put("syntheticMotionScaleAfter", after.scale.toDouble())
      state("synthetic_motion")
      main { stage().pause() }
      metrics?.phase = SlideshowDeviceMetrics.Phase.HOLD
    }

    private fun dreamGesturePolicy() {
      val frame = main { controller() }
      val originalExit = frame.onExit
      val originalInteractive = frame.interactivePreview
      val nativeModeField = PhotoFrameController::class.java.getDeclaredField("nativePhotoMode")
          .apply { isAccessible = true }
      val originalNativeMode = main { nativeModeField.getBoolean(frame) }
      val exits = AtomicInteger()
      try {
        main {
          frame.interactivePreview = false
          frame.onExit = { exits.incrementAndGet() }
          listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(1, 2, action, 100f, 100f, 0)
            try { frame.onTouch(event) } finally { event.recycle() }
          }
        }
        demand("dream_policy_tap_calls_exit_once", exits.get() == 1)
        demand("dream_policy_has_no_viewer_dispatch", main {
          val event = MotionEvent.obtain(1, 2, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
          try { !frame.dispatchViewerControls(event) } finally { event.recycle() }
        })
        main {
          frame.interactivePreview = true
          nativeModeField.setBoolean(frame, false)
          listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(1, 2, action, 100f, 100f, 0)
            try { frame.onTouch(event) } finally { event.recycle() }
          }
        }
        demand("webpage_source_retains_tap_to_exit", exits.get() == 2)
      } finally { main {
        frame.onExit = originalExit
        frame.interactivePreview = originalInteractive
        nativeModeField.setBoolean(frame, originalNativeMode)
      } }
    }

    private fun dreamLifecycle() {
      phase = "os_dream_lifecycle"
      configure(fixtures.photos, "smartFrame", 30)
      // These rich face/dismiss selections use their existing atomic setters. Exact preference
      // restoration in cleanup preserves both their prior values and absent/default key presence.
      main {
        ScreensaverConfig.setFacesEnabled(context, false)
        ScreensaverConfig.setDismissLauncher(context)
        val component = android.content.ComponentName(context.packageName,
            PhotoDreamService::class.java.name).flattenToString()
        listOf("screensaver_enabled" to "1", "screensaver_components" to component,
            "screensaver_default_component" to component).forEach { (key, value) ->
          demand("dream_secure_${key}_configured",
              Settings.Secure.putString(context.contentResolver, key, value) &&
                  Settings.Secure.getString(context.contentResolver, key) == value)
        }
      }
      val result = SlideshowDreamDeviceChecks.run(instrumentation, automation, ::ownedKey)
      measurements.put("osDream", JSONObject().apply {
        result.keySet().forEach { key -> put(key, result.get(key)) }
      })
      demand("actual_os_dream_lifecycle_completed", result.getBoolean("dreamLifecycleCompleted"))
      demand("actual_os_dream_native_photo_and_gesture_verified", result.getBoolean("dreamFullyVerified"))
      notCovered.put(1, "Idle/presence/power-manager initiation and forced redream; user-requested normal OS Dream lifecycle covered")
    }

    private fun wholePhotoChecks() {
      metrics?.phase = SlideshowDeviceMetrics.Phase.HOLD
      SystemClock.sleep(SlideshowStage.DISSOLVE_MS + 100)
      val current = main { stage().currentPhoto!! }
      val before = main { imageTransform(current.bitmap) }
      SystemClock.sleep(500)
      val after = main { imageTransform(current.bitmap) }
      demand("whole_mode_is_static", before == after)
      demand("whole_mode_contains_bitmap", main {
        val image = images(stage()).first { bitmap(it) === current.bitmap }
        val holder = image.parent as View
        image.translationX >= -.05f && image.translationY >= -.05f &&
            image.translationX + image.width * image.scaleX <= holder.width + .05f &&
            image.translationY + image.height * image.scaleY <= holder.height + .05f
      })
    }

    private fun soak(seconds: Long, mode: String) {
      phase = "soak"
      smokeDeadline = Long.MAX_VALUE
      configure(if (mode == "portraitPair") fixtures.pair else fixtures.photos, mode, 5)
      if (mode == "smartFrame") apply("screensaver", JSONObject().put("keep_people_in_frame", true))
      launch(mode, if (mode == "portraitPair") 2 else 1)
      val viewerExited = java.util.concurrent.atomic.AtomicBoolean()
      main {
        val frame = controller()
        val exit = frame.onExit
        frame.onExit = { viewerExited.set(true); exit?.invoke() }
      }
      metrics?.phase = SlideshowDeviceMetrics.Phase.SOAK
      val begin = SystemClock.elapsedRealtime()
      soakUntil = begin + seconds * 1000
      val initialGeneration = main { readField(controller(), "gen") as Int }
      var lastGeneration = initialGeneration
      var advancements = 0L
      var facePresentations = 0L
      var movingFaceSamples = 0L
      var lastFace: java.lang.ref.WeakReference<Bitmap>? = null
      val nasa = File(fixtures.photos, "06-nasa-astronaut.png")
      val nasaKey = "local:${nasa.path}:${nasa.length()}:${nasa.lastModified()}"
      do {
        val left = soakUntil - SystemClock.elapsedRealtime()
        if (left > 0) SystemClock.sleep(minOf(10_000, left))
        bounded()
        demand("soak_not_dismissed_by_viewer_input", !viewerExited.get())
        demand("soak_host_still_open", main {
          host?.let { !it.isFinishing && !it.isDestroyed } == true &&
              readField(controller(), "stopped") == false
        })
        state("soak")
        val generation = main { readField(controller(), "gen") as Int }
        if (generation > lastGeneration) advancements += generation - lastGeneration
        lastGeneration = generation
        if (mode == "smartFrame") {
          val sample = main {
            stage().currentPhoto?.takeIf { it.key == nasaKey && it.regions.isNotEmpty() }
                ?.let { it to imageTransform(it.bitmap) }
          }
          if (sample != null) {
            val (photo, before) = sample
            if (photo.bitmap !== lastFace?.get()) {
              facePresentations++
              lastFace = java.lang.ref.WeakReference(photo.bitmap)
            }
            SystemClock.sleep(250)
            val moved = main {
              stage().currentPhoto?.bitmap === photo.bitmap && !photo.bitmap.isRecycled &&
                  imageTransform(photo.bitmap) != before
            }
            if (moved) movingFaceSamples++
          }
          measurements.put("soakWarmRealFacePresentationsObserved", facePresentations)
              .put("soakNativeRealFaceMotionSamples", movingFaceSamples)
        }
        measurements.put("soakObservedAdvancements", advancements)
        measurements.put("soakElapsedSeconds", (SystemClock.elapsedRealtime() - begin) / 1000)
        checkpoint()
      } while (SystemClock.elapsedRealtime() < soakUntil)
      if (seconds >= 30) demand("soak_observed_automatic_advancement", advancements > 0)
      if (mode == "smartFrame" && seconds >= 90) {
        demand("smart_soak_observed_warm_real_face_presentations", facePresentations > 0)
        if (seconds >= 120)
          demand("smart_soak_reuses_warm_real_face_cache", facePresentations > 1)
        val enabled = Settings.Global.getFloat(context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        if (enabled) demand("smart_soak_observed_native_real_face_motion", movingFaceSamples > 0)
        else skips.put("smart_soak_native_motion", "Platform animator duration scale is zero")
      }
      measurements.put("soakRequestedSeconds", seconds)
      closeHost()
    }

    private fun state(label: String): ViewState {
      val result = main {
        val native = stage()
        val photos = stagePhotos(native)
        val views = images(native)
        val allocations = views.mapNotNull(::bitmap).distinct()
        demand("${label}_native_stage_type", native.javaClass == SlideshowStage::class.java &&
            native.visibility == View.VISIBLE && native.width > 0 && native.height > 0)
        demand("${label}_native_photo_count", photos.size in 1..2 && photos.size == native.photoCount)
        demand("${label}_only_owned_fixture_content", photos.all { ownedKey(it.key) })
        demand("${label}_prepared_pixels_live", photos.all {
          !it.bitmap.isRecycled && it.bitmap.allocationByteCount <= 12 * 1024 * 1024
        } && allocations.none { it.isRecycled })
        demand("${label}_no_webview_renderer", readField(controller(), "webView") == null &&
            readField(controller(), "engineActive") == false && descendants(checkNotNull(host).window.decorView)
                .none { it is WebView })
        val pixels = allocations.sumOf { it.allocationByteCount.toLong() }
        demand("${label}_two_layers_bounded_pixels", native.childCount == 2 &&
            allocations.size <= 4 && pixels <= 48L * 1024 * 1024)
        sharedAdmission(label)
        ViewState(native.currentPhoto?.key, photos.size, pixels, allocations.size)
      }
      measurements.put("stagePixelBytesMax", maxOf(measurements.optLong("stagePixelBytesMax"), result.pixels))
      measurements.put("stageBitmapCountMax", maxOf(measurements.optInt("stageBitmapCountMax"), result.bitmaps))
      return result
    }

    private fun testPhoto(tag: String): SlideshowPhoto {
      val bitmap = checkNotNull(SlideshowDecoder.file(File(fixtures.photos, "01-portrait.jpg").path))
      ownedBitmaps += bitmap
      return SlideshowPhoto("device-check:$id:$tag", bitmap)
    }

    private fun asset(tag: String): SlideshowAsset = SlideshowAsset("device-check:$id:$tag",
        { edge -> SlideshowDecoder.file(File(fixtures.photos, "02-portrait.png").path, edge) })

    private fun ownedKey(key: String): Boolean =
        key.startsWith("local:${fixtures.root.path}/") || key.startsWith("device-check:$id:")

    private fun controller(): PhotoFrameController =
        readField(checkNotNull(host), "frame") as PhotoFrameController

    private fun stage(): SlideshowStage = readField(controller(), "nativeStage") as SlideshowStage

    @Suppress("UNCHECKED_CAST")
    private fun stagePhotos(stage: SlideshowStage): List<SlideshowPhoto> =
        readField(checkNotNull(readField(stage, "shown")), "photos") as List<SlideshowPhoto>

    private fun imageTransform(bitmap: Bitmap): SlideshowGeometry.Transform {
      val image = images(stage()).first { bitmap(it) === bitmap }
      return SlideshowGeometry.Transform(image.scaleX, image.translationX, image.translationY)
    }

    private fun bitmap(image: ImageView): Bitmap? = (image.drawable as? BitmapDrawable)?.bitmap
    private fun images(root: View): List<ImageView> = descendants(root).filterIsInstance<ImageView>()

    private fun descendants(root: View): List<View> {
      val result = ArrayList<View>()
      fun visit(view: View, depth: Int) {
        check(depth <= 60 && result.size < 5000)
        result += view
        if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it), depth + 1) }
      }
      visit(root, 0)
      return result
    }

    private fun readField(owner: Any?, name: String): Any? {
      val target = checkNotNull(owner)
      return try {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
      } catch (_: NoSuchFieldException) {
        // Kotlin lazy/computed properties (notably nativeLoader) have a private getter.
        target.javaClass.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercaseChar() })
            .apply { isAccessible = true }.invoke(target)
      }
    }

    private fun sharedAdmission(label: String) {
      val frame = controller()
      val budget = listOf("pixelBudget", "nativePixelBudget").firstNotNullOfOrNull { name ->
        runCatching { readField(frame, name) }.getOrNull()
      }
      if (budget == null) {
        skips.put("shared_pixel_admission", "Controller has no accessible admission budget; compatibility build")
        return
      }
      val used = (budget.javaClass.getDeclaredMethod("used").invoke(budget) as Number).toLong()
      val peak = (budget.javaClass.getDeclaredMethod("getPeak").invoke(budget) as Number).toLong()
      demand("${label}_shared_admission_used_bound", used in 0..48L * 1024 * 1024)
      demand("${label}_shared_admission_peak_bound", peak in used..48L * 1024 * 1024)
      measurements.put("sharedAdmissionUsedBytesMax",
          maxOf(measurements.optLong("sharedAdmissionUsedBytesMax"), used))
      measurements.put("sharedAdmissionPeakBytes",
          maxOf(measurements.optLong("sharedAdmissionPeakBytes"), peak))
    }

    private fun <T> main(block: () -> T): T {
      var result: T? = null
      var error: Throwable? = null
      instrumentation.runOnMainSync { try { result = block() } catch (failure: Throwable) { error = failure } }
      error?.let { throw it }
      @Suppress("UNCHECKED_CAST")
      return result as T
    }

    private fun revealControls() {
      if (find("Pause", true) != null || find("Resume", true) != null) return
      val bounds = main { Rect().also { checkNotNull(host).window.decorView.getGlobalVisibleRect(it) } }
      touch(bounds.exactCenterX(), bounds.top + bounds.height() * .25f)
      poll("viewer_controls_revealed") { find("Pause", true) != null || find("Resume", true) != null }
    }

    private fun doneExits() {
      revealControls()
      val activity = checkNotNull(host)
      val exitUptime = java.util.concurrent.atomic.AtomicLong()
      main {
        val frame = controller()
        val exit = frame.onExit
        frame.onExit = { exitUptime.set(SystemClock.uptimeMillis()); exit?.invoke() }
      }
      val begin = SystemClock.elapsedRealtime()
      tap("Done")
      poll("done_requests_finish") { main { activity.isFinishing } }
      measurements.put("doneProbeToFinishObservedMs", SystemClock.elapsedRealtime() - begin)
      demand("done_exit_callback_observed", exitUptime.get() >= lastTouchUpUptime && lastTouchUpUptime > 0)
      measurements.put("doneInputUpToExitCallbackMs", exitUptime.get() - lastTouchUpUptime)
      poll("done_closes_owned_preview") { main { activity.isDestroyed } }
      measurements.put("doneToDestroyedMs", SystemClock.elapsedRealtime() - begin)
      main { metrics?.detach() }
      host = null
    }

    private fun waitControl(label: String): Rect {
      var found: Rect? = null
      poll("control_$label") { found = find(label, true); found != null }
      return checkNotNull(found)
    }

    private fun waitLabel(label: String) {
      poll("label_$label") { find(label, false) != null }
    }

    private fun tap(label: String) {
      val bounds = waitControl(label)
      touch(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun touch(x: Float, y: Float) {
      val root = automation.rootInActiveWindow
      demand("input_targets_instrumented_package", root?.packageName?.toString() == context.packageName)
      root?.recycle()
      val begin = SystemClock.uptimeMillis()
      for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
        if (action == MotionEvent.ACTION_UP) SystemClock.sleep(40)
        val eventUptime = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_UP) lastTouchUpUptime = eventUptime
        val event = MotionEvent.obtain(begin, eventUptime, action, x, y, 0)
            .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { demand("native_input_injected", automation.injectInputEvent(event, true)) }
        finally { event.recycle() }
      }
    }

    /** Allowlisted literal labels only; never dump the accessibility tree or household strings. */
    private fun find(label: String, actionable: Boolean): Rect? {
      val root = automation.rootInActiveWindow ?: return null
      val count = intArrayOf(0)
      fun visit(node: AccessibilityNodeInfo, depth: Int): Rect? {
        if (++count[0] > 4000 || depth > 60) return null
        if (node.isVisibleToUser && node.isEnabled &&
            (node.text?.toString() == label || node.contentDescription?.toString() == label) &&
            (!actionable || node.isClickable ||
                node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id })) {
          val bounds = Rect().also(node::getBoundsInScreen)
          if (!bounds.isEmpty) return bounds
        }
        for (index in 0 until node.childCount) {
          val child = node.getChild(index) ?: continue
          try { visit(child, depth + 1)?.let { return it } } finally { child.recycle() }
        }
        return null
      }
      try {
        if (root.packageName?.toString() != context.packageName) return null
        return visit(root, 0)
      } finally { root.recycle() }
    }

    private fun poll(label: String, timeout: Long = WAIT_MS, condition: () -> Boolean) {
      val stop = SystemClock.elapsedRealtime() + timeout
      while (SystemClock.elapsedRealtime() < stop) {
        bounded()
        if (condition()) { assertions.put(label, true); return }
        SystemClock.sleep(POLL_MS)
      }
      demand(label, false)
    }

    private fun demand(label: String, pass: Boolean) {
      synchronized(assertions) { assertions.put(label, pass) }
      if (!pass) throw ProbeFailure(label)
    }

    private fun bounded() {
      if (Thread.currentThread().isInterrupted) throw ProbeFailure("probe_interrupted")
      if (SystemClock.elapsedRealtime() >= smokeDeadline) throw ProbeFailure("smoke_deadline")
      if (soakUntil > 0 && SystemClock.elapsedRealtime() > soakUntil + 15_000)
        throw ProbeFailure("soak_deadline")
    }

    private fun awaitUninterruptibly(latch: CountDownLatch, millis: Long) {
      val stop = SystemClock.elapsedRealtime() + millis
      while (SystemClock.elapsedRealtime() < stop) {
        try {
          if (latch.await(minOf(1000, stop - SystemClock.elapsedRealtime()).coerceAtLeast(1),
              TimeUnit.MILLISECONDS)) return
        } catch (_: InterruptedException) {
          // Deliberate fixture: emulate a decode that finishes despite obsolete-request cancel.
        }
      }
      throw ProbeFailure("fixture_release_timeout")
    }

    private fun closeHost() {
      val activity = host ?: return
      main { metrics?.detach(); if (!activity.isFinishing) activity.finish() }
      val stop = SystemClock.elapsedRealtime() + WAIT_MS
      while (!main { activity.isDestroyed } && SystemClock.elapsedRealtime() < stop) SystemClock.sleep(POLL_MS)
      check(main { activity.isDestroyed })
      host = null
    }

    private fun cleanup() {
      phase = "cleanup"
      fun attempt(label: String, action: () -> Unit) {
        try { action(); assertions.put(label, true) }
        catch (_: Throwable) { failed = true; assertions.put(label, false) }
      }
      attempt("owned_loader_closed") { main { loader?.close() }; loader = null }
      attempt("owned_preview_destroyed") { closeHost() }
      attempt("legacy_heartbeat_destroyed_and_timers_restored") {
        main {
          legacyHeartbeat?.let { web ->
            // Only cleanup may undo global suppression after the regression has been observed.
            try { web.resumeTimers() } finally { web.stopLoading(); web.destroy() }
          }
          legacyHeartbeat = null
        }
      }
      attempt("owned_adjustment_metadata_removed") {
        // Await only newly observed threads created by our Save action before removing its key.
        ownedPositionWrites.forEach { thread -> thread.join(4000); check(!thread.isAlive) }
        val store = SlideshowFramingStore(context.filesDir)
        ownedPositionKeys.forEach { key -> check(ownedKey(key)); store.save(key, null) }
        if (ownedPositionKeys.isNotEmpty()) {
          val file = File(context.filesDir, "slideshow_positions.json")
          val restored = JSONObject(file.readText())
          check(restored.toString() == (positionsBefore ?: JSONObject()).toString())
          if (!positionsExisted && restored.length() == 0) check(file.delete())
        }
      }
      attempt("registry_values_and_source_restored") {
        if (configured) {
          val original = checkNotNull(sourceBefore)
          main {
            touched.forEach { (domain, keys) ->
              val before = settingsBefore.getValue(domain)
              val body = JSONObject()
              keys.forEach { key -> if (before.has(key)) body.put(key, before.get(key)) }
              SettingsRegistry.apply(context, domain, body)
            }
            restoreSource(original)
            restoreExactPreferences()
            // Re-run the domain-owned hook after restoring original absent/default storage.
            SettingsGuard.afterScreensaverApply(context, touched["screensaver"].orEmpty())
          }
          touched.forEach { (domain, keys) ->
            val current = checkNotNull(SettingsRegistry.domain(domain)).flatJson(context)
            keys.forEach { key -> check(current.opt(key) == settingsBefore.getValue(domain).opt(key)) }
          }
          check(ScreensaverConfig.load(context) == original)
        }
      }
      attempt("secure_dream_settings_restored") {
        if (configured) main {
          secureBefore.forEach { (key, value) ->
            if (Settings.Secure.getString(context.contentResolver, key) != value)
              check(Settings.Secure.putString(context.contentResolver, key, value))
            check(Settings.Secure.getString(context.contentResolver, key) == value)
          }
        }
      }
      attempt("presence_snapshot_restored") {
        presenceBefore?.let { original ->
          if (PresenceHub.current != original) main {
            PresenceHub::class.java.getDeclaredField("current")
                .apply { isAccessible = true }.set(PresenceHub, original)
            @Suppress("UNCHECKED_CAST")
            val listeners = PresenceHub::class.java.getDeclaredField("listeners")
                .apply { isAccessible = true }.get(PresenceHub) as List<PresenceHub.Listener>
            listeners.forEach { it.onPresenceChanged(original) }
            PresenceHub::class.java.getDeclaredMethod("publish", Context::class.java, PresenceState::class.java)
                .apply { isAccessible = true }.invoke(PresenceHub, context, original)
          }
          check(PresenceHub.current == original)
        }
      }
      attempt("owned_pixels_released_after_preview_destroyed") {
        // The stage is cleared/destroyed before the test releases its synthetic allocations.
        if (host == null) releaseOwnedPixels()
      }
      attempt("legacy_owned_fixtures_removed") {
        if (optionBoolean("legacyOnly") && fixtures.root.exists()) {
          check(host == null)
          check(fixtures.root.canonicalFile.parentFile == folder.canonicalFile)
          check(fixtures.root.deleteRecursively())
        }
      }
      attempt("frame_listener_released") { main { metrics?.detach() }; metrics?.close() }
      monitors.forEach { instrumentation.removeMonitor(it) }
    }

    private fun releaseOwnedPixels() {
      check(host == null)
      ownedBitmaps.distinct().forEach { if (!it.isRecycled) it.recycle() }
      ownedBitmaps.clear()
    }

    private fun restoreSource(original: ScreensaverConfig.Settings) {
      when (original.source) {
        ScreensaverConfig.SOURCE_FOLDER -> ScreensaverConfig.setFolder(context, original.folderPath.orEmpty())
        ScreensaverConfig.SOURCE_URL -> ScreensaverConfig.setAlbumUrl(context, original.albumUrl.orEmpty())
        ScreensaverConfig.SOURCE_IMMICH -> ScreensaverConfig.setImmich(context,
            original.immichUrl.orEmpty(), original.immichKey.orEmpty())
        ScreensaverConfig.SOURCE_SMB -> ScreensaverConfig.setSmb(context,
            original.smbHost.orEmpty(), original.smbShare.orEmpty(), original.smbPath.orEmpty(),
            original.smbUser.orEmpty(), original.smbPass.orEmpty())
        ScreensaverConfig.SOURCE_DAV -> ScreensaverConfig.setDav(context,
            original.davUrl.orEmpty(), original.davUser.orEmpty(), original.davPass.orEmpty())
        ScreensaverConfig.SOURCE_WEBURL -> ScreensaverConfig.setWebUrl(context, original.webUrl.orEmpty())
        else -> ScreensaverConfig.useDefault(context)
      }
    }

    /** Test-only restoration preserves dormant source fields and absent/default key presence. */
    private fun restoreExactPreferences() {
      val preferences = context.getSharedPreferences("immortal_screensaver", Context.MODE_PRIVATE)
      val before = checkNotNull(preferencesBefore)
      val now = preferences.all
      val editor = preferences.edit()
      (before.keys + now.keys).filter { before[it] != now[it] || before.containsKey(it) != now.containsKey(it) }
          .forEach { key -> putPreference(editor, key, before[key]) }
      check(editor.commit())
      check(preferences.all == before)
    }

    private fun putPreference(editor: SharedPreferences.Editor, key: String, value: Any?) {
      when (value) {
        null -> editor.remove(key)
        is Boolean -> editor.putBoolean(key, value)
        is Int -> editor.putInt(key, value)
        is Long -> editor.putLong(key, value)
        is Float -> editor.putFloat(key, value)
        is String -> editor.putString(key, value)
        is Set<*> -> {
          @Suppress("UNCHECKED_CAST")
          editor.putStringSet(key, value as Set<String>)
        }
        else -> error("Unsupported preference type")
      }
    }

    private fun checkpoint() {
      val memory = SlideshowDeviceMetrics.memory()
      peakPssKiB = maxOf(peakPssKiB, memory.getLong("processPssKiB"))
      peakNativeBytes = maxOf(peakNativeBytes, memory.getLong("nativeHeapAllocatedBytes"))
      peakJavaBytes = maxOf(peakJavaBytes, memory.getLong("javaHeapUsedBytes"))
      val sample = JSONObject().put("elapsedMs", SystemClock.elapsedRealtime() - started)
          .put("phase", phase).put("memory", memory).put("frames", metrics?.frames() ?: JSONObject())
      File(folder, "samples-private.jsonl").appendText(sample.toString() + "\n")
      sampleCount++
      val raw = summary().put("latestMemory", memory)
          .put("frameDefinition", "Window FrameMetrics.TOTAL_DURATION; host timing, not delivered FPS")
          .put("startupDefinition", "startActivity to owned native photo metadata, including polling")
          .put("fixtureLicense", "Synthetic pixels generated by test; NASA astronaut public domain, source in test assets")
      val temp = File(folder, "private.json.tmp")
      temp.writeText(raw.toString(2))
      check(temp.renameTo(File(folder, "private.json")))
      instrumentation.sendStatus(0, Bundle().apply {
        putString("slideshowPhase", phase)
        putLong("slideshowElapsedSeconds", (SystemClock.elapsedRealtime() - started) / 1000)
        putInt("slideshowSamples", sampleCount)
        putString("slideshowPrivateReport", "$relative/private.json")
      })
    }

    private fun summary(): JSONObject = JSONObject().put("completed", !failed)
        .put("phase", phase).put("failedAssertion", failureLabel ?: JSONObject.NULL)
        .put("errorType", errorType ?: JSONObject.NULL).put("assertions", assertions)
        .put("skips", skips).put("measurements", measurements).put("notCovered", notCovered)
        .put("frames", metrics?.frames() ?: JSONObject()).put("memorySamples", sampleCount)
        .put("sampledPeakPssKiB", peakPssKiB).put("sampledPeakNativeBytes", peakNativeBytes)
        .put("sampledPeakJavaBytes", peakJavaBytes).put("elapsedMs", SystemClock.elapsedRealtime() - started)
        .put("privateReport", "$relative/private.json")

    private fun colorNear(actual: Int, expected: Int): Boolean =
        kotlin.math.abs(Color.red(actual) - Color.red(expected)) <= 18 &&
            kotlin.math.abs(Color.green(actual) - Color.green(expected)) <= 18 &&
            kotlin.math.abs(Color.blue(actual) - Color.blue(expected)) <= 18

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun optionLong(name: String, default: Long): Long =
        options.get(name)?.toString()?.toLongOrNull() ?: if (options.containsKey(name)) -1 else default

    private fun optionBoolean(name: String): Boolean = options.get(name)?.toString() == "true"
  }
}
