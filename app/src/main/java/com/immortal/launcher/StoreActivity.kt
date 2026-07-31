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
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import kotlinx.coroutines.launch

/**
 * The Immortal App Store. Two screens in one activity: a browse view (search,
 * updates, a Portal Pick hero, a featured carousel, and grouped iOS category
 * cards) and a per-app detail view (description, author, source, credit,
 * contextual Install/Open/Update action). Fully driveable by touch or the
 * Portal TV remote.
 *
 * Skinned to the light iOS App Store aesthetic (see [IosBackground] et al in
 * SettingsComponents): #F2F2F7 background, white grouped cards, blue GET pills.
 */
class StoreActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { StoreRoot() } }
  }
}

// --- root: state + navigation -------------------------------------------------

@Composable
private fun StoreRoot() {
  val context = LocalContext.current
  val activity = context as? Activity
  var apps by remember { mutableStateOf<List<CatalogApp>>(emptyList()) }
  val status = remember { mutableStateMapOf<String, String>() }
  var updates by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
  var query by remember { mutableStateOf("") }
  var detail by remember { mutableStateOf<CatalogApp?>(null) }
  var pendingConfirm by remember { mutableStateOf<Intent?>(null) }

  val confirmLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

  LaunchedEffect(Unit) { StoreCatalog.loadCatalog(context) { apps = it } }
  LaunchedEffect(apps) {
    if (apps.isNotEmpty()) StoreCatalog.findUpdates(context, apps) { updates = it }
  }
  LaunchedEffect(pendingConfirm) {
    pendingConfirm?.let {
      confirmLauncher.launch(it)
      pendingConfirm = null
    }
  }

  DisposableEffect(Unit) {
    val receiver =
        object : BroadcastReceiver() {
          override fun onReceive(c: Context, intent: Intent) {
            val pkg = intent.getStringExtra(STORE_EXTRA_PKG) ?: return
            when (val s = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
              PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                pendingConfirm =
                    if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                status[pkg] = "Confirm to install…"
              }
              PackageInstaller.STATUS_SUCCESS -> {
                status[pkg] = "Installed ✓"
                updates = updates - pkg
              }
              else -> status[pkg] = "Failed (status=$s)"
            }
          }
        }
    val filter = IntentFilter(STORE_INSTALL_ACTION)
    if (Build.VERSION.SDK_INT >= 33)
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
    onDispose { runCatching { context.unregisterReceiver(receiver) } }
  }

  val install: (CatalogApp) -> Unit = { app ->
    StoreCatalog.install(context, app) { p, m ->
      status[p] = m
      if (m == "Installed ✓") updates = updates - p
    }
  }

  // One Back interceptor for both screens: detail closes, browse exits. Catches
  // the TV remote's Back before the focus system can swallow it.
  Surface(
      modifier =
          Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.key == Key.Back) {
              if (e.type == KeyEventType.KeyUp) {
                if (detail != null) detail = null else activity?.finish()
              }
              true
            } else false
          },
      color = IosBackground,
  ) {
    val current = detail
    if (current == null) {
      BrowseScreen(
          apps = apps,
          status = status,
          updates = updates,
          query = query,
          onQuery = { query = it },
          onOpenDetail = { detail = it },
          onInstall = install,
      )
    } else {
      BackHandler { detail = null }
      AppDetailScreen(
          app = current,
          status = status[current.packageName],
          hasUpdate = updates.containsKey(current.packageName),
          onBack = { detail = null },
          onInstall = { install(current) },
      )
    }
  }
}

// --- browse -------------------------------------------------------------------

