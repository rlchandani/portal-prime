/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** The form's loaded baseline; writes are expressed in registry keys. */
internal data class MultiRoomConnectionValues(
    val host: String, val port: Int, val username: String, val password: String,
) {
    fun editedValues(host: String, port: Int, username: String, password: String): Map<String, Any> =
        buildMap {
            // Match the existing host/user setters; passwords retain exact text.
            if (host.trim() != this@MultiRoomConnectionValues.host.trim()) put("snapcastHost", host)
            if (port != this@MultiRoomConnectionValues.port) put("maPort", port)
            if (username.trim() != this@MultiRoomConnectionValues.username.trim()) put("maUsername", username)
            if (password != this@MultiRoomConnectionValues.password) put("maPassword", password)
        }
}
