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
    var incompleteMask by rememberSaveable { mutableIntStateOf(0) }
    var stopped by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf(SpeedTest.Phase.DONE) }
    var job by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableStateOf(0L) }
    val running = phase != SpeedTest.Phase.DONE
    fun cancel() {
        if (job?.isActive == true) {
            error = "The test was stopped. Run it again when you’re ready."
            completed = false
            stopped = true
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
        result = next; started = true; completed = false; stopped = false; incompleteMask = 0; error = null; phase = SpeedTest.Phase.PING
        job = scope.launch {
            try {
                val missing = ApprovedToolsSpeedRunner.run(next) { nextPhase ->
                    // The runner owns blocking sockets; Compose state belongs to the main scope.
                    scope.launch {
                        if (request == generation && job?.isActive == true) phase = nextPhase
                    }
                }
                if (request != generation) return@launch
                incompleteMask = SpeedTestPresentation.effectiveMissingMask(missing, next.speedValues())
                completed = incompleteMask == 0
                if (!completed) error = SpeedTestPresentation.failureText(incompleteMask)
            } catch (cancelled: CancellationException) {
                if (request == generation) {
                    stopped = true
                    error = "The test was stopped. Run it again when you’re ready."
                }
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) {
                    incompleteMask = SpeedTestPresentation.ALL
                    error = "The test couldn’t reach its server. Check your connection and try again."
                }
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
        val outcome = when {
            running -> SpeedTestDisplayStatus.RUNNING
            !started -> SpeedTestDisplayStatus.READY
            completed -> SpeedTestDisplayStatus.COMPLETE
            stopped || error == null -> SpeedTestDisplayStatus.STOPPED
            else -> SpeedTestDisplayStatus.INCOMPLETE
        }
        ToolsSpeedResults(result.speedValues(), outcome, incompleteMask)
    })
}

private fun SpeedTest.Result.speedValues() = SpeedTestValues(pingMs, jitterMs, downMbps, upMbps)

/** The same production result panel is used by the actual tool and public UI fixtures. */
@Composable
internal fun ToolsSpeedResults(values: SpeedTestValues, outcome: SpeedTestDisplayStatus, missingMask: Int) {
    ToolsPanel("Results") {
        SpeedTestPresentation.caption(outcome)?.let { ToolsText(it, true) }
        SpeedTestPresentation.rows(values, outcome, missingMask).forEach { row ->
            ToolsMetric(row.label, row.value)
        }
    }
}
