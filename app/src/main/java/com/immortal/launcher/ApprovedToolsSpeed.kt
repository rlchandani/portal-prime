/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import java.util.Locale

/** Explicit native destination: each run owns a result so an abandoned run cannot update a retry. */
@Composable
internal fun ApprovedToolsSpeedScreen(onBack: () -> Unit) {
  val owner = LocalLifecycleOwner.current
  var runId by remember { mutableStateOf(0) }
  val result = remember(runId) { SpeedTest.Result() }
  var phase by remember(runId) { mutableStateOf(SpeedTest.Phase.DONE) }
  var error by remember(runId) { mutableStateOf<String?>(null) }
  LaunchedEffect(owner, runId) {
    if (runId == 0) return@LaunchedEffect
    // A retained page cancels transfers on stop; returning requires another explicit run.
    var started = false
    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      if (started) return@repeatOnLifecycle
      started = true
      phase = SpeedTest.Phase.PING
      val job = currentCoroutineContext()[Job]
      try {
        ApprovedToolsSpeedRunner.run(result) { next -> if (job?.isActive == true) phase = next }
        if (job?.isActive == true) {
          if (result.pingMs == 0.0 && result.downMbps == 0.0 && result.upMbps == 0.0) error = "The test couldn’t reach its server. Check your internet connection and try again."
        }
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        error = "The test was stopped. Run it again when you’re ready."
        throw cancelled
      } catch (failure: Exception) {
        error = failure.message ?: "The speed test couldn’t finish."
      } finally {
        phase = SpeedTest.Phase.DONE
      }
    }
  }
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 56.dp, vertical = 36.dp)) {
    ApprovedAppsButton("‹  Tools", onClick = onBack)
    Spacer(Modifier.height(26.dp))
    ApprovedAppsHeading("Speed test", "Measure this Portal’s internet connection.")
    SpeedGroup("Connection") {
      SpeedRow("Server", result.server.ifBlank { if (runId == 0) "Cloudflare" else if (phase == SpeedTest.Phase.DONE) "Unavailable" else "Finding server…" })
      SpeedRow("Progress", when (phase) { SpeedTest.Phase.PING -> "Measuring latency…"; SpeedTest.Phase.DOWNLOAD -> "Testing download…"; SpeedTest.Phase.UPLOAD -> "Testing upload…"; SpeedTest.Phase.DONE -> if (runId == 0) "Ready to test" else if (error != null) "Test incomplete" else "Test complete" })
    }
    Spacer(Modifier.height(24.dp))
    SpeedGroup("Results") {
      SpeedRow("Ping", metric(result.pingMs, "ms"))
      SpeedRow("Jitter", metric(result.jitterMs, "ms"))
      SpeedRow("Download", metric(result.downMbps, "Mbps"))
      SpeedRow("Upload", metric(result.upMbps, "Mbps"))
    }
    if (phase != SpeedTest.Phase.DONE) LinearProgressIndicator(color = IosBlue, trackColor = IosDivider, modifier = Modifier.fillMaxWidth().padding(top = 26.dp))
    error?.let { Text(it, color = IosSecondary, fontSize = 24.sp, lineHeight = 34.sp, modifier = Modifier.padding(top = 26.dp)) }
    ApprovedAppsButton(if (runId == 0) "Start test" else if (phase == SpeedTest.Phase.DONE) "Run again" else "Testing…", primary = true, enabled = phase == SpeedTest.Phase.DONE, modifier = Modifier.padding(top = 28.dp)) { runId++ }
  }
}

private fun metric(value: Double, unit: String): String = if (value > 0.0) String.format(Locale.getDefault(), "%.1f %s", value, unit) else "—"

@Composable
private fun SpeedGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
  Text(title, color = IosSecondary, fontSize = 22.sp, modifier = Modifier.padding(start = 22.dp, bottom = 12.dp))
  Surface(color = IosCard, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, IosDivider), modifier = Modifier.fillMaxWidth()) { Column(content = content) }
}

@Composable
private fun SpeedRow(label: String, value: String) {
  Row(Modifier.fillMaxWidth().heightIn(min = 100.dp).padding(horizontal = 28.dp, vertical = 21.dp)) {
    Text(label, color = IosLabel, fontSize = 26.sp, lineHeight = 34.sp, modifier = Modifier.weight(1f))
    Text(value, color = IosSecondary, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Normal)
  }
}
