/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves and launches whatever the user picked to open when they tap the photo-frame
 * screensaver to dismiss it (see [ScreensaverDismissAppActivity]). Kept separate from
 * [ScreensaverConfig] (pure preference storage) because this touches the PackageManager
 * and launches activities.
 *
 * Three mutually-exclusive targets, in precedence order:
 *  1. **Home Assistant** ([ScreensaverConfig.Settings.dismissHaDashboard] non-null) —
 *     deep-link to a dashboard via `homeassistant://navigate/<path>`, or open HA's
 *     default dashboard when the path is blank.
 *  2. **An app** ([ScreensaverConfig.Settings.dismissAppComponent]) — a plain launch.
 *  3. **The Immortal launcher** (both null) — the default; [launchChosenApp] is a no-op
 *     and the dream just finishes home.
 *
 * Everything degrades to the launcher: an uninstalled app or a removed HA companion
 * makes [launchChosenApp] return false, so a stale choice can never strand the dream.
 */
object ScreensaverDismiss {
  private const val TAG = "ImmortalDismiss"

  // Both flavours of the official HA Android app; the minimal (F-Droid) build is what
  // no-GMS devices like the Portal run, the other is the Play build.
  private val HA_PACKAGES =
      listOf("io.homeassistant.companion.android", "io.homeassistant.companion.android.minimal")

  /** The installed HA companion package (either flavour), or null if neither is present. */
  fun installedHaPackage(context: Context): String? {
    val pm = context.packageManager
    return HA_PACKAGES.firstOrNull { pkg ->
      runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
    }
  }

  /** The chosen app launch target, or null if none is set or it's no longer installed. */
  fun chosenComponent(context: Context): ComponentName? {
    val flat = ScreensaverConfig.load(context).dismissAppComponent ?: return null
    val cn = ComponentName.unflattenFromString(flat) ?: return null
    return runCatching { context.packageManager.getActivityInfo(cn, 0); cn }.getOrNull()
  }

  /** Friendly label of the current dismiss target, or null when it resolves to the launcher. */
  fun chosenLabel(context: Context): String? {
    val cfg = ScreensaverConfig.load(context)
    cfg.dismissHaDashboard?.let { path ->
      if (installedHaPackage(context) == null) return null // HA gone → effectively launcher
      val p = path.trim()
      return if (p.isBlank()) "Home Assistant" else "Home Assistant · $p"
    }
    val cn = chosenComponent(context) ?: return null
    val pm = context.packageManager
    val raw = runCatching { pm.getActivityInfo(cn, 0).loadLabel(pm).toString() }.getOrNull()
    return raw?.let { Curation.displayLabel(cn.packageName, it) }
  }

  /**
   * Launch the chosen target, if any. Called from the dream's tap-to-dismiss handler.
   * Returns true when handled, including a pinned session with no launch. Any failure is swallowed so the
   * dream still finishes to the launcher.
   */
  fun launchChosenApp(context: Context): Boolean {
    // Finishing an owned Dream reveals the pinned app. Never send it another launch intent.
    if (PinnedAppScreensaver.hasPin()) return true
    val cfg = ScreensaverConfig.load(context)

    // 1. Home Assistant dashboard / app.
    cfg.dismissHaDashboard?.let { path ->
      val pkg = installedHaPackage(context) ?: return false
      return runCatching {
            val intent =
                if (path.isBlank()) {
                  context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(
                      Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
                } else {
                  Intent(Intent.ACTION_VIEW, Uri.parse(haDeepLink(path)))
                      .setPackage(pkg)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(intent)
            true
          }
          .onFailure { Log.w(TAG, "couldn't open HA dashboard '$path': $it") }
          .getOrDefault(false)
    }

    // 2. A plain app.
    val cn = chosenComponent(context) ?: return false
    return runCatching {
          context.startActivity(
              Intent(Intent.ACTION_MAIN)
                  .addCategory(Intent.CATEGORY_LAUNCHER)
                  .setComponent(cn)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          true
        }
        .onFailure { Log.w(TAG, "couldn't open dismiss target $cn: $it") }
        .getOrDefault(false)
  }

  /** An app's launcher icon as an [ImageBitmap], or null if it can't be loaded. */
  fun appIcon(context: Context, pkg: String): ImageBitmap? =
      runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(144, 144).asImageBitmap() }
          .getOrNull()

  /**
   * Turn user-entered dashboard text into a `homeassistant://navigate/<path>` deep link.
   * Accepts a bare path ("today-home/security"), a leading slash ("/lovelace/0"), a full
   * `homeassistant://` URI (used verbatim), or a pasted `http(s)://host/<path>` URL (the
   * host is stripped). Only called with non-blank input.
   */
  fun haDeepLink(input: String): String {
    val p = input.trim()
    if (p.startsWith("homeassistant://")) return p
    val rel =
        when {
          p.startsWith("http://") || p.startsWith("https://") ->
              p.substringAfter("://").substringAfter('/', "")
          else -> p
        }
            .trim()
            .trimStart('/')
    return "homeassistant://navigate/$rel"
  }
}

/**
 * Picker for the "open this when the screensaver is dismissed" setting. Offers a Home
 * Assistant section (only when an HA app is installed) with an optional dashboard deep
 * link, then the Immortal launcher (default) and the installed apps. Reached from the
 * screensaver settings.
 */
class ScreensaverDismissAppActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) {
      SettingsScaffold(title = "Open when you tap to exit", onBack = { finish() }) {
        ScreensaverDismissContent()
      }
    } }
  }
}

