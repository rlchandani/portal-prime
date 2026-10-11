/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.immortal.launcher.settings.SettingsDomain
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings screen for the photo-frame screensaver. Reached from a "Screensaver" tile in the
 * launcher's Settings folder. The controls are now rendered generically from the `screensaver` and
 * `calendar` settings domains (the same registry that drives the phone remote) via [SettingsList];
 * the rich sub-screens (clock-face picker, photo-source setup, calendar/dismiss entry) stay bespoke
 * and are reached through the registry's `NavSpec`s.
 */
class ScreensaverSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { ScreensaverSettingsScreen() } }
  }
}

@Composable
private fun ScreensaverSettingsScreen() {
  val activity = LocalContext.current as? Activity
  SettingsScaffold(
      title = "Screensaver",
      subtitle = "Choose what shows on the photo frame when your Portal is idle.",
      onBack = { activity?.finish() },
      lazyDetail = screensaverSettingsDetail(),
  ) {}
}

/** One lazy list is reused by the main Settings pane and the direct Activity entry. */
@Composable
internal fun screensaverSettingsDetail(): ApprovedSettingsLazyDetail {
  val context = LocalContext.current
  val activity = context as? Activity
  val editor = rememberScreensaverEditor()
  val settings by editor.settings
  fun apply(domain: SettingsDomain<ScreensaverConfig.Settings>, key: String, value: Any) =
    editor.apply(domain, key, value)
  val screensaver = if (settings.enabled) prepareSettingsList(
      SettingsDomains.screensaver, settings, exclude = setOf("enabled"),
      editsEnabled = !editor.applying.value,
  ) { key, value -> apply(SettingsDomains.screensaver, key, value) } else null
  val calendar = if (settings.enabled) prepareSettingsList(
      SettingsDomains.calendar, settings, editsEnabled = !editor.applying.value,
  ) { key, value -> apply(SettingsDomains.calendar, key, value) } else null
  val requested = LocalApprovedRequestedControl.current
      ?: activity?.intent?.getStringExtra(APPROVED_SETTINGS_CONTROL)
  val scroll = rememberLazyListState()
  val index = approvedScreensaverControlIndex(
      requested, settings.enabled, screensaver?.projection?.controlIndices.orEmpty(),
      screensaver?.itemCount ?: 0, calendar?.projection?.controlIndices.orEmpty(),
  )
  LaunchedEffect(requested, index) { if (index != null) scroll.scrollToItem(index) }
  val firstFocus = remember { FocusRequester() }
  var focusedOnce by remember { mutableStateOf(false) }
  return ApprovedSettingsLazyDetail(scroll, accessibilityLabel = "Slideshow settings list") {
    item(key = "screensaver.enabled", contentType = "master") {
      // The host creates this list model before its incoming pane mounts. Request focus
      // from the attached row, once per visit, rather than before the FocusRequester attaches.
      LaunchedEffect(firstFocus) {
        if (!focusedOnce) {
          firstFocus.requestFocus()
          focusedOnce = true
        }
      }
      ApprovedSettingsLazyRow(
          "screensaver.enabled", true, true, requested,
          modifier = Modifier.focusRequester(firstFocus).focusGroup(),
      ) {
        ToggleRow("Show the photo-frame screensaver", settings.enabled, enabled = !editor.applying.value) {
          apply(SettingsDomains.screensaver, "enabled", it)
        }
      }
    }
    item(key = "screensaver:master-footer", contentType = "footer") {
      ScreensaverSaveStatus(editor)
    }
    if (settings.enabled && screensaver != null && calendar != null) {
      item(key = "screensaver:leading-gap", contentType = "gap") { Spacer(Modifier.size(20.dp)) }
      settingsListItems(screensaver)
      item(key = "calendar:page-heading", contentType = "section-heading") { SectionLabel("Calendar") }
      settingsListItems(calendar)
      item(key = "screensaver:preview-gap", contentType = "gap") { Spacer(Modifier.size(6.dp)) }
      item(key = "screensaver:preview", contentType = "preview") {
        PrimaryPillButton("Preview screensaver", enabled = !editor.applying.value) {
          context.startActivity(Intent(context, PhotoFramePreviewActivity::class.java))
        }
      }
    }
  }
}

private data class ScreensaverSettingsEditor(
  val settings: MutableState<ScreensaverConfig.Settings>,
  val applying: MutableState<Boolean>,
  val failed: MutableState<Boolean>,
  val apply: (SettingsDomain<ScreensaverConfig.Settings>, String, Any) -> Unit,
)

