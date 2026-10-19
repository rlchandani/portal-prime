/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.net.URI
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Pure, bounded adapter for MA 2.10.7 (schema 65, music-assistant-models 1.1.205).
 *
 * Contract sources:
 * https://github.com/music-assistant/server/blob/2.10.7/music_assistant/controllers/players/controller.py
 * https://github.com/music-assistant/server/blob/2.10.7/music_assistant/controllers/player_queues/controller.py
 * https://pypi.org/project/music-assistant-models/1.1.205/
 *
 * A transport owns authentication, request correlation, partial-message assembly and refreshes.
 * This adapter never connects, stores credentials, changes state on an acknowledgement, or
 * assumes a queue ID equals the selected player ID. Pass complete command replies, not events.
 * Older/newer server compatibility is not claimed merely because a hello advertises a schema.
 */
object MaMusicProtocol {
  const val MAX_ROOMS = 64
  const val MAX_QUEUE_ITEMS = 500
  private const val MAX_ID = 256
  private const val MAX_TEXT = 1024
  private const val MAX_URL = 4096
  private const val MAX_MESSAGE = 2_000_000
  private const val MAX_DEPTH = 32

  sealed class Reply<out T> {
    data class Success<T>(val value: T) : Reply<T>()
    data class Failure(
        val connection: MusicConnection,
        val problem: String,
        val errorCode: Int? = null,
    ) : Reply<Nothing>()
  }

  @ConsistentCopyVisibility
  data class Source internal constructor(
      val id: String,
      val playPause: Boolean,
      val seek: Boolean,
      val nextPrevious: Boolean,
  )

  @ConsistentCopyVisibility
  data class Media internal constructor(
      val id: String,
      val title: String,
      val artist: String,
      val album: String,
      val artUrl: String,
      val durationMs: Long?,
      val mediaType: String,
      val sourceId: String?,
  ) {
    internal fun track(sourceId: String) = MusicTrack(
        id, sourceId, title, artist, album, artUrl, durationMs, mediaType == "radio")
  }

  @ConsistentCopyVisibility
  data class Player internal constructor(
      val playerId: String,
      val name: String,
      val available: Boolean,
      val playback: PlaybackState,
      val features: Set<String>,
      val activeSource: String?,
      val activeGroup: String?,
      val syncedTo: String?,
      val groupMembers: Set<String>,
      val staticGroupMembers: Set<String>,
      val canGroupWith: Set<String>,
      val sources: List<Source>,
      val volume: Int?,
      val muted: Boolean?,
      val volumeControl: String,
      val elapsedMs: Long,
      val media: Media?,
      val elapsedUpdatedUnixMs: Long?,
  ) {
    internal val canSetVolume: Boolean
      get() = available && "volume_set" in features && volumeControl != "none"
  }

  @ConsistentCopyVisibility
  data class QueueEntry internal constructor(
      val queueId: String,
      val item: MusicQueueItem,
      val available: Boolean,
      val mediaType: String,
      val album: String,
  ) {
    internal fun track(sourceId: String) = MusicTrack(item.id, sourceId, item.title,
        item.artist, album, item.artUrl, item.durationMs, mediaType == "radio")
  }

  @ConsistentCopyVisibility
  data class Queue internal constructor(
      val queueId: String,
      val available: Boolean,
      val active: Boolean,
      val itemCount: Int,
      val playback: PlaybackState,
      val elapsedMs: Long,
      val currentIndex: Int?,
      val current: QueueEntry?,
      val next: QueueEntry?,
      val repeatAll: Boolean,
      val elapsedUpdatedUnixMs: Long?,
      val playbackSpeed: Double,
  )

  /** The queue ID accompanies even an empty page, so a stale empty reply cannot change queues. */
  @ConsistentCopyVisibility
  data class QueueItems internal constructor(val queueId: String, val items: List<QueueEntry>)

  /** Keep this alongside its snapshot when building commands for the same observed selection. */
  class Selection internal constructor(
      val snapshot: MusicPlayerSnapshot,
      val queueId: String? = null,
      internal val controlPlayer: Player? = null,
      internal val players: List<Player> = emptyList(),
      internal val selectableItems: Set<String> = emptySet(),
      internal val queueTransport: Boolean = false,
  )

