/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import java.text.DateFormatSymbols
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Accept integer drafts saved by earlier APKs as well as current text drafts. */
internal val EverydayNumberTextSaver = Saver<String, Any>(
    save = { it },
    restore = { saved -> when (saved) {
      is String -> saved
      is Int -> saved.toString()
      else -> null
    } },
)

private val EverydayInk: Color
  @Composable get() = LocalApprovedPalette.current.text
private val EverydaySecondary: Color
  @Composable get() = LocalApprovedPalette.current.secondary
private val EverydayRaised: Color
  @Composable get() = LocalApprovedPalette.current.surface2
private val EverydayDivider: Color
  @Composable get() = LocalApprovedPalette.current.divider
private val EverydayAccent: Color
  @Composable get() = LocalApprovedPalette.current.accent
private val EverydayAccentInk: Color
  @Composable get() {
    val palette = LocalApprovedPalette.current
    return if (palette.dark) palette.background else Color.White
  }
private val EverydayDanger: Color
  @Composable get() {
    val palette = LocalApprovedPalette.current
    return if (palette.dark) Color(0xFFE6B5BA) else palette.error
  }
private val EverydayDangerSurface: Color
  @Composable get() = if (LocalApprovedPalette.current.dark) Color(0xFF46383F) else Color(0xFFFBECEE)
private val EverydayIcons = listOf("📅", "🎂", "🎉", "✈️", "❤️", "🎄", "🏖️", "🎓", "💍", "🍼", "⭐", "🏠")
private val EverydayTimerPresets = listOf(1, 3, 5, 10, 15, 30)
private val EverydayStorageMutex = Mutex()
private val LocalEverydayCollectionHeight = staticCompositionLocalOf { 576.dp }

/**
 * Each body is composed by the host's per-tool SaveableStateProvider. Only draft values
 * are saveable; persisted collections are refreshed through their existing Config API.
 */
