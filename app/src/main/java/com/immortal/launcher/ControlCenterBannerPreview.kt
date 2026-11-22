/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/**
 * The Alerts screen's "Preview a Home banner" (mock `.chips`; the mock's longer "Preview a banner on
 * Portal home" left "Home" alone on a second line): one sample per severity, shown through [ControlCenterBanners.preview]. A preview is local and cosmetic: never an entity,
 * never a sound (quiet hours or not), never a wake, never sent to the household, never counted by
 * [CcAlertGate] or the alert history, and it always gives way to a real alert ([CcBannerSlot]).
 */
internal object CcBannerPreview {
  @Immutable
  data class Sample(val label: String, val icon: String, val alert: CcAlert)

  /** What the banner host does for one preview tap. Every side effect a real alert has is off. */
  @Immutable
  data class Request(val alert: CcAlert, val takeover: Boolean, val timeoutMs: Long) {
    val sound: Boolean get() = false
    val wake: Boolean get() = false
    val relay: Boolean get() = false
  }

  /** Short enough to be a glance, long enough to read the takeover. */
  const val TIMEOUT_MS = 8_000L
  /** The tag drawn on the preview so nobody mistakes it for a real alert. */
  const val TAG_LABEL = "Preview"
  const val CLOSE_LABEL = "Close preview"

  private const val ID = "preview:"

  val samples: List<Sample> = listOf(
      Sample("Washer done", "washer", CcAlert("${ID}washer", CcSeverity.INFO, CcKind.APPLIANCE, "", "Washer is done",
          "Preview · Laundry · finished 7:30 pm", 0L, CcAlertAction.DISMISS)),
      Sample("Garage open", "garage", CcAlert("${ID}garage", CcSeverity.WARNING, CcKind.GARAGE, "", "Garage door has been open 17 min",
          "Preview · opened 7:25 pm · after sunset", 0L, CcAlertAction.CLOSE, deviceClass = "garage")),
      Sample("Leak (critical)", "leak", CcAlert("${ID}leak", CcSeverity.CRITICAL, CcKind.LEAK, "", "Water leak · Laundry",
          "Preview · every Portal shows this full screen, with sound", 0L, CcAlertAction.SHUT_WATER, deviceClass = "moisture")),
  )

  fun isPreview(alert: CcAlert): Boolean = alert.id.startsWith(ID) && alert.entityId.isEmpty()

  /** Criticals take over the screen, as the real ones do; the rest are a top banner. */
  fun takeover(alert: CcAlert): Boolean = alert.severity == CcSeverity.CRITICAL

  /** The preview for a chip tap, or null for anything that isn't one of [samples]. */
  fun request(alert: CcAlert): Request? =
      if (isPreview(alert) && samples.any { it.alert == alert }) Request(alert, takeover(alert), TIMEOUT_MS) else null
}

/**
 * Who owns the one banner window. A real alert always wins: it replaces a preview, and a preview
 * never replaces (or times out) a real alert. Among real alerts a showing critical keeps the screen.
 */
internal object CcBannerSlot {
  @Immutable
  data class Showing(val severity: CcSeverity, val preview: Boolean)

  fun admitReal(showing: Showing?, alert: CcAlert): Boolean =
      showing == null || showing.preview || !(showing.severity == CcSeverity.CRITICAL && alert.severity != CcSeverity.CRITICAL)

  fun admitPreview(showing: Showing?): Boolean = showing == null || showing.preview
}

/**
 * Where a banner window can go. Real alerts need the accessibility overlay ([BarWatchService]) so
 * they show over any app. A preview is tapped inside the Control Center, so it is drawn in the
 * Control Center's own window: the accessibility host is unbound whenever a UiAutomation client
 * runs (uiautomator, capture tooling) or the app is updated, and its detach removes every overlay,
 * which took previews down within ~2 s. The overlay is only the fallback without a usable window.
 */
internal enum class CcBannerHost {
  ACCESSIBILITY, ACTIVITY, NONE;

  companion object {
    fun forReal(accessibility: Boolean): CcBannerHost = if (accessibility) ACCESSIBILITY else NONE

    fun forPreview(accessibility: Boolean, activity: Boolean): CcBannerHost = when {
      activity -> ACTIVITY
      accessibility -> ACCESSIBILITY
      else -> NONE
    }

    /** The accessibility host went away: only what it hosts goes with it. */
    fun removedOnDetach(where: CcBannerHost): Boolean = where == ACCESSIBILITY
  }
}
