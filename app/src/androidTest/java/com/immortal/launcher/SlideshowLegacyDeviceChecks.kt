/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Instrumentation
import android.os.SystemClock
import android.webkit.WebView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Optional real-WebView bridge smoke. Returns only counters/booleans for an owned local deck. */
internal object SlideshowLegacyDeviceChecks {
  fun run(instrumentation: Instrumentation, webView: WebView, mode: String,
      directory: String, demand: (String, Boolean) -> Unit): JSONObject {
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
          return a>.01 && r.width>0 && r.height>0;
        }
        var live=nodes.filter(visible), node=live[live.length-1];
        function hash(s) { var h=2166136261; for(var i=0;i<s.length;i++)
          h=Math.imul(h^s.charCodeAt(i),16777619); return h>>>0; }
        var photos=window.PHOTOS||[], background=node?getComputedStyle(node).backgroundImage:'none';
        return {
          bridge:!!window.immortalSlideshow,
          deckSize:photos.length,
          ownedLibrary:photos.length===2&&photos.every(function(p){return p.url.indexOf($prefix)===0;}),
          beats:beats.length,
          liveImages:live.length,
          nonBlank:live.length>0&&background!=='none',
          paused:beats.length>0&&beats.every(function(b){return b.classList.contains('paused');}),
          imageToken:hash(background),
          transformToken:hash(node?getComputedStyle(node).transform:'none')
        };
      })()
    """.trimIndent()
    fun evaluate(code: String): String {
      val done = CountDownLatch(1)
      var result = "null"
      instrumentation.runOnMainSync {
        webView.evaluateJavascript(code) { result = it; done.countDown() }
      }
      demand("${mode}_javascript_callback", done.await(4, TimeUnit.SECONDS))
      return result
    }
    fun state(): JSONObject = JSONObject(evaluate(script))
    fun waitFor(label: String, condition: (JSONObject) -> Boolean): JSONObject {
      val stop = SystemClock.elapsedRealtime() + 15_000
      while (SystemClock.elapsedRealtime() < stop) {
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
    val first = waitFor("owned_scene_ready") {
      it.optBoolean("bridge") && it.optBoolean("ownedLibrary") && it.optBoolean("nonBlank")
    }
    SystemClock.sleep(1400) // Let the initial scene admission/fade settle before pause.
    command("pause")
    val frozen = waitFor("pause_reaches_actual_scene") { it.optBoolean("paused") && it.optBoolean("nonBlank") }
    SystemClock.sleep(350)
    val held = state()
    demand("${mode}_pause_keeps_static_visible_scene", held.optBoolean("nonBlank") &&
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
    return JSONObject().put("deckSize", returned.getInt("deckSize")).put("bridgeSmoke", true)
        .put("liveImages", returned.getInt("liveImages")).put("beats", returned.getInt("beats"))
  }
}