@Composable
internal fun ToolsAlarmContent() {
  val context = LocalContext.current
  val appContext = context.applicationContext
  val load = rememberEverydayLoad { AlarmConfig.load(appContext) }
  val operations = rememberEverydayOperations()
  val paneActive = LocalApprovedLauncherPageActive.current
  var editing by rememberSaveable { mutableStateOf(false) }
  var draftStarted by rememberSaveable { mutableStateOf(false) }
  var draftHour by rememberSaveable(stateSaver = EverydayNumberTextSaver) { mutableStateOf("7") }
  var draftMinute by rememberSaveable(stateSaver = EverydayNumberTextSaver) { mutableStateOf("0") }
  val alarm = load.value

  fun changeAlarm(change: (AlarmConfig.Config) -> AlarmConfig.Config, closeEditor: Boolean = false) {
    operations.run("Couldn’t save the alarm. Try again.", task = {
      // Preserve the label and any changes made through another surface since this body loaded.
      val next = change(AlarmConfig.load(appContext))
      AlarmConfig.save(appContext, next)
      AlarmScheduler.reschedule(appContext)
      next
    }) { saved ->
      load.value = saved
      if (closeEditor) {
        editing = false
        draftStarted = false
      }
    }
  }

  ToolsTwoPane(left = {
    EverydayPanel("Your next alarm") {
      EverydayLoadStatus(load)
      if (alarm != null) {
        EverydayValue(
            String.format(Locale.ROOT, "%02d:%02d", alarm.hour, alarm.minute),
            size = 120, centered = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
        val next = remember(alarm, paneActive) {
          if (paneActive) AlarmConfig.nextTrigger(alarm) else null
        }
        ToolsText(
            if (next == null) "Your alarm is turned off"
            else "Next: " + SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(next)),
            secondary = true)
        EverydayActions {
          ToolsButton("Change time", primary = true, enabled = !operations.busy) {
            if (!draftStarted) {
              draftHour = alarm.hour.toString()
              draftMinute = alarm.minute.toString()
              draftStarted = true
            }
            operations.error = null
            editing = true
          }
          ToolsButton(if (alarm.enabled) "Turn off" else "Turn on", enabled = !operations.busy) {
            changeAlarm({ it.copy(enabled = !it.enabled) })
          }
        }
        Spacer(Modifier.height(20.dp))
        ToolsStatus(if (alarm.enabled) "Alarm enabled" else "Alarm off", active = alarm.enabled)
        EverydayError(operations.error)
      }
    }
  }, right = {
    EverydayPanel("Repeat") {
      ToolsText("Choose days, or leave all off for a one-time alarm.", secondary = true)
      Spacer(Modifier.height(24.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Calendar uses 1-based Sunday–Saturday, unlike the mock's 0-based browser days.
        listOf("S", "M", "T", "W", "T", "F", "S").forEachIndexed { index, label ->
          val day = index + Calendar.SUNDAY
          val selected = alarm?.days?.contains(day) == true
          val fullDay = DateFormatSymbols(Locale.getDefault()).weekdays[day]
          Surface(
              onClick = {
                changeAlarm({ current ->
                  current.copy(days = if (day in current.days) current.days - day else current.days + day)
                })
              },
              enabled = alarm != null && !operations.busy,
              shape = RoundedCornerShape(16.dp),
              color = if (selected) EverydayAccent else EverydayRaised,
              contentColor = if (selected) EverydayAccentInk else EverydayInk,
              modifier = Modifier.weight(1f).heightIn(min = 68.dp).semantics {
                contentDescription = fullDay
                stateDescription = if (selected) "Selected" else "Not selected"
              },
              border = androidx.compose.foundation.BorderStroke(1.dp, EverydayDivider),
          ) {
            Text(
                label, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
                fontSize = 24.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 16.dp))
          }
        }
      }
      EverydayActions {
        ToolsButton("Preview ringing screen", enabled = alarm != null && !operations.busy) {
          context.startActivity(
              Intent(context, AlarmRingActivity::class.java).putExtra("tools_preview", true))
        }
      }
    }
  })

  if (editing && alarm != null) {
    val hour = ApprovedToolsEverydayValidation.number(draftHour, 0, 23)
    val minute = ApprovedToolsEverydayValidation.number(draftMinute, 0, 59)
    EverydaySheet("Change alarm time", onDismiss = {
      // ToolsSheet also dismisses on lifecycle hiding. Preserve the valid draft until
      // explicit Cancel or a successful Save, so reopening cannot overwrite it.
      if (!operations.busy) editing = false
    }, actions = {
      ToolsButton("Cancel", enabled = !operations.busy) {
        editing = false
        draftStarted = false
      }
      ToolsButton("Save", primary = true, enabled = !operations.busy && hour != null && minute != null) {
        if (hour != null && minute != null) {
          changeAlarm({ it.copy(hour = hour, minute = minute) }, closeEditor = true)
        }
      }
    }) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f)) {
          EverydayNumberField("Hour", draftHour, 0, 23, alarm.hour, wrap = true,
              enabled = !operations.busy) { draftHour = it }
        }
        Column(Modifier.weight(1f)) {
          // Exact minute offsets remain editable without rounding or a second sheet.
          EverydayNumberField("Minute", draftMinute, 0, 59, alarm.minute, wrap = true,
              enabled = !operations.busy) { draftMinute = it }
        }
      }
      EverydayError(operations.error)
    }
  }
}

