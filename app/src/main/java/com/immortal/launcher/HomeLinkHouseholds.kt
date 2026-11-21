/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One Portal or phone in a household's Devices card (mock `hhScr`). */
@Immutable
internal data class HomeLinkDevice(val name: String, val detail: String, val phone: Boolean, val presence: CallPresence)

/** This home's Devices card from the rooms heard on the bus: this Portal first, then the others by name. Pure. */
internal object HomeLinkDevices {
  const val THIS_ONE = "Portal · this one"
  const val PORTAL = "Portal"

  fun ofThisHome(selfName: String, peers: List<CallPeer>, now: Long): List<HomeLinkDevice> =
      listOf(HomeLinkDevice(selfName, THIS_ONE, phone = false, presence = CallPresence.AVAILABLE)) +
          peers.sortedBy { it.name.lowercase() }.map { HomeLinkDevice(it.name, PORTAL, phone = false, presence = CallPolicy.presence(it, now)) }
}

/** Which pane(s) Households & devices shows: side list + detail on a Portal, one at a time on a Mini. */
internal enum class HomeLinkPane { LIST, DETAIL, BOTH }

/**
 * Households & devices (mock `hhScr`): this home first, then every linked home. [selected] is a
 * linked home's id, or null for this home. Immutable: every action returns a new state.
 */
@Immutable
internal data class HomeLinkHouseholdsState(
    val book: HomeLinkBook,
    val selected: String? = null,
    /** On a Mini the detail replaces the list; this says it is open. */
    val detailOpen: Boolean = false,
    /** "Remove Lakeview?" is showing for [selected]. */
    val confirmingRemove: Boolean = false,
) {
  /** The linked home being shown, or null for this home (also when [selected] was removed elsewhere). */
  val link: HomeLink? get() = selected?.let { id -> book.links.firstOrNull { it.id == id } }

  fun select(id: String?): HomeLinkHouseholdsState =
      copy(selected = id?.takeIf { want -> book.links.any { it.id == want } }, detailOpen = true, confirmingRemove = false)

  fun back(): HomeLinkHouseholdsState = copy(detailOpen = false, confirmingRemove = false)

  /** The system Back key: closes the remove confirm, then a Mini's open detail; null means leave Households. */
  fun backOrNull(compact: Boolean): HomeLinkHouseholdsState? = when {
    confirmingRemove -> cancelRemove()
    compact && detailOpen -> back()
    else -> null
  }

  fun askRemove(): HomeLinkHouseholdsState = if (link == null) this else copy(confirmingRemove = true)

  fun cancelRemove(): HomeLinkHouseholdsState = copy(confirmingRemove = false)

  /** A new book (edited here, or synced from another Portal): drops a selection that no longer exists. */
  fun withBook(next: HomeLinkBook): HomeLinkHouseholdsState {
    val keep = selected?.takeIf { id -> next.links.any { it.id == id } }
    return copy(book = next, selected = keep, confirmingRemove = confirmingRemove && keep != null,
        detailOpen = detailOpen && (keep != null || selected == null))
  }

  fun pane(compact: Boolean): HomeLinkPane = when {
    !compact -> HomeLinkPane.BOTH
    detailOpen -> HomeLinkPane.DETAIL
    else -> HomeLinkPane.LIST
  }
}

/** Edits to what one linked home may do. Pure: each returns a new link (drop-in stays impossible). */
internal object HomeLinkPermissionEdit {
  fun call(link: HomeLink, on: Boolean): HomeLink = link.copy(permissions = link.permissions.copy(canCall = on))

  fun availability(link: HomeLink, on: Boolean): HomeLink = link.copy(permissions = link.permissions.copy(seesAvailability = on))

  fun room(link: HomeLink, room: String, rings: Boolean): HomeLink {
    if (!HomeLinkWire.validName(room)) return link
    val rooms = link.permissions.ringRooms
    return link.copy(permissions = link.permissions.copy(ringRooms = if (rings) rooms + room else rooms - room))
  }

  /** Every room that can be shown: this home's rooms in order, then any stored room no longer here. */
  fun roomsToShow(link: HomeLink, rooms: List<String>): List<String> =
      (rooms + link.permissions.ringRooms.sorted()).distinct()
}

/** Words for Households & devices and the permissions screen, from the approved mock. Pure. */
internal object HomeLinkHouseholdsCopy {
  const val TITLE = "Households & devices"
  const val ADD = "Add a household"
  const val JOIN = "Join with a code"
  const val THIS_HOME = "This home"
  const val PORTALS = "Pair Portals"
  const val SIDE_NOTE = "Each household has its own keys. Removing one deletes its keys from every Portal here."
  const val CHECK_AGAIN = "Check again"
  const val DEVICES = "Devices"
  const val SUB_CALL = "Rings the rooms you choose"
  const val AVAILABILITY = "See when we’re available"
  const val SUB_AVAILABILITY = "Shows Available / In a call / Quiet hours, never who is in the room"
  const val DROP_IN = "Drop in"
  const val SUB_DROP_IN = "Never across homes. Drop-ins only work inside one home."
  const val PERMS_SUB = "You can change this any time in Households"
  const val PERMS_AVAILABILITY_SUB = "Available, In a call or Quiet hours. Never who’s in which room."
  const val NO_LINKS = "No other households yet. Invite one, or join with a code from theirs."
  const val CANCEL = "Cancel"
  const val DONE = "Done"
  private val CHECKED = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

  fun call(selfName: String): String = "Call $selfName"

  fun whatCanDo(name: String): String = "What $name can do"

  fun permsTitle(name: String): String = "What can $name do?"

  fun permsCallSub(name: String): String = "$name can video-call us"

  fun roomsThatRing(name: String): String = "Rooms that ring when $name calls"

  fun permsNote(name: String): String =
      "Drop-ins never work between homes. Auto-answer can only be turned on at $name, on their own Portal."

  fun remove(name: String): String = "Remove $name"

  fun removeBody(name: String): String =
      "$name won’t be able to call you, and its keys are deleted from every Portal here. To call again you’d link with a new code."

  /** "Safety number checked 17 Nov" (mock chip), in [zone]. */
  fun checked(at: Long, zone: ZoneId): String = "Safety number checked " + CHECKED.format(Instant.ofEpochMilli(at).atZone(zone))

  /** The side line under a linked home's name, and the privacy row summary ("4 households · all checked"). */
  fun ringSummary(link: HomeLink): String = when {
    !link.permissions.canCall -> "Can’t call"
    link.permissions.ringRooms.isEmpty() -> "Calls ring no rooms"
    link.permissions.ringRooms.size == 1 -> "Rings 1 room"
    else -> "Rings ${link.permissions.ringRooms.size} rooms"
  }

  fun summary(book: HomeLinkBook): String = when (val n = book.links.size) {
    0 -> "No households yet"
    1 -> "1 household · checked"
    else -> "$n households · all checked"
  }

  fun presence(p: CallPresence): String = p.label
}
