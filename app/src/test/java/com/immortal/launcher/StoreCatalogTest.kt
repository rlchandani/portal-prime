/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Catalog JSON parsing + APK-URL resolution logic (no device needed). */
class StoreCatalogTest {

  private val sample =
      """
      {"categories":[
        {"name":"Media","apps":[
          {"name":"VLC","packageName":"org.videolan.vlc","source":"fdroid","fdroidId":"org.videolan.vlc","versionCode":13070106,"description":"Plays media."},
          {"name":"Immortal","packageName":"com.immortal.launcher","source":"url","apkUrl":"https://x/immortal.apk","description":"the app"}
        ]},
        {"name":"Tools","apps":[
          {"name":"Aurora","packageName":"com.aurora.store"}
        ]}
      ]}
      """.trimIndent()

  @Test
  fun parse_flattensCategoriesAndKeepsCategoryName() {
    val apps = StoreCatalog.parse(sample)
    assertEquals(3, apps.size)
    assertEquals("Media", apps[0].category)
    assertEquals("Media", apps[1].category)
    assertEquals("Tools", apps[2].category)
  }

  @Test
  fun parse_readsFieldsAndAppliesDefaults() {
    val apps = StoreCatalog.parse(sample)

    val vlc = apps.first { it.packageName == "org.videolan.vlc" }
    assertEquals("fdroid", vlc.source)
    assertEquals(13070106L, vlc.versionCode)

    // A minimal entry: source defaults to "fdroid"; optional fields are null/blank.
    val aurora = apps.first { it.packageName == "com.aurora.store" }
    assertEquals("fdroid", aurora.source)
    assertNull(aurora.apkUrl)
    assertNull(aurora.fdroidId)
    assertNull(aurora.versionCode)
    assertEquals("", aurora.description)
  }

  @Test
  fun resolveApkUrl_usesDirectUrlForUrlSource() {
    val app =
        CatalogApp(
            name = "Immortal",
            packageName = "com.immortal.launcher",
            source = "url",
            fdroidId = null,
            apkUrl = "https://x/immortal.apk",
            versionCode = null,
            description = "",
            category = "Tools",
        )
    assertEquals("https://x/immortal.apk", StoreCatalog.resolveApkUrl(app))
  }

  @Test
  fun resolveApkUrl_buildsFdroidUrlFromPinnedVersion() {
    // A pinned versionCode avoids any network call (the arm64 build of a multi-ABI app).
    val app =
        CatalogApp(
            name = "VLC",
            packageName = "org.videolan.vlc",
            source = "fdroid",
            fdroidId = "org.videolan.vlc",
            apkUrl = null,
            versionCode = 13070106L,
            description = "",
            category = "Media",
        )
    assertEquals(
        "https://f-droid.org/repo/org.videolan.vlc_13070106.apk",
        StoreCatalog.resolveApkUrl(app),
    )
  }
}
