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

/** Owned offline Window/WebView, shipped renderers/scheduler, and genuinely decoded color PNGs. */
internal object FilmstripInputDeviceChecks {
  private const val HOST = "com.immortal.launcher.MusicDesignPreviewActivity"
  private const val HISTORY_REPORT_PREFIX = "slideshow-history-owned-"

  fun run(probe: Instrumentation): Bundle = Probe(probe).run()
  fun runHistory(probe: Instrumentation): Bundle = Probe(probe, history = true).run()

  private class Probe(private val probe: Instrumentation, private val history: Boolean = false) {
    private val began = SystemClock.elapsedRealtime()
    private val workDeadline = began + 48_000
    private val deadline = began + 55_000
    private val token = UUID.randomUUID().toString()
    private val prefix = if (history) "$HISTORY_REPORT_PREFIX$token" else "filmstrip-input-owned-$token"
    private val resultKey = if (history) "slideshowHistory" else "filmstripInput"
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
    private val observations = JSONArray()
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

    private fun state(): JSONObject = js(
        if (history) "window.slideshowHistory ? slideshowHistory.state() : ({})"
        else "window.filmstripInput ? filmstripInput.state() : ({})")

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
        // Native center pixels independently verify the generated red PNG at both endpoints.
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

    private fun historyPage(): String = """
      <!doctype html><html data-owned-probe="$token"><head><meta name="viewport" content="width=device-width,initial-scale=1">
      <link rel="stylesheet" href="styles.css"></head><body><div id="stage"></div>
      <script>window.PHOTOS=$photos;window.aspect=function(p){return p.w/p.h;};
      window.photoUrl=function(p){return p.url;};window.shuffled=function(deck){return deck.slice();};</script>
      <script src="lib-engine.js"></script><script src="modes.js"></script>
      <script src="mode-impls.js"></script><script src="scheduler.js"></script><script>
      (function(){
        var stage=document.getElementById('stage'), doc=document, decoder=window.preloadDecoded;
        var modeRun=SS.MODES.kenBurns.run, owners=[], decoded=0, commits=0, arm=false, gate=null;
        var held=false, heldIndex=-1, heldSignal=null, heldOwner=null, released=false;
        var releasedFrames=false, releasedSettled=false, minOwners=99, maxOwners=0;
        function sample(){
          var n=stage.querySelectorAll('.ss-beat').length;
          minOwners=Math.min(minOwners,n);maxOwners=Math.max(maxOwners,n);
        }
        function current(){
          // An incoming root is never evidence of publication. Outgoing roots retain .on,
          // so select the latest committed beat and its admitted internal layer.
          var beats=stage.querySelectorAll('.ss-beat.on');
          return beats.length ? beats[beats.length-1] : null;
        }
        function index(root){
          var hero=root && root.querySelector('.ss-layer.on .ss-hero');
          if(!hero)return -1;
          var source=hero.style.backgroundImage;
          for(var i=0;i<PHOTOS.length;i++){
            if(source==='url("'+PHOTOS[i].url+'")' || source==="url('"+PHOTOS[i].url+"')" ||
                source==='url('+PHOTOS[i].url+')')return i;
          }
          return -1;
        }
        SS.MODES.kenBurns.run=function(ctx,deck){
          var entry={root:ctx.root,disposed:false,mutations:0};
          owners.push(entry);sample();
          new MutationObserver(function(records){entry.mutations+=records.length;})
            .observe(entry.root,{subtree:true,attributes:true,childList:true});
          var cleanup=modeRun(ctx,deck);
          var wrapped=function(){entry.disposed=true;cleanup();};
          // Preserve the renderer's own ready getter and pause/resume contracts.
          ['ready','pause','resume','next','previous','nativeToken','restore'].forEach(function(key){
            var descriptor=Object.getOwnPropertyDescriptor(cleanup,key);
            if(descriptor)Object.defineProperty(wrapped,key,descriptor);
          });
          return wrapped;
        };
        window.preloadDecoded=function(url,signal){
          var hold=arm; if(hold)arm=false;
          var entry=owners[owners.length-1];
          // The shipped decoder receives the original abort signal and must succeed
          // before its result is gated. The late result intentionally survives abort.
          return decoder(url,signal).then(function(value){
            decoded++;
            if(!hold)return value;
            held=true;heldIndex=PHOTOS.findIndex(function(p){return p.url===url;});
            heldSignal=signal;heldOwner=entry;
            return new Promise(function(resolve){
              gate=function(){gate=null;held=false;released=true;resolve(value);};
            }).then(function(value){releasedSettled=true;return value;});
          });
        };
        var engine=SS.startEngine({stage:stage,mode:'kenBurns',intervalSec:600,
          photoMotion:false,onReady:function(){commits++;sample();}});
        new MutationObserver(sample).observe(stage,{childList:true});
        window.slideshowHistory={
          state:function(){
            sample();var root=current();
            return {ready:commits>0,visibleIndex:index(root),commits:commits,decoded:decoded,
              sceneOwners:stage.querySelectorAll('.ss-beat').length,minSceneOwners:minOwners,
              maxSceneOwners:maxOwners,currentOwner:owners.findIndex(function(o){return o.root===root;}),
              disposedOwners:owners.filter(function(o){return o.disposed;}).length,
              held:held,heldIndex:heldIndex,heldHasAbortSignal:!!heldSignal,
              heldAborted:!!heldSignal && heldSignal.aborted,
              oldRootDisposed:!!heldOwner && heldOwner.disposed && !heldOwner.root.isConnected,
              oldRootMutations:heldOwner ? heldOwner.mutations : 0,
              released:released,releasedSettled:releasedSettled,releasedFrames:releasedFrames,
              sameDocument:document===doc,
              documentOwned:document.documentElement.getAttribute('data-owned-probe')===${JSONObject.quote(token)},
              noNativeBridge:typeof window.Android==='undefined'};
          },
          next:function(){engine.next();return this.state();},
          pendingPrevious:function(){arm=true;engine.previous();return this.state();},
          previous:function(){engine.previous();return this.state();},
          release:function(){
            if(gate)gate();
            requestAnimationFrame(function(){requestAnimationFrame(function(){releasedFrames=true;});});
            return this.state();
          }
        };
        window.addEventListener('pagehide',function(){engine.stop();});
      })();
      </script></body></html>
    """.trimIndent()

