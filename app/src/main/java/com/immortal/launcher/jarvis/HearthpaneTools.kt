/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import org.json.JSONException
import org.json.JSONObject

/**
 * The tools Hearthpane gives Jarvis through Jarvis's tool-provider contract: `hearthpane.screen_context` (what the
 * screen in front shows, as text — never pixels), `hearthpane.highlight` (pulse one of that screen's controls) and
 * `hearthpane.home_control` (do something in the house through the Control Center, see
 * [com.immortal.launcher.CcJarvisHome]).
 * Pure apart from its two seams, so every rule is JVM-tested: arguments are untrusted (from the model, via Jarvis),
 * and a highlight must name a target of the screen that is actually in front.
 */
internal class HearthpaneTools(
    private val screen: () -> HearthpaneScreens.Entry?,
    private val highlight: (target: String, hint: String?) -> Unit,
    /** `hearthpane.home_control` with its raw (untrusted) arguments; validated and policed by the Control Center. */
    private val homeControl: (argsJson: String?) -> JSONObject = { JSONObject().put("error", "home control unavailable") },
    /** Settings rows anywhere that match a few words ("dark mode"), best first. */
    private val search: (query: String) -> List<ScreenContext.Match> = { emptyList() },
) {
  fun invoke(name: String?, argsJson: String?): JSONObject = when (name) {
    SCREEN_CONTEXT -> screenContext(args(argsJson)?.optString("query")?.trim()?.take(MAX_QUERY).orEmpty())
    HIGHLIGHT -> highlight(args(argsJson))
    HOME_CONTROL -> runCatching { homeControl(argsJson) }.getOrElse { error("home control failed") }
    else -> error("unknown tool: ${name.orEmpty().take(MAX_NAME)}")
  }

  private fun screenContext(query: String): JSONObject {
    val entry = screen()
    val out = if (entry == null) ScreenContext.none()
        else runCatching { entry.context() }.getOrElse { return error("screen context unavailable") }
    if (query.isEmpty()) return out
    val matches = runCatching { search(query) }.getOrDefault(emptyList())
    return ScreenContext.withMatches(out, query, matches)
  }

  private fun highlight(args: JSONObject?): JSONObject {
    val target = args?.optString("target")?.trim().orEmpty()
    val hint = args?.optString("hint")?.trim()?.take(MAX_HINT)?.ifEmpty { null }
    val entry = screen()
    return when {
      target.isEmpty() || target.length > MAX_TARGET -> error("target is required")
      entry == null -> error("no Hearthpane screen is in front")
      screenOf(target) != entry.id() -> error("that control isn't on the screen in front")
      else -> {
        highlight(target, hint)
        JSONObject().put("ok", true).put("target", target)
      }
    }
  }

  private fun args(json: String?): JSONObject? = try {
    JSONObject(json ?: "{}")
  } catch (_: JSONException) {
    null
  }

  private fun error(message: String) = JSONObject().put("error", message)

  companion object {
    const val SCREEN_CONTEXT = "hearthpane.screen_context"
    const val HIGHLIGHT = "hearthpane.highlight"
    const val HOME_CONTROL = "hearthpane.home_control"
    private const val MAX_TARGET = 160
    private const val MAX_HINT = 60
    private const val MAX_NAME = 60
    private const val MAX_QUERY = 60

    /** The screen a target id belongs to, or null for an id no screen hands out. */
    fun screenOf(target: String): String? = when {
      target.startsWith(ScreenContext.ROOM_TARGET) || target.startsWith(ScreenContext.ENTITY_TARGET) ->
        HearthpaneScreens.CONTROL_CENTER
      target.startsWith(ScreenContext.SETTINGS_TARGET) -> HearthpaneScreens.SETTINGS
      target in ScreenContext.MUSIC_TARGETS -> HearthpaneScreens.MUSIC
      target.startsWith(ScreenContext.CALL_ROOM_TARGET) || target.startsWith(ScreenContext.CALL_VIDEO_TARGET) ->
        HearthpaneScreens.CALLS
      else -> null
    }
  }
}
