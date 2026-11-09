/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.settings.DerivedSpec
import com.immortal.launcher.settings.SettingsPresentation
import com.immortal.launcher.ui.theme.LocalAdvancedSettings
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import kotlinx.coroutines.delay

/** How long a search result's row stays highlighted (fades over the last part). */
internal const val SETTINGS_HIGHLIGHT_MS = 2_600L
private const val SETTINGS_HIGHLIGHT_HOLD = .3f
private const val HIGHLIGHT_ALPHA = .3f
private val SectionInset = 30.dp
private val AdvancedBadgeColor = Color(0xFFE8A23A)

/** Section title above a card, with an ADV badge for Advanced-only sections. */
@Composable
internal fun SettingsSectionHeader(text: String, advanced: Boolean) {
    Row(Modifier.padding(start = SectionInset, end = SectionInset, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text, color = IosSecondary, fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
        if (advanced) SettingsAdvancedBadge()
    }
}

@Composable
internal fun SettingsAdvancedBadge() {
    Text("ADV", color = if (LocalApprovedPalette.current.dark) AdvancedBadgeColor else Color(0xFFC47D12),
        fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp,
        modifier = Modifier.background(AdvancedBadgeColor.copy(alpha = .22f), RoundedCornerShape(7.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp))
}

@Composable
internal fun SettingsSectionFooter(text: String) {
    Text(text, color = IosSecondary, fontSize = 20.sp, lineHeight = 28.sp,
        modifier = Modifier.padding(start = SectionInset, end = SectionInset, top = 10.dp))
}

/** The coloured tile, title and one-line description at the top of a top-level page. */
@Composable
internal fun SettingsLede(page: SettingsPageDef, text: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 34.dp)
        .background(IosCard, RoundedCornerShape(26.dp)).padding(horizontal = 30.dp, vertical = 26.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(26.dp)) {
        SettingsTileIcon(page.icon, Color(page.color), SettingsTileSize.LEDE, shared = true)
        Column(Modifier.weight(1f)) {
            Text(page.title, color = IosLabel, fontSize = 38.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold)
            Text(text, color = IosSecondary, fontSize = 23.sp, lineHeight = 31.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

internal enum class SettingsTileSize(val box: Int, val glyph: Int, val radius: Int) {
    SMALL(40, 24, 10), SIDEBAR(46, 28, 12), LEDE(84, 50, 22)
}

/** White glyph on a coloured rounded square, as on iPadOS. Sidebar glyphs share parsed vectors. */
@Composable
internal fun SettingsTileIcon(icon: String, color: Color, size: SettingsTileSize, shared: Boolean = false) {
    Box(Modifier.size(size.box.dp).background(color, RoundedCornerShape(size.radius.dp)),
        contentAlignment = Alignment.Center) {
        if (shared) {
            Icon(approvedSettingsSidebarVector(approvedIconResource(icon)), contentDescription = null,
                tint = Color.White, modifier = Modifier.size(size.glyph.dp))
        } else {
            ApprovedIcon(icon, tint = Color.White, modifier = Modifier.size(size.glyph.dp))
        }
    }
}

/** A notice card in place of rows that are hidden until something else is turned on. */
@Composable
internal fun SettingsNotice(text: String) {
    Text(text, color = IosSecondary, fontSize = 22.sp, lineHeight = 31.sp,
        modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp)
            .background(IosCard, RoundedCornerShape(Sizes.cardRadius)).padding(28.dp))
}

/** Registry rows of one domain inside the section card, through the shared renderer. */
@Composable
internal fun SettingsDomainRowsCard(
    rows: SettingsDomainRows,
    state: SettingsDomainState,
    highlight: String?,
    first: Boolean,
    last: Boolean,
) {
    val context = LocalContext.current
    val advanced = LocalAdvancedSettings.current
    val specs = rows.keys.mapNotNull { key -> state.domain.specs.firstOrNull { it.key == key } }
        .filter { spec ->
            spec !is DerivedSpec<*> && (advanced || !SettingsPresentation.isAdvanced(rows.domain, spec.key)) &&
                spec.visibleWhen(context, state.snapshot)
        }
    specs.forEachIndexed { index, spec ->
        val id = "${rows.domain}.${spec.key}"
        ApprovedSettingsLazyRow(id, first && index == 0, last && index == specs.lastIndex, highlight) {
            SettingControl(rows.domain, spec, state.snapshot, advanced, state.editsEnabled, state.apply)
        }
    }
}

/** A navigation row that shares the section card with its neighbours. */
@Composable
internal fun SettingsNavigationRow(
    id: String,
    title: String,
    subtitle: String,
    first: Boolean,
    last: Boolean,
    highlight: String?,
    onClick: () -> Unit,
) {
    ApprovedSettingsLazyRow(id, first, last, highlight) { NavRow(title, subLabel = subtitle, onClick = onClick) }
}

/** Search jumps highlight a row briefly; the host then clears the request so it never repeats. */
@Composable
internal fun rememberBriefHighlight(control: String?, onShown: () -> Unit): String? {
    LaunchedEffect(control) {
        if (control != null) {
            delay(SETTINGS_HIGHLIGHT_MS)
            onShown()
        }
    }
    return control
}

/** A bespoke block with a fading tint when search lands on one of its rows. Draw-phase only. */
@Composable
internal fun SettingsBlockHighlight(active: Boolean, content: @Composable () -> Unit) {
    val tint = IosBlue
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (!active) { alpha.snapTo(0f); return@LaunchedEffect }
        alpha.snapTo(HIGHLIGHT_ALPHA)
        delay((SETTINGS_HIGHLIGHT_MS * SETTINGS_HIGHLIGHT_HOLD).toLong())
        alpha.animateTo(0f, tween((SETTINGS_HIGHLIGHT_MS * (1 - SETTINGS_HIGHLIGHT_HOLD)).toInt(), easing = LinearEasing))
    }
    Box(Modifier.fillMaxWidth().drawWithContent {
        drawContent()
        val a = alpha.value
        if (a > 0f) drawRect(tint.copy(alpha = a))
    }) { content() }
}

@Composable
internal fun SettingsAdvancedHintRow() {
    Column(Modifier.padding(top = 10.dp, start = SectionInset)) {
        Text("More options are available in Advanced settings.", color = IosSecondary,
            fontSize = 21.sp, lineHeight = 30.sp)
        ApprovedSettingsTextButton("Show advanced options", LocalApprovedEnableAdvanced.current)
    }
}

@Composable
internal fun SettingsSectionGap() = Spacer(Modifier.size(34.dp))
