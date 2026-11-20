/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val CALENDAR_REFRESH_MS = 15 * 60_000L

/**
 * The personal / Family Home cards, shown in place of the shared widget area while someone is
 * recognised. Same 24 dp cards and 1.5 dp trim as Home; nothing jumps when it swaps in.
 */
@Composable
internal fun WhosTherePersonalHome(use24: Boolean, compactPortrait: Boolean, onNotYou: () -> Unit,
                                   modifier: Modifier = Modifier) {
  val ui by rememberWhosThereUi()
  val view = ui.view
  val viewKey = WhosThereViewPolicy.people(view) to (view is WhosThereView.Family)
  val cards: List<PersonalCard> = when (view) {
    is WhosThereView.Personal -> {
      val p = ui.person(view.personId) ?: return
      when (p.role) {
        WhosThereRole.KID -> listOf(PersonalCard.Routine(p), PersonalCard.Chores(p))
        WhosThereRole.TEEN -> listOf(PersonalCard.Day(p), PersonalCard.Reminders(p), PersonalCard.Chores(p))
        WhosThereRole.ADULT -> listOf(PersonalCard.Day(p), PersonalCard.Reminders(p))
      }
    }
    is WhosThereView.Family -> {
      val people = view.personIds.mapNotNull(ui::person)
      listOfNotNull(PersonalCard.FamilyDay(people), PersonalCard.ShowMine(people),
          people.firstOrNull { it.role.parentManaged }?.let { PersonalCard.Chores(it) })
    }
    WhosThereView.Shared -> return
  }
  // The mock's arrival: cards fade in one by one, ~90 ms apart. Composing one card per step also
  // spreads the work over several frames instead of one long frame when the Home changes.
  var shown by androidx.compose.runtime.remember(viewKey) { androidx.compose.runtime.mutableIntStateOf(0) }
  androidx.compose.runtime.LaunchedEffect(viewKey, cards.size) {
    for (i in 1..cards.size) {
      androidx.compose.runtime.withFrameNanos { }
      shown = i
      kotlinx.coroutines.delay(CARD_STAGGER_MS)
    }
  }
  // The panel covers the shared widgets kept alive underneath, so it takes every touch.
  val absorb = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
  Column(modifier.androidxClickable(absorb)) {
    NotYouRow(ui, onNotYou)
    if (compactPortrait) {
      Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        cards.forEachIndexed { i, c -> Slot(i < shown, Modifier.fillMaxWidth().weight(1f)) { m -> Card(c, ui, use24, m) } }
      }
    } else {
      Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        cards.forEachIndexed { i, c -> Slot(i < shown, Modifier.fillMaxHeight().weight(1f)) { m -> Card(c, ui, use24, m) } }
      }
    }
  }
}

private const val CARD_STAGGER_MS = 90L

private fun Modifier.androidxClickable(source: androidx.compose.foundation.interaction.MutableInteractionSource): Modifier =
    clickable(interactionSource = source, indication = null) {}

/** A card's place in the grid: empty until its turn, then the card fades in (the grid never moves). */
@Composable
private fun Slot(visible: Boolean, modifier: Modifier, content: @Composable (Modifier) -> Unit) {
  if (!visible) { Spacer(modifier); return }
  val alpha = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
  androidx.compose.runtime.LaunchedEffect(Unit) { alpha.animateTo(1f, tween(320)) }
  content(modifier.graphicsLayer { this.alpha = alpha.value; translationY = (1f - alpha.value) * 18f })
}

private sealed interface PersonalCard {
  data class Day(val p: WhosTherePerson) : PersonalCard
  data class Reminders(val p: WhosTherePerson) : PersonalCard
  data class Chores(val p: WhosTherePerson) : PersonalCard
  data class Routine(val p: WhosTherePerson) : PersonalCard
  data class FamilyDay(val people: List<WhosTherePerson>) : PersonalCard
  data class ShowMine(val people: List<WhosTherePerson>) : PersonalCard
}

@Composable
private fun Card(card: PersonalCard, ui: WhosThereUi, use24: Boolean, modifier: Modifier) = when (card) {
  is PersonalCard.Day -> DayCard(ui, card.p, use24, modifier)
  is PersonalCard.Reminders -> RemindersCard(ui, card.p, modifier)
  is PersonalCard.Chores -> WhosThereKidChoresCard(card.p, modifier)
  is PersonalCard.Routine -> WhosThereKidRoutineCard(card.p, modifier)
  is PersonalCard.FamilyDay -> FamilyDayCard(ui, card.people, use24, modifier)
  is PersonalCard.ShowMine -> ShowMineCard(card.people, modifier)
}

