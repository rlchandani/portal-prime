/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import com.immortal.launcher.ui.theme.LocalAdvancedSettings
import com.immortal.launcher.settings.SettingsDomains
import org.json.JSONObject


import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HaSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entities = intent.getStringExtra(APPROVED_SETTINGS_PAGE) == "ha-entities"
        setContent {
            PortalPrimeTheme(darkTheme = false) {
                SettingsScaffold(title = if (entities) "Choose Home Assistant entities" else "Home dashboard",
                    onBack = { finish() }) { ApprovedEditorsHaContent(entities) }
            }
        }
    }
}

@Composable
internal fun HaSettingsContent() { ApprovedEditorsHaContent(entitiesOnly = false) }

@Composable
private fun ApprovedEditorsHaContent(entitiesOnly: Boolean) {
    val context = LocalContext.current
    if (!entitiesOnly && HomeDashboardSettings()) return
    val back = rememberApprovedSettingsBack()
    val scope = rememberCoroutineScope()
    val requests = remember { ApprovedEditorsRequestGate() }
    var connectionJob by remember { mutableStateOf<Job?>(null) }
    var url by remember { mutableStateOf(HaConfig.baseUrl(context)) }
    // Secrets never get copied into an editable field just to display the configured state.
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var discovered by remember { mutableStateOf<List<ApprovedEditorsEntity>>(emptyList()) }
    var pinned by remember { mutableStateOf(HaConfig.entities(context)) }
    var query by remember { mutableStateOf("") }
    var expandedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    var groups by remember { mutableStateOf<List<ApprovedEditorsHaGroup>>(emptyList()) }
    var filtering by remember { mutableStateOf(false) }
    var removal by remember { mutableStateOf<HaConfig.Entity?>(null) }
    val pinnedIds = remember(pinned) { pinned.map { it.entityId }.toSet() }

    fun cancelConnection() {
        if (busy) status = ""
        requests.cancel(); connectionJob?.cancel(); connectionJob = null; busy = false
    }
    fun discover(saveDraft: Boolean) {
        val candidateUrl = if (saveDraft) url.trim().trimEnd('/') else HaConfig.baseUrl(context)
        val candidateToken = if (saveDraft) approvedEditorsCredential(token, HaConfig.token(context))
            else HaConfig.token(context)
        if (!approvedEditorsHttpUrl(candidateUrl) || candidateToken.isBlank()) {
            error = "Enter your Home Assistant http:// or https:// address and a long-lived token."
            return
        }
        cancelConnection()
        val request = requests.begin()
        busy = true; error = null; status = "Connecting…"
        connectionJob = scope.launch {
            try {
                val result = approvedEditorsDiscoverHa(candidateUrl, candidateToken)
                if (!requests.isCurrent(request)) return@launch
                busy = false
                if (result == null) {
                    error = "Couldn't reach Home Assistant — check URL and token"
                    status = ""; discovered = emptyList()
                } else {
                    if (saveDraft) {
                        // No suspension between the cancellation check and this atomic Config write.
                        HaConfig.setConnection(context, candidateUrl, candidateToken)
                        url = HaConfig.baseUrl(context); token = ""
                    }
                    discovered = result
                    status = "Connected ✓ — ${result.size} controllable entities found"
                    if (saveDraft) {
                        ApprovedEditorsHaTransfer.publish(candidateUrl, candidateToken, result)
                        context.startActivity(approvedSettingsIntent(context, "ha-entities"))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requests.isCurrent(request)) {
                    busy = false; status = ""
                    error = "Couldn't reach Home Assistant — check URL and token"
                }
            }
        }
    }
    val lifecycle = (context as? ComponentActivity)?.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) cancelConnection()
            if (event == Lifecycle.Event.ON_RESUME) pinned = HaConfig.entities(context)
        }
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
            requests.cancel(); connectionJob?.cancel()
        }
    }
    LaunchedEffect(entitiesOnly) {
        if (entitiesOnly && HaConfig.isConfigured(context)) {
            val transferred = ApprovedEditorsHaTransfer.take(HaConfig.baseUrl(context), HaConfig.token(context))
            if (transferred == null) discover(saveDraft = false)
            else {
                discovered = transferred
                status = "Connected ✓ — ${transferred.size} controllable entities found"
            }
        }
    }
    // The immutable indexed names are built during IO discovery. Search/grouping is computed only
    // when inputs change, off main, with cancelled recomputations unable to publish old matches.
    LaunchedEffect(discovered, query, expandedGroups) {
        filtering = true
        groups = withContext(Dispatchers.Default) {
            approvedEditorsHaGroups(discovered, query, expandedGroups)
        }
        filtering = false
    }

    Column {
        if (!entitiesOnly) {
            ApprovedEditorsForm(actions = {
                ApprovedEditorsActions("Save & connect", error, enabled = !busy,
                    onCancel = {
                        cancelConnection(); url = HaConfig.baseUrl(context); token = ""; error = null; status = ""
                        back()
                    }) { discover(saveDraft = true) }
                if (status.isNotEmpty()) FooterNote(status)
            }) {
                ApprovedEditorsField("Base URL", url, { url = it; error = null; status = "" },
                    placeholder = "http://homeassistant.local:8123", enabled = !busy)
                Divider()
                ApprovedEditorsField("Long-lived token", token, { token = it; error = null; status = "" },
                    placeholder = if (HaConfig.token(context).isBlank()) "Enter a new value"
                        else "Leave blank to keep your saved token", secret = true, enabled = !busy)
            }
            FooterNote("In Home Assistant: profile → Long-lived access tokens → Create. " +
                "The connection is saved after the server responds successfully.")
            SectionLabel("More options")
            Card {
                NavRow("Choose Home Assistant entities") {
                    cancelConnection()
                    context.startActivity(approvedSettingsIntent(context, "ha-entities"))
                }
                Divider()
                NavRow("Home Assistant dashboard") {
                    cancelConnection()
                    context.startActivity(android.content.Intent(context, HaDashboardActivity::class.java))
                }
            }
        } else {
            if (!HaConfig.isConfigured(context)) {
                Card { ApprovedEditorsEmpty("Set up your Home Assistant connection before searching for entities.") }
                ApprovedSettingsTextButton("Set up Home Assistant") {
                    context.startActivity(approvedSettingsIntent(context, "ha"))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ApprovedEditorsButton(if (busy) "Discovering…" else "Refresh entities", enabled = !busy) {
                        discover(saveDraft = false)
                    }
                    if (busy) ApprovedEditorsButton("Cancel") { cancelConnection(); status = "" }
                }
                if (status.isNotEmpty()) FooterNote(status)
                error?.let { FooterNote(it) }
            }
        }

        if (entitiesOnly) {
            ApprovedEditorsCollection("Pinned to widget", pinned, key = { it.entityId },
                empty = "No entities selected.") { entity ->
                ApprovedEditorsCollectionRow(entity.name, entity.entityId, onRemove = { removal = entity })
            }
        }
        if (entitiesOnly && discovered.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Card {
                ApprovedEditorsField("Search ${discovered.size} entities", query, { query = it },
                    placeholder = "Search by name or entity id")
            }
            Spacer(Modifier.height(24.dp))
            Card {
                if (filtering) ApprovedEditorsEmpty("Searching…")
                else if (groups.isEmpty()) ApprovedEditorsEmpty("No entities match \"$query\".")
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
                    groups.forEach { group ->
                        item(key = "group:${group.domain}", contentType = "group") {
                            ApprovedEditorsHaGroupRow(group, searching = query.isNotBlank()) {
                                expandedGroups = if (group.domain in expandedGroups)
                                    expandedGroups - group.domain else expandedGroups + group.domain
                            }
                        }
                        items(group.shown, key = { "entity:${it.id}" }, contentType = { "entity" }) { entity ->
                            Divider()
                            val already = entity.id in pinnedIds
                            val pin = {
                                HaConfig.addEntity(context, entity.id, entity.name)
                                pinned = HaConfig.entities(context)
                            }
                            val interaction = if (already) Modifier else Modifier.tvFocusableRow(
                                focusFill = IosFocusTint, onClick = pin)
                            Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 100.dp)
                                .then(interaction)
                                .padding(horizontal = 28.dp, vertical = 21.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(entity.name, color = IosLabel, fontSize = 26.sp, lineHeight = 34.sp)
                                    Text(entity.id, color = IosSecondary, fontSize = 21.sp, lineHeight = 29.sp)
                                }
                                Text(if (already) "Pinned ✓" else "+ Pin",
                                    color = if (already) IosSecondary else IosBlue,
                                    fontSize = 24.sp, lineHeight = 32.sp)
                            }
                        }
                        if (group.hidden > 0) item(key = "more:${group.domain}", contentType = "hint") {
                            ApprovedEditorsEmpty("+${group.hidden} more — search to narrow")
                        }
                        item(key = "end:${group.domain}", contentType = "divider") { Divider() }
                    }
                }
            }
        }
    }
    removal?.let { entity ->
        ApprovedEditorsRemoveDialog(entity.name, onCancel = { removal = null }) {
            HaConfig.removeEntity(context, entity.entityId); removal = null
            pinned = HaConfig.entities(context)
        }
    }
}

