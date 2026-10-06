/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shared overlays omit their modal/page frame only while inside a Tools detail workspace. */
internal val LocalApprovedToolWorkspace = staticCompositionLocalOf { false }

/**
 * One return destination for tool controls, including rich editor Cancel actions. Closing a
 * workspace disposes its content; audio sessions and coroutine work retain their existing owners.
 * Every tool shares the fixed back/heading above a bounded body; self-scrolling content such
 * as the bedtime reader uses the same frame without adding a second scroll container.
 */
@Composable
internal fun ApprovedToolWorkspace(
    title: String,
    onDismiss: () -> Unit,
    scrollContent: Boolean = true,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    CompositionLocalProvider(
        LocalApprovedToolPage provides true,
        LocalApprovedToolWorkspace provides true,
        LocalApprovedSettingsBack provides onDismiss,
    ) {
        Column(Modifier.fillMaxSize().background(IosBackground)
            .padding(horizontal = 56.dp, vertical = 36.dp)) {
            ApprovedAppsButton("‹  Tools", onClick = onDismiss)
            Spacer(Modifier.height(26.dp))
            ApprovedAppsHeading(title)
            Box(Modifier.weight(1f).fillMaxWidth()
                .then(if (scrollContent) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
                content()
            }
        }
    }
}