@Composable
internal fun ToolsTimersContent() {
  val context = LocalContext.current.applicationContext
  val load = rememberEverydayLoad { TimerConfig.load(context) }
  val operations = rememberEverydayOperations()
  var custom by rememberSaveable { mutableStateOf(false) }
  var hours by rememberSaveable { mutableStateOf("0") }
  var minutes by rememberSaveable { mutableStateOf("5") }
  var seconds by rememberSaveable { mutableStateOf("0") }
  var cancelId by rememberSaveable { mutableStateOf<String?>(null) }
  val timers = load.value.orEmpty()
  val duration = remember(hours, minutes, seconds) {
    ApprovedToolsEverydayValidation.timerDurationMillis(hours, minutes, seconds)
  }
  fun startTimer(label: String, millis: Long, closeEditor: Boolean = false) {
    operations.run("Couldn’t start the timer. Try again.", task = {
      TimerConfig.add(context, label, millis) // Persists and schedules atomically at this boundary.
      TimerConfig.load(context)
    }) { saved ->
      load.value = saved
      if (closeEditor) custom = false
    }
  }

  EverydayCollectionPanes(left = {
    EverydayPanel("Start a timer") {
      ToolsText("Tap a duration to start.", secondary = true)
      Spacer(Modifier.height(24.dp))
      EverydayTimerPresets.chunked(3).forEachIndexed { index, row ->
        if (index > 0) Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          row.forEach { preset ->
            ToolsButton(
                "$preset min", enabled = load.value != null && !operations.busy,
                modifier = Modifier.weight(1f),
            ) { startTimer("$preset min", preset * 60_000L) }
          }
        }
      }
      EverydayActions {
        ToolsButton("Custom timer", primary = true, enabled = load.value != null && !operations.busy) {
          operations.error = null
          custom = true
        }
      }
      Spacer(Modifier.height(20.dp))
      ToolsText("Timers keep their place when you switch tools.", secondary = true)
      EverydayError(operations.error.takeUnless { custom || cancelId != null })
    }
  }, right = {
    EverydayPanel("Running timers") {
      EverydayLoadStatus(load)
      if (load.value != null) {
        EverydayRunningTimers(timers, enabled = !operations.busy) { id ->
          operations.error = null
          cancelId = id
        }
      }
    }
  })

  if (custom) {
    EverydaySheet("Custom timer", onDismiss = { if (!operations.busy) custom = false }, actions = {
      ToolsButton("Cancel", enabled = !operations.busy) { custom = false }
      ToolsButton("Start timer", primary = true, enabled = duration != null && !operations.busy) {
        duration?.let { startTimer("Timer", it, closeEditor = true) }
      }
    }) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        ToolsField("Hours", hours, { hours = it }, numeric = true, modifier = Modifier.weight(1f))
        ToolsField("Minutes", minutes, { minutes = it }, numeric = true, modifier = Modifier.weight(1f))
        ToolsField("Seconds", seconds, { seconds = it }, numeric = true, modifier = Modifier.weight(1f))
      }
      Spacer(Modifier.height(20.dp))
      ToolsText("Hours 0–23 · Minutes and seconds 0–59", secondary = true)
      EverydayError(
          if (duration == null) "Enter a duration greater than zero, with each field in range."
          else operations.error)
    }
  }
  cancelId?.let { id ->
    val selected = timers.firstOrNull { it.id == id }
    ToolsConfirm(
        "Cancel this timer?",
        selected?.let { "Cancel “${it.label}”? Your other timers will keep running." }
            ?: "This timer has finished.",
        confirmLabel = "Cancel timer",
        onDismiss = { cancelId = null },
        onConfirm = {
          cancelId = null
          operations.run("Couldn’t cancel the timer. Try again.", task = {
            TimerConfig.remove(context, id)
            TimerConfig.load(context)
          }) { saved ->
            load.value = saved
            cancelId = null
          }
        },
    )
  }
}

