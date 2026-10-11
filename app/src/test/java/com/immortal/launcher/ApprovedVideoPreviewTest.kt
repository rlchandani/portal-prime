/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class ApprovedVideoPreviewTest {
    private fun bitmap(width: Int, height: Int) = mock(Bitmap::class.java).also {
        `when`(it.width).thenReturn(width); `when`(it.height).thenReturn(height)
    }
    @Test fun portalDecodesScaledFrameWithoutFullResolutionBitmap() {
        for (api in listOf(27, 28, 29, 36)) {
            val retriever = mock(MediaMetadataRetriever::class.java)
            val frame = bitmap(512, 288)
            `when`(retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 512, 512)).thenReturn(frame)
            assertSame(frame, approvedVideoFrame(retriever, api))
            verify(retriever, never()).getFrameAtTime(0)
        }
    }
    @Test fun failedScaledDecodeNeverFallsBackToLargeFrame() {
        val retriever = mock(MediaMetadataRetriever::class.java)
        `when`(retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 512, 512)).thenThrow(IllegalArgumentException("fixture"))
        assertTrue(runCatching { approvedVideoFrame(retriever, 29) }.isFailure)
        verify(retriever, never()).getFrameAtTime(0)
    }
    @Test fun missingScaledFrameDoesNotTriggerFullFrameDecode() {
        val retriever = mock(MediaMetadataRetriever::class.java)
        assertNull(approvedVideoFrame(retriever, 28))
        verify(retriever).getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 512, 512)
        verify(retriever, never()).getFrameAtTime(0)
    }
    @Test fun olderAndroidKeepsSmallFrameWithoutAnotherAllocation() {
        val retriever = mock(MediaMetadataRetriever::class.java); val frame = bitmap(160, 90)
        `when`(retriever.getFrameAtTime(0)).thenReturn(frame)
        assertSame(frame, approvedVideoFrame(retriever, 26))
        verify(frame, never()).recycle()
        verify(retriever, never()).getScaledFrameAtTime(anyLong(), anyInt(), anyInt(), anyInt())
    }
    @Test fun olderAndroidScalesPortraitAndReleasesOriginalFrame() {
        val retriever = mock(MediaMetadataRetriever::class.java); val frame = bitmap(2160, 3840); val small = bitmap(288,512)
        `when`(retriever.getFrameAtTime(0)).thenReturn(frame)
        assertSame(small, approvedVideoFrame(retriever, 26, scale = { source, width, height ->
            assertSame(frame, source); assertEquals(288, width); assertEquals(512, height); small
        }))
        verify(frame).recycle()
    }
    @Test fun failedCompatibilityResizeReleasesItsOwnedFrame() {
        val retriever = mock(MediaMetadataRetriever::class.java); val frame = bitmap(3840,2160)
        `when`(retriever.getFrameAtTime(0)).thenReturn(frame)
        assertTrue(runCatching { approvedVideoFrame(retriever, 24, scale = { _, _, _ -> error("fixture") }) }.isFailure)
        verify(frame).recycle()
    }
}
