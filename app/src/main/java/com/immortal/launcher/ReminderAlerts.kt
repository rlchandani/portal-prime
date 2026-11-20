/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Announces due reminders on THIS Portal (every Portal runs its own, from the same replica):
 * a Home banner, plus a chime only per [ReminderDue.decide] — never in quiet hours, and for a
 * person's reminder only where that person is recognised. A person who walks up while their
 * banner is still showing gets the chime then. What was announced is remembered per due moment
 * (in prefs), so a restart doesn't ring again. Main thread only.
 */
internal object ReminderAlerts {
  private const val TAG = "HearthpaneReminders"
  private const val PREFS = "hearthpane_reminder_alerts"
  private const val KEY = "announced"
  /** A banner nobody acts on hides after this long. */
  const val SHOW_MS = 10 * 60_000L
  /** Re-check at least this often (clock changes, a missed post while dozing). */
  private const val MAX_WAIT_MS = 5 * 60_000L

  data class Alert(val reminderId: String, val shownAtMs: Long, val chimed: Boolean)

  private val main by lazy { Handler(Looper.getMainLooper()) }
  private val mutable = MutableStateFlow<Alert?>(null)
  val alert: StateFlow<Alert?> = mutable.asStateFlow()
  private val queue = ArrayDeque<String>()
  private var announced: Set<String> = emptySet()
  private var app: Context? = null
  private val tick = Runnable { check() }

  fun start(context: Context) = main.post {
    if (app != null) return@post
    val c = context.applicationContext
    app = c
    announced = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty().toSet()
    check()
  }

  /** Store listener: a new or edited due time re-arms; a reminder done elsewhere clears its banner. */
  fun onChanged() = main.post { check() }

  /** Who's there? changed: the owner may have just walked up to a silent banner. */
  fun onWhoChanged(ui: WhosThereUi) = main.post {
    val c = app ?: return@post
    val a = mutable.value ?: return@post
    if (a.chimed) return@post
    val r = ReminderStore.current(c).reminders[a.reminderId] ?: return@post
    if (decision(c, r, ui).sound) {
      mutable.value = a.copy(chimed = true)
      sound(c, r, ui)
    }
  }

  fun dismiss() = main.post { advance() }

  /** Any thread but main (writes the store). */
  fun markDone(context: Context, id: String) {
    RemindersFeature.setDone(context, id, true)
    main.post { if (mutable.value?.reminderId == id) advance() }
  }

  /** Whether the banner may show [r]'s title right now. */
  fun showsTitle(r: Reminder, ui: WhosThereUi): Boolean = ReminderDue.decide(r, false, ownerHere(r, ui), ui.hidePrivate).showTitle

  private fun check() {
    val c = app ?: return
    main.removeCallbacks(tick)
    val now = ReminderStore.clock()
    val s = ReminderStore.current(c)
    // A shown or queued reminder that was done, deleted or rescheduled elsewhere goes away.
    queue.removeAll { id -> s.reminders[id]?.open != true }
    mutable.value?.let { a ->
      val r = s.reminders[a.reminderId]
      if (r == null || !r.open || now - a.shownAtMs > SHOW_MS) advance()
    }
    val due = ReminderDue.dueNow(s, now, announced)
    if (due.isNotEmpty()) {
      remember(c, s, announced + due.map(ReminderDue::key))
      due.forEach { if (it.id !in queue && mutable.value?.reminderId != it.id) queue.addLast(it.id) }
      if (mutable.value == null) advance()
    }
    val next = ReminderDue.nextDue(s, now)
    val wait = ((next ?: Long.MAX_VALUE) - now).coerceIn(1_000L, MAX_WAIT_MS)
    main.postDelayed(tick, wait)
  }

  private fun advance() {
    val c = app ?: return
    val s = ReminderStore.current(c)
    while (true) {
      val id = queue.removeFirstOrNull() ?: break
      val r = s.reminders[id]?.takeIf { it.open } ?: continue
      val ui = WhosThere.state.value
      val sound = decision(c, r, ui).sound
      mutable.value = Alert(id, ReminderStore.clock(), chimed = sound)
      if (sound) sound(c, r, ui)
      return
    }
    mutable.value = null
  }

  private fun decision(c: Context, r: Reminder, ui: WhosThereUi): ReminderDue.Decision =
      ReminderDue.decide(r, quiet(c), ownerHere(r, ui), ui.hidePrivate)

  private fun ownerHere(r: Reminder, ui: WhosThereUi): Boolean {
    val view = ui.view as? WhosThereView.Personal ?: return false
    val owner = ui.owner ?: return false
    return !view.confirm && ReminderVisibility.belongsTo(r, owner, ui.people)
  }

  /** The chime, and the title spoken only when it's household or not private. */
  private fun sound(c: Context, r: Reminder, ui: WhosThereUi) {
    runCatching {
      ChimePlayer.playChime(c)
      if (!r.private) main.postDelayed({ ChimePlayer.announce(c, "Reminder: ${r.title}") }, SPEAK_AFTER_MS)
    }.onFailure { Log.w(TAG, "reminder chime failed", it) }
    Log.i(TAG, "reminder chimed (household=${r.household}, owner here=${ownerHere(r, ui)})")
  }

  private fun quiet(c: Context): Boolean = runCatching {
    DinnerQuiet.isQuiet(ChimeConfig.load(c), ReminderStore.clock(), TimeZone.getDefault())
  }.getOrDefault(true) // unreadable settings: fail safe, stay silent

  /** Keeps only keys of reminders that still exist, so the set stays small. */
  private fun remember(c: Context, s: ReminderState, keys: Set<String>) {
    val live = s.live.map(ReminderDue::key).toSet()
    announced = keys.filter { it in live }.toSet()
    c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, announced).apply()
  }

  private const val SPEAK_AFTER_MS = 1_600L
}
