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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.immortal.launcher.ui.theme.LocalAdvancedSettings
import com.immortal.launcher.ui.theme.PortalPrimeTheme

class ImmichConnectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestAlbums = intent.getStringExtra(APPROVED_SETTINGS_PAGE) == "albums"
        // Keep the requested album destination for the shared shell, including its prerequisite.
        setContent { PortalPrimeTheme(darkTheme = false) { ImmichConnectScreen(requestAlbums) { finish() } } }
    }
}

private data class VerifiedImmich(val url: String, val key: String, val albums: List<ImmichSource.Album>)

@Composable
private fun ImmichConnectScreen(requestAlbums: Boolean, onCancel: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val existing = remember { ScreensaverConfig.load(context) }
    val work = rememberApprovedSourcesWork()
    var url by remember { mutableStateOf(existing.immichUrl.orEmpty()) }
    var key by remember { mutableStateOf(existing.immichKey.orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }
    var verified by remember { mutableStateOf<VerifiedImmich?>(null) }
    var selectedId by remember { mutableStateOf(existing.immichAlbumId) }
    val cancel = { work.close(); onCancel() }
    BackHandler(onBack = cancel)

    fun connect() {
        if (work.busy) return
        val address = url.trim()
        val apiKey = key.trim()
        if (address.isEmpty() || apiKey.isEmpty()) {
            status = "Enter both the server address and an API key."
            return
        }
        status = null
        work.start(work = {
            if (!ImmichSource.testConnection(address, apiKey)) error("connection")
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            // An empty list is a valid library; null is an album request failure.
            val albums = ImmichSource.listAlbums(address, apiKey) ?: error("albums")
            VerifiedImmich(address, apiKey, albums)
        }) { result ->
            result.onSuccess {
                activity?.intent?.putExtra(APPROVED_SETTINGS_PAGE, "albums")
                verified = it
                selectedId = existing.immichAlbumId.takeIf { id -> it.albums.any { album -> album.id == id } }
            }.onFailure {
                status = "Couldn't load Immich albums. Check the address, API key and network, then try again."
            }
        }
    }

    val advanced = LocalAdvancedSettings.current
    LaunchedEffect(requestAlbums, advanced) {
        if (requestAlbums && advanced && verified == null && url.isNotBlank() && key.isNotBlank()) connect()
    }

    val connection = verified
    ApprovedSourcesPage(if (requestAlbums || connection != null) "Choose Immich album" else "Connect Immich",
        onBack = cancel, advancedOnly = true) {
        if (connection == null) {
            ApprovedSourcesForm(if (requestAlbums) "Connect Immich first" else "Connect your photo library",
                "Enter your Immich server address and API key. Create a key in Immich → Account Settings → API Keys. " +
                    "After connecting, choose an album or your whole library. Your current source stays selected until you confirm.") {
                ApprovedSourcesField("Server address", url, { url = it }, "http://192.168.1.50:2283", enabled = !work.busy)
                ApprovedSourcesField("API key", key, { key = it }, secret = true, enabled = !work.busy)
                ApprovedSourcesStatus(status)
                ApprovedSourcesActions(if (work.busy) "Connecting…" else "Test & connect",
                    enabled = !work.busy && url.isNotBlank() && key.isNotBlank(),
                    onConfirm = ::connect, onCancel = cancel)
            }
        } else {
            ApprovedSettingsSectionLabel("Immich albums")
            Card {
                // Bound the collection inside the shared scrolling shell; compose only visible albums.
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                    item(key = "whole-library") {
                        SelectableRow("Whole library", "All your photos", selectedId == null) { selectedId = null }
                    }
                    items(connection.albums, key = { "album:${it.id}" }) { album ->
                        Divider()
                        SelectableRow(album.name, "${album.count} photo${if (album.count == 1) "" else "s"}",
                            selectedId == album.id) { selectedId = album.id }
                    }
                }
            }
            if (connection.albums.isEmpty()) {
                ApprovedSourcesStatus("No albums found. You can use your whole library.", error = false)
            }
            Spacer(Modifier.height(30.dp))
            ApprovedSourcesActions("Use this album", onConfirm = {
                if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                    work.close()
                    val album = connection.albums.firstOrNull { it.id == selectedId }
                    // No writes occur during testing or selection. Save the confirmed group in one
                    // main-thread action, setting the active source last with the existing setters.
                    ScreensaverConfig.setImmichAlbum(context, album?.id, album?.name)
                    ScreensaverConfig.setImmich(context, connection.url, connection.key)
                    approvedSourcesReturn(activity)
                }
            }, onCancel = cancel)
        }
    }
}
