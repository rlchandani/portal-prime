/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * A P-256 ECDSA signer the pairing protocol can run with: a household's [HomeLinkIdentity] (a
 * software key shared by the home's Portals) or one Portal's [PortalDeviceKey] (non-exportable,
 * Keystore). [publicKey] is Base64 X.509; [sign] returns a Base64 SHA256withECDSA signature that
 * [HomeLinkIdentity.verify] accepts.
 */
internal interface HomeLinkSigner {
  val publicKey: String
  fun sign(data: ByteArray): String
}
