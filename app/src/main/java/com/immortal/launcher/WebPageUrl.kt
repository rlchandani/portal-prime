/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.net.IDN
import java.net.URI
import java.net.URL

/** Format validation only: never resolves a host or rewrites a signed URL. */
internal object WebPageUrl {
  fun normalize(raw: String): String? {
    if (raw.any { Character.isISOControl(it) }) return null
    val value = raw.trim()
    if (value.isEmpty()) return null
    return runCatching {
      // URI checks escaping and syntax; URL accepts Unicode and ordinary LAN hostnames.
      val uri = URI(value)
      if (!uri.isAbsolute || !(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) ||
          uri.rawAuthority.isNullOrBlank()) return null
      val address = URL(value)
      val host = address.host
      if (host.isNullOrBlank() || address.port !in -1..65535) return null
      val hostPort = uri.rawAuthority.substringAfterLast('@')
      val suffix = if (hostPort.startsWith('[')) hostPort.substringAfter(']', "invalid")
          else hostPort.substringAfter(':', "")
      // URL treats an explicit -1 as its default port; user input must use digits.
      if (suffix.isNotEmpty()) {
        val port = if (hostPort.startsWith('[')) {
          if (!suffix.startsWith(':')) return null
          suffix.drop(1)
        } else suffix
        if (port.isEmpty() || port.any { it !in '0'..'9' }) return null
      }
      if (host.startsWith('[')) {
        if (uri.host.isNullOrBlank()) return null // URI validates the IPv6 literal.
      } else {
        val ascii = IDN.toASCII(host)
        if (ascii.trimEnd('.').isBlank() || ascii.any {
              it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it !in ".-_"
            }) return null
      }
      value
    }.getOrNull()
  }
}
