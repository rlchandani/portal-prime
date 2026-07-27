/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher.mock

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── iOS 26 Liquid Glass palette ───────────────────────────────────────────────
private val GlassFill      = Color(0x28FFFFFF) // 16% white frosted surface
private val GlassBorder    = Color(0x40FFFFFF) // 25% white inner border
private val GlassSpecular  = Color(0x20FFFFFF) // 12% white specular highlight
private val DockSurface    = Color(0x50000000) // 31% black dock base
private val TextPrimary    = Color(0xFFFFFFFF)
private val TextSecondary  = Color(0xCCFFFFFF) // 80% white
private val TextTertiary   = Color(0x80FFFFFF) // 50% white

// Lazy singleton — avoids re-allocating a bitmap on every recomposition
private val FALLBACK_BITMAP: ImageBitmap by lazy {
    android.graphics.Bitmap
        .createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
        .also { bm ->
            android.graphics.Canvas(bm)
                .drawColor(android.graphics.Color.argb(60, 200, 200, 255))
        }
        .asImageBitmap()
}

data class AppInfo(val label: String, val icon: ImageBitmap)

// ── Activity ──────────────────────────────────────────────────────────────────

class IosHomeMockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PortalPrimeTheme {
                IosHomeScreen()
            }
        }
    }
}

// ── Root screen ───────────────────────────────────────────────────────────────

@Composable
private fun IosHomeScreen() {
    val context = LocalContext.current
    val apps by produceState(emptyList<AppInfo>()) {
        value = withContext(Dispatchers.IO) { loadInstalledApps(context) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF1A3A6B), // center: deep royal blue
                        Color(0xFF0A1628), // mid: midnight blue
                        Color(0xFF0A0A1A), // edge: near-black
                    ),
                    radius = 1400f,
                )
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            StatusBar()

            // ── Two-zone content area ────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // LEFT — widget column (40%)
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.4f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    WeatherWidget(modifier = Modifier.fillMaxWidth())
                    GreetingWidget(modifier = Modifier.fillMaxWidth())
                    NowPlayingWidget(modifier = Modifier.fillMaxWidth())
                }

                // RIGHT — icon grid (60%)
                AppIconGrid(
                    apps = apps,
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.6f),
                )
            }

            Dock(apps = apps.take(5))
        }
    }
}

// ── Status bar ────────────────────────────────────────────────────────────────

@Composable
private fun StatusBar() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 28.dp),
    ) {
        Text(
            text = "4:15 PM",
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Text(
            text = "Thursday, July 23",
            color = TextSecondary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.align(Alignment.Center),
        )
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("☀️ 73°", color = TextPrimary, fontSize = 16.sp)
            Text("WiFi", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("🔋", fontSize = 16.sp)
        }
    }
}

// ── Left-zone widgets ─────────────────────────────────────────────────────────

@Composable
private fun WeatherWidget(modifier: Modifier = Modifier) {
    LiquidGlassCard(modifier = modifier.height(184.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column {
                    Text(
                        "Edmonds, WA",
                        color = TextTertiary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.5.sp,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "73°",
                        color = TextPrimary,
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Thin,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("☀️", fontSize = 30.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Sunny", color = TextSecondary, fontSize = 14.sp)
                    Text("H: 76°  L: 62°", color = TextTertiary, fontSize = 12.sp)
                }
            }

            // 5-day forecast strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf("MON" to "72", "TUE" to "68", "WED" to "65", "THU" to "71", "FRI" to "75")
                    .forEach { (day, temp) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(day, color = TextTertiary, fontSize = 10.sp, letterSpacing = 0.5.sp)
                            Text("☀", fontSize = 14.sp, color = TextSecondary)
                            Text("$temp°", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
            }
        }
    }
}

@Composable
private fun GreetingWidget(modifier: Modifier = Modifier) {
    LiquidGlassCard(modifier = modifier.height(120.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Good afternoon, Rohit",
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Thursday, July 23, 2026",
                color = TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Light,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Edmonds  ·  73°  ·  Sunny",
                color = TextTertiary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun NowPlayingWidget(modifier: Modifier = Modifier) {
    LiquidGlassCard(modifier = modifier.height(100.dp)) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x30FFFFFF)),
                contentAlignment = Alignment.Center,
            ) {
                Text("♪", fontSize = 24.sp, color = TextSecondary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Nothing playing",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "Open a music app to start",
                    color = TextTertiary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

// ── Right-zone icon grid ──────────────────────────────────────────────────────

@Composable
private fun AppIconGrid(apps: List<AppInfo>, modifier: Modifier = Modifier) {
    val displayApps = if (apps.isEmpty()) placeholderApps() else apps.take(20)

    // Constrain grid width so cells stay icon-sized regardless of right-zone width
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxHeight(),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(displayApps) { app -> AppIconCell(app) }
        }
    }
}

@Composable
private fun AppIconCell(app: AppInfo) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(GlassFill)
                .border(1.dp, GlassBorder, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = app.icon,
                contentDescription = app.label,
                modifier = Modifier
                    .size(68.dp)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop,
            )
            // Specular highlight — top-left arc
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .align(Alignment.TopCenter)
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .background(
                        Brush.verticalGradient(listOf(GlassSpecular, Color(0x00FFFFFF)))
                    ),
            )
        }
        Text(
            text = app.label,
            color = TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(88.dp),
        )
    }
}

// ── Dock ──────────────────────────────────────────────────────────────────────

@Composable
private fun Dock(apps: List<AppInfo>) {
    val dockApps = if (apps.isEmpty()) placeholderApps().take(5) else apps

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .background(
                Brush.verticalGradient(listOf(Color(0x00000000), Color(0x50000000)))
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(28.dp))
                .background(DockSurface)
                .border(1.dp, GlassBorder, RoundedCornerShape(28.dp))
                .padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            dockApps.forEach { app -> DockIcon(app) }
        }
    }
}

@Composable
private fun DockIcon(app: AppInfo) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(GlassFill)
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = app.icon,
            contentDescription = app.label,
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop,
        )
        // Specular highlight
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .align(Alignment.TopCenter)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(
                    Brush.verticalGradient(listOf(GlassSpecular, Color(0x00FFFFFF)))
                ),
        )
    }
}

// ── Liquid Glass card ─────────────────────────────────────────────────────────

@Composable
private fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(GlassFill)
            .border(1.dp, GlassBorder, RoundedCornerShape(cornerRadius)),
    ) {
        content()
        // Top-edge specular rim (renders on top of content)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(cornerRadius * 2)
                .align(Alignment.TopCenter)
                .clip(RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius))
                .background(
                    Brush.verticalGradient(listOf(GlassSpecular, Color(0x00FFFFFF)))
                ),
        )
    }
}

// ── Utilities ─────────────────────────────────────────────────────────────────

@SuppressLint("QueryPermissionsNeeded")
private suspend fun loadInstalledApps(context: android.content.Context): List<AppInfo> {
    return try {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .mapNotNull { ri ->
                val label = ri.loadLabel(pm).toString().trim()
                if (label.isBlank()) return@mapNotNull null
                AppInfo(label = label, icon = ri.loadIcon(pm).toBitmap().asImageBitmap())
            }
            .sortedBy { it.label }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun placeholderApps(): List<AppInfo> =
    listOf(
        "Photos", "Calendar", "Mail", "Messages", "Safari",
        "Music", "Maps", "Settings", "App Store", "Clock",
        "Notes", "Reminders", "Weather", "Files", "Podcasts",
        "Health", "Fitness", "Books", "Shortcuts", "Tips",
    ).map { name -> AppInfo(label = name, icon = FALLBACK_BITMAP) }
