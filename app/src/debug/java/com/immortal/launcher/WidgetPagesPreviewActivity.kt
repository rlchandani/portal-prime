/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.immortal.launcher.ui.components.OnResume
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only generated retained subtree. Uses production scope/producers, no photo/config reads. */
class WidgetPagesPreviewActivity : ComponentActivity() {
  private class HostOwner : LifecycleOwner {
    val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
  }

  private class Page {
    var created = 0
    var disposed = 0
    var running = 0
    var producerStarts = 0
    var producerStops = 0
    var delivered = 0
    var photoRunning = 0
    var photoTicks = 0
    var resumes = 0
    var owner: LifecycleOwner? = null
    var ownerState = Lifecycle.State.INITIALIZED
    var gate: LauncherPageInputGate? = null
    var active = false
    var widgetInput = false
    var cached = "empty"
    var revision by mutableStateOf(0)
    var latency by mutableStateOf(35L)
    val requests = AtomicInteger()
    val inFlight = AtomicInteger()
    val peakInFlight = AtomicInteger()
  }

  private val host = HostOwner()
  private val parentGate = LauncherPageInputGate()
  private val baseline = List(3) { Page() }
  private val scoped = List(3) { Page() }
  private var legacy by mutableStateOf(true)
  private var selected by mutableStateOf(0)
  private var parentActive by mutableStateOf(true)
  private var mounted by mutableStateOf(true)
  private lateinit var directory: File
  private val checks = JSONArray()
  private val observations = JSONArray()
  private val report = JSONObject().put("complete", false).put("success", false)
      .put("api", Build.VERSION.SDK_INT)
      .put("scope", "Actual ApprovedWidgetPageScope + produceHomeState + OnResume")
      .put("baseline", "Replay of old provider wiring in this APK; not a physical old-APK comparison")
      .put("retention", "Three generated retained children, representing a retained neighbor subtree")
      .put("photoWorker", "Generated repeatOnLifecycle(RESUMED) worker; no ApprovedHomePhoto source reads")
      .put("physicalPortalPerformanceMeasured", false).put("fpsMeasured", false)
      .put("thirdPartyAppWidgetProviderUpdatesMeasured", false)
      .put("syntheticParentLifecycle", true)
      .put("checks", checks).put("observations", observations)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or
        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    directory = File(cacheDir, "widget-pages-native").apply { mkdirs() }
    host.registry.currentState = Lifecycle.State.RESUMED
    setContent {
      MaterialTheme {
        Column(Modifier.fillMaxSize().background(Color(0xff161b22)).padding(24.dp)) {
          Text(if (legacy) "Old widget wiring replay · three active producers"
            else "Production widget scope · selected page $selected", color = Color.White)
          CompositionLocalProvider(LocalLifecycleOwner provides host) {
            ApprovedLauncherPageLifecycle(parentActive, parentGate) {
              if (mounted) key(legacy) {
                val pages = if (legacy) baseline else scoped
                repeat(3) { index ->
                  key(index) {
                    if (legacy) {
                      CompositionLocalProvider(LocalApprovedWidgetPageInput provides (index == selected)) {
                        FixturePage(index, pages[index])
                      }
                    } else {
                      ApprovedWidgetPageScope(index == selected) { FixturePage(index, pages[index]) }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
    lifecycleScope.launch {
      try {
        runChecks()
        report.put("success", true)
      } catch (error: Throwable) {
        report.put("failure", error.toString())
      } finally {
        report.put("complete", true).put("passed", checks.length())
        publish()
      }
    }
  }

  @Composable
  private fun FixturePage(index: Int, page: Page) {
    val owner = LocalLifecycleOwner.current
    val active = LocalApprovedLauncherPageActive.current
    val widgetInput = LocalApprovedWidgetPageInput.current
    val gate = LocalApprovedLauncherInputGate.current
    val revision = page.revision
    val latency = page.latency
    val cached by produceHomeState("empty", revision, latency) {
      page.running++
      page.producerStarts++
      try {
        while (true) {
          val request = page.requests.incrementAndGet()
          withContext(Dispatchers.IO + NonCancellable) {
            val inFlight = page.inFlight.incrementAndGet()
            page.peakInFlight.updateAndGet { old -> maxOf(old, inFlight) }
            try { delay(latency) } finally { page.inFlight.decrementAndGet() }
          }
          value = "page=$index;key=$revision;request=$request"
          page.delivered++
          homeRefreshDelay(600)
        }
      } finally {
        page.running--
        page.producerStops++
      }
    }
    DisposableEffect(owner) {
      page.created++
      page.owner = owner
      val observer = LifecycleEventObserver { _, _ -> page.ownerState = owner.lifecycle.currentState }
      owner.lifecycle.addObserver(observer)
      page.ownerState = owner.lifecycle.currentState
      onDispose {
        owner.lifecycle.removeObserver(observer)
        page.disposed++
      }
    }
    OnResume { page.resumes++ }
    LaunchedEffect(owner) {
      owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        page.photoRunning++
        try {
          while (true) {
            page.photoTicks++
            delay(80)
          }
        } finally { page.photoRunning-- }
      }
    }
    SideEffect {
      page.active = active
      page.widgetInput = widgetInput
      page.gate = gate
      page.cached = cached
    }
    Column(Modifier.fillMaxWidth().padding(top = 28.dp)
        .background(if (active) Color(0xff315b73) else Color(0xff303841)).padding(20.dp)) {
      Text("Widget page $index · ${if (active) "active" else "paused"}", color = Color.White)
      Text(cached, color = Color.White)
      Text("Generated polling data and lifecycle photo worker", color = Color(0xffc5ced8))
    }
  }

  private fun publish() {
    val temporary = File(directory, "checks.tmp")
    temporary.writeText(report.toString(2) + "\n")
    check(temporary.renameTo(File(directory, "checks.json")))
  }

  private fun demand(label: String, condition: Boolean) {
    check(condition) { label }
    checks.put(label)
  }

  private suspend fun until(label: String, timeout: Long = 3_000, condition: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + timeout
    while (!condition()) {
      check(SystemClock.elapsedRealtime() < deadline) { "$label timed out" }
      delay(10)
    }
  }

  private fun snapshot(label: String) {
    observations.put(JSONObject().put("label", label).put("selected", selected)
        .put("parentActive", parentActive).put("hostLifecycle", host.lifecycle.currentState.name)
        .put("parentGateActive", parentGate.active).put("parentGateAccepts", parentGate.acceptsAction())
        .put("pages", JSONArray((if (legacy) baseline else scoped).map { page ->
          JSONObject().put("created", page.created).put("disposed", page.disposed)
              .put("active", page.active).put("widgetInput", page.widgetInput)
              .put("producerBodiesRunning", page.running)
              .put("producerStarts", page.producerStarts).put("producerStops", page.producerStops)
              .put("requests", page.requests.get()).put("inFlight", page.inFlight.get())
              .put("peakInFlight", page.peakInFlight.get()).put("deliveries", page.delivered)
              .put("cached", page.cached).put("photoWorkersRunning", page.photoRunning)
              .put("photoTicks", page.photoTicks).put("onResumeCalls", page.resumes)
              .put("childLifecycle", page.ownerState.name)
              .put("parentGateIdentityPreserved", page.gate === parentGate)
        })))
  }

  private suspend fun checkpoint(name: String) {
    snapshot(name)
    report.put("checkpoint", name)
    publish()
    val acknowledgement = File(directory, "continue-$name")
    until("Host screenshot acknowledgement $name", 15_000) { acknowledgement.exists() }
    acknowledgement.delete()
    report.remove("checkpoint")
  }

  private suspend fun selectedReady(label: String) {
    until(label) {
      scoped.all { it.created == 1 } &&
          scoped.withIndex().all { (index, page) ->
            page.active == (index == selected) && page.widgetInput == (index == selected) &&
                page.running == (if (index == selected) 1 else 0) &&
                page.photoRunning == (if (index == selected) 1 else 0) &&
                page.ownerState == (if (index == selected) Lifecycle.State.RESUMED else Lifecycle.State.CREATED)
          }
    }
    demand("$label exactly one running produceHomeState body", scoped.sumOf { it.running } == 1)
    demand("$label exactly one RESUMED photo-like worker", scoped.sumOf { it.photoRunning } == 1)
    demand("$label hidden children CREATED", scoped.withIndex().all { (index, page) ->
      index == selected || page.ownerState == Lifecycle.State.CREATED
    })
    demand("$label forwards unchanged parent input gate identity", scoped.all { it.gate === parentGate })
    demand("$label hidden children do not disable shared parent input gate",
        parentGate.active && parentGate.acceptsAction())
    snapshot(label)
  }

  private suspend fun runChecks() {
    demand("Native fixture Android API 34", Build.VERSION.SDK_INT == 34)
    until("Old wiring starts all three actual producers") {
      baseline.all { it.running == 1 && it.photoRunning == 1 && it.cached != "empty" }
    }
    demand("Old provider wiring replay runs three produceHomeState bodies",
        baseline.sumOf { it.running } == 3)
    demand("Old wiring replay runs three RESUMED photo-like workers", baseline.sumOf { it.photoRunning } == 3)
    report.put("redBaseline", JSONObject().put("assertion", "At most one retained child producer runs")
        .put("expectedMaximum", 1).put("observedProducerBodies", 3)
        .put("observedResumedPhotoWorkers", 3).put("expectedFailureDetected", true)
        .put("replayInCurrentApk", true))
    checkpoint("baseline-three")

    legacy = false
    until("Baseline disposal cancels workers") {
      baseline.all { it.disposed == 1 && it.running == 0 && it.photoRunning == 0 && it.inFlight.get() == 0 }
    }
    selectedReady("scoped-page-0")
    until("Page zero generated cached value") { scoped[0].cached != "empty" }
    val owners = scoped.map { it.owner }
    checkpoint("scoped-zero")
    selected = 1
    selectedReady("scoped-page-1")
    until("Page one generated cached value") { scoped[1].cached != "empty" }
    checkpoint("scoped-one")
    selected = 2
    selectedReady("scoped-page-2")
    until("Page two generated cached value") { scoped[2].cached != "empty" }
    checkpoint("scoped-two")

    val frozen = scoped.take(2).map { it.delivered to it.photoTicks }
    delay(180)
    demand("Hidden children publish no polling data or photo ticks",
        scoped.take(2).map { it.delivered to it.photoTicks } == frozen)
    selected = 1
    selectedReady("reverse-page-1")
    selected = 0
    selectedReady("reverse-page-0")
    demand("Child lifecycle owner identities remain stable across hide and reveal",
        scoped.indices.all { scoped[it].owner === owners[it] })
    demand("Retained neighbor children created once without disposal", scoped.all { it.created == 1 && it.disposed == 0 })

    val resumeBefore = scoped.map { it.resumes }
    host.registry.currentState = Lifecycle.State.STARTED
    until("STARTED caps active owner and stops RESUMED worker") {
      scoped[0].ownerState == Lifecycle.State.STARTED && scoped.all { it.photoRunning == 0 }
    }
    demand("STARTED parent keeps selected polling body enabled", scoped[0].active)
    demand("STARTED caps selected child at STARTED", scoped[0].ownerState == Lifecycle.State.STARTED)
    host.registry.currentState = Lifecycle.State.CREATED
    until("CREATED parent stops all work") {
      scoped.all { !it.active && it.running == 0 && it.photoRunning == 0 && it.ownerState == Lifecycle.State.CREATED }
    }
    demand("Paused host has zero polling bodies", scoped.sumOf { it.running } == 0)
    demand("Paused host has zero RESUMED workers", scoped.sumOf { it.photoRunning } == 0)
    demand("Paused parent input gate remains shared and disabled",
        !parentGate.acceptsAction() && scoped.all { it.gate === parentGate })
    checkpoint("host-paused")
    host.registry.currentState = Lifecycle.State.RESUMED
    selectedReady("parent-resumed")
    demand("Actual OnResume helper runs only for selected child on parent resume",
        scoped.indices.all { scoped[it].resumes == resumeBefore[it] + if (it == selected) 1 else 0 })

    parentActive = false
    until("Inactive parent cancels all producers") {
      scoped.all { !it.active && !it.widgetInput && it.running == 0 && it.photoRunning == 0 }
    }
    demand("Parent inactive denies shared gate without changing its identity",
        !parentGate.acceptsAction() && scoped.all { it.gate === parentGate })
    parentActive = true
    selectedReady("parent-active-recovery")

    // A fresh long refresh deadline guarantees a cache-only reveal before the next request.
    scoped[0].revision = 7
    until("Revision seven published") { scoped[0].cached.startsWith("page=0;key=7;") }
    val cached = scoped[0].cached
    val requestsBefore = scoped[0].requests.get()
    selected = 1
    until("Zero hidden with retained cache") { !scoped[0].active && scoped[0].running == 0 }
    selected = 0
    until("Zero revealed with cached value") { scoped[0].active && scoped[0].cached == cached }
    demand("Reveal keeps cached generated value", scoped[0].cached == cached)
    demand("Reveal honors remaining refresh deadline before issuing request",
        scoped[0].requests.get() == requestsBefore)
    selectedReady("cache-reveal")
    selected = 1
    selectedReady("key-hidden-origin")
    val hiddenStarts = scoped[0].requests.get()
    scoped[0].revision = 8
    delay(120)
    demand("Hidden key change does not start a request", scoped[0].requests.get() == hiddenStarts)
    selected = 0
    until("Reveal uses changed data key") { scoped[0].cached.startsWith("page=0;key=8;") }
    demand("Changed keys publish matching generated data", scoped[0].cached.startsWith("page=0;key=8;"))

    scoped[0].latency = 420
    until("Cancellation-resistant generated IO starts") { scoped[0].inFlight.get() == 1 }
    val starts = scoped[0].requests.get()
    parentActive = false
    until("Quick hide applied") { !scoped[0].active }
    parentActive = true
    until("Quick reveal applied") { scoped[0].active }
    demand("Quick resume cannot duplicate cancellation-resistant request",
        scoped[0].requests.get() == starts && scoped[0].inFlight.get() == 1)
    until("New request starts after old IO releases flight", 3_000) { scoped[0].requests.get() > starts }
    demand("Per-page generated requests never overlap", scoped.all { it.peakInFlight.get() <= 1 })
    until("New request publishes after cancellation") {
      scoped[0].cached.endsWith("request=${scoped[0].requests.get()}") && scoped[0].inFlight.get() == 0
    }
    snapshot("delayed-io-recovered")
    mounted = false
    until("Unmount cleans all retained children", 3_000) {
      scoped.all { it.disposed == 1 && it.running == 0 && it.photoRunning == 0 && it.inFlight.get() == 0 }
    }
    demand("Unmount disposes every generated retained child once", scoped.all { it.created == 1 && it.disposed == 1 })
    demand("Unmount cancels every polling and lifecycle worker", scoped.all { it.running == 0 && it.photoRunning == 0 })
    demand("Child disposal leaves the active parent input gate enabled", parentGate.active && parentGate.acceptsAction())
    snapshot("cleanup-unmounted")
    report.put("baselineCreatedDisposed", JSONArray(baseline.map {
      JSONObject().put("created", it.created).put("disposed", it.disposed)
    }))
  }
}
