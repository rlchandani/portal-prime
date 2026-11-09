/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SearchFieldHeight = 76.dp
private val SearchFieldRadius = 22.dp

/** The sidebar search field; a clear button appears while there is a query. */
@Composable
internal fun SettingsSearchField(query: String, onChange: (String) -> Unit) {
    val composition = FramePerformanceCapture.beginSettingsUiWork(SettingsUiWorkStage.SEARCH_COMPOSITION)
    Row(
        Modifier.fillMaxWidth().height(SearchFieldHeight)
            .background(IosSegmentTrack, RoundedCornerShape(SearchFieldRadius))
            .then(if (BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS) Modifier.settingsUiTarget("search", "input") else Modifier)
            .padding(start = 22.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ApprovedIcon("search", tint = IosSecondary, modifier = Modifier.size(28.dp))
        BasicTextField(
            value = query, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = IosLabel, fontSize = 26.sp),
            cursorBrush = SolidColor(IosBlue),
            modifier = Modifier.weight(1f).semantics { contentDescription = "Search settings" },
            decorationBox = { inner ->
                if (query.isEmpty()) Text("Search", color = IosSecondary, fontSize = 26.sp)
                inner()
            },
        )
        if (query.isNotEmpty()) {
            Box(Modifier.size(SearchFieldHeight - 8.dp).tvFocusable(CircleShape, focusScale = 1f, ringColor = IosBlue) {
                onChange("")
            }.semantics { contentDescription = "Clear search" }, contentAlignment = Alignment.Center) {
                ApprovedIcon("x", tint = IosSecondary, modifier = Modifier.size(26.dp))
            }
        }
    }
    composition?.finish()
}
