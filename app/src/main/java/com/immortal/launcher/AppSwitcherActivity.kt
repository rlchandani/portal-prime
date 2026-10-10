/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale

/** QuickBar retains Recents; Home can use EXTRA_ALL_APPS or embed [ApprovedAppsScreen]. */
class AppSwitcherActivity : ComponentActivity() {
  companion object { const val EXTRA_ALL_APPS = "approved_all_apps" }
  override fun onResume() { super.onResume(); DreamPolicy.clearBridge(this) }
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      ApprovedAppsScreen(onExit = { finish() }, initiallyRecent = !intent.getBooleanExtra(EXTRA_ALL_APPS, false), initiallySearching = intent.getBooleanExtra("approved_search", false))
    }
  }
}

private data class AppItem(val label: String, val component: ComponentName, val icon: ImageBitmap, val lastUsedMs: Long = 0L, val fgMs: Long = 0L)
private data class ApprovedAppsInventory(val installed: List<AppItem>, val shortcuts: Map<String, ImageBitmap>)

/** One captured Home snapshot owns both app entries and shortcut artwork. */
private fun appInventory(context: Context, entries: List<AppEntry>): ApprovedAppsInventory =
    ApprovedAppsInventory(
        approvedInstalledEntries(context, entries).map { AppItem(it.label, it.component, it.icon) },
        approvedShortcutIcons(context, entries),
    )

/**
 * Native installed-app page; an embedding Home host supplies its theme, header and dock.
 * [searchRequest] toggles search; [openSearchRequest] opens it for an incoming Home route.
 * [onSelectTab] routes embedded Tools to Home.
 */
@Composable
internal fun ApprovedAppsScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
    initiallyRecent: Boolean = false,
    initiallySearching: Boolean = false,
    searchRequest: Int = 0,
    openSearchRequest: Int = 0,
    onSelectTab: ((String) -> Unit)? = null,
) {
  val content: @Composable () -> Unit = {
    ApprovedAppsContent(onExit, modifier, embedded, initiallyRecent, initiallySearching, searchRequest, openSearchRequest, onSelectTab)
  }
  if (embedded) content() else PortalPrimeTheme(darkTheme = false) { content() }
}