    private fun retireHistoryFade(label: String): JSONObject {
      // Scheduler retires the outgoing owner after its shipped 1600ms fade + 100ms.
      check(remaining(2_000) >= 1_750)
      SystemClock.sleep(1_750)
      return awaitState(label) { it.optInt("sceneOwners") == 1 }
    }

    private fun observeHistory(label: String, value: JSONObject) {
      observations.put(JSONObject().put("phase", label).put("state", value))
      demand("${label}_bounded_scene_owners", value.getInt("minSceneOwners") in 1..2 &&
          value.getInt("maxSceneOwners") in 1..2)
    }

    private fun historyScenario() {
      phase = "history_initial_A"
      val initial = awaitState("history_initial_A_red") {
        it.optBoolean("ready") && it.optInt("visibleIndex", -1) == 0 && it.optInt("commits") == 1
      }
      initialDecodedCount = initial.getInt("decoded")
      demand("history_actual_initial_decode", initialDecodedCount >= 1)
      demand("no_native_bridge", initial.getBoolean("noNativeBridge"))
      val owner = activity.get()
      val browser = web
      observeHistory("initial_A", retireHistoryFade("initial_A_stable"))
      capture("before")

      phase = "history_next_B"
      js("slideshowHistory.next()")
      val next = awaitState("history_next_B_green") {
        it.optInt("visibleIndex", -1) == 1 && it.optInt("commits") == 2
      }
      demand("history_next_new_owner", next.getInt("currentOwner") != initial.getInt("currentOwner"))
      observeHistory("next_B", retireHistoryFade("next_B_outgoing_retired"))

      phase = "history_previous_A_held"
      js("slideshowHistory.pendingPrevious()")
      val held = awaitState("history_successful_previous_A_decode_held") { it.optBoolean("held") }
      demand("history_held_source_A", held.getInt("heldIndex") == 0)
      demand("history_successful_held_decode_count", held.getInt("decoded") > next.getInt("decoded"))
      demand("history_original_abort_signal", held.getBoolean("heldHasAbortSignal") &&
          !held.getBoolean("heldAborted"))
      demand("history_held_A_cannot_publish", held.getInt("visibleIndex") == 1 &&
          held.getInt("commits") == 2)
      observeHistory("previous_A_held", held)

      phase = "history_next_cancels_A"
      js("slideshowHistory.next()")
      val canceled = awaitState("history_next_returns_B") {
        it.optInt("visibleIndex", -1) == 1 && it.optInt("commits") == 3 &&
            it.optBoolean("oldRootDisposed") && it.optBoolean("heldAborted")
      }
      demand("history_cancel_new_B_owner", canceled.getInt("currentOwner") != next.getInt("currentOwner"))
      val retired = retireHistoryFade("cancel_B_outgoing_retired")
      observeHistory("cancel_returns_B", retired)

      phase = "history_release_obsolete_decode"
      js("slideshowHistory.release()")
      val late = awaitState("history_obsolete_decode_released") {
        it.optBoolean("releasedSettled") && it.optBoolean("releasedFrames")
      }
      demand("history_late_decode_cannot_promote", late.getInt("visibleIndex") == 1 &&
          late.getInt("commits") == 3 && late.getInt("currentOwner") == retired.getInt("currentOwner"))
      demand("history_old_root_disposed", late.getBoolean("oldRootDisposed") && late.getBoolean("heldAborted"))
      demand("history_disposed_root_no_late_write",
          late.getInt("oldRootMutations") == retired.getInt("oldRootMutations"))
      observeHistory("late_decode", late)

      phase = "history_previous_A"
      js("slideshowHistory.previous()")
      val final = awaitState("historyPreviousA") {
        it.optInt("visibleIndex", -1) == 0 && it.optInt("commits") == 4
      }
      observeHistory("final_A", retireHistoryFade("final_A_outgoing_retired"))
      demand("same_activity_webview_document", main {
        activity.get() === owner && web === browser && browser?.isAttachedToWindow == true
      } && final.getBoolean("sameDocument") && final.getBoolean("documentOwned"))
      capture("after")
    }

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
          browser.loadDataWithBaseURL("file:///android_asset/screensaver/",
              if (history) historyPage() else page(), "text/html", "UTF-8", null)
        }
        if (history) {
          historyScenario()
        } else {
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
        }
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
          .put("observations", observations)
          .put("generatedOnly", true).put("shippedAssets", true)
      var written = false
      try { reportFile.writeText(report.toString(2)); written = reportFile.length() > 0 }
      catch (_: Throwable) { /* Only generated assertion labels and UUID-owned paths are returned. */ }
      return Bundle().apply {
        putBoolean("${resultKey}Completed", completed && written)
        putBoolean("${resultKey}Passed", passed && written)
        putBoolean("${resultKey}NativePassed", passed && written)
        putInt("${resultKey}FailureCount", failures)
        putString("${resultKey}ReportRelativePath", "cache/${reportFile.name}")
        putString("${resultKey}ArtifactsJson", artifacts.toString())
        putString("${resultKey}ChecksJson", checks.toString())
        if (history) putString("slideshowHistoryObservationsJson", observations.toString())
      }
    }
  }
}
