/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * THE paste-a-link screen. Calendar/.ics, shared-album, and web-page entry are the same screen
 * with different validators and strings — this is the single implementation all three use.
 *
 * @param validate returns a non-null provider/label when [trimmed] input is acceptable (the
 *   label feeds the "Looks like a X link." line; pass "" for a generic "Looks good.").
 */
@Composable
internal fun UrlEntryScreen(
    title: String,
    subtitle: String,
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
    helpTitle: String? = null,
    helpBody: String? = null,
) {
  var url by remember { mutableStateOf(initial) }
  val (_, initialFocus) = rememberInitialFocus()

  val trimmed = url.trim()
  val provider = if (trimmed.isEmpty()) null else validate(trimmed)
  val valid = provider != null

  BackHandler { onCancel() }

  SettingsScaffold(title = title, subtitle = subtitle, onBack = onCancel) {
    Column {
      OutlinedTextField(
          colors = iosTextFieldColors(),
          value = url,
          onValueChange = { url = it },
          placeholder = { Text(placeholder) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
          shape = RoundedCornerShape(14.dp),
      )

      Text(
          when {
            trimmed.isEmpty() -> emptyHint
            valid -> if (provider!!.isBlank()) "Looks good." else "Looks like a $provider link."
            else -> invalidHint
          },
          color = if (valid || trimmed.isEmpty()) IosSecondary else IosRed,
          fontSize = Sizes.rowSub,
          modifier = Modifier.padding(top = 10.dp, start = 4.dp),
      )

      Spacer(Modifier.heightIn(min = 26.dp))

      Surface(
          color = if (valid) IosBlue else IosDivider,
          shape = RoundedCornerShape(16.dp),
          modifier =
              Modifier.fillMaxWidth().then(initialFocus).tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) {
                if (valid) onSave(trimmed)
              },
      ) {
        Text(
            primaryLabel,
            color = if (valid) Color.White else IosSecondary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth(),
        )
      }

      Spacer(Modifier.heightIn(min = 12.dp))

      if (removeLabel != null && onRemove != null && initial.isNotBlank()) {
        Surface(
            color = IosCard,
            shape = RoundedCornerShape(16.dp),
            modifier =
                Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) { onRemove() },
        ) {
          Text(
              removeLabel,
              color = IosRed,
              fontSize = Sizes.rowTitle,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth(),
          )
        }
        Spacer(Modifier.heightIn(min = 12.dp))
      }

      Surface(
          color = IosCard,
          shape = RoundedCornerShape(16.dp),
          modifier =
              Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) { onCancel() },
      ) {
        Text(
            "Cancel",
            color = IosBlue,
            fontSize = Sizes.rowTitle,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth(),
        )
      }

      if (helpTitle != null && helpBody != null) {
        Spacer(Modifier.heightIn(min = 26.dp))
        Text(
            helpTitle,
            color = IosLabel,
            fontSize = Sizes.rowSub,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Text(
            helpBody,
            color = IosTertiary,
            fontSize = Sizes.rowSub,
            modifier = Modifier.padding(start = 4.dp),
        )
      }
    }
  }
}
