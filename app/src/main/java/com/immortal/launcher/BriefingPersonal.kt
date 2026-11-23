/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Who's there? personal briefing: when Who's there? recognises an adult, the morning briefing is theirs
 * (their name, their calendar, their reminders) instead of the household's.
 *
 * Privacy: reminders are private ("Just you", see [WhosThereContent]) and so are calendar items marked
 * private. They show only while [WhosThereUi.hidePrivate] is false: the owner alone, not in a call, the
 * "hide when others are near" setting respected. Recognition only runs while Home is in front, so it
 * can't notice someone arriving while the briefing is open; private items therefore hide on their own
 * [PRIVATE_HOLD_MS] after it opens, and the household briefing replaces the personal one as soon as Who's
 * there? no longer shows that person's Home (they left, the screen slept, "Not you"). Pure.
 */
internal object BriefingPersonalPolicy {
  /** Long enough to read (and hear) the briefing; then private items hide until Home checks again. */
  const val PRIVATE_HOLD_MS = 60_000L

  /** The person the briefing is for: a recognised adult whose own Home is showing, else null. */
  fun owner(ui: WhosThereUi, personId: String? = null): WhosTherePerson? {
    val view = ui.view as? WhosThereView.Personal ?: return null
    if (view.confirm) return null
    if (personId != null && view.personId != personId) return null
    return ui.person(view.personId)?.takeIf { it.role == WhosThereRole.ADULT }
  }

  /** Whether private items hide now: Who's there? says so, or [PRIVATE_HOLD_MS] passed since the briefing opened. */
  fun hidePrivate(ui: WhosThereUi, holdOver: Boolean): Boolean = ui.hidePrivate || holdOver
}

/**
 * The personal part of [BriefingInputs]: whose briefing it is and what is theirs. [reminders] are
 * their open reminders from the store ([ReminderVisibility.briefingTitles]); [events] are today's, theirs and
 * the family's ([loadWhosThereDay]).
 */
internal data class BriefingPersonalInputs(
    val name: String,
    val hidePrivate: Boolean,
    /** Ready(null): no calendar added. */
    val events: BriefingPart<List<PersonalEvent>?> = BriefingPart.Loading,
    val reminders: List<String> = emptyList(),
    /**
     * "Your teams / Your markets" ([com.immortal.launcher.memory.BriefingInterests]); empty with the personalization
     * switch off, nothing followed, or others near.
     */
    val interests: List<String> = emptyList(),
)
