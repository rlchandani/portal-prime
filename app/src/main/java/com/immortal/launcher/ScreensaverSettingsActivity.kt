/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.SampleAppTheme
import kotlin.concurrent.thread

/**
 * Settings screen for the photo-frame screensaver. Reached from a "Screensaver"
 * tile in the launcher's Settings folder. Lets the user keep Immortal's built-in
 * photos or point the frame at a local folder of photos/videos — including one on a
 * USB-C drive plugged into the Portal.
 */
class ScreensaverSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { ScreensaverSettingsScreen() } }
  }
}

@Composable
private fun ScreensaverSettingsScreen() {
  val context = LocalContext.current
  var settings by remember { mutableStateOf(ScreensaverConfig.load(context)) }
  // null = not counted yet; -1 = unreadable; >=0 = media items found.
  var mediaCount by remember { mutableStateOf<Int?>(null) }
  var folderName by remember { mutableStateOf<String?>(null) }

  // Re-read config when we come back from the folder picker (a separate activity).
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, e ->
      if (e == Lifecycle.Event.ON_RESUME) settings = ScreensaverConfig.load(context)
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }

  LaunchedEffect(settings.folderPath, settings.includeVideo, settings.source) {
    val path = if (settings.usesFolder) settings.folderPath else null
    if (path == null) {
      mediaCount = null
      folderName = null
    } else {
      mediaCount = null
      folderName = LocalMedia.displayName(path)
      thread {
        mediaCount =
            if (LocalMedia.isAccessible(path)) LocalMedia.enumerate(path, settings.includeVideo).size
            else -1
      }
    }
  }

  fun openPicker() {
    context.startActivity(Intent(context, FolderPickerActivity::class.java))
  }

  // Remote support: focus the first control on open; Back exits the screen.
  val activity = context as? Activity
  val firstFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

  Column(
      modifier =
          Modifier.fillMaxSize()
              .onPreviewKeyEvent { e ->
                if (e.key == Key.Back) {
                  if (e.type == KeyEventType.KeyUp) activity?.finish()
                  true
                } else false
              }
              .background(Color(0xFF101012))
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 28.dp, vertical = 32.dp),
  ) {
    Column(modifier = Modifier.widthIn(max = 1100.dp).focusRequester(firstFocus).focusGroup()) {
      Text("Screensaver", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
      Text(
          "Choose what shows on the photo frame when your Portal is idle.",
          color = Color(0xFF9A9A9A),
          fontSize = 16.sp,
          modifier = Modifier.padding(top = 6.dp),
      )
      Spacer(Modifier.size(26.dp))

      SectionLabel("Source")
      Card {
        SelectableRow(
            title = "Immortal photos",
            subtitle = "A calming built-in photo feed (no setup).",
            selected = !settings.usesFolder,
            onClick = {
              ScreensaverConfig.useDefault(context)
              settings = ScreensaverConfig.load(context)
            },
        )
        Divider()
        SelectableRow(
            title = "My photos & videos",
            subtitle = folderSubtitle(settings.usesFolder, folderName, mediaCount),
            selected = settings.usesFolder,
            onClick = { openPicker() },
        )
        if (settings.usesFolder) {
          Divider()
          TextButtonRow("Choose a different folder…") { openPicker() }
        }
      }
      Text(
          "Tip: copy photos and videos onto a USB-C drive, plug it into your Portal, and pick it " +
              "here. If a folder can't be read (e.g. the drive is unplugged), Immortal shows its " +
              "built-in photos instead.",
          color = Color(0xFF7C7C7C),
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
      )

      Spacer(Modifier.size(26.dp))

      SectionLabel("Display")
      Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Image fit", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
          Segmented(
              options =
                  listOf(
                      "Fill" to ScreensaverConfig.FIT_FILL,
                      "Fit" to ScreensaverConfig.FIT_FIT,
                  ),
              selected = settings.fit,
              onSelect = {
                ScreensaverConfig.setFit(context, it)
                settings = settings.copy(fit = it)
              },
          )
        }
        Divider()
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
          Text("Time per item: ${settings.intervalSec}s", color = Color.White, fontSize = 17.sp)
          val sliderSrc = remember { MutableInteractionSource() }
          Slider(
              value = settings.intervalSec.toFloat(),
              onValueChange = { settings = settings.copy(intervalSec = it.toInt()) },
              onValueChangeFinished = {
                ScreensaverConfig.setInterval(context, settings.intervalSec)
              },
              valueRange = 5f..120f,
              interactionSource = sliderSrc,
              modifier = Modifier.padding(top = 4.dp).focusRing(sliderSrc, RoundedCornerShape(8.dp)),
          )
        }
        Divider()
        ToggleRow("Shuffle order", settings.shuffle) {
          ScreensaverConfig.setShuffle(context, it)
          settings = settings.copy(shuffle = it)
        }
        Divider()
        ToggleRow("Include videos", settings.includeVideo) {
          ScreensaverConfig.setIncludeVideo(context, it)
          settings = settings.copy(includeVideo = it)
        }
      }

      Spacer(Modifier.size(28.dp))
      Surface(
          color = Color(0xFF2E6BE6),
          shape = RoundedCornerShape(16.dp),
          modifier =
              Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(16.dp)) {
                context.startActivity(Intent(context, PhotoFramePreviewActivity::class.java))
              },
      ) {
        Text(
            "Preview screensaver",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth(),
        )
      }
    }
  }
}

