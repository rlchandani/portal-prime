/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.youtube

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * The one entry point the music UI and player use for official-YouTube data.
 *
 * **Sign-in** — [signIn] runs the OAuth device flow (scope `youtube.readonly`) and drives
 * [signInState]; cancel the calling coroutine to abandon it. [isConfigured] is false (and
 * [signIn] ends in [YtSignInState.NotConfigured]) when the build has no OAuth client.
 *
 * **Accounts** — [accounts] / [activeAccount] are persisted (encrypted, outside SharedPreferences
 * so fleet backups never see tokens). [switchAccount] changes the active account; [signOut]
 * revokes at Google and deletes locally even when offline. An account whose refresh token was
 * revoked stays listed with `needsSignIn = true` and its calls return [YtResult.NeedsSignIn].
 *
 * **Library** — [playlists], [playlistTracks], [likedSongs], [artists], [artistTracks] are
 * stale-while-refresh flows: a cached copy first (`isRefreshing = true` when older than 10 min),
 * then the network result. Cache ≤ 30 days, per account. Collect once per screen open.
 *
 * **Search** — [search] is for explicit submits only: ≥ 2 characters, 100 network calls per
 * Pacific day, 24 h result cache. [searchesRemainingToday] feeds the "resting" UI.
 *
 * **Queueing** — [playable] adds durations and drops tracks that can't be embedded.
 *
 * All results are [YtResult]s; nothing here throws for network/API problems or carries secrets.
 */
