/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** The form's loaded baseline; writes are expressed in registry keys. */
internal data class MqttConnectionValues(
    val host: String, val port: Int, val username: String, val password: String, val useTls: Boolean,
) {
    fun editedValues(host: String, port: Int, username: String, password: String, useTls: Boolean): Map<String, Any> =
        buildMap {
            // Normalize host/user like the existing setters; passwords retain exact text.
            val normalizedHost = host.trim()
            val normalizedUsername = username.trim()
            if (normalizedHost != this@MqttConnectionValues.host.trim()) put("host", normalizedHost)
            if (port != this@MqttConnectionValues.port) put("port", port)
            if (normalizedUsername != this@MqttConnectionValues.username.trim()) put("username", normalizedUsername)
            // Submit changed blanks too: the registry owns secret blank-ignore.
            if (password != this@MqttConnectionValues.password) put("password", password)
            if (useTls != this@MqttConnectionValues.useTls) put("useTls", useTls)
        }
}