@Composable
private fun BrowseScreen(
    apps: List<CatalogApp>,
    status: Map<String, String>,
    updates: Map<String, Long>,
    query: String,
    onQuery: (String) -> Unit,
    onOpenDetail: (CatalogApp) -> Unit,
    onInstall: (CatalogApp) -> Unit,
) {
  val context = LocalContext.current
  // Initial focus goes to the FIRST APP CARD, not the search field — focusing the
  // field would pop the keyboard over the whole store on open.
  val firstCardFocus = remember { FocusRequester() }
  LaunchedEffect(apps.isNotEmpty()) {
    if (apps.isNotEmpty()) runCatching { firstCardFocus.requestFocus() }
  }
  val searchFocus = remember { FocusRequester() }
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()

  val filtered =
      remember(apps, query) {
        if (query.isBlank()) apps
        else
            apps.filter {
              it.name.contains(query, true) ||
                  it.description.contains(query, true) ||
                  (it.author ?: "").contains(query, true)
            }
      }
  val updateApps = remember(apps, updates) { apps.filter { it.packageName in updates.keys } }
  val firstPkg =
      remember(updateApps, filtered) {
        (updateApps.firstOrNull() ?: filtered.firstOrNull())?.packageName
      }

  // "Portal Originals" powers both the hero (first not-installed, compatible one)
  // and the featured carousel. Fall back to the first app for the hero if empty.
  val originals =
      remember(apps) { apps.filter { it.category.equals("Portal Originals", ignoreCase = true) } }
  val heroApp =
      remember(apps, originals) {
        originals.firstOrNull {
          !StoreCatalog.isInstalled(context, it.packageName) && StoreCatalog.isCompatible(it)
        } ?: apps.firstOrNull()
      }

  val showUpdates = query.isBlank() && updateApps.isNotEmpty()
  val categories =
      remember(filtered, query) {
        if (query.isBlank()) filtered.groupBy { it.category } else emptyMap()
      }
  val jumpCategories = remember(apps) { apps.map { it.category }.distinct() }

  // Flat item indices of each category header, so the jump-bar can scroll to them.
  // MUST mirror the LazyColumn item order below exactly.
  val headerIndex =
      remember(apps, query, showUpdates, categories, heroApp, originals) {
        val map = LinkedHashMap<String, Int>()
        var i = 0
        i++ // top header block (title + search + apk link)
        if (query.isBlank() && heroApp != null) i++ // hero
        if (query.isBlank() && originals.isNotEmpty()) i++ // featured carousel
        if (showUpdates) i += 2 // updates header + card
        if (query.isBlank()) for (cat in categories.keys) { map[cat] = i; i += 2 }
        map
      }

  Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp).focusGroup(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      item(key = "top") {
        Spacer(Modifier.height(56.dp))
        Text("App Store", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = IosLabel)
        Text(
            "${apps.size} apps picked for Portal · community submissions welcome",
            fontSize = 15.sp,
            color = IosSecondary,
        )
        if (InstallDaemon.installPaused(context)) PausedBanner()
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("Search apps…", color = IosSecondary) },
            singleLine = true,
            modifier =
                Modifier.fillMaxWidth().heightIn(min = 54.dp).focusRequester(searchFocus),
            shape = RoundedCornerShape(14.dp),
            colors = iosTextFieldColors(),
        )
        Text(
            "📁  Have an APK file? Install it →",
            color = IosBlue,
            fontSize = 15.sp,
            modifier =
                Modifier.padding(top = 10.dp)
                    .tvFocusable(RoundedCornerShape(8.dp), ringColor = IosBlue) {
                      context.startActivity(Intent(context, ApkBrowserActivity::class.java))
                    },
        )
      }

      if (query.isBlank() && heroApp != null) {
        item(key = "hero") {
          Spacer(Modifier.height(10.dp))
          HeroBanner(heroApp, updates, onOpenDetail, onInstall)
        }
      }

      if (query.isBlank() && originals.isNotEmpty()) {
        item(key = "featured") {
          Spacer(Modifier.height(14.dp))
          FeaturedCarousel(originals, onOpenDetail)
        }
      }

      // The FocusRequester may only be attached once: it goes on the first row of the
      // FIRST section shown (updates if present, else the first category / results).
      if (showUpdates) {
        item(key = "updates-header") {
          Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            SectionHeader(
                if (updateApps.size == 1) "1 update available"
                else "${updateApps.size} updates available")
            Spacer(Modifier.weight(1f))
            if (updateApps.size > 1)
                IosActionPill("Update all", filled = true) { updateApps.forEach(onInstall) }
          }
        }
        item(key = "updates-card") {
          CategoryCard(
              updateApps, status, updates, firstPkg, firstCardFocus, onOpenDetail, onInstall)
        }
      }

      if (query.isBlank()) {
        var firstSection = !showUpdates
        categories.forEach { (cat, list) ->
          item(key = "h:$cat") { SectionHeader(cat) }
          val focusPkg = if (firstSection) firstPkg else null
          item(key = "c:$cat") {
            CategoryCard(
                list, status, updates, focusPkg, firstCardFocus, onOpenDetail, onInstall)
          }
          firstSection = false
        }
      } else {
        item(key = "results-header") {
          SectionHeader(if (filtered.isEmpty()) "No matches" else "Results")
        }
        if (filtered.isNotEmpty()) {
          item(key = "results-card") {
            CategoryCard(
                filtered, status, updates, firstPkg, firstCardFocus, onOpenDetail, onInstall)
          }
        }
      }
      item(key = "bottom") { Spacer(Modifier.height(110.dp)) }
    }

    CategoryJumpBar(
        categories = jumpCategories,
        onCategory = { cat ->
          headerIndex[cat]?.let { idx -> scope.launch { listState.animateScrollToItem(idx) } }
        },
        onSearch = {
          scope.launch {
            listState.animateScrollToItem(0)
            runCatching { searchFocus.requestFocus() }
          }
        },
        modifier = Modifier.align(Alignment.BottomCenter),
    )
  }
}

