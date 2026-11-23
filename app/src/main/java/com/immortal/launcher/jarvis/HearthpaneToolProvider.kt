/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import com.immortal.launcher.Calling
import com.immortal.launcher.DeferredStartup
import com.immortal.launcher.CcJarvisHome
import com.immortal.launcher.DoorbellCardOverlay
import com.immortal.launcher.ReminderLimits
import com.immortal.launcher.ReminderStore
import com.immortal.launcher.RemindersFeature
import com.immortal.launcher.WhosThere
import com.immortal.launcher.jarvis.wake.HearthpaneWake
import com.immortal.launcher.memory.JarvisKnowsActivity
import com.immortal.launcher.memory.JarvisMemory
import com.immortal.launcher.memory.MemoryHandoff
import com.immortal.launcher.memory.MemoryTool
import org.json.JSONObject

/**
 * Jarvis's tool provider in Hearthpane (Jarvis's `ToolContract`: discovered by manifest meta-data, invoked with
 * `call("invoke", toolName, {ARGS})`, answering `{RESULT}` JSON). Only Jarvis may call it ([allowedCaller]); every
 * table operation is refused. Runs on a binder thread and answers from in-memory snapshots, well inside Jarvis's 5 s
 * tool timeout.
 */
class HearthpaneToolProvider : ContentProvider() {
  private val tools by lazy {
    HearthpaneTools(HearthpaneScreens::current, { target, hint -> JarvisHighlight.show(target, hint) },
        homeControl = { args -> CcJarvisHome.run(requireNotNull(context), args) }, search = SettingsJarvisContext::search,
        reminders = { args -> reminderTool.invoke(args) },
        household = HearthpaneHouseholdTools.build(requireNotNull(context)) + HearthpaneFeedTools.build(requireNotNull(context)) +
            HearthpaneMarketTools.build(requireNotNull(context)) +
            (MemoryTool.NAME to ::memoryTool))
  }

  /** `hearthpane.memory`, bound per call so it always sees the conversation under way. */
  private fun memoryTool(args: String?): JSONObject {
    val ctx = requireNotNull(context).applicationContext
    return JarvisMemory.handoff(ctx).tool(show = { JarvisKnowsActivity.open(ctx) }).invoke(args)
  }
  private val reminderTool by lazy {
    val ctx = requireNotNull(context).applicationContext
    HearthpaneReminders(
        who = { WhosThere.state.value },
        state = { ReminderStore.current(ctx) },
        add = { draft -> RemindersFeature.add(ctx, draft, ReminderLimits.JARVIS, ReminderStore.Source.JARVIS) },
        setDone = { id -> RemindersFeature.setDone(ctx, id, true, ReminderStore.Source.JARVIS) },
        use24 = { android.text.format.DateFormat.is24HourFormat(ctx) },
    )
  }

  override fun onCreate(): Boolean = true

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
    val ctx = context ?: return null
    // Binder thread: Calling and Reminders state come from deferred startup steps.
    DeferredStartup.ensureStarted()
    val uidPackages = ctx.packageManager.getPackagesForUid(Binder.getCallingUid())?.toList().orEmpty()
    if (method !in METHODS || !allowedCaller(uidPackages)) {
      Log.w(TAG, "refused call $method from $uidPackages")
      return null
    }
    if (method == JarvisPrivacy.METHOD) return Bundle().apply { putString(EXTRA_RESULT, privacy().toString()) }
    if (method in MEMORY_METHODS) return Bundle().apply { putString(EXTRA_RESULT, memory(ctx, method, extras).toString()) }
    val result = tools.invoke(arg, extras?.getString(EXTRA_ARGS))
    Log.i(TAG, "$arg → ${if (result.has("error")) "error" else "ok"} (${result.toString().length} chars)")
    return Bundle().apply { putString(EXTRA_RESULT, result.toString()) }
  }

  /** Jarvis's end-of-conversation handoff and the note at its open; never logs what was said. */
  private fun memory(ctx: android.content.Context, method: String, extras: Bundle?): JSONObject {
    val args = extras?.getString(EXTRA_ARGS)
    val handoff = JarvisMemory.handoff(ctx.applicationContext)
    val result = runCatching {
      when (method) {
        MemoryHandoff.METHOD_CONTEXT -> handoff.context(args)
        MemoryHandoff.METHOD_OBSERVE -> handoff.observe(args)
        else -> handoff.undo(args)
      }
    }.getOrElse { JSONObject().put("error", "memory unavailable") }
    Log.i(TAG, "$method → ${if (result.has("error")) "error" else "ok"} (${result.toString().length} chars)")
    return result
  }

  /** Facts for Jarvis's screenshot guard; never pixels, and nothing the model sees. */
  private fun privacy(): JSONObject {
    val facts = JarvisPrivacy.Facts(
        screen = runCatching { HearthpaneScreens.current()?.id?.invoke() }.getOrNull(),
        launcherInFront = JarvisPrivacy.launcherStarted(),
        dreaming = HearthpaneWake.dreaming(),
        callActive = runCatching { Calling.state.value.busy }.getOrDefault(true),
        cameraInUse = HearthpaneWake.cameraInUse(),
        cameraFeedShowing = DoorbellCardOverlay.showing,
    )
    Log.i(TAG, "privacy → $facts")
    return JarvisPrivacy.json(facts)
  }

  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?,
      sort: String?): Cursor? = null
  override fun getType(uri: Uri): String? = null
  override fun insert(uri: Uri, values: ContentValues?): Uri? = null
  override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0
  override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 0

  companion object {
    private const val TAG = "HearthpaneTools"
    const val JARVIS_PACKAGE = "com.portal.assistant"
    const val METHOD_INVOKE = "invoke"
    private val MEMORY_METHODS =
        setOf(MemoryHandoff.METHOD_CONTEXT, MemoryHandoff.METHOD_OBSERVE, MemoryHandoff.METHOD_FORGET)
    private val METHODS = setOf(METHOD_INVOKE, JarvisPrivacy.METHOD) + MEMORY_METHODS
    const val EXTRA_ARGS = "com.portal.assistant.tools.extra.ARGS"
    const val EXTRA_RESULT = "com.portal.assistant.tools.extra.RESULT"

    /** Only Jarvis's own uid: every package sharing the calling uid must be Jarvis. */
    fun allowedCaller(uidPackages: List<String>): Boolean =
        uidPackages.isNotEmpty() && uidPackages.all { it == JARVIS_PACKAGE }
  }
}