@Composable
private fun ApprovedAppsContent(
    onExit: () -> Unit,
    modifier: Modifier,
    embedded: Boolean,
    initiallyRecent: Boolean,
    initiallySearching: Boolean,
    searchRequest: Int,
    openSearchRequest: Int,
    onSelectTab: ((String) -> Unit)?,
) {
  val context = LocalContext.current
  val owner = (if (embedded) LocalApprovedLauncherHostOwner.current else null) ?: LocalLifecycleOwner.current
  val resume = rememberApprovedResume(owner)
  val pageActive = LocalApprovedLauncherPageActive.current
  var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
  var refresh by remember { mutableStateOf(0) }
  var lastSuccessfulEpoch by remember(owner) { mutableStateOf<Pair<Int, Int>?>(null) }
  var lastRecentEpoch by remember(owner) { mutableStateOf<Pair<Int, Int>?>(null) }
  var recents by remember { mutableStateOf<List<AppItem>?>(null) }
  var inventory by remember {
    mutableStateOf(peekApprovedIconInventory()?.let { appInventory(context, it) })
  }
  val allApps = inventory?.installed
  var badges by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
  var query by remember { mutableStateOf("") }
  var searching by remember { mutableStateOf(initiallySearching) }
  var recent by remember { mutableStateOf(initiallyRecent) }
  var menuFor by remember { mutableStateOf<AppItem?>(null) }
  var folder by remember { mutableStateOf<String?>(null) }
  var assignments by remember { mutableStateOf(UserLayout.load(context)) }
  var order by remember { mutableStateOf(UserLayout.loadOrder(context)) }
  var editFavorites by remember { mutableStateOf(false) }
  var editFolder by remember { mutableStateOf(false) }
  var renameFolder by remember { mutableStateOf(false) }
  var newFolderName by remember { mutableStateOf("") }
  var moveApp by remember { mutableStateOf<AppItem?>(null) }
  var appsFailure by remember { mutableStateOf<String?>(null) }
  var recentsFailure by remember { mutableStateOf<String?>(null) }
  val showingRecents = recent && query.isBlank()
  val failure = if (showingRecents) recentsFailure else appsFailure
  val displayReady = if (showingRecents) recents != null else allApps != null
  DisposableEffect(owner) {
    val observer = LifecycleEventObserver { _, _ ->
      resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }
    owner.lifecycle.addObserver(observer)
    onDispose { owner.lifecycle.removeObserver(observer) }
  }
  // Keep invalidations while this retained page is hidden; host resume covers the stopped gap.
  DisposableEffect(context, resumed) {
    if (!resumed) onDispose { } else {
      val appContext = context.applicationContext
      val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { refresh++ }
      }
      val filter = IntentFilter().apply {
        addAction(Intent.ACTION_PACKAGE_ADDED)
        addAction(Intent.ACTION_PACKAGE_REMOVED)
        addAction(Intent.ACTION_PACKAGE_CHANGED)
        addDataScheme("package")
      }
      appContext.registerReceiver(receiver, filter)
      onDispose { appContext.unregisterReceiver(receiver) }
    }
  }
  LaunchedEffect(searchRequest) {
    if (searchRequest > 0) searching = !searching
  }
  LaunchedEffect(openSearchRequest) {
    if (openSearchRequest > 0) {
      recent = false
      folder = null
      searching = true
    }
  }
  // Badge retrieval refreshes independently so a slow Binder read cannot delay app preparation.
  LaunchedEffect(resume, refresh, pageActive, resumed) {
    if (!pageActive || !resumed) return@LaunchedEffect
    val loaded = loadApprovedAppBadges()
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
    badges = loaded
  }
  LaunchedEffect(resume, refresh, pageActive, resumed) {
    if (!pageActive || !resumed) return@LaunchedEffect
    assignments = UserLayout.load(context)
    order = UserLayout.loadOrder(context)
    val epoch = resume to refresh
    if (lastSuccessfulEpoch == epoch) return@LaunchedEffect
    try {
      val loaded = withContext(Dispatchers.IO) {
        val loadingContext = kotlinx.coroutines.currentCoroutineContext()
        val checkActive = { loadingContext.ensureActive() }
        loadAllApps(context, checkActive)
      }
      kotlinx.coroutines.currentCoroutineContext().ensureActive()
      inventory = loaded; appsFailure = null
      lastSuccessfulEpoch = epoch
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (error: Exception) { appsFailure = "Your apps couldn’t be loaded. Try again." }
  }
  // UsageStats and recent-app icon lookups are not on the All apps opening path.
  LaunchedEffect(resume, refresh, pageActive, resumed, recent) {
    if (!recent || !pageActive || !resumed) return@LaunchedEffect
    val epoch = resume to refresh
    if (lastRecentEpoch == epoch) return@LaunchedEffect
    try {
      val loaded = withContext(Dispatchers.IO) {
        val loadingContext = kotlinx.coroutines.currentCoroutineContext()
        loadRecents(context) { loadingContext.ensureActive() }
      }
      kotlinx.coroutines.currentCoroutineContext().ensureActive()
      recents = loaded; recentsFailure = null
      lastRecentEpoch = epoch
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (error: Exception) { recentsFailure = "Your recent apps couldn’t be loaded. Try again." }
  }
  val installed = allApps.orEmpty()
  val ordered = UserLayout.applyOrder(installed, order) { it.component.flattenToString() }
  val installedIds = ordered.map { it.component.flattenToString() }
  val builtinIds = listOf(StoreActivity::class.java, ToolsActivity::class.java, WebAppActivity::class.java, ImmortalSettingsActivity::class.java, HomeDashboardActivity::class.java)
      .map { ComponentName(context.packageName, it.name).flattenToString() } + ComponentName("com.immortal.launcher", "__immortal_calls_bridge__").flattenToString()
  val knownIds = (installedIds + builtinIds).toSet()
  val favoriteKeys = (order.filter { it in knownIds } + installedIds + builtinIds).distinct().take(6)
  fun folderOf(app: AppItem): String? = (assignments[app.component.packageName] ?: Curation.folderFor(app.component.packageName))?.takeIf { it.isNotBlank() }
  fun launchApp(app: AppItem) { if (launchApprovedIntent(context, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.component)) && !embedded) onExit() }
  val folderNames = installed.mapNotNull { folderOf(it) }.distinct()
  val shown = when {
    query.isNotBlank() -> ordered.filter { "${it.label} ${it.component.packageName}".contains(query.trim(), true) }
    recent -> recents.orEmpty()
    folder != null -> ordered.filter { folderOf(it) == folder }
    else -> ordered.filter { folderOf(it) == null }
  }
  val shortcutIcons = inventory?.shortcuts.orEmpty()
  BackHandler {
    when { menuFor != null -> menuFor = null; folder != null -> folder = null; recent && !initiallyRecent -> recent = false; searching -> { searching = false; query = "" }; else -> onExit() }
  }
  val content: @Composable () -> Unit = {
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(6), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalArrangement = Arrangement.spacedBy(32.dp), modifier = modifier.fillMaxSize().padding(horizontal = 56.dp), contentPadding = PaddingValues(top = 36.dp, bottom = 24.dp)) {
      item(key = "heading", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
        Column(Modifier.fillMaxWidth()) {
          ApprovedAppsHeading(if (folder != null) folder!! else if (recent) "Recent apps" else "Your apps",
              if (folder != null) "Apps in your folder." else if (recent) "Open, inspect, close or uninstall an app." else "Installed apps on this Portal.")
          Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (folder == null && !recent) {
              ApprovedAppsButton("App Store", primary = true) { launchApprovedIntent(context, Intent(context, StoreActivity::class.java)) }
              if ("Settings" in folderNames) ApprovedAppsButton("Settings folder") { folder = "Settings" }
              ApprovedAppsButton("Edit favorites") { editFavorites = true }
              ApprovedAppsButton("Recent apps") { recent = true }
            } else if (folder != null) {
              ApprovedAppsButton("Rename folder") { newFolderName = folder!!; renameFolder = true }
              ApprovedAppsButton("Edit folder contents") { editFolder = true }
              ApprovedAppsButton("All apps") { folder = null }
            } else ApprovedAppsButton("All apps") { recent = false }
          }
          if (searching) ApprovedAppsSearch(query, { query = it }, "Search installed apps", Modifier.padding(top = 24.dp))
          if (failure != null) {
            Text(failure!!, color = IosSecondary, fontSize = 24.sp, modifier = Modifier.padding(top = 24.dp))
            ApprovedAppsButton("Try again", modifier = Modifier.padding(top = 20.dp)) { refresh++ }
          }
          if (failure == null && !displayReady) Text("Loading apps…", color = IosSecondary, fontSize = 24.sp, modifier = Modifier.padding(top = 32.dp))
          if (failure == null && displayReady &&
              shown.isEmpty() && (query.isNotBlank() || recent || folder != null)) {
            Text(if (query.isNotBlank()) "No apps match “${query.trim()}”." else if (recent) "No recent apps yet. Usage access may need setup." else "This folder is empty.", color = IosSecondary, fontSize = 24.sp, modifier = Modifier.padding(top = 32.dp))
            if (recent) ApprovedAppsButton("Usage access", modifier = Modifier.padding(top = 20.dp)) { launchApprovedIntent(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
          }
          Spacer(Modifier.height(36.dp))
        }
      }
      // Publish installed apps, folders and built-ins together, never a short grid
      // of built-ins that shifts when the asynchronous inventory arrives.
      val builtins = if (allApps != null && !recent && folder == null && query.isBlank())
        listOf("store" to "App Store", "music" to "Music", "calls" to "Calls", "settings" to "Settings") +
            if ("dashboard" in shortcutIcons) listOf("dashboard" to "Home dashboard") else emptyList()
        else emptyList()
      val otherFolders = if (!recent && folder == null && query.isBlank()) folderNames.filterNot { it == "Settings" } else emptyList()
      val tiles = shown.map { app -> AppsTileEntry(app.label, app = app) } + otherFolders.map { AppsTileEntry(it, folder = it) } + builtins.map { AppsTileEntry(it.second, builtin = it.first) }
      items(tiles.size, key = { index -> tiles[index].app?.component?.flattenToString() ?: "${tiles[index].builtin}:${tiles[index].folder}" }, contentType = { "app-tile" }) { index ->
        val entry = tiles[index]
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
          InstalledAppTile(entry, entry.app?.component?.packageName?.let { badges[it] } ?: 0, if (recent) entry.app?.let(::recencyLine) else null,
              shortcutIcon = shortcutIcons[entry.builtin],
              onOpen = {
                when {
                  entry.app != null -> if (recent) menuFor = entry.app else launchApp(entry.app)
                  entry.folder != null -> folder = entry.folder
                  embedded && entry.builtin == "tools" && onSelectTab != null -> onSelectTab("tools")
                  else -> launchApprovedBuiltin(context, entry.builtin!!)
                }
              },
              onOptions = { menuFor = entry.app })
        }
      }
    }
  }
  if (embedded) content() else ApprovedAppsShell("apps", onExit, onSearch = { searching = !searching }) { content() }
  menuFor?.let { app ->
    val isFavorite = app.component.flattenToString() in favoriteKeys
    AppsDialog(app.label, { menuFor = null }) {
      AppsMenuRow("Open") { menuFor = null; launchApp(app) }
      AppsMenuRow(if (isFavorite) "Remove from favorites" else "Add to favorites") {
        val id = app.component.flattenToString()
        val remaining = (ordered.map { it.component.flattenToString() } + order).distinct().filterNot { it == id }
        order = if (isFavorite) remaining + id else listOf(id) + remaining
        UserLayout.saveOrder(context, order); menuFor = null
      }
      AppsMenuRow("Move to folder") { menuFor = null; moveApp = app }
      AppsMenuRow("App info") { menuFor = null; launchApprovedIntent(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.component.packageName}"))) }
      AppsMenuRow("Close (free up memory)") {
        runCatching { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(app.component.packageName) }
        menuFor = null
      }
      AppsMenuRow("Uninstall", danger = true) { menuFor = null; launchApprovedIntent(context, Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.component.packageName}"))) }
    }
  }
  moveApp?.let { app ->
    AppsDialog("Move ${app.label}", { moveApp = null }) {
      (listOf("No folder", "Settings") + folderNames).distinct().forEach { target ->
        AppsMenuRow(target) { assignments = assignments + (app.component.packageName to if (target == "No folder") "" else target); UserLayout.save(context, assignments); moveApp = null }
      }
    }
  }
  if (editFavorites) {
    val shortcutFavorites = favoriteKeys.filter { it in builtinIds }
    val availableSlots = (6 - shortcutFavorites.size).coerceAtLeast(0)
    var chosen by remember { mutableStateOf(favoriteKeys.filter { it in installedIds }) }
    AppsDialog("Favorite apps", { editFavorites = false }) {
      Text("Choose ${minOf(availableSlots, installed.size)} apps for Home.", color = IosSecondary, fontSize = 24.sp, modifier = Modifier.padding(bottom = 20.dp))
      ordered.forEach { app ->
        val id = app.component.flattenToString()
        AppsSelectionRow(app.label, id in chosen) { chosen = if (id in chosen) chosen - id else if (chosen.size < availableSlots) chosen + id else chosen }
      }
      ApprovedAppsButton("Done", primary = true, enabled = chosen.size == minOf(availableSlots, ordered.size), modifier = Modifier.padding(top = 24.dp)) {
        order = shortcutFavorites + chosen + (installedIds + order).distinct().filterNot { it in chosen || it in shortcutFavorites }; UserLayout.saveOrder(context, order); editFavorites = false
      }
    }
  }
  if (renameFolder && folder != null) AppsDialog("Rename folder", { renameFolder = false }) {
    ApprovedAppsSearch(newFolderName, { newFolderName = it }, "Folder name")
    ApprovedAppsButton("Save", primary = true, enabled = newFolderName.isNotBlank(), modifier = Modifier.padding(top = 24.dp)) {
      val old = folder!!; val name = newFolderName.trim()
      val members = installed.filter { folderOf(it) == old }
      assignments = assignments + members.associate { it.component.packageName to name }; UserLayout.save(context, assignments)
      folder = name; renameFolder = false
    }
  }
  if (editFolder && folder != null) AppsDialog("Edit folder contents", { editFolder = false }) {
    ordered.forEach { app -> AppsMenuRow(app.label + if (folderOf(app) == folder) "    ✓" else "") {
      assignments = assignments + (app.component.packageName to if (folderOf(app) == folder) "" else folder!!); UserLayout.save(context, assignments)
    } }
    ApprovedAppsButton("Done", primary = true, modifier = Modifier.padding(top = 24.dp)) { editFolder = false }
  }
}

private data class AppsTileEntry(val label: String, val app: AppItem? = null, val builtin: String? = null, val folder: String? = null)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InstalledAppTile(entry: AppsTileEntry, badge: Int, subtitle: String?, shortcutIcon: ImageBitmap?, onOpen: () -> Unit, onOptions: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 205.dp).combinedClickable(
        onClick = onOpen, onLongClickLabel = "App actions",
        onLongClick = if (entry.app != null) onOptions else null)
        .padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
      BadgedBox(badge = { if (badge > 0) Badge { Text(if (badge > 9) "9+" else "$badge") } }) {
        ApprovedAppsIcon(entry.app?.component?.packageName ?: "builtin:${entry.builtin ?: "folder"}",
            entry.label, 112.dp, entry.app?.icon ?: shortcutIcon, plain = entry.folder == null)
      }
      Text(entry.label, color = IosLabel, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Normal, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
      if (subtitle != null) Text(subtitle, color = IosSecondary, fontSize = 20.sp, lineHeight = 28.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 7.dp))
    }
}

