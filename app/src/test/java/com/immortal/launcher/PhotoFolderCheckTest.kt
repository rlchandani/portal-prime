/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoFolderCheckTest {
    @Test fun successfulEmptyFolderIsConnectedWithoutPhotos() {
        assertEquals(PhotoFolderCheck.Empty, photoFolderCheck(Result.success(0)))
    }
    @Test fun failedListingIsUnavailableRatherThanEmpty() {
        assertEquals(PhotoFolderCheck.Unavailable, photoFolderCheck(Result.failure(IllegalStateException("unavailable"))))
    }
    @Test fun nullableSourceFailureIsUnavailable() {
        assertEquals(PhotoFolderCheck.Unavailable, photoFolderCheck(Result.success(null)))
    }
    @Test fun successfulPhotosRetainTheirCount() {
        assertEquals(PhotoFolderCheck.Photos(12), photoFolderCheck(Result.success(12)))
    }
    @Test fun invalidNegativeCountCannotSelectASource() {
        assertEquals(PhotoFolderCheck.Unavailable, photoFolderCheck(Result.success(-1)))
    }
}
