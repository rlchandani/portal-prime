/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException

/** Revisit is a memory lookup; explicit refresh still uses each source's native cache policy. */
private object ToolsDiscoveryCache {
    var passes: List<IssPasses.Pass>? = null
    var passesAt = 0L
    var aurora: Aurora.Status? = null
    var auroraAt = 0L
}

@Composable
internal fun ToolsIssContent() {
    val context = LocalContext.current.applicationContext
    val active = LocalApprovedLauncherPageActive.current
    var passes by remember { mutableStateOf(ToolsDiscoveryCache.passes) }
    var foregroundAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(ToolsDiscoveryCache.passes?.let {
        issPredictionMessage(IssPasses.Prediction(it))
    }) }
    var revision by remember { mutableStateOf(0) }
    val refreshGate = remember { ToolsRefreshGate() }
    LaunchedEffect(active, revision) {
        if (!active) return@LaunchedEffect
        foregroundAt = System.currentTimeMillis()
        val remaining = passes?.filter { it.endMillis > foregroundAt }
        passes = remaining
        val cached = ToolsDiscoveryCache.passes
        if (!refreshGate.isPending(revision) && cached != null &&
            (cached.isEmpty() || !remaining.isNullOrEmpty()) &&
            foregroundAt - ToolsDiscoveryCache.passesAt < 30 * 60_000L) {
            message = issPredictionMessage(IssPasses.Prediction(remaining.orEmpty()))
            ToolsDiscoveryDiagnostics.cacheHit("iss")
            return@LaunchedEffect
        }
        loading = true
        message = null
        var usable = false
        ToolsDiscoveryDiagnostics.started("iss")
        try {
            val result = ToolsWork.load { IssPasses.predictStatus(context) }
            foregroundAt = System.currentTimeMillis()
            val predicted = result.passes.filter { it.endMillis > foregroundAt }
            passes = passes?.filter { it.endMillis > foregroundAt }
            message = issPredictionMessage(IssPasses.Prediction(predicted, result.problem))
            if (result.problem == null) {
                passes = predicted
                usable = predicted.isNotEmpty()
                ToolsDiscoveryCache.passes = predicted
                ToolsDiscoveryCache.passesAt = foregroundAt
                refreshGate.completed(revision)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            foregroundAt = System.currentTimeMillis()
            passes = passes?.filter { it.endMillis > foregroundAt }
            message = issPredictionMessage(IssPasses.Prediction(
                emptyList(), IssPasses.PredictionProblem.CALCULATION))
        } finally { ToolsDiscoveryDiagnostics.finished("iss", usable); loading = false }
    }
    val upcoming = passes.orEmpty().filter { it.endMillis > foregroundAt }
    val next = upcoming.firstOrNull { it.visible } ?: upcoming.firstOrNull()
    ToolsTwoPane(left = {
        ToolsPanel(if (next?.visible == true) "Next visible pass" else "Next pass") {
            if (next == null) ToolsText(if (loading) "Finding passes…" else message ?: "No upcoming passes.", true)
            else {
                Text(IssPasses.timeLabel(next.startMillis), color = IosLabel, fontSize = 52.sp,
                    lineHeight = 64.sp, fontWeight = FontWeight.Medium)
                ToolsText("Rises ${next.startDir} · peak ${next.maxElevationDeg}° to ${next.peakDir} · sets ${next.endDir}", true)
                ToolsStatus(if (next.visible) "Should be visible to the naked eye" else "Above the horizon; may not be visible", next.visible)
            }
            ToolsButton(if (loading) "Finding passes…" else "Refresh passes", enabled = !loading) { revision++ }
            if (next != null && message != null) ToolsText(message!!, true)
        }
        ToolsPanel("Before you head outside") {
            ToolsText("Times are local. Look toward the listed direction with a clear horizon, away from bright lights.", true)
            ToolsText("Orbit prediction uses the latest available ISS data and the location used by Weather.", true)
        }
    }, right = {
        ToolsPanel("Upcoming passes") {
            if (upcoming.isEmpty()) ToolsText(if (loading) "Finding passes…" else message ?: "No upcoming passes.", true)
            upcoming.forEach { pass ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolsText(IssPasses.timeLabel(pass.startMillis))
                    ToolsText("${pass.startDir} → ${pass.endDir} · peak ${pass.maxElevationDeg}°", true)
                    if (pass.visible) ToolsStatus("Visible", true)
                }
            }
        }
    })
}

@Composable
internal fun ToolsAuroraContent() {
    val context = LocalContext.current.applicationContext
    val active = LocalApprovedLauncherPageActive.current
    var outlook by remember { mutableStateOf(ToolsDiscoveryCache.aurora) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableStateOf(0) }
    val refreshGate = remember { ToolsRefreshGate() }
    LaunchedEffect(active, revision) {
        if (!active) return@LaunchedEffect
        if (!refreshGate.isPending(revision) && outlook != null && System.currentTimeMillis() - ToolsDiscoveryCache.auroraAt < 15 * 60_000L) {
            ToolsDiscoveryDiagnostics.cacheHit("aurora")
            return@LaunchedEffect
        }
        loading = true
        message = null
        var usable = false
        ToolsDiscoveryDiagnostics.started("aurora")
        try {
            val result = ToolsWork.load { Aurora.status(context) }
            if (result == null) message = "The outlook couldn’t be updated. Check your connection and weather location, then try again."
            else {
                usable = true
                outlook = result
                ToolsDiscoveryCache.aurora = result
                ToolsDiscoveryCache.auroraAt = System.currentTimeMillis()
                refreshGate.completed(revision)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "The outlook couldn’t be updated. Please try again." }
        finally { ToolsDiscoveryDiagnostics.finished("aurora", usable); loading = false }
    }
    val current = outlook
    ToolsTwoPane(left = {
        ToolsPanel("Tonight’s outlook") {
            Text(current?.headline ?: if (loading) "Checking the K-index…" else "Outlook unavailable",
                color = IosLabel, fontSize = 38.sp, lineHeight = 48.sp, fontWeight = FontWeight.Medium)
            ToolsText(current?.detail ?: "Check the forecast for your location.", true)
            if (message != null) ToolsText(message!!, true)
            ToolsButton(if (loading) "Checking outlook…" else "Refresh outlook", enabled = !loading) { revision++ }
        }
    }, right = {
        ToolsPanel("Conditions") {
            ToolsMetric("Kp now", current?.let { Aurora.fmtKp(it.kpNow) } ?: "—")
            ToolsMetric("Next 24 h peak", current?.let { Aurora.fmtKp(it.kpForecast) } ?: "—")
            ToolsMetric("Look toward", current?.let { if (it.lookToward == "N") "North" else "South" } ?: "—")
            ToolsText("Best after dark, away from city lights, with a clear horizon. Source: NOAA SWPC.", true)
        }
    })
}

@Composable
internal fun ToolsMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 70.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        ToolsText(label, true, Modifier.weight(1f))
        ToolsText(value)
    }
}