@Composable
private fun AppsMenuRow(label: String, danger: Boolean = false, onClick: () -> Unit) {
  Text(label, color = if (danger) IosRed else IosBlue, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium,
      textAlign = TextAlign.Start,
      modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp).tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f, onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp))
}

@Composable
private fun AppsSelectionRow(label: String, selected: Boolean, onClick: () -> Unit) {
  Surface(color = IosCard, shape = RoundedCornerShape(20.dp),
      border = BorderStroke(1.dp, IosDivider), modifier = Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().heightIn(min = 80.dp)
        .tvFocusable(RoundedCornerShape(20.dp), focusScale = 1f, onClick = onClick)
        .padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(label, color = IosLabel, fontSize = 26.sp, lineHeight = 34.sp,
          modifier = Modifier.weight(1f))
      if (selected) Text("Selected", color = IosBlue, fontSize = 22.sp, lineHeight = 30.sp)
    }
  }
}

@Composable
private fun AppsDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
  if (!LocalApprovedLauncherPageActive.current) return
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(color = IosCard, shape = RoundedCornerShape(28.dp), border = BorderStroke(1.dp, IosDivider),
        modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth().heightIn(max = 1160.dp)) {
      Column {
        Row(Modifier.fillMaxWidth().padding(start = 38.dp, top = 30.dp, end = 38.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically) {
          Text(title, color = IosLabel, fontSize = 34.sp, lineHeight = 44.sp,
              fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
          Surface(color = IosSegmentTrack, shape = CircleShape,
              modifier = Modifier.size(68.dp).semantics { contentDescription = "Close" }
                  .tvFocusable(CircleShape, focusScale = 1f, onClick = onDismiss)) {
            Box(contentAlignment = Alignment.Center) {
              Text("×", color = IosLabel, fontSize = 30.sp)
            }
          }
        }
        LazyColumn(Modifier.weight(1f, fill = false),
            contentPadding = PaddingValues(start = 38.dp, end = 38.dp, bottom = 38.dp)) {
          item {
            Column(Modifier.fillMaxWidth(), content = content)
          }
        }
      }
    }
  }
}

