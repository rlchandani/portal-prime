/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette

/**
 * Revision 04 URL body: full-width Options form, field card, hint and inline actions.
 * Providers and validation stay with the caller; nothing is saved while typing or cancelling.
 */
@Composable
internal fun UrlEntryScreen(
    title: String,
    fieldLabel: String,
    placeholder: String,
    initial: String,
    emptyHint: String,
    invalidHint: String,
    primaryLabel: String,
    validate: (String) -> String?,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    removeLabel: String? = null,
    onRemove: (() -> Unit)? = null,
    fieldHelp: String? = null,
    advancedOnly: Boolean = false,
    validHint: ((String) -> String)? = null,
) {
    val palette = LocalApprovedPalette.current
    val activity = LocalContext.current as? Activity
    var value by remember { mutableStateOf(initial) }
    var remove by remember { mutableStateOf(false) }
    var complete by remember { mutableStateOf(false) }
    val cancel = { complete = true; onCancel() }
    BackHandler(onBack = cancel)

    val trimmed = value.trim()
    val provider = if (trimmed.isEmpty()) null else validate(trimmed)
    ApprovedSourcesPage(title, onBack = cancel, advancedOnly = advancedOnly) {
        ApprovedEditorsForm(actions = {
            // The frozen form reserves its empty error paragraph above the URL hint.
            Spacer(Modifier.height(70.dp))
            Text(
                when {
                    trimmed.isEmpty() -> emptyHint
                    provider == null -> invalidHint
                    validHint != null -> validHint(provider)
                    provider.isEmpty() -> "Looks good."
                    else -> "Looks like a $provider link."
                },
                color = if (trimmed.isNotEmpty() && provider == null) palette.error else palette.text,
                fontSize = 24.sp, lineHeight = 37.7.sp,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            )
            Row(Modifier.padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                ApprovedEditorsButton(primaryLabel, primary = true, enabled = provider != null && !complete) {
                    val draft = value.trim()
                    if (draft.isNotEmpty() && validate(draft) != null && !complete &&
                        activity?.isFinishing != true && activity?.isDestroyed != true) {
                        complete = true
                        onSave(draft)
                    }
                }
                ApprovedEditorsButton("Cancel", onClick = cancel)
                if (initial.isNotBlank() && removeLabel != null && onRemove != null) {
                    ApprovedSettingsTextButton(removeLabel) { if (!complete) remove = true }
                }
            }
        }) {
            ApprovedEditorsField(fieldLabel, value, { if (!complete) value = it }, placeholder)
            if (!fieldHelp.isNullOrBlank()) {
                Text(fieldHelp, color = palette.secondary, fontSize = 20.sp, lineHeight = 27.sp,
                    modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 14.dp))
            }
            // Preserve the field's bottom rule and 26 dp trailing margin inside the field card.
            Spacer(Modifier.height(26.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.divider))
            Spacer(Modifier.height(26.dp))
        }
    }
    if (remove && onRemove != null) {
        ApprovedSettingsDialog("Remove connection?", onDismiss = { remove = false },
            help = "The existing source remains available until you confirm.") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ApprovedEditorsButton("Cancel") { remove = false }
                ApprovedEditorsButton("Remove", primary = true) {
                    remove = false
                    if (!complete && activity?.isFinishing != true && activity?.isDestroyed != true) {
                        complete = true
                        onRemove()
                    }
                }
            }
        }
    }
}
