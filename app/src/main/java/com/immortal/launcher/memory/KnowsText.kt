/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

/**
 * The "What Jarvis knows" screen's words, as the mock has them (mocks/jarvis-personalization-20261122): from slice 5
 * what is learned follows the person between Portals, and a Forget or Forget all reaches every one of them. Pure.
 */
internal object KnowsText {
  /** A toast: the line, and the small line under it. */
  data class Toast(val text: String, val sub: String?)

  fun banner(household: Boolean, firstName: String?): String = if (household) {
    "Learned automatically while nobody was recognised, from what's said to Jarvis and opened on these Portals. " +
        "Shared by everyone in the household. Dashed items are still being learned."
  } else {
    "Learned automatically from what ${firstName ?: "you"} says to Jarvis and opens on these Portals. Kept on your " +
        "Portals, only for you. Dashed items are still being learned."
  }

  const val EMPTY_TITLE = "Nothing here yet"
  const val EMPTY = "Jarvis learns as you talk and use your Portals. There's nothing to set up."

  fun forgot(title: String) = Toast("Forgot $title", "Removed from every Portal")

  fun kept(title: String) = Toast("Kept $title", null)

  fun forgotAll(household: Boolean, firstName: String?) = Toast(
      if (household) "Forgot everything about the household" else "Forgot everything about ${firstName ?: "you"}",
      "On all Portals · conversation history too")

  fun learning(on: Boolean) = if (on) {
    Toast("Learning on", "Jarvis learns from what you say and open")
  } else {
    Toast("Learning paused", "Jarvis keeps what it knows but learns nothing new")
  }
}
