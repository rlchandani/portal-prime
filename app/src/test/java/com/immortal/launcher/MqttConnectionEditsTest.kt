/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class MqttConnectionEditsTest {
    private val baseline = MqttConnectionValues("broker", 1883, "user", "secret", false)
    private fun values(v: MqttConnectionValues): Map<String, Any> = mapOf(
        "host" to v.host, "port" to v.port, "username" to v.username,
        "password" to v.password, "useTls" to v.useTls)
    private fun edits(draft: MqttConnectionValues) = baseline.editedValues(
        draft.host, draft.port, draft.username, draft.password, draft.useTls)

    @Test fun hostEditPreservesNewerRemotePortCredentialsAndTls() {
        val current = baseline.copy(port = 2883, username = "remote", password = "remote-secret", useTls = true)
        val changes = edits(baseline.copy(host = "new-broker"))
        assertEquals(mapOf("host" to "new-broker"), changes)
        assertEquals(values(current.copy(host = "new-broker")), values(current) + changes)
    }

    @Test fun portEditPreservesNewerRemoteHostCredentialsAndTls() {
        val current = baseline.copy(host = "remote-broker", username = "remote", password = "remote-secret", useTls = true)
        val changes = edits(baseline.copy(port = 2883))
        assertEquals(mapOf("port" to 2883), changes)
        assertEquals(values(current.copy(port = 2883)), values(current) + changes)
    }

    @Test fun usernameEditPreservesNewerRemoteHostPortPasswordAndTls() {
        val current = baseline.copy(host = "remote-broker", port = 2883, password = "remote-secret", useTls = true)
        val changes = edits(baseline.copy(username = "new-user"))
        assertEquals(mapOf("username" to "new-user"), changes)
        assertEquals(values(current.copy(username = "new-user")), values(current) + changes)
    }

    @Test fun passwordEditPreservesNewerRemoteHostPortUsernameAndTls() {
        val current = baseline.copy(host = "remote-broker", port = 2883, username = "remote", useTls = true)
        val changes = edits(baseline.copy(password = "new-secret"))
        assertEquals(mapOf("password" to "new-secret"), changes)
        assertEquals(values(current.copy(password = "new-secret")), values(current) + changes)
    }

    @Test fun unchangedApplyDoesNotSubmitAnyStaleFields() {
        assertTrue(edits(baseline).isEmpty())
    }

    @Test fun revertingDraftToLoadedValuesPreservesAllNewerRemoteFields() {
        val changed = baseline.copy(host = "draft", port = 2883, username = "draft", password = "draft", useTls = true)
        val reverted = changed.copy(host = baseline.host, port = baseline.port,
            username = baseline.username, password = baseline.password, useTls = baseline.useTls)
        val current = MqttConnectionValues("remote-broker", 8883, "remote", "remote-secret", true)
        val changes = edits(reverted)
        assertTrue(changes.isEmpty())
        assertEquals(values(current), values(current) + changes)
    }

    @Test fun intentionalTlsEnablePreservesNewerRemoteCustomPortAndCredentials() {
        val current = baseline.copy(host = "remote-broker", port = 2883, username = "remote", password = "remote-secret")
        val changes = edits(baseline.copy(useTls = true))
        assertEquals(mapOf("useTls" to true), changes)
        assertEquals(values(current.copy(useTls = true)), values(current) + changes)
    }

    @Test fun intentionalTlsDisableDoesNotInventAPortEdit() {
        val loaded = baseline.copy(port = 9883, useTls = true)
        assertEquals(mapOf("useTls" to false), loaded.editedValues(
            loaded.host, loaded.port, loaded.username, loaded.password, false))
    }

    @Test fun explicitTlsAndCustomPortEditsAreBothSubmitted() {
        assertEquals(mapOf("port" to 9883, "useTls" to true),
            edits(baseline.copy(port = 9883, useTls = true)))
    }

    @Test fun hostAndUsernameWhitespaceOnlyChangesAreNoOpsInEitherBaseline() {
        assertTrue(edits(baseline.copy(host = "  broker\t", username = " user ")).isEmpty())
        val loaded = baseline.copy(host = " broker ", username = " user ")
        assertTrue(loaded.editedValues("broker", loaded.port, "user", loaded.password, loaded.useTls).isEmpty())
    }

    @Test fun changedHostAndUsernameAreTrimmedButPasswordRemainsExact() {
        assertEquals(mapOf("host" to "new-broker", "username" to "new-user", "password" to " secret "),
            edits(baseline.copy(host = " new-broker\t", username = " new-user ", password = " secret ")))
    }

    @Test fun intentionalUsernameClearDoesNotSubmitAnUntouchedPassword() {
        assertEquals(mapOf("username" to ""), edits(baseline.copy(username = " \t")))
    }

    @Test fun changedBlankPasswordIsSubmittedForTheRegistryToHandle() {
        // Secret blank-ignore belongs to the registry, not this pure edit helper.
        assertEquals(mapOf("password" to ""), edits(baseline.copy(password = "")))
        val loaded = baseline.copy(password = "")
        assertTrue(loaded.editedValues(loaded.host, loaded.port, loaded.username, "", loaded.useTls).isEmpty())
    }

    @Test fun explicitEditsCanChangeAllFiveConnectionFields() {
        assertEquals(mapOf("host" to "new-broker", "port" to 9883, "username" to "new-user",
            "password" to "new-secret", "useTls" to true),
            edits(MqttConnectionValues("new-broker", 9883, "new-user", "new-secret", true)))
    }

    @Test fun readbackBaselineDoesNotResubmitEarlierEditsOrRemoteCompanions() {
        val saved = MqttConnectionValues("new-broker", 2883, "remote", "remote-secret", true)
        assertTrue(saved.editedValues(saved.host, saved.port, saved.username, saved.password, saved.useTls).isEmpty())
        assertEquals(mapOf("username" to "next-user"),
            saved.editedValues(saved.host, saved.port, "next-user", saved.password, saved.useTls))
    }
}
