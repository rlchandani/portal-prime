/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/** Legacy callers retain their frame; the Tools host owns its single workspace heading. */
@Composable
internal fun ApprovedToolsSpeedScreen(onBack: () -> Unit) {
    if (LocalApprovedToolWorkspace.current) ToolsSpeedContent()
    else ApprovedToolWorkspace("Speed test", onBack, scrollContent = false) { ToolsSpeedContent() }
}

private val speedResultSaver = listSaver<SpeedTest.Result, Any>(
    save = { listOf(it.server, it.pingMs, it.jitterMs, it.downMbps, it.upMbps) },
    restore = { values -> SpeedTest.Result().apply {
        server = values[0] as String; pingMs = values[1] as Double; jitterMs = values[2] as Double
        downMbps = values[3] as Double; upMbps = values[4] as Double
    } })

@Composable
internal fun ToolsSpeedContent() {
    val owner = LocalLifecycleOwner.current
    val active = LocalApprovedLauncherPageActive.current
    val scope = rememberCoroutineScope()
    var result by rememberSaveable(stateSaver = speedResultSaver) { mutableStateOf(SpeedTest.Result()) }
    var started by rememberSaveable { mutableStateOf(false) }
    var completed by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf(SpeedTest.Phase.DONE) }
    var job by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableStateOf(0L) }
    val running = phase != SpeedTest.Phase.DONE
    fun cancel() {
        if (job?.isActive == true) {
            error = "The test was stopped. Run it again when you’re ready."
            completed = false
            generation++
            job?.cancel()
            job = null
            phase = SpeedTest.Phase.DONE
        }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) cancel() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); generation++; job?.cancel() }
    }
    LaunchedEffect(active) { if (!active) cancel() }
    fun start() {
        if (!active || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || job?.isActive == true) return
        val next = SpeedTest.Result()
        val request = ++generation
        result = next; started = true; completed = false; error = null; phase = SpeedTest.Phase.PING
        job = scope.launch {
            try {
                val missing = ApprovedToolsSpeedRunner.run(next) { nextPhase ->
                    // The runner owns blocking sockets; Compose state belongs to the main scope.
                    scope.launch {
                        if (request == generation && job?.isActive == true) phase = nextPhase
                    }
                }
                if (request != generation) return@launch
                completed = missing.isEmpty() && next.pingMs > 0 && next.downMbps > 0 && next.upMbps > 0
                if (!completed) error = "The test couldn’t finish every measurement. Check your connection and try again."
            } catch (cancelled: CancellationException) {
                if (request == generation) error = "The test was stopped. Run it again when you’re ready."
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) error = "The test couldn’t reach its server. Check your connection and try again."
            } finally {
                if (request == generation) { phase = SpeedTest.Phase.DONE; job = null }
            }
        }
    }
    ToolsTwoPane(left = {
        ToolsPanel("Connection") {
            ToolsMetric("Server", result.server.ifBlank { "Cloudflare" })
            ToolsText(when (phase) {
                SpeedTest.Phase.PING -> "Measuring latency…"
                SpeedTest.Phase.DOWNLOAD -> "Testing download…"
                SpeedTest.Phase.UPLOAD -> "Testing upload…"
                SpeedTest.Phase.DONE -> if (!started) "Ready to test" else if (completed) "Test complete" else "Test stopped or incomplete"
            })
            if (running) LinearProgressIndicator(color = IosBlue, trackColor = IosDivider, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ToolsButton(if (!started) "Start test" else "Run again", primary = true, enabled = !running, onClick = ::start)
                if (running) ToolsButton("Cancel test", onClick = ::cancel)
            }
            error?.let { ToolsText(it, true) }
            ToolsText("The test downloads and uploads data to Cloudflare. It runs only when you start it.", true)
        }
    }, right = {
        ToolsPanel("Results") {
            ToolsMetric("Ping", speedMetric(result.pingMs, "ms"))
            ToolsMetric("Jitter", if (result.pingMs > 0) String.format(Locale.getDefault(), "%.1f ms", result.jitterMs) else "—")
            ToolsMetric("Download", speedMetric(result.downMbps, "Mbps"))
            ToolsMetric("Upload", speedMetric(result.upMbps, "Mbps"))
        }
    })
}

private fun speedMetric(value: Double, unit: String): String =
    if (value > 0) String.format(Locale.getDefault(), "%.1f %s", value, unit) else "—"