@Composable
internal fun ToolsCountdownsContent() {
  val context = LocalContext.current.applicationContext
  val load = rememberEverydayLoad { CountdownConfig.load(context) }
  val operations = rememberEverydayOperations()
  val initialDate = remember { Calendar.getInstance() }
  var adding by rememberSaveable { mutableStateOf(false) }
  var name by rememberSaveable { mutableStateOf("") }
  var emoji by rememberSaveable { mutableStateOf("🎂") }
  var month by rememberSaveable(stateSaver = EverydayNumberTextSaver) { mutableStateOf((initialDate.get(Calendar.MONTH) + 1).toString()) }
  var day by rememberSaveable(stateSaver = EverydayNumberTextSaver) { mutableStateOf(initialDate.get(Calendar.DAY_OF_MONTH).toString()) }
  var year by rememberSaveable(stateSaver = EverydayNumberTextSaver) { mutableStateOf(initialDate.get(Calendar.YEAR).coerceIn(2000, 2100).toString()) }
  var yearly by rememberSaveable { mutableStateOf(true) }
  var removeId by rememberSaveable { mutableStateOf<Long?>(null) }
  var submitted by rememberSaveable { mutableStateOf(false) }
  val dateError = remember(name, month, day, year, yearly) {
    ApprovedToolsEverydayValidation.countdownDraftError(name, month, day, year, yearly)
  }
  val resume = rememberApprovedResume()
  val today = remember(resume) { Calendar.getInstance() }
  val savedEvents = load.value
  val derivedEvents by produceState<List<Pair<CountdownConfig.Event, Int?>>?>(
      initialValue = null, key1 = savedEvents, key2 = today,
  ) {
    value = savedEvents?.let { saved ->
      withContext(Dispatchers.Default) {
        saved.map { event ->
          event to ApprovedToolsEverydayValidation.countdownDaysUntil(event.month, event.day, event.year, today)
        }.sortedBy { it.second ?: Int.MAX_VALUE }
      }
    }
  }
  val events = derivedEvents.orEmpty()

  EverydayCollectionPanes(left = {
    EverydayPanel("Dates to look forward to") {
      EverydayLoadStatus(load)
      when {
        load.value == null -> Unit
        derivedEvents == null -> ToolsStatus("Preparing dates…")
        events.isEmpty() -> EverydayEmpty(
            "cal", "Make a date", "Birthdays, holidays, and the things that matter.")
        else -> EverydayCollection {
          itemsIndexed(events, key = { _, item -> item.first.id }) { index, (event, days) ->
            if (index > 0) EverydayDivider()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 100.dp).padding(vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EverydayTitle("${event.emoji} ${event.label}")
                val phrase = when (days) {
                  null -> "Check this saved date"
                  0 -> "Today"
                  1 -> "Tomorrow"
                  else -> if (days > 0) "$days days to go" else "${-days} days ago"
                }
                val date = if (event.year == 0)
                  String.format(Locale.ROOT, "%02d-%02d · Every year", event.month, event.day)
                else String.format(Locale.ROOT, "%04d-%02d-%02d · Once", event.year, event.month, event.day)
                ToolsText("$phrase · $date", secondary = true)
              }
              ToolsButton("Remove", enabled = !operations.busy) {
                operations.error = null
                removeId = event.id
              }
            }
          }
        }
      }
      EverydayError(operations.error.takeUnless { adding })
    }
  }, right = {
    EverydayPanel("Add a countdown") {
      ToolsText(
          "Choose a name, date, and icon. It will also be available to the Home countdown widget.",
          secondary = true)
      EverydayActions {
        ToolsButton("Add countdown", primary = true, enabled = load.value != null && !operations.busy) {
          operations.error = null
          submitted = false
          adding = true
        }
      }
    }
  })

  if (adding) {
    EverydaySheet("Add countdown", onDismiss = { if (!operations.busy) adding = false }, actions = {
      ToolsButton("Cancel", enabled = !operations.busy) { adding = false }
      ToolsButton("Add countdown", primary = true, enabled = !operations.busy) {
        submitted = true
        if (dateError == null) {
          val savedName = name.trim()
          val savedEmoji = emoji
          val savedMonth = checkNotNull(ApprovedToolsEverydayValidation.number(month, 1, 12))
          val savedDay = checkNotNull(ApprovedToolsEverydayValidation.number(day, 1, 31))
          val savedYear = if (yearly) 0 else checkNotNull(ApprovedToolsEverydayValidation.number(year, 2000, 2100))
          operations.run("Couldn’t add the countdown. Try again.", task = {
            CountdownConfig.add(context, savedName, savedEmoji, savedMonth, savedDay, savedYear)
            CountdownConfig.load(context)
          }) { saved ->
            load.value = saved
            adding = false
            name = ""
            submitted = false
          }
        }
      }
    }) {
      ToolsField("Name", name, { name = it })
      Spacer(Modifier.height(24.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f)) {
          EverydayNumberField("Month", month, 1, 12, initialDate.get(Calendar.MONTH) + 1,
              wrap = true, enabled = !operations.busy) { month = it }
        }
        Column(Modifier.weight(1f)) {
          EverydayNumberField("Day", day, 1, 31, initialDate.get(Calendar.DAY_OF_MONTH),
              wrap = true, enabled = !operations.busy) { day = it }
        }
      }
      Spacer(Modifier.height(20.dp))
      Row(
          Modifier.fillMaxWidth().heightIn(min = 68.dp)
              .toggleable(value = yearly, role = Role.Checkbox, onValueChange = { yearly = it }),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Checkbox(
            checked = yearly, onCheckedChange = null,
            colors = CheckboxDefaults.colors(
                checkedColor = EverydayAccent, checkmarkColor = EverydayAccentInk,
                uncheckedColor = EverydaySecondary),
            modifier = Modifier.size(68.dp))
        ToolsText("Repeat every year")
      }
      if (!yearly) {
        Spacer(Modifier.height(20.dp))
        EverydayNumberField("Year", year, 2000, 2100, initialDate.get(Calendar.YEAR).coerceIn(2000, 2100),
            enabled = !operations.busy) { year = it }
      }
      Spacer(Modifier.height(24.dp))
      ToolsChoice("Icon", emoji, EverydayIcons) { emoji = it }
      if (ApprovedToolsEverydayValidation.number(month, 1, 12) == 2 &&
          ApprovedToolsEverydayValidation.number(day, 1, 31) == 29 && yearly) {
        Spacer(Modifier.height(20.dp))
        ToolsText("February 29 repeats in leap years.", secondary = true)
      }
      EverydayError(if (submitted) dateError ?: operations.error else operations.error)
    }
  }
  removeId?.let { id ->
    val selected = events.firstOrNull { it.first.id == id }?.first
    ToolsConfirm(
        "Remove countdown?",
        selected?.let { "Remove “${it.label}” from your countdowns?" } ?: "Remove this countdown?",
        confirmLabel = "Remove",
        onDismiss = { removeId = null },
        onConfirm = {
          removeId = null
          operations.run("Couldn’t remove the countdown. Try again.", task = {
            CountdownConfig.remove(context, id)
            CountdownConfig.load(context)
          }) { saved ->
            load.value = saved
            removeId = null
          }
        },
    )
  }
}

