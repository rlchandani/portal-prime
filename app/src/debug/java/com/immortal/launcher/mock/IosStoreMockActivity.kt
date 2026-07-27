/*
 * Copyright (c) 2026 Starbright Lab.
 * iOS 26 App Store mock — preview-only, not wired into any live install flow.
 */

package com.immortal.launcher.mock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme

// ── iOS 26 system palette ──────────────────────────────────────────────────────
private val IosBg          = Color(0xFFF2F2F7)
private val IosGroupedBg   = Color(0xFFE5E5EA)
private val IosWhite       = Color.White
private val IosBlue        = Color(0xFF007AFF)
private val IosPrimary     = Color(0xFF000000)
private val IosSecondary   = Color(0x993C3C43)   // #3C3C43 @ 60%
private val IosTertiary    = Color(0x4D3C3C43)   // #3C3C43 @ 30%
private val IosSeparator   = Color(0xFFC7C7CC)
private val IosCardBg      = Color.White

// Glass tint for tab bar / hero overlay
private val GlassFill      = Color(0x59FFFFFF)   // white ~35%

// ── App data ───────────────────────────────────────────────────────────────────

private data class StoreApp(
    val name: String,
    val category: String,
    val tagline: String,
    val ratingStars: Float,
    val reviewCount: String,
    val size: String,
    val iconColor: Color,
    val iconEmoji: String,
    val isFree: Boolean = true,
    val price: String = "GET",
)

private val featuredApps = listOf(
    StoreApp("Fantastical",      "Productivity",  "Calendar & Tasks reimagined",     4.8f, "142K", "62 MB",  Color(0xFF4F7CFF), "📅"),
    StoreApp("Halide Mark III",  "Photo & Video", "Pro camera for iPhone",           4.9f,  "89K", "48 MB",  Color(0xFF1C1C1E), "📷"),
    StoreApp("Things 3",         "Productivity",  "Award-winning task manager",      4.9f, "231K", "41 MB",  Color(0xFF6E4AE6), "✅"),
    StoreApp("Dark Sky",         "Weather",       "Hyperlocal weather forecasts",    4.7f,  "57K", "34 MB",  Color(0xFF1D3557), "🌧"),
    StoreApp("Overcast",         "Podcasts",      "Powerful, stylish podcast player",4.8f, "108K", "29 MB",  Color(0xFFF48024), "🎙"),
)

private val allApps = listOf(
    StoreApp("Reeder.",          "News",          "RSS + read-later, beautifully done", 4.9f, "78K",  "21 MB",  Color(0xFFEB5545), "📰"),
    StoreApp("Darkroom",         "Photo & Video", "Photo & video editor",            4.8f,  "54K", "85 MB",  Color(0xFF121212), "🖼"),
    StoreApp("Bear",             "Productivity",  "Markdown notes & writing app",    4.8f, "197K", "38 MB",  Color(0xFFE5202E), "🐻"),
    StoreApp("Carrot Weather",   "Weather",       "Weather with attitude",           4.7f,  "63K", "45 MB",  Color(0xFFFF6B35), "🌩"),
    StoreApp("Structured",       "Productivity",  "Visual daily planner",            4.8f,  "41K", "33 MB",  Color(0xFF2AA668), "🗓"),
    StoreApp("Ivory",            "Social",        "Mastodon client by Tapbots",      4.7f,  "28K", "27 MB",  Color(0xFF6236FF), "🦣"),
    StoreApp("Infuse 7",         "Entertainment", "Video player & media library",    4.9f, "314K", "73 MB",  Color(0xFF0071E3), "🎬", isFree = false, price = "$9.99"),
    StoreApp("Lungo",            "Utilities",     "Keep your Mac & iPhone awake",    4.6f,  "19K", "8 MB",   Color(0xFF7E3F00), "☕"),
)

private val tabItems = listOf("Today", "Apps", "Games", "Arcade", "Search")

// ── Activity ───────────────────────────────────────────────────────────────────

class IosStoreMockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PortalPrimeTheme(darkTheme = false) {
                IosStoreScreen()
            }
        }
    }
}

// ── Root screen ────────────────────────────────────────────────────────────────