  fun parsePlayers(json: String, expectedMessageId: String? = null): Reply<List<Player>> =
      parse(json, expectedMessageId) { value ->
        val array = value as? JSONArray ?: bad()
        val result = LinkedHashMap<String, Player>()
        for (index in 0 until array.length()) {
          val p = readPlayer(array.opt(index) as? JSONObject ?: bad())
          result.putIfAbsent(p.playerId, p)
          if (result.size == MAX_ROOMS) break
        }
        result.values.toList()
      }

  /** Accepts the result of player_queues/get or player_queues/get_active_queue. */
  fun parseQueue(json: String, expectedMessageId: String? = null): Reply<Queue?> =
      parse(json, expectedMessageId) { value ->
        if (value == JSONObject.NULL) null else {
          val q = value as? JSONObject ?: bad()
          val id = q.requiredId("queue_id")
          q.requiredText("display_name")
          q.boolean("shuffle_enabled", false)
          val elapsedUpdated = q.timestampMs("elapsed_time_last_updated", nullable = false)
          val speed = q.number("playback_speed", nullable = false) ?: 1.0
          require(speed in 0.5..3.0)
          val count = q.integer("items", required = true)!!
          require(count >= 0)
          val currentIndex = q.integer("current_index")
          require(currentIndex == null || currentIndex in 0 until count)
          val current = q.obj("current_item")?.let { readEntry(it, id) }
          val next = q.obj("next_item")?.let { readEntry(it, id) }
          require(count > 0 || (current == null && next == null))
          val repeat = if (q.has("repeat_mode")) q.requiredText("repeat_mode") else "off"
          require(repeat in setOf("off", "one", "all"))
          Queue(id, q.boolean("available"), q.boolean("active"), count,
              playback(q.requiredText("state")), q.secondsMs("elapsed_time", nullable = false) ?: 0,
              currentIndex, current, next, repeat == "all", elapsedUpdated, speed)
        }
      }

  fun parseQueueItems(
      json: String,
      queueId: String,
      expectedMessageId: String? = null,
  ): Reply<QueueItems> = parse(json, expectedMessageId) { value ->
    require(validId(queueId))
    val array = value as? JSONArray ?: bad()
    val result = LinkedHashMap<String, QueueEntry>()
    for (index in 0 until array.length()) {
      val entry = readEntry(array.opt(index) as? JSONObject ?: bad(), queueId)
      result.putIfAbsent(entry.item.id, entry)
      if (result.size == MAX_QUEUE_ITEMS) break
    }
    QueueItems(queueId, result.values.toList())
  }

  /** Only the current server's explicit authenticated:true is an auth success. */
  fun parseAuthReply(json: String, expectedMessageId: String? = null): Reply<Unit> {
    val result = parse(json, expectedMessageId) { value ->
      (value as? JSONObject ?: bad()).boolean("authenticated")
    }
    return when (result) {
      is Reply.Failure -> result
      is Reply.Success -> if (result.value) Reply.Success(Unit) else
        Reply.Failure(MusicConnection.AUTH_REQUIRED, "Music Assistant authentication required")
    }
  }

  /** The transport/group/volume commands supported here return None (JSON null). */
  fun parseCommandReply(json: String, expectedMessageId: String? = null): Reply<Unit> =
      parse(json, expectedMessageId) { require(it == JSONObject.NULL); Unit }

