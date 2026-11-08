/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Revision 04 native catalog; the installer remains the existing daemon/session pipeline. */
class StoreActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { StoreRoot() } }
  }
}

@Composable
private fun StoreRoot() {
  val context = LocalContext.current
  val activity = context as? Activity
  val resume = rememberApprovedResume()
  var apps by remember { mutableStateOf<List<CatalogApp>>(emptyList()) }
  var loaded by remember { mutableStateOf(false) }
  var retry by remember { mutableStateOf(0) }
  val status = remember { mutableStateMapOf<String, String>() }
  var updates by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
  var query by remember { mutableStateOf("") }
  var detail by remember { mutableStateOf<CatalogApp?>(null) }
  var pendingConfirm by remember { mutableStateOf<Intent?>(null) }
  var confirmApp by remember { mutableStateOf<CatalogApp?>(null) }
  var alive by remember { mutableStateOf(true) }
  var showUpdates by remember { mutableStateOf(false) }
  var findingUpdates by remember { mutableStateOf(false) }
  var generation by remember { mutableStateOf(0) }
  var updateGeneration by remember { mutableIntStateOf(0) }
  var installedEpoch by remember { mutableIntStateOf(0) }
  val installedRequest = remember(apps, resume, installedEpoch) {
    InstalledCatalogRequest(apps, resume, installedEpoch)
  }
  val installedSnapshot by produceState<InstalledCatalogSnapshot?>(null, installedRequest) {
    value = null
    val versions = withContext(Dispatchers.IO) {
      val loadingContext = currentCoroutineContext()
      apps.mapNotNull { app ->
        loadingContext.ensureActive()
        StoreLoadingDiagnostics.uiRead()
        StoreCatalog.installedVersionCode(context.applicationContext, app.packageName)
            ?.let { app.packageName to it }
      }.toMap()
    }
    value = InstalledCatalogSnapshot(installedRequest, versions)
  }
  val installedVersions = installedSnapshot?.versionsFor(installedRequest)
  val availableUpdates = remember(updates, installedVersions) {
    installedVersions?.let { versions ->
      updates.filter { (pkg, latest) -> versions[pkg]?.let { it < latest } == true }
    } ?: updates
  }
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

  fun markInstalled(pkg: String) {
    status[pkg] = "Installed ✓"
    updates = updates - pkg
    installedEpoch++
  }

  DisposableEffect(retry) {
    loaded = false
    val request = ++generation
    val cancelCatalog = StoreCatalog.loadCatalog(context.applicationContext) {
      if (alive && generation == request) { apps = it; loaded = true }
    }
    onDispose {
      generation++
      cancelCatalog()
    }
  }
  DisposableEffect(apps, resume, installedEpoch, installedVersions) {
    var scanAlive = true
    val request = ++updateGeneration
    findingUpdates = apps.isNotEmpty()
    val cancelUpdates = if (apps.isNotEmpty() && installedVersions != null) {
      StoreCatalog.findUpdates(context.applicationContext, apps, installedVersions) {
        if (alive && scanAlive && updateGeneration == request) {
          updates = it
          findingUpdates = false
        }
      }
    } else null
    onDispose {
      scanAlive = false
      updateGeneration++
      cancelUpdates?.invoke()
    }
  }
  LaunchedEffect(pendingConfirm) {
    pendingConfirm?.let { launcher.launch(it); pendingConfirm = null }
  }
  DisposableEffect(Unit) {
    alive = true
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context, intent: Intent) {
        val pkg = intent.getStringExtra(STORE_EXTRA_PKG) ?: return
        when (val result = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
          PackageInstaller.STATUS_PENDING_USER_ACTION -> {
            pendingConfirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            status[pkg] = "Confirm to install…"
          }
          PackageInstaller.STATUS_SUCCESS -> markInstalled(pkg)
          PackageInstaller.STATUS_FAILURE_ABORTED -> status[pkg] = "Installation cancelled"
          else -> status[pkg] = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { "Install failed: $it" } ?: "Install failed (status=$result)"
        }
      }
    }
    val filter = IntentFilter(STORE_INSTALL_ACTION)
    if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
    onDispose { alive = false; generation++; runCatching { context.unregisterReceiver(receiver) } }
  }
  val install: (CatalogApp) -> Unit = { app ->
    if (!storeBusy(status[app.packageName]) && StoreCatalog.isCompatible(app)) {
      StoreCatalog.install(context.applicationContext, app) { pkg, message ->
        if (alive) {
          if (message == "Installed ✓") markInstalled(pkg) else status[pkg] = message
        }
      }
    }
  }
  BackHandler { when { confirmApp != null -> confirmApp = null; showUpdates -> showUpdates = false; detail != null -> detail = null; else -> activity?.finish() } }
  val scroll = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
  val scope = rememberCoroutineScope()
  ApprovedAppsShell("store", { activity?.finish() }, onSearch = { scope.launch { detail = null; scroll.animateScrollToItem(0) } }) {
    val current = detail
    if (current == null) {
      StoreBrowse(apps, status, availableUpdates, installedVersions, query, { query = it }, { detail = it }, { confirmApp = it },
          loaded, { retry++ }, { showUpdates = true }, scroll)
    } else StoreDetail(current, status[current.packageName], availableUpdates, installedVersions?.containsKey(current.packageName),
        { detail = null }, { confirmApp = current })
  }
  confirmApp?.let { app ->
    val installed = installedVersions?.containsKey(app.packageName)
    val compatible = StoreCatalog.isCompatible(app)
    val installLabel = when {
      installed == true && app.packageName in availableUpdates -> "Update"
      installed == true -> "Reinstall"
      else -> "Install"
    }
    val canInstall = compatible && installed != null && !storeBusy(status[app.packageName])
    StoreDialog(when {
      installed == null -> "Checking ${app.name}…"
      installed == true && app.packageName in availableUpdates -> "Update ${app.name}?"
      installed == true -> "Reinstall ${app.name}?"
      else -> "Install ${app.name}?"
    }, { confirmApp = null }) {
      Text("The app will be downloaded from ${if (app.source == "fdroid") "F-Droid" else "the catalog publisher"}. Android checks its package and signature before installation.", color = IosSecondary, fontSize = 24.sp, lineHeight = 34.sp)
      Row(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ApprovedAppsButton("Cancel", onClick = { confirmApp = null })
        ApprovedAppsButton(installLabel, primary = true, enabled = canInstall) {
          if (canInstall) { confirmApp = null; install(app) }
        }
      }
    }
  }
  if (showUpdates) StoreDialog("Updates", { showUpdates = false }) {
    when {
      findingUpdates || installedVersions == null -> Text("Checking installed catalog apps…", color = IosSecondary, fontSize = 24.sp)
      availableUpdates.isEmpty() -> Text("No updates found for your installed catalog apps.", color = IosSecondary, fontSize = 24.sp)
      else -> apps.filter { it.packageName in availableUpdates }.forEach { app ->
        StoreAppCard(app, status[app.packageName], availableUpdates, installedVersions?.containsKey(app.packageName),
            { showUpdates = false; detail = app }, { showUpdates = false; confirmApp = app })
        Spacer(Modifier.height(16.dp))
      }
    }
    ApprovedAppsButton("Done", modifier = Modifier.padding(top = 24.dp)) { showUpdates = false }
  }
}

