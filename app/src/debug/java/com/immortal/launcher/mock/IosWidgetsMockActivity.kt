/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher.mock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme

// ── iOS 26 Liquid Glass palette ───────────────────────────────────────────────
private val WallpaperCenter  = Color(0xFF1A3A6B) // deep royal blue
private val WallpaperMid     = Color(0xFF0A1628) // midnight blue
private val WallpaperEdge    = Color(0xFF0A0A1A) // near-black

private val GlassFill        = Color(0x28FFFFFF) // 16% white frosted surface
private val GlassBorder      = Color(0x40FFFFFF) // 25% white inner border
private val GlassSpecularHi  = Color(0x20FFFFFF) // 12% white specular highlight
private val TextPrimW        = Color(0xFFFFFFFF)
private val TextSecW         = Color(0xCCFFFFFF) // 80% white
private val TextTertW        = Color(0x80FFFFFF) // 50% white
private val AccentGreen      = Color(0xFF30D158)
private val AccentBlue       = Color(0xFF0A84FF)
private val AccentAmber      = Color(0xFFFF9F0A)
private val SectionLabelClr  = Color(0xB3FFFFFF) // 70% white

// ── Activity ──────────────────────────────────────────────────────────────────

class IosWidgetsMockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PortalPrimeTheme {
                WidgetsShowcaseScreen()
            }
        }
    }
}

// ── Root screen ───────────────────────────────────────────────────────────────

@Composable
private fun WidgetsShowcaseScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        WallpaperCenter,
                        WallpaperMid,
                        WallpaperEdge,
                    ),
                    radius = 1600f,
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // ── Title ──────────────────────────────────────────────────────────
            Text(
                text = "iOS 26 Widget Gallery",
                color = TextPrimW,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            // ── Row 1: Weather ─────────────────────────────────────────────────
            SectionLabel("WEATHER")
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth(),
            ) {
                WeatherSmallWidget()
                WeatherMediumWidget(modifier = Modifier.weight(1f))
            }
            WeatherLargeWidget(modifier = Modifier.fillMaxWidth())

            // ── Row 2: Clock / Time ────────────────────────────────────────────
            SectionLabel("TIME")
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ClockSmallWidget()
                ClockMediumWidget(modifier = Modifier.weight(1f))
            }
            ClockWideWidget(modifier = Modifier.fillMaxWidth())

            // ── Row 3: System info ─────────────────────────────────────────────
            SectionLabel("SYSTEM")
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth(),
            ) {
                BatteryWidget()
                WifiWidget()
                StorageWidget()
            }

            // ── Row 4: Quick actions ───────────────────────────────────────────
            SectionLabel("QUICK ACTIONS")
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth(),
            ) {
                PhotoFrameWidget(modifier = Modifier.weight(1f))
                JarvisWidget(modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Reusable: section label ───────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = SectionLabelClr,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

// ── Reusable: liquid glass widget container ───────────────────────────────────

/**
 * Wraps widget content in an iOS 26 Liquid Glass surface:
 *   - Frosted white fill (16% alpha)
 *   - 1dp white border (25% alpha)
 *   - Radial specular highlight anchored to the top-left quadrant
 */
@Composable
private fun GlassWidget(
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
        // Specular highlight — top-left radial glow
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(GlassSpecularHi, Color.Transparent),
                        center = Offset(0f, 0f), // top-left; Compose normalises internally
                        radius = 300f,
                    )
                )
        )
        content()
    }
}

// ── Weather: Small 150×150dp ──────────────────────────────────────────────────

@Composable
private fun WeatherSmallWidget() {
    GlassWidget(
        modifier = Modifier.size(150.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Partly Cloudy", color = TextSecW, fontSize = 11.sp)
            Column {
                Text(
                    text = "⛅",
                    fontSize = 36.sp,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                Text(
                    text = "68°",
                    color = TextPrimW,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Light,
                )
            }
            Text("Edmonds, WA", color = TextTertW, fontSize = 10.sp)
        }
    }
}

// ── Weather: Medium 320×150dp ─────────────────────────────────────────────────

@Composable
private fun WeatherMediumWidget(modifier: Modifier = Modifier) {
    GlassWidget(
        modifier = modifier.height(150.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Text(
                        text = "68°",
                        color = TextPrimW,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Light,
                    )
                    Text("Partly Cloudy", color = TextSecW, fontSize = 12.sp)
                    Text("Edmonds, WA", color = TextTertW, fontSize = 10.sp)
                }
                Text("⛅", fontSize = 48.sp)
            }
            // 3-day strip
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    Triple("Today", "⛅", "68°"),
                    Triple("Fri", "☀️", "72°"),
                    Triple("Sat", "🌧", "61°"),
                ).forEach { (day, icon, temp) ->
                    DayForecastChip(day, icon, temp)
                }
            }
        }
    }
}