/** iOS-style uppercase grey section header (13sp, letter-spaced). */
@Composable
private fun SectionHeader(text: String) {
  Text(
      text.uppercase(),
      fontSize = 13.sp,
      fontWeight = FontWeight.SemiBold,
      letterSpacing = 0.5.sp,
      color = IosSecondary,
      modifier = Modifier.padding(top = 16.dp, bottom = 6.dp, start = 4.dp),
  )
}

/** A white grouped card wrapping one category's rows with hairline inset dividers. */
@Composable
private fun CategoryCard(
    apps: List<CatalogApp>,
    status: Map<String, String>,
    updates: Map<String, Long>,
    firstFocusPkg: String?,
    firstFocus: FocusRequester,
    onOpenDetail: (CatalogApp) -> Unit,
    onInstall: (CatalogApp) -> Unit,
) {
  Surface(color = IosCard, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
    Column {
      apps.forEachIndexed { i, app ->
        val rowMod =
            if (firstFocusPkg != null && app.packageName == firstFocusPkg)
                Modifier.focusRequester(firstFocus)
            else Modifier
        AppRow(app, status[app.packageName], updates, onOpenDetail, onInstall, rowMod)
        if (i < apps.lastIndex) RowDivider()
      }
    }
  }
}

/** Hairline iOS divider between list rows, inset past the icon (~76dp). */
@Composable
private fun RowDivider() {
  Box(modifier = Modifier.fillMaxWidth().height(0.5.dp)) {
    Spacer(
        Modifier.fillMaxWidth()
            .padding(start = 76.dp)
            .height(0.5.dp)
            .background(IosDivider))
  }
}

