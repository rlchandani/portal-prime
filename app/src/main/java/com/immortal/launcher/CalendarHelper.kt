package com.immortal.launcher

import android.content.Context
import android.provider.CalendarContract
import java.util.Calendar

data class CalendarEvent(
    val id: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String? = null,
)

object CalendarHelper {

  /**
   * Query the device calendar for upcoming events in the next 7 days.
   * Returns an empty list if permission is not granted or no calendar accounts exist.
   */
  fun upcoming(context: Context, days: Int = 7): List<CalendarEvent> {
    return runCatching {
      val now = System.currentTimeMillis()
      val end = now + days * 24L * 60L * 60L * 1000L
      val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
        appendPath(now.toString())
        appendPath(end.toString())
      }.build()

      val events = mutableListOf<CalendarEvent>()
      context.contentResolver.query(
          uri,
          arrayOf(
              CalendarContract.Instances.EVENT_ID,
              CalendarContract.Instances.TITLE,
              CalendarContract.Instances.BEGIN,
              CalendarContract.Instances.END,
              CalendarContract.Instances.ALL_DAY,
              CalendarContract.Instances.EVENT_LOCATION,
          ),
          null,
          null,
          CalendarContract.Instances.BEGIN + " ASC"
      )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
        val titleCol = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
        val beginCol = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
        val endCol = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
        val allDayCol = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
        val locCol = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
        while (cursor.moveToNext()) {
          val title = cursor.getString(titleCol) ?: continue
          // Skip cancelled/declined events by checking for empty title
          if (title.isBlank()) continue
          events.add(
              CalendarEvent(
                  id = cursor.getLong(idCol),
                  title = title,
                  begin = cursor.getLong(beginCol),
                  end = cursor.getLong(endCol),
                  allDay = cursor.getInt(allDayCol) == 1,
                  location = cursor.getString(locCol)?.takeIf { it.isNotBlank() },
              )
          )
          if (events.size >= 10) break
        }
      }
      events
    }.getOrDefault(emptyList())
  }

  fun hasPermission(context: Context): Boolean {
    return context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
  }
}
