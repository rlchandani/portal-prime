/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings › Calls › Messages: how many are stored here, the space they use, and Delete all (tap twice). */
@Composable
internal fun CallMessagesSettingsBlock() {
  val context = LocalContext.current
  LaunchedEffect(Unit) { CallMessageStore.ensureLoadedOffMain(context) }
  val messages by CallMessageStore.messages.collectAsState()
  var bytes by remember { mutableLongStateOf(0L) }
  var confirm by remember { mutableStateOf(false) }
  LaunchedEffect(messages) { bytes = withContext(Dispatchers.IO) { CallMessageStore.bytesUsed(context) } }
  Card {
    InfoRow("Messages on this Portal", CallMessageCopy.storedLine(messages.size, bytes)) {
      CallPill(if (confirm) "Tap again to delete all" else "Delete all", {
        if (confirm) { CallMessageStore.deleteAll(context); confirm = false } else confirm = true
      }, icon = "trash", style = if (confirm) CallButtonStyle.BAD else CallButtonStyle.DEFAULT, size = CallButtonSize.SM,
          enabled = messages.isNotEmpty() || confirm, surface = callsSurface())
    }
  }
}

@Composable
private fun InfoRow(title: String, help: String, trailing: @Composable () -> Unit) {
  Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = Sizes.rowMinHeight).padding(horizontal = 28.dp, vertical = 22.dp),
      verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f).padding(end = 24.dp)) {
      Text(title, color = IosLabel, fontSize = Sizes.rowTitle, lineHeight = 34.sp)
      Text(help, color = IosSecondary, fontSize = Sizes.rowSub, lineHeight = 28.sp, modifier = Modifier.padding(top = 8.dp))
    }
    trailing()
  }
}
