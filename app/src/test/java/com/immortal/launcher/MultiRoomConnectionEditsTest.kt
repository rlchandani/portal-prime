/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class MultiRoomConnectionEditsTest {
    private val baseline = MultiRoomConnectionValues("server", 8095, "user", "secret")
    private fun values(v: MultiRoomConnectionValues): Map<String, Any> = mapOf(
        "snapcastHost" to v.host, "maPort" to v.port,
        "maUsername" to v.username, "maPassword" to v.password)
    private fun edits(draft: MultiRoomConnectionValues) = baseline.editedValues(
        draft.host, draft.port, draft.username, draft.password)

    @Test fun hostEditPreservesNewerCompanionPortAndCredentials() {
        val current = baseline.copy(port = 9095, username = "remote", password = "remote-secret")
        val saved = values(current) + edits(baseline.copy(host = "new-server"))
        assertEquals(values(current.copy(host = "new-server")), saved)
    }
    @Test fun portEditPreservesNewerCompanionHostAndCredentials() {
        val current = baseline.copy(host = "remote-server", username = "remote", password = "remote-secret")
        val saved = values(current) + edits(baseline.copy(port = 9095))
        assertEquals(values(current.copy(port = 9095)), saved)
    }
    @Test fun unchangedApplyDoesNotSubmitAnyStaleFields() {
        assertTrue(edits(baseline).isEmpty())
    }
    @Test fun returningADraftToItsLoadedValueLeavesTheRemoteValueUntouched() {
        val changed = baseline.copy(host = "draft")
        val reverted = changed.copy(host = baseline.host)
        assertTrue(edits(reverted).isEmpty())
    }
    @Test fun hostAndUsernameUseTheExistingWhitespaceNormalization() {
        assertTrue(edits(baseline.copy(host = "  server  ", username = " user ")).isEmpty())
    }
    @Test fun intentionalUsernameClearDoesNotSubmitAnUntouchedPassword() {
        assertEquals(mapOf("maUsername" to ""), edits(baseline.copy(username = "")))
    }
    @Test fun passwordWhitespaceRemainsSignificant() {
        assertEquals(mapOf("maPassword" to " secret "), edits(baseline.copy(password = " secret ")))
    }
    @Test fun explicitEditsCanChangeAllConnectionFieldsWithoutOtherSettings() {
        val draft = MultiRoomConnectionValues("new-server", 1, "new-user", "new-secret")
        assertEquals(values(draft), edits(draft))
    }
    @Test fun readbackBaselinePreventsTheNextApplyFromResubmittingEarlierEdits() {
        val saved = baseline.copy(host = "new-server", port = 9095, password = "remote-secret")
        assertEquals(mapOf("maUsername" to "new-user"),
            saved.editedValues(saved.host, saved.port, "new-user", saved.password))
    }
}
