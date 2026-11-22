/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.BriefingBuilder
import com.immortal.launcher.BriefingCopy
import com.immortal.launcher.BriefingInputs
import com.immortal.launcher.BriefingPart
import com.immortal.launcher.BriefingSpeech
import com.immortal.launcher.GuestContent
import org.json.JSONException
import org.json.JSONObject

/**
 * `hearthpane.briefing` for Jarvis: "what's my briefing" → the household morning summary as text for Jarvis to say
 * (greeting, time, weather, today's calendar, the household list, running timers, the first morning routine), or
 * with `show` the Briefing card on the Portal's screen, which reads itself aloud when its setting allows (quiet hours
 * keep it silent). Only the household briefing reaches the model: never personal reminders or private events, and
 * what Guest mode hides (calendar, household list) is left out while guests are here. Pure apart from its seams.
 */
internal class HearthpaneBriefing(
    /** Local inputs plus whatever weather and calendar answered within the tool's time budget. */
    private val inputs: () -> BriefingInputs,
    private val open: () -> Unit,
    private val hides: (GuestContent) -> Boolean = { false },
    /** The card's "Read it aloud" is on and it's outside quiet hours. */
    private val readsAloud: () -> Boolean = { false },
) {
  fun invoke(argsJson: String?): JSONObject {
    val args = try { JSONObject(argsJson ?: "{}") } catch (_: JSONException) { return error("arguments must be a JSON object") }
    return runCatching { if ((args.opt("show") as? Boolean) == true) show() else read() }
        .getOrElse { error("briefing unavailable") }
  }

  private fun read(): JSONObject {
    val i = guarded(inputs())
    val today = (i.events as? BriefingPart.Ready)?.value?.let { BriefingBuilder.todaysEvents(it, i) }
    val time = BriefingCopy.clock(i.nowMs, i.zone, i.locale, i.use24Hour)
    val text = BriefingSpeech.summary(i, today, BriefingBuilder.firstMorningRoutine(i), time)
    val out = JSONObject().put("ok", true).put("briefing", text)
    if (i.weather !is BriefingPart.Ready || (i.weather as BriefingPart.Ready).value == null)
      out.put("note", "No weather in this briefing; use a weather lookup if the user wants it.")
    return out
  }

  private fun show(): JSONObject {
    open()
    val aloud = readsAloud()
    return JSONObject().put("ok", true).put("reads_aloud", aloud).put("message",
        if (aloud) "The briefing is on the Portal's screen and is reading itself aloud: say nothing more than OK."
        else "The briefing is on the Portal's screen.")
  }

  /** Strips what Guest mode hides, and anything personal. */
  private fun guarded(i: BriefingInputs): BriefingInputs = i.copy(
      personal = null,
      events = if (hides(GuestContent.CALENDAR)) BriefingPart.Ready(null) else i.events,
      listItems = if (hides(GuestContent.HOUSEHOLD_LIST)) emptyList() else i.listItems,
  )

  private fun error(message: String) = JSONObject().put("error", message)

  companion object {
    const val NAME = "hearthpane.briefing"
  }
}
