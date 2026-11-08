/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Calendar
import java.util.TimeZone

/**
 * Call everyone (dinner call): the pure model. Six fixed presets, a typed message of up to
 * [DinnerMessage.MAX_CUSTOM] characters, or a bare Ping. Runtime lives in [DinnerCall]; the wire
 * format in [DinnerWire]; sender-side results in [DinnerBook].
 */
internal enum class DinnerKind(val wire: String) {
  PRESET("p"), CUSTOM("c"), PING("g");

  companion object {
    fun fromWire(code: String): DinnerKind? = values().firstOrNull { it.wire == code }
  }
}

/** [icon] names a drawable alias resolved by [DinnerIcons]. */
internal data class DinnerPreset(val id: String, val icon: String, val text: String)

internal object DinnerPresets {
  val all = listOf(
      DinnerPreset("dinner", "plate", "Dinner’s ready"),
      DinnerPreset("down", "stairs", "Come downstairs"),
      DinnerPreset("leave", "door", "Time to leave"),
      DinnerPreset("bed", "moon", "Bedtime"),
      DinnerPreset("lights", "lamp", "Lights out in 5 minutes"),
      DinnerPreset("home10", "homeby", "Home in 10 minutes"),
  )

  fun byId(id: String?): DinnerPreset? = all.firstOrNull { it.id == id }
}

/** One call. Build through the factories, which validate; the constructor is for the wire decoder. */
internal data class DinnerMessage(val kind: DinnerKind, val presetId: String? = null, val customText: String? = null) {
  val text: String
    get() = when (kind) {
      DinnerKind.PING -> "Ping"
      DinnerKind.CUSTOM -> customText.orEmpty()
      DinnerKind.PRESET -> DinnerPresets.byId(presetId)?.text.orEmpty()
    }

  val icon: String
    get() = when (kind) {
      DinnerKind.PING -> "bell"
      DinnerKind.CUSTOM -> "keyboard"
      DinnerKind.PRESET -> DinnerPresets.byId(presetId)?.icon ?: "megaphone"
    }

  companion object {
    const val MAX_CUSTOM = 40

    fun preset(id: String?): DinnerMessage? = DinnerPresets.byId(id)?.let { DinnerMessage(DinnerKind.PRESET, presetId = it.id) }
    fun ping() = DinnerMessage(DinnerKind.PING)
    fun custom(raw: String?): DinnerMessage? = cleanText(raw, MAX_CUSTOM)?.let { DinnerMessage(DinnerKind.CUSTOM, customText = it) }

    /**
     * Single-line, printable text: line breaks become spaces, control and format characters
     * (including bidi overrides) are dropped, whitespace collapses. Null when blank or over
     * [max] code points: an over-long phone/peer message is rejected rather than truncated.
     */
    fun cleanText(raw: String?, max: Int): String? {
      if (raw == null) return null
      val out = StringBuilder()
      var i = 0
      while (i < raw.length) {
        val cp = raw.codePointAt(i)
        i += Character.charCount(cp)
        when {
          cp == '\n'.code || cp == '\r'.code || cp == '\t'.code || Character.isSpaceChar(cp) -> out.append(' ')
          Character.isISOControl(cp) || Character.getType(cp).let { it == Character.FORMAT.toInt() || it == Character.SURROGATE.toInt() } -> Unit
          else -> out.appendCodePoint(cp)
        }
      }
      val text = out.toString().replace(Regex(" {2,}"), " ").trim()
      return text.takeIf { it.isNotEmpty() && it.codePointCount(0, it.length) <= max }
    }
  }
}

/** Per-room delivery result. [wire] travels in dinner_ack; [rowText] is the sender's row copy. */
internal enum class DinnerStatus(val wire: String, val rowText: String, val failed: Boolean = false, val retryable: Boolean = false) {
  SENDING("sending", "Sending…"),
  SPOKEN("spoken", "Chimed and said it"),
  CHIMED("chimed", "Chimed"),
  CHIMED_ONLY("chimedonly", "Chimed, shown without voice"),
  QUIET("quiet", "Shown quietly, quiet hours"),
  BLOCKED("blocked", "Calls are turned off there", failed = true),
  NO_ANSWER("noanswer", "No answer, not delivered", failed = true, retryable = true),
  NOT_SET_UP("notsetup", "Not sent. Set up Call everyone from Calder Pocket", failed = true, retryable = true);

  companion object {
    private val reportable = setOf(SPOKEN, CHIMED, CHIMED_ONLY, QUIET, BLOCKED)

    /** Statuses a receiving Portal may report; anything else from a peer is ignored. */
    fun fromWire(code: String?): DinnerStatus? = reportable.firstOrNull { it.wire == code }
  }
}

internal enum class DinnerReply(val wire: String, val label: String) {
  COMING("coming", "Coming"), ON_MY_WAY("onmyway", "On my way");

  companion object {
    fun fromWire(code: String?): DinnerReply? = values().firstOrNull { it.wire == code }
  }
}

/** What the receiving Portal knows when a call lands. */
internal data class DinnerReceivePolicy(val receive: Boolean, val speak: Boolean, val quietNow: Boolean, val ttsAvailable: Boolean)

internal data class DinnerDelivery(val status: DinnerStatus, val showCard: Boolean, val chime: Boolean, val speak: Boolean)

/** The receiving room always decides (prototype open question 3, recommended option). */
internal object DinnerDecision {
  fun decide(kind: DinnerKind, policy: DinnerReceivePolicy): DinnerDelivery = when {
    !policy.receive -> DinnerDelivery(DinnerStatus.BLOCKED, showCard = false, chime = false, speak = false)
    policy.quietNow -> DinnerDelivery(DinnerStatus.QUIET, showCard = true, chime = false, speak = false)
    kind == DinnerKind.PING -> DinnerDelivery(DinnerStatus.CHIMED, showCard = true, chime = true, speak = false)
    policy.speak && policy.ttsAvailable -> DinnerDelivery(DinnerStatus.SPOKEN, showCard = true, chime = true, speak = true)
    else -> DinnerDelivery(DinnerStatus.CHIMED_ONLY, showCard = true, chime = true, speak = false)
  }
}

/** Quiet hours are the chime's (shared with the hourly chime), evaluated on an injected clock. */
internal object DinnerQuiet {
  fun minuteOfDay(nowMs: Long, zone: TimeZone): Int =
      Calendar.getInstance(zone).apply { timeInMillis = nowMs }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

  fun isQuiet(settings: ChimeConfig.Settings, nowMs: Long, zone: TimeZone): Boolean =
      settings.quietHoursOn && ChimeConfig.inQuietWindow(minuteOfDay(nowMs, zone), settings.quietStartMin, settings.quietEndMin)
}
