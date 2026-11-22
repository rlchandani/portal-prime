/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import android.util.TypedValue
import android.content.pm.ApplicationInfo
import androidx.compose.ui.input.key.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.core.graphics.drawable.toBitmap
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class AppEntry(
    val label: String,
    val component: ComponentName,
    val icon: ImageBitmap,
    val folder: String? = null,
    val uninstallable: Boolean = false,
)

internal data class WidgetProviderEntry(
    val label: String,
    val packageLabel: String,
    val icon: ImageBitmap?,
    val info: AppWidgetProviderInfo?,
    val spanX: Int,
    val spanY: Int,
    val customKind: String? = null,
)

/** Home's immutable inventory snapshots retain their icons across Activity resumes/recreation.
 * The application receiver survives Home being stopped; no Activity is retained by this cache.
 */
internal object ApprovedHomeInventory {
  val revision = MutableStateFlow(0L)
  val apps = HomePresentationInventoryCache<AppEntry>()
  val providers = HomePresentationInventoryCache<WidgetProviderEntry>()
  private var registered = false

  @Synchronized fun invalidate() {
    apps.invalidate()
    providers.invalidate()
    revision.value++
  }

  @Synchronized fun register(context: Context) {
    if (registered) return
    val filter = android.content.IntentFilter().apply {
      addAction(Intent.ACTION_PACKAGE_ADDED)
      addAction(Intent.ACTION_PACKAGE_REMOVED)
      addAction(Intent.ACTION_PACKAGE_CHANGED)
      addAction(Intent.ACTION_PACKAGE_REPLACED)
      addDataScheme("package")
    }
    val receiver = object : android.content.BroadcastReceiver() {
      override fun onReceive(c: Context, intent: Intent) { invalidate() }
    }
    registered = runCatching {
      context.applicationContext.registerReceiver(receiver, filter)
      true
    }.getOrDefault(false)
  }
}

/** Package changes refresh every consumer of the shared app artwork inventory. */
internal val approvedIconInventoryRevision: StateFlow<Long>
  get() = ApprovedHomeInventory.revision

/** A dashboard address changes the shortcut inventory, without another package scan on each frame. */
internal fun invalidateApprovedDashboardInventory() = ApprovedHomeInventory.invalidate()

internal fun approvedInstalledEntries(context: Context, inventory: List<AppEntry>): List<AppEntry> =
    inventory.filter {
      it.component.packageName != context.packageName && it.component != CALLS_COMPONENT
    }

/** Reuse the same already-rendered shortcut artwork as Home; never decode from composition. */
internal fun peekApprovedIconInventory(): List<AppEntry>? = ApprovedHomeInventory.apps.peek()

internal fun approvedShortcutIcons(context: Context, inventory: List<AppEntry>): Map<String, ImageBitmap> {
  if (inventory.isEmpty()) return emptyMap()
  // Warm Apps prepares this map from composition; build targets once for the whole inventory.
  val store = ComponentName(context, StoreActivity::class.java)
  val music = ComponentName(context, WebAppActivity::class.java)
  val dashboard = ComponentName(context, HomeDashboardActivity::class.java)
  val control = ComponentName(context, ControlCenterActivity::class.java)
  val haDashboard = ComponentName(context, HaDashboardActivity::class.java)
  val settings = ComponentName(context, ImmortalSettingsActivity::class.java)
  return inventory.mapNotNull { app ->
    val key = when (app.component) {
      store -> "store"
      music -> "music"
      dashboard -> "dashboard"
      control -> ApprovedAppsBuiltins.CONTROL
      haDashboard -> ApprovedAppsBuiltins.HA_DASHBOARD
      settings -> "settings"
      CALLS_COMPONENT -> "calls"
      else -> null
    }
    key?.let { it to app.icon }
  }.toMap()
}

/** Run on IO; cold Home and Apps requests share one package/icon scan. */
internal fun loadApprovedIconInventory(context: Context): List<AppEntry> {
  ApprovedHomeInventory.register(context)
  return ApprovedHomeInventory.apps.load { loadApps(context.applicationContext) }
}

internal fun loadApprovedInstalledApps(context: Context): List<AppEntry> =
    approvedInstalledEntries(context, loadApprovedIconInventory(context))

// Sentinel component for the injected "Calls" grid tile — no real Activity backs it; onLaunch
// recognizes this and bridges to the stock Portal calling surface via launchStockHome().
internal val CALLS_COMPONENT = ComponentName("com.immortal.launcher", "__immortal_calls_bridge__")

