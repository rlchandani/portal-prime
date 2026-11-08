/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.youtube

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.immortal.launcher.BuildConfig
import com.immortal.launcher.Http
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Android wiring for [YouTubeMusicLibrary]: Keystore-encrypted token file, OkHttp, BuildConfig client. */
object YtAndroid {
  private const val DIR = "youtube"
  private const val ACCOUNTS_FILE = "accounts.bin"
  private const val CACHE_DIR = "cache"

  @Volatile private var instance: YouTubeMusicLibrary? = null

  /** The process-wide library (one token store, one search budget). */
  fun library(context: Context): YouTubeMusicLibrary =
      instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
      }

  private fun create(context: Context): YouTubeMusicLibrary {
    // noBackupFilesDir: excluded from Android auto-backup; fleet backups copy only shared_prefs.
    val root = File(context.noBackupFilesDir, DIR)
    return YouTubeMusicLibrary(
        client = YtOAuthClient(BuildConfig.YT_OAUTH_CLIENT_ID, BuildConfig.YT_OAUTH_CLIENT_SECRET),
        transport = YtOkHttpTransport(),
        secureStore = YtKeystoreSecureStore(File(root, ACCOUNTS_FILE)),
        cacheStore = YtFileCacheStore(File(root, CACHE_DIR)),
        clock = YtClock.SYSTEM,
    )
  }
}

/**
 * AES-256-GCM file encrypted with a non-exportable Android Keystore key. Layout:
 * `[version][iv length][iv][ciphertext+tag]`. Writes go through a temp file + rename.
 * If the key is lost (app data cleared, Keystore reset) [load] throws and the store reads as
 * empty — the accounts simply sign in again.
 */
class YtKeystoreSecureStore(private val file: File, private val alias: String = DEFAULT_ALIAS) : YtSecureStore {

  override fun load(): String? {
    if (!file.isFile) return null
    val bytes = file.readBytes()
    if (bytes.size < 2 || bytes[0] != VERSION) throw IOException("unsupported account file")
    val ivLength = bytes[1].toInt()
    if (ivLength <= 0 || bytes.size < 2 + ivLength) throw IOException("truncated account file")
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 2, ivLength))
    return String(cipher.doFinal(bytes, 2 + ivLength, bytes.size - 2 - ivLength), Charsets.UTF_8)
  }

  override fun save(text: String) {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key())
    val iv = cipher.iv
    val sealed = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
    val dir = file.parentFile
    if (dir != null && !dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create token directory")
    val temp = File(dir, file.name + ".tmp")
    temp.writeBytes(byteArrayOf(VERSION, iv.size.toByte()) + iv + sealed)
    if (!temp.renameTo(file)) {
      temp.delete()
      throw IOException("cannot replace token file")
    }
  }

  override fun clear() {
    file.delete()
  }

  private fun key(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(KEY_BITS)
        .build())
    return generator.generateKey()
  }

  private companion object {
    const val DEFAULT_ALIAS = "immortal.youtube.accounts"
    const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val TAG_BITS = 128
    const val KEY_BITS = 256
    const val VERSION: Byte = 1
  }
}

/**
 * OkHttp transport. Failures are rethrown as a fixed-message [IOException] so no URL, header or
 * body can reach a log or crash report through the exception chain; cancelling the coroutine
 * cancels the call.
 */
class YtOkHttpTransport(private val client: OkHttpClient = DEFAULT_CLIENT) : YtHttpTransport {

  override suspend fun execute(request: YtHttpRequest): YtHttpResponse = suspendCancellableCoroutine { cont ->
    val call = client.newCall(build(request))
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        cont.resumeWithException(IOException(FAILURE))
      }

      override fun onResponse(call: Call, response: Response) {
        val result = try {
          response.use { YtHttpResponse(it.code, it.body?.string().orEmpty()) }
        } catch (e: IOException) {
          cont.resumeWithException(IOException(FAILURE))
          return
        }
        cont.resume(result)
      }
    })
  }

  private fun build(request: YtHttpRequest): Request {
    val builder = Request.Builder().url(request.url).header("User-Agent", Http.DEFAULT_UA)
    request.headers.forEach { (k, v) -> builder.header(k, v) }
    val form = request.form
    if (form == null) return builder.method(request.method, null).build()
    val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
    return builder.method(request.method, body).build()
  }

  private companion object {
    const val FAILURE = "YouTube request failed"
    val DEFAULT_CLIENT: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
  }
}
