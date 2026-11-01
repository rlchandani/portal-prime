/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.WebView
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parent supplies one fresh generated file:// photo and configures folder/panorama/5s playback.
 * Owns only a UUID-created preview, synthetic cache entries and three Window PixelCopy PNGs.
 * Never reads settings or source credentials. Pixel geometry and ML recall need separate checks.
 */
internal object SlideshowLiveFaceDeviceChecks {
  private const val HOST = "com.immortal.launcher.PhotoFramePreviewActivity"
  private const val POINT = "0.2,0.5,0.8"
  private const val REGION = "$POINT,0.15,0.2,0.75,0.7"

  fun run(probe: Instrumentation, options: Bundle): Bundle = Probe(probe, options).run()

  private class Probe(private val probe: Instrumentation, private val options: Bundle) {
    private val began = SystemClock.elapsedRealtime()
    private val workDeadline = began + 48_000
    private val deadline = began + 55_000
    private val token = UUID.randomUUID().toString()
    private val prefix = "live-face-owned-$token"
    private val reportFile = File(probe.targetContext.cacheDir, "$prefix.json")
    private val handler = Handler(Looper.getMainLooper())
    private val application = probe.targetContext.applicationContext as Application
    private val owned = mutableListOf<Activity>() // Access only on Main.
    private val drawRemovals = mutableListOf<() -> Unit>() // Access only on Main.
    private val created = CountDownLatch(1)
    private val activity = AtomicReference<Activity>()
    private val checks = JSONArray()
    private val artifacts = JSONArray()
    private val scenes = JSONObject()
    private val timings = JSONObject()
    private var failures = 0
    private var completed = false
    private var registered = false
    private var phase = "fixture"
    @Volatile private var closing = false
    private var webView: WebView? = null
    private var initialActivity: Activity? = null
    private var photoUrl = ""

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
      override fun onActivityCreated(host: Activity, state: Bundle?) {
        if (host.javaClass.name != HOST || host.intent.getStringExtra("owned_probe") != token) return
        owned.add(host)
        activity.compareAndSet(null, host)
        if (closing) host.finish()
        created.countDown()
      }
      override fun onActivityStarted(host: Activity) {}
      override fun onActivityResumed(host: Activity) {}
      override fun onActivityPaused(host: Activity) {}
      override fun onActivityStopped(host: Activity) {}
      override fun onActivitySaveInstanceState(host: Activity, state: Bundle) {}
      override fun onActivityDestroyed(host: Activity) {}
    }

    private fun expect(name: String, passed: Boolean) {
      checks.put(JSONObject().put("name", name).put("passed", passed))
      if (!passed) failures++
    }

    private fun demand(name: String, passed: Boolean) {
      expect(name, passed)
      check(passed) { name }
    }

    private fun remaining(limit: Long = 3_000, cleanup: Boolean = false): Long =
        minOf(limit, (if (cleanup) deadline else workDeadline) - SystemClock.elapsedRealtime())
            .coerceAtLeast(0)

    /** Handler/latch rather than unbounded runOnMainSync or overall-idle waits. */
    private fun <T> main(cleanup: Boolean = false, block: () -> T): T {
      val done = CountDownLatch(1)
      val expired = AtomicBoolean()
      var result: T? = null
      var failure: Throwable? = null
      val task = Runnable {
        try { if (!expired.get()) result = block() }
        catch (error: Throwable) { failure = error }
        finally { done.countDown() }
      }
      check(handler.post(task))
      try {
        check(done.await(remaining(cleanup = cleanup), TimeUnit.MILLISECONDS))
      } finally {
        expired.set(true)
        handler.removeCallbacks(task)
      }
      failure?.let { throw it }
      @Suppress("UNCHECKED_CAST")
      return result as T
    }

    private fun ownedHost(): Activity = checkNotNull(activity.get()).also { host ->
      check(host === initialActivity && owned.size == 1 && owned.single() === host &&
          host.javaClass.name == HOST && host.intent.getStringExtra("owned_probe") == token &&
          !host.isDestroyed && !host.isFinishing)
    }

    private fun sameOwners() = main {
      val host = ownedHost()
      val views = webViews(host.window.decorView)
      views.size == 1 && views.single() === webView &&
          webView?.isAttachedToWindow == true && webView?.isShown == true
    }

