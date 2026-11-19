/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import com.immortal.launcher.youtube.YtKeystoreSecureStore
import com.immortal.launcher.youtube.YtSecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Strict codec for [AutoAnswerRules]. Anything unexpected (wrong version, wrong types, a bad id,
 * too many people, auto-answer on without Grandma mode) reads as everything off: a damaged or
 * planted file can only ever turn auto-answer off, never on.
 */
internal object AutoAnswerCodec {
  const val VERSION = 1

  fun encode(r: AutoAnswerRules): String = JSONObject().put("v", VERSION).put("g", r.grandmaMode).put("a", r.enabled)
      .put("ids", JSONArray(r.allowed.sorted())).toString()

  fun decode(raw: String?): AutoAnswerRules {
    if (raw.isNullOrBlank()) return AutoAnswerRules()
    val o = runCatching { JSONObject(raw) }.getOrNull() ?: return AutoAnswerRules()
    if (o.opt("v") != VERSION) return AutoAnswerRules()
    val grandma = o.opt("g") as? Boolean ?: return AutoAnswerRules()
    val enabled = o.opt("a") as? Boolean ?: return AutoAnswerRules()
    val arr = o.opt("ids") as? JSONArray ?: return AutoAnswerRules()
    if (arr.length() > AutoAnswerRules.MAX_ALLOWED) return AutoAnswerRules()
    val ids = (0 until arr.length()).map { i -> (arr.opt(i) as? String)?.takeIf(DinnerWire::isDeviceId) ?: return AutoAnswerRules() }
    if (ids.toSet().size != ids.size) return AutoAnswerRules()
    if (enabled && !grandma) return AutoAnswerRules()
    return AutoAnswerRules(grandma, enabled, ids.toSet())
  }
}

/** Pure edits (each returns a new value; refused edits return the input unchanged). */
internal object AutoAnswerEdits {
  /** Turning Grandma mode off disarms auto-answer too, so switching it back on never re-arms it silently. */
  fun grandma(r: AutoAnswerRules, on: Boolean): AutoAnswerRules =
      if (on) r.copy(grandmaMode = true) else r.copy(grandmaMode = false, enabled = false)

  /** Auto-answer exists only inside Grandma mode. */
  fun enabled(r: AutoAnswerRules, on: Boolean): AutoAnswerRules =
      if (on && !r.grandmaMode) r else r.copy(enabled = on)

  fun allow(r: AutoAnswerRules, id: String, on: Boolean): AutoAnswerRules = when {
    !on -> r.copy(allowed = r.allowed - id)
    !DinnerWire.isDeviceId(id) || id in r.allowed -> r
    r.allowed.size >= AutoAnswerRules.MAX_ALLOWED -> r
    else -> r.copy(allowed = r.allowed + id)
  }

  /** Changes that make this Portal answer by itself need someone standing at it ([CallInPerson]). */
  fun arms(before: AutoAnswerRules, after: AutoAnswerRules): Boolean =
      (after.enabled && !before.enabled) || (after.allowed - before.allowed).isNotEmpty()
}

/**
 * Keeps the rules in an AES-GCM file sealed with a non-exportable Keystore key. The file lives in
 * `no_backup/` [AutoAnswerRepository.DIR], which [FleetSecretPaths] blocks from the fleet file
 * routes, outside SharedPreferences and the settings registry, so no phone, remote, fleet call or
 * other Portal can read or write it. Failures read as everything off.
 */
internal class AutoAnswerRepository(private val secure: YtSecureStore, private val warn: (String) -> Unit = { Log.w(TAG, it) }) {
  private val mutable = MutableStateFlow(load())
  val rules: StateFlow<AutoAnswerRules> = mutable.asStateFlow()

  private fun load(): AutoAnswerRules =
      runCatching { AutoAnswerCodec.decode(secure.load()) }.onFailure { warn("rules unreadable, all off") }
          .getOrDefault(AutoAnswerRules())

  /**
   * Applies [edit]. Arming changes (see [AutoAnswerEdits.arms]) need [inPerson]; disarming never
   * does. Returns false when refused or not stored.
   */
  @Synchronized
  fun update(inPerson: Boolean, edit: (AutoAnswerRules) -> AutoAnswerRules): Boolean {
    val before = mutable.value
    val after = edit(before)
    if (after == before) return true
    if (AutoAnswerEdits.arms(before, after) && !inPerson) return false
    val stored = runCatching { secure.save(AutoAnswerCodec.encode(after)) }
        .onFailure { warn("rules not stored") }.isSuccess
    if (stored) mutable.value = after
    return stored
  }

  companion object {
    private const val TAG = "HearthpaneGrandma"
    const val DIR = "hearthpane_grandma"
  }
}

/** This Portal's Grandma mode and auto-answer rules (on-device screens only). */
internal object CallAutoAnswerStore {
  private const val ALIAS = "hearthpane.calling.grandma"
  @Volatile private var repo: AutoAnswerRepository? = null

  fun repository(context: Context): AutoAnswerRepository = repo ?: synchronized(this) {
    repo ?: AutoAnswerRepository(YtKeystoreSecureStore(
        File(File(context.applicationContext.noBackupFilesDir, AutoAnswerRepository.DIR), "rules.bin"), ALIAS))
        .also { repo = it }
  }

  fun rules(context: Context): AutoAnswerRules = repository(context).rules.value

  fun flow(context: Context): StateFlow<AutoAnswerRules> = repository(context).rules

  /** For the on-device setup screen only; arming edits are refused unless [CallInPerson.confirmed]. */
  fun update(context: Context, edit: (AutoAnswerRules) -> AutoAnswerRules): Boolean =
      repository(context).update(CallInPerson.confirmed(Calling.now()), edit)
}
