/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.immortal.launcher.settings.SettingsRegistry
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import com.immortal.launcher.ui.theme.LocalAdvancedSettings
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import org.json.JSONObject

class ScreensaverSourcesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PortalPrimeTheme(darkTheme = false) { ScreensaverSourcesScreen() } }
    }
}

@Composable
private fun ScreensaverSourcesScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    ApprovedSourcesPage("Photo sources", onBack = { activity?.finish() }) {
        ScreensaverSourcesContent(onDone = { activity?.let { approvedSourcesReturn(it) } })
    }
}

@Composable
internal fun ScreensaverSourcesContent(onDone: () -> Unit) {
    val context = LocalContext.current
    val navigate = rememberApprovedSettingsNavigator()
    val owner = LocalLifecycleOwner.current
    var settings by remember { mutableStateOf(ScreensaverConfig.load(context)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                settings = ScreensaverConfig.load(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val path = settings.folderPath.takeIf { settings.usesFolder }
    val localStatus by approvedSourcesIoState<ApprovedSourcesFolderStatus?>(null, path) {
        if (path == null) null else {
            val accessible = LocalMedia.isAccessible(path)
            ApprovedSourcesFolderStatus(accessible, if (accessible) approvedSourcesSummary(path) else null)
        }
    }
    fun openSource(page: String) = navigate(approvedSettingsRoute(page))
    val showAdvanced = LocalAdvancedSettings.current
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            SourceTile("Calder photos", "A calming built-in photo feed. No setup needed.",
                settings.source == ScreensaverConfig.SOURCE_DEFAULT,
                "photo", Modifier.weight(1f)) {
                ScreensaverConfig.useDefault(context)
                onDone()
            }
            SourceTile("My photos & videos", "Choose a photo folder on your Portal.",
                settings.usesFolder, "folder", Modifier.weight(1f)) { openSource("folder") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            SourceTile("Shared album link", "Use an iCloud, Google Photos or Synology share link.",
                settings.usesUrl, "link", Modifier.weight(1f)) { openSource("album") }
            SourceTile("Connect your phone", "Set up photos from another device on your Wi-Fi.",
                false, "phone", Modifier.weight(1f)) { openSource("phone") }
        }
    }
    if (settings.source != ScreensaverConfig.SOURCE_DEFAULT) {
        SectionLabel("Current source")
        Card {
            ApprovedSourcesInfoRow("Selected", sourceStatus(settings, localStatus))
            val page = sourcePage(settings)
            if (showAdvanced || page in listOf("folder", "album")) {
                Divider()
                NavRow("Change source connection", "") { openSource(page) }
            }
        }
        if (!showAdvanced && sourcePage(settings) in listOf("immich", "smb", "dav", "web")) {
            FooterNote("Your current source remains selected. Its connection is available in Advanced settings.")
        }
    }
    if (showAdvanced) {
        SectionLabel("Advanced options")
        Card {
            NavRow("Immich") { openSource("immich") }
            Divider()
            NavRow("SMB / NAS") { openSource("smb") }
            Divider()
            NavRow("WebDAV") { openSource("dav") }
            Divider()
            NavRow("Web page") { openSource("web") }
            if (settings.usesUrl) {
                Divider()
                PickerRow("Refresh album", settings.albumRefreshMin,
                    listOf(15, 30, 45, 60, 120, 180, 240, 360, 720, 1440).map {
                        PickerOption(it, if (it < 60) "$it min" else "${it / 60} hours")
                    }) { minutes ->
                    SettingsRegistry.apply(context, "screensaver",
                        JSONObject().put("albumRefreshMin", minutes))
                    settings = ScreensaverConfig.load(context)
                }
            }
        }
    }
    FooterNote("If a source can't be read, Calder falls back to its built-in photos.")
}

@Composable
private fun SourceTile(
    title: String, description: String, selected: Boolean, icon: String,
    modifier: Modifier, onClick: () -> Unit,
) {
    val palette = LocalApprovedPalette.current
    val shape = RoundedCornerShape(24.dp)
    Surface(color = palette.surface, shape = shape,
        border = BorderStroke(1.dp, if (selected) palette.accent else palette.divider),
        modifier = modifier.defaultMinSize(minHeight = 180.dp)
            .semantics { this.selected = selected }
            .tvFocusable(shape, focusScale = 1f, ringColor = palette.accent, onClick = onClick)) {
        Row(Modifier.padding(30.dp), horizontalArrangement = Arrangement.spacedBy(23.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Surface(color = palette.accent, shape = RoundedCornerShape(17.dp)) {
                ApprovedIcon(icon,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(64.dp).padding(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = palette.text, fontSize = 28.sp, lineHeight = 38.sp,
                    fontWeight = FontWeight.Medium)
                Text(description, color = palette.secondary, fontSize = 23.sp, lineHeight = 32.sp,
                    modifier = Modifier.padding(top = 9.dp))
            }
            ApprovedSettingsChevron()
        }
    }
}

private fun sourcePage(s: ScreensaverConfig.Settings): String = when (s.source) {
    ScreensaverConfig.SOURCE_FOLDER -> "folder"
    ScreensaverConfig.SOURCE_URL -> "album"
    ScreensaverConfig.SOURCE_IMMICH -> "immich"
    ScreensaverConfig.SOURCE_SMB -> "smb"
    ScreensaverConfig.SOURCE_DAV -> "dav"
    ScreensaverConfig.SOURCE_WEBURL -> "web"
    else -> "sources"
}

private fun sourceStatus(s: ScreensaverConfig.Settings, local: ApprovedSourcesFolderStatus?): String = when {
    s.usesFolder -> "${LocalMedia.displayName(s.folderPath.orEmpty())} · " +
        (when {
            local == null -> "Scanning…"
            !local.accessible -> "Can't read this folder; showing built-in photos"
            local.summary?.total == 0 -> if (local.summary?.capped == true) "Scan limit reached" else "No photos or videos"
            else -> local.summary?.let { "${it.total}${if (it.capped) "+" else ""} photos and videos" }.orEmpty()
        })
    s.usesUrl -> RemoteAlbum.providerName(s.albumUrl.orEmpty())
    s.usesImmich -> "Immich · ${s.immichAlbumName ?: "Whole library"}"
    s.usesSmb -> "Network share · ${s.smbShare}"
    s.usesDav -> "WebDAV folder"
    s.usesWebUrl -> "Web page"
    s.source != ScreensaverConfig.SOURCE_DEFAULT -> "Connection needs setup · showing built-in photos"
    else -> "Calder photos"
}
