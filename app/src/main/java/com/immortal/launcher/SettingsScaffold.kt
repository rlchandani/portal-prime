/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Shared revision 04 shell. Existing rich editors retain their content and Activity navigation. */
@Composable
internal fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    subtitle: String = "",
    body: @Composable () -> Unit,
) = SettingsScaffold(title, onBack, subtitle, lazyDetail = null, body = body)

@Composable
internal fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    subtitle: String = "",
    lazyDetail: ApprovedSettingsLazyDetail?,
    body: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val page = approvedPageForHost(context, title)
    val control = LocalApprovedRequestedControl.current
        ?: (context as? Activity)?.intent?.getStringExtra(APPROVED_SETTINGS_CONTROL)
    var prerequisite by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(control) {
        if (!control.isNullOrBlank()) prerequisite = withContext(Dispatchers.IO) { approvedSettingsPrerequisite(context, control) }
    }
    BackHandler(onBack = onBack)
    ApprovedSettingsShell(
        category = approvedSettingsCategory(page),
        title = ApprovedSettingsPages.firstOrNull { it.title.equals(title, true) }?.title
            ?: approvedSettingsPage(page)?.title ?: title,
        subtitle = subtitle,
        onBack = onBack,
        page = page,
        lazyDetail = lazyDetail,
        content = body,
    )
    prerequisite?.let { message ->
        ApprovedSettingsDialog("Setting prerequisite", onDismiss = { prerequisite = null }, help = message) {
            ApprovedSettingsTextButton("Review parent settings") { prerequisite = null }
        }
    }
}