private fun estimateWidgetSpan(minDp: Int, tileDp: Dp): Int {
  if (minDp <= 0) return HomeWidgetStore.DEFAULT_SPAN_X
  val tile = tileDp.value.coerceAtLeast(1f)
  return HomeWidgetStore.normalizeSpan(((minDp + tile - 1f) / tile).toInt().coerceAtLeast(1))
}

internal fun loadApps(context: Context): List<AppEntry> {
  val pm = context.packageManager
  val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
  val candidates = pm.queryIntentActivities(intent, 0).filter {
    val pkg = it.activityInfo.packageName
    pkg != context.packageName && !Curation.isHidden(pkg, "")
  }
  return prepareHomeAppIcons(
          candidates,
          packageName = { it.activityInfo.packageName },
          rawLabel = { it.loadLabel(pm).toString() },
          hiddenLabel = { it in Curation.hiddenLabels },
          prepare = { ri, rawLabel ->
            val ai = ri.activityInfo
            val drawable = Curation.iconOverride(ai.packageName)
                ?.let { resId -> androidx.core.content.ContextCompat.getDrawable(context, resId) }
                ?: ri.loadIcon(pm)
            val bmp: Bitmap = IconRenderer.renderAppIcon(context, drawable, 144)
            val appInfo = ai.applicationInfo
            val isUninstallable = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                                  (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            AppEntry(
                label = Curation.displayLabel(ai.packageName, rawLabel),
                component = ComponentName(ai.packageName, ai.name),
                icon = bmp.asImageBitmap(),
                folder = Curation.folderFor(ai.packageName),
                uninstallable = isUninstallable,
            )
          },
      )
      .sortedBy { it.label.lowercase(Locale.getDefault()) }
      .let { apps ->
        // Inject our own iOS-style settings as a grid tile. Our app is excluded from the
        // normal query (pkg == this launcher), and ImmortalSettingsActivity is exported=false
        // with no LAUNCHER filter, so it's launched via the special-case in onLaunch below.
        val settingsIcon = Curation.iconOverride("com.android.settings")
            ?.let { resId -> androidx.core.content.ContextCompat.getDrawable(context, resId) }
            ?.let { IconRenderer.renderAppIcon(context, it, 144).asImageBitmap() }
        // Inject the Immortal App Store too (StoreActivity — also our own, excluded from the query).
        val storeIcon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.icon_store_tile)
            ?.let { IconRenderer.renderAppIcon(context, it, 144).asImageBitmap() }
        // Inject a "Calls" tile that bridges to the stock Portal calling surface (the only trusted
        // caller). It has no real Activity — launch is special-cased in onLaunch via CALLS_COMPONENT.
        val callIcon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_call_tile)
            ?.let { IconRenderer.renderAppIcon(context, it, 144).asImageBitmap() }
        // Inject "Tools" (ToolsActivity — our own, exported=false, launched via the onLaunch
        // special-case). It's the entry point to timers, notes, converter, cameras, countdown editor.
        val toolsIcon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_tools_tile)
            ?.let { IconRenderer.renderAppIcon(context, it, 144).asImageBitmap() }
        // Inject "Music" (WebAppActivity → the YouTube Music PWA — there's no installable
        // client without GMS, so the web app IS the client on a Portal).
        val musicIcon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_music_tile)
            ?.let { IconRenderer.renderAppIcon(context, it, 144).asImageBitmap() }
        var out = apps
        if (HomeDashboardConfig.configured(context)) {
          val dashboardIcon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.icon_ha_tile)
              ?.let { IconRenderer.renderAppIcon(context, it, 144).asImageBitmap() }
          if (dashboardIcon != null) out = out + AppEntry(
              label = "Home dashboard",
              component = ComponentName(context, HomeDashboardActivity::class.java),
              icon = dashboardIcon,
          )
        }
        // "Home Control" — the Control Center — once Home Assistant is connected (not on
        // banners-only Portals, which keep alerts but no app).
        if (HaConfig.isConfigured(context) && !ControlCenterConfig.load(context).bannersOnly) {
          val controlIcon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.icon_ha_tile)
              ?.let { IconRenderer.renderAppIcon(context, it, 144).asImageBitmap() }
          if (controlIcon != null) out = out + AppEntry(
              label = "Home Control",
              component = ComponentName(context.packageName, ControlCenterActivity::class.java.name),
              icon = controlIcon,
              uninstallable = false,
          )
        }
        // "Home Assistant" — Hearthpane's own HA dashboard — whenever Home Assistant is connected.
        if (HaConfig.isConfigured(context)) {
          val haIcon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.icon_ha_tile)
              ?.let { IconRenderer.renderAppIcon(context, it, 144).asImageBitmap() }
          if (haIcon != null) out = out + AppEntry(
              label = "Home Assistant",
              component = ComponentName(context.packageName, HaDashboardActivity::class.java.name),
              icon = haIcon,
              uninstallable = false,
          )
        }
        if (musicIcon != null) {
          out = out + AppEntry(
              label = "Music",
              component = ComponentName(context.packageName, WebAppActivity::class.java.name),
              icon = musicIcon,
              uninstallable = false,
          )
        }
        if (callIcon != null) {
          out = out + AppEntry(
              label = "Calls",
              component = CALLS_COMPONENT,
              icon = callIcon,
              uninstallable = false,
          )
        }
        if (toolsIcon != null) {
          out = out + AppEntry(
              label = "Tools",
              component = ComponentName(context.packageName, ToolsActivity::class.java.name),
              icon = toolsIcon,
              uninstallable = false,
          )
        }
        if (settingsIcon != null) {
          out = out + AppEntry(
              label = "Settings",
              component = ComponentName(context.packageName, ImmortalSettingsActivity::class.java.name),
              icon = settingsIcon,
              uninstallable = false,
          )
        }
        if (storeIcon != null) {
          out = out + AppEntry(
              label = "App Store",
              component = ComponentName(context.packageName, StoreActivity::class.java.name),
              icon = storeIcon,
              uninstallable = false,
          )
        }
        out
      }
}

