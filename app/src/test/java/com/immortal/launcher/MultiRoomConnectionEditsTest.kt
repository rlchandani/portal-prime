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
    private val baseline = MultiRoomConnectionValues("server", 8095, "user", "secret",
        "test-api-token", "snapcast:raw-player")
    private fun values(v: MultiRoomConnectionValues): Map<String, Any> = mapOf(
        "snapcastHost" to v.host, "maPort" to v.port,
        "maUsername" to v.username, "maPassword" to v.password,
        "maApiToken" to v.apiToken, "maPlayerId" to v.playerId)
    private fun edits(draft: MultiRoomConnectionValues) = baseline.editedValues(
        draft.host, draft.port, draft.username, draft.password, draft.apiToken, draft.playerId)

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
        val draft = MultiRoomConnectionValues("new-server", 1, "new-user", "new-secret",
            "new-test-token", "snapcast:new-player")
        assertEquals(values(draft), edits(draft))
    }
    @Test fun blankSecretDraftsDoNotSubmitClearsOrStaleSecrets() {
        assertEquals(mapOf("snapcastHost" to "new-server"),
            edits(baseline.copy(host = "new-server", password = "", apiToken = "")))
        assertTrue(edits(baseline.copy(password = "   ", apiToken = "\t")).isEmpty())
    }
    @Test fun tokenEditPreservesNewerRemotePlayerAndLegacyCredentials() {
        val current = baseline.copy(playerId = "snapcast:remote-player", username = "remote",
            password = "remote-secret")
        val saved = values(current) + edits(baseline.copy(apiToken = "new-test-token"))
        assertEquals(values(current.copy(apiToken = "new-test-token")), saved)
    }
    @Test fun rawPlayerIdIsNotNormalizedAndCanBeExplicitlyCleared() {
        assertEquals(mapOf("maPlayerId" to "snapcast:Case-Sensitive/Raw_ID"),
            edits(baseline.copy(playerId = "snapcast:Case-Sensitive/Raw_ID")))
        assertEquals(mapOf("maPlayerId" to ""), edits(baseline.copy(playerId = "")))
    }
    @Test fun readbackBaselinePreventsTheNextApplyFromResubmittingEarlierEdits() {
        val saved = baseline.copy(host = "new-server", port = 9095, password = "remote-secret")
        assertEquals(mapOf("maUsername" to "new-user"),
            saved.editedValues(saved.host, saved.port, "new-user", saved.password))
    }
}