@Composable
internal fun ToolsConverterContent() {
  val context = LocalContext.current.applicationContext
  val owner = LocalLifecycleOwner.current
  val categories = remember { Converter.UNIT_CATEGORIES.map { it.first } + "Currency" }
  var category by rememberSaveable { mutableStateOf("Length") }
  var from by rememberSaveable { mutableStateOf("m") }
  var to by rememberSaveable { mutableStateOf("ft") }
  var input by rememberSaveable { mutableStateOf("1") }
  val units = remember(category) {
    if (category == "Currency") Converter.CURRENCIES
    else Converter.UNIT_CATEGORIES.first { it.first == category }.second.keys.toList()
  }
  val amount = remember(input) { ApprovedToolsEverydayValidation.converterValue(input) }
  var rate by remember(from, to) { mutableStateOf<CurrencyRate?>(null) }
  var rateLoading by remember(from, to) { mutableStateOf(false) }
  var retry by remember { mutableIntStateOf(0) }

  // Fetch a unit rate, not a conversion for every keystroke. Amount edits remain instant,
  // and only the synchronous Converter API's existing cache owns the exchange rates.
  LaunchedEffect(owner, category, from, to, retry) {
    if (category != "Currency" || from == to) return@LaunchedEffect
    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      rateLoading = true
      try {
        rate = loadCurrencyRate(
            cached = { ToolsWork.load { Converter.freshExchangeRateSnapshot(context, from, to) } },
            fetch = { ToolsWork.load { Converter.exchangeRateSnapshot(context, from, to) } },
        )?.takeIf { it.value.isFinite() }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        rate = null
      } finally {
        rateLoading = false
      }
    }
  }
  val result = remember(category, from, to, amount, rate) {
    amount?.let { value ->
      when {
        category != "Currency" -> Converter.convert(category, from, to, value)
        from == to -> value
        else -> rate?.let { value * it.value }
      }?.takeIf { it.isFinite() }
    }
  }

  ToolsTwoPane(left = {
    EverydayPanel("Convert") {
      ToolsChoice("Category", category, categories) { selected ->
        if (selected == category) return@ToolsChoice
        category = selected
        val nextUnits = if (selected == "Currency") Converter.CURRENCIES
        else Converter.UNIT_CATEGORIES.first { it.first == selected }.second.keys.toList()
        from = nextUnits.first()
        to = nextUnits.getOrElse(1) { nextUnits.first() }
      }
      Spacer(Modifier.height(24.dp))
      ToolsField("Value", input, { input = it }, numeric = true)
      Spacer(Modifier.height(24.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f)) { ToolsChoice("From", from, units) { from = it } }
        Column(Modifier.weight(1f)) { ToolsChoice("To", to, units) { to = it } }
      }
      EverydayActions {
        ToolsButton("Swap units") {
          val previousFrom = from
          from = to
          to = previousFrom
        }
      }
    }
  }, right = {
    EverydayPanel("Result") {
      when {
        amount == null -> ToolsText("Enter a valid number.")
        result != null -> EverydayValue(
            "${everydayFormatNumber(result)} $to", size = 90,
            modifier = Modifier.padding(top = 16.dp, bottom = 12.dp))
        category == "Currency" && rateLoading -> ToolsStatus("Getting exchange rates…")
        category == "Currency" && rate == null -> {
          ToolsText("No exchange rate available.")
          ToolsText("Connect to the internet to save a rate for this currency pair.", secondary = true)
          EverydayActions { ToolsButton("Try again") { retry++ } }
        }
        else -> ToolsText("This value is too large to convert.")
      }
      val resultRate = rate
      if (category == "Currency" && from != to && result != null && resultRate != null) {
        val locale = Locale.getDefault()
        val referenceDate = remember(resultRate.referenceDate, locale) {
          resultRate.referenceDate?.let {
            java.time.LocalDate.parse(it).format(
                java.time.format.DateTimeFormatter.ofLocalizedDate(
                    java.time.format.FormatStyle.MEDIUM).withLocale(locale))
          }
        }
        ToolsText(
            "${if (resultRate.saved) "Saved ECB rate" else "ECB reference rate"} · " +
                (referenceDate ?: "reference date unavailable"),
            secondary = true)
      }
      Spacer(Modifier.height(20.dp))
      ToolsText(
          when {
            category != "Currency" -> "Calculated on the device. No internet needed."
            from == to -> "No exchange rate needed for the same currency."
            else -> "Uses ECB reference rates. The device can reuse its last saved rates offline."
          },
          secondary = true)
    }
  })
}

