/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

import org.json.JSONObject

/**
 * With "Hey Jarvis on Hearthpane screens" on, Jarvis's own background wake (the companion `wakeEnabled`) has to be
 * off. Measured on Family: it re-arms whenever a Hearthpane screen resumes, runs Vosk at ~65% of a core, and while
 * both capture, Android hands *it* the audio and Hearthpane near-silence, so neither wakes reliably. Hearthpane's
 * detector covers every Hearthpane screen, and Jarvis's foreground wake (Jarvis open) is unaffected. Pure.
 */
internal object JarvisBackgroundWake {
  /** The `configure` body to send for Jarvis's [status] (the companion `status` projection), or null for none. */
  fun configurationFor(hearthpaneWakeOn: Boolean, status: JSONObject): JSONObject? =
      if (hearthpaneWakeOn && status.optBoolean("supported") && status.optBoolean("wakeEnabled")) {
        JSONObject().put("wakeEnabled", false)
      } else {
        null
      }
}
