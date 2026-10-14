/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM recognition and source boundaries: synthetic tokens, no fetch calls or devices. */
class RemoteAlbumUrlValidationTest {

  private val shares =
      listOf(
          "https://www.icloud.com/sharedalbum/#TEST_TOKEN" to "iCloud Shared Album",
          "https://photos.icloud.com/shared/album/TEST_TOKEN" to "iCloud Shared Album",
          "https://photos.app.goo.gl/TEST_TOKEN" to "Google Photos",
          "https://photos.google.com/share/TEST_TOKEN" to "Google Photos",
          "https://nas-host:5001/photos/mo/sharing/TEST_TOKEN" to "Synology Photos",
      )

  private fun assertRecognized(url: String, provider: String) {
    assertTrue("Supported: $url", RemoteAlbum.isSupported(url))
    assertEquals("Provider: $url", provider, RemoteAlbum.providerName(url))
    assertEquals("iCloud: $url", provider == "iCloud Shared Album", RemoteAlbum.isIcloud(url))
    assertEquals("Google: $url", provider == "Google Photos", RemoteAlbum.isGooglePhotos(url))
    assertEquals("Synology: $url", provider == "Synology Photos", RemoteAlbum.isSynologyPhotos(url))
  }

  private fun assertRejected(url: String) {
    assertFalse("Unsupported: $url", RemoteAlbum.isSupported(url))
    assertFalse("Not iCloud: $url", RemoteAlbum.isIcloud(url))
    assertFalse("Not Google: $url", RemoteAlbum.isGooglePhotos(url))
    assertFalse("Not Synology: $url", RemoteAlbum.isSynologyPhotos(url))
    assertEquals("Fallback provider: $url", "Shared album", RemoteAlbum.providerName(url))
  }

  @Test
  fun legacyIcloud_acceptsAllThreeRoutes() {
    listOf("sharedalbum", "photo-stream", "photostream").forEach { route ->
      assertRecognized("https://www.icloud.com/$route/#TEST_TOKEN", "iCloud Shared Album")
    }
  }

  @Test
  fun currentIcloud_acceptsPathToken() {
    assertRecognized("https://photos.icloud.com/shared/album/TEST_TOKEN", "iCloud Shared Album")
  }

  @Test
  fun googleShort_acceptsPathToken() {
    assertRecognized("https://photos.app.goo.gl/TEST_TOKEN", "Google Photos")
  }

  @Test
  fun googleLong_acceptsShareToken() {
    assertRecognized("https://photos.google.com/share/TEST_TOKEN", "Google Photos")
  }

  @Test
  fun synology_acceptsHttpHttpsLanHostsAndNormalPorts() {
    listOf(
            "http://192.168.1.42:5000/photos/mo/sharing/TEST_TOKEN",
            "https://192.168.1.42:5001/photos/mo/sharing/TEST_TOKEN",
            "http://nas-host/photos/mo/sharing/TEST_TOKEN",
            "https://nas-host:8443/photos/mo/sharing/TEST_TOKEN",
            "http://nas.local:8080/mo/sharing/TEST_TOKEN",
            "https://nas.example.test/mo/sharing/TEST_TOKEN",
            "http://[2001:db8::42]:5000/photos/mo/sharing/TEST_TOKEN",
        )
        .forEach { assertRecognized(it, "Synology Photos") }
  }

  @Test
  fun synology_preservesInternationalizedHostnameLinks() {
    val url = "https://bücher.example:5001/photos/mo/sharing/TEST_TOKEN?key=TEST_KEY#view"
    assertRecognized(url, "Synology Photos")
    assertEquals("https://xn--bcher-kva.example:5001/photos/mo/sharing/webapi/entry.cgi",
        RemoteAlbum.synologyShare(url)?.apiUrl)
    assertEquals("TEST_TOKEN", RemoteAlbum.synologyShare(url)?.passphrase)
    assertRejected("https://user@bücher.example:5001/photos/mo/sharing/TEST_TOKEN")
    assertRejected("https://bücher.example:65536/photos/mo/sharing/TEST_TOKEN")
  }

  @Test
  fun schemeAndHostname_areCaseInsensitive() {
    listOf(
            "HTTPS://WWW.ICLOUD.COM/sharedalbum/#TEST_TOKEN" to "iCloud Shared Album",
            "hTtPs://Photos.iCloud.Com/shared/album/TEST_TOKEN" to "iCloud Shared Album",
            "HTTPS://PHOTOS.APP.GOO.GL/TEST_TOKEN" to "Google Photos",
            "hTtPs://Photos.Google.Com/share/TEST_TOKEN" to "Google Photos",
            "HTTP://NAS-HOST:5000/photos/mo/sharing/TEST_TOKEN" to "Synology Photos",
        )
        .forEach { (url, provider) -> assertRecognized(url, provider) }
  }

