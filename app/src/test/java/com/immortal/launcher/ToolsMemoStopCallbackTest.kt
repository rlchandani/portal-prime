/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*

/** Executes the compiled Notes local stop function, without composing a screen or native audio. */
class ToolsMemoStopCallbackTest {
    private class State {
        val recording = mutableStateOf(true)
        val starting = mutableStateOf(false)
        val stopping = mutableStateOf(false)
        val message = mutableStateOf<String?>(null)
        val deleting = mutableStateOf(false)
        val playing = mutableStateOf(false)
        val preparing = mutableStateOf(false)
        val hasMemo = mutableStateOf(false)
        val loading = mutableStateOf(false)
        val clearText = mutableStateOf(false)
        val discardText = mutableStateOf(false)
        val deleteMemo = mutableStateOf(false)

        fun stop(audio: AudioNote, context: Context) {
            val method = Class.forName("com.immortal.launcher.ApprovedToolsAudioKt")
                .declaredMethods.single { it.name == "ToolsNotesContent\$stopAudio" }
            method.isAccessible = true
            method.invoke(null, audio, recording, starting, stopping, message, deleting, playing,
                preparing, context, hasMemo, loading, clearText, discardText, deleteMemo)
        }
    }

    private fun fixture(saved: Boolean = true, action: (State, () -> Unit, MutableList<(Boolean) -> Unit>) -> Unit) {
        val dir = Files.createTempDirectory("immortal-memo-ui-test-").toFile()
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(dir)
        if (saved) NotesConfig.audioFile(context).writeText("previous memo")
        val audio = mock(AudioNote::class.java)
        val callbacks = mutableListOf<(Boolean) -> Unit>()
        doAnswer { invocation ->
            callbacks.add(invocation.getArgument(0))
            null
        }.`when`(audio).stopAsync(any<(Boolean) -> Unit>() ?: {})
        val state = State()
        try {
            action(state, { state.stop(audio, context) }, callbacks)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun repeatedStopRetainsFirstOutcomeAndPreviousMemoControls() = fixture { state, stop, callbacks ->
        stop()
        stop()
        assertEquals(1, callbacks.size)
        assertTrue(state.stopping.value)
        callbacks.single()(false)
        assertFalse(state.stopping.value)
        assertTrue(state.hasMemo.value)
        assertEquals("The new recording couldn't be saved. Your previous memo is still available.", state.message.value)
    }

    @Test fun successfulRecordingReportsSaved() = fixture { state, stop, callbacks ->
        stop()
        callbacks.single()(true)
        assertFalse(state.stopping.value)
        assertTrue(state.hasMemo.value)
        assertEquals("Voice memo saved.", state.message.value)
    }

    @Test fun failedFirstRecordingKeepsRetryMessage() = fixture(saved = false) { state, stop, callbacks ->
        stop()
        callbacks.single()(false)
        assertFalse(state.hasMemo.value)
        assertEquals("The recording was too short or couldn't be saved. Try recording again.", state.message.value)
    }

    @Test fun deletionOwnsItsStateWhileStopSettles() = fixture { state, stop, callbacks ->
        stop()
        state.deleting.value = true
        state.message.value = "Deleting voice memo…"
        callbacks.single()(false)
        assertEquals("Deleting voice memo…", state.message.value)
        assertFalse(state.hasMemo.value)
    }
}
