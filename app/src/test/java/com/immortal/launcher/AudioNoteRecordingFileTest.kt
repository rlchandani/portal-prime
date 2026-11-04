/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*

/** Real AudioNote and files; Android recorder calls are mocked, with no microphone access. */
class AudioNoteRecordingFileTest {
    private val previous = "previous saved memo".toByteArray()
    private val replacement = "finalized replacement memo".toByteArray()

    private class Attempt {
        var prepareFails = false
        var startFails = false
        var stopFails = false
        var releaseFails = false
        var empty = false
        var cancelDuringPrepare = false
        var now = 0L
        lateinit var output: File
    }

    private fun fixture(old: Boolean = true, action: (AudioNote, Attempt, File) -> Unit) {
        val dir = Files.createTempDirectory("immortal-memo-test-").toFile()
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(dir)
        if (old) NotesConfig.audioFile(context).writeBytes(previous)
        val otherStage = File(dir, "another-owner.m4a.part").apply { writeText("untouched") }
        val attempt = Attempt()
        lateinit var note: AudioNote
        try {
            mockStatic(Looper::class.java).use {
                mockStatic(Log::class.java).use {
                    mockStatic(SystemClock::class.java).use { clock ->
                        clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { attempt.now }
                        mockConstruction(Handler::class.java) { handler, _ ->
                            doAnswer { invocation ->
                                invocation.getArgument<Runnable>(0).run()
                                true
                            }.`when`(handler).post(any(Runnable::class.java))
                        }.use {
                            mockConstruction(MediaRecorder::class.java) { recorder, _ ->
                                doAnswer { invocation ->
                                    attempt.output = File(invocation.getArgument<String>(0))
                                    null
                                }.`when`(recorder).setOutputFile(anyString())
                                doAnswer {
                                    attempt.output.writeText("partial")
                                    if (attempt.cancelDuringPrepare) note.release()
                                    if (attempt.prepareFails) throw IllegalStateException("prepare failed")
                                    null
                                }.`when`(recorder).prepare()
                                doAnswer {
                                    if (attempt.startFails) throw IllegalStateException("start failed")
                                    null
                                }.`when`(recorder).start()
                                doAnswer {
                                    if (attempt.stopFails) throw IllegalStateException("stop failed")
                                    attempt.output.writeBytes(if (attempt.empty) byteArrayOf() else replacement)
                                    null
                                }.`when`(recorder).stop()
                                doAnswer {
                                    if (attempt.releaseFails) throw IllegalStateException("release failed")
                                    null
                                }.`when`(recorder).release()
                            }.use {
                                note = AudioNote(context)
                                // Mockito's native construction scopes are thread-local. Run the actual
                                // coordinator inline here; its concurrency has separate unchanged tests.
                                AudioNote::class.java.getDeclaredField("work").apply {
                                    isAccessible = true
                                    set(note, AudioNoteWork(Executor { it.run() }))
                                }
                                try {
                                    action(note, attempt, dir)
                                } finally {
                                    note.release()
                                }
                            }
                        }
                    }
                }
            }
            assertEquals("untouched", otherStage.readText())
        } finally {
            Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------"))
            dir.deleteRecursively()
        }
    }

    private fun assertPrevious(dir: File) {
        assertArrayEquals(previous, File(dir, "leave_note.m4a").readBytes())
    }

    private fun assertOwnStageCleaned(attempt: Attempt, dir: File) {
        assertNotEquals(File(dir, "leave_note.m4a"), attempt.output)
        assertFalse(attempt.output.exists())
    }

    @Test fun savedMemoRemainsUnchangedWhileReplacementRecords() = fixture { note, attempt, dir ->
        assertTrue(note.startRecording())
        assertPrevious(dir)
        assertNotEquals(File(dir, "leave_note.m4a"), attempt.output)
        assertEquals(dir, attempt.output.parentFile)
    }

    @Test fun prepareFailurePreservesSavedMemo() = fixture { note, attempt, dir ->
        attempt.prepareFails = true
        assertFalse(note.startRecording())
        assertPrevious(dir)
        assertOwnStageCleaned(attempt, dir)
    }

