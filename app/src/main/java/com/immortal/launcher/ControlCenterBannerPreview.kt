/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/**
 * The Alerts screen's "Preview a banner on Portal home" (mock `.chips`): one sample per severity,
 * shown silently through [ControlCenterBanners.preview] — never an entity, a sound or an HA call,
 * so dry run and quiet hours need nothing special. The body says it is a preview.
 */
internal object CcBannerPreview {
  @Immutable
  data class Sample(val label: String, val icon: String, val alert: CcAlert)

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
}
