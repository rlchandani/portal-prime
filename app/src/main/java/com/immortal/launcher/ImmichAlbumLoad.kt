/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/** Album metadata can fail independently of a verified whole-library connection. */
internal data class ImmichAlbumLoad(
    val albums: List<ImmichSource.Album> = emptyList(),
    val failed: Boolean = false,
) {
    fun refreshed(result: Result<List<ImmichSource.Album>?>): ImmichAlbumLoad =
        result.getOrNull()?.let { ImmichAlbumLoad(it) } ?: copy(failed = true)

    fun selection(id: String?): String? =
        id?.takeIf { candidate -> albums.any { it.id == candidate } }
}
