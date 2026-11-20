/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cold-start R8 rules in `portal-r8-rules.pro` keep Android-10-missing API code out of the classes Home's first frame
 * loads. A rule that names a class that no longer exists silently does nothing, so every named class must still resolve.
 */
class StartupVerificationRulesTest {
  private val rules: List<String> by lazy {
    val file = listOf(File("portal-r8-rules.pro"), File("app/portal-r8-rules.pro")).first { it.exists() }
    val lines = file.readLines()
    val start = lines.indexOfFirst { it.startsWith("# Cold start: keep code that names classes missing on Android 10") }
    assertTrue("cold-start section missing", start >= 0)
    lines.drop(start).dropWhile { it.startsWith("#") }.takeWhile { it.startsWith("-keep") }
  }

  /** `androidx.foo.**` resolves through one class known to live in that package. */
  private val packageWitness = mapOf(
      "androidx.compose.ui.scrollcapture" to "androidx.compose.ui.scrollcapture.ComposeScrollCaptureCallback")

  private fun className(rule: String) = Regex("""\bclass\s+(\S+)""").find(rule)!!.groupValues[1]

  private fun resolves(name: String) =
      runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess

  @Test fun everyNamedClassStillExists() {
    val missing = rules.map(::className).filterNot { name ->
      if (name.endsWith(".**")) packageWitness[name.removeSuffix(".**")]?.let(::resolves) == true else resolves(name)
    }
    assertEquals("renamed or removed classes in the cold-start R8 rules: $missing", emptyList<String>(), missing)
  }

  @Test fun theRulesCoverEachSoftFailureFoundOnTheColdPath() {
    // Cold-boot trace + device boot classpath (Hearthpane 1.287, Android 10): each class names an API absent on 29.
    val expected = listOf(
        "androidx.media3.exoplayer.mediacodec.LoudnessCodecController",
        "androidx.media3.exoplayer.trackselection.DefaultTrackSelector\$SpatializerWrapperV32",
        "androidx.compose.ui.scrollcapture.**",
        "androidx.compose.ui.text.android.AndroidLayoutApi34",
        "androidx.compose.ui.text.android.selection.Api34SegmentFinder")
    assertEquals(expected, rules.map(::className))
  }

  @Test fun noRuleAllowsOptimization() {
    // allowoptimization lets R8 merge the class into another one again, which is what these rules prevent.
    assertFalse(rules.any { "allowoptimization" in it })
  }
}