@Composable
private fun StoreBrowse(
    apps: List<CatalogApp>, status: Map<String, String>, updates: Map<String, Long>,
    installedVersions: Map<String, Long>?, query: String,
    onQuery: (String) -> Unit, onDetail: (CatalogApp) -> Unit, onInstall: (CatalogApp) -> Unit,
    loaded: Boolean, retry: () -> Unit, onUpdates: () -> Unit,
    listState: androidx.compose.foundation.lazy.grid.LazyGridState,
) {
  val context = LocalContext.current
  val advanced = com.immortal.launcher.ui.theme.LocalAdvancedSettings.current
  val filtered = remember(apps, query) { apps.filter { "${it.name} ${it.category} ${it.description} ${it.author.orEmpty()}".contains(query.trim(), ignoreCase = true) } }
  val categories = remember(filtered) { filtered.groupBy { it.category } }
  androidx.compose.foundation.lazy.grid.LazyVerticalGrid(columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(20.dp), state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 56.dp), contentPadding = PaddingValues(top = 36.dp, bottom = 24.dp)) {
    item("hero", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
      val dark = LocalApprovedPalette.current.dark
      Column(Modifier.fillMaxWidth()) {
        Surface(color = if (dark) Color(0xFF26463E) else Color(0xFFDCECE4), shape = RoundedCornerShape(28.dp), border = BorderStroke(1.dp, if (dark) Color(0xFF44675D) else Color(0xFFC2DBD0)), modifier = Modifier.fillMaxWidth()) {
          Row(Modifier.heightIn(min = 245.dp).padding(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.weight(1.35f)) {
              Text("More life for your Portal.", color = IosLabel, fontSize = 46.sp, lineHeight = 58.sp, fontWeight = FontWeight.Medium)
              Text("The actual Hearthpane catalog. Installed apps and optional downloads are distinct.", color = IosSecondary, fontSize = 24.sp, lineHeight = 34.sp, modifier = Modifier.padding(top = 18.dp))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(30.dp, Alignment.CenterHorizontally)) {
              ApprovedAppsIcon("org.videolan.vlc", "VLC", 116.dp)
              ApprovedAppsIcon("io.homeassistant.companion.android.minimal", "Home Assistant", 116.dp)
            }
          }
        }
        Spacer(Modifier.height(28.dp))
        if (InstallDaemon.installPaused(context)) { StorePausedBanner(); Spacer(Modifier.height(24.dp)) }
        ApprovedAppsSearch(query, onQuery, if (loaded) "Search all ${apps.size} apps" else "Search catalog")
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
          ApprovedAppsButton("Updates", onClick = onUpdates)
          if (advanced) ApprovedAppsButton("Install an APK") { context.startActivity(Intent(context, ApkBrowserActivity::class.java)) }
        }
      }
    }
    when {
      !loaded -> item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { Text("Loading catalog…", color = IosSecondary, fontSize = 24.sp, modifier = Modifier.padding(top = 30.dp)) }
      apps.isEmpty() -> item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
        Column(Modifier.padding(top = 30.dp)) {
          Text("The catalog couldn’t be loaded. Check your connection and try again.", color = IosSecondary, fontSize = 24.sp)
          ApprovedAppsButton("Try again", modifier = Modifier.padding(top = 20.dp), onClick = retry)
        }
      }
      filtered.isEmpty() -> item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { Text("No apps found.", color = IosSecondary, fontSize = 24.sp, modifier = Modifier.padding(top = 30.dp)) }
      else -> categories.forEach { (category, group) ->
        item("heading:$category", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { Text(category, color = IosSecondary, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 6.dp, top = 28.dp, bottom = 12.dp)) }
        items(group.size, key = { group[it].packageName }, contentType = { "catalog-app" }) { index ->
          val app = group[index]
          Box(Modifier.padding(bottom = 20.dp)) {
            StoreAppCard(app, status[app.packageName], updates, installedVersions?.containsKey(app.packageName),
                { onDetail(app) }, { onInstall(app) })
          }
        }
      }
    }
  }
}