@Composable
private fun AppRow(
    app: CatalogApp,
    status: String?,
    updates: Map<String, Long>,
    onOpenDetail: (CatalogApp) -> Unit,
    onInstall: (CatalogApp) -> Unit,
    modifier: Modifier = Modifier,
) {
  Row(
      modifier =
          modifier
              .fillMaxWidth()
              .tvFocusableRow { onOpenDetail(app) }
              .padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    AppIcon(app, 58.dp)
    Column(modifier = Modifier.weight(1f).padding(start = 14.dp, end = 12.dp)) {
      Text(
          app.name,
          fontSize = 16.sp,
          fontWeight = FontWeight.SemiBold,
          color = IosLabel,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
      Text(
          app.author ?: app.category,
          fontSize = 13.sp,
          color = IosSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
      status?.let {
        Text(
            it,
            fontSize = 12.sp,
            color = IosSecondary,
            modifier = Modifier.padding(top = 2.dp),
        )
      }
    }
    AppActionSlot(app, updates, onInstall)
  }
}

// --- action slot (install / update / open / incompatible state machine) ---------

/**
 * The contextual action for an app, preserving the exact install/update state machine:
 * incompatible → disabled chip, has-update → Update, installed → Open, else → Install.
 */
@Composable
private fun AppActionSlot(
    app: CatalogApp,
    updates: Map<String, Long>,
    onInstall: (CatalogApp) -> Unit,
) {
  val context = LocalContext.current
  val installed = StoreCatalog.isInstalled(context, app.packageName)
  val compatible = StoreCatalog.isCompatible(app)
  val hasUpdate = app.packageName in updates.keys
  when {
    !compatible -> DisabledChip(StoreCatalog.incompatibleLabel(app.minSdk ?: 0))
    hasUpdate -> IosActionPill("Update", filled = true) { onInstall(app) }
    installed -> OpenPill(app)
    else -> IosActionPill("Install", filled = true) { onInstall(app) }
  }
}

/** iOS "GET"-style pill. Filled blue for Install/Update, tinted for Open. Keeps the TV focus ring. */
@Composable
private fun IosActionPill(label: String, filled: Boolean, onClick: () -> Unit) {
  val src = remember { MutableInteractionSource() }
  val pillShape = RoundedCornerShape(50)
  Box(
      modifier =
          Modifier.focusRing(src, pillShape, ringColor = IosBlue)
              .clip(pillShape)
              .background(if (filled) IosBlue else IosBlue.copy(alpha = 0.12f))
              .clickable(interactionSource = src, indication = null, onClick = onClick)
              .defaultMinSize(minWidth = 78.dp, minHeight = 44.dp)
              .padding(horizontal = 18.dp, vertical = 7.dp),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        label,
        color = if (filled) Color.White else IosBlue,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
    )
  }
}

@Composable
private fun OpenPill(app: CatalogApp) {
  val context = LocalContext.current
  IosActionPill("Open", filled = false) {
    runCatching {
      context.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
        context.startActivity(it)
      }
    }
  }
}

/** Grey disabled chip for an incompatible app: [IosSecondary] text on a neutral pill. */
@Composable
private fun DisabledChip(text: String) {
  Box(
      modifier =
          Modifier.clip(RoundedCornerShape(50))
              .background(IosDivider)
              .defaultMinSize(minHeight = 44.dp)
              .padding(horizontal = 14.dp, vertical = 7.dp),
      contentAlignment = Alignment.Center,
  ) {
    Text(text, color = IosSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
  }
}

@Composable
private fun Chip(text: String, color: Color) {
  Box(
      modifier =
          Modifier.clip(RoundedCornerShape(8.dp))
              .background(color.copy(alpha = 0.12f))
              .padding(horizontal = 10.dp, vertical = 6.dp),
  ) {
    Text(text, fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium)
  }
}

// --- hero + featured (real Portal Originals data) --------------------------------

@Composable
private fun HeroBanner(
    app: CatalogApp,
    updates: Map<String, Long>,
    onOpenDetail: (CatalogApp) -> Unit,
    onInstall: (CatalogApp) -> Unit,
) {
  val base = baseColor(app)
  val shape = RoundedCornerShape(20.dp)
  Box(
      modifier =
          Modifier.fillMaxWidth()
              .height(230.dp)
              .clip(shape)
              .background(base)
              .tvFocusable(shape, focusScale = 1f) { onOpenDetail(app) },
  ) {
    Box(
        modifier =
            Modifier.fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x22000000),
                        1f to Color(0xCC000000),
                    )),
    )
    Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = 20.dp)) {
      AppIcon(app, 84.dp)
    }
    Column(modifier = Modifier.align(Alignment.BottomStart).padding(20.dp)) {
      Box(
          modifier =
              Modifier.clip(RoundedCornerShape(6.dp))
                  .background(Color(0x33FFFFFF))
                  .padding(horizontal = 10.dp, vertical = 4.dp),
      ) {
        Text(
            "PORTAL PICK",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        )
      }
      Spacer(Modifier.height(8.dp))
      Text(app.name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
      Text(
          app.description,
          color = Color(0xCCFFFFFF),
          fontSize = 15.sp,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.height(14.dp))
      AppActionSlot(app, updates, onInstall)
    }
  }
}