private fun folderSubtitle(usesFolder: Boolean, name: String?, count: Int?): String =
    when {
      !usesFolder -> "Pick a folder on the Portal, an SD card, or a USB drive."
      count == null -> "${name ?: "Selected folder"} — scanning…"
      count < 0 -> "${name ?: "Selected folder"} — can't read it; showing built-in photos"
      count == 0 -> "${name ?: "Selected folder"} — no photos or videos found"
      else -> "${name ?: "Selected folder"} — $count item${if (count == 1) "" else "s"}"
    }

@Composable
private fun SectionLabel(text: String) {
  Text(
      text.uppercase(),
      color = Color(0xFF7C7C7C),
      fontSize = 13.sp,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
  )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
  Surface(
      color = Color(0xFF1C1C1E),
      shape = RoundedCornerShape(18.dp),
      modifier = Modifier.fillMaxWidth(),
  ) {
    Column { content() }
  }
}

@Composable
private fun Divider() {
  Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color(0x14FFFFFF)))
}

@Composable
private fun SelectableRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
  Row(
      modifier =
          Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(12.dp)) { onClick() }
              .padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(title, color = Color.White, fontSize = 17.sp)
      Text(
          subtitle,
          color = Color(0xFF9A9A9A),
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 2.dp),
      )
    }
    // Visual only — the whole row is the focus/click target.
    RadioButton(selected = selected, onClick = null)
  }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(
      modifier =
          Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(12.dp)) { onChange(!checked) }
              .padding(horizontal = 18.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(title, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
    // Visual only — the row toggles it (so the remote's center button works).
    Switch(checked = checked, onCheckedChange = null)
  }
}

@Composable
private fun TextButtonRow(label: String, onClick: () -> Unit) {
  Text(
      label,
      color = Color(0xFF8AB4F8),
      fontSize = 16.sp,
      modifier = Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(8.dp)) { onClick() }.padding(18.dp),
  )
}

@Composable
private fun Segmented(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
  Row(
      modifier = Modifier.background(Color(0xFF2A2A2C), RoundedCornerShape(12.dp)).padding(3.dp),
      horizontalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    options.forEach { (label, value) ->
      val on = value == selected
      Surface(
          color = if (on) Color(0xFF2E6BE6) else Color.Transparent,
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.tvFocusable(RoundedCornerShape(10.dp)) { onSelect(value) },
      ) {
        Text(
            label,
            color = if (on) Color.White else Color(0xFFBBBBBB),
            fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
      }
    }
  }
}