  /**
   * Select a canonical ma:<player-id> using independently parsed, correlated replies.
   * A queue must match the resolved player's active_source (or its ID when source is null).
   * Authentication acknowledgements and socket hellos cannot make this selection READY.
   * Supply both observation clocks for corrected position; without Unix time the server's
   * reported position is retained. The shared snapshot currently extrapolates only at 1x.
   */
  fun select(
      sourceId: String,
      players: Reply<List<Player>>,
      queue: Reply<Queue?>? = null,
      items: Reply<QueueItems>? = null,
      observedAtElapsedMs: Long = 0,
      observedAtUnixMs: Long? = null,
  ): Selection {
    fun fail(connection: MusicConnection, problem: String) = Selection(
        MusicPlayerSnapshot(connection = connection, sourceId = sourceId, problem = problem))
    val rawId = rawPlayerId(sourceId)
        ?: return fail(MusicConnection.INCOMPATIBLE, "Invalid Music Assistant source")
    for (reply in listOf(players, queue, items)) {
      if (reply is Reply.Failure) return fail(reply.connection, reply.problem)
    }
    val list = (players as Reply.Success).value.asSequence()
        .distinctBy { it.playerId }.take(MAX_ROOMS).toList()
    val byId = list.associateBy { it.playerId }
    val selected = byId[rawId]
        ?: return fail(MusicConnection.OFFLINE, "Music Assistant player is unavailable")
    if (!selected.available) return fail(MusicConnection.OFFLINE, "Music Assistant player is unavailable")
    var control = selected
    val visited = HashSet<String>()
    while (true) {
      if (!visited.add(control.playerId)) {
        return fail(MusicConnection.INCOMPATIBLE, "Invalid Music Assistant group")
      }
      // Matches the released server's get_active_queue redirect ordering.
      val leader = control.syncedTo?.takeIf { it != control.playerId }
          ?: control.activeGroup?.takeIf { it != control.playerId } ?: break
      control = byId[leader]
          ?: return fail(MusicConnection.INCOMPATIBLE, "Music Assistant group leader is unavailable")
    }
    if (!control.available) return fail(MusicConnection.OFFLINE, "Music Assistant group is unavailable")
    val q = (queue as? Reply.Success)?.value
    val page = (items as? Reply.Success)?.value
    if (q != null && q.queueId != (control.activeSource ?: control.playerId)) {
      return fail(MusicConnection.INCOMPATIBLE, "Music Assistant queue belongs to another source")
    }
    if (page != null && (q == null || page.queueId != q.queueId)) {
      return fail(MusicConnection.INCOMPATIBLE, "Music Assistant queue page belongs to another source")
    }
    if (q != null && !q.available) return fail(MusicConnection.OFFLINE, "Music Assistant queue is unavailable")
    val source = control.sources.firstOrNull { it.id == control.activeSource }
    val queueOwnsPlayback = q != null &&
        (q.active || control.playback == PlaybackState.IDLE)
    val current = if (queueOwnsPlayback) q?.current else null
    val confirmedPlayerArt = control.media?.takeIf { media ->
      current != null && media.id == current.item.id &&
          (media.sourceId == null || media.sourceId == q?.queueId)
    }?.artUrl.orEmpty()
    val queueTrack = current?.track(sourceId)?.let { track ->
      if (track.artUrl.isBlank() && confirmedPlayerArt.isNotBlank()) {
        track.copy(artUrl = confirmedPlayerArt)
      } else track
    }
    val track = queueTrack ?: if (!queueOwnsPlayback) control.media?.track(sourceId) else null
    val queueTransport = queueOwnsPlayback &&
        current?.mediaType !in setOf("audio_source", "plugin_source")
    val currentAvailable = current?.available == true
    val finite = track != null && !track.isLive && (track.durationMs ?: 0) > 0
    val canGroup = "set_members" in control.features && list.any {
      it.available && it.playerId != control.playerId &&
          (it.playerId in control.canGroupWith ||
              (it.playerId in control.groupMembers && it.playerId !in control.staticGroupMembers))
    }
    val caps = MusicCapabilities(
        play = if (queueTransport) q!!.itemCount > 0 else source?.playPause == true,
        pause = if (queueTransport) q!!.active && currentAvailable && "pause" in control.features else
          source?.playPause == true && "pause" in control.features,
        next = if (queueTransport) q!!.active && currentAvailable && q.currentIndex != null &&
          (q.currentIndex < q.itemCount - 1 || q.next?.available == true || q.repeatAll) else
          source?.nextPrevious == true && "next_previous" in control.features,
        previous = if (queueTransport) q!!.active && currentAvailable && q.currentIndex != null &&
          (q.currentIndex > 0 || q.elapsedMs >= 5_000) else
          source?.nextPrevious == true && "next_previous" in control.features,
        seek = finite && if (queueTransport) q!!.active && currentAvailable &&
          q.currentIndex != null && current.mediaType in setOf("track", "audiobook", "podcast_episode") else
          source?.seek == true && "seek" in control.features,
        queue = queueOwnsPlayback,
        rooms = canGroup,
        volume = list.any { it.canSetVolume },
    )
    val rooms = list.map { p ->
      val groupId = p.syncedTo?.takeIf { it != p.playerId }
          ?: p.activeGroup?.takeIf { it != p.playerId }
          ?: p.playerId.takeIf { p.groupMembers.any { member -> member != p.playerId } }
      MusicRoom("ma:${p.playerId}", p.name, p.available, p.volume, p.muted,
          groupId?.let { "ma:$it" }, p.canGroupWith.filter { it in byId }
              .map { "ma:$it" }.toSet(), volumeControllable = p.canSetVolume)
    }
    val state = if (queueOwnsPlayback) q!!.playback else control.playback
    val reportedPosition = if (queueOwnsPlayback) q!!.elapsedMs else control.elapsedMs
    val updatedUnixMs = if (queueOwnsPlayback) q!!.elapsedUpdatedUnixMs else control.elapsedUpdatedUnixMs
    val speed = if (queueOwnsPlayback) q!!.playbackSpeed else 1.0
    val position = correctedPosition(reportedPosition, state, updatedUnixMs, observedAtUnixMs, speed)
    val snapshot = MusicPlayerSnapshot(
        connection = MusicConnection.READY, sourceId = sourceId, track = track,
        playback = state,
        positionMs = if (finite) position.coerceAtMost(track!!.durationMs!!) else position,
        positionAtElapsedMs = observedAtElapsedMs.coerceAtLeast(0), capabilities = caps,
        queue = if (queueOwnsPlayback) page?.items?.map { entry ->
          if (entry.item.id == current?.item?.id && entry.item.artUrl.isBlank() &&
              confirmedPlayerArt.isNotBlank()) entry.item.copy(artUrl = confirmedPlayerArt)
          else entry.item
        } ?: emptyList() else emptyList(),
        rooms = rooms,
    )
    return Selection(snapshot, q?.queueId, control, list,
        if (queueOwnsPlayback) page?.items?.filter { it.available }?.map { it.item.id }?.toSet()
            ?: emptySet() else emptySet(), queueTransport)
  }

