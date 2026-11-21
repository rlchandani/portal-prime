/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

/** Typing a code on the Join keypad (mock `joinScr`), the QR text and the screens' words. */
class HomeLinkEntryTest {
  private fun typed(s: String) = s.fold(HomeLinkCodeEntry()) { e, c -> e.type(c) }

  @Test fun startsEmptyWithTheCursorOnTheFirstSlot() {
    val e = HomeLinkCodeEntry()
    assertEquals(0, e.cursor)
    assertEquals(List(10) { null }, e.slots)
    assertFalse(e.complete)
    assertNull(e.code)
  }

  @Test fun typingFillsSlotsInOrderAndNormalises() {
    val e = typed("mp7o")
    assertEquals(listOf('M', 'P', '7', '0'), e.slots.take(4))
    assertEquals(4, e.cursor)
  }

  @Test fun ignoresSymbolsNotInCodes() {
    assertEquals(typed("MP"), typed("M-P U!"))
  }

  @Test fun fullCodeStopsTakingSymbolsAndParses() {
    val e = typed("MPT7Q4K2PAZZ")
    assertTrue(e.complete)
    assertEquals(-1, e.cursor)
    assertEquals(HomeLinkCode.parse("MPT7Q4K2PA"), e.code)
  }

  @Test fun backspaceRemovesTheLastAndIsSafeWhenEmpty() {
    assertEquals(typed("MP"), typed("MP7").backspace())
    assertEquals(HomeLinkCodeEntry(), HomeLinkCodeEntry().backspace())
  }

  @Test fun typingNeverChangesTheOriginal() {
    val e = typed("MP")
    e.type('7'); e.backspace()
    assertEquals(typed("MP"), e)
  }

  @Test fun keypadPagesMatchTheMockAndCoverTheAlphabet() {
    assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", HomeLinkKeypad.LETTERS, "0", HomeLinkKeypad.DELETE),
        HomeLinkKeypad.keys(letters = false))
    val letters = HomeLinkKeypad.keys(letters = true)
    assertEquals(HomeLinkKeypad.DIGITS, letters[letters.size - 2])
    assertEquals(HomeLinkKeypad.DELETE, letters.last())
    val symbols = (HomeLinkKeypad.keys(false) + letters).filter { it.length == 1 && it != HomeLinkKeypad.DELETE }.joinToString("")
    assertEquals(HomeLinkCode.ALPHABET.toSet(), symbols.toSet())
  }

  @Test fun qrCarriesTheCodeForToolkit() {
    val code = HomeLinkCode.parse("MPT7Q4K2PA")!!
    assertEquals("hearthpane:link:MPT7Q4K2PA", HomeLinkCopy.qrText(code))
  }

  @Test fun failureWordsSayNothingWasKept() {
    for (f in HomeLinkFailure.values()) {
      val words = HomeLinkCopy.failure(f)
      assertTrue(words.title.isNotBlank())
      assertTrue(f.name, words.body.contains("new code"))
    }
    assertEquals("We stopped the pairing", HomeLinkCopy.failure(HomeLinkFailure.MISMATCH).title)
    assertEquals("This code ran out", HomeLinkCopy.failure(HomeLinkFailure.EXPIRED).title)
  }

  @Test fun verifyTitleNamesTheOtherHome() {
    assertEquals("Check it’s really Lakeview", HomeLinkCopy.verifyTitle("Lakeview"))
    assertEquals("Expires in 9:42", HomeLinkCopy.expires(HomeLinkInvite(HomeLinkCode.parse("MPT7Q4K2PA")!!, 0L), 18_000L))
  }
}
