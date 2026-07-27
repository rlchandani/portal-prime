/*
 * Copyright (c) 2026 Starbright Lab.
 * iOS 26 Settings mock — preview-only, mirrors the Settings.app grouped-list aesthetic.
 */

package com.immortal.launcher.mock

import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme

// ── iOS 26 system palette (mirrors IosStoreMockActivity) ──────────────────────
private val IosSettingsBg       = Color(0xFFF2F2F7)
private val IosCardBg           = Color.White
private val IosGroupedHeader    = Color(0xFF6C6C70)
private val IosPrimary          = Color(0xFF000000)
private val IosSecondary        = Color(0x993C3C43)
private val IosBlue             = Color(0xFF007AFF)
private val IosSeparator        = Color(0xFFC7C7CC)
private val IosChevron          = Color(0xFFC7C7CC)

// Icon background colours — each settings row has a coloured squircle icon
private val IconRed     = Color(0xFFFF3B30)
private val IconBlue    = Color(0xFF007AFF)
private val IconGreen   = Color(0xFF34C759)
private val IconOrange  = Color(0xFFFF9500)
private val IconPurple  = Color(0xFFAF52DE)
private val IconGray    = Color(0xFF8E8E93)
private val IconTeal    = Color(0xFF5AC8FA)
private val IconIndigo  = Color(0xFF5856D6)

// ── Data model ────────────────────────────────────────────────────────────────

private data class SettingsRow(
    val icon: String,
    val iconBg: Color,
    val label: String,
    val value: String = "",
    val isDestructive: Boolean = false,
)

private data class SettingsSection(
    val header: String?,
    val footer: String?,
    val rows: List<SettingsRow>,
)

// ── Activity ───────────────────────────────────────────────────────────────────

class IosSettingsMockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PortalPrimeTheme(darkTheme = false) {
                IosSettingsScreen()
            }
        }
    }
}

// ── Root screen ────────────────────────────────────────────────────────────────

@Composable
private fun IosSettingsScreen() {
    val context = LocalContext.current

    // Read real device info where available
    val modelName   = remember { Build.MODEL ?: "Portal Prime" }
    val androidVer  = remember { Build.VERSION.RELEASE ?: "10" }
    val storageText = remember { readStorageSummary() }
    val buildNum    = remember { Build.ID ?: "unknown" }

    val sections = listOf(
        // ── Profile card (top) ────────────────────────────────────────────────
        SettingsSection(
            header = null,
            footer = "Your Immortal account and device subscription",
            rows = listOf(
                SettingsRow("👤", IconBlue, "Immortal Account", "rohit@example.com"),
            )
        ),
        // ── GENERAL ───────────────────────────────────────────────────────────
        SettingsSection(
            header = "GENERAL",
            footer = null,
            rows = listOf(
                SettingsRow("📶", IconBlue,    "Wi-Fi",            "Home-5G"),
                SettingsRow("🔊", IconRed,     "Sound & Haptics",  ""),
                SettingsRow("🔆", IconBlue,    "Display & Brightness", "Always On"),
                SettingsRow("🌙", IconIndigo,  "Focus",            "Off"),
                SettingsRow("🔔", IconRed,     "Notifications",    ""),
            )
        ),
        // ── APPS ──────────────────────────────────────────────────────────────
        SettingsSection(
            header = "APPS",
            footer = null,
            rows = listOf(
                SettingsRow("🖼",  IconOrange, "Screensaver",      "Immich · 3 sources"),
                SettingsRow("🤖", IconPurple,  "Jarvis",           "Enabled"),
                SettingsRow("🏠", IconBlue,    "PortalHub",        "v2.1"),
                SettingsRow("📦", IconGreen,   "App Store",        "58 apps"),
                SettingsRow("🌐", IconTeal,    "Browser",          ""),
            )
        ),
        // ── SYSTEM ────────────────────────────────────────────────────────────
        SettingsSection(
            header = "SYSTEM",
            footer = null,
            rows = listOf(
                SettingsRow("ℹ️", IconBlue,   "About",            "$modelName · Android $androidVer"),
                SettingsRow("💾", IconGray,   "Storage",          storageText),
                SettingsRow("🔒", IconGray,   "Privacy & Security",""),
                SettingsRow("⚙️", IconGray,   "Developer Mode",   "Off"),
                SettingsRow("🔧", IconOrange,  "Debug",            "Build $buildNum"),
            )
        ),
        // ── DANGER ZONE ───────────────────────────────────────────────────────
        SettingsSection(
            header = null,
            footer = "This will erase all launcher customisations and restore factory defaults.",
            rows = listOf(
                SettingsRow("🗑", IconRed, "Reset All Settings", "", isDestructive = true),
            )
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IosSettingsBg)
    ) {
        // iOS-style large title nav bar
        NavBar()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 40.dp)
        ) {
            sections.forEach { section ->
                SettingsSectionView(section)
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

// ── Navigation bar (large title) ──────────────────────────────────────────────

@Composable
private fun NavBar() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(IosSettingsBg)
            .padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 8.dp)
    ) {
        Text(
            "Settings",
            color = IosPrimary,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ── Section ────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsSectionView(section: SettingsSection) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        // Section header
        section.header?.let { header ->
            Text(
                header,
                color = IosGroupedHeader,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)
            )
        }

        // Card containing all rows
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(IosCardBg)
        ) {
            section.rows.forEachIndexed { index, row ->
                SettingsRowView(row)
                if (index < section.rows.lastIndex) {
                    // Separator — inset to align under the label, past icon
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .padding(start = 56.dp)
                            .background(IosSeparator)
                    )
                }
            }
        }

        // Section footer
        section.footer?.let { footer ->
            Text(
                footer,
                color = IosGroupedHeader,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp)
            )
        }
    }
}

// ── Single row ────────────────────────────────────────────────────────────────

@Composable
private fun SettingsRowView(row: SettingsRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {}
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Icon squircle (30dp, matching iOS Settings row icon size)
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(row.iconBg),
            contentAlignment = Alignment.Center
        ) {
            Text(row.icon, fontSize = 16.sp)
        }

        // Label
        Text(
            row.label,
            color = if (row.isDestructive) IconRed else IosPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )

        // Value + chevron
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (row.value.isNotEmpty()) {
                Text(
                    row.value,
                    color = IosSecondary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal
                )
            }
            if (!row.isDestructive) {
                Text(
                    "›",
                    color = IosChevron,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Light
                )
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun readStorageSummary(): String {
    return try {
        val stat = StatFs(Environment.getDataDirectory().path)
        val totalGb  = stat.totalBytes / 1_073_741_824L
        val freeGb   = stat.availableBytes / 1_073_741_824L
        val usedGb   = totalGb - freeGb
        "${usedGb} GB used of ${totalGb} GB"
    } catch (_: Exception) {
        "Storage unavailable"
    }
}
