/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/** The code being typed on Join a household (mock `joinScr`). Immutable: each key returns a new entry. */
@Immutable
internal data class HomeLinkCodeEntry(val symbols: String = "", val domain: HomeLinkDomain = HomeLinkDomain.HOME) {
  val complete: Boolean get() = symbols.length == HomeLinkCode.LENGTH

  /** The slot the next symbol goes in, or -1 when full. */
  val cursor: Int get() = if (complete) -1 else symbols.length

  val slots: List<Char?> get() = List(HomeLinkCode.LENGTH) { symbols.getOrNull(it) }

  /** The typed code once complete and of this screen's [domain] (a pinning code on the home screen stays null). */
  val code: HomeLinkCode? get() = if (complete) HomeLinkCode.parse(symbols, domain) else null

  /** Adds [c] (normalised like [HomeLinkCode.parse]); anything else, or a full code, is ignored. */
  fun type(c: Char): HomeLinkCodeEntry {
    if (complete) return this
    val n = when (val up = c.uppercaseChar()) { 'O' -> '0'; 'I', 'L' -> '1'; else -> up }
    return if (n in HomeLinkCode.ALPHABET) copy(symbols = symbols + n) else this
  }

  fun backspace(): HomeLinkCodeEntry = if (symbols.isEmpty()) this else copy(symbols = symbols.dropLast(1))
}

/** The Join keypad: digits like the mock (1–9, ABC, 0, ⌫), and a letters page. Pure. */
internal object HomeLinkKeypad {
  const val LETTERS = "ABC"
  const val DIGITS = "123"
  const val DELETE = "⌫"
  private const val DIGIT_COLUMNS = 3
  private const val LETTER_COLUMNS = 6

  fun keys(letters: Boolean): List<String> =
      if (letters) HomeLinkCode.ALPHABET.filter { it.isLetter() }.map { it.toString() } + DIGITS + DELETE
      else (1..9).map { it.toString() } + LETTERS + "0" + DELETE

  fun columns(letters: Boolean): Int = if (letters) LETTER_COLUMNS else DIGIT_COLUMNS
}

/** Words for the household-linking screens, from the approved mock. Pure. */
internal object HomeLinkCopy {
  /** A failed or stopped pairing (mock `mismatchScr`). */
  data class Failure(val title: String, val body: String)

  const val INVITE_TITLE = "Invite a household"
  const val INVITE_SUB = "For family in another home"
  const val INVITE_H2 = "Or type this code on their Portal"
  const val INVITE_HOW = "On their Portal: Calls › Households › Join with a code."
  const val INVITE_ONCE = "Works once"
  const val INVITE_READ = "Read it out on a phone call"
  const val INVITE_NOTE = "The code only introduces your Portals. Next, you both check the same four pictures, so nobody else can slip in between."
  const val INVITE_SCAN = "Scan with Portal Toolkit on their phone"
  const val NEW_CODE = "New code"
  const val CANCEL = "Cancel"
  const val JOIN_TITLE = "Join a household"
  const val JOIN_H2 = "Type the code from their Portal"
  const val JOIN_HOW = "Codes are 10 letters and numbers. Ask the person who sent it to read it out."
  const val JOIN = "Join"
  const val VERIFY_LEDE = "Both screens show the same four pictures if the connection is really between your two homes. Compare them on a phone call or in person."
  const val MATCH = "They match"
  const val NO_MATCH = "They don’t match"
  const val START_AGAIN = "Start again"
  const val CLOSE = "Close"
  const val DONE = "Done"
  const val NO_ANSWER = "Nothing answered that code. Check it with the person who read it out."
  private const val QR_PREFIX = "hearthpane:link:"

  /** What the invite QR holds: Portal Toolkit reads the code from it. */
  fun qrText(code: HomeLinkCode): String = QR_PREFIX + code.value

  fun expires(invite: HomeLinkInvite, now: Long): String = "Expires in " + invite.countdown(now)

  fun connectedTitle(theirName: String): String = "$theirName is connected"

  fun connectedBody(theirName: String): String =
      "$theirName can call you now. The keys live only on your Portals. No company sits in the middle."

  fun verifyTitle(theirName: String): String = "Check it’s really $theirName"

  fun failure(f: HomeLinkFailure): Failure = when (f) {
    HomeLinkFailure.MISMATCH -> Failure("We stopped the pairing",
        "The pictures didn’t match, so something may be in between your Portals. Nothing was shared and no keys were saved. Try again with a new code.")
    HomeLinkFailure.TAMPERED -> Failure("We stopped the pairing",
        "Something didn’t check out between your Portals. Nothing was shared and no keys were saved. Try again with a new code.")
    HomeLinkFailure.TOO_MANY_TRIES -> Failure("This code stopped working",
        "It was typed wrong three times, so we switched it off to keep guessers out. Nothing was shared. Make a new code and read it out again.")
    HomeLinkFailure.EXPIRED -> Failure("This code ran out",
        "Codes work for 10 minutes and only once. Nothing was shared. Make a new code and read it out again.")
  }
}