@Composable
private fun StoreAppCard(app: CatalogApp, status: String?, updates: Map<String, Long>, installed: Boolean?,
    onDetail: () -> Unit, onInstall: () -> Unit) {
  Surface(color = IosCard, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, IosDivider), modifier = Modifier.fillMaxWidth()) {
    Row(Modifier.heightIn(min = 158.dp).padding(26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(25.dp)) {
      StoreIcon(app, 85.dp)
      Column(Modifier.weight(1f).tvFocusable(RoundedCornerShape(12.dp), focusScale = 1f, onClick = onDetail)) {
        Text(app.name, color = IosLabel, fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium)
        Text(app.description, color = IosSecondary, fontSize = 22.sp, lineHeight = 30.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
        if (status != null) Text(status, color = IosSecondary, fontSize = 18.sp, lineHeight = 25.sp, modifier = Modifier.padding(top = 8.dp))
      }
      StoreAction(app, status, updates, installed, onInstall)
    }
  }
}

private fun storeBusy(status: String?): Boolean = status in setOf("Resolving…", "Downloading…", "Installing…", "Confirm to install…")

@Composable
private fun StoreAction(app: CatalogApp, status: String?, updates: Map<String, Long>, installed: Boolean?, onInstall: () -> Unit) {
  val context = LocalContext.current
  val action = storePrimaryAction(installed, StoreCatalog.isCompatible(app), app.packageName in updates, storeBusy(status))
  val label = when (action) {
    StorePrimaryAction.OPEN -> "Open"
    StorePrimaryAction.UPDATE -> "Update"
    StorePrimaryAction.GET -> "Get"
    StorePrimaryAction.WAIT -> "Wait…"
    StorePrimaryAction.CHECKING -> "Checking…"
    StorePrimaryAction.INCOMPATIBLE -> StoreCatalog.incompatibleLabel(app.minSdk ?: 0)
  }
  val shape = RoundedCornerShape(16.dp)
  Box(Modifier.widthIn(min = 126.dp).heightIn(min = 68.dp).clip(shape).background(LocalApprovedPalette.current.surface2)
      .tvFocusable(shape, focusScale = 1f, enabled = action.enabled) {
        if (action.enabled) {
          when (action) {
            StorePrimaryAction.OPEN -> {
              runCatching {
                val launch = context.packageManager.getLaunchIntentForPackage(app.packageName)
                    ?: Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${app.packageName}"))
                context.startActivity(launch)
              }.onFailure {
                android.widget.Toast.makeText(context, "This app couldn’t be opened.",
                    android.widget.Toast.LENGTH_LONG).show()
              }
            }
            StorePrimaryAction.GET, StorePrimaryAction.UPDATE -> onInstall()
            else -> Unit
          }
        }
      }.padding(horizontal = 26.dp, vertical = 18.dp), contentAlignment = Alignment.Center) {
    Text(label, color = if (action.enabled) IosLabel else IosSecondary, fontSize = 23.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium)
  }
}

@Composable
private fun StoreIcon(app: CatalogApp, size: Dp) {
  if (approvedAppIdentity(app.packageName) != null) { ApprovedAppsIcon(app.packageName, app.name, size); return }
  val context = LocalContext.current
  var bitmap by remember(app.packageName) { mutableStateOf(StoreIcons.cached(app.packageName)) }
  DisposableEffect(app.packageName) {
    var live = true
    val request = if (bitmap == null) StoreIcons.load(context, app) { if (live) bitmap = it } else null
    onDispose { live = false; request?.cancel() }
  }
  val image = remember(bitmap) { bitmap?.asImageBitmap() }
  if (image != null) Image(image, null, Modifier.size(size).clip(RoundedCornerShape(size / 4)))
  else ApprovedAppsIcon(app.packageName, app.name, size)
}

@Composable
private fun StoreDetail(app: CatalogApp, status: String?, updates: Map<String, Long>, installed: Boolean?,
    onBack: () -> Unit, onInstall: () -> Unit) {
  val context = LocalContext.current
  val compatible = StoreCatalog.isCompatible(app)
  LazyColumn(Modifier.fillMaxSize().padding(horizontal = 56.dp), contentPadding = PaddingValues(top = 36.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
    item { ApprovedAppsButton("‹  App Store", onClick = onBack) }
    item { ApprovedAppsHeading(app.name, app.description) }
    item {
      Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
          StoreIcon(app, 140.dp)
          StoreAction(app, status, updates, installed, onInstall)
          if (installed == true && compatible && !storeBusy(status)) ApprovedAppsButton("Reinstall", onClick = onInstall)
        }
        if (status != null) Text(status, color = IosSecondary, fontSize = 24.sp, modifier = Modifier.padding(top = 20.dp))
        if (!compatible) Text(
            "The latest version needs a newer Android version than this Portal has." +
                if (installed == true) " You can still open the version already installed." else "",
            color = IosRed, fontSize = 24.sp, modifier = Modifier.padding(top = 20.dp))
      }
    }
    item {
      StoreInformationCard("About this app") { Text(app.longDescription ?: app.description, color = IosSecondary, fontSize = 25.sp, lineHeight = 36.sp) }
    }
    item {
      StoreInformationCard("Details") {
        StoreDetailRow("Author", app.author ?: "Catalog publisher")
        StoreDetailRow("Source", if (app.source == "fdroid") "F-Droid" else "Direct download")
        StoreDetailRow("Minimum Android API", app.minSdk?.toString() ?: "Catalog default")
        StoreDetailRow("Device support", app.devices.ifEmpty { listOf("touch", "tv") }.joinToString(", "))
        StoreDetailRow("Category", app.category)
        StoreDetailRow("Version", app.versionCode?.toString() ?: "Latest release")
        StoreDetailRow("Package", app.packageName)
        app.submittedBy?.let { StoreDetailRow("Submitted by", it) }
        app.homepage?.let { url -> ApprovedAppsButton("Open publisher website", modifier = Modifier.padding(top = 22.dp)) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } } }
      }
    }
  }
}

