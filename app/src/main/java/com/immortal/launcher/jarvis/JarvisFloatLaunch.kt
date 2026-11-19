/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

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
  const val EXTRA_TAP_UPTIME = "com.portal.assistant.float.extra.TAP_UPTIME"
  const val EXTRA_THEME = "com.portal.assistant.float.extra.THEME"

  /** What the card is told about the moment it opens over. */
  data class Request(
      val screen: String?,
      val quiet: Boolean,
      val callActive: Boolean,
      val light: Boolean,
      val tapUptimeMs: Long,
      val typing: Boolean = false,
  )

  /** Typed extras, ready for `Intent.putExtra`. Only set flags are sent; the screen id only when it's valid. */
  fun extras(request: Request): Map<String, Any> = buildMap {
    put(EXTRA_TAP_UPTIME, request.tapUptimeMs)
    if (request.quiet) put(EXTRA_QUIET, true)
    if (request.callActive) put(EXTRA_CALL, true)
    if (request.light) put(EXTRA_THEME, "light")
    if (request.typing) put(EXTRA_MODE, "text")
    request.screen?.takeIf(HearthpaneScreens::isValidId)?.let { put(EXTRA_SCREEN, it) }
  }

  /** Which way a tap opens Jarvis: the floating card when this Jarvis has it, else the full app (older Jarvis). */
  enum class Route { FLOAT, FULL_APP }

  fun route(floatInstalled: Boolean): Route = if (floatInstalled) Route.FLOAT else Route.FULL_APP
}