@Composable
private fun IosStoreScreen() {
    var selectedTab by remember { mutableStateOf(0) }

    Box(modifier = Modifier.fillMaxSize().background(IosBg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 82.dp) // room for tab bar
                .verticalScroll(rememberScrollState())
        ) {
            HeroSection()
            Spacer(Modifier.height(24.dp))
            FeaturedSection()
            Spacer(Modifier.height(28.dp))
            AllAppsSection()
            Spacer(Modifier.height(16.dp))
        }

        // Liquid Glass tab bar pinned at bottom
        IosTabBar(
            tabs = tabItems,
            selectedIndex = selectedTab,
            onTabSelected = { selectedTab = it },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

// ── Hero banner ────────────────────────────────────────────────────────────────

@Composable
private fun HeroSection() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF1A1A2E), Color(0xFF16213E), Color(0xFF0F3460))
                )
            )
    ) {
        // Background star field / subtle particles
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0x33A0C4FF), Color(0x00000000)),
                        radius = 400f
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
        ) {
            // EDITOR'S CHOICE badge
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x33FFFFFF))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text("★", color = Color(0xFFFFD60A), fontSize = 11.sp)
                Text("EDITOR'S CHOICE", color = Color.White, fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Fantastical",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 36.sp
            )
            Text(
                "The calendar app that does it all",
                color = Color(0xCCFFFFFF),
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal
            )
            Spacer(Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IosGetButton(label = "GET", outlined = false)
                Text(
                    "In-App Purchases",
                    color = Color(0xAAFFFFFF),
                    fontSize = 12.sp
                )
            }
        }

        // Frosted app icon floated top-right
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 48.dp, end = 20.dp)
                .size(80.dp)
                .clip(IosSquircle(18.dp))
                .background(Color(0xFF4F7CFF)),
            contentAlignment = Alignment.Center
        ) {
            Text("📅", fontSize = 38.sp)
        }
    }
}

// ── Featured horizontal scroll ─────────────────────────────────────────────────

@Composable
private fun FeaturedSection() {
    Column {
        SectionHeader("FEATURED", modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            featuredApps.forEach { app ->
                FeaturedCard(app)
            }
        }
    }
}

@Composable
private fun FeaturedCard(app: StoreApp) {
    Box(
        modifier = Modifier
            .width(280.dp)
            .height(180.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(app.iconColor)
            .clickable {}
    ) {
        // Gradient overlay for legibility
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.5f to Color(0x40000000),
                        1f to Color(0xCC000000)
                    )
                )
        )
        // Large emoji as artwork
        Text(
            app.iconEmoji,
            fontSize = 72.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 14.dp)
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp)
        ) {
            Text(
                app.category.uppercase(),
                color = Color(0xAAFFFFFF),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp
            )
            Text(app.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(app.tagline, color = Color(0xCCFFFFFF), fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

// ── All apps list ──────────────────────────────────────────────────────────────

@Composable
private fun AllAppsSection() {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        SectionHeader("ALL APPS")
        Spacer(Modifier.height(10.dp))
        // iOS grouped-list card
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(IosCardBg)
        ) {
            allApps.forEachIndexed { index, app ->
                AppListRow(app)
                if (index < allApps.lastIndex) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .padding(start = 76.dp)
                            .background(IosSeparator)
                    )
                }
            }
        }
    }
}

@Composable
private fun AppListRow(app: StoreApp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {}
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App icon — squircle
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(IosSquircle(13.dp))
                .background(app.iconColor),
            contentAlignment = Alignment.Center
        ) {
            Text(app.iconEmoji, fontSize = 26.sp)
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                app.name,
                color = IosPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                app.category,
                color = IosSecondary,
                fontSize = 13.sp,
                maxLines = 1
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                StarRating(app.ratingStars)
                Text(
                    app.reviewCount,
                    color = IosTertiary,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(Modifier.width(10.dp))
        IosGetButton(label = app.price, outlined = true)
    }
}

// ── Reusable components ────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = IosSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.3.sp,
        modifier = modifier
    )
}

@Composable
private fun IosGetButton(label: String, outlined: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50.dp))
            .background(
                if (outlined) Color(0x1A007AFF) else IosBlue
            )
            .clickable {}
            .padding(horizontal = 18.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (outlined) IosBlue else Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun StarRating(stars: Float) {
    val full = stars.toInt()
    val remainder = stars - full
    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        repeat(5) { i ->
            val color = when {
                i < full            -> Color(0xFFFF9F0A)
                i == full && remainder >= 0.5f -> Color(0xFFFF9F0A)
                else                -> Color(0xFFDDDDDD)
            }
            Text("★", color = color, fontSize = 11.sp, lineHeight = 11.sp)
        }
    }
}

// Liquid Glass tab bar with frosted background
@Composable
private fun IosTabBar(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(GlassFill)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val tabIcons = listOf("🌟", "📦", "🎮", "🕹", "🔍")
            tabs.forEachIndexed { i, label ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onTabSelected(i) }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(tabIcons.getOrElse(i) { "•" }, fontSize = 20.sp)
                    Text(
                        label,
                        color = if (i == selectedIndex) IosBlue else IosSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (i == selectedIndex) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

// Squircle shape helper (continuous corner approximation)
private fun IosSquircle(radius: androidx.compose.ui.unit.Dp) = RoundedCornerShape(radius)