/** Numbers are draft fields in the parent sheet; only its final action persists changes. */
@Composable
private fun EverydayNumberField(
    title: String, value: String, min: Int, max: Int, fallback: Int,
    wrap: Boolean = false, enabled: Boolean = true, onChange: (String) -> Unit,
) {
  ToolsField(title, value, onChange, numeric = true, integer = true, enabled = enabled)
  Spacer(Modifier.height(16.dp))
  Row(
      Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    ToolsButton("−", enabled = enabled,
        modifier = Modifier.semantics { contentDescription = "Decrease $title" }) {
      onChange(ApprovedToolsEverydayValidation.stepNumber(value, fallback, min, max, increase = false, wrap = wrap))
    }
    ToolsText("$min–$max", secondary = true, modifier = Modifier.weight(1f))
    ToolsButton("+", enabled = enabled,
        modifier = Modifier.semantics { contentDescription = "Increase $title" }) {
      onChange(ApprovedToolsEverydayValidation.stepNumber(value, fallback, min, max, increase = true, wrap = wrap))
    }
  }
  EverydayError(if (ApprovedToolsEverydayValidation.number(value, min, max) == null)
      "Enter a whole number from $min to $max." else null)
}

@Composable
private fun EverydayRunningTimers(timers: List<TimerConfig.Timer>, enabled: Boolean, onCancel: (String) -> Unit) {
  val owner = LocalLifecycleOwner.current
  val sorted = remember(timers) { timers.sortedBy { it.endAtMillis } }
  val clock = remember { mutableStateOf(System.currentTimeMillis()) }
  var firstLive by remember(sorted) {
    mutableIntStateOf(firstLiveTimerIndex(sorted, System.currentTimeMillis()))
  }
  // Only composed clock texts read this State. Offscreen rows, collection framing,
  // presets and form drafts do not recompose on ticks. Expiry lookup is logarithmic.
  LaunchedEffect(owner, sorted) {
    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      while (true) {
        val now = System.currentTimeMillis()
        clock.value = now
        firstLive = firstLiveTimerIndex(sorted, now)
        if (firstLive == sorted.size) break
        delay(1000L)
      }
    }
  }
  // Capture one cutoff for both count and indexing; expiry must not change a live
  // item provider's index base before its replacement has been composed.
  val liveStart = firstLive
  if (liveStart == sorted.size) EverydayEmpty(
      "timer", "A moment to yourself", "Your active timers will appear here.")
  else EverydayCollection {
    items(count = sorted.size - liveStart, key = { index -> sorted[index + liveStart].id }) { index ->
      val timer = sorted[index + liveStart]
      Column {
        if (index > 0) EverydayDivider()
        EverydayTimerRow(timer, clock, enabled) { onCancel(timer.id) }
      }
    }
  }
}

