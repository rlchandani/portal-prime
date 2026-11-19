/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The at-rest data key for stored video messages: 32 random bytes, kept only wrapped by a
 * non-exportable Android Keystore AES key (so a copied file set is useless off this Portal).
 * The wrapped blob lives inside the protected store directory. Unwrapped once per process.
 */
internal object CallMessageKeys {
  private const val ALIAS = "hearthpane_vmsg_v1"
  private const val BLOB = "key.bin"
  private const val GCM_BITS = 128
  private const val IV_BYTES = 12
  private const val KEY_BYTES = 32
  @Volatile private var cached: ByteArray? = null

  @Synchronized
  fun dataKey(context: Context, dir: File): ByteArray {
    cached?.let { return it }
    val blob = File(dir, BLOB)
    val key = if (blob.exists()) unwrap(Base64.decode(blob.readText(), Base64.NO_WRAP)) else create(blob)
    cached = key
    return key
  }

  private fun create(blob: File): ByteArray {
    val key = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
    val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
    val wrapped = c.iv + c.doFinal(key)
    val tmp = File(blob.parentFile, "$BLOB.tmp")
    tmp.writeText(Base64.encodeToString(wrapped, Base64.NO_WRAP))
    if (!tmp.renameTo(blob)) throw java.io.IOException("could not store the message key")
    return key
  }

  private fun unwrap(wrapped: ByteArray): ByteArray {
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(GCM_BITS, wrapped, 0, IV_BYTES))
    return c.doFinal(wrapped, IV_BYTES, wrapped.size - IV_BYTES)
  }

  private fun keystoreKey(): SecretKey {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
    val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256).build())
    return gen.generateKey()
  }

  /** Delete-all also forgets the key: nothing old can be decrypted again. */
  @Synchronized
  fun forget(dir: File) {
    cached = null
    File(dir, BLOB).delete()
    runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
  }
}
