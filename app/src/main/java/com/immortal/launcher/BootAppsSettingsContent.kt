/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.immortal.launcher.ui.components.OnResume
import com.immortal.launcher.settings.SettingsDomains
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class BootAppOption(val pkg: String, val label: String, val icon: ImageBitmap)
/** Launchable apps except our launcher and retired features, for the boot-launch picker. */
private fun loadLaunchableApps(context: Context): List<BootAppOption> {
  val pm = context.packageManager
  val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
  return pm.queryIntentActivities(intent, 0)
      .filter {
        it.activityInfo.packageName != context.packageName &&
            it.activityInfo.packageName !in Curation.retiredPackages
      }
      .mapNotNull { ri ->
        runCatching {
              BootAppOption(
                  pkg = ri.activityInfo.packageName,
                  label = ri.loadLabel(pm).toString(),
                  icon = ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap())
            }
            .getOrNull()
      }
      .distinctBy { it.pkg }
      .sortedBy { it.label.lowercase(Locale.getDefault()) }
}

/**
 * The "Start on boot" subpage: pick which installed apps Immortal relaunches after a
 * reboot — the same per-device list provisioning seeds (so e.g. the Music Assistant /
 * Sendspin player, which has no boot receiver, comes back on its own). Toggling a row
 * writes the list straight to the file [BootLaunch] reads on boot. Back returns to the
 * main settings page (handled by the parent via [onBack]).
 */
@Composable
internal fun BootAppsScreen(onBack: () -> Unit) {
  SettingsScaffold(
      title = "Start on boot",
      subtitle = "Pick which installed apps Hearthpane relaunches after a reboot.",
      onBack = onBack,
  ) { BootAppsContent() }
}

/** Inlinable body of the Start-on-boot screen — rendered by [BootAppsScreen] (standalone Activity
 *  host) and by the two-panel settings detail panel. Owns the boot-launch selection set (the
 *  Activity used to hold it) so it's self-contained wherever it's rendered. */