/** "For Maya · Not you?" — the correction is always one tap away. */
@Composable
private fun NotYouRow(ui: WhosThereUi, onNotYou: () -> Unit) {
  Row(Modifier.fillMaxWidth().height(56.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    val names = WhosThereViewPolicy.people(ui.view).mapNotNull { ui.person(it)?.name }
    val label = when {
      ui.view is WhosThereView.Family -> "Shared things only while you’re all here"
      (ui.view as? WhosThereView.Personal)?.confirm == true -> "Is this you, ${names.firstOrNull()}? Private items stay hidden until you say so"
      ui.hidePrivate && ui.canReveal -> "Someone else is here, so private items are hidden"
      else -> "Just for ${names.firstOrNull().orEmpty()}"
    }
    Text(label, color = ApprovedHomePalette.Secondary, fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f))
    if ((ui.view as? WhosThereView.Personal)?.confirm == true) ApprovedHomePillButton("Yes, it’s me") { WhosThere.confirm() }
    Spacer(Modifier.width(12.dp))
    ApprovedHomePillButton(if (ui.view is WhosThereView.Family) "Who’s here?" else "Not you?") { onNotYou() }
  }
}

@Composable
private fun CardFrame(modifier: Modifier, title: String, owner: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
  ApprovedHomeSurface(modifier) {
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = ApprovedHomePalette.Secondary, fontSize = 23.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        owner()
      }
      content()
    }
  }
}