class YouTubeMusicLibrary internal constructor(
    client: YtOAuthClient,
    private val transport: YtHttpTransport,
    secureStore: YtSecureStore,
    cacheStore: YtCacheStore,
    private val clock: YtClock,
    private val delay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val io: CoroutineContext = Dispatchers.IO,
) {
  private val configured = client.isConfigured
  private val oauth = YtOAuthApi(transport, client)
  private val store = YtAccountStore(secureStore)
  private val tokens = YtAccessTokens(store, oauth, clock)
  private val library = YtLibraryApi(YtApiClient(transport, tokens))
  private val cache = YtLibraryCache(cacheStore, clock)
  private val searcher = YtSearch(library, cacheStore, clock)
  private val music = YtMusicFilter(library, cacheStore, clock)
  private val signInMutex = Mutex()
  private val signInFlow = MutableStateFlow<YtSignInState>(YtSignInState.Idle)

  /** True when this build carries an OAuth client id + secret. */
  val isConfigured: Boolean get() = configured

  val signInState: StateFlow<YtSignInState> = signInFlow.asStateFlow()
  val accounts: StateFlow<List<YtAccount>> = store.accounts
  val activeAccount: StateFlow<YtAccount?> = store.active

  /**
   * Runs the device flow to a terminal state (also published on [signInState]) and returns it.
   * On success the new account is stored and becomes active. A second concurrent call returns
   * the current state without starting another flow.
   */
  suspend fun signIn(): YtSignInState {
    if (!configured) return publish(YtSignInState.NotConfigured)
    if (!signInMutex.tryLock()) return signInFlow.value
    try {
      val flow = YtDeviceFlow(oauth, transport, clock, delay) { account, refreshToken, grant ->
        withContext(io) { store.upsert(account, refreshToken) }
        tokens.remember(account.id, grant)
      }
      return publish(flow.run { signInFlow.value = it })
    } finally {
      if (!signInFlow.value.isTerminal) signInFlow.value = YtSignInState.Idle
      signInMutex.unlock()
    }
  }

  /** Returns [signInState] to Idle after the UI has shown a terminal state. */
  fun resetSignIn() {
    if (!signInMutex.isLocked) signInFlow.value = YtSignInState.Idle
  }

  /** Makes [accountId] active (persisted). False when the account is unknown. */
  suspend fun switchAccount(accountId: String): Boolean = withContext(io) { store.setActive(accountId) }

  /**
   * Revokes the account's refresh token at Google, then deletes the token, the account and its
   * cached library locally — the local delete happens even when revoke fails (offline).
   * Returns true when Google confirmed the revoke.
   */
  suspend fun signOut(accountId: String): Boolean = withContext(io) {
    val token = store.refreshTokenFor(accountId) ?: return@withContext false
    val revoked = oauth.revoke(token)
    store.remove(accountId)
    tokens.invalidate(accountId)
    cache.deletePrefix(YtLibraryCache.accountPrefix(accountId))
    revoked
  }

  /** The active account's own playlists. */
  fun playlists(forceRefresh: Boolean = false): Flow<YtResult<YtLoaded<List<YtPlaylist>>>> =
      cached("playlists", YtLibraryCache.PLAYLISTS, forceRefresh) { library.myPlaylists(it) }

  /** Tracks of [playlistId], deleted/private videos removed. */
  fun playlistTracks(playlistId: String, forceRefresh: Boolean = false): Flow<YtResult<YtLoaded<List<YtTrack>>>> =
      cached("items.$playlistId", YtLibraryCache.TRACKS, forceRefresh) { library.playlistTracks(it, playlistId) }

  /** Liked songs: YouTube Music's `LM` list when available, else the channel's liked videos. */
  fun likedSongs(forceRefresh: Boolean = false): Flow<YtResult<YtLoaded<List<YtTrack>>>> =
      cached("liked", YtLibraryCache.TRACKS, forceRefresh) { library.likedTracks(it) }

  /** Subscribed channels, presented as artists. */
  fun artists(forceRefresh: Boolean = false): Flow<YtResult<YtLoaded<List<YtArtist>>>> =
      cached("artists", YtLibraryCache.ARTISTS, forceRefresh) { library.artists(it) }

  /** An artist channel's uploads. */
  fun artistTracks(channelId: String, forceRefresh: Boolean = false): Flow<YtResult<YtLoaded<List<YtTrack>>>> =
      cached("uploads.$channelId", YtLibraryCache.TRACKS, forceRefresh) { library.artistTracks(it, channelId) }

  /** Searches music videos for a submitted [query]; see the class doc for the budget rules. */
  suspend fun search(query: String): YtResult<YtSearchPage> =
      withContext(io) { searcher.search(usableAccountId(), query) }

  /** Network searches left today (resets at midnight Pacific). */
  fun searchesRemainingToday(): Int = searcher.remainingToday()

  /**
   * [tracks] with durations filled in and non-embeddable / unavailable videos removed, in order.
   * Costs one quota unit per 50 tracks (not the search budget).
   */
  suspend fun playable(tracks: List<YtTrack>): YtResult<List<YtTrack>> = withContext(io) {
    val accountId = usableAccountId() ?: return@withContext YtResult.NeedsSignIn
    if (tracks.isEmpty()) return@withContext YtResult.Ok(emptyList())
    library.videoFacts(accountId, tracks.map { it.videoId }).map { facts ->
      tracks.mapNotNull { track ->
        facts[track.videoId]?.takeIf { it.embeddable }?.let { track.copy(durationMs = it.durationMs ?: track.durationMs) }
      }
    }
  }

  /**
   * Whether each playlist is "mostly music" (see [YtMusicRule]): cached verdicts first, then the
   * rest as they're sampled (videos.list, 1 unit per 50 ids — never the search budget). A playlist
   * missing from the map is unknown and should be shown.
   */
  fun playlistVerdicts(playlists: List<YtPlaylist>): Flow<Map<String, Boolean>> {
    val accountId = usableAccountId() ?: return flowOf(emptyMap())
    return music.verdicts(accountId, playlists).flowOn(io)
  }

  /** [tracks] without the ones YouTube files under a non-Music category (unknown ones stay). */
  suspend fun musicOnly(tracks: List<YtTrack>): YtResult<List<YtTrack>> = withContext(io) {
    val accountId = usableAccountId() ?: return@withContext YtResult.NeedsSignIn
    music.categories(accountId, tracks.map { it.videoId })
        .map { categories -> tracks.filter { YtMusicRule.keepAsSong(categories[it.videoId]) } }
  }

  /** The active account's Hide/Show choices (removed when that account signs out). */
  suspend fun playlistChoices(): Map<String, YtPlaylistChoice> = withContext(io) {
    store.active.value?.id?.let(music::choices).orEmpty()
  }

  /** Hide or show [playlistId] on this Portal for the active account; null = automatic again. */
  suspend fun setPlaylistChoice(playlistId: String, choice: YtPlaylistChoice?) = withContext(io) {
    store.active.value?.id?.let { music.setChoice(it, playlistId, choice) }
    Unit
  }

  private fun usableAccountId(): String? = store.active.value?.takeUnless { it.needsSignIn }?.id

  private fun <T> cached(
      name: String,
      codec: YtCodec<T>,
      forceRefresh: Boolean,
      fetch: suspend (String) -> YtResult<T>,
  ): Flow<YtResult<YtLoaded<T>>> {
    val account = store.active.value ?: return flowOf(YtResult.NeedsSignIn)
    val key = YtLibraryCache.accountPrefix(account.id) + name
    return cache.stream(key, codec, forceRefresh) {
      if (account.needsSignIn) YtResult.NeedsSignIn else fetch(account.id)
    }.flowOn(io)
  }

  private fun publish(state: YtSignInState): YtSignInState {
    signInFlow.value = state
    return state
  }
}
