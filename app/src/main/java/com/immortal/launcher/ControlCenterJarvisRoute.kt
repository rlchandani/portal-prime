/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Policy for Jarvis's household requests (pure, unit tested): the same rules as a tap on this
 * Portal ([CcUi.act]). Kids mode's hidden and read-only things are refused; anything that asks
 * first — unlocking, the garage, the water valve, the alarm, sensitive or first whole-house
 * scenes, the car's confirm commands — is never run by voice: it waits for the household on the
 * Portal's own confirm sheet. Everything else runs.
 */
internal object CcHomeRoute {
  data class Routed(val run: List<CcHomeItem>, val confirm: List<CcHomeItem>, val refused: List<Refusal>)

  data class Refusal(val item: CcHomeItem, val kids: Boolean)

  fun split(items: List<CcHomeItem>, s: ControlCenterConfig.Settings, scenesSeen: () -> Set<String>): Routed {
    val run = ArrayList<CcHomeItem>()
    val confirm = ArrayList<CcHomeItem>()
    val refused = ArrayList<Refusal>()
    for (item in items) {
      val e = item.entity
      val call = item.call
      when {
        CcPolicy.hidden(e, s) -> refused += Refusal(item, kids = true)
        CcPolicy.readOnly(e, s) -> refused += Refusal(item, kids = true)
        call.guard != CcGuard.NONE && s.kids -> refused += Refusal(item, kids = true)
        CcPolicy.ask(e, call, s, if (call.domain == "scene") scenesSeen() else emptySet()) == CcPolicy.Ask.NONE -> run += item
        else -> confirm += item
      }
    }
    return Routed(run, confirm, refused)
  }

  /** How the run went: calls Home Assistant accepted, and calls that failed or didn't answer in time. */
  data class Outcome(val ok: Int, val failed: Int, val pending: Int)

  /** The one or two sentences Jarvis says back. */
  fun reply(a: CcHomeIntent.Act, r: Routed, o: Outcome, dryRun: Boolean): String {
    val parts = ArrayList<String>()
    if (r.run.isNotEmpty()) parts += done(a, r.run.size)
    if (o.failed > 0) parts += if (o.failed == r.run.size) "Home Assistant didn't do it." else "${o.failed} didn't respond."
    r.confirm.firstOrNull()?.let { parts += "${asking(it)} needs a tap to confirm. It's on the Portal's screen." }
    if (r.confirm.size > 1) parts += "Do the other ${r.confirm.size - 1} on screen too."
    if (r.run.isEmpty() && r.confirm.isEmpty()) {
      parts += when {
        r.refused.isNotEmpty() -> "That one needs a grown-up."
        a.already > 0 -> already(a)
        else -> "Nothing to do."
      }
    }
    if (dryRun && r.run.isNotEmpty()) parts += "(Dry run: nothing was sent to Home Assistant.)"
    return parts.joinToString(" ")
  }

  /** Jarvis's "Done" card: what changed and its new state (null when nothing ran). */
  data class Card(val title: String, val state: String, val on: Boolean?)

  fun card(a: CcHomeIntent.Act, r: Routed): Card? {
    if (r.run.isEmpty()) return null
    val title = a.device ?: "${a.place?.removePrefix("the ")?.replaceFirstChar { it.uppercase() } ?: "Home"} ${a.what.noun}"
    val state = when (a.verb) {
      CcHomeVerb.ON -> "On"; CcHomeVerb.OFF -> "Off"; CcHomeVerb.LOCK -> "Locked"; CcHomeVerb.UNLOCK -> "Unlocked"
      CcHomeVerb.OPEN -> "Opening"; CcHomeVerb.CLOSE -> "Closing"; CcHomeVerb.STOP -> "Stopped"
      CcHomeVerb.BRIGHTNESS -> "${a.value?.toInt() ?: 0}%"; CcHomeVerb.TEMPERATURE -> "${CcHomeCommand.fmt(a.value ?: 0.0)}°"
      CcHomeVerb.RUN -> "Started"; CcHomeVerb.ARM -> "Arming"; CcHomeVerb.DISARM -> "Disarmed"
    }
    val on = when (a.verb) { CcHomeVerb.ON -> true; CcHomeVerb.OFF -> false; else -> null }
    return Card(title.take(MAX_TITLE), state, on)
  }

  private fun done(a: CcHomeIntent.Act, n: Int): String {
    val what = if (a.device != null) "the ${a.device.lowercase()}" else "$n ${if (n == 1) a.what.one else a.what.noun} in ${a.place}"
    return when (a.verb) {
      CcHomeVerb.ON -> "Turned on $what."
      CcHomeVerb.OFF -> "Turned off $what."
      CcHomeVerb.LOCK -> "Locked $what."
      CcHomeVerb.UNLOCK -> "Unlocked $what."
      CcHomeVerb.OPEN -> "Opening $what."
      CcHomeVerb.CLOSE -> "Closing $what."
      CcHomeVerb.STOP -> "Stopped $what."
      CcHomeVerb.BRIGHTNESS -> "Set $what to ${a.value?.toInt() ?: 0}%."
      CcHomeVerb.TEMPERATURE -> "Set $what to ${CcHomeCommand.fmt(a.value ?: 0.0)}°."
      CcHomeVerb.RUN -> "Started ${a.device ?: "the scene"}."
      CcHomeVerb.ARM -> "Arming $what."
      CcHomeVerb.DISARM -> "Disarmed $what."
    }
  }

  private fun already(a: CcHomeIntent.Act): String {
    val state = when (a.verb) {
      CcHomeVerb.ON -> "on"; CcHomeVerb.OFF, CcHomeVerb.BRIGHTNESS -> "off"; CcHomeVerb.LOCK -> "locked"
      CcHomeVerb.UNLOCK -> "unlocked"; CcHomeVerb.OPEN -> "open"; CcHomeVerb.CLOSE -> "closed"; else -> "set"
    }
    return if (a.device != null) "The ${a.device.lowercase()} is already $state."
    else "The ${a.what.noun} in ${a.place} are already $state."
  }

  /** "Unlocking the front door", "Closing the garage door", "Shutting off the water", "Running Good night". */
  fun asking(item: CcHomeItem): String {
    val e = item.entity
    val c = item.call
    val name = e.name
    return when {
      c.domain == "lock" -> "Unlocking ${the(name)}"
      c.domain == "cover" -> "${if (c.service == "close_cover") "Closing" else "Opening"} ${the(name)}"
      c.domain == "valve" || c.domain == "switch" -> if (c.service == "close_valve" || c.service == "turn_off") "Shutting off the water" else "Turning the water back on"
      c.domain == "alarm_control_panel" -> if (c.service == "alarm_disarm") "Disarming the alarm" else "Arming the alarm"
      c.domain == "scene" || c.domain == "script" -> "Running $name"
      else -> c.label.ifBlank { name }
    }
  }

  private fun the(name: String) = if (name.lowercase().startsWith("the ")) name.lowercase() else "the ${name.lowercase()}"

  private const val MAX_TITLE = 60
}
