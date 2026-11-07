/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Calendar
import java.util.TimeZone

/** User-facing Call everyone copy, taken from the approved prototype. Pure. */
internal object DinnerCopy {
  /** A Portal room reads "the Family Area"; a phone reads "Rohit’s Pixel". */
  fun senderLabel(name: String, isPhone: Boolean) = if (isPhone) name else "the $name"

  fun fromLine(message: DinnerMessage, senderName: String, senderIsPhone: Boolean, timeLabel: String): String =
      "${if (message.kind == DinnerKind.CUSTOM) "Typed by" else "From"} ${senderLabel(senderName, senderIsPhone)} at $timeLabel"

  fun replyLine(senderName: String, senderIsPhone: Boolean) = "Your reply goes to ${senderLabel(senderName, senderIsPhone)}"

  fun cardStatus(delivery: DinnerDelivery, kind: DinnerKind, policy: DinnerReceivePolicy, quietEndLabel: String): String = when {
    delivery.status == DinnerStatus.QUIET -> "Quiet hours until $quietEndLabel, so no chime or voice"
    kind == DinnerKind.PING -> "Chimed once"
    delivery.speak -> "Chimed and said it once"
    policy.speak && !policy.ttsAvailable -> "Chimed. Couldn’t speak it this time"
    else -> "Chimed. Voice is off on this Portal"
  }

  fun resultTitle(send: DinnerSend): String = when {
    send.pending -> "Sending “${send.message.text}”"
    send.allFailed -> "Couldn’t reach ${send.rows.joinToString(" or ") { it.roomName }}"
    else -> "Sent “${send.message.text}”"
  }

  fun replyRow(roomName: String, reply: DinnerReply) = "$roomName replied: ${reply.label}"

  /** The line under the sheet title: who will hear it, who is quiet, or that we're offline. */
  fun toLine(targetNames: List<String>, quietNames: List<String>, offline: Boolean): String = when {
    offline -> "This Portal is offline. Reconnect to send."
    quietNames.isNotEmpty() -> "${quietNames.joinToString(", ")} ${if (quietNames.size > 1) "are" else "is"} in quiet hours, so it shows without sound"
    targetNames.size > 1 -> "${targetNames.joinToString(" and ")} chime and say it once"
    else -> "${targetNames.firstOrNull().orEmpty()} chimes and says it once"
  }

  fun pingToast(send: DinnerSend): String {
    val names = send.rows.joinToString(" and ") { it.roomName }
    val bad = send.rows.filter { it.status.failed }
    return when {
      send.pending -> "Pinging $names…"
      bad.isNotEmpty() -> "Couldn’t reach ${bad.joinToString(" or ") { it.roomName }}"
      send.rows.any { it.status == DinnerStatus.QUIET } -> "Pinged $names, shown quietly"
      else -> "Pinged $names"
    }
  }

  /** Card title size in sp; long typed messages step down like the prototype's xl2/xl3. */
  fun titleSize(text: String): Float = when {
    text.length > 26 -> 64f
    text.length > 16 -> 80f
    else -> 100f
  }

  fun minuteLabel(minuteOfDay: Int, use24: Boolean): String {
    val h = (minuteOfDay / 60) % 24
    val m = minuteOfDay % 60
    return if (use24) "%02d:%02d".format(h, m) else "${if (h % 12 == 0) 12 else h % 12}:%02d ${if (h < 12) "AM" else "PM"}".format(m)
  }

  fun timeLabel(epochMs: Long, zone: TimeZone, use24: Boolean): String =
      minuteLabel(Calendar.getInstance(zone).apply { timeInMillis = epochMs }
          .let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }, use24)
}
