/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class ApprovedEditorsFlowPolicyTest {
    @Test fun blankCredentialDraftPreservesStoredValue() {
        assertEquals("stored-secret", approvedEditorsCredential(" \n ", "stored-secret"))
        assertEquals("replacement", approvedEditorsCredential(" replacement ", "stored-secret"))
    }

    @Test fun haSearchMatchesIdsAndNamesAndLiftsBrowsingCap() {
        val entities = (1..35).map {
            ApprovedEditorsEntity("light.kitchen_$it", "Kitchen light $it")
        }
        val collapsed = approvedEditorsHaGroups(entities, "", emptySet()).single()
        assertFalse(collapsed.expanded)
        assertTrue(collapsed.shown.isEmpty())
        val browsing = approvedEditorsHaGroups(entities, "", setOf("light")).single()
        assertEquals(12, browsing.shown.size)
        assertEquals(23, browsing.hidden)
        val searching = approvedEditorsHaGroups(entities, " KITCHEN ", emptySet()).single()
        assertTrue(searching.expanded)
        assertEquals(35, searching.shown.size)
        assertEquals(0, searching.hidden)
        assertEquals("light.kitchen_35",
            approvedEditorsHaGroups(entities, "kitchen_35", emptySet()).single().shown.single().id)
        assertTrue(approvedEditorsHaGroups(entities, "garage", setOf("light")).isEmpty())
    }

    @Test fun haDomainsKeepExistingOrderAndUnknownDomains() {
        val entities = listOf(
            ApprovedEditorsEntity("sensor.temp", "Temperature"),
            ApprovedEditorsEntity("light.kitchen", "Kitchen"),
            ApprovedEditorsEntity("custom.entry", "Custom"),
            ApprovedEditorsEntity("scene.evening", "Evening"))
        assertEquals(listOf("light", "scene", "sensor", "custom"),
            approvedEditorsHaGroups(entities, "", emptySet()).map { it.domain })
    }

    @Test fun cancelledOrSupersededRequestCannotCommit() {
        val gate = ApprovedEditorsRequestGate()
        val cancelled = gate.begin()
        gate.cancel()
        assertFalse(gate.isCurrent(cancelled))
        val old = gate.begin()
        val current = gate.begin()
        assertFalse(gate.isCurrent(old))
        assertTrue(gate.isCurrent(current))
    }

    @Test fun discoveryTransferIsOneShotAndRejectsChangedConnection() {
        val entities = listOf(ApprovedEditorsEntity("light.kitchen", "Kitchen"))
        ApprovedEditorsHaTransfer.publish("http://ha.local:8123", "first-token", entities)
        assertNull(ApprovedEditorsHaTransfer.take("http://ha.local:8123", "changed-token"))
        assertNull(ApprovedEditorsHaTransfer.take("http://ha.local:8123", "first-token"))
        ApprovedEditorsHaTransfer.publish("http://ha.local:8123", "first-token", emptyList())
        assertEquals(emptyList<ApprovedEditorsEntity>(),
            ApprovedEditorsHaTransfer.take("http://ha.local:8123", "first-token"))
        assertNull(ApprovedEditorsHaTransfer.take("http://ha.local:8123", "first-token"))
    }

    @Test fun urlValidationAndOptionalCryptoSymbolKeepNativeFallback() {
        assertTrue(approvedEditorsHttpUrl("http://homeassistant.local:8123"))
        assertTrue(approvedEditorsHttpUrl("https://feeds.example.com/rss.xml"))
        assertFalse(approvedEditorsHttpUrl("https://"))
        assertFalse(approvedEditorsHttpUrl("file:///sdcard/feed.xml"))
        assertEquals("AVALANCHE-2", approvedEditorsCryptoSymbol(" avalanche-2 ", ""))
        assertEquals("AVAX", approvedEditorsCryptoSymbol("avalanche-2", " avax "))
    }
}