@Composable
private fun FeaturedCarousel(apps: List<CatalogApp>, onOpenDetail: (CatalogApp) -> Unit) {
  Column {
    SectionHeader("Featured")
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      apps.forEach { app -> FeaturedCard(app, onOpenDetail) }
    }
  }
}

@Composable
private fun FeaturedCard(app: CatalogApp, onOpenDetail: (CatalogApp) -> Unit) {
  val base = baseColor(app)
  val shape = RoundedCornerShape(16.dp)
  Box(
      modifier =
          Modifier.width(260.dp)
              .height(150.dp)
              .clip(shape)
              .background(base)
              .tvFocusable(shape, focusScale = 1f) { onOpenDetail(app) },
  ) {
    Box(
        modifier =
            Modifier.fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.5f to Color(0x40000000),
                        1f to Color(0xCC000000),
                    )),
    )
    Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 14.dp)) {
      AppIcon(app, 60.dp)
    }
    Column(modifier = Modifier.align(Alignment.BottomStart).padding(14.dp)) {
      Text(
          app.category.uppercase(),
          color = Color(0xAAFFFFFF),
          fontSize = 11.sp,
          fontWeight = FontWeight.SemiBold,
          letterSpacing = 0.5.sp,
      )
      Text(app.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
      Text(
          app.description,
          color = Color(0xCCFFFFFF),
          fontSize = 12.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

// --- category jump-bar (pinned bottom) -------------------------------------------

@Composable
private fun CategoryJumpBar(
    categories: List<String>,
    onCategory: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
  if (categories.isEmpty()) return
  Box(
      modifier =
          modifier
              .fillMaxWidth()
              .padding(16.dp)
              .clip(RoundedCornerShape(26.dp))
              .background(Color(0xF2FFFFFF)),
  ) {
    Row(
        modifier =
            Modifier.horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      categories.forEach { cat -> JumpChip(cat) { onCategory(cat) } }
      JumpChip("🔍  Search") { onSearch() }
    }
  }
}

@Composable
private fun JumpChip(label: String, onClick: () -> Unit) {
  val shape = RoundedCornerShape(50)
  Box(
      modifier =
          Modifier.clip(shape)
              .background(IosBlue.copy(alpha = 0.10f))
              .tvFocusable(shape, ringColor = IosBlue, onClick = onClick)
              .padding(horizontal = 14.dp, vertical = 8.dp),
      contentAlignment = Alignment.Center,
  ) {
    Text(label, color = IosBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
  }
}

// --- app icon (catalog icon or monogram fallback) -------------------------------

private val monogramPalette =
    listOf(
        Color(0xFF3B6BC9), Color(0xFF8E4FC9), Color(0xFF2E8B6B), Color(0xFFB9552E),
        Color(0xFF4F7DA0), Color(0xFFA04F70), Color(0xFF6B8E2E), Color(0xFF73598C))

private fun baseColor(app: CatalogApp): Color =
    monogramPalette[Math.abs(app.name.hashCode()) % monogramPalette.size]

@Composable
private fun AppIcon(app: CatalogApp, size: androidx.compose.ui.unit.Dp) {
  val context = LocalContext.current
  val bmp by
      produceState<Bitmap?>(initialValue = StoreIcons.cached(app.packageName), app.packageName) {
        if (value == null) StoreIcons.load(context, app) { value = it }
      }
  val b = bmp
  if (b != null) {
    Image(
        bitmap = b.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.size(size).clip(RoundedCornerShape(size / 4)),
    )
  } else {
    val color = monogramPalette[Math.abs(app.name.hashCode()) % monogramPalette.size]
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(color),
        contentAlignment = Alignment.Center,
    ) {
      Text(
          app.name.first().uppercase(),
          color = Color.White,
          fontWeight = FontWeight.Bold,
          fontSize = (size.value * 0.42f).sp,
      )
    }
  }
}

// --- detail ---------------------------------------------------------------------

@Composable
private fun AppDetailScreen(
    app: CatalogApp,
    status: String?,
    hasUpdate: Boolean,
    onBack: () -> Unit,
    onInstall: () -> Unit,
) {
  val context = LocalContext.current
  val installed = StoreCatalog.isInstalled(context, app.packageName)
  val compatible = StoreCatalog.isCompatible(app)
  val backFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { backFocus.requestFocus() } }

  Column(
      modifier =
          Modifier.fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 40.dp)
              .focusGroup(),
  ) {
    Spacer(Modifier.height(56.dp))
    Text(
        "←  App Store",
        color = IosBlue,
        fontSize = 16.sp,
        modifier =
            Modifier.focusRequester(backFocus)
                .tvFocusable(RoundedCornerShape(8.dp), ringColor = IosBlue) { onBack() },
    )
    Spacer(Modifier.height(22.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
      AppIcon(app, 96.dp)
      Column(modifier = Modifier.padding(start = 22.dp)) {
        Text(app.name, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = IosLabel)
        app.author?.let { Text(it, fontSize = 15.sp, color = IosSecondary) }
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Chip(if (app.source == "fdroid") "F-Droid" else "Direct download", IosBlue)
          if (!compatible) Chip(StoreCatalog.incompatibleLabel(app.minSdk ?: 0), IosRed)
          if (app.devices.size == 1)
              Chip(
                  if (app.devices.first() == "tv") "Portal TV" else "Touch Portals",
                  IosGreen)
        }
      }
    }

    Spacer(Modifier.height(20.dp))
    status?.let {
      Text(it, color = IosSecondary, fontSize = 15.sp)
      Spacer(Modifier.height(10.dp))
    }
    when {
      !compatible ->
          Text(
              "This app needs a newer Android version than this Portal has.",
              color = IosRed,
              fontSize = 15.sp,
          )
      hasUpdate -> IosActionPill("Update", filled = true) { onInstall() }
      installed ->
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OpenPill(app)
            IosActionPill("Reinstall", filled = true) { onInstall() }
          }
      else -> IosActionPill("Install", filled = true) { onInstall() }
    }

    Spacer(Modifier.height(24.dp))
    Text(
        app.longDescription ?: app.description,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = IosSecondary,
    )

    Spacer(Modifier.height(28.dp))
    Text("Details", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = IosLabel)
    Spacer(Modifier.height(8.dp))
    DetailRow("Version", app.versionCode?.toString() ?: if (app.source == "fdroid") "Latest from F-Droid" else "Latest release")
    DetailRow("Package", app.packageName)
    app.homepage?.let { url ->
      Text(
          "Website  ·  $url",
          color = IosBlue,
          fontSize = 14.sp,
          modifier =
              Modifier.padding(vertical = 6.dp)
                  .tvFocusable(RoundedCornerShape(8.dp), ringColor = IosBlue) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                  },
      )
    }
    app.submittedBy?.let { DetailRow("Submitted by", it) }
    Spacer(Modifier.height(48.dp))
  }
}

@Composable
private fun DetailRow(label: String, value: String) {
  Row(modifier = Modifier.padding(vertical = 6.dp)) {
    Text(label, color = IosSecondary, fontSize = 14.sp, modifier = Modifier.width(130.dp))
    Text(value, color = IosLabel, fontSize = 14.sp)
  }
}

// --- banners ----------------------------------------------------------------------

@Composable
private fun PausedBanner() {
  Surface(
      color = Color(0x1AFF9500),
      shape = RoundedCornerShape(14.dp),
      modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text(
          "Installing new apps is paused",
          fontSize = 16.sp,
          fontWeight = FontWeight.SemiBold,
          color = IosLabel,
      )
      Text(
          "On a first-gen Portal, installing apps needs the installer-dialog fix that " +
              "Immortal's setup applies. Connect to your computer and run the Immortal " +
              "installer. Everything else keeps working.",
          fontSize = 13.sp,
          color = IosSecondary,
          modifier = Modifier.padding(top = 6.dp),
      )
    }
  }
}
