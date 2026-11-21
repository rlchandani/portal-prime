/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Which ceremony a code belongs to (calling-phase-2.md, "Portal-only device keys"). Linking two
 * homes ([HOME]) and pinning another Portal of this home ([PORTAL_PIN]) run the same protocol, so
 * every label that feeds a hash, MAC, PAKE generator or signature is domain-separated: a pinning
 * code can never complete a home link and vice versa, even if someone types one into the other
 * screen (different mailbox id, different PAKE base point, different session keys, different
 * signed texts). The nameplate's first symbol tells them apart for people too: pinning codes
 * always start with [PIN_PREFIX], home codes never do.
 *
 * [HOME] keeps the v2 labels the linking protocol shipped with, so nothing about it changes.
 */
internal enum class HomeLinkDomain(
    nameplate: String,
    pake: String,
    sid: String,
    isk: String,
    commit: String,
    /** Signed by the inviter (its key and name, bound to the PAKE session). */
    val reveal: String,
    /** Signed by the joiner (its key, nonce and name, over the inviter's key). */
    val open: String,
) {
  HOME("hearthpane-homelink-nameplate-v2", "hearthpane-homelink-cpace-v2-P256_XMD:SHA-256_SSWU_RO_",
      "hearthpane-homelink-sid-v1", "hearthpane-homelink-isk-v1", "hearthpane-homelink-commit-v1",
      "hearthpane-homelink-reveal-v2", "hearthpane-homelink-open-v2"),
  PORTAL_PIN("hearthpane-portalpin-nameplate-v1", "hearthpane-portalpin-cpace-v1-P256_XMD:SHA-256_SSWU_RO_",
      "hearthpane-portalpin-sid-v1", "hearthpane-portalpin-isk-v1", "hearthpane-portalpin-commit-v1",
      "hearthpane-portalpin-reveal-v1", "hearthpane-portalpin-open-v1");

  private val nameplateBytes = HomeLinkHash.utf8(nameplate)
  private val pakeBytes = HomeLinkHash.utf8(pake)
  private val sidBytes = HomeLinkHash.utf8(sid)
  private val iskBytes = HomeLinkHash.utf8(isk)
  private val commitBytes = HomeLinkHash.utf8(commit)

  /** HMAC key for the mailbox id. */
  val nameplateSalt: ByteArray get() = nameplateBytes.copyOf()
  /** RFC 9380 DST for the PAKE's hash to the curve. */
  val pakeDst: ByteArray get() = pakeBytes.copyOf()
  val sidLabel: ByteArray get() = sidBytes.copyOf()
  val iskLabel: ByteArray get() = iskBytes.copyOf()
  val commitLabel: ByteArray get() = commitBytes.copyOf()

  /** Whether the messages carry and sign the sender's household device id (only Portals pinning each other). */
  val bindsDevice: Boolean get() = this == PORTAL_PIN

  /** The code's first symbol is fixed for pinning codes and never that symbol for home codes. */
  fun ownsNameplate(nameplate: String): Boolean = nameplate.startsWith(PIN_PREFIX) == (this == PORTAL_PIN)

  companion object {
    const val PIN_PREFIX = 'P'
  }
}
