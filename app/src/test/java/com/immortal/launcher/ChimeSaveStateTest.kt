/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class ChimeSaveStateTest {
  @Test fun refreshRemainsBusyUntilTheReadCompletes() {
    val ready = ChimeSaveState().completeRead(0, 0)
    assertFalse(ready.saving)
    val refreshed = ready.beginRefresh()
    assertTrue(refreshed.saving)
    // Cancellation has no completion event, so this state stays busy on return.
    assertTrue(refreshed.beginRefresh().saving)
    assertFalse(refreshed.completeRead(0, 0).saving)
  }
  @Test fun failureRemainsVisibleAfterAResumeRead() {
    val edit = ChimeSaveState().beginEdit(0)
    val failed = edit.completeRead(edit.revision, 1)
    assertTrue(failed.failed)
    assertTrue(failed.beginRefresh().completeRead(edit.revision, 1).failed)
  }
  @Test fun successfulRetryClearsThePreviousFailure() {
    val edit = ChimeSaveState().beginEdit(0)
    val failed = edit.completeRead(edit.revision, 1)
    val retry = failed.beginEdit(1)
    assertFalse(retry.failed); assertTrue(retry.saving)
    val saved = retry.completeRead(retry.revision, 1)
    assertFalse(saved.failed); assertFalse(saved.saving)
  }
  @Test fun obsoleteCompletionsCannotClearTheNewerEdit() {
    val first = ChimeSaveState().beginEdit(0)
    val next = first.beginEdit(0)
    assertSame(next, next.completeRead(first.revision, 0))
    assertSame(next, next.readFailed(first.revision))
    assertTrue(next.saving)
  }
  @Test fun readFailureIsReportedUntilASuccessfulReadResolvesIt() {
    val state = ChimeSaveState().readFailed(0)
    assertTrue(state.failed); assertFalse(state.saving)
    val refreshed = state.beginRefresh().completeRead(0, 0)
    assertFalse(refreshed.saving); assertFalse(refreshed.failed)
  }
}