@Composable
private fun StoreInformationCard(title: String, content: @Composable ColumnScope.() -> Unit) {
  Surface(color = IosCard, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, IosDivider), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(horizontal = 36.dp, vertical = 34.dp)) {
      Text(title, color = IosLabel, fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 18.dp))
      content()
    }
  }
}

@Composable
private fun StoreDetailRow(label: String, value: String) {
  Row(Modifier.fillMaxWidth().padding(vertical = 21.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(label, color = IosLabel, fontSize = 26.sp, modifier = Modifier.weight(1f))
    Text(value, color = IosSecondary, fontSize = 24.sp, modifier = Modifier.widthIn(max = 850.dp))
  }
}

@Composable
private fun StorePausedBanner() {
  Surface(color = LocalApprovedPalette.current.surface2, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(24.dp)) {
      Text("Installing new apps is paused", color = IosLabel, fontSize = 26.sp, fontWeight = FontWeight.Medium)
      Text("Connect to your computer and run Hearthpane setup to restore the first-generation Portal installer. Everything else keeps working.", color = IosSecondary, fontSize = 22.sp, lineHeight = 30.sp, modifier = Modifier.padding(top = 10.dp))
    }
  }
}

@Composable
private fun StoreDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(color = IosCard, shape = RoundedCornerShape(28.dp), border = BorderStroke(1.dp, IosDivider), modifier = Modifier.width(760.dp).heightIn(max = 980.dp)) {
      LazyColumn(contentPadding = PaddingValues(36.dp)) {
        item {
          Column(Modifier.fillMaxWidth()) {
            Text(title, color = IosLabel, fontSize = 34.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 24.dp))
            content()
          }
        }
      }
    }
  }
}
