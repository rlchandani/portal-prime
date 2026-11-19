/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeBackPolicyTest {
  private fun resolve(music: Boolean = false, home: Boolean = true, draft: Boolean = false, edit: Boolean = false) =
      HomeBackPolicy.resolve(musicOpen = music, onHomeTab = home, hasDraft = draft, editMode = edit)

  @Test fun backOnBareHomeTab_isConsumed_neverLeavesLauncher() {
    assertEquals(HomeBackAction.CONSUME, resolve())
  }

  @Test fun musicOverlay_closesFirst() {
    assertEquals(HomeBackAction.CLOSE_MUSIC, resolve(music = true, home = false, draft = true, edit = true))
  }

  @Test fun otherTab_returnsToHomeTab() {
    assertEquals(HomeBackAction.GO_HOME_TAB, resolve(home = false, draft = true, edit = true))
  }

  @Test fun addDraft_clearsBeforeEditMode() {
    assertEquals(HomeBackAction.CLEAR_DRAFT, resolve(draft = true, edit = true))
  }

  @Test fun editMode_exitsEditing() {
    assertEquals(HomeBackAction.EXIT_EDIT, resolve(edit = true))
  }

  @Test fun everyCombination_handlesBack() {
    for (bits in 0 until 16) {
      val action = resolve(music = bits and 1 != 0, home = bits and 2 != 0, draft = bits and 4 != 0, edit = bits and 8 != 0)
      val bare = bits == 2
      assertEquals("bits=$bits", bare, action == HomeBackAction.CONSUME)
    }
  }
}
