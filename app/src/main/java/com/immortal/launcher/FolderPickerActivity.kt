/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.immortal.launcher.ui.theme.SampleAppTheme
import java.io.File

/**
 * A minimal folder browser — the Portal has no system document picker, so we
 * provide our own. Lists internal storage plus any mounted SD/USB volumes, lets the
 * user drill into a folder, and saves it as the screensaver source. Uses direct
 * file access (legacy storage + READ_EXTERNAL_STORAGE), so it reaches USB-C drives.
 */
class FolderPickerActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      SampleAppTheme(darkTheme = true) {
        FolderPicker(
            onPick = { path ->
              ScreensaverConfig.setFolder(this, path)
              finish()
            },
            onCancel = { finish() },
        )
      }
    }
  }
}

private fun hasStoragePermission(c: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(c, Manifest.permission.READ_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED

@Composable
private fun FolderPicker(onPick: (String) -> Unit, onCancel: () -> Unit) {
  val context = LocalContext.current
  var granted by remember { mutableStateOf(hasStoragePermission(context)) }
  val permLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
  LaunchedEffect(Unit) {
    if (!granted) permLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
  }

  val roots = remember { LocalMedia.storageRoots() }
  // null = showing the list of storage volumes; otherwise the folder we're inside.
  var current by remember { mutableStateOf<File?>(null) }

  fun up(): File? = current?.let { if (roots.any { r -> r.path == it.absolutePath }) null else it.parentFile }

  BackHandler { if (current == null) onCancel() else current = up() }

  val subdirs =
      remember(current, granted) {
        current?.let {
          runCatching {
                it.listFiles()
                    ?.filter { f -> f.isDirectory && f.canRead() && !f.name.startsWith(".") }
                    ?.sortedBy { f -> f.name.lowercase() } ?: emptyList()
              }
              .getOrDefault(emptyList())
        } ?: emptyList()
      }

  Column(
      modifier =
          Modifier.fillMaxSize().background(Color(0xFF101012)).padding(horizontal = 28.dp, vertical = 28.dp),
  ) {
    Text(
        if (current == null) "Choose a folder" else current!!.absolutePath,
        color = Color.White,
        fontSize = 24.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
    )
    Spacer(Modifier.size(18.dp))

    if (!granted) {
      Text(
          "Immortal needs permission to read your files. Tap to allow, then pick a folder.",
          color = Color(0xFF9A9A9A),
          fontSize = 15.sp,
      )
      Spacer(Modifier.size(16.dp))
      PrimaryButton("Allow access") {
        permLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
      }
      return@Column
    }

    if (current != null) {
      PrimaryButton("Use this folder") { onPick(current!!.absolutePath) }
      Spacer(Modifier.size(14.dp))
    }

    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
      if (current == null) {
        if (roots.isEmpty()) {
          item { Hint("No storage found. If you plugged in a USB drive, give it a moment.") }
        }
        items(roots) { root ->
          PickerRow(icon = "🖴", title = root.label, subtitle = root.path) { current = File(root.path) }
        }
      } else {
        item { PickerRow(icon = "↑", title = "Up one level", subtitle = "") { current = up() } }
        if (subdirs.isEmpty()) {
          item { Hint("No subfolders here. Tap \"Use this folder\" to use it.") }
        }
        items(subdirs) { dir ->
          PickerRow(icon = "📁", title = dir.name, subtitle = "") { current = dir }
        }
      }
    }

    Spacer(Modifier.size(10.dp))
    Text(
        "Cancel",
        color = Color(0xFF8AB4F8),
        fontSize = 16.sp,
        modifier = Modifier.clickable { onCancel() }.padding(8.dp),
    )
  }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
  Surface(
      color = Color(0xFF2E6BE6),
      shape = RoundedCornerShape(14.dp),
      modifier = Modifier.fillMaxWidth().clickable { onClick() },
  ) {
    Text(
        label,
        color = Color.White,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth(),
    )
  }
}

@Composable
private fun PickerRow(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .clickable { onClick() }
              .padding(vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(40.dp).background(Color(0xFF2A2A2C), RoundedCornerShape(10.dp)),
    ) {
      Text(icon, fontSize = 18.sp)
    }
    Column {
      Text(title, color = Color.White, fontSize = 17.sp)
      if (subtitle.isNotBlank()) {
        Text(subtitle, color = Color(0xFF8A8A8A), fontSize = 12.sp)
      }
    }
  }
}

@Composable
private fun Hint(text: String) {
  Text(text, color = Color(0xFF7C7C7C), fontSize = 14.sp, modifier = Modifier.padding(vertical = 14.dp))
}
