/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Mock-fidelity W-1: People & Recognition wears the mock's people glyph, its switch the face glyph. */
class WhosThereSettingsIconTest {
  @Test fun peopleAndRecognitionUsesThePeopleGlyph() {
    val page = requireNotNull(SettingsTree.page(SettingsPageIds.PEOPLE))
    assertEquals("users", page.icon)
    assertEquals(R.drawable.approved_icon_users, approvedIconResource(page.icon))
    assertNotEquals("not the film strip", R.drawable.approved_icon_film, approvedIconResource(page.icon))
  }

  @Test fun theRecogniseSwitchUsesTheFaceGlyph() {
    assertEquals(R.drawable.approved_icon_face, approvedIconResource("face"))
    assertEquals(R.drawable.approved_icon_users, approvedIconResource("People"))
  }
}