/** Upper bound: deadlines equal to now have already expired. The input is sorted once. */
private fun firstLiveTimerIndex(sorted: List<TimerConfig.Timer>, now: Long): Int {
  val insertion = sorted.binarySearch { if (it.endAtMillis <= now) -1 else 1 }
  return -insertion - 1
}

@Composable
private fun EverydayTimerRow(timer: TimerConfig.Timer, clock: State<Long>, enabled: Boolean, onCancel: () -> Unit) {
  val largeText = LocalDensity.current.fontScale > 1.15f
  if (largeText) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      EverydayTitle(timer.label)
      Row(
          Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        EverydayTimerClock(timer, clock, modifier = Modifier.weight(1f))
        ToolsButton("Cancel", destructive = true, enabled = enabled, onClick = onCancel)
      }
    }
  } else {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 100.dp).padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      EverydayTitle(timer.label, Modifier.weight(1f))
      EverydayTimerClock(timer, clock)
      ToolsButton("Cancel", destructive = true, enabled = enabled, onClick = onCancel)
    }
  }
}

@Composable
private fun EverydayTimerClock(timer: TimerConfig.Timer, clock: State<Long>, modifier: Modifier = Modifier) {
  EverydayValue(
      ApprovedToolsEverydayValidation.timerClock(timer.endAtMillis - clock.value),
      size = 56, modifier = modifier)
}

/** Capture the finite workspace height before ToolsTwoPane's scroll introduces infinity. */
@Composable
private fun EverydayCollectionPanes(
    left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit,
) {
  val configuration = LocalConfiguration.current
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val panelOverhead = (64f + 20f + 39f * configuration.fontScale).dp
    val available = if (constraints.hasBoundedHeight) (maxHeight - panelOverhead).coerceAtLeast(68.dp)
      else (configuration.screenHeightDp * 0.45f).coerceAtLeast(240f).dp
    CompositionLocalProvider(LocalEverydayCollectionHeight provides available) {
      ToolsTwoPane(left, right)
    }
  }
}

/** ToolsTwoPane's children are vertically unbounded; apply the captured limit to the list. */
@Composable
private fun EverydayCollection(content: LazyListScope.() -> Unit) {
  LazyColumn(
      modifier = Modifier.fillMaxWidth().heightIn(max = LocalEverydayCollectionHeight.current),
      state = rememberLazyListState(), content = content)
}

/** The shared primitives own their framing; this inner column owns the mock's body spacing. */
@Composable
private fun EverydayPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
  ToolsPanel(title) { Column(Modifier.fillMaxWidth(), content = content) }
}

