/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.immortal.launcher.ui.components.OnResumeAfterFirstComposition
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

internal val WallpaperChoices: List<Pair<String, String>> =
    listOf(WallpaperConfig.DUSK_AURORA to "Dusk Aurora", WallpaperConfig.DARK to "Dark") +
        WallpaperConfig.GRADIENTS.map { (id, _) ->
          id to id.removePrefix("g_").replaceFirstChar { it.uppercase() }
        } + WallpaperConfig.PHOTOS +
        listOf(WallpaperConfig.SCREENSAVER to "Screensaver", WallpaperConfig.SKY to "Sky",
            WallpaperConfig.STARFIELD to "Star field")

@Composable
internal fun WallpaperSection() {
  val application = LocalContext.current.applicationContext
  var configuration by remember(application) { mutableStateOf(WallpaperConfig.load(application)) }
  OnResumeAfterFirstComposition { configuration = WallpaperConfig.load(application) }
  val pageActive = LocalApprovedLauncherPageActive.current
  var previewRevision by remember { mutableStateOf(0) }
  var previewsLoading by remember { mutableStateOf(true) }
  val thumbnails by produceState<Map<String, android.graphics.Bitmap>?>(null, application, pageActive, previewRevision) {
    if (!pageActive) return@produceState
    if (value?.size == WallpaperConfig.PHOTOS.size) {
      previewsLoading = false
      return@produceState
    }
    previewsLoading = true
    try {
      value = loadWallpaperPreviews(WallpaperConfig.PHOTOS.map { it.first },
          decode = { decodeWallpaperPreview(application, it) }, discard = { it.recycle() },
          existing = value.orEmpty())
    } finally {
      if (currentCoroutineContext().isActive) previewsLoading = false
    }
  }
  val selectedLabel = WallpaperChoices.firstOrNull { it.first == configuration.mode }?.second ?: "Custom"
  Card {
    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Background", color = IosLabel, fontSize = Sizes.rowTitle, modifier = Modifier.weight(1f))
        Text(selectedLabel, color = IosSecondary, fontSize = Sizes.rowSub,
            modifier = Modifier.semantics { contentDescription = "Current background: $selectedLabel" })
      }
      Text("Choose a color, photo, or a background that changes through the day.",
          color = IosSecondary, fontSize = Sizes.rowSub)
      // Columns follow the split view's page width (no measure-time subcomposition), and each row
      // is precomposed on its own so ~30 swatches never land in one frame.
      val screenWidth = LocalConfiguration.current.screenWidthDp
      val columns = remember(screenWidth) { WallpaperSwatchLayout.columns(screenWidth) }
      val rowHeight = remember(screenWidth, columns) { WallpaperSwatchLayout.rowHeightDp(screenWidth, columns).dp }
      val rows = remember(columns) { WallpaperSwatchLayout.rows(WallpaperChoices, columns) }
      Column(Modifier.fillMaxWidth().selectableGroup().semantics {
        contentDescription = when {
          previewsLoading -> "Wallpaper previews loading"
          thumbnails?.size == WallpaperConfig.PHOTOS.size -> "Wallpaper previews ready"
          else -> "Wallpaper previews unavailable"
        }
      }, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        rows.forEach { choices ->
          key(choices.first().first) {
            SettingsDeferredSlot(rowHeight) {
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WallpaperSwatchLayout.GAP_DP.dp)) {
                choices.forEach { (id, label) ->
                  val isPhoto = id.startsWith(WallpaperConfig.PHOTO_PREFIX)
                  val thumb = thumbnails?.get(id)
                  WallpaperSwatch(label, selected = configuration.mode == id,
                      enabled = !isPhoto || thumb != null, modifier = Modifier.weight(1f), onClick = {
                        WallpaperConfig.setMode(application, id)
                        configuration = WallpaperConfig.load(application)
                      }) { WallpaperChoicePreview(id, thumb) }
                }
                repeat(columns - choices.size) { Spacer(Modifier.weight(1f)) }
              }
            }
          }
        }
      }
      if (previewsLoading) {
        Text("Loading photo previews…", color = IosSecondary, fontSize = Sizes.rowSub)
      } else if (thumbnails?.size != WallpaperConfig.PHOTOS.size) {
        Text("Some photo previews could not be loaded.", color = IosSecondary, fontSize = Sizes.rowSub)
        ApprovedSettingsTextButton("Try again") {
          if (!previewsLoading) {
            previewsLoading = true
            previewRevision++
          }
        }
      }
      Divider()
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text("Film grain", color = IosLabel, fontSize = Sizes.rowTitle)
          Text("Adds a subtle grain texture over the wallpaper.", color = IosSecondary,
              fontSize = Sizes.rowSub, modifier = Modifier.padding(top = 2.dp))
        }
        ApprovedSettingsSwitch(checked = configuration.grain, onCheckedChange = {
          WallpaperConfig.setGrain(application, it)
          configuration = WallpaperConfig.load(application)
        }, modifier = Modifier.semantics { contentDescription = "Film grain" })
      }
    }
  }
}

@Composable
private fun WallpaperChoicePreview(id: String, thumbnail: android.graphics.Bitmap?) {
  val colors = wallpaperPreviewColors(id)
  when {
    thumbnail != null -> {
      val image = remember(thumbnail) { thumbnail.asImageBitmap() }
      Image(bitmap = image, contentDescription = null, modifier = Modifier.fillMaxSize())
    }
    colors != null -> Spacer(Modifier.fillMaxSize().background(Brush.verticalGradient(colors.map { Color(it) })))
    id == WallpaperConfig.DARK -> Spacer(Modifier.fillMaxSize().background(com.immortal.launcher.ui.theme.ApprovedDarkPalette.background))
    else -> Spacer(Modifier.fillMaxSize().background(Color(0xFF333740)))
  }
}

@Composable
private fun WallpaperSwatch(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    preview: @Composable () -> Unit,
) {
  Column(horizontalAlignment = Alignment.CenterHorizontally,
      modifier = modifier.tvFocusable(RoundedCornerShape(16.dp), enabled = enabled) { onClick() }
          .semantics {
            role = Role.RadioButton
            this.selected = selected
            contentDescription = "Wallpaper $label"
          }) {
    Box(Modifier.fillMaxWidth().aspectRatio(240f / 145f).clip(RoundedCornerShape(16.dp))
        .border(if (selected) 3.dp else 1.dp, if (selected) IosBlue else IosDivider, RoundedCornerShape(16.dp))) {
      preview()
    }
    Text(label, color = (if (selected) IosBlue else IosSecondary).copy(alpha = if (enabled) 1f else .55f),
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        fontSize = Sizes.rowSub, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
  }
}
