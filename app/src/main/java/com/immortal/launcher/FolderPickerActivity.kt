/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A minimal folder browser — the Portal has no system document picker, so we
 * provide our own. Lists internal storage plus any mounted SD/USB volumes, shows a
 * photo/video count and a few thumbnails per folder, lets the user drill in, and
 * saves the chosen folder as the screensaver source. Uses direct file access
 * (legacy storage + READ_EXTERNAL_STORAGE), so it reaches USB-C drives.
 */
class FolderPickerActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      PortalPrimeTheme(darkTheme = true) {
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

  fun up(): File? =
      current?.let { if (roots.any { r -> r.path == it.absolutePath }) null else it.parentFile }

  BackHandler { if (current == null) onCancel() else current = up() }
  val firstFocus = remember { FocusRequester() }
  LaunchedEffect(current, granted) { if (granted) runCatching { firstFocus.requestFocus() } }

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
          Modifier.fillMaxSize()
              .onPreviewKeyEvent { e ->
                if (e.key == Key.Back) {
                  if (e.type == KeyEventType.KeyUp) {
                    if (current == null) onCancel() else current = up()
                  }
                  true
                } else false
              }
              .background(Color(0xFF101012))
              .padding(horizontal = 28.dp, vertical = 28.dp),
  ) {
    Text(
        if (current == null) "Choose a folder" else current!!.absolutePath,
        color = Color.White,
        fontSize = 24.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
    )
    Spacer(Modifier.size(14.dp))

    if (!granted) {
      Text(
          "Immortal needs permission to read your files. Tap to allow, then pick a folder.",
          color = Color(0xFF9A9A9A),
          fontSize = 15.sp,
      )
      Spacer(Modifier.size(16.dp))
      PrimaryButton("Allow access", null) {
        permLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
      }
      return@Column
    }

    if (current != null) {
      val summary by
          produceState<MediaSummary?>(initialValue = null, current!!.absolutePath) {
            value = withContext(Dispatchers.IO) { LocalMedia.summarize(current!!.absolutePath) }
          }
      PrimaryButton("Use this folder", summaryLine(summary, here = true)) {
        onPick(current!!.absolutePath)
      }
      Spacer(Modifier.size(14.dp))
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f).focusRequester(firstFocus).focusGroup()) {
      if (current == null) {
        if (roots.isEmpty()) {
          item {
            Hint(
                "No folders found yet. Add some photos to your Portal's storage and they'll " +
                    "show up here.")
          }
        }
        items(roots) { root ->
          SimpleRow(icon = "🖴", title = root.label, subtitle = root.path) {
            current = File(root.path)
          }
        }
      } else {
        item { SimpleRow(icon = "↑", title = "Up one level", subtitle = "") { current = up() } }
        if (subdirs.isEmpty()) {
          item { Hint("No subfolders here. The photos and videos in this folder are shown below.") }
        }
        items(subdirs) { dir -> FolderRow(dir) { current = dir } }
        item { CurrentFolderPreview(current!!) }
      }
    }

    Spacer(Modifier.size(10.dp))
    Text(
        "Cancel",
        color = Color(0xFF8AB4F8),
        fontSize = 16.sp,
        modifier = Modifier.tvFocusable(RoundedCornerShape(8.dp)) { onCancel() }.padding(8.dp),
    )
  }
}

/** Counts + thumbnails are loaded in the background per folder. */
@Composable
private fun FolderRow(dir: File, onClick: () -> Unit) {
  val summary by
      produceState<MediaSummary?>(initialValue = null, dir.absolutePath) {
        value = withContext(Dispatchers.IO) { LocalMedia.summarize(dir.absolutePath) }
      }
  Row(
      modifier =
          Modifier.fillMaxWidth().tvFocusableRow { onClick() }.padding(vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    IconBox("📁")
    Column(modifier = Modifier.weight(1f)) {
      Text(dir.name, color = Color.White, fontSize = 17.sp, maxLines = 1)
      Text(summaryLine(summary), color = Color(0xFF8A8A8A), fontSize = 12.sp)
    }
    summary?.samples?.let { samples ->
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        samples.take(3).forEach { Thumb(it) }
      }
    }
  }
}

@Composable
private fun Thumb(item: MediaItem, sizeDp: Int = 44) {
  val bmp by
      produceState<Bitmap?>(initialValue = null, item.path) {
        value = withContext(Dispatchers.IO) { Thumbnails.get(item, 160) }
      }
  Box(
      contentAlignment = Alignment.Center,
      modifier =
          Modifier.size(sizeDp.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2A2C)),
  ) {
    bmp?.let {
      Image(
          bitmap = it.asImageBitmap(),
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
      )
    }
    if (item.isVideo) Text("▶", color = Color.White, fontSize = (sizeDp / 3).sp)
  }
}

/** A grid of thumbnails of the media directly in the folder you're viewing, so you
 * can see what you'd be using before tapping "Use this folder". */
@Composable
private fun CurrentFolderPreview(dir: File) {
  val media by
      produceState<List<MediaItem>?>(initialValue = null, dir.absolutePath) {
        value = withContext(Dispatchers.IO) { LocalMedia.enumerate(dir.absolutePath, true, 24) }
      }
  val list = media ?: return
  if (list.isEmpty()) return
  Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
    Text(
        "IN THIS FOLDER",
        color = Color(0xFF7C7C7C),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 12.dp),
    )
    list.chunked(6).forEach { rowItems ->
      Row(
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier.padding(bottom = 10.dp),
      ) {
        rowItems.forEach { Thumb(it, sizeDp = 76) }
      }
    }
  }
}

private fun summaryLine(s: MediaSummary?, here: Boolean = false): String {
  if (s == null) return if (here) "Counting…" else "Scanning…"
  if (s.total == 0) return if (here) "No photos or videos in this folder" else "No photos or videos"
  val plus = if (s.capped) "+" else ""
  val parts = ArrayList<String>()
  if (s.photos > 0) parts.add("${s.photos}$plus photo${if (s.photos == 1 && !s.capped) "" else "s"}")
  if (s.videos > 0) parts.add("${s.videos}$plus video${if (s.videos == 1 && !s.capped) "" else "s"}")
  return parts.joinToString(" · ")
}

@Composable
private fun IconBox(icon: String) {
  Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.size(40.dp).background(Color(0xFF2A2A2C), RoundedCornerShape(10.dp)),
  ) {
    Text(icon, fontSize = 18.sp)
  }
}

@Composable
private fun PrimaryButton(label: String, subtitle: String?, onClick: () -> Unit) {
  Surface(
      color = Color(0xFF2E6BE6),
      shape = RoundedCornerShape(14.dp),
      modifier =
          Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(14.dp), focusScale = 1f) {
            onClick()
          },
  ) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(vertical = 13.dp).fillMaxWidth(),
    ) {
      Text(label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
      if (subtitle != null) {
        Text(subtitle, color = Color(0xCCFFFFFF), fontSize = 12.sp, textAlign = TextAlign.Center)
      }
    }
  }
}

@Composable
private fun SimpleRow(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
  Row(
      modifier =
          Modifier.fillMaxWidth().tvFocusableRow { onClick() }.padding(vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    IconBox(icon)
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
  Text(
      text,
      color = Color(0xFF7C7C7C),
      fontSize = 14.sp,
      modifier = Modifier.padding(vertical = 14.dp),
  )
}
