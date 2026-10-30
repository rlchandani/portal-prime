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
import android.view.Choreographer
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs the shipped Compose fallback with generated pages and a fixture-owned frame clock.
 * Coordinates are layout observations, never a physical display/FPS measurement.
 */
class LauncherTabsPreviewActivity : ComponentActivity() {
  private class HostOwner : LifecycleOwner {
    val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
  }

  private class Page {
    var creations = 0
    var disposals = 0
    var clicks = 0
    var compositions = 0
    var placements = 0
    var active = false
    var lifecycle = Lifecycle.State.INITIALIZED
    var gate: LauncherPageInputGate? = null
    var x = Float.NaN
    var width = 0
    var buttonX = 0f
    var buttonY = 0f
  }

  private val pages = ApprovedLauncherTab.entries.associateWith { Page() }
  private val host = HostOwner()
  private val clock = BroadcastFrameClock()
  private lateinit var recomposer: Recomposer
  private lateinit var compose: ComposeView
  private lateinit var directory: File
  private var selected by mutableStateOf(ApprovedLauncherTab.HOME)
  private var contentActive by mutableStateOf(true)
  private var narrow by mutableStateOf(false)
  private var mounted by mutableStateOf(true)
  private var viewportWidth = 0
  private var viewportX = 0f
  private var timeNanos = 1_000_000_000L
  private val checks = JSONArray()
  private val samples = JSONArray()
  private val report = JSONObject()
      .put("complete", false).put("success", false)
      .put("renderer", "Actual production ApprovedLauncherTabs Compose fallback")
      .put("nativeHomeCandidateUsed", false)
      .put("api", Build.VERSION.SDK_INT)
      .put("animationClock", "Debug fixture BroadcastFrameClock; fixed 16.666667ms steps")
      .put("measurement", "Compose layout coordinates, composition counters, lifecycle and input")
      .put("physicalDisplayMeasured", false).put("physicalPortalPerformanceMeasured", false)
      .put("fpsMeasured", false).put("syntheticHostLifecycle", true)
      .put("householdConfigRead", false).put("checks", checks).put("samples", samples)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or
        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    directory = File(cacheDir, "launcher-tabs-native").apply { mkdirs() }
    host.registry.currentState = Lifecycle.State.RESUMED
    recomposer = Recomposer(lifecycleScope.coroutineContext + clock)
    compose = ComposeView(this).apply {
      setParentCompositionContext(recomposer)
      setContent {
        MaterialTheme {
          Column(Modifier.fillMaxSize().background(Color(0xff161b22))) {
            Text("Production launcher fallback fixture", color = Color.White,
                modifier = Modifier.height(48.dp))
            CompositionLocalProvider(LocalLifecycleOwner provides host) {
              Box(Modifier.fillMaxWidth().weight(1f)) {
                if (mounted) {
                  ApprovedLauncherTabs(selected,
                      modifier = (if (narrow) Modifier.width(720.dp) else Modifier.fillMaxWidth())
                          .fillMaxSize().onGloballyPositioned {
                            viewportWidth = it.size.width
                            viewportX = it.positionInRoot().x
                          },
                      contentActive = contentActive) { tab -> FixturePage(tab) }
                }
              }
            }
          }
        }
      }
    }
    setContentView(compose)
    lifecycleScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
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

  override fun onDestroy() {
    if (::compose.isInitialized) compose.disposeComposition()
    if (::recomposer.isInitialized) recomposer.cancel()
    super.onDestroy()
  }