  @Test
  fun surroundingWhitespace_isTrimmedByEveryPublicRecognizer() {
    shares.forEach { (url, provider) -> assertRecognized(" \t\n$url\r\n ", provider) }
  }

  @Test
  fun queryFragmentAndTrailingPaths_doNotHideValidPathTokens() {
    shares.filterNot { (url, _) -> url.contains('#') }.forEach { (url, provider) ->
      listOf("/", "/photo/SECOND_SEGMENT", "?key=TEST_KEY", "#photos", "/photo?l=en#view")
          .forEach { suffix -> assertRecognized(url + suffix, provider) }
    }
    assertRecognized(
        "https://www.icloud.com/sharedalbum/?l=en#TEST_TOKEN?view=photos",
        "iCloud Shared Album")
  }

  @Test
  fun synology_acceptsReverseProxyPrefixesAndTrailingSegments() {
    listOf("/photos", "/portal/photos", "/proxy/nas/photos").forEach { prefix ->
      assertRecognized(
          "https://nas-host:5001$prefix/mo/sharing/TEST_TOKEN/photo?sort=time#view",
          "Synology Photos")
    }
  }

  @Test
  fun relativeAndBareHostLinks_areRejected() {
    shares.forEach { (url, _) ->
      assertRejected(url.removePrefix("https://"))
      assertRejected(url.removePrefix("https:"))
    }
    listOf("/shared/album/TEST_TOKEN", "/photos/mo/sharing/TEST_TOKEN", "", " \t\n")
        .forEach { assertRejected(it) }
  }

  @Test
  fun nonHttpSchemes_areRejected() {
    shares.forEach { (url, _) ->
      listOf("ftp", "file").forEach { scheme ->
        assertRejected(url.replaceFirst("https", scheme))
      }
    }
  }

  @Test
  fun missingHost_isRejected() {
    listOf(
            "https:///www.icloud.com/sharedalbum/#TEST_TOKEN",
            "https:///photos.icloud.com/shared/album/TEST_TOKEN",
            "https:///photos.app.goo.gl/TEST_TOKEN",
            "https:///photos.google.com/share/TEST_TOKEN",
            "https:///photos/mo/sharing/TEST_TOKEN",
        )
        .forEach { assertRejected(it) }
  }

  @Test
  fun userInfo_isRejectedEvenOnGenuineProviderHosts() {
    shares.forEach { (url, _) ->
      listOf("user@", "user:password@", "@").forEach { userInfo ->
        assertRejected(url.replaceFirst("://", "://$userInfo"))
      }
    }
  }

  @Test
  fun invalidPorts_areRejected() {
    shares.forEach { (url, _) ->
      val withoutPort = url.replace(":5001", "")
      val authorityEnd = withoutPort.indexOf('/', withoutPort.indexOf("://") + 3)
      listOf("-1", "0", "65536", "999999999999", "abc", "1.5").forEach { port ->
        assertRejected(
            withoutPort.substring(0, authorityEnd) + ":$port" +
                withoutPort.substring(authorityEnd))
      }
    }
  }

  @Test
  fun malformedPercentEscapes_areRejectedInAllUrlComponents() {
    shares.forEach { (url, _) ->
      listOf("%", "%2", "%GG").forEach { escape ->
        assertRejected(url.replace("TEST_TOKEN", "TEST_TOKEN$escape"))
        assertRejected(url.replace("://", "://$escape"))
        assertRejected(url.substringBefore('#') + "?bad=$escape#TEST_TOKEN")
        assertRejected(url.substringBefore('#') + "#TEST_TOKEN$escape")
      }
    }
  }

  @Test
  fun embeddedWhitespace_isRejectedWithoutTrimmingTokens() {
    shares.forEach { (url, _) ->
      listOf(" ", "\t", "\n", "\r").forEach { whitespace ->
        assertRejected(url.replace("TEST_TOKEN", "TEST${whitespace}TOKEN"))
        assertRejected(url.replace("://", "://$whitespace"))
        assertRejected(url.substringBefore('#') + "?key=TEST${whitespace}KEY#TEST_TOKEN")
      }
    }
  }

