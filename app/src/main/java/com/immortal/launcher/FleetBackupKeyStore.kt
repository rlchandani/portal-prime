/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Base64

/**
 * The operator's backup public key, which every `/backup/export` is sealed to.
 *
 * A fleet enrolment only proposes a key: it waits as *pending* until someone with USB access
 * confirms it by id ([FleetBackupKeyAdbReceiver], `CONFIRM_BACKUP_KEY`). Otherwise a fleet token
 * alone could enrol a key it holds on a Portal that has none yet and export the secrets 1.274
 * took off `/fs/read`. Once a key is active, enrolling it again is a no-op and any other key is
 * refused (`409 backup_key_mismatch`); changing it (a new or lost Mac key) means clearing it over
 * USB (`FORGET_BACKUP_KEY`) and enrolling again. Public key material only; never a secret.
 */
internal object FleetBackupKeyStore {
  const val PREFS = "fleet_backup_key"
  private const val ACTIVE = "spki"
  private const val PENDING = "pending"
  const val ACTION_CONFIRM = "com.immortal.launcher.action.CONFIRM_BACKUP_KEY"
  const val ACTION_FORGET = "com.immortal.launcher.action.FORGET_BACKUP_KEY"
  const val EXTRA_KEY_ID = "keyId"

  enum class Outcome { UNCHANGED, PENDING, MISMATCH }

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private fun read(c: Context, name: String): ByteArray? =
      prefs(c).getString(name, null)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

  /** The confirmed key exports are sealed to, or null. */
  fun get(c: Context): ByteArray? = read(c, ACTIVE)

  fun pending(c: Context): ByteArray? = read(c, PENDING)

  /** Proposes [spki] (already parsed and size-checked by the caller). */
  @Synchronized fun enrol(c: Context, spki: ByteArray): Outcome {
    val outcome = decide(get(c), spki)
    if (outcome == Outcome.PENDING && !pending(c).contentEquals(spki)) {
      store(c) { putString(PENDING, Base64.getEncoder().encodeToString(spki)) }
    }
    return outcome
  }

  /** Activates the pending key if its id is [keyId]. Only the USB-guarded receiver calls this. */
  @Synchronized fun confirm(c: Context, keyId: String): Boolean {
    val candidate = pending(c) ?: return false
    if (get(c) != null || FleetBackupCrypto.keyId(candidate) != keyId) return false
    store(c) { putString(ACTIVE, Base64.getEncoder().encodeToString(candidate)).remove(PENDING) }
    return true
  }

  @Synchronized fun forget(c: Context): Boolean = prefs(c).edit().remove(ACTIVE).remove(PENDING).commit()

  /** The enrolment rule, pure: nothing active yet means the key waits for USB confirmation. */
  fun decide(active: ByteArray?, candidate: ByteArray): Outcome = when {
    active == null -> Outcome.PENDING
    active.contentEquals(candidate) -> Outcome.UNCHANGED
    else -> Outcome.MISMATCH
  }

  private inline fun store(c: Context, edit: android.content.SharedPreferences.Editor.() -> Unit) {
    if (!prefs(c).edit().apply(edit).commit()) throw IllegalStateException("backup key not stored")
  }
}

/**
 * The USB half of backup key enrolment. Only the adb shell can send these: the manifest guards
 * the receiver with `android.permission.DUMP`, which the shell holds and apps can't request.
 *
 * - `adb shell am broadcast -a com.immortal.launcher.action.CONFIRM_BACKUP_KEY --es keyId <id>
 *   -p <package>` activates the pending key with that id (`fleet-backup.sh` sends it when the
 *   Portal is on USB).
 * - `adb shell am broadcast -a com.immortal.launcher.action.FORGET_BACKUP_KEY -p <package>`
 *   clears the active and pending keys.
 */
class FleetBackupKeyAdbReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val app = context.applicationContext
    when (intent.action) {
      FleetBackupKeyStore.ACTION_CONFIRM -> {
        val keyId = intent.getStringExtra(FleetBackupKeyStore.EXTRA_KEY_ID).orEmpty()
        Log.i(TAG, "backup key confirmed over adb: ${FleetBackupKeyStore.confirm(app, keyId)}")
      }
      FleetBackupKeyStore.ACTION_FORGET -> Log.i(TAG, "backup key cleared over adb: ${FleetBackupKeyStore.forget(app)}")
    }
  }

  private companion object {
    const val TAG = "HearthpaneBackupKey"
  }
}
