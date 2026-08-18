/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.immortal.launcher.ui.components.Glass
import com.immortal.launcher.ui.components.GlassCard
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.*

/**
 * Full-screen Home Assistant dashboard — 1:1 port of mocks/ipados/08-ha-dashboard.html.
 *
 * Layout: a 330dp house rail (greeting hero + room list + scene grid) and a per-room
 * entity canvas (glow toggles, drag dimmer, thermostat dial, media tile, sparkline sensors).
 * Data via [HaDashboardApi]; tile shapes via [HaDashboardModel]. Entry: launch this activity
 * from [HaSettingsActivity] or the HA widget's "Full dashboard" button.
 */
class HaDashboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PortalPrimeTheme { HaDashboardScreen() } }
    }
}

// ─── colors (from mock 08 + Glass tokens) ──────────────────────────────────────

private val AmberOn = Color(0xF0FFD66E)
private val AmberText = Color(0xFF3D2C00)
private val AmberTextSub = Color(0xA83D2C00)
private val SceneViolet = Color(0x577C4DFF)
private val SceneRim = Color(0x4DA078FF)
private val PresenceGreen = Color(0xFF1DE9B6)
private val HvacOrange = Color(0xFFFF9D5C)
private val SparkCyan = Color(0xFF26C6DA)

// Thermostat arc gradient stops (teal → amber → orange — same as mock's #tgrad)
private val DialGradientColors = listOf(Color(0xFF26C6DA), Color(0xFFFFD66E), Color(0xFFFF9D5C))

// ─── screen ────────────────────────────────────────────────────────────────────