/** The ordinary page contains the web dashboard; native API controls remain in Advanced. */
@Composable
private fun HomeDashboardSettings(): Boolean {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(HomeDashboardConfig.load(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    Column {
        SettingsList(SettingsDomains.homeDashboard, snapshot, onApply = { key, value ->
            val applied = SettingsDomains.homeDashboard.apply(context, JSONObject().put(key, value))
            error = if (key in applied) null else "Enter a complete http:// or https:// dashboard address."
            snapshot = HomeDashboardConfig.load(context)
        })
        error?.let { FooterNote(it) }
        if (snapshot.url.isNotBlank()) {
            Card {
                NavRow("Open dashboard") { HomeDashboardActivity.openDashboard(context) }
            }
        }
        FooterNote("Your dashboard opens in the Portal browser and keeps its saved sign-in. " +
            "It runs when you open it, keeping Home lightweight.")
    }
    return !LocalAdvancedSettings.current
}

@Composable
private fun ApprovedEditorsHaGroupRow(
    group: ApprovedEditorsHaGroup, searching: Boolean, onClick: () -> Unit,
) {
    val interaction = if (searching) Modifier else Modifier.tvFocusableRow(
        focusFill = IosFocusTint, onClick = onClick)
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 100.dp).then(interaction)
        .padding(horizontal = 28.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("${haGroupLabel(group.domain)} · ${group.total}", color = IosLabel,
            fontSize = 26.sp, lineHeight = 34.sp, modifier = Modifier.weight(1f))
        Text(if (group.expanded) "Collapse" else "Expand", color = IosSecondary,
            fontSize = 24.sp, lineHeight = 32.sp)
    }
}

/** Friendly, pluralized section heading for an HA domain. */
private fun haGroupLabel(domain: String): String =
    when (domain) {
      "light" -> "Lights"
      "switch" -> "Switches"
      "fan" -> "Fans"
      "scene" -> "Scenes"
      "script" -> "Scripts"
      "automation" -> "Automations"
      "input_boolean" -> "Toggles"
      "climate" -> "Climate"
      "cover" -> "Covers"
      "media_player" -> "Media players"
      "binary_sensor" -> "Binary sensors"
      "sensor" -> "Sensors"
      "person" -> "People"
      "device_tracker" -> "Device trackers"
      "weather" -> "Weather"
      "sun" -> "Sun"
      else -> domain.replaceFirstChar { it.uppercase() }
    }