  @Test
  fun providerHostnameLookalikes_areRejected() {
    listOf(
            "https://www.icloud.com.example.test/sharedalbum/#TEST_TOKEN",
            "https://evilicloud.com/sharedalbum/#TEST_TOKEN",
            "https://photos.icloud.com.example.test/shared/album/TEST_TOKEN",
            "https://evilicloud.com/shared/album/TEST_TOKEN",
            "https://photos.app.goo.gl.example.test/TEST_TOKEN",
            "https://evilphotos.app.goo.gl/TEST_TOKEN",
            "https://photos.google.com.example.test/share/TEST_TOKEN",
            "https://evilphotos.google.com/share/TEST_TOKEN",
        )
        .forEach { assertRejected(it) }
  }

  @Test
  fun providerTextInPathQueryOrFragment_doesNotSelectProvider() {
    shares.filterNot { (_, provider) -> provider == "Synology Photos" }.forEach { (url, _) ->
      assertRejected("https://example.test/redirect/$url")
      assertRejected("https://example.test/?next=$url")
      assertRejected("https://example.test/#$url")
    }
    assertRejected("https://nas-host/?next=/photos/mo/sharing/TEST_TOKEN")
    assertRejected("https://nas-host/#/photos/mo/sharing/TEST_TOKEN")
  }

  @Test
  fun currentIcloudAndGoogle_requirePathTokens() {
    listOf(
            "https://photos.icloud.com/",
            "https://photos.icloud.com/shared/album",
            "https://photos.icloud.com/shared/album/",
            "https://photos.app.goo.gl",
            "https://photos.app.goo.gl/",
            "https://photos.google.com/",
            "https://photos.google.com/share",
            "https://photos.google.com/share/",
        )
        .forEach { url ->
          listOf("", "?token=TEST_TOKEN", "#TEST_TOKEN").forEach { assertRejected(url + it) }
        }
  }

  @Test
  fun legacyIcloud_requiresNonblankFragmentToken() {
    listOf("sharedalbum", "photo-stream", "photostream").forEach { route ->
      listOf("", "#", "#/", "#?l=en", "# \t").forEach { suffix ->
        assertRejected("https://www.icloud.com/$route/$suffix")
      }
      assertRejected("https://www.icloud.com/$route/?token=TEST_TOKEN")
    }
  }

  @Test
  fun synology_requiresNonblankHostAndFirstTokenSegment() {
    listOf(
            "https:///photos/mo/sharing/TEST_TOKEN",
            "http:///photos/mo/sharing/TEST_TOKEN",
            "https://:5001/photos/mo/sharing/TEST_TOKEN",
            "https://nas-host:5001/photos/mo/sharing",
            "https://nas-host:5001/photos/mo/sharing/",
            "https://nas-host:5001/photos/mo/sharing/?token=TEST_TOKEN",
            "https://nas-host:5001/photos/mo/sharing/#TEST_TOKEN",
            "https://nas-host:5001/photos/mo/sharing//TEST_TOKEN",
            "https://nas-host:5001/photos/mo/sharing/ \t",
        )
        .forEach { assertRejected(it) }
  }


  @Test
  fun prepare_invalidAlbumRejectsWholeBodyIncludingValidInterval() {
    val stored =
        ScreensaverConfig.Settings(
            source = ScreensaverConfig.SOURCE_URL,
            albumUrl = "https://photos.app.goo.gl/OLD_TEST_TOKEN",
            intervalSec = 45,
        )
    listOf(
            "",
            " \t",
            "https://example.test/album/TEST_TOKEN",
            "https://www.icloud.com.example.test/sharedalbum/#TEST_TOKEN",
            "https://photos.google.com/share/",
            "http://nas-host:5000/photos/mo/sharing/",
        )
        .forEach { url ->
          val input = JSONObject().put("albumUrl", url).put("intervalSec", 60)
          val before = input.toString()
          val prepared = RemoteCompanionSources.prepare(input, stored)
          assertEquals(url, "invalid_album_link", prepared.error)
          assertNull("Rejected body must not apply intervalSec: $url", prepared.body)
          assertEquals("Input must remain unchanged: $url", before, input.toString())
        }
  }

