/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.annotation.SuppressLint
import android.app.Instrumentation
import android.os.SystemClock
import android.webkit.WebView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Optional real-WebView bridge smoke. Returns only counters/booleans for an owned local deck. */
internal object SlideshowLegacyDeviceChecks {
  fun run(instrumentation: Instrumentation, webView: WebView, mode: String,
      directory: String, demand: (String, Boolean) -> Unit, report: JSONObject = JSONObject(),
      started: Long = SystemClock.elapsedRealtime(), bounded: () -> Unit = {}): JSONObject {
    require(mode == "kenBurns" || mode == "filmstrip")
    val prefix = JSONObject.quote("file://$directory/")
    val script = """
      (function() {
        var beats = Array.from(document.querySelectorAll('#stage .ss-beat'));
        var nodes = Array.from(document.querySelectorAll(
          '#stage .ss-layer.on .ss-hero, #stage .ss-slide.center .ss-img'));
        function visible(n) {
          var r = n.getBoundingClientRect(), a = 1;
          for (var e=n; e; e=e.parentElement) {
            var s=getComputedStyle(e);
            if(s.display==='none'||s.visibility==='hidden') return false;
            a*=Number(s.opacity);
          }
          return a>.01 && r.width>0 && r.height>0 &&
            r.right>0 && r.bottom>0 && r.left<innerWidth && r.top<innerHeight;
        }
        var live=nodes.filter(visible), node=live[live.length-1];
        function hash(s) { var h=2166136261; for(var i=0;i<s.length;i++)
          h=Math.imul(h^s.charCodeAt(i),16777619); return h>>>0; }
        var photos=window.PHOTOS||[], background=node?getComputedStyle(node).backgroundImage:'none';
        var loading=document.getElementById('loading');
        return {
          bridge:!!window.immortalSlideshow,
          deckSize:photos.length,
          ownedLibrary:photos.length===2&&photos.every(function(p){return p.url.indexOf($prefix)===0;}),
          beats:beats.length,
          liveImages:live.length,
          nonBlank:live.length>0&&background!=='none',
          loadingDismissed:!!loading&&!visible(loading),
          paused:beats.length>0&&beats.every(function(b){return b.classList.contains('paused');}),
          imageToken:hash(background),
          transformToken:hash(node?getComputedStyle(node).transform:'none')
        };
      })()
    """.trimIndent()
    fun evaluate(code: String): String = SlideshowLegacyDeviceChecks.evaluate(instrumentation, webView, code,
        "${mode}_javascript_callback", demand, bounded)
    fun state(): JSONObject = JSONObject(evaluate(script))
    fun waitFor(label: String, condition: (JSONObject) -> Boolean): JSONObject {
      val stop = SystemClock.elapsedRealtime() + 10_000
      while (SystemClock.elapsedRealtime() < stop) {
        bounded()
        if (Thread.currentThread().isInterrupted) { demand("legacy_probe_interrupted", false) }
        val current = state()
        if (condition(current)) { demand("${mode}_$label", true); return current }
        SystemClock.sleep(100)
      }
      demand("${mode}_$label", false)
      error("Unreachable")
    }
    fun command(name: String) {
      val quoted = JSONObject.quote(name)
      demand("${mode}_bridge_$name", evaluate(
          "(function(){var a=window.immortalSlideshow;if(!a||typeof a[$quoted]!=='function')" +
              "return false;return a[$quoted]()!==false;})()") == "true")
    }
    val first = try {
      waitFor("owned_scene_ready") {
        it.optBoolean("bridge") && it.optBoolean("ownedLibrary") &&
            it.optBoolean("nonBlank") && it.optBoolean("loadingDismissed")
      }.also { report.put("ready", true) }
    } finally {
      report.put("readinessLatencyMs", SystemClock.elapsedRealtime() - started)
    }
    demand("${mode}_first_photo_visible", first.optBoolean("nonBlank"))
    demand("${mode}_loading_dismissed", first.optBoolean("loadingDismissed"))
    SystemClock.sleep(1400) // Let the initial scene admission/fade settle before pause.
    command("pause")
    val frozen = waitFor("pause_reaches_actual_scene") { it.optBoolean("paused") && it.optBoolean("nonBlank") }
    SystemClock.sleep(350)
    val held = state()
    demand("${mode}_pause_keeps_static_visible_scene", held.optBoolean("nonBlank") &&
        held.optBoolean("paused") && held.optBoolean("loadingDismissed") &&
        frozen.getLong("imageToken") == held.getLong("imageToken") &&
        frozen.getLong("transformToken") == held.getLong("transformToken"))
    command("resume")
    waitFor("resume_reaches_actual_scene") { !it.optBoolean("paused") && it.optBoolean("nonBlank") }
    command("next")
    waitFor("next_advances_same_deck") {
      it.optBoolean("nonBlank") && it.getLong("imageToken") != first.getLong("imageToken")
    }
    SystemClock.sleep(1400)
    command("previous")
    val returned = waitFor("previous_returns_same_deck") {
      it.optBoolean("nonBlank") && it.getLong("imageToken") == first.getLong("imageToken")
    }
    demand("${mode}_bounded_live_composition", returned.getInt("beats") in 1..2)
    demand("${mode}_loading_stays_dismissed", returned.optBoolean("loadingDismissed"))
    return report.put("deckSize", returned.getInt("deckSize")).put("bridgeSmoke", true)
        .put("liveImages", returned.getInt("liveImages")).put("beats", returned.getInt("beats"))
  }

  /** A separate live Android WebView: the slideshow must never suppress its timer on exit. */
  @SuppressLint("SetJavaScriptEnabled")
  fun startHeartbeat(webView: WebView) {
    webView.settings.javaScriptEnabled = true
    webView.loadData("""<!doctype html><html><body><script>
      window.immortalLegacyHeartbeat = 0;
      setInterval(function() { window.immortalLegacyHeartbeat++; }, 100);
      </script></body></html>""", "text/html", "UTF-8")
  }

  fun heartbeat(instrumentation: Instrumentation, webView: WebView, label: String,
      demand: (String, Boolean) -> Unit, bounded: () -> Unit): JSONObject {
    fun ticks(): Long = evaluate(instrumentation, webView,
        "Number(window.immortalLegacyHeartbeat || 0)", "${label}_javascript_callback",
        demand, bounded).toLong()
    // Sample after stop/destroy has completed; a tick racing with exit cannot count as recovery.
    val first = ticks()
    val begin = SystemClock.elapsedRealtime()
    val stop = begin + 3000
    while (SystemClock.elapsedRealtime() < stop) {
      bounded()
      val current = ticks()
      if (current > first) {
        demand(label, true)
        return JSONObject().put("ticks", current - first)
            .put("elapsedMs", SystemClock.elapsedRealtime() - begin)
      }
      SystemClock.sleep(100)
    }
    demand(label, false)
    error("Unreachable")
  }

  private fun evaluate(instrumentation: Instrumentation, webView: WebView, code: String,
      label: String, demand: (String, Boolean) -> Unit, bounded: () -> Unit): String {
    bounded()
    if (Thread.currentThread().isInterrupted) demand("legacy_probe_interrupted", false)
    val done = CountDownLatch(1)
    var result = "null"
    instrumentation.runOnMainSync {
      webView.evaluateJavascript(code) { result = it; done.countDown() }
    }
    demand(label, done.await(4, TimeUnit.SECONDS))
    bounded()
    return result
  }
}
