/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.smbj.share.DiskShare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class SmbSourceListingTest {
  @Test fun unavailableShareReturnsNullAndLegacyListingIsEmpty() {
    val source = source()

    assertNull(source.listImagesOrNull())
    assertEquals(emptyList<String>(), source.listImages())
  }

  @Test fun accessibleEmptyRootReturnsEmptyRatherThanNull() {
    val share = mock(DiskShare::class.java)
    `when`(share.list("")).thenReturn(emptyList())
    val source = source(share)

    assertEquals(emptyList<String>(), source.listImagesOrNull())
    verify(share).list("")
    assertEquals(emptyList<String>(), source.listImages())
  }

  @Test fun failedBaseFolderReadReturnsNullAndLegacyListingIsEmpty() {
    val share = mock(DiskShare::class.java)
    `when`(share.list("photos\\holiday")).thenThrow(IllegalStateException("Access denied"))
    val source = source(share, "/photos/holiday/")

    assertNull(source.listImagesOrNull())
    verify(share).list("photos\\holiday")
    assertEquals(emptyList<String>(), source.listImages())
  }

  @Test fun successfulListingReturnsOnlyImagePathsIncludingReadableChildren() {
    val share = mock(DiskShare::class.java)
    val rootEntries = listOf(entry("portrait.JPG"), entry("notes.txt"),
        entry("clip.mp4"), entry("album", directory = true))
    val childEntries = listOf(entry("landscape.png"))
    `when`(share.list("photos")).thenReturn(rootEntries)
    `when`(share.list("photos\\album")).thenReturn(childEntries)
    val source = source(share, "/photos/")
    val expected = listOf("photos\\portrait.JPG", "photos\\album\\landscape.png")

    assertEquals(expected, source.listImagesOrNull())
    verify(share).list("photos")
    verify(share).list("photos\\album")
    assertEquals(expected, source.listImages())
  }

  @Test fun unreadableChildIsSkippedWithoutLosingRootImage() {
    val share = mock(DiskShare::class.java)
    val rootEntries = listOf(entry("root.jpg"), entry("private", directory = true))
    `when`(share.list("")).thenReturn(rootEntries)
    `when`(share.list("private")).thenThrow(IllegalStateException("Access denied"))
    val source = source(share)

    assertEquals(listOf("root.jpg"), source.listImagesOrNull())
    verify(share).list("")
    verify(share).list("private")
  }

  private fun source(share: DiskShare? = null, basePath: String = ""): SmbSource =
      SmbSource("unused-host", "photos", basePath, "unused-user", "unused-password").also {
        SmbSource::class.java.getDeclaredField("share").apply {
          isAccessible = true
          set(it, share)
        }
      }

  private fun entry(
      name: String,
      directory: Boolean = false,
  ): FileIdBothDirectoryInformation = mock(FileIdBothDirectoryInformation::class.java).also {
    `when`(it.fileName).thenReturn(name)
    `when`(it.fileAttributes).thenReturn(
        if (directory) FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value else 0L)
  }
}