@Composable
private fun HaDashboardScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configured = remember { HaConfig.isConfigured(context) }

    // Selected room id; null = loading / unconfigured
    var selectedRoomId by remember { mutableStateOf<String?>(null) }
    var areas by remember { mutableStateOf<List<HaDashboardApi.Area>>(emptyList()) }

    // Per-room entity details cache: roomId -> list of Detail
    var detailsCache by remember { mutableStateOf<Map<String, List<HaDashboardApi.Detail>>>(emptyMap()) }

    // Optimistic local state for instant tile feedback: entityId -> on/off
    var optimistic by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var optimisticBri by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var optimisticTemp by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }

    // History cache: entityId -> list of Float
    var historyCache by remember { mutableStateOf<Map<String, List<Float>>>(emptyMap()) }

    // Now-playing
    var nowPlaying by remember { mutableStateOf(NowPlayingHub.current) }
    DisposableEffect(Unit) {
        val l = NowPlayingHub.Listener { s -> nowPlaying = s }
        NowPlayingHub.addListener(l)
        onDispose { NowPlayingHub.removeListener(l) }
    }

    // Load areas once on start
    LaunchedEffect(configured) {
        if (!configured) return@LaunchedEffect
        val fetched = withContext(Dispatchers.IO) { HaDashboardApi.fetchAreas(context) }
        areas = fetched
        selectedRoomId = fetched.firstOrNull()?.id
    }

    // Load details when room changes, poll every 30s
    val roomId = selectedRoomId
    val roomEntityIds = remember(roomId, areas) {
        areas.find { it.id == roomId }?.entityIds ?: emptyList()
    }
    LaunchedEffect(roomId, roomEntityIds) {
        if (roomId == null || roomEntityIds.isEmpty()) return@LaunchedEffect
        while (true) {
            val details = withContext(Dispatchers.IO) { HaDashboardApi.fetchDetails(context, roomEntityIds) }
            detailsCache = detailsCache + (roomId to details)
            optimistic = emptyMap()
            optimisticBri = emptyMap()
            optimisticTemp = emptyMap()
            // Kick off history for sensors in this room (fire and forget)
            details.filter { it.domain == "sensor" }.forEach { d ->
                if (!historyCache.containsKey(d.entityId)) {
                    launch(Dispatchers.IO) {
                        val hist = HaDashboardApi.fetchHistory(context, d.entityId)
                        if (hist.isNotEmpty()) historyCache = historyCache + (d.entityId to hist)
                    }
                }
            }
            delay(30_000L)
        }
    }

    Box(Modifier.fillMaxSize()) {
        DuskAuroraBackground()

        if (!configured) {
            NotConfigured()
        } else {
            val currentDetails = detailsCache[roomId] ?: emptyList()
            val tiles = HaDashboardModel.tilesFor(currentDetails)

            Row(
                modifier = Modifier.fillMaxSize().padding(top = 42.dp, start = 34.dp, end = 34.dp, bottom = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(26.dp)
            ) {
                // ── house rail ──
                HouseRail(
                    areas = areas,
                    selectedId = roomId,
                    allDetails = detailsCache,
                    nowPlaying = nowPlaying,
                    presence = PresenceHub.current,
                    modifier = Modifier.width(330.dp).fillMaxHeight(),
                    onRoomSelected = { id ->
                        selectedRoomId = id
                        optimistic = emptyMap(); optimisticBri = emptyMap(); optimisticTemp = emptyMap()
                    },
                    onScene = { entityId ->
                        scope.launch { withContext(Dispatchers.IO) { HaClient.activate(context, entityId) } }
                    }
                )

                // ── room canvas ──
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    RoomHeader(
                        name = areas.find { it.id == roomId }?.name ?: "",
                        details = currentDetails
                    )
                    RoomGrid(
                        tiles = tiles,
                        optimistic = optimistic,
                        optimisticBri = optimisticBri,
                        optimisticTemp = optimisticTemp,
                        historyCache = historyCache,
                        nowPlaying = nowPlaying,
                        onToggle = { entityId, curOn ->
                            optimistic = optimistic + (entityId to !curOn)
                            scope.launch { withContext(Dispatchers.IO) { HaClient.toggle(context, entityId) } }
                        },
                        onBrightness = { entityId, pct ->
                            optimisticBri = optimisticBri + (entityId to pct)
                            optimistic = optimistic + (entityId to (pct > 0))
                            scope.launch { withContext(Dispatchers.IO) { HaDashboardApi.setBrightness(context, entityId, pct) } }
                        },
                        onTempStep = { entityId, delta, curTarget ->
                            val next = curTarget + delta
                            optimisticTemp = optimisticTemp + (entityId to next)
                            scope.launch { withContext(Dispatchers.IO) { HaDashboardApi.setTemperature(context, entityId, next) } }
                        },
                        onMediaAction = { action ->
                            when (action) {
                                MediaAction.PLAY_PAUSE -> NowPlayingHub.playPause()
                                MediaAction.NEXT -> NowPlayingHub.next()
                                MediaAction.PREVIOUS -> NowPlayingHub.previous()
                            }
                        }
                    )
                }
            }
        }
    }
}

// ─── house rail ────────────────────────────────────────────────────────────────

