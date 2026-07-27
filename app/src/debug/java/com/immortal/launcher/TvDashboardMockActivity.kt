package com.immortal.launcher

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme

// ── Palette ──────────────────────────────────────────────────────────────────
private val BgBase       = Color(0xFF111114)
private val BgCard       = Color(0xFF1E1E24)
private val BgCardAlt    = Color(0xFF18181E)
private val CardBorder   = Color(0xFF2E2E3A)
private val TextPrimary  = Color(0xFFF0F0F5)
private val TextSecond   = Color(0xFF8888A0)
private val TextMuted    = Color(0xFF55556A)
private val AccentGreen  = Color(0xFF30D158)
private val AccentAmber  = Color(0xFFFF9F0A)
private val AccentBlue   = Color(0xFF0A84FF)
private val AccentRed    = Color(0xFFFF453A)
private val PillBg       = Color(0xFF28282F)

class TvDashboardMockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PortalPrimeTheme {
                DashboardScreen()
            }
        }
    }
}

@Composable
private fun DashboardScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(
                colors = listOf(Color(0xFF0F0F12), Color(0xFF111114), Color(0xFF0D0D10))
            ))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // ── TOP BAR ────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        "THURSDAY, JUL 23",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Row {
                        Text(
                            "Good evening, ",
                            color = TextPrimary,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Rohit",
                            color = TextSecond,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "2:55 PM",
                        color = TextPrimary,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Light
                    )
                    Text(
                        "Edmonds, WA · 73° · Sunny",
                        color = TextSecond,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── STATUS PILLS ───────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill("System Disarmed", AccentGreen)
                StatusPill("All Locked", AccentGreen)
                StatusPill("5 Lights On", AccentAmber)
                StatusPill("Rohit · Home", AccentBlue)
                StatusPill("Internet OK", AccentGreen)
                StatusPill("Tesla · 80%", AccentGreen)
            }

            Spacer(Modifier.height(24.dp))

            // ── ROW 1: Weather (wide) + Thermostat (medium) + Security (narrow) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Weather card — wide (weight 2.2)
                DashCard(modifier = Modifier.weight(2.2f).fillMaxHeight()) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            DashSectionLabel("EVERETT, WA")
                            Spacer(Modifier.height(8.dp))
                            Text("73°", color = TextPrimary, fontSize = 56.sp, fontWeight = FontWeight.Light)
                            Text("Sunny · Feels like 71°", color = AccentBlue, fontSize = 14.sp)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                MiniStat("Humidity", "45%")
                                MiniStat("Wind", "8 mph")
                                MiniStat("Sunset", "9:01 PM")
                            }
                        }
                        // Forecast strip
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            listOf("MON" to "72°", "TUE" to "68°", "WED" to "65°", "THU" to "71°", "FRI" to "75°")
                                .forEach { (day, temp) ->
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(day, color = TextMuted, fontSize = 11.sp, letterSpacing = 0.5.sp)
                                        Text("☀️", fontSize = 16.sp)
                                        Text(temp, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                        }
                    }
                }

                // Thermostat card — medium (weight 1.3)
                DashCard(modifier = Modifier.weight(1.3f).fillMaxHeight()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        DashSectionLabel("THERMOSTAT")
                        // Circular temp display
                        Box(
                            modifier = Modifier
                                .size(110.dp)
                                .border(4.dp, AccentBlue, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("72°", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                Text("current", color = TextMuted, fontSize = 11.sp)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(AccentBlue.copy(alpha = 0.15f))
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Text("COOLING", color = AccentBlue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("70°", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                                Text("Cool to", color = TextMuted, fontSize = 11.sp)
                            }
                            Box(modifier = Modifier.width(1.dp).height(32.dp).background(CardBorder))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("75°", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                                Text("Heat to", color = TextMuted, fontSize = 11.sp)
                            }
                        }
                    }
                }

                // Security card — narrow (weight 1.2)
                DashCard(modifier = Modifier.weight(1.2f).fillMaxHeight()) {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        DashSectionLabel("SECURITY")
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(AccentGreen)
                            Spacer(Modifier.width(6.dp))
                            Text("System Disarmed", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text("All entry points secured", color = TextMuted, fontSize = 11.sp)
                        Spacer(Modifier.height(12.dp))
                        listOf("Front Door" to "LOCKED", "Garage Door" to "CLOSED", "Deck Door" to "CLOSED", "Main Door" to "CLOSED").forEach { (name, status) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(name, color = TextSecond, fontSize = 12.sp)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(AccentGreen.copy(alpha = 0.12f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(status, color = AccentGreen, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                                }
                            }
                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(CardBorder.copy(alpha = 0.4f)))
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── ROW 2: Quick Actions (apps) ────────────────────────────────
            DashSectionLabel("QUICK ACTIONS")
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth().height(110.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                listOf(
                    Triple("📅", "PortalHub", AccentAmber),
                    Triple("🤖", "Jarvis", Color(0xFFBF5AF2)),
                    Triple("📞", "Calls", AccentBlue),
                    Triple("📦", "App Store", AccentGreen),
                    Triple("🌐", "Browser", AccentBlue),
                    Triple("⚙️", "Settings", TextSecond),
                ).forEach { (icon, label, accent) ->
                    DashCard(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(icon, fontSize = 28.sp)
                            Spacer(Modifier.height(6.dp))
                            Text(label, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── ROW 3: Presence + Indoor Climate ───────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Presence
                DashCard(modifier = Modifier.weight(1.5f).fillMaxHeight()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        DashSectionLabel("PRESENCE")
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            PersonChip("Rohit", "Home · Just now", AccentGreen, "🧑")
                            PersonChip("Neha", "Away · Not home", TextMuted, "👩")
                        }
                    }
                }

                // Indoor climate
                DashCard(modifier = Modifier.weight(2f).fillMaxHeight()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        DashSectionLabel("INDOOR CLIMATE")
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            ClimateChip("74°", "TEMP")
                            ClimateChip("54%", "HUMIDITY")
                            ClimateChip("Good", "AIR QUALITY", AccentGreen)
                        }
                    }
                }
            }
        }
    }
}

// ── Reusable Components ───────────────────────────────────────────────────────

@Composable
private fun DashCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(BgCard)
            .border(1.dp, CardBorder.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
    ) { content() }
}

@Composable
private fun DashSectionLabel(text: String) {
    Text(text, color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
}

@Composable
private fun StatusPill(text: String, dotColor: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(PillBg)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Dot(dotColor, size = 7)
        Text(text, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Dot(color: Color, size: Int = 8) {
    Box(modifier = Modifier.size(size.dp).clip(CircleShape).background(color))
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column {
        Text(value, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(label, color = TextMuted, fontSize = 11.sp)
    }
}

@Composable
private fun PersonChip(name: String, status: String, statusColor: Color, avatar: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(BgCardAlt)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF3A3A4A)),
            contentAlignment = Alignment.Center
        ) { Text(avatar, fontSize = 18.sp) }
        Column {
            Text(name, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Dot(statusColor, 6)
                Text(status, color = statusColor, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ClimateChip(value: String, label: String, valueColor: Color = TextPrimary) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(BgCardAlt)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = valueColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(label, color = TextMuted, fontSize = 10.sp, letterSpacing = 0.5.sp)
        }
    }
}
