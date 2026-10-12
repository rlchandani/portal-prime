/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher.settings

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SettingsSchemaRequestTest {
    private val context = mock(Context::class.java)
    private data class Snapshot(val enabled: Boolean, val number: Int, val password: String = "test-secret")

    private fun domain(id: String, source: SettingsSnapshotSource<Snapshot>? = null,
        load: (Context) -> Snapshot = { source!!.load(it) }): SettingsDomain<Snapshot> = SettingsDomain(
        id, id, load = load,
        specs = listOf(
            BoolSpec("enabled", "Enabled", { it.enabled }, { _, _ -> }),
            IntSpec("number", "Number", { it.number }, { _, _ -> }, min = 0, max = 100,
                visible = { _, s -> s.enabled }),
            StringSpec("password", "Password", { it.password }, { _, _ -> }, secret = true),
        ), defaults = { Snapshot(false, 7, "") }, sections = mapOf("number" to "Details"),
        schemaSnapshotSource = source,
    )

    @Test fun sharedPairLoadsOncePerCompleteResponseAcrossTwentyVisits() {
        var loads = 0
        val source = SettingsSnapshotSource { _: Context -> loads++; Snapshot(true, 3) }
        val domains = listOf(domain("screensaver", source), domain("calendar", source))
        repeat(20) {
            val result = SettingsRegistry.schemaJson(context, domains).getJSONArray("domains")
            assertEquals(2, result.length())
            assertEquals(3, result.getJSONObject(0).getJSONArray("controls").getJSONObject(1).getInt("value"))
            assertEquals(3, result.getJSONObject(1).getJSONArray("controls").getJSONObject(1).getInt("value"))
        }
        assertEquals("40 duplicate loads must become 20 request loads", 20, loads)
    }

    @Test fun nextRequestReadsChangesAndReevaluatesParentGatesAndSecretMasking() {
        var current = Snapshot(true, 3)
        val source = SettingsSnapshotSource { _: Context -> current }
        val domains = listOf(domain("screensaver", source), domain("calendar", source))
        val first = SettingsRegistry.schemaJson(context, domains).getJSONArray("domains")
        assertEquals(3, first.getJSONObject(0).getJSONArray("controls").length())
        current = Snapshot(false, 8, "")
        val second = SettingsRegistry.schemaJson(context, domains).getJSONArray("domains")
        for (i in 0 until second.length()) {
            val controls = second.getJSONObject(i).getJSONArray("controls")
            assertEquals(2, controls.length())
            assertFalse(controls.getJSONObject(0).getBoolean("value"))
            assertEquals("", controls.getJSONObject(1).getString("value"))
            assertFalse(controls.getJSONObject(1).getBoolean("hasValue"))
        }
        assertEquals(3, first.getJSONObject(0).getJSONArray("controls").length())
        val password = first.getJSONObject(0).getJSONArray("controls").getJSONObject(2)
        assertEquals("", password.getString("value"))
        assertTrue(password.getBoolean("hasValue"))
    }

    @Test fun directDomainSchemasStillLoadOnEveryCall() {
        var loads = 0
        val source = SettingsSnapshotSource { _: Context -> Snapshot(true, ++loads) }
        val domain = domain("screensaver", source)
        repeat(3) { domain.schemaJson(context) }
        assertEquals(3, loads)
    }

    @Test fun unrelatedDomainsStillUseTheirOwnLoaders() {
        var a = 0; var b = 0
        val domains = listOf(domain("a", load = { a++; Snapshot(true, 1) }),
            domain("b", load = { b++; Snapshot(true, 2) }))
        val response = SettingsRegistry.schemaJson(context, domains).getJSONArray("domains")
        assertEquals(1, a); assertEquals(1, b)
        assertEquals(1, response.getJSONObject(0).getJSONArray("controls").getJSONObject(1).getInt("value"))
        assertEquals(2, response.getJSONObject(1).getJSONArray("controls").getJSONObject(1).getInt("value"))
    }

    @Test fun failedReadCanRetryAndOnlySuccessfulSnapshotIsShared() {
        var loads = 0
        val source = SettingsSnapshotSource { _: Context ->
            loads++; if (loads == 1) throw IllegalStateException("read failed")
            Snapshot(true, loads)
        }
        val request = SettingsSchemaRequest(context)
        try { request.load(source); fail("Expected initial failure") } catch (_: IllegalStateException) { }
        val value = request.load(source)
        assertSame(value, request.load(source))
        assertEquals(2, loads)
    }

    @Test fun nullableSnapshotIsCachedWithoutTreatingNullAsAbsent() {
        var loads = 0
        val source = SettingsSnapshotSource<Snapshot?> { loads++; null }
        val request = SettingsSchemaRequest(context)
        assertNull(request.load(source)); assertNull(request.load(source))
        assertEquals(1, loads)
    }

    @Test fun nullableDomainSnapshotDoesNotFallBackToAnExtraLoad() {
        var loads = 0
        val source = SettingsSnapshotSource<Snapshot?> { loads++; null }
        val first = SettingsDomain("first", "First", load = source::load,
            specs = listOf(DerivedSpec<Snapshot?>("missing", { it == null }, render = true)),
            schemaSnapshotSource = source)
        val second = SettingsDomain("second", "Second", load = source::load,
            specs = first.specs, schemaSnapshotSource = source)
        val schemas = SettingsRegistry.schemaJson(context, listOf(first, second)).getJSONArray("domains")
        assertTrue(schemas.getJSONObject(0).getJSONArray("controls").getJSONObject(0).getBoolean("value"))
        assertTrue(schemas.getJSONObject(1).getJSONArray("controls").getJSONObject(0).getBoolean("value"))
        assertEquals(1, loads)
    }

    @Test fun equalValuesFromDifferentTypedSourcesDoNotAlias() {
        var a = 0; var b = 0
        val sourceA = SettingsSnapshotSource { _: Context -> a++; Snapshot(true, 1) }
        val sourceB = SettingsSnapshotSource { _: Context -> b++; Snapshot(true, 1) }
        val request = SettingsSchemaRequest(context)
        val first = request.load(sourceA); val second = request.load(sourceB)
        assertNotSame(first, second); assertSame(first, request.load(sourceA)); assertSame(second, request.load(sourceB))
        assertEquals(1, a); assertEquals(1, b)
    }

    @Test fun schemaFieldsMatchIndependentDomainSchemasIncludingDefaultsSectionsAndSecrets() {
        val source = SettingsSnapshotSource { _: Context -> Snapshot(true, 3) }
        val domains = listOf(domain("screensaver", source), domain("calendar", source))
        val response = SettingsRegistry.schemaJson(context, domains).getJSONArray("domains")
        domains.forEachIndexed { i, d ->
            val expected = d.schemaJson(context)
            assertEquals(expected.toString(), response.getJSONObject(i).toString())
            val n = response.getJSONObject(i).getJSONArray("controls").getJSONObject(1)
            assertEquals(7, n.getInt("default")); assertEquals("Details", n.getString("section"))
        }
    }

    @Test fun concurrentRequestsNeverShareSnapshotsOrContext() {
        val loads = AtomicInteger()
        val ready = CountDownLatch(8); val release = CountDownLatch(1)
        val source = SettingsSnapshotSource { _: Context -> loads.incrementAndGet(); Snapshot(true, 3) }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (0 until 8).map {
                pool.submit<Snapshot> {
                    val request = SettingsSchemaRequest(mock(Context::class.java))
                    ready.countDown(); check(release.await(3, TimeUnit.SECONDS))
                    val value = request.load(source); assertSame(value, request.load(source)); value
                }
            }
            assertTrue(ready.await(3, TimeUnit.SECONDS)); release.countDown()
            val values = results.map { it.get(3, TimeUnit.SECONDS) }
            assertEquals(8, loads.get())
            assertEquals(8, java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Snapshot, Boolean>()).apply { addAll(values) }.size)
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
