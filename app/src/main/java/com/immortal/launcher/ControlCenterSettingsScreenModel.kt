/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/**
 * Pure pieces of the Control Center settings screen (unit tested): its sections, the two-entry
 * kids-PIN reducer, steppers, and the honest labels for the connection, plan source and registry.
 */
internal enum class CcSetSection(val icon: String, val label: String) {
  PORTALS("tablet", "Portals that show it"),
  KIDS("smile", "Kids & guests"),
  CONFIRM("shield", "Ask before…"),
  BANNERS("bell", "Alerts & banners"),
  LOOK("cube", "Plan style & motion"),
  HA("link", "Home Assistant"),
}

/** Setting a kids PIN: type it, type it again; [done] holds the agreed PIN. */
@Immutable
internal data class CcPinSet(
    val confirming: Boolean = false,
    val first: String = "",
    val entry: String = "",
    val error: String? = null,
    val done: String? = null,
) {
  val title: String get() = if (confirming) "Enter it again" else "Set a kids PIN"
  val subtitle: String
    get() = error ?: if (confirming) "Type the same ${first.length} digits to confirm."
    else "${ControlCenterConfig.MIN_PIN} to ${ControlCenterConfig.MAX_PIN} digits. It's asked when leaving kids mode on this Portal."
  val canSubmit: Boolean get() = entry.length >= ControlCenterConfig.MIN_PIN
}

internal object CcPinSetter {
  fun digit(s: CcPinSet, d: Char): CcPinSet =
      if (!d.isDigit() || s.entry.length >= ControlCenterConfig.MAX_PIN || s.done != null) s else s.copy(entry = s.entry + d, error = null)

  fun back(s: CcPinSet): CcPinSet = s.copy(entry = s.entry.dropLast(1))

  fun submit(s: CcPinSet): CcPinSet = when {
    !s.canSubmit -> s.copy(error = "Use ${ControlCenterConfig.MIN_PIN} to ${ControlCenterConfig.MAX_PIN} digits")
    !s.confirming -> CcPinSet(confirming = true, first = s.entry)
    s.entry == s.first -> s.copy(done = s.entry, error = null)
    else -> CcPinSet(error = "Those didn't match — start again")
  }
}

/** What picking a Portal mode does: switch at once, confirm first, or set a kids PIN first. */
internal enum class CcModeStep { NOTHING, CONFIRM, SET_PIN }

internal object CcSetLogic {
  /** Warning delay presets the stepper walks through (minutes). */
  val WARN_STEPS = listOf(1, 2, 5, 10, 15, 20, 30, 45, 60, 90, 120)
  const val EXIT_STEP = 5

  fun modeStep(current: String, target: String, hasKidsPin: Boolean): CcModeStep = when {
    target == current -> CcModeStep.NOTHING
    target == ControlCenterConfig.MODE_KIDS && !hasKidsPin -> CcModeStep.SET_PIN
    target == ControlCenterConfig.MODE_FULL -> CcModeStep.NOTHING
    else -> CcModeStep.CONFIRM
  }

  fun modeConfirm(target: String): String = when (target) {
    ControlCenterConfig.MODE_KIDS -> "Kids mode hides what you chose under Kids & guests, and Settings and Edit plan. Leaving it asks for the kids PIN."
    ControlCenterConfig.MODE_BANNERS -> "This Portal will only show alert banners; the Control Center closes. Turn it back on in Settings › Home Assistant."
    else -> ""
  }

  fun stepped(value: Int, delta: Int, min: Int, max: Int): Int = (value + delta).coerceIn(min, max)

  fun warnStep(value: Int, up: Boolean): Int =
      if (up) WARN_STEPS.firstOrNull { it > value } ?: WARN_STEPS.last() else WARN_STEPS.lastOrNull { it < value } ?: WARN_STEPS.first()

  /** "10 pm – 8 am" (minutes of day); "Off" when quiet hours are off. */
  fun quietLabel(on: Boolean, startMin: Int, endMin: Int): String = if (!on) "Off" else "${clock(startMin)} – ${clock(endMin)}"

  fun clock(minOfDay: Int): String {
    val m = Math.floorMod(minOfDay, MIN_PER_DAY)
    val h = m / MIN_PER_HOUR
    val h12 = if (h % HALF_DAY == 0) HALF_DAY else h % HALF_DAY
    val mm = m % MIN_PER_HOUR
    return (if (mm == 0) "$h12" else "$h12:${mm.toString().padStart(2, '0')}") + if (h < HALF_DAY) " am" else " pm"
  }

  fun phaseTag(phase: CcPhase): Pair<String, CcTone> = when (phase) {
    CcPhase.LIVE -> "Connected" to CcTone.GREEN
    CcPhase.CONNECTING -> "Connecting" to CcTone.NEUTRAL
    CcPhase.STALE -> "Reconnecting" to CcTone.AMBER
    CcPhase.UNREACHABLE -> "Not answering" to CcTone.RED
    CcPhase.AUTH_FAILED -> "Token refused" to CcTone.RED
    CcPhase.OFFLINE -> "Portal offline" to CcTone.RED
    CcPhase.NOT_CONFIGURED -> "Not set up" to CcTone.AMBER
  }

  fun planSource(src: CcPlanSource, plan: CcPlan?): Pair<String, String> = when {
    plan == null || plan.isEmpty -> "No floor plan yet" to "Build one with Rebuild plan below."
    src == CcPlanSource.HOME_ASSISTANT -> "In Home Assistant" to "Shared by every Portal on this HA user · a copy is cached on this Portal"
    src == CcPlanSource.THIS_PORTAL -> "On this Portal only" to "Home Assistant hasn't stored it yet — saving again while connected shares it"
    else -> "Not loaded yet" to "Looking for the plan in Home Assistant"
  }

  fun registryLine(reg: CcRegistry?): String {
    if (reg == null) return "Not available — reading floors and areas needs an administrator's token"
    val floors = if (reg.areas.isEmpty()) 0 else CcPlanEdit.floorsFor(reg).size
    return "$floors ${plural(floors, "floor")} · ${reg.areas.size} ${plural(reg.areas.size, "area")} · ${reg.entities.size} ${if (reg.entities.size == 1) "entity" else "entities"}"
  }

  private const val MIN_PER_DAY = 24 * 60
  private const val MIN_PER_HOUR = 60
  private const val HALF_DAY = 12
}
