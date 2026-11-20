/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/**
 * Where a request-supplied URL may make the Portal connect (LAN-appliance SSRF guard). Pure apart
 * from the injectable resolver.
 */
internal object LanUrlPolicy {
  private const val ULA_PREFIX = 0xfc
  private const val ULA_MASK = 0xfe
  private const val BYTE_MASK = 0xff

  /** RFC 1918 IPv4 or IPv6 unique-local (fc00::/7). Never loopback, link-local, any or multicast. */
  fun isPrivateLan(address: InetAddress): Boolean = when (address) {
    is Inet4Address -> address.isSiteLocalAddress
    is Inet6Address -> (address.address[0].toInt() and BYTE_MASK and ULA_MASK) == ULA_PREFIX
    else -> false
  }

  /** Every address the host resolves to must be private (no mixed public/private answers). */
  fun resolvesPrivate(host: String, resolve: (String) -> List<InetAddress>): Boolean =
      runCatching { resolve(host) }.getOrNull()?.let { it.isNotEmpty() && it.all(::isPrivateLan) } == true

  /**
   * `/remote/immich/albums` may contact [url] only when it is the Immich server already saved on
   * this Portal (the owner set that up), or an http(s) URL whose host resolves to private LAN
   * addresses only.
   */
  fun immichAlbumsAllowed(url: String, stored: String?, resolve: (String) -> List<InetAddress> = ::systemResolve): Boolean {
    val endpoint = RemoteCompanionSources.endpoint(url, immich = true) ?: return false
    if (!stored.isNullOrBlank() && endpoint == RemoteCompanionSources.endpoint(stored, immich = true)) return true
    val host = runCatching { URI(endpoint).host }.getOrNull()?.trim('[', ']') ?: return false
    return resolvesPrivate(host, resolve)
  }

  private fun systemResolve(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()
}

/**
 * Hosts a fleet `/install` `apkUrl` may download from, including each redirect hop: our GitHub
 * release hosts, F-Droid, and the hosts the loaded catalog itself points at. HTTPS on 443 only.
 */
internal object ApkUrlPolicy {
  private const val HTTPS = "https"
  private const val HTTPS_PORT = 443
  private const val NO_PORT = -1
  val BUILT_IN_HOSTS: Set<String> = OtaConstants.HOP_HOSTS + "f-droid.org"

  /** [BUILT_IN_HOSTS] plus the https hosts named by catalog entries. */
  fun allowedHosts(catalog: List<CatalogApp>): Set<String> =
      BUILT_IN_HOSTS + catalog.mapNotNull { app -> app.apkUrl?.let(::httpsHost) }

  fun isAllowed(url: String, hosts: Set<String>): Boolean = httpsHost(url)?.let { it in hosts } == true

  /** The resolved redirect target, or null when it is missing or leaves [hosts]. */
  fun nextHop(current: String, location: String?, hosts: Set<String>): String? {
    if (location.isNullOrBlank()) return null
    val next = runCatching { URI(current).resolve(location.trim()).toString() }.getOrNull() ?: return null
    return next.takeIf { isAllowed(it, hosts) }
  }

  private fun httpsHost(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull()?.takeIf { it.isAbsolute } ?: return null
    if (!uri.scheme.equals(HTTPS, ignoreCase = true) || uri.rawUserInfo != null) return null
    if (uri.port != NO_PORT && uri.port != HTTPS_PORT) return null
    return uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
  }
}

/** Size and free-space limits for fleet `/fs/write`. */
internal object FsWritePolicy {
  const val MAX_BYTES = 512L * 1024 * 1024
  /** Storage that must still be free after the write lands. */
  const val MIN_FREE_AFTER_BYTES = 256L * 1024 * 1024

  /** Null when the write may proceed, else the error code to return. */
  fun refusal(contentLength: Long, usableBytes: Long): String? = when {
    contentLength > MAX_BYTES -> "body_too_large"
    usableBytes - contentLength < MIN_FREE_AFTER_BYTES -> "insufficient_storage"
    else -> null
  }
}
