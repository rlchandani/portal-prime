/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import com.immortal.launcher.ui.theme.ProvideHearthpaneTouchTargets
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
internal fun ApprovedHomeDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
  if (!LocalApprovedLauncherPageActive.current || !LocalApprovedWidgetPageInput.current) return
  androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
      properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) { ProvideHearthpaneTouchTargets {
    CompositionLocalProvider(LocalApprovedHomePopup provides true) {
    ApprovedHomeSurface(Modifier.widthIn(max = 880.dp).fillMaxWidth(.8f).heightIn(max = 1096.dp), radius = 28.dp) {
      Column(Modifier.padding(36.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text(title, color = ApprovedHomePalette.Text, fontSize = 32.sp, lineHeight = 42.sp,
              fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
          ApprovedHomeTextButton("Close", onClick = onDismiss)
        }
        content()
      }
    }
    }
  }}
}

/** A keyboard-free duration picker (±steppers, remote/touch friendly) for a custom timer. */
@Composable
internal fun CustomTimerDialog(onStart: (Long) -> Unit, onDismiss: () -> Unit) {
  var minutes by remember { mutableStateOf(5) }
  var seconds by remember { mutableStateOf(0) }
  ApprovedHomeDialog("Custom timer", onDismiss) {
      Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
          DurationStepper("min", minutes, step = 1) { minutes = (minutes + it).coerceIn(0, 180) }
          Text(":", color = ApprovedHomePalette.Text, fontSize = 48.sp, lineHeight = 60.sp)
          DurationStepper("sec", seconds, step = 5) { seconds = (seconds + it + 60) % 60 }
        }
        Spacer(Modifier.size(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          TimerChip("Cancel") { onDismiss() }
          TimerChip("Start ${minutes}:%02d".format(seconds), enabled = minutes > 0 || seconds > 0) {
            onStart((minutes * 60 + seconds) * 1000L)
          }
        }
      }
  }
}

@Composable
private fun DurationStepper(label: String, value: Int, step: Int, onChange: (Int) -> Unit) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(label.uppercase(Locale.getDefault()), color = ApprovedHomePalette.Secondary,
        fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.size(8.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      StepButton("−") { onChange(-step) }
      Text("%02d".format(value), color = ApprovedHomePalette.Text, fontSize = 48.sp,
          lineHeight = 60.sp, fontWeight = FontWeight.Normal)
      StepButton("+") { onChange(step) }
    }
  }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
  Surface(
      color = ApprovedHomePalette.Chrome,
      shape = RoundedCornerShape(16.dp),
      modifier = Modifier.size(68.dp).tvFocusable(RoundedCornerShape(16.dp)) { onClick() },
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(label, color = ApprovedHomePalette.Accent, fontSize = 26.sp, lineHeight = 35.sp)
    }
  }
}

@Composable
private fun TimerChip(label: String, enabled: Boolean = true, onClick: () -> Unit) {
  Surface(
      color = ApprovedHomePalette.Chrome,
      shape = RoundedCornerShape(16.dp),
      modifier = Modifier.heightIn(min = 52.dp)
          .tvFocusable(RoundedCornerShape(16.dp), enabled = enabled) { onClick() },
  ) {
    Text(
        label,
        color = if (enabled) ApprovedHomePalette.Accent else ApprovedHomePalette.Secondary,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
    )
  }
}

@Composable
internal fun ApprovedAppActions(
    app: AppEntry,
    assignments: Map<String, String>,
    favorite: Boolean,
    onLaunch: () -> Unit,
    onFavorite: () -> Unit,
    onFolder: (String) -> Unit,
    onUninstall: () -> Unit,
    onDismiss: () -> Unit,
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var confirmUninstall by remember { mutableStateOf(false) }
  var editingFolder by remember { mutableStateOf(false) }
  var newFolder by remember { mutableStateOf("") }
  ApprovedHomeDialog(app.label, onDismiss) {
    if (confirmUninstall) {
      Text("Uninstall ${app.label}? Android will ask you to confirm.", color = ApprovedHomePalette.Secondary, fontSize = 23.sp)
      ApprovedHomeTextButton("Cancel") { confirmUninstall = false }
      ApprovedHomeTextButton("Uninstall", onClick = onUninstall)
    } else if (editingFolder) {
      Text("Move to folder", color = ApprovedHomePalette.Text, fontSize = 26.sp)
      (assignments.values + Curation.folders.values).filter { it.isNotBlank() }.distinct().sorted().forEach { name ->
        ApprovedHomeTextButton(name, Modifier.fillMaxWidth()) { onFolder(name) }
      }
      androidx.compose.material3.OutlinedTextField(newFolder, { newFolder = it }, singleLine = true,
          placeholder = { Text("New folder name") }, modifier = Modifier.fillMaxWidth())
      ApprovedHomeTextButton("Create folder", enabled = newFolder.isNotBlank()) { onFolder(newFolder.trim()) }
      ApprovedHomeTextButton("Remove from folder") { onFolder("") }
    } else {
      ApprovedHomeTextButton("Open", onClick = onLaunch)
      ApprovedHomeTextButton(if (favorite) "Remove from favorites" else "Add to favorites", onClick = onFavorite)
      ApprovedHomeTextButton("Move to folder") { editingFolder = true }
      if (app.component.packageName != context.packageName) {
        ApprovedHomeTextButton("App info") {
          runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
              Uri.parse("package:${app.component.packageName}"))) }; onDismiss()
        }
        ApprovedHomeTextButton("Close app") {
          runCatching { (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
              .killBackgroundProcesses(app.component.packageName) }; onDismiss()
        }
      }
      if (app.uninstallable) ApprovedHomeTextButton("Uninstall") { confirmUninstall = true }
    }
  }
}