    /** Two genuine owned-tree draw callbacks before each Window PixelCopy. */
    private fun frame() {
      val drawn = CountDownLatch(1)
      var draws = 0
      var preDraws = 0
      val decor = main { ownedHost().window.decorView }
      val before = ViewTreeObserver.OnPreDrawListener { preDraws++; true }
      val listener = ViewTreeObserver.OnDrawListener {
        draws++
        if (draws >= 2 && preDraws > 0) drawn.countDown()
        else decor.postInvalidateOnAnimation()
      }
      val remove: () -> Unit = {
        if (decor.viewTreeObserver.isAlive) {
          decor.viewTreeObserver.removeOnPreDrawListener(before)
          decor.viewTreeObserver.removeOnDrawListener(listener)
        }
      }
      try {
        main {
          ownedHost()
          drawRemovals.add(remove)
          decor.viewTreeObserver.addOnPreDrawListener(before)
          decor.viewTreeObserver.addOnDrawListener(listener)
          decor.postInvalidateOnAnimation()
        }
        check(drawn.await(remaining(), TimeUnit.MILLISECONDS))
      } finally {
        main(cleanup = true) {
          remove()
          drawRemovals.remove(remove)
        }
      }
    }

    /** Read only generated-deck booleans and style hashes, never a URL/hierarchy dump. */
    private fun scene(expectedCache: String): JSONObject {
      val done = CountDownLatch(1)
      var result = "null"
      val script = """
        (function() {
          var ps=window.PHOTOS||[], p=ps.length===1&&ps[0].url===${JSONObject.quote(photoUrl)}?ps[0]:null;
          var roots=Array.from(document.querySelectorAll('#stage .ss-beat.on .panorama-drift'));
          var live=roots.filter(function(r) {
            var s=getComputedStyle(r);
            return !r.classList.contains('retired')&&s.visibility!=='hidden'&&Number(s.opacity)>.99;
          });
          var root=live.length===1?live[0]:null, pan=root&&root.querySelector('.ss-pan');
          function hash(s) {var h=2166136261;for(var i=0;i<s.length;i++)
            h=Math.imul(h^s.charCodeAt(i),16777619);return h>>>0;}
          var loading=document.getElementById('loading'), raw='';
          try {if(p&&window.Android&&typeof Android.getPhotoFocus==='function')
            raw=Android.getPhotoFocus(p.id);} catch(_) {}
          var style=pan?getComputedStyle(pan):null, fb=p&&p.fb;
          return {
            ownedDeck:!!p, ready:!!pan&&!!loading&&getComputedStyle(loading).display==='none'&&
              style.backgroundImage!=='none'&&pan.getBoundingClientRect().width>0,
            liveLayers:live.length, bridgeCacheMatches:raw===${JSONObject.quote(expectedCache)},
            pointMatches:!!p&&p.fx===.2&&p.fy===.5&&p.pw===.8,
            regionMatches:Array.isArray(fb)&&JSON.stringify(fb)===JSON.stringify([.15,.2,.75,.7]),
            hasRegion:Array.isArray(fb),
            styleToken:hash(pan?[pan.style.width,pan.style.height,pan.style.top,
              pan.style.transform,pan.style.animation,style.backgroundSize,style.backgroundPosition].join('|'):''),
            imageToken:hash(style?style.backgroundImage:'')
          };
        })()
      """.trimIndent()
      main {
        check(sameViewOnMain())
        checkNotNull(webView).evaluateJavascript(script) { result = it; done.countDown() }
      }
      check(done.await(remaining(), TimeUnit.MILLISECONDS))
      return JSONObject(result)
    }

    private fun sameViewOnMain(): Boolean {
      val views = webViews(ownedHost().window.decorView)
      return views.size == 1 && views.single() === webView
    }

    private fun waitUntil(until: Long) {
      while (SystemClock.elapsedRealtime() < until) {
        check(remaining() > 0)
        SystemClock.sleep(minOf(80, until - SystemClock.elapsedRealtime()).coerceAtLeast(1))
      }
    }