  fun playersCommand(messageId: String): JSONObject? = command(messageId, "players/all", JSONObject())

  fun activeQueueCommand(messageId: String, sourceId: String): JSONObject? {
    val id = rawPlayerId(sourceId) ?: return null
    return command(messageId, "player_queues/get_active_queue", JSONObject().put("player_id", id))
  }

  fun queueStateCommand(messageId: String, queueId: String): JSONObject? =
      if (!validId(queueId)) null else command(messageId, "player_queues/get",
          JSONObject().put("queue_id", queueId))

  fun queueItemsCommand(
      messageId: String,
      queueId: String,
      limit: Int = MAX_QUEUE_ITEMS,
      offset: Int = 0,
  ): JSONObject? = if (!validId(queueId) || limit !in 1..MAX_QUEUE_ITEMS || offset < 0) null else
    command(messageId, "player_queues/items",
        JSONObject().put("queue_id", queueId).put("limit", limit).put("offset", offset))

  fun transportCommand(messageId: String, selection: Selection, action: MusicCommand): JSONObject? {
    val name = when (action) {
      MusicCommand.PLAY -> "play"
      MusicCommand.PAUSE -> "pause"
      MusicCommand.NEXT -> "next"
      MusicCommand.PREVIOUS -> "previous"
      else -> return null
    }
    if (!selection.snapshot.can(action)) return null
    return playbackCommand(messageId, selection, name)
  }

  fun seekCommand(messageId: String, selection: Selection, positionMs: Long): JSONObject? {
    if (!selection.snapshot.can(MusicCommand.SEEK)) return null
    val duration = selection.snapshot.track?.durationMs ?: return null
    if (positionMs !in 0..duration || positionMs / 1000 > Int.MAX_VALUE) return null
    return playbackCommand(messageId, selection, "seek",
        JSONObject().put("position", (positionMs / 1000).toInt()))
  }

