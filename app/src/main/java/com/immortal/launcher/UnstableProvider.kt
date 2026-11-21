/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.ContentProviderClient
import android.content.ContentResolver
import android.net.Uri
import android.os.DeadObjectException
import android.util.Log
import java.io.InputStream

/**
 * Every call into another app's ContentProvider goes through a fresh *unstable* client.
 *
 * `ContentResolver.call`/`query`/`openInputStream` hold a stable provider reference, and Android kills each client
 * holding one when the provider's process dies (an in-place update of Jarvis killed Home: "depends on provider
 * com.portal.assistant/.control.AssistantControlProvider in dying proc"). An unstable client instead throws
 * DeadObjectException, which is answered here as "provider unavailable". The client is never kept across calls,
 * so the next call reacquires and reaches the restarted provider.
 */
internal object UnstableProvider {
  private const val TAG = "UnstableProvider"

  enum class Failure { UNAVAILABLE, DIED, FAILED }

  /** Runs [block] on a fresh unstable client for [authority], closed afterwards. Null when unavailable or failed. */
  fun <T> call(resolver: ContentResolver, authority: String, block: (ContentProviderClient) -> T?): T? =
      withClient(authority, { resolver.acquireUnstableContentProviderClient(authority) }, { it.close() }, block,
          isDeath = { it is DeadObjectException }, onFailure = ::log)

  /**
   * Gives [block] an opener for [uri]. A content:// opener reads through an unstable client that stays open only
   * for [block]; file:// and android.resource:// never reach a provider and keep the resolver.
   */
  fun <T> withStream(resolver: ContentResolver, uri: Uri, block: (open: () -> InputStream?) -> T?): T? {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return block { resolver.openInputStream(uri) }
    val authority = uri.authority ?: return null
    return call(resolver, authority) { client -> block { client.openAssetFile(uri, "r")?.createInputStream() } }
  }

  /** The Android-free core: acquire, run, always close; a null client or any failure yields null. */
  internal fun <C, T> withClient(
      authority: String,
      acquire: () -> C?,
      close: (C) -> Unit,
      block: (C) -> T?,
      isDeath: (Throwable) -> Boolean,
      onFailure: (String, Failure, Throwable?) -> Unit,
  ): T? {
    val client = try {
      acquire()
    } catch (e: Exception) {
      onFailure(authority, Failure.FAILED, e)
      return null
    }
    if (client == null) {
      onFailure(authority, Failure.UNAVAILABLE, null)
      return null
    }
    return try {
      block(client)
    } catch (e: Exception) {
      onFailure(authority, if (isDeath(e)) Failure.DIED else Failure.FAILED, e)
      null
    } finally {
      try {
        close(client)
      } catch (e: Exception) {
        onFailure(authority, Failure.FAILED, e)
      }
    }
  }

  private fun log(authority: String, failure: Failure, error: Throwable?) {
    val message = "$authority ${failure.name.lowercase()}${error?.let { ": ${it.javaClass.simpleName}" } ?: ""}"
    // Not installed is routine (an optional companion); a dying or failing provider is worth a warning.
    if (failure == Failure.UNAVAILABLE) Log.d(TAG, message) else Log.w(TAG, message)
  }
}
