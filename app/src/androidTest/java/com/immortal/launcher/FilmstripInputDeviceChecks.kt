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
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.ViewTreeObserver
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/** Owned offline Window/WebView, shipped Filmstrip renderer, and genuinely decoded color PNGs. */
internal object FilmstripInputDeviceChecks {
  private const val HOST = "com.immortal.launcher.MusicDesignPreviewActivity"

  fun run(probe: Instrumentation): Bundle = Probe(probe).run()

  private class Probe(private val probe: Instrumentation) {
    private val began = SystemClock.elapsedRealtime()
    private val workDeadline = began + 48_000
    private val deadline = began + 55_000
    private val token = UUID.randomUUID().toString()
    private val prefix = "filmstrip-input-owned-$token"
    private val folder = File(probe.targetContext.cacheDir, prefix)
    private val reportFile = File(probe.targetContext.cacheDir, "$prefix.json")
    private val handler = Handler(Looper.getMainLooper())
    private val application = probe.targetContext.applicationContext as Application
    private val created = CountDownLatch(1)
    private val activity = AtomicReference<Activity>()
    private val owned = mutableListOf<Activity>() // Main only.
    private val checks = JSONArray()
    private val artifacts = JSONArray()
    private val photos = JSONArray()
    private var web: WebView? = null
    private var registered = false
    private var failures = 0
    private var initialDecodedCount = 0
    private var completed = false
    private var phase = "fixture"
    @Volatile private var closing = false

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

    private fun remaining(limit: Long = 3_000, cleanup: Boolean = false): Long =
        minOf(limit, (if (cleanup) deadline else workDeadline) - SystemClock.elapsedRealtime())
            .coerceAtLeast(0)

    private fun expect(name: String, passed: Boolean) {
      checks.put(JSONObject().put("name", name).put("passed", passed))
      if (!passed) failures++
    }

    private fun demand(name: String, passed: Boolean) {
      expect(name, passed)
      check(passed) { name }
    }

    /** Bounded Main dispatch, without UiAutomation, runOnMainSync, or overall-idle waits. */
    private fun <T> main(cleanup: Boolean = false, block: () -> T): T {
      val done = CountDownLatch(1)
      val expired = AtomicBoolean()
      var value: T? = null
      var error: Throwable? = null
      val task = Runnable {
        try { if (!expired.get()) value = block() }
        catch (failure: Throwable) { error = failure }
        finally { done.countDown() }
      }
      check(handler.post(task))
      try { check(done.await(remaining(cleanup = cleanup), TimeUnit.MILLISECONDS)) }
      finally { expired.set(true); handler.removeCallbacks(task) }
      error?.let { throw it }
      @Suppress("UNCHECKED_CAST")
      return value as T
    }

    private fun host(): Activity = checkNotNull(activity.get()).also {
      check(owned.size == 1 && owned.single() === it &&
          it.intent.getStringExtra("owned_probe") == token && !it.isDestroyed && !it.isFinishing)
    }

    private fun js(script: String): JSONObject {
      val done = CountDownLatch(1)
      var raw = "null"
      main {
        host()
        checkNotNull(web).evaluateJavascript(script) { raw = it; done.countDown() }
      }
      check(done.await(remaining(), TimeUnit.MILLISECONDS))
      return JSONObject(raw)
    }

    private fun state(): JSONObject = js("window.filmstripInput ? filmstripInput.state() : ({})")

    private fun awaitState(label: String, predicate: (JSONObject) -> Boolean): JSONObject {
      val until = minOf(workDeadline, SystemClock.elapsedRealtime() + 8_000)
      while (SystemClock.elapsedRealtime() < until) {
        val value = state()
        if (predicate(value)) { demand(label, true); return value }
        SystemClock.sleep(40)
      }
      demand(label, false)
      error(label)
    }

