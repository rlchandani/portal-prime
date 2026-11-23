/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** How one house request of a routine went. */
internal data class HomeStepResult(val request: String, val status: Status, val reply: String) {
  enum class Status { DONE, CONFIRM, SKIPPED, FAILED, REFUSED }
}

/** A routine's house requests, run together; [error] when the house couldn't be reached at all. */
internal data class HomeBatch(val steps: List<HomeStepResult>, val error: String?)

/**
 * `hearthpane.routines`: Jarvis runs a multi-step routine ("good night", "I'm leaving") without a Home Assistant
 * scene. Every house step goes through the same resolve-and-policy gate as `hearthpane.home_control`, all in one
 * batch inside Jarvis's tool timeout: unlocking, the garage, the water valve and the alarm are never done by voice,
 * they wait for a tap on the Portal's own confirm sheet. A step the house has no device for is left out quietly.
 * Do Not Disturb is handed back to Jarvis (`also_call`), and the next wake-up alarm is said. Households make, list
 * and delete their own routines by voice. Arguments are untrusted (model output). Pure apart from its seams.
 */
internal class HearthpaneRoutines(
    private val saved: () -> List<HomeRoutine>,
    private val save: (List<HomeRoutine>) -> Unit,
    /** Runs the house requests together (the Control Center gate). */
    private val home: (List<String>) -> HomeBatch,
    /** "tomorrow at 7 am" for the next wake-up alarm, null when none is on. */
    private val nextAlarm: () -> String?,
) {
  fun invoke(argsJson: String?): JSONObject {
    val args = try { JSONObject(argsJson ?: "{}") } catch (_: JSONException) { return error("arguments must be a JSON object") }
    return runCatching {
      when (args.optString("action").trim().lowercase()) {
        "run", "start", "do" -> run(args)
        "list", "read" -> list()
        "create", "save", "add", "make" -> create(args)
        "delete", "remove" -> delete(args)
        else -> error("action must be run, list, create or delete")
      }
    }.getOrElse { error("routines unavailable") }
  }

  private fun run(args: JSONObject): JSONObject {
    val name = (args.opt("name") as? String)?.trim().orEmpty()
    if (name.isEmpty()) return error("name is required, e.g. good night")
    val routine = HomeRoutines.find(saved(), name) ?: return JSONObject().put("ok", false).put("message",
        "There's no routine called $name. The routines are ${names()}. Ask whether to make one.")
    val requests = routine.steps.filterIsInstance<RoutineStep.Home>().map { it.request }
    val batch = if (requests.isEmpty()) HomeBatch(emptyList(), null) else home(requests)
    val parts = ArrayList<String>()
    batch.error?.let { parts += it.trimEnd('.') + "." }
    batch.steps.filter { it.status != HomeStepResult.Status.SKIPPED }.forEach { parts += it.reply }
    val quiet = RoutineStep.Quiet in routine.steps
    if (quiet) parts += "Do Not Disturb comes on next."
    if (RoutineStep.NextAlarm in routine.steps) nextAlarm()?.let { parts += "Your alarm rings $it." }
    if (parts.isEmpty()) parts += "Nothing to do: the house has none of its devices."
    val confirm = batch.steps.any { it.status == HomeStepResult.Status.CONFIRM }
    val out = JSONObject().put("ok", batch.error == null || quiet).put("routine", routine.name)
        .put("message", "${routine.name}: ${parts.joinToString(" ")}")
    if (confirm) out.put("needs_confirmation", true)
    if (quiet) out.put("also_call", JSONObject().put("tool", DND_TOOL).put("args", JSONObject().put("enabled", true)))
    return out
  }

  private fun list(): JSONObject {
    val all = HomeRoutines.all(saved())
    val json = JSONArray().apply {
      all.forEach { r -> put(JSONObject().put("name", r.name).put("steps", JSONArray(r.steps.map(::spoken)))) }
    }
    return JSONObject().put("ok", true).put("routines", json).put("message", "The routines are ${names()}.")
  }

  private fun create(args: JSONObject): JSONObject {
    val name = (args.opt("name") as? String)?.trim().orEmpty()
    if (name.isEmpty() || name.length > HomeRoutines.MAX_NAME) return error("name is required, ${HomeRoutines.MAX_NAME} characters at most")
    val raw = args.optJSONArray("steps") ?: return error("steps is required: the routine's steps in the user's words")
    if (raw.length() > HomeRoutines.MAX_STEPS) return error("a routine has ${HomeRoutines.MAX_STEPS} steps at most")
    val steps = (0 until raw.length()).mapNotNull { HomeRoutines.step(raw.optString(it)) }
    if (steps.isEmpty()) return error("steps must have at least one step")
    val mine = saved()
    val others = mine.filterNot { HomeRoutines.key(it.name) == HomeRoutines.key(name) }
    if (others.size >= HomeRoutines.MAX_ROUTINES) return error("the household keeps ${HomeRoutines.MAX_ROUTINES} routines at most")
    save(others + HomeRoutine(name, steps))
    return JSONObject().put("ok", true).put("message", "Saved the $name routine: ${HearthpaneLists.spoken(steps.map(::spoken))}. " +
        "Say \"run $name\" to start it.")
  }

  private fun delete(args: JSONObject): JSONObject {
    val name = (args.opt("name") as? String)?.trim().orEmpty()
    val mine = saved()
    val gone = mine.firstOrNull { HomeRoutines.key(it.name) == HomeRoutines.key(name) }
        ?: return JSONObject().put("ok", false).put("message", "There's no household routine called $name to delete.")
    save(mine - gone)
    val builtIn = HomeRoutines.find(emptyList(), gone.name)
    return JSONObject().put("ok", true).put("message",
        if (builtIn != null) "Deleted it. ${builtIn.name} is back to the built-in steps." else "Deleted the ${gone.name} routine.")
  }

  private fun names() = HearthpaneLists.spoken(HomeRoutines.all(saved()).map { it.name })

  private fun spoken(step: RoutineStep): String = when (step) {
    is RoutineStep.Home -> step.request
    RoutineStep.Quiet -> "Do Not Disturb on"
    RoutineStep.NextAlarm -> "say the next alarm"
  }

  private fun error(message: String) = JSONObject().put("error", message)

  companion object {
    const val NAME = "hearthpane.routines"
    /** Jarvis's own Do Not Disturb tool. */
    const val DND_TOOL = "portal.set_do_not_disturb"
  }
}