@Composable
internal fun BootAppsContent() {
  val context = LocalContext.current.applicationContext
  val owner = LocalLifecycleOwner.current
  val pageActive = LocalApprovedLauncherPageActive.current
  var startupSettings by remember(context) { mutableStateOf(ImmortalSettings.load(context)) }
  OnResume { startupSettings = ImmortalSettings.load(context) }
  DisposableEffect(context, pageActive) {
    val stop = if (pageActive) ImmortalSettings.observeSnapshot(context) {
      startupSettings = ImmortalSettings.load(context)
    } else null
    onDispose { stop?.invoke() }
  }
  var apps by remember(context) { mutableStateOf<List<BootAppOption>?>(null) }
  var selected by remember(context) { mutableStateOf<Set<String>>(emptySet()) }
  var loading by remember(context) { mutableStateOf(true) }
  var applying by remember(context) { mutableStateOf(false) }
  var problem by remember(context) { mutableStateOf<String?>(null) }
  var refresh by remember(context) { mutableStateOf(0) }
  val scope = rememberCoroutineScope()
  val writeLock = remember(context) { Mutex() }
  LaunchedEffect(context, owner, pageActive, refresh) {
    if (!pageActive) return@LaunchedEffect
    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      loading = true
      try {
        val loaded = withContext(Dispatchers.IO) {
          writeLock.withLock {
            val saved = BootLaunch.readPackages(context).toSet()
            loadLaunchableApps(context) to saved
          }
        }
        apps = loaded.first
        selected = loaded.second
        problem = null
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        problem = "Could not load startup apps."
      } finally {
        loading = false
      }
      awaitCancellation()
    }
  }
  val canEdit = apps != null && !loading && !applying && problem == null
  val onSelectionChange: (String, Boolean) -> Unit = { pkg, removeOnly ->
    if (canEdit && !applying && !loading && problem == null) {
      applying = true
      scope.launch {
        try {
          val confirmed = withContext(Dispatchers.IO) {
            writeLock.withLock {
              val saved = BootLaunch.readPackages(context).toSet()
              // Removing an unavailable entry must never re-add it after another writer removed it.
              val chosen = if (removeOnly || pkg in saved) saved - pkg else saved + pkg
              if (chosen != saved) check(BootLaunch.setPackages(context, chosen.toList()))
              BootLaunch.readPackages(context).toSet().also { check(it == chosen) }
            }
          }
          selected = confirmed
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          // Keep the last confirmed values and require a fresh read before editing again.
          problem = "Could not save your choice."
        } finally {
          applying = false
        }
      }
    }
  }
  val listedPackages = remember(apps) { apps.orEmpty().map { it.pkg }.toSet() }
  val unlisted = remember(selected, listedPackages) { selected.filterNot { it in listedPackages } }
  Column(Modifier.semantics {
    contentDescription = when {
      applying -> "Saving startup apps"
      loading -> "Startup apps loading"
      problem != null -> "Startup apps unavailable"
      else -> "Startup apps ready"
    }
  }) {
    val domain = SettingsDomains.immortal
    SettingsRows(domain, startupSettings,
        exclude = domain.specs.map { it.key }.toSet() - "stayInStartupApp") { key, value ->
      domain.apply(context, JSONObject().put(key, value))
      startupSettings = ImmortalSettings.load(context)
    }
    problem?.let { message ->
      FooterNote(message)
      if (apps != null) FooterNote("Last confirmed choices are shown.")
      if (!loading && !applying) ApprovedSettingsTextButton("Try again") {
        loading = true
        refresh++
      }
    }
    if (applying) FooterNote("Saving your choice…")
    if (loading && apps != null) FooterNote("Refreshing startup apps…")
    Card {
      val list = apps
      if (list == null) {
        Text(if (loading) "Loading apps…" else "Try again to read your saved choices.",
            color = IosSecondary, fontSize = Sizes.rowSub, modifier = Modifier.padding(18.dp))
      } else if (list.isEmpty()) {
        Text("No other apps installed yet.", color = IosSecondary,
            fontSize = Sizes.rowSub, modifier = Modifier.padding(18.dp))
      } else {
        LazyColumn(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp - 400).coerceAtLeast(400).dp)) {
          itemsIndexed(list, key = { _, app -> app.pkg }) { i, app ->
            if (i > 0) Divider()
            Row(
                modifier = Modifier.fillMaxWidth()
                    .defaultMinSize(minHeight = Sizes.rowMinHeight)
                    .tvFocusableRow(focusFill = IosFocusTint, enabled = canEdit) { onSelectionChange(app.pkg, false) }
                    .semantics {
                      role = Role.Switch
                      toggleableState = ToggleableState(app.pkg in selected)
                    }
                    .padding(horizontal = 28.dp, vertical = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(42.dp))
              Text(app.label, color = IosLabel, fontSize = Sizes.rowTitle,
                  modifier = Modifier.weight(1f).padding(start = 14.dp))
              ApprovedSettingsSwitch(app.pkg in selected, enabled = canEdit)
            }
          }
        }
      }
    }
    if (apps != null && unlisted.isNotEmpty()) {
      SectionLabel("Saved apps not listed")
      FooterNote("These startup choices are saved, but their apps aren't shown above. " +
          "Remove a choice to stop trying to start it after a reboot.")
      Card {
        LazyColumn(Modifier.heightIn(max = 280.dp)) {
          itemsIndexed(unlisted, key = { _, pkg -> pkg }) { index, pkg ->
            if (index > 0) Divider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp)) {
              Text(pkg, color = IosLabel, fontSize = Sizes.rowSub,
                  maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                  modifier = Modifier.weight(1f))
              ActionPill("Remove", enabled = canEdit,
                  modifier = Modifier.semantics { contentDescription = "Remove startup entry $pkg" }) {
                onSelectionChange(pkg, true)
              }
            }
          }
        }
      }
    }
    Text("These apps relaunch automatically after a reboot — handy for players like Music " +
        "Assistant that don't restart themselves.", color = IosSecondary,
        fontSize = Sizes.rowSub, modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp))
  }
}
