/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

internal enum class SoundPreviewPhase { IDLE, STARTING, PLAYING, FAILED }

internal data class SoundPreviewState(
    val label: String? = null,
    val phase: SoundPreviewPhase = SoundPreviewPhase.IDLE
)

/**
 * Owns at most one manual preview. All calls, including factory callbacks, belong
 * on the main thread. Factories must release their own partial allocations if
 * they throw before returning an owner.
 */
internal class SoundPreviewSession(private val onState: (SoundPreviewState) -> Unit) {
    var state: SoundPreviewState = SoundPreviewState()
        private set

    private class Preview {
        var resource: AutoCloseable? = null
    }

    private var active = false
    private var disposed = false
    private var current: Preview? = null
    private var revision = 0L

    fun setActive(active: Boolean) {
        if (disposed) return
        this.active = active
        if (!active) stop()
    }

    fun play(
        label: String,
        start: (onPlaying: () -> Unit, onFinished: (Boolean) -> Unit) -> AutoCloseable
    ) {
        if (!active || disposed) return
        val request = ++revision
        val previous = current
        current = null // Invalidate callbacks before invoking external close code.
        release(previous)
        if (request != revision || !active || disposed) return

        val preview = Preview()
        current = preview
        publish(SoundPreviewState(label, SoundPreviewPhase.STARTING))
        if (current !== preview) return // A state observer may cancel synchronously.

        try {
            val resource = start(
                {
                    if (current === preview && state.phase == SoundPreviewPhase.STARTING) {
                        publish(SoundPreviewState(label, SoundPreviewPhase.PLAYING))
                    }
                },
                { success -> finish(preview, label, success) }
            )
            // Completion or cancellation can happen before the factory returns.
            // Such a resource was never adopted and must be closed immediately.
            if (current === preview) preview.resource = resource else close(resource)
        } catch (_: Exception) {
            finish(preview, label, false)
        }
    }

    fun stop() {
        val request = ++revision
        val previous = current
        current = null
        release(previous)
        if (request == revision) publish(SoundPreviewState())
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        active = false
        stop()
    }

    private fun finish(preview: Preview, label: String, success: Boolean) {
        if (current !== preview) return
        val request = ++revision
        current = null
        release(preview)
        if (request == revision) {
            publish(if (success) SoundPreviewState() else SoundPreviewState(label, SoundPreviewPhase.FAILED))
        }
    }

    private fun release(preview: Preview?) {
        val resource = preview?.resource ?: return
        preview.resource = null
        close(resource)
    }

    private fun close(resource: AutoCloseable) {
        try {
            resource.close()
        } catch (_: Exception) {
            // A failed release must not prevent the next preview or be retried.
        }
    }

    private fun publish(next: SoundPreviewState) {
        if (state == next) return
        state = next
        onState(next)
    }
}