    private fun frame() {
      val drawn = CountDownLatch(1)
      val decor = main { host().window.decorView }
      var count = 0
      val listener = ViewTreeObserver.OnDrawListener {
        if (++count >= 2) drawn.countDown() else decor.postInvalidateOnAnimation()
      }
      try {
        main { decor.viewTreeObserver.addOnDrawListener(listener); decor.postInvalidateOnAnimation() }
        check(drawn.await(remaining(), TimeUnit.MILLISECONDS))
      } finally {
        main(cleanup = true) {
          if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnDrawListener(listener)
        }
      }
    }

    private fun capture(label: String) {
      frame()
      val (width, height) = main { host().window.decorView.let { it.width to it.height } }
      check(width in 4..4096 && height in 4..4096 && width.toLong() * height <= 8_388_608)
      val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      val done = CountDownLatch(1)
      val lock = Any()
      var abandoned = false
      var requested = false
      var status = -1
      try {
        main {
          synchronized(lock) {
            check(!abandoned)
            requested = true
            try {
              PixelCopy.request(host().window, bitmap, { code ->
                synchronized(lock) {
                  status = code
                  if (abandoned && !bitmap.isRecycled) bitmap.recycle()
                  done.countDown()
                }
              }, handler)
            } catch (error: Throwable) { requested = false; throw error }
          }
        }
        check(done.await(remaining(), TimeUnit.MILLISECONDS) && status == PixelCopy.SUCCESS)
        // The center card is the actual red PNG, independently of JS center/Promise assertions.
        var red = 0
        for (y in height / 2 - 4..height / 2 + 4)
          for (x in width / 2 - 4..width / 2 + 4) {
            val color = bitmap.getPixel(x, y)
            if (Color.red(color) > 200 && Color.green(color) < 45 && Color.blue(color) < 45) red++
          }
        demand("${label}_native_center_red", red >= 75)
        val file = File(probe.targetContext.cacheDir, "$prefix-$label.png")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        artifacts.put(JSONObject().put("phase", label).put("relativePath", "cache/${file.name}")
            .put("width", width).put("height", height).put("redSamples", red))
      } finally {
        synchronized(lock) {
          abandoned = true
          if ((!requested || done.count == 0L) && !bitmap.isRecycled) bitmap.recycle()
        }
      }
    }

    private fun page(): String = """
      <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
      <link rel="stylesheet" href="styles.css"></head><body>
      <div id="stage"><div id="owned" class="ss-beat on first-scene"></div></div>
      <script>window.PHOTOS=$photos;window.aspect=function(p){return p.w/p.h;};
      window.photoUrl=function(p){return p.url;};</script>
      <script src="lib-engine.js"></script><script src="modes.js"></script>
      <script src="mode-impls.js"></script><script>
      (function(){
        var root=document.getElementById('owned'), decoder=window.preloadDecoded;
        var ready=false, decoded=0, gate=null, arm=false, held=false, closed=false;
        var firstDone=false, previousDone=false, firstOK=null, previousOK=null, observed=[];
        var lateFirst=null, latePrevious=null, mutationCount=0, releasedFrames=false;
        var observer=new MutationObserver(function(){mutationCount++;});
        observer.observe(root,{subtree:true,attributes:true,childList:true});
        // Call the shipped Image.onload + Image.decode implementation first. Hold only
        // its successful result, so this gate never substitutes a mocked image decode.
        window.preloadDecoded=function(url,signal){
          var hold=arm; if(hold) arm=false;
          return decoder(url,signal).then(function(value){
            decoded++;
            if(!hold) return value;
            held=true;
            return new Promise(function(resolve){gate=function(){held=false;gate=null;resolve(value);};});
          });
        };
        var cleanup=SS.MODES.filmstrip.run({root:root,deck:PHOTOS,cursor:{i:0},
          W:innerWidth,H:innerHeight,url:photoUrl,nativeToken:'owned-input',
          photoMs:600000,microMs:600000},PHOTOS);
        function center(){return Array.prototype.indexOf.call(root.querySelectorAll('.ss-slide'),
          root.querySelector('.ss-slide.center'));}
        cleanup.ready.then(function(ok){ready=ok===true;});
        window.filmstripInput={
          state:function(){return {ready:ready,center:center(),decoded:decoded,held:held,
            firstDone:firstDone,previousDone:previousDone,firstOK:firstOK,previousOK:previousOK,
            observed:observed.slice(),closed:closed,lateFirst:lateFirst,latePrevious:latePrevious,
            mutationCount:mutationCount,releasedFrames:releasedFrames,
            noNativeBridge:typeof window.Android==='undefined',
            documentToken:${JSONObject.quote(token)}};},
          begin:function(){
            arm=true;
            cleanup.next().then(function(ok){firstOK=ok;firstDone=true;observed.push(center());});
            cleanup.previous().then(function(ok){previousOK=ok;previousDone=true;observed.push(center());});
            return this.state();
          },
          release:function(){
            if(gate)gate();
            requestAnimationFrame(function(){requestAnimationFrame(function(){releasedFrames=true;});});
            return this.state();
          },
          closeBusy:function(){
            arm=true;lateFirst=null;latePrevious=null;releasedFrames=false;
            cleanup.next().then(function(ok){lateFirst=ok;});
            cleanup.previous().then(function(ok){latePrevious=ok;});
            return this.state();
          },
          dispose:function(){cleanup();closed=true;return this.state();}
        };
        window.addEventListener('pagehide',function(){cleanup();});
      })();
      </script></body></html>
    """.trimIndent()

