/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ApprovedColorSchemeReuseTest {
    @Test fun repeatedDarkHostEntriesReuseOneTemplate() {
        val first = approvedColorScheme(true)
        repeat(40) { assertSame(first, approvedColorScheme(true)) }
    }

    @Test fun repeatedLightHostEntriesReuseOneTemplate() {
        val first = approvedColorScheme(false)
        repeat(40) { assertSame(first, approvedColorScheme(false)) }
    }

    @Test fun modeSwitchingKeepsBothPublishedTemplates() {
        val dark = approvedColorScheme(true)
        val light = approvedColorScheme(false)
        assertNotSame(dark, light)
        repeat(20) {
            assertSame(light, approvedColorScheme(false))
            assertSame(dark, approvedColorScheme(true))
        }
    }

    @Test fun concurrentHostsShareTheirCorrectModeTemplate() {
        val dark = approvedColorScheme(true)
        val light = approvedColorScheme(false)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val readers = (0 until 8).map { index ->
                pool.submit<ColorScheme> {
                    check(start.await(2, TimeUnit.SECONDS))
                    approvedColorScheme(index % 2 == 0)
                }
            }
            start.countDown()
            readers.forEachIndexed { index, task ->
                assertSame(if (index % 2 == 0) dark else light, task.get(2, TimeUnit.SECONDS))
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun everyPublicColourRoleMatchesThePreviouslyRenderedSchemes() {
        listOf(false, true).forEach { dark ->
            val expected = previousScheme(dark)
            val actual = approvedColorScheme(dark)
            val roles = colourRoles(expected)
            assertTrue("The comparison must cover the complete colour-role API", roles.size >= 29)
            assertEquals(roles, colourRoles(actual))
        }
    }

    @Test fun alternatingModesNeverChangesAlreadyPublishedColours() {
        val dark = approvedColorScheme(true)
        val light = approvedColorScheme(false)
        val beforeDark = colourRoles(dark)
        val beforeLight = colourRoles(light)
        repeat(40) { approvedColorScheme(it % 2 == 0) }
        assertEquals(beforeDark, colourRoles(dark))
        assertEquals(beforeLight, colourRoles(light))
    }

    // Color getters are JVM-mangled inline Long values. Compare ALL getters so
    // default Material roles remain covered when the library adds more roles.
    private fun colourRoles(scheme: ColorScheme): Map<String, Long> =
        scheme.javaClass.methods.filter {
            it.name.startsWith("get") && it.parameterCount == 0 &&
                it.returnType == Long::class.javaPrimitiveType
        }.associate { it.name to (it.invoke(scheme) as Long) }

    // Frozen pre-change colour contract, including each factory's default roles.
    private fun previousScheme(dark: Boolean): ColorScheme {
        val palette = if (dark) ApprovedDarkPalette else ApprovedLightPalette
        return if (dark) darkColorScheme(
            primary = palette.accent, onPrimary = palette.background,
            primaryContainer = palette.selected, onPrimaryContainer = palette.text,
            secondary = palette.secondary, background = palette.background,
            onBackground = palette.text, surface = palette.surface, onSurface = palette.text,
            surfaceVariant = palette.surface2, onSurfaceVariant = palette.secondary,
            surfaceContainer = palette.surface, outline = palette.secondary,
            outlineVariant = palette.divider, error = palette.error)
        else lightColorScheme(
            primary = palette.accent, onPrimary = palette.surface,
            primaryContainer = palette.selected, onPrimaryContainer = palette.text,
            secondary = palette.secondary, background = palette.background,
            onBackground = palette.text, surface = palette.surface, onSurface = palette.text,
            surfaceVariant = palette.surface2, onSurfaceVariant = palette.secondary,
            surfaceContainer = palette.surface, outline = palette.secondary,
            outlineVariant = palette.divider, error = palette.error)
    }
}
