/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Bounds one broadcast's connections: unauthenticated handshakes and authenticated listeners are
 * capped separately, so a LAN flood of half-open sockets can't exhaust mics or threads, and can
 * delay (never displace) a household listener only until those handshakes time out. Thread-safe.
 */
internal class IntercomSlots(private val maxPending: Int, private val maxListeners: Int) {
  private var pending = 0
  private var listeners = 0

  @Synchronized fun tryBeginHandshake(): Boolean = (pending < maxPending).also { if (it) pending++ }
  @Synchronized fun endHandshake() { if (pending > 0) pending-- }
  @Synchronized fun tryBeginListener(): Boolean = (listeners < maxListeners).also { if (it) listeners++ }
  @Synchronized fun endListener() { if (listeners > 0) listeners-- }
}
