/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Setup distinguishes an accessible empty folder from an unreadable source. */
internal sealed interface PhotoFolderCheck {
    data class Photos(val count: Int) : PhotoFolderCheck
    data object Empty : PhotoFolderCheck
    data object Unavailable : PhotoFolderCheck
}

internal fun photoFolderCheck(result: Result<Int?>): PhotoFolderCheck =
    when (val count = result.getOrNull()) {
        null -> PhotoFolderCheck.Unavailable
        0 -> PhotoFolderCheck.Empty
        else -> if (count > 0) PhotoFolderCheck.Photos(count) else PhotoFolderCheck.Unavailable
    }

internal const val EMPTY_PHOTO_FOLDER_MESSAGE =
    "Connected, but no readable photos were found in this folder. Choose a folder with photos and try again. Your current photo source hasn't changed."
