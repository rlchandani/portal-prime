/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.youtube

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Encrypted persistence seam for the account file. The Android implementation
 * ([YtKeystoreSecureStore]) is an AES-GCM file under `noBackupFilesDir` keyed by the Android
 * Keystore — deliberately NOT SharedPreferences, which fleet backups copy. Implementations must
 * not log or echo [save]'s text.
 */
interface YtSecureStore {
  /** The stored plaintext, or null when nothing is stored. May throw if the key is gone. */
  fun load(): String?
  fun save(text: String)
  fun clear()
}

/** An account plus its refresh token. [toString] is redacted. */
internal data class YtStoredAccount(val account: YtAccount, val refreshToken: String) {
  override fun toString(): String = "YtStoredAccount(${account.id})"
}

/** Everything the secure store holds: the accounts and which one is active. */
internal data class YtAccountsSnapshot(val accounts: List<YtStoredAccount>, val activeId: String?) {
  val active: YtStoredAccount? get() = accounts.firstOrNull { it.account.id == activeId }

  override fun toString(): String = "YtAccountsSnapshot(${accounts.size} accounts, active=$activeId)"

  companion object {
    val EMPTY = YtAccountsSnapshot(emptyList(), null)
  }
}

/** JSON codec for [YtAccountsSnapshot]. Never throws; undecodable input → null. */
internal object YtAccountCodec {
  private const val VERSION = 1

  fun encode(snapshot: YtAccountsSnapshot): String {
    val list = JSONArray()
    snapshot.accounts.forEach { stored ->
      list.put(JSONObject()
          .put("id", stored.account.id)
          .put("title", stored.account.title)
          .put("thumb", stored.account.thumbnailUrl ?: JSONObject.NULL)
          .put("needsSignIn", stored.account.needsSignIn)
          .put("rt", stored.refreshToken))
    }
    return JSONObject().put("v", VERSION).put("active", snapshot.activeId ?: JSONObject.NULL)
        .put("accounts", list).toString()
  }

  fun decode(text: String): YtAccountsSnapshot? {
    val json = parseJson(text) ?: return null
    val list = json.optJSONArray("accounts") ?: return null
    val accounts = (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.let(::decodeAccount) }
    val active = json.optString("active").takeIf { id -> accounts.any { it.account.id == id } }
    return YtAccountsSnapshot(accounts, active ?: accounts.firstOrNull()?.account?.id)
  }

  private fun decodeAccount(json: JSONObject): YtStoredAccount? {
    val id = json.optString("id").ifEmpty { return null }
    val token = json.optString("rt").ifEmpty { return null }
    val thumb = if (json.isNull("thumb")) null else json.optString("thumb").ifEmpty { null }
    val account = YtAccount(id, json.optString("title"), thumb, json.optBoolean("needsSignIn"))
    return YtStoredAccount(account, token)
  }
}

/**
 * Multi-account token store: one refresh token per account plus the persisted active account.
 * Every change replaces the immutable snapshot, then persists it, then publishes it.
 */
internal class YtAccountStore(private val secure: YtSecureStore) {
  private val mutex = Mutex()
  private val state = MutableStateFlow(loadSafely())

  private val accountsState = MutableStateFlow(state.value.accounts.map { it.account })
  private val activeState = MutableStateFlow(state.value.active?.account)

  val snapshot: StateFlow<YtAccountsSnapshot> = state.asStateFlow()
  val accounts: StateFlow<List<YtAccount>> = accountsState.asStateFlow()
  val active: StateFlow<YtAccount?> = activeState.asStateFlow()

  fun refreshTokenFor(accountId: String): String? =
      state.value.accounts.firstOrNull { it.account.id == accountId }?.refreshToken

  /** Adds or replaces [account] (clearing needsSignIn) and makes it active. */
  suspend fun upsert(account: YtAccount, refreshToken: String) = update { current ->
    val stored = YtStoredAccount(account.copy(needsSignIn = false), refreshToken)
    val exists = current.accounts.any { it.account.id == account.id }
    val accounts = if (exists) current.accounts.map { if (it.account.id == account.id) stored else it }
    else current.accounts + stored
    YtAccountsSnapshot(accounts, account.id)
  }

  suspend fun markNeedsSignIn(accountId: String) = update { current ->
    current.copy(accounts = current.accounts.map {
      if (it.account.id == accountId) it.copy(account = it.account.copy(needsSignIn = true)) else it
    })
  }

  /** Returns false when [accountId] is unknown. */
  suspend fun setActive(accountId: String): Boolean {
    if (state.value.accounts.none { it.account.id == accountId }) return false
    update { current -> current.copy(activeId = accountId) }
    return true
  }

  /** Removes [accountId]; the next remaining account (if any) becomes active. Returns the removed entry. */
  suspend fun remove(accountId: String): YtStoredAccount? {
    val removed = state.value.accounts.firstOrNull { it.account.id == accountId } ?: return null
    update { current ->
      val remaining = current.accounts.filterNot { it.account.id == accountId }
      val active = current.activeId.takeIf { id -> remaining.any { it.account.id == id } }
      YtAccountsSnapshot(remaining, active ?: remaining.firstOrNull()?.account?.id)
    }
    return removed
  }

  private suspend fun update(change: (YtAccountsSnapshot) -> YtAccountsSnapshot) = mutex.withLock {
    val next = change(state.value)
    persist(next)
    state.value = next
    accountsState.value = next.accounts.map { it.account }
    activeState.value = next.active?.account
  }

  /**
   * A failed write (disk full, Keystore unavailable) keeps the change in memory for this process
   * rather than crashing the player; the exception is dropped because its message is unvetted.
   */
  private fun persist(snapshot: YtAccountsSnapshot) {
    try {
      if (snapshot.accounts.isEmpty()) secure.clear() else secure.save(YtAccountCodec.encode(snapshot))
    } catch (e: Exception) {
      persistFailures++
    }
  }

  /** Count of writes that failed to reach disk (diagnostics only). */
  @Volatile var persistFailures = 0
    private set

  /** A missing/corrupt/undecryptable file means "no accounts" — never a crash, never its contents in a message. */
  private fun loadSafely(): YtAccountsSnapshot =
      try {
        secure.load()?.let(YtAccountCodec::decode) ?: YtAccountsSnapshot.EMPTY
      } catch (e: Exception) {
        YtAccountsSnapshot.EMPTY
      }
}
