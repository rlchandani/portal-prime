/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class LocalApk(val file: File, val label: String, val version: String, val pkg: String, val icon: ImageBitmap?)

/** Local APKs retain the existing HeadlessInstaller daemon/signature/permission behavior. */
class ApkBrowserActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { ApkBrowserScreen() } }
  }
}

@Composable
private fun ApkBrowserScreen() {
  val context = LocalContext.current
  val activity = context as? Activity
  val resume = rememberApprovedResume()
  var granted by remember { mutableStateOf(false) }
  var apks by remember { mutableStateOf<List<LocalApk>?>(null) }
  var refresh by remember { mutableStateOf(0) }
  var error by remember { mutableStateOf<String?>(null) }
  var selected by remember { mutableStateOf<LocalApk?>(null) }
  val status = remember { mutableStateMapOf<String, String>() }
  val scope = rememberCoroutineScope()
  val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
  LaunchedEffect(resume) { granted = context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED }
  LaunchedEffect(granted, refresh) {
    apks = null; error = null
    if (granted) {
      try { apks = withContext(Dispatchers.IO) { scanApks(context.applicationContext) } }
      catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
      catch (failure: Exception) { error = "Your downloaded APKs couldn’t be read."; apks = emptyList() }
    } else apks = emptyList()
  }
  ApprovedAppsShell("store", { activity?.finish() }) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 56.dp), contentPadding = PaddingValues(top = 36.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
      item("heading") {
        Column(Modifier.fillMaxWidth()) {
          ApprovedAppsHeading("Install an APK", "Choose a downloaded Android app to install.")
          Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ApprovedAppsButton("‹  App Store") { activity?.finish() }
            if (granted) ApprovedAppsButton("Refresh files") { refresh++ }
          }
        }
      }
      if (!granted) item("permission") {
        ApkInformation("Allow file access", "Immortal needs access to your downloads to find APK files.") {
          Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ApprovedAppsButton("Allow file access", primary = true) { permission.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE) }
            ApprovedAppsButton("App permissions") { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) } }
          }
        }
      }
      if (InstallDaemon.installPaused(context)) item("paused") {
        ApkInformation("Installing new apps is paused", "Connect to your computer and run Immortal setup to restore the first-generation Portal installer. Everything else keeps working.") {}
      }
      when {
        error != null -> item("error") { ApkInformation("Couldn’t load files", error!!) { ApprovedAppsButton("Try again") { refresh++ } } }
        apks == null -> item("loading") { Text("Finding APK files…", color = IosSecondary, fontSize = 24.sp) }
        granted && apks!!.isEmpty() -> item("empty") { ApkInformation("No APK files found", "Download an APK to this Portal’s Downloads folder, then refresh this list.") {} }
      }
      items(apks.orEmpty(), key = { it.file.absolutePath }, contentType = { "apk" }) { apk ->
        val busy = status[apk.file.absolutePath] == "Installing…"
        Surface(color = IosCard, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, IosDivider), modifier = Modifier.fillMaxWidth()) {
          Row(Modifier.heightIn(min = 158.dp).padding(26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(25.dp)) {
            if (apk.icon != null) Image(apk.icon, null, Modifier.size(85.dp).clip(RoundedCornerShape(22.dp)))
            else ApprovedAppsIcon(apk.pkg, apk.label, 85.dp)
            Column(Modifier.weight(1f)) {
              Text(apk.label, color = IosLabel, fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium)
              Text("v${apk.version} · ${apk.file.name}", color = IosSecondary, fontSize = 22.sp, lineHeight = 30.sp, modifier = Modifier.padding(top = 7.dp))
              status[apk.file.absolutePath]?.let { Text(it, color = IosSecondary, fontSize = 22.sp, lineHeight = 30.sp, modifier = Modifier.padding(top = 7.dp)) }
            }
            ApprovedAppsButton(if (busy) "Installing…" else "Install", enabled = !busy && !InstallDaemon.installPaused(context)) { selected = apk }
          }
        }
      }
    }
  }
  selected?.let { apk ->
    Dialog(onDismissRequest = { selected = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
      Surface(color = IosCard, shape = RoundedCornerShape(28.dp), border = BorderStroke(1.dp, IosDivider), modifier = Modifier.width(760.dp)) {
        Column(Modifier.padding(36.dp)) {
          Text("Install ${apk.label}?", color = IosLabel, fontSize = 34.sp, fontWeight = FontWeight.Medium)
          Text("${apk.file.name}\nAndroid verifies this APK’s compatibility and signing certificate before installing it.", color = IosSecondary, fontSize = 24.sp, lineHeight = 34.sp, modifier = Modifier.padding(top = 20.dp))
          Row(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ApprovedAppsButton("Cancel") { selected = null }
            ApprovedAppsButton("Install", primary = true) {
              selected = null
              val key = apk.file.absolutePath
              if (InstallDaemon.installPaused(context)) status[key] = "Paused — connect to your computer to add apps"
              else {
                status[key] = "Installing…"
                scope.launch {
                  try {
                    val ok = withContext(Dispatchers.IO) { HeadlessInstaller.install(context.applicationContext, apk.file, apk.pkg) }
                    status[key] = if (ok) "Installed ✓" else "Install failed or was cancelled"
                  } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                  catch (failure: Exception) { status[key] = "Install failed: ${failure.message ?: "unknown error"}" }
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ApkInformation(title: String, body: String, content: @Composable () -> Unit) {
  Surface(color = IosCard, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, IosDivider), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(34.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
      Text(title, color = IosLabel, fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium)
      Text(body, color = IosSecondary, fontSize = 24.sp, lineHeight = 34.sp)
      content()
    }
  }
}
private fun scanApks(context: android.content.Context): List<LocalApk> {
  val dirs =
      listOfNotNull(
          Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
          @Suppress("DEPRECATION") Environment.getExternalStorageDirectory(),
          context.getExternalFilesDir(null),
      )
  val pm = context.packageManager
  return dirs
      .flatMap { d -> runCatching { d.listFiles { f -> f.extension.equals("apk", true) }?.toList() }.getOrNull() ?: emptyList() }
      .distinctBy { it.absolutePath }
      .mapNotNull { f ->
        runCatching {
              val info = pm.getPackageArchiveInfo(f.absolutePath, 0)
              val ai = info?.applicationInfo
              ai?.sourceDir = f.absolutePath
              ai?.publicSourceDir = f.absolutePath
              LocalApk(
                  file = f,
                  label = ai?.loadLabel(pm)?.toString() ?: f.name,
                  version = info?.versionName ?: "?",
                  pkg = info?.packageName ?: f.name,
                  icon = ai?.loadIcon(pm)?.toBitmap(120, 120)?.asImageBitmap(),
              )
            }
            .getOrNull()
      }
      .sortedByDescending { it.file.lastModified() }
}