    @Test fun startFailurePreservesSavedMemo() = fixture { note, attempt, dir ->
        attempt.startFails = true
        assertFalse(note.startRecording())
        assertPrevious(dir)
        assertOwnStageCleaned(attempt, dir)
    }

    @Test fun cancellationDuringPreparePreservesSavedMemo() = fixture { note, attempt, dir ->
        attempt.cancelDuringPrepare = true
        assertFalse(note.startRecording())
        assertPrevious(dir)
        assertOwnStageCleaned(attempt, dir)
    }

    @Test fun tooShortReplacementPreservesSavedMemo() = fixture { note, attempt, dir ->
        assertTrue(note.startRecording())
        attempt.now = 100
        assertFalse(note.stopRecording())
        assertPrevious(dir)
        assertOwnStageCleaned(attempt, dir)
    }

    @Test fun stopFailurePreservesSavedMemo() = fixture { note, attempt, dir ->
        assertTrue(note.startRecording())
        attempt.now = 600
        attempt.stopFails = true
        assertFalse(note.stopRecording())
        assertPrevious(dir)
        assertOwnStageCleaned(attempt, dir)
    }

    @Test fun releaseFailurePreservesSavedMemo() = fixture { note, attempt, dir ->
        assertTrue(note.startRecording())
        attempt.now = 600
        attempt.releaseFails = true
        assertFalse(note.stopRecording())
        assertPrevious(dir)
        assertOwnStageCleaned(attempt, dir)
    }

    @Test fun emptyReplacementPreservesSavedMemo() = fixture { note, attempt, dir ->
        assertTrue(note.startRecording())
        attempt.now = 600
        attempt.empty = true
        assertFalse(note.stopRecording())
        assertPrevious(dir)
        assertOwnStageCleaned(attempt, dir)
    }

    @Test fun failedRenamePreservesSavedMemo() = fixture { note, attempt, dir ->
        assertTrue(note.startRecording())
        attempt.now = 600
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("r-x------"))
        try {
            assertFalse(note.stopRecording())
            assertPrevious(dir)
        } finally {
            Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test fun validReplacementPublishesFinalizedBytes() = fixture { note, attempt, dir ->
        assertTrue(note.startRecording())
        attempt.now = 600
        assertTrue(note.stopRecording())
        assertArrayEquals(replacement, File(dir, "leave_note.m4a").readBytes())
        assertOwnStageCleaned(attempt, dir)
    }

    @Test fun firstValidMemoPublishesFinalizedBytes() = fixture(old = false) { note, attempt, dir ->
        assertTrue(note.startRecording())
        attempt.now = 600
        assertTrue(note.stopRecording())
        assertArrayEquals(replacement, File(dir, "leave_note.m4a").readBytes())
        assertOwnStageCleaned(attempt, dir)
    }

    @Test fun failedFirstMemoLeavesNoSavedFile() = fixture(old = false) { note, attempt, dir ->
        attempt.prepareFails = true
        assertFalse(note.startRecording())
        assertFalse(File(dir, "leave_note.m4a").exists())
        assertOwnStageCleaned(attempt, dir)
    }

    @Test fun idleStopRetainsSavedMemo() = fixture { note, _, dir ->
        assertTrue(note.stopRecording())
        assertPrevious(dir)
    }

    @Test fun releaseStillFinalizesValidRecording() = fixture { note, attempt, dir ->
        assertTrue(note.startRecording())
        attempt.now = 600
        note.release()
        assertArrayEquals(replacement, File(dir, "leave_note.m4a").readBytes())
        assertOwnStageCleaned(attempt, dir)
    }

    @Test fun explicitDeletionStillWinsAfterFinalization() = fixture { note, attempt, dir ->
        assertTrue(note.startRecording())
        attempt.now = 600
        var deleted = false
        note.deleteMemoAsync { deleted = it }
        assertTrue(deleted)
        assertFalse(File(dir, "leave_note.m4a").exists())
        assertOwnStageCleaned(attempt, dir)
    }
}