@Composable
private fun DayForecastChip(day: String, icon: String, temp: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x18FFFFFF))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(day, color = TextTertW, fontSize = 10.sp)
            Text(icon, fontSize = 14.sp)
            Text(temp, color = TextSecW, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ── Weather: Large 320×300dp ──────────────────────────────────────────────────

@Composable
private fun WeatherLargeWidget(modifier: Modifier = Modifier) {
    GlassWidget(modifier = modifier.height(300.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Edmonds, WA", color = TextTertW, fontSize = 11.sp)
                    Text(
                        text = "68°",
                        color = TextPrimW,
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Thin,
                    )
                    Text("Partly Cloudy", color = TextSecW, fontSize = 13.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("⛅", fontSize = 52.sp)
                    Text("H: 74°  L: 59°", color = TextTertW, fontSize = 11.sp)
                }
            }

            // Detail chips row
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                WeatherDetailChip(label = "Feels like", value = "65°", modifier = Modifier.weight(1f))
                WeatherDetailChip(label = "Humidity", value = "72%", modifier = Modifier.weight(1f))
                WeatherDetailChip(label = "Wind", value = "12 mph", modifier = Modifier.weight(1f))
            }

            // 5-day forecast strip
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                listOf(
                    Triple("Today", "⛅", "68°"),
                    Triple("Fri", "☀️", "72°"),
                    Triple("Sat", "🌧", "61°"),
                    Triple("Sun", "⛅", "65°"),
                    Triple("Mon", "☀️", "70°"),
                ).forEach { (day, icon, temp) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(day, color = TextTertW, fontSize = 10.sp)
                        Text(icon, fontSize = 16.sp)
                        Text(temp, color = TextSecW, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // Sunrise / sunset
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x18FFFFFF))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("🌅", fontSize = 14.sp)
                        Column {
                            Text("Sunrise", color = TextTertW, fontSize = 10.sp)
                            Text("5:34 AM", color = TextSecW, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x18FFFFFF))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("🌇", fontSize = 14.sp)
                        Column {
                            Text("Sunset", color = TextTertW, fontSize = 10.sp)
                            Text("9:02 PM", color = TextSecW, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeatherDetailChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x18FFFFFF))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = TextTertW, fontSize = 10.sp)
            Text(value, color = TextPrimW, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ── Clock: Small analog 150×150dp ─────────────────────────────────────────────

@Composable
private fun ClockSmallWidget() {
    GlassWidget(modifier = Modifier.size(150.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Clock", color = TextTertW, fontSize = 10.sp, letterSpacing = 1.sp)
            // Analog clock face (thin-line style)
            AnalogClockFace(modifier = Modifier.size(90.dp))
            Text("4:15 PM", color = TextTertW, fontSize = 10.sp)
        }
    }
}

@Composable
private fun AnalogClockFace(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .border(1.dp, GlassBorder, CircleShape)
            .background(Color(0x10FFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        // Hour markers and hands via concentric text placeholders
        // Hour indicators at 12, 3, 6, 9
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            // Center dot
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(TextPrimW),
            )
        }
        // 12 o'clock
        Text(
            "━",
            color = TextSecW,
            fontSize = 6.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 4.dp),
        )
        // 3 o'clock
        Text(
            "━",
            color = TextSecW,
            fontSize = 6.sp,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp),
        )
        // 6 o'clock
        Text(
            "━",
            color = TextSecW,
            fontSize = 6.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 4.dp),
        )
        // 9 o'clock
        Text(
            "━",
            color = TextSecW,
            fontSize = 6.sp,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 4.dp),
        )
        // Time label (stand-in for hands at 4:15)
        Text(
            "4:15",
            color = TextPrimW,
            fontSize = 11.sp,
            fontWeight = FontWeight.Light,
        )
    }
}

// ── Clock: Medium digital 320×150dp ──────────────────────────────────────────

