/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.provider.Settings
import android.util.Log
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/** Durable restoration token distinguishes an absent system setting from no pending handoff. */
internal data class SavedPortalAmbient(val original: String?)

internal interface PortalAmbientBackend {
  fun saved(): SavedPortalAmbient?
  fun save(original: String?): Boolean
  fun clear(): Boolean
  fun current(): String?
  fun write(value: String?): Boolean
}

/** Calls owns a temporary ambient-mode override; a return restores the exact original value. */
internal class PortalAmbientHandoff(private val backend: PortalAmbientBackend) {
  @Synchronized
  fun begin(): Boolean {
    val saved = backend.saved()
    val current = backend.current()
    if (saved == null && current == "0") return true
    // Confirm a durable token before every override, including a repeated handoff.
    // Rebase a later external change instead of restoring an obsolete original value.
    val original = if (saved != null && current == "0") saved.original else current
    if (!backend.save(original)) return false
    if (current == "0" || backend.write("0")) return true
    restore()
    return false
  }

  @Synchronized
  fun restore(): Boolean {
    val saved = backend.saved() ?: return true
    val current = backend.current()
    // Preserve a later external change; only replace the temporary value we own.
    if (current == "0" && current != saved.original && !backend.write(saved.original)) return false
    return backend.clear()
  }
}

/** A tombstone is committed atomically; failed writes preserve the previous restoration token. */
internal fun encodePortalAmbient(original: String?): String = if (original == null) "0" else "1$original"
internal fun decodePortalAmbient(encoded: String): SavedPortalAmbient? = when {
  encoded == "-" -> null
  encoded == "0" -> SavedPortalAmbient(null)
  encoded.startsWith("1") -> SavedPortalAmbient(encoded.substring(1))
  else -> error("Invalid Portal ambient restoration token")
}

/** Meta uses this secure setting to enter ambient Home and schedule its 400 ms dream bootstrap. */
internal object PortalHomeAmbientGuard {
  private const val SETTING = "screensaver_activate_on_sleep"

  private fun handoff(context: Context): PortalAmbientHandoff {
    val app = context.applicationContext ?: context
    val file = AtomicFile(File(app.filesDir, "portal_home_ambient_handoff"))
    fun persist(encoded: String): Boolean = DreamBridgePersistence.commit {
      var output: FileOutputStream? = null
      try {
        output = file.startWrite()
        output.write(encoded.toByteArray(Charsets.UTF_8))
        output.fd.sync() // Surface a failed durability flush before committing the atomic replacement.
        file.finishWrite(output)
        true
      } catch (_: Exception) {
        output?.let { file.failWrite(it) }
        false
      }
    }
    return PortalAmbientHandoff(object : PortalAmbientBackend {
      override fun saved(): SavedPortalAmbient? = try {
        decodePortalAmbient(file.readFully().toString(Charsets.UTF_8))
      } catch (_: FileNotFoundException) { null }
      override fun save(original: String?): Boolean = persist(encodePortalAmbient(original))
      override fun clear(): Boolean = persist("-")
      override fun current(): String? = Settings.Secure.getString(app.contentResolver, SETTING)
      override fun write(value: String?): Boolean = Settings.Secure.putString(app.contentResolver, SETTING, value)
    })
  }

  @Synchronized
  fun begin(context: Context): Boolean = runCatching { handoff(context).begin() }
      .getOrElse { Log.w("ImmortalPortalHome", "Could not suspend Meta ambient bootstrap", it); false }

  @Synchronized
  fun restore(context: Context): Boolean = runCatching { handoff(context).restore() }
      .getOrElse { Log.w("ImmortalPortalHome", "Could not restore ambient mode", it); false }
}
