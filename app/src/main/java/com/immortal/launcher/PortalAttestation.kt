/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.ByteArrayInputStream
import java.security.GeneralSecurityException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** What another Portal's Keystore attestation chain showed when it was pinned (recorded, never required). */
internal enum class PortalAttestationLevel {
  /** Pinned before attestation was checked, or its chain never arrived. */
  UNCHECKED,
  /** The Portal's Keystore gave no attestation (one self-signed certificate, or none). */
  ABSENT,
  /** A chain that doesn't verify, isn't for the pinned key, or has no readable key description. */
  INVALID,
  /** A well-formed chain ending in a root that isn't Google's (e.g. a vendor's own attestation root). */
  UNTRUSTED_ROOT,
  /** Google-rooted, but the key or the attestation lives in software. */
  SOFTWARE,
  TEE,
  STRONGBOX;

  /** Whether the chain proved the pinned key is non-exportable hardware. */
  val hardware: Boolean get() = this == TEE || this == STRONGBOX
}

/**
 * Checks a Portal's Keystore key attestation chain (API 24+, so fine on a Portal's Android 9/10):
 * leaf first, each certificate signed by the next, the last self-signed by one of Google's
 * hardware attestation roots ([GOOGLE_ROOTS], from `android.googleapis.com/attestation/root`),
 * the leaf's key equal to the key we pinned, and the leaf's KeyDescription extension saying
 * where the key lives. Pure; the result is only recorded on the pin (Portals aren't
 * Google-certified, so a vendor root or no attestation at all is expected and never blocks).
 *
 * Not checked (recorded as residual): certificate validity dates (attestation intermediates are
 * routinely expired on older devices) and Google's revocation list (needs a network fetch).
 */
internal object PortalAttestation {
  const val MAX_CHAIN = 6
  const val MAX_CERT_BYTES = 4_096
  private const val KEY_DESCRIPTION_OID = "1.3.6.1.4.1.11129.2.1.17"

  /** SubjectPublicKeyInfo (Base64) of Google's attestation roots: the RSA-4096 root and the 2025 ECDSA P-384 root. */
  val GOOGLE_ROOTS: List<String> = listOf(
      "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAr7bHgiuxpwHsK7Qui8xUFmOr75gvMsd/dTEDDJdSSxtf6An7xyqpRR90PL2abxM1dEqlXnf2" +
          "tqw1Ne4Xwl5jlRfdnJLmN0pTy/4lj4/7tv0Sk3iiKkypnEUtR6WfMgH0QZfKHM1+di+y9TFRtv6y//0rb+T+W8a9nsNL/ggjnar86461qO0rOs2c" +
          "Xjp3kOG1FEJ5MVmFmBGtnrKpa73XpXyTqRxB/M0n1n/W9nGqC4FSYa04T6N5RIZGBN2z2MT5IKGbFlbC8UrW0DxW7AYImQQcHtGl/m00QLVWutHQ" +
          "oVJYnFPlXTcHYvASLu+RhhsbDmxMgJJ0mcDpvsC4PjvB+TxywElgS70vE0XmLD+OJtvsBslHZvPBKCOdT0MS+tgSOIfga+z1Z1g7+DVagf7quvma" +
          "g8jfPioyKvxnK/EgsTUVi2ghzq8wm27ud/mIM7AY2qEORR8Go3TVB4HzWQgpZrt3i5MIlCaY504LzSRiigHCzAPlHws+W0rB5N+er5/2pJKnfBSD" +
          "iCiFAVtCLOZ7gLiMm0jhO2B6tUXHI/+MRPjy02i59lINMRRev56GKtcd9qO/0kUJWdZTdA2XoS82ixPvZtXQpUpuL12ab+9EaDK8Z4RHJYYfCT3Q" +
          "5vNAXaiWQ+8PTWm2QgBR/bkwSWc+NpUFgNPN9PvQi8WEg5UmAGMCAwEAAQ==",
      "MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEI9ojcU7fPlsFCjxy6IRqzgeOoK0b+YsV9FPQywiyw8EQRTkJ9u3qwfnI4DGoSLlBqClTXJfgfCcZvs60Fik" +
          "NMHnu4fkRzObfgDkU2KNXezT9/RQ+XvNslxPHrHCowhGr",
  )

