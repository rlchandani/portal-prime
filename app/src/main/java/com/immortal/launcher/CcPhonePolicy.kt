/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * What a paired phone may do (pure, unit tested). The phone is a household member's own device:
 * kids mode is this Portal's screen setting and doesn't apply, but the household's asks do
 * (unlock, garage, valve, whole-house scenes; disarm and car unlock/open always ask). An asking
 * call goes only with a confirm ticket the phone earned by showing its confirm (biometric or a
 * dialog) — see [CcRemoteConfirm]. Unknown entities never get a blind call from the phone.
 */
internal object CcPhonePolicy {
  /** The Portal's settings as the phone sees them: same asks, never kids mode. */
  fun settings(portal: ControlCenterConfig.Settings): ControlCenterConfig.Settings =
      portal.copy(portalMode = ControlCenterConfig.MODE_FULL)

  fun ask(e: CcEntity, call: CcCall, portal: ControlCenterConfig.Settings, scenesSeen: () -> Set<String>): CcPolicy.Ask =
      CcPolicy.ask(e, call, settings(portal), if (call.domain == "scene") scenesSeen() else emptySet())

  /** Null when [call] may go now; else the refusal. [confirmed] = a valid ticket for exactly this call. */
  fun refusal(e: CcEntity, call: CcCall, portal: ControlCenterConfig.Settings, confirmed: Boolean, scenesSeen: () -> Set<String>): String? =
      CcPolicy.refusal(e, call, settings(portal), confirmed, scenesSeen)

  /** The phone's confirm sheet: title, body and button for an asking call (household words, as on the Portal). */
  fun prompt(e: CcEntity, call: CcCall, ask: CcPolicy.Ask, carName: String): Triple<String, String, String> = when {
    call.guard == CcGuard.CONFIRM -> CcTeslaCalls.confirm(carName, call)
    ask == CcPolicy.Ask.PIN -> Triple("${CcAlarmMode.entries.firstOrNull { it.service == call.service }?.label ?: "Change the alarm"}?",
        if (e.codeFormat != null) "Enter the alarm code." else "Confirm it's you.", "Confirm")
    call.domain == "lock" -> Triple("Unlock ${e.name}?", "Anyone at the door can come in once it's unlocked.", "Unlock")
    call.domain == "cover" && call.service == "close_cover" -> Triple("Close ${e.name}?", "Check the doorway is clear.", "Close")
    call.domain == "cover" -> Triple("Open ${e.name}?", "Check the doorway is clear.", "Open")
    call.domain == "valve" && call.service == "close_valve" -> Triple("Shut off ${e.name}?", "Stops all taps until it's turned back on.", "Shut off")
    call.domain == "valve" -> Triple("Turn ${e.name} back on?", "Water flows again.", "Turn on")
    call.domain == "scene" || call.domain == "script" -> Triple("Run ${e.name}?",
        if (CcPolicy.sensitive(e)) "It changes locks, doors or the alarm." else "It changes ${e.members.size} things around the house.", "Run")
    else -> Triple("${call.label.ifBlank { e.name }}?", "Confirm it's you.", "Confirm")
  }
}

/** The phone's live-state lease on this Portal's Home Assistant subscription. */
internal object CcRemoteWatch {
  /** Each phone read renews it; the phone polls far more often while its screen is open. */
  const val LEASE_MS = 45_000L
  const val EXPIRY_SLACK_MS = 500L
}
