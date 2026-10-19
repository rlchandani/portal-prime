/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * Pure auth boundary for the legacy control path, using the pinned MA auth contract.
 * Each socket must prove authenticated:true for its own request. No token/success cache
 * exists, so endpoint, user, password, or token changes cannot inherit old authorization.
 *
 * auth/login's current response shape has not been verified locally. Username/password
 * minting is explicitly unsupported instead of accepting an unverified login result.
 * Only the configured MA API token is sent; provider cookies/credentials stay on MA.
 */
internal class MaLegacyAuth {
  private val requests = AtomicLong()

  fun configurationFailure(apiToken: String, user: String, password: String): MaMusicProtocol.Reply.Failure? {
    if (apiToken.isBlank()) {
      val problem = if (user.isBlank() && password.isBlank())
        "Music Assistant API token required; no login configured" else
        "Legacy username/password login is unsupported; configure a Music Assistant API token"
      return MaMusicProtocol.Reply.Failure(MusicConnection.AUTH_REQUIRED, problem)
    }
    if (apiToken.length > 8192 || apiToken.any { it.code < 32 || it.code == 127 }) {
      return MaMusicProtocol.Reply.Failure(MusicConnection.AUTH_REQUIRED, "Invalid Music Assistant API token")
    }
    return null
  }

  fun authorize(
      apiToken: String, user: String, password: String,
      exchange: (JSONObject) -> String?,
  ): MaMusicProtocol.Reply<Unit> {
    configurationFailure(apiToken, user, password)?.let { return it }
    val id = "legacy-auth-${requests.incrementAndGet()}"
    val request = JSONObject().put("message_id", id).put("command", "auth")
        .put("args", JSONObject().put("token", apiToken).put("device_name", "Immortal"))
    val response = try { exchange(request) } catch (_: Exception) {
      return MaMusicProtocol.Reply.Failure(MusicConnection.OFFLINE, "Music Assistant authentication connection failed")
    } ?: return MaMusicProtocol.Reply.Failure(MusicConnection.OFFLINE, "Music Assistant authentication timed out")
    return MaMusicProtocol.parseAuthReply(response, expectedMessageId = id)
  }
}