private fun launchApprovedIntent(context: Context, intent: Intent): Boolean = runCatching {
  if (intent.component?.packageName == JarvisHandoff.PACKAGE) {
    check(JarvisHandoff.launch(context))
  } else if (intent.component == ComponentName(context, StoreActivity::class.java))
    context.startApprovedInternalActivity(intent)
  else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  true
}.getOrElse { Toast.makeText(context, "This app couldn’t be opened.", Toast.LENGTH_LONG).show(); false }

private fun launchApprovedBuiltin(context: Context, id: String) {
  when (id) {
    "store" -> launchApprovedIntent(context, Intent(context, StoreActivity::class.java))
    "tools" -> context.openApprovedHomeTab(ApprovedLauncherTab.TOOLS)
    "settings" -> launchApprovedIntent(context, Intent(context, ImmortalSettingsActivity::class.java))
    "music" -> launchApprovedIntent(context, Intent(context, WebAppActivity::class.java).putExtra(WebAppActivity.EXTRA_URL, WebAppActivity.URL_YT_MUSIC))
    "dashboard" -> HomeDashboardActivity.openDashboard(context)
    "calls" -> PortalHomeBridge.launch(context)
  }
}
private fun recencyLine(app: AppItem): String {
  val ago = ago(System.currentTimeMillis() - app.lastUsedMs)
  val today = duration(app.fgMs)
  return if (today.isNotEmpty()) "$ago · $today today" else ago
}

