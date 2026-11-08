package com.immortal.launcher

import com.immortal.launcher.settings.SettingsDomains
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The visible product name is Hearthpane; the legacy identifiers stay put so updates keep working. */
class BrandingTest {
  private fun mainFile(path: String): File =
      listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.exists() }

  @Test fun appLabelResolvesToHearthpane() {
    val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(mainFile("res/values/strings.xml"))
    val strings = doc.getElementsByTagName("string")
    val appName = (0 until strings.length).map { strings.item(it) }
        .first { it.attributes.getNamedItem("name").nodeValue == "app_name" }.textContent
    assertEquals("Hearthpane", appName)
    assertEquals(appName, Brand.NAME)
  }

  @Test fun homeWordmarkIsLowercaseHearthpane() {
    assertEquals("Hearthpane", Brand.WORDMARK)
  }

  @Test fun manifestLabelsCarryNoLegacyBrand() {
    val labels = Regex("""android:label="([^"]*)"""").findAll(mainFile("AndroidManifest.xml").readText())
        .map { it.groupValues[1] }.toList()
    assertTrue(labels.isNotEmpty())
    labels.forEach { assertFalse("label still says Immortal: $it", it.contains("Immortal")) }
  }

  @Test fun settingsDomainShowsHearthpaneButKeepsItsPersistedId() {
    assertEquals("Hearthpane", SettingsDomains.immortal.title)
    assertEquals("immortal", SettingsDomains.immortal.id)
  }

  @Test fun classicFaceShowsHearthpaneButKeepsItsPersistedId() {
    val entry = FaceCatalog.entries.first { it.id == "immortal-classic" }
    assertEquals("Hearthpane", entry.name)
  }
}