  @Composable
  private fun FixturePage(tab: ApprovedLauncherTab) {
    val page = pages.getValue(tab)
    val owner = LocalLifecycleOwner.current
    val active = LocalApprovedLauncherPageActive.current
    val gate = LocalApprovedLauncherInputGate.current
    DisposableEffect(owner) {
      page.creations++
      val observer = LifecycleEventObserver { _, _ -> page.lifecycle = owner.lifecycle.currentState }
      owner.lifecycle.addObserver(observer)
      page.lifecycle = owner.lifecycle.currentState
      onDispose {
        owner.lifecycle.removeObserver(observer)
        page.disposals++
      }
    }
    SideEffect {
      page.compositions++
      page.active = active
      page.gate = gate
    }
    val color = when (tab) {
      ApprovedLauncherTab.HOME -> Color(0xff315b73)
      ApprovedLauncherTab.APPS -> Color(0xff66548b)
      ApprovedLauncherTab.TOOLS -> Color(0xff79613c)
    }
    Box(Modifier.fillMaxSize().background(color).onGloballyPositioned {
      page.placements++
      page.x = it.positionInRoot().x
      page.width = it.size.width
    }, contentAlignment = Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${tab.id} fixture page", color = Color.White)
        Button(onClick = {
          if (gate?.acceptsAction() != false) page.clicks++
        }, modifier = Modifier
            .semantics { contentDescription = "Activate ${tab.id} fixture" }
            .onGloballyPositioned {
              val position = it.positionInWindow()
              page.buttonX = position.x + it.size.width / 2f
              page.buttonY = position.y + it.size.height / 2f
            }) { Text("Tap ${tab.id}") }
      }
    }
  }

  private fun publish() {
    val temporary = File(directory, "checks.tmp")
    temporary.writeText(report.toString(2) + "\n")
    check(temporary.renameTo(File(directory, "checks.json")))
  }

  private fun demand(name: String, condition: Boolean) {
    check(condition) { name }
    checks.put(name)
  }

  private fun fraction(tab: ApprovedLauncherTab): Float =
      (pages.getValue(tab).x - viewportX) / viewportWidth

  private fun sample(label: String): JSONObject {
    val row = JSONObject().put("label", label).put("clockNanos", timeNanos)
        .put("selected", selected.id).put("viewportWidthPx", viewportWidth)
    val values = JSONObject()
    pages.forEach { (tab, page) ->
      values.put(tab.id, JSONObject().put("created", page.creations)
          .put("disposed", page.disposals).put("clicks", page.clicks)
          .put("compositions", page.compositions).put("placements", page.placements)
          .put("active", page.active).put("lifecycle", page.lifecycle.name)
          .put("inputGateActive", page.gate?.active ?: false)
          .put("inputAllowed", page.gate?.acceptsAction() ?: false)
          .put("xPx", if (page.x.isFinite()) page.x else JSONObject.NULL)
          .put("offsetFraction", if (page.x.isFinite() && viewportWidth > 0)
            fraction(tab) else JSONObject.NULL))
    }
    row.put("pages", values)
    samples.put(row)
    return row
  }

  private suspend fun androidFrame() {
    suspendCancellableCoroutine<Unit> { continuation ->
      val callback = Choreographer.FrameCallback {
        if (continuation.isActive) continuation.resume(Unit)
      }
      Choreographer.getInstance().postFrameCallback(callback)
      continuation.invokeOnCancellation { Choreographer.getInstance().removeFrameCallback(callback) }
    }
  }

  private suspend fun step(label: String? = null, advance: Boolean = true) {
    // Native traversal follows each fixture animation tick; this is deliberately not a FPS test.
    if (advance) timeNanos += 16_666_667L
    clock.sendFrame(timeNanos)
    androidFrame()
    androidFrame()
    if (label != null) sample(label)
  }

  private suspend fun flush(label: String) {
    repeat(3) { step(advance = false) }
    sample(label)
  }

  private suspend fun settle(label: String, selectedActionAllowed: Boolean = true) {
    repeat(16) { step() }
    sample(label)
    demand("$label selected page settles at zero", abs(fraction(selected)) <= 1f / viewportWidth)
    demand("$label only selected page active",
        pages.all { (tab, page) -> page.creations == 0 || page.active == (tab == selected && contentActive) })
    demand("$label only selected page accepts input",
        pages.all { (tab, page) ->
          page.creations == 0 || page.gate?.acceptsAction() == (tab == selected && selectedActionAllowed && contentActive &&
              host.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        })
  }

  private suspend fun checkpoint(name: String, active: ApprovedLauncherTab?) {
    report.put("checkpoint", name).put("expectedAccessibleTab", active?.id ?: JSONObject.NULL)
    sample("accessibility-$name")
    publish()
    val acknowledgement = File(directory, "continue-$name")
    val deadline = SystemClock.elapsedRealtime() + 25_000
    while (!acknowledgement.exists()) {
      check(SystemClock.elapsedRealtime() < deadline) { "Checker did not acknowledge $name" }
      delay(100)
    }
    acknowledgement.delete()
    report.remove("checkpoint")
  }

  private suspend fun move(tab: ApprovedLauncherTab, label: String) {
    val previous = selected
    val direction = if (tab.ordinal > previous.ordinal) 1f else -1f
    selected = tab
    repeat(2) { step() }
    var observedMotion = false
    var compositionBaseline: Map<ApprovedLauncherTab, Int>? = null
    var stableMotionSamples = 0
    repeat(8) {
      step("$label-$it")
      val outgoing = fraction(previous)
      val incoming = fraction(tab)
      if (abs(outgoing) > .01f && abs(outgoing) < .95f &&
          abs(incoming) > .01f && abs(incoming) < .95f) {
        demand("$label outgoing follows direction", outgoing * direction < 0f)
        demand("$label incoming follows direction", incoming * direction > 0f)
        demand("$label paired pages stay one viewport apart",
            abs(abs(incoming - outgoing) - 1f) <= 2f / viewportWidth)
        val counts = pages.mapValues { it.value.compositions }
        if (compositionBaseline == null) compositionBaseline = counts
        else {
          demand("$label frame-$it has no page recomposition", counts == compositionBaseline)
          stableMotionSamples++
        }
        observedMotion = true
      }
    }
    demand("$label observes in-flight layout coordinates", observedMotion)
    demand("$label observes multiple placement frames without page recomposition",
        stableMotionSamples >= 2)
    settle(label)
  }

  private suspend fun reverse(from: ApprovedLauncherTab, to: ApprovedLauncherTab, label: String) {
    selected = from
    settle("$label-origin")
    selected = to
    var partial = false
    repeat(10) {
      if (!partial) {
        step()
        partial = abs(fraction(from)) in .18f.. .75f
      }
    }
    demand("$label captures an interrupted transition", partial)
    val beforeFrom = fraction(from)
    val beforeTo = fraction(to)
    sample("$label-before")
    selected = from
    // Recomposition begins a new plan but no animation-clock frame has advanced yet.
    flush("$label-retarget-no-tick")
    demand("$label outgoing preserved before new animation frame",
        abs(fraction(from) - beforeFrom) <= 2f / viewportWidth)
    demand("$label incoming preserved before new animation frame",
        abs(fraction(to) - beforeTo) <= 2f / viewportWidth)
    step("$label-first-tick")
    demand("$label outgoing does not jump to an edge at restart",
        abs(fraction(from) - beforeFrom) <= .04f)
    demand("$label incoming does not jump to an edge at restart",
        abs(fraction(to) - beforeTo) <= .04f)
    settle(label)
  }

  private fun touch(action: Int, page: Page, down: Long) {
    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
        page.buttonX, page.buttonY, 0)
    try { dispatchTouchEvent(event) } finally { event.recycle() }
  }

  private suspend fun runChecks() {
    repeat(8) { step() }
    demand("Fixture runs Android API 34", Build.VERSION.SDK_INT == 34)
    demand("Initial Home created once, unvisited pages not composed",
        pages.getValue(ApprovedLauncherTab.HOME).creations == 1 &&
            pages.filterKeys { it != ApprovedLauncherTab.HOME }.values.all { it.creations == 0 })
    settle("initial-home")
    checkpoint("home", ApprovedLauncherTab.HOME)
    val home = pages.getValue(ApprovedLauncherTab.HOME)
    var down = SystemClock.uptimeMillis()
    touch(MotionEvent.ACTION_DOWN, home, down)
    androidFrame()
    touch(MotionEvent.ACTION_UP, home, down)
    repeat(2) { step() }
    demand("Active Home button accepts a real dispatched touch", home.clicks == 1)

    move(ApprovedLauncherTab.APPS, "home-to-apps")
    checkpoint("apps", ApprovedLauncherTab.APPS)
    move(ApprovedLauncherTab.TOOLS, "apps-to-tools")
    checkpoint("tools", ApprovedLauncherTab.TOOLS)
    move(ApprovedLauncherTab.APPS, "tools-to-apps")
    move(ApprovedLauncherTab.HOME, "apps-to-home")
    reverse(ApprovedLauncherTab.HOME, ApprovedLauncherTab.APPS, "home-apps-rapid-reversal")
    reverse(ApprovedLauncherTab.TOOLS, ApprovedLauncherTab.APPS, "tools-apps-rapid-reversal")
    reverse(ApprovedLauncherTab.HOME, ApprovedLauncherTab.TOOLS, "home-tools-rapid-reversal")

    selected = ApprovedLauncherTab.APPS
    settle("resize-origin")
    selected = ApprovedLauncherTab.TOOLS
    repeat(5) { step() }
    val oldWidth = viewportWidth
    val before = ApprovedLauncherTab.entries.associateWith { fraction(it) }
    narrow = true
    // Width changes without advancing tween time.
    flush("resize-recomposition")
    val normalized = ApprovedLauncherTab.entries.associateWith { fraction(it) }
    demand("Resize changes the measured launcher viewport", viewportWidth < oldWidth)
    demand("Resize preserves displayed normalized offsets with animation time frozen",
        listOf(ApprovedLauncherTab.APPS, ApprovedLauncherTab.TOOLS).all {
          abs(normalized.getValue(it) - before.getValue(it)) <= 2f / viewportWidth
        })
    demand("Resize retains normalized pair separation",
        abs(abs(normalized.getValue(ApprovedLauncherTab.TOOLS) -
            normalized.getValue(ApprovedLauncherTab.APPS)) - 1f) <= 2f / viewportWidth)
    report.put("resizeBeforeFractions", JSONObject(before.mapKeys { it.key.id }))
        .put("resizeAfterFractions", JSONObject(normalized.mapKeys { it.key.id }))
    settle("resize-settled")
    narrow = false
    settle("full-width-restored")

    host.registry.currentState = Lifecycle.State.CREATED
    repeat(4) { step() }
    sample("parent-created")
    demand("Parent CREATED pauses every retained page lifecycle",
        pages.values.all { it.lifecycle == Lifecycle.State.CREATED })
    demand("Parent CREATED denies every retained page input",
        pages.values.all { it.gate?.acceptsAction() == false })
    host.registry.currentState = Lifecycle.State.RESUMED
    repeat(4) { step() }
    demand("Parent RESUMED resumes only selected Tools",
        pages.all { (tab, page) -> page.lifecycle ==
            if (tab == selected) Lifecycle.State.RESUMED else Lifecycle.State.CREATED })

    contentActive = false
    repeat(4) { step() }
    sample("content-inactive")
    demand("Inactive content denies all input and active producers",
        pages.values.all { !it.active && it.gate?.acceptsAction() == false })
    checkpoint("inactive", null)
    contentActive = true
    selected = ApprovedLauncherTab.HOME
    settle("touch-origin")
    val beforeClick = home.clicks
    down = SystemClock.uptimeMillis()
    touch(MotionEvent.ACTION_DOWN, home, down)
    androidFrame()
    selected = ApprovedLauncherTab.APPS
    settle("held-touch-hidden")
    selected = ApprovedLauncherTab.HOME
    settle("held-touch-returned", selectedActionAllowed = false)
    touch(MotionEvent.ACTION_UP, home, down)
    repeat(3) { step() }
    demand("Held touch cannot click after hide and quick return", home.clicks == beforeClick)
    down = SystemClock.uptimeMillis()
    touch(MotionEvent.ACTION_DOWN, home, down)
    androidFrame()
    touch(MotionEvent.ACTION_UP, home, down)
    repeat(3) { step() }
    demand("Fresh touch works after a cancelled held gesture", home.clicks == beforeClick + 1)
    demand("All visited pages created exactly once and retained throughout transitions",
        pages.values.all { it.creations == 1 && it.disposals == 0 })
    checkpoint("final-home", ApprovedLauncherTab.HOME)
    mounted = false
    repeat(4) { step() }
    sample("unmounted")
    demand("Unmount disposes every retained page exactly once",
        pages.values.all { it.creations == 1 && it.disposals == 1 })
    demand("Unmount closes every page input gate",
        pages.values.all { it.gate?.acceptsAction() == false })
    report.put("finalCounters", JSONObject(pages.map { (tab, page) ->
      tab.id to JSONObject().put("created", page.creations).put("disposed", page.disposals)
          .put("clicks", page.clicks).put("compositions", page.compositions)
          .put("placements", page.placements)
    }.toMap()))
  }
}