private fun ago(ms: Long): String {
  val s = ms / 1000
  return when {
    s < 45 -> "just now"
    s < 3600 -> "${s / 60}m ago"
    s < 86400 -> "${s / 3600}h ago"
    else -> "${s / 86400}d ago"
  }
}

private fun duration(ms: Long): String {
  val m = ms / 60000
  return when {
    m <= 0 -> ""
    m < 60 -> "${m}m"
    else -> "${m / 60}h ${m % 60}m"
  }
}

private fun recentIcon(context: Context, pkg: String, inventory: Map<String, ImageBitmap>, loadDrawable: () -> android.graphics.drawable.Drawable): ImageBitmap {
  inventory[pkg]?.let { return it }
  // Rare launchable entries absent from the captured inventory render once on IO.
  // No independent package cache can outlive Home's package-change invalidation.
  val drawable = Curation.iconOverride(pkg)
      ?.let { androidx.core.content.ContextCompat.getDrawable(context, it) } ?: loadDrawable()
  return IconRenderer.renderAppIcon(context, drawable, 144).asImageBitmap()
}

/** Recently-used apps, most-recent first, with usage time; launchable + curated, minus Immortal. */
private fun loadRecents(context: Context, checkActive: () -> Unit): List<AppItem> {
  checkActive()
  val usm =
      context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
          ?: return emptyList()
  val now = System.currentTimeMillis()
  val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 24L * 3600_000, now)
  if (stats.isNullOrEmpty()) return emptyList()
  val icons = loadApprovedInstalledApps(context).associate { it.component.packageName to it.icon }
  checkActive()

  val lastUsed = HashMap<String, Long>()
  val fg = HashMap<String, Long>()
  for (s in stats) {
    checkActive()
    if (s.lastTimeUsed > (lastUsed[s.packageName] ?: 0)) lastUsed[s.packageName] = s.lastTimeUsed
    fg[s.packageName] = (fg[s.packageName] ?: 0) + s.totalTimeInForeground
  }

  val pm = context.packageManager
  return lastUsed.entries
      .sortedByDescending { it.value }
      .asSequence()
      .mapNotNull { (pkg, last) ->
        checkActive()
        if (pkg == context.packageName || Curation.isHidden(pkg, "")) return@mapNotNull null
        // Drop epoch/stale entries UsageStats sometimes reports (e.g. a never-foregrounded
        // system service with lastTimeUsed≈0 showing as "20000d ago").
        if (last <= 0L || now - last > 7L * 86400_000) return@mapNotNull null
        val component = pm.getLaunchIntentForPackage(pkg)?.component ?: return@mapNotNull null
        runCatching {
              val ai = pm.getApplicationInfo(pkg, 0)
              val raw = pm.getApplicationLabel(ai).toString()
              val label = Curation.displayLabel(pkg, raw)
              if (raw in Curation.hiddenLabels || label in Curation.hiddenLabels) return@mapNotNull null
              val bmp = recentIcon(context, pkg, icons) { pm.getApplicationIcon(ai) }
              AppItem(label, component, bmp, last, fg[pkg] ?: 0)
            }
            .getOrNull()
      }
      .take(16)
      .toList()
}

/** All installed launchable apps (for search / drawer), alphabetised, curated, minus Immortal. */
private fun loadAllApps(context: Context, checkActive: () -> Unit): ApprovedAppsInventory {
  checkActive()
  val entries = loadApprovedIconInventory(context)
  checkActive()
  return appInventory(context, entries)
}