internal fun loadWidgetProviders(context: Context, tileDp: Dp): List<WidgetProviderEntry> {
  val pm = context.packageManager
  val density = context.resources.displayMetrics.densityDpi
  val custom =
      listOf(
          WidgetProviderEntry(
              label = "Weather",
              packageLabel = Brand.NAME,
              icon = null,
              info = null,
              spanX = 2,
              spanY = 2,
              customKind = HomeWidgetStore.KIND_WEATHER,
          ),
          WidgetProviderEntry(
              label = "World Clock",
              packageLabel = Brand.NAME,
              icon = null,
              info = null,
              spanX = 2,
              spanY = 2,
              customKind = HomeWidgetStore.KIND_WORLD_CLOCK,
          ),
          WidgetProviderEntry(
              label = "Timers",
              packageLabel = Brand.NAME,
              icon = null,
              info = null,
              spanX = 2,
              spanY = 2,
              customKind = HomeWidgetStore.KIND_TIMERS,
          ),
      )
  val android =
      AppWidgetManager.getInstance(context)
      .installedProviders
      .mapNotNull { info ->
        runCatching {
              val packageLabel =
                  pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0))
                      .toString()
              val icon =
                  runCatching { info.loadIcon(context, density) }
                      .getOrNull()
                      ?: runCatching { pm.getApplicationIcon(info.provider.packageName) }.getOrNull()
              val iconBitmap = icon?.toBitmap(96, 96)?.asImageBitmap()
              WidgetProviderEntry(
                  label = info.loadLabel(pm).toString().ifBlank { packageLabel },
                  packageLabel = packageLabel,
                  icon = iconBitmap,
                  info = info,
                  spanX =
                      estimateWidgetSpan(appWidgetMinDp(context, info.minWidth), tileDp)
                          .coerceAtLeast(HomeWidgetStore.DEFAULT_SPAN_X),
                  spanY =
                      estimateWidgetSpan(appWidgetMinDp(context, info.minHeight), tileDp)
                          .coerceAtLeast(HomeWidgetStore.DEFAULT_SPAN_Y),
              )
            }
            .getOrNull()
      }
      .sortedWith(
          compareBy(
              { it.packageLabel.lowercase(Locale.getDefault()) },
              { it.label.lowercase(Locale.getDefault()) }))
  return custom + android
}

private fun appWidgetMinDp(context: Context, raw: Int): Int {
  if (raw <= 0) return 0
  // Android 9/10 on Portal reports AppWidgetProviderInfo sizes as raw complex
  // dimension values (for example, 10241 == 40dp). Newer framework builds may
  // already hand back dp integers, so only decode values that are clearly not
  // plain dp sizes.
  if (raw < 1000) return raw
  val metrics = context.resources.displayMetrics
  return (TypedValue.complexToDimensionPixelSize(raw, metrics) / metrics.density).roundToInt()
}