    fun run(): Bundle {
      try {
        demand("worker_off_main", Looper.myLooper() != Looper.getMainLooper())
        demand("api29_window", Build.VERSION.SDK_INT == 29)
        check(folder.mkdir())
        listOf(Color.RED, Color.GREEN, Color.BLUE).forEachIndexed { index, color ->
          check(remaining() > 0)
          val file = File(folder, "$index.png")
          val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
          try {
            bitmap.eraseColor(color)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
          } finally { bitmap.recycle() }
          photos.put(JSONObject().put("id", index).put("seed", "$token-$index")
              .put("w", 400).put("h", 300).put("url", android.net.Uri.fromFile(file).toString()))
        }
        phase = "owned_window"
        main { application.registerActivityLifecycleCallbacks(lifecycle); registered = true }
        main {
          // This existing debug host creates only a quiet generated clock, no player or sources.
          probe.targetContext.startActivity(Intent().setClassName(probe.targetContext, HOST)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("screen", "night")
              .putExtra("owned_probe", token))
        }
        demand("uuid_activity_created", created.await(remaining(8_000), TimeUnit.MILLISECONDS))
        main {
          val owner = host()
          val browser = WebView(owner)
          web = browser
          browser.settings.apply {
            javaScriptEnabled = true
            blockNetworkLoads = true
            allowFileAccess = true
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
          }
          browser.webViewClient = WebViewClient()
          owner.setContentView(browser)
          browser.loadDataWithBaseURL("file:///android_asset/screensaver/", page(), "text/html", "UTF-8", null)
        }
        phase = "initial_decode"
        val initial = awaitState("shipped_renderer_ready") { it.optBoolean("ready") }
        initialDecodedCount = initial.getInt("decoded")
        // The shipped viewport bound may leave offscreen cards undecoded.
        demand("actual_initial_decodes", initialDecodedCount >= 1)
        demand("initial_center_zero", initial.getInt("center") == 0)
        demand("no_native_bridge", initial.getBoolean("noNativeBridge"))
        val owner = activity.get()
        val browser = web
        // Initial go(0) also animates the shipped track from its CSS starting position.
        check(remaining(2_000) >= 1_600)
        SystemClock.sleep(1_600)
        capture("before")
        phase = "queued_input"
        js("filmstripInput.begin()")
        val held = awaitState("actual_navigation_decode_held") { it.optBoolean("held") }
        demand("previous_pending_until_release", !held.getBoolean("previousDone"))
        demand("next_pending_until_release", !held.getBoolean("firstDone"))
        demand("no_center_publication_while_held", held.getInt("center") == 0)
        js("filmstripInput.release()")
        val final = awaitState("both_gestures_completed") {
          it.optBoolean("firstDone") && it.optBoolean("previousDone")
        }
        demand("both_gestures_accepted", final.getBoolean("firstOK") && final.getBoolean("previousOK"))
        demand("queued_previous_returns_initial", final.getInt("center") == 0)
        demand("next_then_previous_order", final.getJSONArray("observed").toString() == "[1,0]")
        demand("same_activity_webview_document", main {
          activity.get() === owner && web === browser && browser?.isAttachedToWindow == true
        } && final.getString("documentToken") == token)
        // Allow shipped 1.4s card/track transitions to finish before independent native pixels.
        check(remaining(2_000) >= 1_600)
        SystemClock.sleep(1_600)
        capture("after")
        phase = "close_queue"
        js("filmstripInput.closeBusy()")
        awaitState("close_decode_held") { it.optBoolean("held") }
        val closed = js("filmstripInput.dispose()")
        js("filmstripInput.release()")
        val late = awaitState("closed_queue_settled") {
          it.opt("lateFirst") == false && it.opt("latePrevious") == false &&
              it.optBoolean("releasedFrames")
        }
        demand("closed_queue_no_late_center_write", late.getInt("center") == closed.getInt("center"))
        demand("closed_queue_no_late_dom_write", late.getInt("mutationCount") == closed.getInt("mutationCount"))
        completed = true
      } catch (_: Throwable) { expect("isolated_failure", false) }
      finally {
        closing = true
        try {
          main(cleanup = true) {
            web?.let { browser ->
              (browser.parent as? android.view.ViewGroup)?.removeView(browser)
              browser.stopLoading()
              browser.destroy()
            }
            web = null
            owned.filterNot { it.isDestroyed || it.isFinishing }.forEach { it.finish() }
          }
          val until = minOf(deadline - 500, SystemClock.elapsedRealtime() + 1_500)
          while (SystemClock.elapsedRealtime() < until &&
              !main(cleanup = true) { owned.all { it.isDestroyed } }) SystemClock.sleep(40)
          expect("owned_activity_destroyed", main(cleanup = true) {
            owned.size == 1 && owned.all { it.isDestroyed }
          })
        } catch (_: Throwable) { expect("owned_window_cleanup", false) }
        finally {
          if (registered) try {
            main(cleanup = true) { application.unregisterActivityLifecycleCallbacks(lifecycle) }
          } catch (_: Throwable) { expect("lifecycle_cleanup", false) }
          var removed = true
          for (index in 0..2) {
            val file = File(folder, "$index.png")
            removed = (!file.exists() || file.delete()) && removed
          }
          removed = (!folder.exists() || folder.delete()) && removed
          expect("exact_generated_files_removed", removed)
        }
      }
      expect("inner_under_55_seconds", SystemClock.elapsedRealtime() - began < 55_000)
      val passed = completed && failures == 0 && artifacts.length() == 2
      val report = JSONObject().put("checks", checks).put("artifacts", artifacts)
          .put("completed", completed).put("passed", passed).put("failureCount", failures)
          .put("elapsedMs", SystemClock.elapsedRealtime() - began).put("lastPhase", phase)
          .put("initialDecodedCount", initialDecodedCount)
          .put("generatedOnly", true).put("shippedAssets", true)
      var written = false
      try { reportFile.writeText(report.toString(2)); written = reportFile.length() > 0 }
      catch (_: Throwable) { /* Only generated assertion labels and UUID-owned paths are returned. */ }
      return Bundle().apply {
        putBoolean("filmstripInputCompleted", completed && written)
        putBoolean("filmstripInputPassed", passed && written)
        putBoolean("filmstripInputNativePassed", passed && written)
        putInt("filmstripInputFailureCount", failures)
        putString("filmstripInputReportRelativePath", "cache/${reportFile.name}")
        putString("filmstripInputArtifactsJson", artifacts.toString())
        putString("filmstripInputChecksJson", checks.toString())
      }
    }
  }
}
