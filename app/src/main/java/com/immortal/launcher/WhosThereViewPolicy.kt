/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Which Home to show, and what stays private. The mock's rules:
 * 1 person → their Home · 2+ recognised → Family view · anyone else near → private blurs ·
 * unsure → shared (or "Is this you?" when they're alone) · unknown → shared.
 */
internal sealed interface WhosThereView {
  data object Shared : WhosThereView

  /**
   * One person's Home. [othersNear] blurs their private items; [confirm] is the medium-confidence
   * "Is this you?" state (non-private cards show, private stays blurred until they tap Yes).
   */
  data class Personal(val personId: String, val othersNear: Boolean, val confirm: Boolean = false) : WhosThereView

  /** Two or more recognised people: things they may all see, private for nobody. */
  data class Family(val personIds: List<String>) : WhosThereView
}

internal object WhosThereViewPolicy {
  /** The personal view returns to shared this long after the last presence beat (mock: 45 s). */
  const val LEAVE_AFTER_MS = 45_000L
  /** "Show" on a blurred card reveals it for this long. */
  const val REVEAL_MS = 30_000L
  /** After "Not you?" → shared, ignore re-recognition of the same person for a while. */
  const val NOT_YOU_HOLD_MS = 120_000L

  fun select(outcome: GlanceOutcome, people: List<WhosTherePerson>, nowMs: Long): WhosThereView {
    val active = people.filter { it.recognisableAt(nowMs) }.associateBy { it.id }
    val recognised = outcome.recognised.filter { it in active }
    return when {
      recognised.size >= 2 -> WhosThereView.Family(recognised)
      recognised.size == 1 -> WhosThereView.Personal(recognised.single(), othersNear = outcome.faceCount > 1)
      outcome.unsure != null && outcome.unsure in active && outcome.faceCount == 1 ->
        WhosThereView.Personal(outcome.unsure, othersNear = false, confirm = true)
      else -> WhosThereView.Shared
    }
  }

  /**
   * A later glance while someone's Home is up. A second face blurs private items but never
   * swaps the Home out from under its owner; a second recognised adult merges to Family.
   */
  fun update(current: WhosThereView, outcome: GlanceOutcome, people: List<WhosTherePerson>, nowMs: Long): WhosThereView {
    val fresh = select(outcome, people, nowMs)
    if (current !is WhosThereView.Personal || outcome.nobody) return fresh
    val ownerStillHere = current.personId in outcome.recognised
    return when {
      fresh is WhosThereView.Family && ownerStillHere -> fresh
      ownerStillHere -> current.copy(othersNear = outcome.faceCount > 1, confirm = false)
      // Several faces and none clearly the owner (turned away, poor light): keep their Home but
      // private. A single unknown face means the owner left, so the shared Home returns.
      fresh == WhosThereView.Shared && outcome.faceCount >= 2 && !current.confirm -> current.copy(othersNear = true)
      else -> fresh
    }
  }

  /** People whose Home the view is for (for the indicator and tint). */
  fun people(view: WhosThereView): List<String> = when (view) {
    WhosThereView.Shared -> emptyList()
    is WhosThereView.Personal -> listOf(view.personId)
    is WhosThereView.Family -> view.personIds
  }
}

/** The private-items rule, separate from layout so it is testable on its own. */
internal object WhosTherePrivacy {

  data class Context(
      val view: WhosThereView,
      val owner: WhosTherePerson?,
      val inCall: Boolean,
      val hidePrivateWhenOthersNear: Boolean,
      val revealUntilMs: Long,
      val nowMs: Long,
  )

  /** True when the personal view's private items (reminders, private events) must be hidden. */
  fun hidePrivate(c: Context): Boolean {
    val view = c.view
    if (view !is WhosThereView.Personal) return true // shared and family views never show private items
    if (c.owner == null || c.owner.role.parentManaged) return true // kids have no private items
    if (view.confirm) return true
    if (c.revealUntilMs > c.nowMs) return false // the owner tapped Show
    // A call owns the camera, so we can't see who walked in: stay private.
    if (c.inCall) return true
    return view.othersNear && c.hidePrivateWhenOthersNear
  }

  /** Only the Home's owner (alone or with others near) may reveal; never in Family view. */
  fun canReveal(c: Context): Boolean {
    val view = c.view
    return view is WhosThereView.Personal && !view.confirm && c.owner != null && !c.owner.role.parentManaged
  }
}
