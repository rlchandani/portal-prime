/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.ActiveCall
import com.immortal.launcher.CallingCopy

/**
 * The one intent every Hearthpane Jarvis entry point sends: Floating Jarvis's card over the screen in front
 * (`com.portal.assistant/.floating.FloatActivity`, Jarvis 2.6+). Its extras mirror Jarvis's `FloatRequest` keys
 * as literals (the two apps share no code). Pure, so the contract is JVM-tested here.
 */
internal object JarvisFloatLaunch {
  const val PACKAGE = "com.portal.assistant"
  const val FLOAT_ACTIVITY = "$PACKAGE.floating.FloatActivity"

  const val EXTRA_MODE = "com.portal.assistant.float.extra.MODE"
  const val EXTRA_QUIET = "com.portal.assistant.float.extra.QUIET"
  const val EXTRA_SCREEN = "com.portal.assistant.float.extra.SCREEN"
  const val EXTRA_CALL = "com.portal.assistant.float.extra.CALL"
  /** Jarvis 2.7.4+: who the active call is with, so the mic-busy card reads "Call with Living Area · …". */
  const val EXTRA_CALL_WITH = "com.portal.assistant.float.extra.CALL_WITH"
  /** A room or person name is short; anything longer is cut so the card's one line stays one line. */
  const val CALL_WITH_MAX_CHARS = 60
  private val WHITESPACE = Regex("\\s+")
  const val EXTRA_TAP_UPTIME = "com.portal.assistant.float.extra.TAP_UPTIME"
  const val EXTRA_THEME = "com.portal.assistant.float.extra.THEME"
  const val MODE_TEXT = "text"
  /** A wake word opened the card: Jarvis shows "Heard “Hey Jarvis”" (an older Jarvis treats it as a voice tap). */
  const val MODE_WAKE = "wake"

  /** What the card is told about the moment it opens over. */
  data class Request(
      val screen: String?,
      val quiet: Boolean,
      val callActive: Boolean,
      val light: Boolean,
      val tapUptimeMs: Long,
      val typing: Boolean = false,
      /** Opened by "Hey Jarvis" heard on a Hearthpane screen ([tapUptimeMs] is then when it was heard). */
      val wake: Boolean = false,
      /** The room or person of the active call ("Living Area", "Kitchen and Den"); only sent with [callActive]. */
      val callWith: String? = null,
  )

  /** Typed extras, ready for `Intent.putExtra`. Only set flags are sent; the screen id only when it's valid. */
  fun extras(request: Request): Map<String, Any> = buildMap {
    put(EXTRA_TAP_UPTIME, request.tapUptimeMs)
    if (request.quiet) put(EXTRA_QUIET, true)
    if (request.callActive) {
      put(EXTRA_CALL, true)
      callWithLine(request.callWith)?.let { put(EXTRA_CALL_WITH, it) }
    }
    if (request.light) put(EXTRA_THEME, "light")
    if (request.typing) put(EXTRA_MODE, MODE_TEXT) else if (request.wake) put(EXTRA_MODE, MODE_WAKE)
    request.screen?.takeIf(HearthpaneScreens::isValidId)?.let { put(EXTRA_SCREEN, it) }
  }

  /** The active call's other side: "Living Area", or "Kitchen and Den" in a group call; null with no call. */
  fun callWithOf(call: ActiveCall?): String? = call?.let { c -> CallingCopy.names(c.members.map { it.name }) }

  /** One trimmed line of at most [CALL_WITH_MAX_CHARS] (ellipsised), or null when there is no name to say. */
  fun callWithLine(names: String?): String? {
    val line = names?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (line.length <= CALL_WITH_MAX_CHARS) line else line.take(CALL_WITH_MAX_CHARS - 1).trimEnd() + "…"
  }

  /** Which way a tap opens Jarvis: the floating card when this Jarvis has it, else the full app (older Jarvis). */
  enum class Route { FLOAT, FULL_APP }

  fun route(floatInstalled: Boolean): Route = if (floatInstalled) Route.FLOAT else Route.FULL_APP
}