  @Test
  fun prepare_wrongAlbumTypesReturnInvalidSourceField() {
    val stored = ScreensaverConfig.Settings()
    listOf<Any>(
            JSONObject.NULL,
            true,
            42,
            1.5,
            JSONObject().put("url", "https://photos.app.goo.gl/TEST_TOKEN"),
            JSONArray().put("https://photos.app.goo.gl/TEST_TOKEN"),
        )
        .forEach { value ->
          val input = JSONObject().put("albumUrl", value).put("intervalSec", 60)
          val before = input.toString()
          val prepared = RemoteCompanionSources.prepare(input, stored)
          assertEquals("Type: $value", "invalid_source_field", prepared.error)
          assertNull("Wrong type must reject the whole body: $value", prepared.body)
          assertEquals(before, input.toString())
        }
  }

  @Test
  fun prepare_validAlbumPreservesRawBodyAndOtherFields() {
    shares.forEach { (url, _) ->
      val raw = " \t$url\r\n "
      val input = JSONObject().put("albumUrl", raw).put("intervalSec", 60)
      val before = input.toString()
      val prepared = RemoteCompanionSources.prepare(input, ScreensaverConfig.Settings())
      assertNull("Valid album: $url", prepared.error)
      val body = prepared.body!!
      assertEquals("Raw album must be preserved: $url", raw, body.getString("albumUrl"))
      assertEquals(60, body.getInt("intervalSec"))
      assertEquals("No fields added or removed: $url", input.length(), body.length())
      assertEquals("Input must remain unchanged: $url", before, input.toString())
    }
  }

  @Test
  fun fleetApply_invalidAlbumKeepsSavedSettingsWithoutWrites() {
    val oldUrl = "https://photos.app.goo.gl/OLD_TEST_TOKEN"
    val prefs =
        ScreensaverTestPrefs(
            mapOf("source" to ScreensaverConfig.SOURCE_URL, "album_url" to oldUrl))
    val before = ScreensaverConfig.load(prefs.context)
    listOf(
            "",
            " \t",
            "not a url",
            "https://example.test/album/TEST_TOKEN",
            "https://www.icloud.com.example.test/sharedalbum/#TEST_TOKEN",
            "ftp://photos.app.goo.gl/TEST_TOKEN",
            "https://photos.google.com/share/",
            "https:///photos/mo/sharing/TEST_TOKEN",
            "http://nas-host:65536/photos/mo/sharing/TEST_TOKEN",
        )
        .forEach { url ->
          val applied = FleetScreensaver.apply(prefs.context, JSONObject().put("albumUrl", url))
          assertFalse("Rejected album must not be reported applied: $url", "albumUrl" in applied)
          assertTrue("No other keys were supplied: $url", applied.isEmpty())
          assertEquals("Saved settings must remain unchanged: $url", before, ScreensaverConfig.load(prefs.context))
          assertEquals(oldUrl, prefs.getString("album_url", null))
          assertEquals(ScreensaverConfig.SOURCE_URL, prefs.getString("source", null))
          assertEquals("Rejected album must perform zero writes: $url", 0, prefs.writeCount)
        }
  }

  @Test
  fun fleetApply_validAlbumUsesAtomicSetterTrimsAndPreservesUrlFormat() {
    val validUrls =
        shares.map { it.first } +
            listOf(
                "HTTPS://Photos.iCloud.Com/shared/album/TEST_TOKEN/photo?l=en#view",
                "http://NAS-HOST:5000/proxy/photos/mo/sharing/TEST_TOKEN/photo?key=TEST%2FKEY#view",
                "https://bücher.example:5001/photos/mo/sharing/TEST_TOKEN",
                "https://photos.google.com/share/TEST_TOKEN/photo?key=TEST_KEY#view",
            )
    validUrls.forEach { url ->
      val prefs =
          ScreensaverTestPrefs(
              mapOf(
                  "source" to ScreensaverConfig.SOURCE_DEFAULT,
                  "album_url" to "https://photos.app.goo.gl/OLD_TEST_TOKEN",
              ))
      val before = ScreensaverConfig.load(prefs.context)
      val raw = " \t$url\r\n "
      val applied = FleetScreensaver.apply(prefs.context, JSONObject().put("albumUrl", raw))
      assertEquals("Applied key: $url", listOf("albumUrl"), applied)
      assertEquals("Only outer whitespace is removed: $url", url, prefs.getString("album_url", null))
      assertEquals(ScreensaverConfig.SOURCE_URL, prefs.getString("source", null))
      assertEquals(
          "Source and album are updated together: $url",
          before.copy(source = ScreensaverConfig.SOURCE_URL, albumUrl = url),
          ScreensaverConfig.load(prefs.context))
      assertEquals("Atomic album setter performs one write: $url", 1, prefs.writeCount)
    }
  }
}
