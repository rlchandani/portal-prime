/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.google.firebase.components.ComponentRegistrar
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ML Kit discovers its components by reflectively calling each registrar's no-arg constructor. The
 * library's consumer rule (`-keep class * implements ComponentRegistrar`) keeps the class but, under
 * R8 full mode, not that constructor. Hearthpane 1.295's performance build stripped all three, so
 * MlKitContext had no face component and every `FaceDetection.getClient` threw a
 * NullPointerException (SlideshowFaces analysis_failed, FaceFocusStore warm-up failed) on generated
 * scenes and real photos alike.
 */
class MlKitR8RulesTest {
  private val rules: List<String> by lazy {
    listOf(File("portal-r8-rules.pro"), File("app/portal-r8-rules.pro")).first { it.exists() }
        .readLines().map { it.trim() }.filter { it.startsWith("-keep") }
  }

  /** The registrars ML Kit 16.1.7 / common 18.11 list in their manifests' discovery service. */
  private val registrars = listOf(
      "com.google.mlkit.common.internal.CommonComponentRegistrar",
      "com.google.mlkit.vision.common.internal.VisionCommonRegistrar",
      "com.google.mlkit.vision.face.internal.FaceRegistrar")

  @Test fun theRulesKeepEveryComponentRegistrarsNoArgConstructor() {
    val rule = Regex("""^-keep\s+class\s+\*\s+implements\s+com\.google\.firebase\.components\.ComponentRegistrar\s*\{\s*<init>\(\);\s*}$""")
    assertTrue("no registrar constructor keep rule in portal-r8-rules.pro: $rules", rules.any(rule::matches))
  }

  @Test fun everyMlKitRegistrarIsCoveredByThatRule() {
    // The rule targets the interface, so each registrar must still implement it and expose the
    // public no-arg constructor ComponentDiscovery calls.
    val uncovered = registrars.filterNot { name ->
      val type = Class.forName(name, false, javaClass.classLoader)
      ComponentRegistrar::class.java.isAssignableFrom(type) &&
          runCatching { type.getConstructor() }.isSuccess
    }
    assertEquals(emptyList<String>(), uncovered)
  }
}
