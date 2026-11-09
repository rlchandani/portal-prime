/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val StandaloneSidePadding = 56.dp
private val StandaloneBottomPadding = 80.dp

/**
 * The split-view chrome for editors that keep their own Activity (connect forms, Lamp, Help,
 * direct launches). The sidebar highlights the page the editor belongs to; picking another page
 * or a search result opens the main Settings there and closes this editor.
 */
@Composable
internal fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    subtitle: String = "",
    body: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val page = remember(context) { approvedPageForHost(context) }
    val control = LocalApprovedRequestedControl.current ?: activity?.intent?.getStringExtra(APPROVED_SETTINGS_CONTROL)
    var prerequisite by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(control) {
        if (!control.isNullOrBlank()) prerequisite = withContext(Dispatchers.IO) { approvedSettingsPrerequisite(context, control) }
    }
    BackHandler(onBack = onBack)
    fun openInSettings(target: String, row: String? = null) {
        context.startActivity(settingsPageIntent(context, target, row))
        activity?.finish()
    }
    SettingsSplitShell(
        selectedRoot = SettingsTree.rootOf(page),
        heading = SettingsDetailHeading(title, SettingsTree.page(page)?.title?.takeIf { it != title }, onBack),
        onSelectPage = { openInSettings(it) },
        onSearchResult = { openInSettings(it.page, it.control) },
        onHome = {
            context.startActivity(android.content.Intent(context, HomeActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
            activity?.finish()
        },
        diagnosticsScope = "${SettingsTree.rootOf(page)}/$page",
        diagnosticsControl = control,
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberApprovedSettingsScrollState("standalone/$title"))
            .padding(start = StandaloneSidePadding, end = StandaloneSidePadding, bottom = StandaloneBottomPadding)) {
            if (subtitle.isNotEmpty()) {
                Text(subtitle, color = IosSecondary, fontSize = 23.sp, lineHeight = 34.sp,
                    modifier = Modifier.padding(bottom = 30.dp))
            }
            body()
        }
    }
    prerequisite?.let { message ->
        ApprovedSettingsDialog("Setting prerequisite", onDismiss = { prerequisite = null }, help = message) {
            ApprovedSettingsTextButton("Review parent settings") { prerequisite = null }
        }
    }
}
