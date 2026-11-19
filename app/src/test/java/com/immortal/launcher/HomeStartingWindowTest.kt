package com.immortal.launcher

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Android 10 leaks a whole starting window (ViewRootImpl, DecorView, package Context) inside system_server
 * every time a splash add fails with "already running, starting window not displayed": the failed
 * ViewRootImpl stays registered with system_server's AccessibilityManager. The system restarts Home on
 * every launcher death and dozens of times per install, each time with a dying ActivityRecord, so Family
 * reached 13,428 leaked ViewRootImpls (1.8 GB system_server). Home must never ask for a splash.
 */
class HomeStartingWindowTest {
  private val android = "http://schemas.android.com/apk/res/android"

  private fun mainFile(path: String): File =
      listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.exists() }

  private fun parse(path: String) =
      DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
          .newDocumentBuilder().parse(mainFile(path))

  private fun elements(doc: org.w3c.dom.Document, tag: String): List<Element> =
      doc.getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

  private fun activityTheme(name: String): String {
    val activity = elements(parse("AndroidManifest.xml"), "activity")
        .single { it.getAttributeNS(android, "name") == name }
    return activity.getAttributeNS(android, "theme").removePrefix("@style/")
  }

  /** Resolves an item through explicit parents and dotted implicit parents, like aapt. */
  private fun themeItem(style: String, item: String): String? {
    val styles = elements(parse("res/values/themes.xml"), "style").associateBy { it.getAttribute("name") }
    var current: String? = style
    while (current != null) {
      val node = styles[current] ?: return null
      elements(node.ownerDocument, "item").firstOrNull {
        it.parentNode === node && it.getAttribute("name") == item
      }?.let { return it.textContent.trim() }
      current = node.getAttribute("parent").takeIf { it.isNotEmpty() && !it.startsWith("android:") }
          ?: current.substringBeforeLast('.', "").takeIf { it.isNotEmpty() && it in styles }
    }
    return null
  }

  @Test fun homeNeverRequestsASplashStartingWindow() {
    val theme = activityTheme(".HomeActivity")
    assertEquals("true", themeItem(theme, "android:windowDisablePreview"))
  }

  @Test fun homeKeepsItsOpaqueBlackWindowAndSystemAnimations() {
    val theme = activityTheme(".HomeActivity")
    assertEquals("@android:color/black", themeItem(theme, "android:windowBackground"))
    assertEquals(null, themeItem(theme, "android:windowAnimationStyle"))
    assertEquals(null, themeItem(theme, "android:windowIsTranslucent"))
  }

  @Test fun themeResolverSeesTheExistingNavigationPolicy() {
    assertEquals("true", themeItem("Theme.SampleApp.Navigation", "android:windowDisablePreview"))
    assertEquals("true", themeItem("Theme.SampleApp.Settings", "android:windowDisablePreview"))
    assertEquals(null, themeItem("Theme.SampleApp", "android:windowDisablePreview"))
  }

  @Test fun homeStillOwnsTheHomeIntent() {
    val home = elements(parse("AndroidManifest.xml"), "activity")
        .single { it.getAttributeNS(android, "name") == ".HomeActivity" }
    val categories = elements(home.ownerDocument, "category")
        .filter { generateSequence(it.parentNode) { p -> p.parentNode }.any { p -> p === home } }
        .map { it.getAttributeNS(android, "name") }
    assertTrue("android.intent.category.HOME" in categories)
  }
}
