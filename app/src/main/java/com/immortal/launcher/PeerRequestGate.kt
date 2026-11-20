/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * The first check on every household-signed Portal↔Portal route (the `/remote/...peer` routes): before reading
 * a body of up to a megabyte, the request's signed header must already verify — right key, sane
 * shape, fresh — and be of the [type] that route serves. An unauthenticated LAN client is refused
 * from the header alone and never makes the Portal read, buffer or hash its body.
 *
 * Wire-compatible by construction: every Portal already sends this header; this only moves the
 * header's own check ahead of the body. The full check (replay guard, pinned body hash) still
 * runs in the route's `verifyRequest` afterwards.
 */
internal object PeerRequestGate {
  fun admits(header: String?, type: String, key: ByteArray, nowMs: Long): Boolean {
    if (header == null) return false
    val message = runCatching { HouseholdMessage.authenticate(header, key, nowMs) }.getOrNull() ?: return false
    return message.type == type
  }
}