@Composable
internal fun OwnerChip(person: WhosTherePerson?, label: String = person?.name ?: "Family", lock: Boolean = false) {
  Row(Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(ApprovedHomePalette.Chrome).padding(horizontal = 6.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    when {
      lock -> WhosThereGlyph(WtGlyph.LOCK, ApprovedHomePalette.Secondary, 20.dp, Modifier.padding(start = 4.dp))
      person != null -> WhosThereAvatar(person, 26.dp)
      else -> ApprovedHomeIcon("home", Modifier.size(20.dp).padding(start = 2.dp))
    }
    Text(label, color = ApprovedHomePalette.Secondary, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(end = 8.dp))
  }
}

/** Private text is covered by soft bars of the same size: the layout never jumps (Android 10 has no blur). */
internal fun Modifier.redacted(hidden: Boolean, color: Color): Modifier = if (!hidden) this else drawWithContent {
  drawRoundRect(color, cornerRadius = CornerRadius(size.height / 2, size.height / 2))
}

/** Today's events for a person's (or the family's) Home and personal briefing; [source] null = no calendar. */
internal data class DayData(val events: List<PersonalEvent>, val source: String?, val unavailable: Boolean)

@Composable
private fun rememberPersonEvents(person: WhosTherePerson?, people: List<WhosTherePerson>): DayData {
  val context = LocalContext.current
  val key = people.joinToString { it.id + it.name + it.calendarUrl }
  val data by produceHomeState(DayData(emptyList(), null, false), person?.id, key) {
    while (true) {
      value = withContext(Dispatchers.IO) { loadWhosThereDay(context, person, people) }
      homeRefreshDelay(CALENDAR_REFRESH_MS)
    }
  }
  return data
}

/** Network: call off the main thread. The person's own feed, else the shared one filtered to them. */
internal fun loadWhosThereDay(context: Context, person: WhosTherePerson?, people: List<WhosTherePerson>): DayData {
  val shared = ScreensaverConfig.load(context).calendarUrl?.takeIf { it.isNotBlank() }
  val own = person?.calendarUrl
  val url = own ?: shared ?: return DayData(emptyList(), null, false)
  val result = runCatching { CalendarFeed.read(url) }.getOrNull() ?: return DayData(emptyList(), url, true)
  val events = when {
    person == null -> WhosThereContent.forFamily(result.events, people)
    own != null -> WhosThereContent.fromOwnFeed(result.events, person)
    else -> WhosThereContent.fromShared(result.events, person, people)
  }
  val now = System.currentTimeMillis()
  val dayEnd = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
  return DayData(WhosThereContent.upcoming(events, now, dayEnd), url, result.unavailable)
}

@Composable
private fun DayCard(ui: WhosThereUi, p: WhosTherePerson, use24: Boolean, modifier: Modifier) {
  val day = rememberPersonEvents(p, ui.people)
  CardFrame(modifier, "${p.name}’s day", { OwnerChip(p) }) {
    EventList(day, use24, ui.hidePrivate, ownerOf = { if (it.owner == EventOwner.MINE) p else null },
        empty = if (day.source == null) "Add a calendar in Settings › Widgets › Calendar" else "Nothing else today")
    if (p.calendarUrl == null && day.source != null) {
      Text("From the family calendar: events tagged “${p.name}:” or #${p.name.lowercase()}", color = ApprovedHomePalette.Secondary,
          fontSize = 18.sp, maxLines = 2)
    }
  }
}

@Composable
private fun FamilyDayCard(ui: WhosThereUi, people: List<WhosTherePerson>, use24: Boolean, modifier: Modifier) {
  val day = rememberPersonEvents(null, people)
  CardFrame(modifier, if (people.size == 2) "Today for you both" else "Today for you all", { OwnerChip(null) }) {
    EventList(day, use24, hide = true, ownerOf = { e -> people.firstOrNull { e.owner == EventOwner.MINE && WhosThereContent.mentions(e.title, it.name) } },
        empty = if (day.source == null) "Add a calendar in Settings › Widgets › Calendar" else "Nothing else today")
  }
}

@Composable
private fun EventList(day: DayData, use24: Boolean, hide: Boolean, ownerOf: (PersonalEvent) -> WhosTherePerson?, empty: String) {
  val fmt = androidx.compose.runtime.remember(use24) { SimpleDateFormat(if (use24) "H:mm" else "h:mm", Locale.getDefault()) }
  if (day.events.isEmpty()) {
    ApprovedHomeWidgetMessage(if (day.unavailable) "Calendar unavailable right now" else empty)
    return
  }
  Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    day.events.forEach { e ->
      val owner = ownerOf(e)
      val barColor = owner?.color?.tint?.argb() ?: Color(0xFF5C7CFA)
      val busy = e.private && hide
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (e.allDay) "All day" else fmt.format(Date(e.startMillis)), color = ApprovedHomePalette.Text, fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.width(96.dp))
        Box(Modifier.width(4.dp).height(44.dp).clip(RoundedCornerShape(2.dp)).background(barColor))
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
          Text(if (busy) WhosThereContent.PRIVATE_LABEL else e.title, color = ApprovedHomePalette.Text, fontSize = 23.sp,
              fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text(if (busy) "Busy" + (owner?.let { " · ${it.name}" } ?: "") else (e.location ?: if (owner == null) "Family" else owner.name),
              color = ApprovedHomePalette.Secondary, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
    }
  }
}

@Composable
private fun RemindersCard(ui: WhosThereUi, p: WhosTherePerson, modifier: Modifier) {
  val context = LocalContext.current
  androidx.compose.runtime.LaunchedEffect(Unit) { withContext(Dispatchers.IO) { HouseholdListStore.ensureLoaded(context) } }
  val list by HouseholdListStore.state.collectAsState()
  val items = androidx.compose.runtime.remember(list, p) { WhosThereContent.reminders(list.open().map { it.text.value }, p) }
  CardFrame(modifier, "Reminders", { OwnerChip(null, "Just you", lock = true) }) {
    if (items.isEmpty()) {
      ApprovedHomeWidgetMessage("Add “${p.name}: …” to the household list and it shows here, just for ${p.name}")
      return@CardFrame
    }
    val bar = ApprovedHomePalette.Chrome
    items.forEach { text ->
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).approvedOutline(RoundedCornerShape(8.dp)))
        Text(text, color = ApprovedHomePalette.Text, fontSize = 23.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 14.dp).redacted(ui.hidePrivate, bar))
      }
    }
    if (ui.hidePrivate) {
      Spacer(Modifier.weight(1f))
      Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(ApprovedHomePalette.Chrome).padding(horizontal = 16.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically) {
        WhosThereGlyph(WtGlyph.LOCK, ApprovedHomePalette.Secondary, 24.dp)
        Text("${items.size} private — someone’s here", color = ApprovedHomePalette.Secondary, fontSize = 19.sp,
            modifier = Modifier.padding(start = 10.dp).weight(1f), maxLines = 1)
        if (ui.canReveal) ApprovedHomeTextButton("Show") { WhosThere.reveal() }
      }
    }
  }
}

@Composable
private fun ShowMineCard(people: List<WhosTherePerson>, modifier: Modifier) {
  CardFrame(modifier, "Just you? Show your Home", {}) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      people.take(3).forEach { p ->
        val shape = RoundedCornerShape(24.dp)
        Column(Modifier.weight(1f).fillMaxHeight().clip(shape).background(ApprovedHomePalette.Chrome)
            .tvFocusable(shape, focusScale = 1f, ringColor = ApprovedHomePalette.Accent) { WhosThere.showMine(p.id) },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
          WhosThereAvatar(p, 84.dp)
          Text(p.name, color = ApprovedHomePalette.Text, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
          Text("Show mine", color = ApprovedHomePalette.Secondary, fontSize = 19.sp)
        }
      }
    }
  }
}