@Composable
private fun ClockMediumWidget(modifier: Modifier = Modifier) {
    GlassWidget(modifier = modifier.height(150.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "4:15 PM",
                color = TextPrimW,
                fontSize = 56.sp,
                fontWeight = FontWeight.Thin,
                lineHeight = 56.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Thursday, July 23",
                color = TextSecW,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
            )
        }
    }
}

// ── Clock: Wide full-width 100dp tall ─────────────────────────────────────────

@Composable
private fun ClockWideWidget(modifier: Modifier = Modifier) {
    GlassWidget(modifier = modifier.height(80.dp)) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "4:15 PM",
                color = TextPrimW,
                fontSize = 36.sp,
                fontWeight = FontWeight.Light,
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(40.dp)
                    .background(GlassBorder)
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "Thursday",
                    color = TextSecW,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "July 23, 2026",
                    color = TextTertW,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

// ── System: Battery 150×150dp ─────────────────────────────────────────────────

@Composable
private fun BatteryWidget() {
    GlassWidget(modifier = Modifier.size(150.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            Text("⚡", fontSize = 28.sp)
            Box(contentAlignment = Alignment.Center) {
                // Circular progress arc (static at 100% — device is always plugged in)
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .border(3.dp, AccentGreen, CircleShape),
                )
                Text(
                    text = "100%",
                    color = AccentGreen,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = "Charging",
                color = AccentGreen,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

// ── System: WiFi 150×150dp ────────────────────────────────────────────────────

@Composable
private fun WifiWidget() {
    GlassWidget(modifier = Modifier.size(150.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            Text("📶", fontSize = 28.sp)
            Text(
                text = "Connected",
                color = AccentGreen,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x18FFFFFF))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "LittleCedar",
                    color = TextPrimW,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
            // Signal strength bar
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                listOf(8.dp, 12.dp, 16.dp, 20.dp).forEachIndexed { i, h ->
                    Box(
                        modifier = Modifier
                            .width(6.dp)
                            .height(h)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (i < 4) AccentBlue else Color(0x40FFFFFF)),
                    )
                }
            }
        }
    }
}

// ── System: Storage 150×150dp ─────────────────────────────────────────────────

@Composable
private fun StorageWidget() {
    GlassWidget(modifier = Modifier.size(150.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            Text("Storage", color = TextSecW, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text("💾", fontSize = 24.sp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Used", color = TextTertW, fontSize = 10.sp)
                    Text("14.2 GB", color = TextSecW, fontSize = 10.sp)
                }
                // Storage bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0x30FFFFFF)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.45f)
                            .fillMaxHeight()
                            .background(AccentBlue),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Free", color = TextTertW, fontSize = 10.sp)
                    Text("17.3 GB", color = TextSecW, fontSize = 10.sp)
                }
            }
            Text(
                text = "45% full",
                color = TextTertW,
                fontSize = 10.sp,
            )
        }
    }
}

// ── Quick action: Photo Frame 320×200dp ──────────────────────────────────────

@Composable
private fun PhotoFrameWidget(modifier: Modifier = Modifier) {
    GlassWidget(modifier = modifier.height(200.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("🖼", fontSize = 20.sp)
                Column {
                    Text("Photo Frame", color = TextSecW, fontSize = 11.sp)
                    Text(
                        text = "Screensaver",
                        color = TextPrimW,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Thumbnail preview placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF2A4A7A),
                                Color(0xFF1A2A5A),
                                Color(0xFF2A3A6A),
                            )
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "🏔  Mountain Lake  🌲",
                    color = TextSecW,
                    fontSize = 12.sp,
                )
            }

            // Action button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x30FFFFFF))
                    .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Start Screensaver",
                    color = TextPrimW,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

// ── Quick action: Jarvis 320×200dp ───────────────────────────────────────────

@Composable
private fun JarvisWidget(modifier: Modifier = Modifier) {
    GlassWidget(modifier = modifier.height(200.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("🤖", fontSize = 20.sp)
                Column {
                    Text("Assistant", color = TextSecW, fontSize = 11.sp)
                    Text(
                        text = "Jarvis",
                        color = TextPrimW,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Large tap target — microphone button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0x40FFFFFF),
                                Color(0x10FFFFFF),
                            ),
                            radius = 300f,
                        )
                    )
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("🎙", fontSize = 28.sp)
                    Text(
                        text = "Tap to speak",
                        color = TextSecW,
                        fontSize = 12.sp,
                    )
                }
            }

            // Status
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(AccentGreen),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Ready",
                    color = AccentGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