/** Pane-owned readbacks cancel on departure; accepted writes belong to the process. */
@Composable
private fun rememberScreensaverEditor(): ScreensaverSettingsEditor {
  val application = LocalContext.current.applicationContext
  val writes = remember(application) { ScreensaverSettingsWrites.queue(application) }
  val state = remember(application) { mutableStateOf(ScreensaverConfig.load(application)) }
  val applying = remember { mutableStateOf(true) }
  val failed = remember { mutableStateOf(false) }
  var editRevision by remember { mutableStateOf(0) }
  val completion = rememberCoroutineScope()
  val lifecycleOwner = LocalLifecycleOwner.current
  val pageActive = LocalApprovedLauncherPageActive.current
  suspend fun readSnapshot() = withContext(Dispatchers.IO) { ScreensaverConfig.load(application) }
  LaunchedEffect(application, lifecycleOwner, pageActive) {
    if (!pageActive) return@LaunchedEffect
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      val revision = editRevision
      val failures = writes.failureCount
      try {
        writes.awaitIdle()
        val loaded = readSnapshot()
        if (revision == editRevision) {
          state.value = loaded
          failed.value = writes.failureCount != failures
        }
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { if (revision == editRevision) failed.value = true }
      finally { if (revision == editRevision) applying.value = false }
    }
  }
  return ScreensaverSettingsEditor(state, applying, failed) { domain, key, value ->
    applying.value = true
    failed.value = false
    val revision = ++editRevision
    val failures = writes.failureCount
    writes.submit(ScreensaverSettingEdit(domain.id, key, value))
    completion.launch {
      try {
        writes.awaitIdle()
        val loaded = readSnapshot()
        if (revision == editRevision) {
          state.value = loaded
          failed.value = writes.failureCount != failures
        }
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { if (revision == editRevision) failed.value = true }
      finally { if (revision == editRevision) applying.value = false }
    }
  }
}

@Composable
private fun ScreensaverSaveStatus(editor: ScreensaverSettingsEditor) {
  val marker = when {
    editor.applying.value -> "Saving Slideshow settings"
    editor.failed.value -> "Slideshow save unavailable"
    else -> "Slideshow settings ready"
  }
  Column(Modifier.semantics { contentDescription = marker }) {
    FooterNote(when {
      editor.applying.value -> "Saving changes…"
      editor.failed.value -> "Some changes couldn't be saved. Try again."
      else -> "Show photos when your Portal is idle."
    })
  }
}

/** Inlinable body of the Screensaver settings screen — rendered both by [ScreensaverSettingsScreen]
 *  (standalone Activity host) and by the two-panel settings detail panel. */
@Composable
internal fun ScreensaverSettingsContent() {
  val context = LocalContext.current
  val editor = rememberScreensaverEditor()
  val settings by editor.settings

  val firstFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

  // Every change routes through the registry's apply (which fires the domain's onApplied side
  // effects — reaffirm + overnight reschedule), then we re-read for the next recomposition.
  fun apply(domain: SettingsDomain<ScreensaverConfig.Settings>, key: String, value: Any) {
    editor.apply(domain, key, value)
  }

    Column(modifier = Modifier.focusRequester(firstFocus).focusGroup()) {
      // Master toggle gates everything below (kept here rather than in the list so the rest only
      // renders when the frame is on, matching the original screen).
      Card {
        ToggleRow("Show the photo-frame screensaver", settings.enabled, enabled = !editor.applying.value) {
          apply(SettingsDomains.screensaver, "enabled", it)
        }
      }
      ScreensaverSaveStatus(editor)

      if (settings.enabled) {
        Spacer(Modifier.size(20.dp))
        SettingsList(SettingsDomains.screensaver, settings, exclude = setOf("enabled"),
          editsEnabled = !editor.applying.value) { k, v ->
          apply(SettingsDomains.screensaver, k, v)
        }
        SectionLabel("Calendar")
        SettingsList(SettingsDomains.calendar, settings, editsEnabled = !editor.applying.value) { k, v ->
          apply(SettingsDomains.calendar, k, v)
        }
        Spacer(Modifier.size(6.dp))
        Surface(
            color = IosBlue,
            shape = RoundedCornerShape(16.dp),
            modifier =
                Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) {
                  if (!editor.applying.value)
                    context.startActivity(Intent(context, PhotoFramePreviewActivity::class.java))
                },
        ) {
          Text(
              "Preview screensaver",
              color = Color.White,
              fontSize = 18.sp,
              fontWeight = FontWeight.SemiBold,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth(),
          )
        }
      }
    }
}
