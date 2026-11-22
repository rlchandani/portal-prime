/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings tripwires from the 2026-11-22 capture pass: a hand-made footer (`start = 4.dp`) sat 24 dp
 * left of every other footer on Home Screen, Music, MQTT, World clock and Start on boot.
 */
class SettingsSpacingSourceScanTest {
    private val root: File =
        listOf(File("src/main/java/com/immortal/launcher"), File("app/src/main/java/com/immortal/launcher")).first { it.exists() }
    private val settingsSources: List<Pair<String, String>> by lazy {
        root.walkTopDown().filter { it.isFile && it.extension == "kt" && "Settings" in it.name }
            .map { it.name to it.readText() }.sortedBy { it.first }.toList()
    }

    @Test fun settingsFootersUseFooterNoteNotAFourDpInset() {
        val bad = settingsSources.flatMap { (name, text) ->
            text.lines().mapIndexedNotNull { i, line -> if (AD_HOC_FOOTER.containsMatchIn(line)) "$name:${i + 1}" else null }
        }
        assertTrue("Use FooterNote (28 dp text inset) for text under a card:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    private companion object {
        val AD_HOC_FOOTER = Regex("""padding\(top = \d+\.dp, start = 4\.dp""")
    }
}