  fun queueItemCommand(messageId: String, selection: Selection, queueItemId: String): JSONObject? {
    if (!selection.snapshot.can(MusicCommand.SELECT_QUEUE_ITEM) ||
        queueItemId !in selection.selectableItems) return null
    return command(messageId, "player_queues/play_index",
        JSONObject().put("queue_id", selection.queueId).put("index", queueItemId))
  }

  /** Per-room volume addresses that player directly, never the queue or sync leader. */
  fun volumeCommand(
      messageId: String,
      selection: Selection,
      roomId: String,
      volume: Int,
  ): JSONObject? {
    if (!selection.snapshot.can(MusicCommand.SET_VOLUME) || volume !in 0..100) return null
    val id = rawPlayerId(roomId) ?: return null
    if (selection.players.none { it.playerId == id && it.canSetVolume }) return null
    return command(messageId, "players/cmd/volume_set",
        JSONObject().put("player_id", id).put("volume_level", volume))
  }

  /** Build a delta; a successful acknowledgement still needs a refreshed membership snapshot. */
  fun groupCommand(
      messageId: String,
      selection: Selection,
      add: Set<String> = emptySet(),
      remove: Set<String> = emptySet(),
  ): JSONObject? {
    if (!selection.snapshot.can(MusicCommand.CHANGE_ROOMS) ||
        add.size + remove.size !in 1..MAX_ROOMS || add.any { it in remove }) return null
    val target = selection.controlPlayer ?: return null
    if ("set_members" !in target.features) return null
    val additions = add.map { rawPlayerId(it) ?: return null }
    val removals = remove.map { rawPlayerId(it) ?: return null }
    val available = selection.players.filter { it.available }.map { it.playerId }.toSet()
    if (additions.any { it == target.playerId || it !in available || it !in target.canGroupWith } ||
        removals.any { it == target.playerId || it !in available ||
          it !in target.groupMembers || it in target.staticGroupMembers }) return null
    return command(messageId, "players/cmd/set_members", JSONObject()
        .put("target_player", target.playerId)
        .put("player_ids_to_add", JSONArray(additions))
        .put("player_ids_to_remove", JSONArray(removals)))
  }

  private fun playbackCommand(
      messageId: String,
      selection: Selection,
      name: String,
      args: JSONObject = JSONObject(),
  ): JSONObject? {
    if (selection.queueTransport && selection.queueId != null) {
      return command(messageId, "player_queues/$name", args.put("queue_id", selection.queueId))
    }
    val id = selection.controlPlayer?.playerId ?: return null
    return command(messageId, "players/cmd/$name", args.put("player_id", id))
  }

  private fun command(messageId: String, name: String, args: JSONObject): JSONObject? =
      if (!validId(messageId)) null else JSONObject().put("message_id", messageId)
          .put("command", name).put("args", args)

  private fun readPlayer(p: JSONObject): Player {
    val id = p.requiredId("player_id")
    // The released model emits these aliases; a bad canonical value never falls back to an alias.
    val name = if (p.has("name")) p.requiredText("name") else p.requiredText("display_name")
    if (p.has("display_name")) p.requiredText("display_name")
    val state = if (p.has("playback_state")) p.requiredText("playback_state") else p.requiredText("state")
    if (p.has("state")) playback(p.requiredText("state"))
    val enabled = p.boolean("enabled", true)
    val hiddenInUi = p.boolean("hide_in_ui", false)
    val private = p.boolean("private", false)
    val hidden = hiddenInUi || private
    val elapsedUpdated = p.timestampMs("elapsed_time_last_updated")
    if (p.has("provider")) p.requiredText("provider")
    if (p.has("type")) p.requiredText("type")
    val features = p.strings("supported_features", MAX_ROOMS)
    return Player(id, name, p.boolean("available") && enabled && !hidden,
        playback(state), features, p.optionalId("active_source"), p.optionalId("active_group"),
        p.optionalId("synced_to"), p.strings("group_members", MAX_ROOMS),
        p.strings("static_group_members", MAX_ROOMS), p.strings("can_group_with", MAX_ROOMS),
        p.array("source_list")?.let { array ->
          val sources = LinkedHashMap<String, Source>()
          for (index in 0 until array.length()) {
            val s = array.opt(index) as? JSONObject ?: bad()
            val sid = s.requiredId("id")
            s.requiredText("name")
            s.boolean("passive", false)
            s.boolean("can_shuffle", false)
            s.boolean("can_repeat", false)
            sources.putIfAbsent(sid, Source(sid, s.boolean("can_play_pause", false),
                s.boolean("can_seek", false), s.boolean("can_next_previous", false)))
            if (sources.size == MAX_ROOMS) break
          }
          sources.values.toList()
        } ?: emptyList(),
        p.integer("volume_level")?.also { require(it in 0..100) },
        p.optionalBoolean("volume_muted"),
        if (p.has("volume_control")) p.requiredText("volume_control") else "none",
        p.secondsMs("elapsed_time") ?: 0, p.obj("current_media")?.let(::readMedia), elapsedUpdated)
  }