@Composable
private fun EverydaySheet(
    title: String, onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
  ToolsSheet(title, onDismiss, actions) { Column(Modifier.fillMaxWidth(), content = content) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EverydayActions(content: @Composable () -> Unit) {
  FlowRow(
      Modifier.fillMaxWidth().padding(top = 24.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
  ) { content() }
}

@Composable
private fun EverydayValue(
    text: String, size: Int, modifier: Modifier = Modifier, centered: Boolean = false,
) {
  Text(
      text, modifier = modifier, color = EverydayInk, fontFamily = FontFamily.SansSerif,
      fontSize = size.sp, lineHeight = (size * 1.1f).sp,
      fontWeight = FontWeight.Normal, letterSpacing = (if (size >= 90) -2 else -1).sp,
      textAlign = if (centered) TextAlign.Center else TextAlign.Start)
}

@Composable
private fun EverydayTitle(text: String, modifier: Modifier = Modifier) {
  Text(
      text, modifier = modifier, color = EverydayInk, fontFamily = FontFamily.SansSerif,
      fontSize = 26.sp, lineHeight = 35.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun EverydayDivider() {
  Box(Modifier.fillMaxWidth().height(1.dp).background(EverydayDivider))
}

@Composable
private fun EverydayEmpty(icon: String, title: String, message: String) {
  Column(
      Modifier.fillMaxWidth().heightIn(min = 320.dp).padding(horizontal = 32.dp, vertical = 40.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
  ) {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(approvedIconResource(icon)),
        contentDescription = null,
        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(EverydayAccent),
        modifier = Modifier.size(64.dp))
    EverydayTitle(title)
    ToolsText(message, secondary = true)
  }
}

@Composable
private fun EverydayError(error: String?) {
  if (error != null) {
    Text(
        error, color = EverydayDanger, fontFamily = FontFamily.SansSerif,
        fontSize = 22.sp, lineHeight = 32.sp,
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .background(EverydayDangerSurface, RoundedCornerShape(14.dp))
            .border(1.dp, EverydayDanger.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp))
  }
}

private fun everydayFormatNumber(value: Double): String {
  val magnitude = kotlin.math.abs(value)
  return if (magnitude >= 1_000_000_000 || (magnitude != 0.0 && magnitude < 0.0001))
    String.format(Locale.getDefault(), "%.6g", value)
  else NumberFormat.getNumberInstance(Locale.getDefault()).apply {
    maximumFractionDigits = 6
  }.format(value)
}

@Composable
private fun <T> rememberEverydayLoad(loader: () -> T): EverydayLoad<T> {
  val owner = LocalLifecycleOwner.current
  val currentLoader by rememberUpdatedState(loader)
  val load = remember { EverydayLoad<T>() }
  LaunchedEffect(owner, load.retry) {
    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      load.refresh {
        withContext(Dispatchers.IO) {
          EverydayStorageMutex.withLock { currentLoader() }
        }
      }
    }
  }
  return load
}

@Composable
private fun <T> EverydayLoadStatus(load: EverydayLoad<T>) {
  if (load.error == null) {
    if (load.value == null) ToolsStatus("Loading…")
  } else {
    EverydayError(load.error)
    EverydayActions {
      ToolsButton(if (load.loading) "Trying…" else "Try again", enabled = !load.loading) { load.retry++ }
    }
  }
}

private class EverydayOperations(private val scope: CoroutineScope) {
  var busy by mutableStateOf(false)
    private set
  var error by mutableStateOf<String?>(null)

  fun <T> run(errorMessage: String, task: () -> T, onSuccess: (T) -> Unit) {
    if (busy) return
    busy = true
    error = null
    scope.launch {
      try {
        // A user's explicit save/start/cancel must finish its persistence + scheduling even
        // if they switch tools while it runs. No display job survives the composition.
        val result = withContext(Dispatchers.IO + NonCancellable) {
          EverydayStorageMutex.withLock { task() }
        }
        onSuccess(result)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        error = errorMessage
      } finally {
        busy = false
      }
    }
  }
}

@Composable
private fun rememberEverydayOperations(): EverydayOperations {
  val scope = rememberCoroutineScope()
  return remember(scope) { EverydayOperations(scope) }
}
