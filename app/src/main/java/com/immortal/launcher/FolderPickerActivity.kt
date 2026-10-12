/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.Manifest
import android.app.Activity
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import java.io.File

class FolderPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PortalPrimeTheme(darkTheme = false) { FolderPicker { finish() } } }
    }
}

private data class ApprovedSourcesFolderListing(val accessible: Boolean, val folders: List<File>)

@Composable
private fun FolderPicker(onCancel: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val work = rememberApprovedSourcesWork(requiresAdvanced = false)
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.READ_EXTERNAL_STORAGE) }
    var current by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val summaries = remember { mutableStateMapOf<String, MediaSummary>() }
    val roots by approvedSourcesIoState<List<StorageRoot>?>(null, granted) {
        if (granted) LocalMedia.storageRoots() else null
    }
    val path = current
    val listState = remember(path) { LazyListState() }
    val listing by approvedSourcesIoState<ApprovedSourcesFolderListing?>(null, path, granted) {
        if (!granted || path == null) null else {
            val folder = File(path)
            val accessible = LocalMedia.isAccessible(path)
            val children = if (accessible) runCatching { folder.listFiles() }.getOrNull()?.filter {
                approvedSourcesCheckInterrupted()
                it.isDirectory && it.canRead() && !it.name.startsWith(".")
            }?.sortedWith(compareBy<File> { approvedSourcesCheckInterrupted(); it.name.lowercase() })
                .orEmpty() else emptyList()
            ApprovedSourcesFolderListing(accessible, children)
        }
    }
    val cachedSummary = path?.let { summaries[it] }
    val summary by approvedSourcesIoState<MediaSummary?>(null, path, granted) {
        if (!granted || path == null) null else cachedSummary ?: approvedSourcesSummary(path, sampleCount = 24)
    }
    LaunchedEffect(path, summary) {
        if (path != null) summary?.let {
            if (summaries.size >= 64 && path !in summaries) summaries.remove(summaries.keys.first())
            summaries[path] = it
        }
    }
    fun navigate(next: String?) { work.cancel(); status = null; current = next }
    fun up() {
        val here = current
        navigate(if (here == null || roots.orEmpty().any { it.path == here }) null else File(here).parent)
    }
    val cancel = { work.close(); onCancel() }
    val back = { if (current == null) cancel() else up() }
    BackHandler(onBack = back)
    val palette = LocalApprovedPalette.current
    ApprovedSourcesPage("Choose photo folder", onBack = back) {
        if (!granted) {
            SectionLabel("Photo access")
            Card {
                NavRow("Allow photo access", subLabel = "Immortal needs Android storage permission to read your photos.") {
                    permission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            return@ApprovedSourcesPage
        }
        if (path != null) {
            Text(path, color = palette.text, fontSize = Sizes.titlePage, lineHeight = 52.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                modifier = Modifier.padding(bottom = 20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ApprovedSourcesButton("Up", primary = false, onClick = ::up)
                ApprovedSourcesButton(if (work.busy) "Checking…" else "Use this folder",
                    enabled = !work.busy && listing?.accessible == true) {
                    work.start(work = { LocalMedia.isAccessible(path) }) { result ->
                        if (result.getOrDefault(false) && activity != null) {
                            work.close()
                            ScreensaverConfig.setFolder(context, path)
                            approvedSourcesReturn(activity)
                        } else status = "Can't read this folder. Check photo access or reconnect the drive."
                    }
                }
            }
            Text(summaryLine(summary), color = palette.secondary, fontSize = 23.sp,
                modifier = Modifier.padding(top = 16.dp))
        }
        status?.let { ApprovedSourcesStatus(it) }
        SectionLabel(if (path == null) "Folders" else "Subfolders")
        // The folder group keeps the shared card geometry while virtualizing its real rows.
        Card {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 620.dp), state = listState) {
                if (path == null) {
                    when {
                        roots == null -> item(key = "loading") { FolderHint("Finding photo folders…") }
                        roots.orEmpty().isEmpty() -> item(key = "empty") {
                            FolderHint("No folders found yet. Add photos to your Portal's storage.")
                        }
                        else -> items(roots.orEmpty(), key = { it.path }) { root ->
                            if (root.path != roots.orEmpty().firstOrNull()?.path) Divider()
                            FolderLinkRow(root.label, root.path) { navigate(root.path) }
                        }
                    }
                } else {
                    when {
                        listing == null -> item(key = "loading") { FolderHint("Loading folders…") }
                        listing?.accessible == false -> item(key = "unreadable") {
                            FolderHint("Can't read this folder. Check photo access or reconnect the drive.")
                        }
                        listing?.folders.orEmpty().isEmpty() -> item(key = "empty") {
                            FolderHint("No subfolders here. Photos and videos in this folder appear below.")
                        }
                    }
                    items(listing?.folders.orEmpty(), key = { it.absolutePath }) { dir ->
                        if (dir.absolutePath != listing?.folders?.firstOrNull()?.absolutePath) Divider()
                        FolderRow(dir, summaries) { navigate(dir.absolutePath) }
                    }
                }
            }
        }
        if (path != null) {
            val previewState = remember(path) { LazyListState() }
            val samples = summary?.samples.orEmpty()
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp), state = previewState,
                verticalArrangement = Arrangement.spacedBy(24.dp)) {
                items(samples.chunked(3), key = { "preview:${it.first().path}" }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        row.forEach { item -> key(item.path) {
                            FolderThumb(item, Modifier.weight(1f).aspectRatio(1.5f))
                        } }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        ApprovedSettingsTextButton("Cancel", cancel)
    }
}

@Composable
private fun FolderRow(dir: File, cache: MutableMap<String, MediaSummary>, onClick: () -> Unit) {
    val path = dir.absolutePath
    val cached = cache[path]
    val summary by approvedSourcesIoState<MediaSummary?>(null, path) {
        cached ?: approvedSourcesSummary(path, sampleCount = 24)
    }
    LaunchedEffect(path, summary) {
        summary?.let {
            if (cache.size >= 64 && path !in cache) cache.remove(cache.keys.first())
            cache[path] = it
        }
    }
    FolderLinkRow(dir.name, summaryLine(summary), onClick)
}

/** Folder destinations use the approved folder affordance, rather than a connection chevron. */
@Composable
private fun FolderLinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    val palette = LocalApprovedPalette.current
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = Sizes.rowMinHeight)
        .tvFocusableRow(focusFill = palette.selected, onClick = onClick)
        .padding(horizontal = 28.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        ApprovedSettingsLabel(title, subtitle, Modifier.weight(1f))
        ApprovedIcon("folder", tint = palette.text, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun FolderThumb(item: MediaItem, modifier: Modifier) {
    val palette = LocalApprovedPalette.current
    val density = LocalDensity.current
    BoxWithConstraints(modifier.clip(RoundedCornerShape(20.dp)).background(palette.surface2),
        contentAlignment = Alignment.Center) {
        val target = if (item.isVideo) null else with(density) {
            ApprovedSourcePreviewSize(maxWidth.roundToPx().coerceAtLeast(1), maxHeight.roundToPx().coerceAtLeast(1))
        }
        val bitmap by approvedSourcesIoState<Bitmap?>(null, item.path, target) {
            approvedSourcesThumbnail(item, target)
        }
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = if (item.isVideo) "Video preview" else "Photo preview",
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (item.isVideo) ApprovedIcon("play", tint = palette.text, modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun FolderHint(text: String) {
    Text(text, color = LocalApprovedPalette.current.secondary, fontSize = 23.sp, lineHeight = 34.sp,
        modifier = Modifier.padding(26.dp))
}

private fun summaryLine(summary: MediaSummary?): String {
    if (summary == null) return "Scanning…"
    if (summary.total == 0) return if (summary.capped) "No photos or videos found yet · scan limit reached"
        else "No photos or videos"
    val plus = if (summary.capped) "+" else ""
    return listOfNotNull(
        if (summary.photos > 0) "${summary.photos}$plus photo${if (summary.photos == 1 && !summary.capped) "" else "s"}" else null,
        if (summary.videos > 0) "${summary.videos}$plus video${if (summary.videos == 1 && !summary.capped) "" else "s"}" else null,
    ).joinToString(" · ")
}
