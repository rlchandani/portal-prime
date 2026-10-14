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
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.immortal.launcher.ui.theme.PortalPrimeTheme

class DavConnectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PortalPrimeTheme(darkTheme = false) { DavConnectScreen { finish() } } }
    }
}

@Composable
private fun DavConnectScreen(onCancel: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val existing = remember { ScreensaverConfig.load(context) }
    val work = rememberApprovedSourcesWork()
    var url by remember { mutableStateOf(existing.davUrl.orEmpty()) }
    var user by remember { mutableStateOf(existing.davUser.orEmpty()) }
    var pass by remember { mutableStateOf(existing.davPass.orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf(true) }
    var connectedCount by remember { mutableStateOf<Int?>(null) }
    val cancel = { work.close(); onCancel() }
    BackHandler(onBack = cancel)

    fun connect() {
        if (work.busy) return
        statusError = true
        if (url.isBlank()) { status = "Enter the WebDAV folder address."; return }
        val address = url.trim()
        val username = user.trim()
        val password = pass
        status = null
        work.start(work = {
            DavSource.listImageUrls(address, username, password)?.size
        }) { result ->
            when (val check = photoFolderCheck(result)) {
                is PhotoFolderCheck.Photos -> {
                    ScreensaverConfig.setDav(context, address, username, password)
                    connectedCount = check.count
                }
                PhotoFolderCheck.Empty -> {
                    status = EMPTY_PHOTO_FOLDER_MESSAGE
                    statusError = false
                }
                PhotoFolderCheck.Unavailable -> status = "Couldn't read that folder. Check the address, access permissions and credentials, then try again. Your current photo source hasn't changed."
            }
        }
    }

    ApprovedSourcesPage("Connect WebDAV", onBack = cancel, advancedOnly = true) {
        val count = connectedCount
        if (count != null) {
            ApprovedSettingsSectionLabel("Connected")
            Card {
                ApprovedSourcesInfoRow("Photos found", count.toString())
                Divider()
                ApprovedSourcesInfoRow("Selected source", "WebDAV folder")
            }
            Spacer(Modifier.height(30.dp))
            ApprovedSourcesButton("Done") {
                work.close()
                activity?.let { approvedSourcesReturn(it) }
            }
        } else {
            ApprovedSourcesForm("WebDAV folder", "Paste the full WebDAV folder URL from your NAS or Nextcloud. Enter credentials if required. Immortal checks for photos before selecting this source.") {
                ApprovedSourcesField("WebDAV folder address", url, { url = it }, "http://nas:30035/share/photos/", enabled = !work.busy)
                ApprovedSourcesField("Username (optional)", user, { user = it }, enabled = !work.busy)
                ApprovedSourcesField("Password (optional)", pass, { pass = it }, secret = true, enabled = !work.busy)
                ApprovedSourcesStatus(status, error = statusError)
                ApprovedSourcesActions(if (work.busy) "Connecting…" else "Test & connect",
                    enabled = !work.busy && url.isNotBlank(),
                    onConfirm = ::connect, onCancel = cancel)
            }
        }
    }
}
