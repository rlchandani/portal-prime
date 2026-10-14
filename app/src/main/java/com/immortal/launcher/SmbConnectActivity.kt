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

class SmbConnectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PortalPrimeTheme(darkTheme = false) { SmbConnectScreen { finish() } } }
    }
}

@Composable
private fun SmbConnectScreen(onCancel: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val existing = remember { ScreensaverConfig.load(context) }
    val work = rememberApprovedSourcesWork()
    var host by remember { mutableStateOf(existing.smbHost.orEmpty()) }
    var share by remember { mutableStateOf(existing.smbShare.orEmpty()) }
    var path by remember { mutableStateOf(existing.smbPath.orEmpty()) }
    var user by remember { mutableStateOf(existing.smbUser.orEmpty()) }
    var pass by remember { mutableStateOf(existing.smbPass.orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf(true) }
    var connectedCount by remember { mutableStateOf<Int?>(null) }
    val cancel = { work.close(); onCancel() }
    BackHandler(onBack = cancel)

    fun connect() {
        if (work.busy) return
        statusError = true
        if (host.isBlank() || share.isBlank()) { status = "Enter at least the server and share name."; return }
        val server = host.trim()
        val shareName = share.trim()
        val folder = path.trim()
        val username = user.trim()
        val password = pass
        status = null
        work.start(work = {
            val source = SmbSource(server, shareName, folder, username, password)
            try {
                if (!source.connect()) error("connection")
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                source.listImagesOrNull()?.size
            } finally { source.close() }
        }) { result ->
            when (val check = photoFolderCheck(result)) {
                is PhotoFolderCheck.Photos -> {
                    ScreensaverConfig.setSmb(context, server, shareName, folder, username, password)
                    connectedCount = check.count
                }
                PhotoFolderCheck.Empty -> {
                    status = EMPTY_PHOTO_FOLDER_MESSAGE
                    statusError = false
                }
                PhotoFolderCheck.Unavailable -> status = "Couldn't read that folder. Check the server, share name, folder, access permissions and credentials, then try again. Your current photo source hasn't changed."
            }
        }
    }

    ApprovedSourcesPage("Connect SMB / NAS", onBack = cancel, advancedOnly = true) {
        val count = connectedCount
        if (count != null) {
            ApprovedSettingsSectionLabel("Connected")
            Card {
                ApprovedSourcesInfoRow("Photos found", count.toString())
                Divider()
                ApprovedSourcesInfoRow("Selected source", "Network share")
            }
            Spacer(Modifier.height(30.dp))
            ApprovedSourcesButton("Done") {
                work.close()
                activity?.let { approvedSourcesReturn(it) }
            }
        } else {
            ApprovedSourcesForm("Network share", "Enter your server, share name, an optional folder inside the share, and credentials. Immortal tests the connection and counts photos before selecting this source.") {
                ApprovedSourcesField("Server", host, { host = it }, "192.168.1.50", enabled = !work.busy)
                ApprovedSourcesField("Share name", share, { share = it }, "media", enabled = !work.busy)
                ApprovedSourcesField("Folder in the share", path, { path = it }, "photos (optional)", enabled = !work.busy)
                ApprovedSourcesField("Username", user, { user = it }, enabled = !work.busy)
                ApprovedSourcesField("Password", pass, { pass = it }, secret = true, enabled = !work.busy)
                ApprovedSourcesStatus(status, error = statusError)
                ApprovedSourcesActions(if (work.busy) "Connecting…" else "Test & connect",
                    enabled = !work.busy && host.isNotBlank() && share.isNotBlank(),
                    onConfirm = ::connect, onCancel = cancel)
            }
        }
    }
}