  private fun readMedia(m: JSONObject): Media? {
    val uri = m.rawText("uri")?.also { require(it.length <= MAX_URL) }
    val queueItem = m.optionalId("queue_item_id")
    val sourceId = m.optionalId("source_id")
    val title = m.text("title") ?: ""
    val artist = m.text("artist") ?: ""
    val album = m.text("album") ?: ""
    val artUrl = safeUrl(m.rawText("image_url"))
    val duration = m.secondsMs("duration", integral = true)?.takeIf { it > 0 }
    val type = m.text("media_type") ?: "unknown"
    if (title.isBlank() || (queueItem ?: uri).isNullOrBlank()) return null
    return Media(queueItem ?: uri!!, title, artist, album, artUrl, duration, type, sourceId)
  }

  private fun readEntry(q: JSONObject, expectedQueueId: String): QueueEntry {
    val queueId = q.requiredId("queue_id")
    require(queueId == expectedQueueId)
    val id = q.requiredId("queue_item_id")
    val name = q.requiredText("name")
    val available = q.boolean("available", true)
    q.integer("index")?.also { require(it >= 0) }
    val duration = q.secondsMs("duration", integral = true)?.takeIf { it > 0 }
    val media = q.obj("media_item")
    val stream = q.obj("streamdetails")
    val title = media?.let { if (it.has("name")) it.requiredText("name") else null } ?: name
    val artists = media?.array("artists")?.let { array ->
      val names = ArrayList<String>()
      for (index in 0 until minOf(array.length(), MAX_ROOMS)) {
        names.add((array.opt(index) as? JSONObject ?: bad()).requiredText("name"))
      }
      names.joinToString(" / ").take(MAX_TEXT)
    } ?: ""
    val album = media?.obj("album")?.text("name") ?: ""
    val type = media?.text("media_type") ?: stream?.text("media_type") ?: "unknown"
    val image = q.obj("image")
    val path = image?.rawText("path")
    image?.text("provider")
    image?.text("type")
    image?.text("proxy_id", MAX_ID)
    val remote = image?.boolean("remotely_accessible", false) == true
    val art = if (remote) safeUrl(path) else ""
    return QueueEntry(queueId, MusicQueueItem(id, title, artists, duration, art), available, type, album)
  }

  private fun playback(value: String): PlaybackState = when (value) {
    "playing" -> PlaybackState.PLAYING
    "paused" -> PlaybackState.PAUSED
    "idle" -> PlaybackState.IDLE
    else -> bad()
  }

  private fun <T> parse(
      json: String,
      expectedMessageId: String?,
      read: (Any) -> T,
  ): Reply<T> = try {
    checkJsonBounds(json)
    val tokener = JSONTokener(json)
    val message = tokener.nextValue() as? JSONObject ?: bad()
    require(tokener.nextClean() == '\u0000')
    val messageId = message.requiredId("message_id")
    require(expectedMessageId == null || messageId == expectedMessageId)
    val partial = message.boolean("partial", false)
    if (message.has("error_code")) {
      val code = message.integer("error_code", required = true)!!
      // Server detail strings may contain credentials/URLs. Publish only fixed local text.
      message.text("details")
      when (code) {
        20, 21, 23 -> Reply.Failure(MusicConnection.AUTH_REQUIRED,
            "Music Assistant authentication required", code)
        22 -> Reply.Failure(MusicConnection.INCOMPATIBLE,
            "Music Assistant denied permission", code)
        else -> Reply.Failure(MusicConnection.INCOMPATIBLE,
            "Music Assistant rejected the request", code)
      }
    } else {
      require(!partial && message.has("result"))
      Reply.Success(read(message.get("result")))
    }
  } catch (_: IllegalArgumentException) {
    Reply.Failure(MusicConnection.INCOMPATIBLE, "Invalid Music Assistant reply")
  } catch (_: JSONException) {
    Reply.Failure(MusicConnection.INCOMPATIBLE, "Invalid Music Assistant reply")
  }