@Composable
private fun HouseRail(
    areas: List<HaDashboardApi.Area>,
    selectedId: String?,
    allDetails: Map<String, List<HaDashboardApi.Detail>>,
    nowPlaying: NowPlayingState?,
    presence: PresenceState?,
    modifier: Modifier = Modifier,
    onRoomSelected: (String) -> Unit,
    onScene: (String) -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Hero card
        GlassCard(Modifier.fillMaxWidth().wrapContentHeight()) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("GOOD EVENING", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
                Text("Home", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp)
                Spacer(Modifier.height(10.dp))
                val totalLit = allDetails.values.flatten().let { HaDashboardModel.litCount(it) }
                Text("$totalLit lights on", color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp)
                // Presence chip
                if (presence?.presence == Presence.PRESENT) {
                    Spacer(Modifier.height(8.dp))
                    PresenceChip()
                }
            }
        }

        // Room list
        GlassCard(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.padding(10.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                areas.forEach { area ->
                    val details = allDetails[area.id] ?: emptyList()
                    val lit = HaDashboardModel.litCount(details)
                    RoomRow(
                        name = area.name,
                        litCount = lit,
                        selected = area.id == selectedId,
                        onClick = { onRoomSelected(area.id) }
                    )
                }
            }
        }

        // Scene grid — show scenes from ALL areas (they live in HA globally)
        // In a real device these would come from fetchAreas + scene entities;
        // here we show 4 static well-known scenes as mock fidelity requires
        val staticScenes = listOf(
            "scene.evening" to "Evening",
            "scene.night" to "Night",
            "scene.movie" to "Movie",
            "scene.leave" to "Leave",
        )
        GlassCard(Modifier.fillMaxWidth().wrapContentHeight()) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                Text("SCENES", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
                Spacer(Modifier.height(11.dp))
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    staticScenes.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            pair.forEach { (id, label) ->
                                SceneChip(label = label, modifier = Modifier.weight(1f)) { onScene(id) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresenceChip() {
    val alpha by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(1200, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "dot"
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(PresenceGreen.copy(alpha = 0.1f))
            .border(0.5.dp, PresenceGreen.copy(alpha = 0.28f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(PresenceGreen.copy(alpha = alpha)))
        Text("Present · home", color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun RoomRow(name: String, litCount: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color.White.copy(alpha = 0.11f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (litCount > 0) {
            Text("$litCount", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
            Box(Modifier.size(7.dp).clip(CircleShape).background(AmberOn).drawBehind {
                drawCircle(color = AmberOn.copy(alpha = 0.5f), radius = size.minDimension)
            })
        } else {
            Text("—", color = Color.White.copy(alpha = 0.3f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun SceneChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var flashing by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        if (flashing) SceneViolet.copy(alpha = 0.85f) else SceneViolet,
        animationSpec = tween(300), label = "scene"
    )
    val scope = rememberCoroutineScope()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(0.5.dp, SceneRim, RoundedCornerShape(12.dp))
            .clickable {
                scope.launch { flashing = true; delay(500); flashing = false }
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ─── room canvas ───────────────────────────────────────────────────────────────

@Composable
private fun RoomHeader(name: String, details: List<HaDashboardApi.Detail>) {
    val lit = HaDashboardModel.litCount(details)
    val temp = details.firstOrNull { it.domain == "sensor" && it.unit.contains("°") }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(name, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp)
        val sub = buildString {
            append("$lit light${if (lit == 1) "" else "s"} on")
            if (temp != null) append(" · ${temp.state}°")
        }
        Text(sub, color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
    }
}

private enum class MediaAction { PLAY_PAUSE, NEXT, PREVIOUS }

@Composable
private fun RoomGrid(
    tiles: List<HaDashboardModel.Tile>,
    optimistic: Map<String, Boolean>,
    optimisticBri: Map<String, Int>,
    optimisticTemp: Map<String, Float>,
    historyCache: Map<String, List<Float>>,
    nowPlaying: NowPlayingState?,
    onToggle: (String, Boolean) -> Unit,
    onBrightness: (String, Int) -> Unit,
    onTempStep: (String, Float, Float) -> Unit,
    onMediaAction: (MediaAction) -> Unit,
) {
    // Simple fixed grid: tiles decide their own column span via weight/size.
    // We use a LazyVerticalGrid equivalent built from Column/Rows so we can
    // mix span-1 and span-2 tiles without the LazyGrid's fixed-column constraint.
    val rows = buildRows(tiles)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { tile ->
                    val span = if (tile.kind == HaDashboardModel.Kind.DIMMER ||
                        tile.kind == HaDashboardModel.Kind.MEDIA ||
                        tile.kind == HaDashboardModel.Kind.CLIMATE) 2 else 1
                    val weight = if (row.size == 1 && span == 2) 1f else span.toFloat()
                    EntityTile(
                        tile = tile,
                        optimistic = optimistic,
                        optimisticBri = optimisticBri,
                        optimisticTemp = optimisticTemp,
                        history = historyCache[tile.detail.entityId] ?: emptyList(),
                        nowPlaying = nowPlaying,
                        modifier = Modifier.weight(weight).fillMaxHeight(),
                        onToggle = onToggle,
                        onBrightness = onBrightness,
                        onTempStep = onTempStep,
                        onMediaAction = onMediaAction,
                    )
                }
                // Pad odd rows with a spacer if there's only one narrow tile
                if (row.size == 1 && tiles.count { narrowKind(it.kind) } == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

// Lay tiles into rows: big tiles (span-2) get their own row; narrow tiles pair up.
private fun buildRows(tiles: List<HaDashboardModel.Tile>): List<List<HaDashboardModel.Tile>> {
    val rows = mutableListOf<List<HaDashboardModel.Tile>>()
    val narrow = mutableListOf<HaDashboardModel.Tile>()
    tiles.forEach { t ->
        if (narrowKind(t.kind)) {
            narrow.add(t)
            if (narrow.size == 2) { rows.add(narrow.toList()); narrow.clear() }
        } else {
            if (narrow.size == 1) { rows.add(narrow.toList()); narrow.clear() }
            rows.add(listOf(t))
        }
    }
    if (narrow.isNotEmpty()) rows.add(narrow.toList())
    return rows
}

private fun narrowKind(k: HaDashboardModel.Kind) = k == HaDashboardModel.Kind.TOGGLE || k == HaDashboardModel.Kind.SENSOR

// ─── entity tiles ──────────────────────────────────────────────────────────────

@Composable
private fun EntityTile(
    tile: HaDashboardModel.Tile,
    optimistic: Map<String, Boolean>,
    optimisticBri: Map<String, Int>,
    optimisticTemp: Map<String, Float>,
    history: List<Float>,
    nowPlaying: NowPlayingState?,
    modifier: Modifier = Modifier,
    onToggle: (String, Boolean) -> Unit,
    onBrightness: (String, Int) -> Unit,
    onTempStep: (String, Float, Float) -> Unit,
    onMediaAction: (MediaAction) -> Unit,
) {
    val d = tile.detail
    when (tile.kind) {
        HaDashboardModel.Kind.DIMMER -> DimmerTile(d, optimistic, optimisticBri, modifier, onToggle, onBrightness)
        HaDashboardModel.Kind.CLIMATE -> ClimateTile(d, optimisticTemp, modifier, onTempStep)
        HaDashboardModel.Kind.MEDIA -> MediaTile(d, nowPlaying, modifier, onMediaAction)
        HaDashboardModel.Kind.TOGGLE -> ToggleTile(d, optimistic, modifier, onToggle)
        HaDashboardModel.Kind.SENSOR -> SensorTile(d, history, modifier)
    }
}

@Composable
private fun ToggleTile(
    d: HaDashboardApi.Detail,
    optimistic: Map<String, Boolean>,
    modifier: Modifier = Modifier,
    onToggle: (String, Boolean) -> Unit,
) {
    val on = optimistic[d.entityId] ?: d.state.equals("on", true)
    val bg by animateColorAsState(if (on) AmberOn else Glass.Tint, tween(350), label = "tog")
    val fg = if (on) AmberText else Color.White
    val fgSub = if (on) AmberTextSub else Glass.TextSecondary
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Glass.Radius))
            .background(bg)
            .border(0.5.dp, if (on) Color.Transparent else Glass.Border, RoundedCornerShape(Glass.Radius))
            .clickable { onToggle(d.entityId, on) }
            .padding(horizontal = 17.dp, vertical = 17.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(if (on) AmberText.copy(0.12f) else Glass.SubTint))
        Column {
            Text(d.friendlyName, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(if (on) "On" else "Off", color = fgSub, fontSize = 12.sp)
        }
    }
}

@Composable
private fun DimmerTile(
    d: HaDashboardApi.Detail,
    optimistic: Map<String, Boolean>,
    optimisticBri: Map<String, Int>,
    modifier: Modifier = Modifier,
    onToggle: (String, Boolean) -> Unit,
    onBrightness: (String, Int) -> Unit,
) {
    val on = optimistic[d.entityId] ?: d.state.equals("on", true)
    val pct = optimisticBri[d.entityId] ?: d.brightnessPct ?: if (on) 62 else 0
    val bg by animateColorAsState(if (on) AmberOn else Glass.Tint, tween(350), label = "dim")
    val fg = if (on) AmberText else Color.White
    val fgSub = if (on) AmberTextSub else Glass.TextSecondary
    val sliderBg = if (on) AmberText.copy(0.14f) else Glass.Chrome
    val sliderFill = if (on) AmberText.copy(0.28f) else Color.White.copy(alpha = 0.38f)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Glass.Radius))
            .background(bg)
            .border(0.5.dp, if (on) Color.Transparent else Glass.Border, RoundedCornerShape(Glass.Radius))
            .padding(horizontal = 17.dp, vertical = 17.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().clickable { onToggle(d.entityId, on) },
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(if (on) AmberText.copy(0.12f) else Glass.SubTint))
            Column {
                Text(d.friendlyName, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(if (on) "$pct%" else "Off", color = fgSub, fontSize = 12.sp)
            }
        }
        // Drag slider
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
            var dragging by remember { mutableStateOf(false) }
            var localPct by remember(pct) { mutableStateOf(pct.toFloat()) }
            Box(
                modifier = Modifier.fillMaxWidth().height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(sliderBg)
                    .pointerInput(d.entityId, on) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragging = true },
                            onDragEnd = { dragging = false; onBrightness(d.entityId, localPct.roundToInt()) },
                            onHorizontalDrag = { _, dragAmount ->
                                val newPct = (localPct + dragAmount / size.width * 100).coerceIn(0f, 100f)
                                localPct = newPct
                                onBrightness(d.entityId, newPct.roundToInt())
                            }
                        )
                    }
            ) {
                Box(
                    Modifier.fillMaxHeight()
                        .fillMaxWidth(localPct / 100f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(sliderFill)
                )
                Text(
                    "${localPct.roundToInt()}%",
                    color = fg,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun ClimateTile(
    d: HaDashboardApi.Detail,
    optimisticTemp: Map<String, Float>,
    modifier: Modifier = Modifier,
    onTempStep: (String, Float, Float) -> Unit,
) {
    val target = optimisticTemp[d.entityId] ?: d.targetTemp ?: 70f
    val current = d.currentTemp ?: target
    val action = d.hvacAction?.lowercase() ?: "idle"
    val actionLabel = action.replaceFirstChar { it.uppercaseChar() }

    GlassCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().padding(17.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            // Dial
            ThermostatDial(current = current, target = target, label = d.friendlyName)

            // Steppers
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                TempButton("−") { onTempStep(d.entityId, -1f, target) }
                Text("${target.toInt()}°", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                TempButton("+") { onTempStep(d.entityId, +1f, target) }
            }

            // HVAC status
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val dotAlpha by rememberInfiniteTransition(label = "hvac").animateFloat(
                    1f, 0.4f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "dot"
                )
                Box(Modifier.size(8.dp).clip(CircleShape).background(HvacOrange.copy(dotAlpha)))
                Text(
                    "${actionLabel.uppercase()} TO ${target.toInt()}°",
                    color = HvacOrange, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp
                )
            }
        }
    }
}

@Composable
private fun ThermostatDial(current: Float, target: Float, label: String) {
    // The arc covers 270° (from 135° to 405°), like the mock's 0.75-of-circle design.
    val arcFraction by animateFloatAsState(
        ((target - 50f) / 30f).coerceIn(0f, 1f) * 0.75f,
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "arc"
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(180.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 11.dp.toPx()
            val inset = stroke / 2f
            val startAngle = 135f
            val sweepMax = 270f

            // Track
            drawArc(
                color = Color.White.copy(alpha = 0.12f),
                startAngle = startAngle, sweepAngle = sweepMax,
                useCenter = false,
                topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            // Filled arc with gradient
            val brush = Brush.sweepGradient(DialGradientColors)
            drawArc(
                brush = brush,
                startAngle = startAngle, sweepAngle = sweepMax * arcFraction,
                useCenter = false,
                topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${current.let { if (it == it.toInt().toFloat()) it.toInt().toString() else "%.1f".format(it) }}°",
                color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Light, letterSpacing = (-1.5).sp)
            Text(label, color = Glass.TextSecondary, fontSize = 11.sp, letterSpacing = 1.2.sp)
        }
    }
}

@Composable
private fun TempButton(label: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(44.dp).clip(CircleShape)
            .border(0.5.dp, Glass.Border, CircleShape)
            .background(Glass.Chrome)
            .clickable { onClick() }
    ) {
        Text(label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun MediaTile(
    d: HaDashboardApi.Detail,
    nowPlaying: NowPlayingState?,
    modifier: Modifier = Modifier,
    onMediaAction: (MediaAction) -> Unit,
) {
    val title = nowPlaying?.title?.ifBlank { d.mediaTitle } ?: d.mediaTitle ?: "Nothing playing"
    val artist = nowPlaying?.artist?.ifBlank { d.mediaArtist } ?: d.mediaArtist ?: d.friendlyName
    val playing = nowPlaying?.state == PlaybackState.PLAYING

    GlassCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Album art placeholder — gradient blob + animated EQ bars
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF7C4DFF), Color(0xFF26C6DA))))
            ) {
                if (playing) {
                    EqBars(Modifier.align(Alignment.BottomStart).padding(8.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(artist, color = Glass.TextSecondary, fontSize = 12.sp, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    MediaButton("⏮") { onMediaAction(MediaAction.PREVIOUS) }
                    MediaButton(if (playing) "⏸" else "▶") { onMediaAction(MediaAction.PLAY_PAUSE) }
                    MediaButton("⏭") { onMediaAction(MediaAction.NEXT) }
                }
            }
        }
    }
}

@Composable
private fun EqBars(modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "eq")
    val h1 by inf.animateFloat(5f, 14f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "b1")
    val h2 by inf.animateFloat(3f, 12f, infiniteRepeatable(tween(600, 120), RepeatMode.Reverse), label = "b2")
    val h3 by inf.animateFloat(4f, 15f, infiniteRepeatable(tween(600, 250), RepeatMode.Reverse), label = "b3")
    val h4 by inf.animateFloat(5f, 11f, infiniteRepeatable(tween(600, 400), RepeatMode.Reverse), label = "b4")
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        listOf(h1, h2, h3, h4).forEach { h ->
            Box(Modifier.width(3.dp).height(h.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
        }
    }
}

@Composable
private fun MediaButton(label: String, onClick: () -> Unit) {
    Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 18.sp,
        modifier = Modifier.clickable { onClick() }.padding(2.dp))
}

@Composable
private fun SensorTile(
    d: HaDashboardApi.Detail,
    history: List<Float>,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier) {
        Box(Modifier.fillMaxSize().padding(horizontal = 17.dp, vertical = 17.dp)) {
            Column(Modifier.align(Alignment.TopStart)) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Glass.SubTint))
                Spacer(Modifier.height(8.dp))
                Text(d.friendlyName, color = Glass.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.align(Alignment.BottomStart)) {
                Text(
                    buildString {
                        append(d.state)
                        if (d.unit.isNotBlank()) append(" ")
                    },
                    color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.8).sp
                )
                if (d.unit.isNotBlank()) {
                    Text(d.unit, color = Glass.TextSecondary, fontSize = 13.sp)
                }
            }
            if (history.size >= 2) {
                Sparkline(history, Modifier.align(Alignment.BottomEnd).width(60.dp).height(36.dp).padding(bottom = 4.dp))
            }
        }
    }
}

@Composable
private fun Sparkline(values: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val min = values.min(); val max = values.max()
        val span = (max - min).coerceAtLeast(maxOf(kotlin.math.abs(max) * 0.06f, 1f))
        val mid = (min + max) / 2f
        val pts = values.mapIndexed { i, v ->
            Offset(
                x = i / (values.size - 1).toFloat() * size.width,
                y = size.height / 2f - ((v - mid) / span) * (size.height * 0.7f)
            )
        }
        // Fill
        val path = Path().apply {
            moveTo(pts.first().x, size.height)
            pts.forEach { lineTo(it.x, it.y) }
            lineTo(pts.last().x, size.height); close()
        }
        drawPath(path, Brush.verticalGradient(listOf(SparkCyan.copy(0.35f), SparkCyan.copy(0f))))
        // Line
        for (i in 0 until pts.size - 1) {
            drawLine(SparkCyan, pts[i], pts[i + 1], strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun NotConfigured() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        GlassCard(Modifier.width(360.dp).wrapContentHeight()) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Home Assistant", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("Configure a Home Assistant URL and long-lived access token in Settings → Home Assistant to use the dashboard.", color = Glass.TextSecondary, fontSize = 14.sp)
            }
        }
    }
}
