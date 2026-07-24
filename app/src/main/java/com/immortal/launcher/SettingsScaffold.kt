/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The one light full-screen shell every settings sub-page renders inside, so a sub-page looks
 * like it belongs to the same world as the two-panel main screen: iOS grey background
 * ([IosBackground]), a visible "‹ Back" pill, a left-aligned large [title] and optional
 * [subtitle], and a scrolling body constrained to a comfortable reading width.
 *
 * Back is wired twice on purpose: the hardware/gesture Back key (via [onPreviewKeyEvent]) and
 * the on-screen [SettingsBackButton] both call [onBack]. Host the sub-page's content in [body].
 *
 * Note: the hosting Activity should use `PortalPrimeTheme(darkTheme = false)` to match.
 */
@Composable
internal fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    subtitle: String = "",
    body: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Back) {
                    if (e.type == KeyEventType.KeyUp) onBack()
                    true
                } else false
            }
            .background(IosBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 30.dp),
    ) {
        // Fill the full available width (keeping the page padding above); the Portal panel is
        // wide, so a narrow max-width column would leave a large empty right side.
        Column(modifier = Modifier.fillMaxWidth().focusGroup()) {
            SettingsBackButton(onClick = onBack)
            Spacer(Modifier.size(20.dp))
            Text(
                text = title,
                color = IosLabel,
                fontSize = Sizes.titlePage,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.6).sp,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    color = IosSecondary,
                    fontSize = Sizes.pageSubtitle,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.size(20.dp))
            body()
        }
    }
}