  private fun JSONObject.text(key: String, max: Int = MAX_TEXT): String? {
    return rawText(key)?.take(max)
  }

  private fun JSONObject.rawText(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return opt(key) as? String ?: bad()
  }

  private fun JSONObject.requiredText(key: String): String =
      text(key)?.takeIf { it.isNotBlank() } ?: bad()

  private fun JSONObject.requiredId(key: String): String =
      optionalId(key) ?: bad()

  private fun JSONObject.optionalId(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = opt(key) as? String ?: bad()
    require(validId(value))
    return value
  }

  private fun JSONObject.optionalBoolean(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    return opt(key) as? Boolean ?: bad()
  }

  private fun JSONObject.boolean(key: String, default: Boolean? = null): Boolean {
    if (!has(key)) return default ?: bad()
    return opt(key) as? Boolean ?: bad()
  }

  private fun JSONObject.integer(key: String, required: Boolean = false): Int? {
    if (!has(key) || isNull(key)) {
      require(!required)
      return null
    }
    val value = opt(key) as? Number ?: bad()
    val number = value.toDouble()
    require(number.isFinite() && number >= Int.MIN_VALUE && number <= Int.MAX_VALUE &&
        number == value.toInt().toDouble())
    return value.toInt()
  }

  private fun JSONObject.secondsMs(
      key: String,
      integral: Boolean = false,
      nullable: Boolean = true,
  ): Long? {
    val value = number(key, nullable) ?: return null
    require(value.isFinite() && value >= 0 && value <= Int.MAX_VALUE &&
        (!integral || value == value.toLong().toDouble()))
    return (value * 1000).toLong()
  }

  private fun JSONObject.number(key: String, nullable: Boolean = true): Double? {
    if (!has(key)) return null
    if (isNull(key)) {
      require(nullable)
      return null
    }
    val value = (opt(key) as? Number ?: bad()).toDouble()
    require(value.isFinite())
    return value
  }

  private fun JSONObject.timestampMs(key: String, nullable: Boolean = true): Long? {
    val value = number(key, nullable) ?: return null
    require(value >= 0 && value <= 253_402_300_799.0)
    return (value * 1000).toLong()
  }

  private fun JSONObject.obj(key: String): JSONObject? {
    if (!has(key) || isNull(key)) return null
    return opt(key) as? JSONObject ?: bad()
  }

  private fun JSONObject.array(key: String): JSONArray? {
    if (!has(key)) return null
    return opt(key) as? JSONArray ?: bad()
  }

  private fun JSONObject.strings(key: String, limit: Int): Set<String> {
    val array = array(key) ?: return emptySet()
    val result = LinkedHashSet<String>()
    for (index in 0 until array.length()) {
      val value = array.opt(index) as? String ?: bad()
      require(validId(value))
      result.add(value)
      if (result.size == limit) break
    }
    return result.toSet()
  }

  private fun validId(value: String): Boolean = value.length in 1..MAX_ID &&
      value.isNotBlank() && value == value.trim() && value.none { it.code < 32 || it.code == 127 }

  private fun rawPlayerId(sourceId: String): String? = sourceId.takeIf { it.startsWith("ma:") }
      ?.removePrefix("ma:")?.takeIf(::validId)

  private fun safeUrl(value: String?): String {
    if (value.isNullOrBlank() || value.length > MAX_URL) return ""
    return try {
      val uri = URI(value)
      if (uri.scheme?.lowercase() in setOf("http", "https") &&
          !uri.host.isNullOrBlank() && uri.userInfo == null) value else ""
    } catch (_: IllegalArgumentException) { "" }
      catch (_: java.net.URISyntaxException) { "" }
  }

