/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * What of linking homes an ordinary build runs. The vault sync (`/remote/homelinkpeer`,
 * [HomeLinkSync]) hands the household identity's private key to any caller holding the household
 * key at a Portal's announced address, and paired phones hold that key too, so a phone could pose
 * as a Portal and take the identity. Until Portal-only key material exists (calling-phase-2.md,
 * Risks) the route and the sync are measurement-only, like the "Link homes test" that is their
 * only user: off in release, performance and benchmark builds, on only with
 * `-PportalCallLoopback=true`.
 */
internal object HomeLinkGate {
  /** Register `/remote/homelinkpeer` and run [HomeLinkSync]. */
  val VAULT_SYNC: Boolean = BuildConfig.PORTAL_CALL_LOOPBACK
}
