/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Locale

/**
 * The house basics as choices rather than typing: check-out times, "Good to know" chips, contact
 * chips and welcome samples, plus how each is stored. Ids are the wire format shared with Portal
 * Toolkit, so they never change. Pure JVM.
 */
internal object GuestBasics {
  data class Preset(val id: String, val label: String, val icon: String)
  data class Contact(val id: String, val label: String, val line: String?)

  /** "" is "No check-out"; any other value is canonical 24-hour "HH:mm". */
  val CHECKOUT_CHOICES = listOf("", "10:00", "11:00", "12:00")

  /** Icons are names from the approved icon catalog ([approvedIconResource]). */
  val PRESETS = listOf(
      Preset("shoes_off", "Shoes off inside", "home"),
      Preset("quiet_hours", "Quiet after 10 PM", "moon"),
      Preset("snacks", "Help yourself to snacks", "bag"),
      Preset("coffee", "Coffee is in the kitchen", "theme_day"),
      Preset("pets", "Pets in the house", "hand"),
      Preset("no_smoking", "No smoking", "info"),
      Preset("towels", "Towels in the bathroom", "bulb"),
      Preset("street_parking", "Park on the street", "globe"),
  )

  const val CONTACT_NONE = "none"
  val CONTACTS = listOf(
      Contact(CONTACT_NONE, "No contact line", null),
      Contact("call_portal", "Call us on the Portal", "Need anything? Call us on the Portal."),
      Contact("text", "Text us", "Need anything? Text us."),
      Contact("ask", "Ask for help", "Need anything? Just ask."),
  )

  const val DEFAULT_WELCOME = "Welcome! Make yourself at home."
  val WELCOME_SAMPLES = listOf(
      DEFAULT_WELCOME,
      "So glad you're here. Wi-Fi's below.",
      "Hi! Help yourself to anything in the kitchen.",
      "Welcome to our home — enjoy your stay.",
  )
  const val MAX_WELCOME = 80
  const val MAX_NOTES = 6
  /** What the "Add your own…" field accepts; migrated notes may be up to [GuestConfig.MAX_TEXT]. */
  const val MAX_NOTE_TYPED = 40
  const val MAX_CONTACT_NAME = 40
  private const val NOTE_SEPARATOR = '|'
  private val TIME = Regex("""^\s*(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?\s*$""", RegexOption.IGNORE_CASE)
  private val CANONICAL = Regex("""^([01]\d|2[0-3]):[0-5]\d$""")

  fun isValidCheckout(value: String): Boolean = value.isEmpty() || CANONICAL.matches(value)

  /** A loosely written time as "HH:mm", "" for blank, or null when it isn't a time ("11 on Sunday"). */
  fun parseCheckout(raw: String): String? {
    if (raw.isBlank()) return ""
    val m = TIME.matchEntire(raw) ?: return null
    val hour = m.groupValues[1].toInt()
    val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
    val meridiem = m.groupValues[3].lowercase().replace(".", "")
    val h24 = when (meridiem) {
      "" -> hour.takeIf { it in 0..23 }
      "am" -> hour.takeIf { it in 1..12 }?.let { it % 12 }
      else -> hour.takeIf { it in 1..12 }?.let { it % 12 + 12 }
    } ?: return null
    if (minute !in 0..59) return null
    return String.format(Locale.ROOT, "%02d:%02d", h24, minute)
  }

  fun checkoutLabel(value: String, use24: Boolean): String {
    if (!CANONICAL.matches(value)) return "No check-out"
    val (h, m) = value.split(':').map { it.toInt() }
    if (use24) return value
    val hour12 = if (h % 12 == 0) 12 else h % 12
    return String.format(Locale.ROOT, "%d:%02d %s", hour12, m, if (h < 12) "AM" else "PM")
  }

  /** Known ids only, once each, in catalog order. */
  fun decodePresets(stored: String): List<String> {
    val chosen = stored.split(',').map { it.trim() }.toSet()
    return PRESETS.map { it.id }.filter { it in chosen }
  }

  fun encodePresets(ids: Collection<String>): String = decodePresets(ids.joinToString(",")).joinToString(",")

  fun preset(id: String): Preset? = PRESETS.firstOrNull { it.id == id }

  /** One line, no separator, at most [MAX_NOTE_TYPED] characters. */
  fun cleanNote(raw: String): String =
      raw.filterNot { it.isISOControl() }.replace(NOTE_SEPARATOR, '/').trim().take(MAX_NOTE_TYPED)

  fun decodeNotes(stored: String): List<String> = stored.split(NOTE_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

  /** Blank and repeated notes dropped, separators replaced, capped at [MAX_NOTES]. */
  fun encodeNotes(notes: List<String>): String =
      notes.map { it.filterNot(Char::isISOControl).replace(NOTE_SEPARATOR, '/').trim().take(GuestConfig.MAX_TEXT) }
          .filter { it.isNotEmpty() }.distinct().take(MAX_NOTES).joinToString(NOTE_SEPARATOR.toString())

  fun isValidNotes(stored: String): Boolean {
    if (stored.isEmpty()) return true
    if (stored.any { it.isISOControl() }) return false
    val parts = stored.split(NOTE_SEPARATOR)
    return parts.size <= MAX_NOTES && parts.all { it.isNotBlank() && it.length <= GuestConfig.MAX_TEXT }
  }

  fun isValidContact(id: String): Boolean = CONTACTS.any { it.id == id }

  fun isValidContactName(name: String): Boolean = name.length <= MAX_CONTACT_NAME && name.none { it.isISOControl() }

  /** The card's contact sentence, or null when there's nothing to say. */
  fun contactLine(contact: String, contactName: String): String? {
    val parts = listOfNotNull(CONTACTS.firstOrNull { it.id == contact }?.line,
        contactName.trim().takeIf { it.isNotEmpty() }?.let { "Questions? Ask $it." })
    return parts.joinToString(" ").ifEmpty { null }
  }

  fun cleanWelcome(raw: String): String =
      raw.replace(Regex("[\\r\\n]+"), " ").filterNot { it.isISOControl() }.take(MAX_WELCOME)
}

/** The door code: a secret like the Wi-Fi password, entered on a number pad. */
internal object GuestDoorCode {
  const val MIN = 4
  const val MAX = 8

  fun isValid(code: String): Boolean = code.length in MIN..MAX && code.all { it in '0'..'9' }

  /** Number-pad input: ASCII digits only, at most [MAX]. */
  fun clean(raw: String): String = raw.filter { it in '0'..'9' }.take(MAX)

  /** What the secret store accepts: new codes, plus older free-text codes ("4821#") kept as they were. */
  fun isStorable(code: String): Boolean = code.isNotEmpty() && GuestConfig.isValidText(code)
}