  private fun correctedPosition(
      reportedMs: Long,
      state: PlaybackState,
      updatedUnixMs: Long?,
      observedUnixMs: Long?,
      speed: Double,
  ): Long {
    if (state != PlaybackState.PLAYING || updatedUnixMs == null ||
        observedUnixMs == null || observedUnixMs <= updatedUnixMs) return reportedMs
    val corrected = reportedMs.toDouble() + (observedUnixMs - updatedUnixMs).toDouble() * speed
    return corrected.coerceAtMost(Int.MAX_VALUE.toDouble() * 1000).toLong()
  }

  private enum class JsonState {
    ROOT_VALUE, ROOT_DONE, OBJECT_START, OBJECT_KEY, OBJECT_COLON, OBJECT_VALUE, OBJECT_END,
    ARRAY_START, ARRAY_VALUE, ARRAY_END,
  }

  /**
   * Bound allocation/recursion before org.json sees the payload. Reject non-JSON tokens which
   * Android's permissive JSONTokener otherwise accepts (single quotes, NaN, unquoted strings).
   */
  private fun checkJsonBounds(json: String) {
    require(json.length in 2..MAX_MESSAGE)
    val states = arrayListOf(JsonState.ROOT_VALUE)
    var index = 0
    fun value() {
      states[states.lastIndex] = when (states.last()) {
        JsonState.ROOT_VALUE -> JsonState.ROOT_DONE
        JsonState.OBJECT_VALUE -> JsonState.OBJECT_END
        JsonState.ARRAY_START, JsonState.ARRAY_VALUE -> JsonState.ARRAY_END
        else -> bad()
      }
    }
    while (index < json.length) {
      val c = json[index]
      when (c) {
        ' ', '\r', '\n', '\t' -> { index++; continue }
        '"' -> {
          index++
          var closed = false
          while (index < json.length) {
            val s = json[index++]
            if (s == '"') { closed = true; break }
            require(s.code >= 32)
            if (s == '\\') {
              require(index < json.length)
              val escape = json[index++]
              require(escape in "\"\\/bfnrtu")
              if (escape == 'u') {
                require(index + 4 <= json.length &&
                    json.substring(index, index + 4).all { it.digitToIntOrNull(16) != null })
                index += 4
              }
            }
          }
          require(closed)
          if (states.last() in setOf(JsonState.OBJECT_START, JsonState.OBJECT_KEY)) {
            states[states.lastIndex] = JsonState.OBJECT_COLON
          } else value()
          continue
        }
        '{', '[' -> {
          value()
          states.add(if (c == '{') JsonState.OBJECT_START else JsonState.ARRAY_START)
          require(states.size <= MAX_DEPTH + 1)
        }
        '}', ']' -> {
          require(states.size > 1)
          require(if (c == '}') states.last() in setOf(JsonState.OBJECT_START, JsonState.OBJECT_END)
              else states.last() in setOf(JsonState.ARRAY_START, JsonState.ARRAY_END))
          states.removeAt(states.lastIndex)
        }
        ':' -> {
          require(states.last() == JsonState.OBJECT_COLON)
          states[states.lastIndex] = JsonState.OBJECT_VALUE
        }
        ',' -> {
          states[states.lastIndex] = when (states.last()) {
            JsonState.OBJECT_END -> JsonState.OBJECT_KEY
            JsonState.ARRAY_END -> JsonState.ARRAY_VALUE
            else -> bad()
          }
        }
        else -> {
          val start = index
          while (index < json.length && json[index] !in " \r\n\t,]}:") index++
          val token = json.substring(start, index)
          require(token in setOf("true", "false", "null") ||
              (token.length <= 128 && JSON_NUMBER.matches(token)))
          value()
          continue
        }
      }
      index++
    }
    require(states.size == 1 && states.single() == JsonState.ROOT_DONE)
  }

  private val JSON_NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
  private fun bad(): Nothing = throw IllegalArgumentException("Invalid MA data")
}