private enum class ApprovedEditorsDismissPopup { TARGET, APPS, HA_PATH }

@Composable
internal fun ScreensaverDismissContent() {
  val context = LocalContext.current
  var settings by remember { mutableStateOf(ScreensaverConfig.load(context)) }
  val inventoryRevision by approvedIconInventoryRevision.collectAsState()
  var inventory by remember { mutableStateOf<ScreensaverAppInventory?>(null) }
  var loadedRevision by remember { mutableStateOf<Long?>(null) }
  var loadFailed by remember { mutableStateOf(false) }
  var retryCount by remember { mutableStateOf(0) }
  val choicesReady = inventory != null && loadedRevision == inventoryRevision
  val apps = if (choicesReady) inventory?.apps else null
  val haPkg = if (choicesReady) inventory?.haPackage else null
  val haIcon = if (choicesReady) inventory?.haIcon else null
  var popup by remember { mutableStateOf<ApprovedEditorsDismissPopup?>(null) }
  var path by remember { mutableStateOf("") }
  var query by remember { mutableStateOf("") }
  val advanced = rememberApprovedAdvanced()
  // Composition-owned IO cancels when this rich editor leaves the Settings pane.
  val application = context.applicationContext
  LaunchedEffect(application, inventoryRevision, retryCount) {
    loadedRevision = null
    loadFailed = false
    try {
      val result = withContext(Dispatchers.IO) { loadScreensaverAppInventory(application) }
      inventory = result
      loadedRevision = inventoryRevision
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      loadFailed = true
    }
  }
  val matches = remember(apps, query) {
    val q = query.trim()
    apps?.filter { q.isEmpty() || it.label.contains(q, ignoreCase = true) ||
      it.component.packageName.contains(q, ignoreCase = true) }
  }
  fun refresh() { settings = ScreensaverConfig.load(context) }
  fun chooseLauncher() { ScreensaverConfig.setDismissLauncher(context); refresh(); popup = null }
  fun chooseHa() {
    // Selecting HA without editing its advanced path retains a previously configured dashboard.
    ScreensaverConfig.setDismissHaDashboard(context, settings.dismissHaDashboard.orEmpty())
    refresh(); popup = null
  }
  Column(if (apps == null) Modifier else Modifier.semantics {
    contentDescription = "Slideshow app choices ready"
  }) {
    SectionLabel("Options")
    Card {
      NavRow("Open when you tap to exit", value = when {
        settings.dismissHaDashboard != null -> if (choicesReady && haPkg == null)
          "Immortal launcher" else "Home Assistant dashboard"
        settings.dismissAppComponent != null -> apps?.firstOrNull {
          it.component.flattenToString() == settings.dismissAppComponent
        }?.label ?: "Chosen app"
        else -> "Immortal launcher"
      }, modifier = Modifier.semantics {
        contentDescription = "Open when you tap to exit"
      }) { popup = ApprovedEditorsDismissPopup.TARGET }
    }
    if (loadFailed) {
      FooterNote("Your installed apps couldn't be loaded. Try again.")
      ApprovedEditorsButton("Try again") { retryCount++ }
    }
    if (settings.dismissHaDashboard != null && haPkg == null && apps != null) {
      FooterNote("The Home Assistant companion app is unavailable. The screensaver returns to Immortal.")
    }
    if (settings.dismissHaDashboard != null && advanced && haPkg != null) {
      SectionLabel("Advanced options")
      Card {
        NavRow("Home Assistant dashboard path", value = settings.dismissHaDashboard.orEmpty().ifBlank {
          "Default dashboard"
        }, subLabel = "Leave blank to open the default dashboard.") {
          path = settings.dismissHaDashboard.orEmpty(); popup = ApprovedEditorsDismissPopup.HA_PATH
        }
      }
    }
  }
  when (popup) {
    ApprovedEditorsDismissPopup.TARGET -> ApprovedSettingsDialog("Open when you tap to exit",
      onDismiss = { popup = null }) {
      Card {
        DismissRow(null, "Immortal launcher", "Return to your home screen (default).",
          settings.dismissHaDashboard == null && settings.dismissAppComponent == null) { chooseLauncher() }
        if (haPkg != null) {
          Divider()
          DismissRow(haIcon, "Home Assistant dashboard", "Open the installed Home Assistant app.",
            settings.dismissHaDashboard != null) { chooseHa() }
        }
        Divider()
        DismissRow(null, "Choose an installed app", "Uses the apps installed on your Portal.",
          settings.dismissHaDashboard == null && settings.dismissAppComponent != null,
          modifier = Modifier.semantics { contentDescription = "Choose an installed app" }) {
          query = ""; popup = ApprovedEditorsDismissPopup.APPS
        }
      }
      Spacer(Modifier.size(24.dp))
      ApprovedEditorsButton("Cancel") { popup = null }
    }
    ApprovedEditorsDismissPopup.APPS -> ApprovedSettingsDialog("Choose an installed app",
      onDismiss = { popup = null }) {
      ApprovedEditorsField("Search installed apps", query, { query = it }, placeholder = "Name or package")
      Card {
        val list = matches
        if (list == null) ApprovedEditorsEmpty(if (loadFailed) "Your apps couldn't be loaded."
          else "Finding your apps…")
        else if (list.isEmpty()) ApprovedEditorsEmpty(if (query.isBlank()) "No launchable apps found."
          else "No apps match \"$query\".")
        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
          itemsIndexed(list, key = { _, app -> app.component.flattenToString() }) { index, app ->
            if (index > 0) Divider()
            val component = app.component.flattenToString()
            DismissRow(app.icon, app.label, null,
              settings.dismissHaDashboard == null && settings.dismissAppComponent == component) {
              ScreensaverConfig.setDismissApp(context, component); refresh(); popup = null
            }
          }
        }
      }
      if (loadFailed) {
        Spacer(Modifier.size(16.dp))
        ApprovedEditorsButton("Try again") { retryCount++ }
      }
      Spacer(Modifier.size(24.dp))
      ApprovedEditorsButton("Cancel") { popup = null }
    }
    ApprovedEditorsDismissPopup.HA_PATH -> ApprovedSettingsDialog("Home Assistant dashboard path",
      onDismiss = { popup = null },
      help = "Use a path such as lovelace/0, a Home Assistant deep link, or a full dashboard URL. " +
        "Leave blank for the default dashboard.") {
      ApprovedEditorsField("Dashboard path", path, { path = it }, placeholder = "lovelace/0")
      ApprovedEditorsActions("Save", onCancel = { popup = null }) {
        ScreensaverConfig.setDismissHaDashboard(context, path); refresh(); popup = null
      }
    }
    null -> Unit
  }
}

@Composable
private fun DismissRow(
    icon: ImageBitmap?, title: String, subtitle: String?, selected: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
  Row(modifier.fillMaxWidth().defaultMinSize(minHeight = 116.dp)
      .tvFocusableRow(focusFill = IosFocusTint, onClick = onClick)
      .padding(horizontal = 24.dp, vertical = 23.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(22.dp)) {
    if (icon != null) Image(icon, contentDescription = null,
      modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)))
    Column(Modifier.weight(1f)) {
      Text(title, color = IosLabel, fontSize = 26.sp, lineHeight = 35.sp)
      if (subtitle != null) Text(subtitle, color = IosSecondary, fontSize = 22.sp,
        lineHeight = 30.sp, modifier = Modifier.padding(top = 7.dp))
    }
    RadioButton(selected, onClick = null,
      colors = RadioButtonDefaults.colors(selectedColor = IosBlue, unselectedColor = IosChevron))
  }
}