    private fun capture(phase: String): JSONObject {
      demand("${phase}_same_owned_activity_and_webview", sameOwners())
      frame()
      val (width, height) = main { ownedHost().window.decorView.let { it.width to it.height } }
      check(width in 4..4096 && height in 4..4096 && width.toLong() * height <= 8_388_608)
      val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      val copied = CountDownLatch(1)
      val bitmapLock = Any()
      var abandoned = false
      var status = -1
      var copiedAt = 0L
      var requested = false
      try {
        main {
          synchronized(bitmapLock) {
            check(!abandoned)
            requested = true
            try {
              PixelCopy.request(ownedHost().window, bitmap, { code ->
                synchronized(bitmapLock) {
                  status = code
                  copiedAt = SystemClock.elapsedRealtime()
                  if (abandoned && !bitmap.isRecycled) bitmap.recycle()
                  copied.countDown()
                }
              }, handler)
            } catch (failure: Throwable) {
              requested = false
              throw failure
            }
          }
        }
        check(copied.await(remaining(), TimeUnit.MILLISECONDS) && status == PixelCopy.SUCCESS)
        val digest = MessageDigest.getInstance("SHA-256")
        // Fixed central region: native hashes only; parent checks colored-marker geometry.
        val left = width / 4
        val top = height / 4
        val regionWidth = width / 2
        val regionHeight = height / 2
        val pixels = IntArray(regionWidth)
        val bytes = ByteBuffer.allocate(regionWidth * 4)
        var differing = 0
        val corner = bitmap.getPixel(left, top)
        for (y in top until top + regionHeight) {
          bitmap.getPixels(pixels, 0, regionWidth, left, y, regionWidth, 1)
          bytes.clear()
          pixels.forEach { if (it != corner) differing++; bytes.putInt(it) }
          digest.update(bytes.array())
        }
        expect("${phase}_native_region_nonblank", differing > 100)
        val file = File(probe.targetContext.cacheDir, "$prefix-$phase.png")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        check(file.length() > 0)
        return JSONObject().put("phase", phase).put("relativePath", "cache/${file.name}")
            .put("width", width).put("height", height).put("copiedElapsedMs", copiedAt - began)
            .put("region", JSONArray(listOf(left, top, regionWidth, regionHeight)))
            .put("regionSha256", digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
            .also { artifacts.put(it) }
      } finally {
        synchronized(bitmapLock) {
          abandoned = true
          // A timed-out PixelCopy still owns its bitmap until its callback completes.
          if ((!requested || copied.count == 0L) && !bitmap.isRecycled) bitmap.recycle()
        }
      }
    }

    fun run(): Bundle {
      try {
        demand("worker_off_main", Looper.myLooper() != Looper.getMainLooper())
        photoUrl = options.getString("facePhotoUrl").orEmpty()
        demand("generated_local_fixture_url", photoUrl.startsWith("file:///") &&
            File(photoUrl.removePrefix("file://")).isFile)
        val cache = FaceFocusCache(File(probe.targetContext.filesDir, "face_focus.json"))
        demand("fixture_cache_entry_fresh", cache.entry(photoUrl) == null)
        demand("point_only_seed_accepted", cache.record(photoUrl, POINT))
        phase = "owned_preview_launch"
        main { application.registerActivityLifecycleCallbacks(lifecycle); registered = true }
        main {
          probe.targetContext.startActivity(Intent().setClassName(probe.targetContext, HOST)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("owned_probe", token))
        }
        demand("new_uuid_activity_created", created.await(remaining(8_000), TimeUnit.MILLISECONDS))
        initialActivity = activity.get()
        phase = "initial_scene"
        val readyUntil = minOf(workDeadline, SystemClock.elapsedRealtime() + 12_000)
        var first: JSONObject? = null
        while (SystemClock.elapsedRealtime() < readyUntil) {
          webView = main { webViews(ownedHost().window.decorView).singleOrNull() }
          if (webView != null) {
            val state = scene(POINT)
            if (state.optBoolean("ready")) { first = state; break }
          }
          waitUntil(SystemClock.elapsedRealtime() + 80)
        }
        val initial = checkNotNull(first)
        demand("initial_generated_point_only_scene", initial.optBoolean("ownedDeck") &&
            initial.optBoolean("pointMatches") && !initial.optBoolean("hasRegion") &&
            initial.optBoolean("bridgeCacheMatches"))
        scenes.put("initial", initial)
        val firstPixels = capture("initial")
        val firstAt = began + firstPixels.getLong("copiedElapsedMs")
        phase = "late_cache_record"
        // Upgrade only after the first actual Window pixels have been captured, in this process.
        demand("late_region_seed_accepted", cache.record(photoUrl, REGION))
        cache.persist()
        val updateAt = SystemClock.elapsedRealtime()
        timings.put("cacheUpdateAfterInitialPixelsMs", updateAt - firstAt)
        demand("cache_update_after_initial_pixels", updateAt >= firstAt && updateAt - firstAt < 2_000)
        phase = "mid_hold"
        waitUntil(updateAt + 250)
        val held = scene(REGION)
        scenes.put("midHold", held)
        val heldPixels = capture("mid-hold")
        expect("mid_hold_before_next_five_second_presentation",
            heldPixels.getLong("copiedElapsedMs") - firstPixels.getLong("copiedElapsedMs") in 250..3_000)
        expect("mid_hold_fixture_source_unchanged", held.optBoolean("ownedDeck") &&
            held.getLong("imageToken") == initial.getLong("imageToken"))
        expect("mid_hold_displayed_style_unchanged", held.optBoolean("ready") &&
            held.getLong("styleToken") == initial.getLong("styleToken"))
        expect("mid_hold_native_region_hash_unchanged",
            heldPixels.getString("regionSha256") == firstPixels.getString("regionSha256"))
        expect("mid_hold_bridge_has_late_cache", held.optBoolean("bridgeCacheMatches"))
        phase = "next_presentation"
        waitUntil(firstAt + 7_000)
        val late = scene(REGION)
        scenes.put("nextPresentation", late)
        val latePixels = capture("next-presentation")
        expect("late_capture_after_next_five_second_presentation",
            latePixels.getLong("copiedElapsedMs") - firstPixels.getLong("copiedElapsedMs") >= 7_000)
        // ownedDeck verifies the original PHOTOS URL. A newly ready derivative may
        // replace the loading endpoint without changing that photo or WebView owner.
        expect("late_fixture_source_unchanged", late.optBoolean("ownedDeck"))
        expect("late_metadata_refreshed_same_owner", late.optBoolean("ready") &&
            late.optBoolean("bridgeCacheMatches") && late.optBoolean("regionMatches"))
        expect("late_presentation_style_changed", late.getLong("styleToken") != initial.getLong("styleToken"))
        timings.put("lateAfterInitialPixelsMs",
            latePixels.getLong("copiedElapsedMs") - firstPixels.getLong("copiedElapsedMs"))
        completed = true
      } catch (_: Throwable) {
        expect("isolated_failure", false)
      } finally {
        closing = true
        if (registered) {
          try {
            main(cleanup = true) {
              drawRemovals.toList().forEach { it() }
              drawRemovals.clear()
              owned.filterNot { it.isDestroyed || it.isFinishing }.forEach { it.finish() }
            }
            expect("draw_listeners_removed", main(cleanup = true) { drawRemovals.isEmpty() })
            val until = minOf(deadline - 500, SystemClock.elapsedRealtime() + 1_500)
            while (SystemClock.elapsedRealtime() < until &&
                !main(cleanup = true) { owned.all { it.isDestroyed } }) SystemClock.sleep(40)
            expect("owned_activity_destroyed", main(cleanup = true) {
              owned.isNotEmpty() && owned.all { it.isDestroyed }
            })
          } catch (_: Throwable) { expect("owned_activity_cleanup", false) }
          finally {
            try {
              main(cleanup = true) { application.unregisterActivityLifecycleCallbacks(lifecycle) }
              expect("lifecycle_listener_unregistered", true)
            }
            catch (_: Throwable) { expect("lifecycle_listener_cleanup", false) }
          }
        }
      }
      expect("inner_under_55_seconds", SystemClock.elapsedRealtime() - began < 55_000)
      val passed = completed && failures == 0 && artifacts.length() == 3
      val relative = "cache/${reportFile.name}"
      val report = JSONObject().put("checks", checks).put("artifacts", artifacts).put("scenes", scenes)
          .put("timings", timings).put("completed", completed).put("passed", passed)
          .put("failureCount", failures).put("checkCount", checks.length())
          .put("renderCount", artifacts.length())
          .put("lastPhase", phase)
          .put("sameActivity", completed).put("sameWebView", completed)
          .put("generatedOnly", true).put("syntheticCachedResults", true)
          .put("pixelGeometryVerified", false).put("mlRecallVerified", false)
          .put("elapsedMs", SystemClock.elapsedRealtime() - began).put("relativeReportPath", relative)
      var written = false
      try { reportFile.writeText(report.toString(2)); written = reportFile.length() > 0 }
      catch (_: Throwable) { /* Return only generated fixture assertion labels and paths. */ }
      return Bundle().apply {
        putBoolean("liveFaceMetadataCompleted", completed && written)
        putBoolean("liveFaceMetadataPassed", passed && written)
        putBoolean("liveFaceMetadataNativePassed", passed && written)
        putBoolean("liveFaceMetadataSameActivity", completed)
        putBoolean("liveFaceMetadataSameWebView", completed)
        putBoolean("liveFaceMetadataPixelGeometryVerified", false)
        putBoolean("liveFaceMetadataMlRecallVerified", false)
        putInt("liveFaceMetadataRenderCount", artifacts.length())
        putInt("liveFaceMetadataCheckCount", checks.length())
        putInt("liveFaceMetadataFailureCount", failures)
        putString("liveFaceMetadataReportRelativePath", relative)
        putString("liveFaceMetadataArtifactsJson", artifacts.toString())
        putString("liveFaceMetadataChecksJson", checks.toString())
      }
    }

    private fun webViews(view: View): List<WebView> {
      if (view is WebView) return listOf(view)
      if (view !is ViewGroup) return emptyList()
      return (0 until view.childCount).flatMap { webViews(view.getChildAt(it)) }
    }
  }
}