  /**
   * The level [chain] (DER certificates, leaf first) proves for [expectedKey] (Base64 X.509, the
   * pinned key). [challenge], if given, must be the one the key was made with.
   */
  fun verify(chain: List<ByteArray>, expectedKey: String, roots: List<String> = GOOGLE_ROOTS,
             challenge: ByteArray? = null): PortalAttestationLevel {
    if (chain.size <= 1) return PortalAttestationLevel.ABSENT
    if (chain.size > MAX_CHAIN || chain.any { it.size > MAX_CERT_BYTES }) return PortalAttestationLevel.INVALID
    val certs = parse(chain) ?: return PortalAttestationLevel.INVALID
    if (HomeLinkHash.b64(certs.first().publicKey.encoded) != expectedKey) return PortalAttestationLevel.INVALID
    if (!signedInOrder(certs)) return PortalAttestationLevel.INVALID
    val description = keyDescription(certs.first()) ?: return PortalAttestationLevel.INVALID
    if (challenge != null && !HomeLinkHash.same(challenge, description.challenge)) return PortalAttestationLevel.INVALID
    if (HomeLinkHash.b64(certs.last().publicKey.encoded) !in roots) return PortalAttestationLevel.UNTRUSTED_ROOT
    return when (minOf(description.attestationLevel, description.keyLevel)) {
      LEVEL_TEE -> PortalAttestationLevel.TEE
      LEVEL_STRONGBOX -> PortalAttestationLevel.STRONGBOX
      else -> PortalAttestationLevel.SOFTWARE
    }
  }

  /** The two security levels and challenge from the KeyDescription extension, or null. */
  internal class KeyDescription(val attestationLevel: Int, val keyLevel: Int, val challenge: ByteArray)

  private const val LEVEL_TEE = 1
  private const val LEVEL_STRONGBOX = 2

  private fun parse(chain: List<ByteArray>): List<X509Certificate>? = try {
    val factory = CertificateFactory.getInstance("X.509")
    chain.map { factory.generateCertificate(ByteArrayInputStream(it)) as X509Certificate }
  } catch (e: GeneralSecurityException) { null } catch (e: ClassCastException) { null }

  private fun signedInOrder(certs: List<X509Certificate>): Boolean = try {
    certs.zipWithNext { child, parent -> child.verify(parent.publicKey) }
    certs.last().verify(certs.last().publicKey)
    true
  } catch (e: GeneralSecurityException) { false } catch (e: RuntimeException) { false }

  internal fun keyDescription(cert: X509Certificate): KeyDescription? {
    val wrapped = cert.getExtensionValue(KEY_DESCRIPTION_OID) ?: return null
    return try {
      val outer = Der(wrapped).next(Der.OCTET_STRING)
      val seq = Der(Der(outer).next(Der.SEQUENCE))
      seq.next(Der.INTEGER)
      val attestation = Der.int(seq.next(Der.ENUMERATED))
      seq.next(Der.INTEGER)
      val key = Der.int(seq.next(Der.ENUMERATED))
      KeyDescription(attestation, key, seq.next(Der.OCTET_STRING))
    } catch (e: IllegalArgumentException) { null }
  }
}

/** Just enough DER to walk the start of a KeyDescription: definite lengths, single-byte tags. */
internal class Der(private val bytes: ByteArray) {
  private var at = 0

  /** The content of the next element, which must have [tag]. */
  fun next(tag: Int): ByteArray {
    require(at + 2 <= bytes.size && (bytes[at].toInt() and 0xff) == tag) { "unexpected DER" }
    at++
    val first = bytes[at++].toInt() and 0xff
    val length = if (first < 0x80) first else {
      val n = first and 0x7f
      require(n in 1..3 && at + n <= bytes.size) { "bad DER length" }
      (0 until n).fold(0) { acc, _ -> (acc shl 8) or (bytes[at++].toInt() and 0xff) }
    }
    require(length >= 0 && at + length <= bytes.size) { "DER overrun" }
    return bytes.copyOfRange(at, at + length).also { at += length }
  }

  companion object {
    const val INTEGER = 0x02
    const val OCTET_STRING = 0x04
    const val ENUMERATED = 0x0a
    const val SEQUENCE = 0x30

    /** A small non-negative INTEGER/ENUMERATED value. */
    fun int(content: ByteArray): Int {
      require(content.size in 1..4) { "bad DER int" }
      return content.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
    }
  }
}
