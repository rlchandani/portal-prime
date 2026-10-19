/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// SPDX-License-Identifier: MIT
// In this upstream tree the MIT text is in LICENSE-MIT. The combined
// Snapdroid application remains governed by upstream GPL-3.0-or-later.
package de.badaix.snapcast;


/**
 * Single-reader, continuously drained 48 kHz / signed 16-bit LE / stereo PCM.
 * No audio-focus policy and no automatic recovery. Output writes run on the PCM reader.
 * Suppress detaches immediately; one persistent closer retires outputs without waiting for writes.
 * Start fails closed while a prior close is outstanding. Call close on service destruction.
 */
public final class ConnectionPcmSink implements AutoCloseable {
    public interface Output {
        void play();
        int write(byte[] data, int offset, int length);
        /** Must interrupt an outstanding write; invoked only on the sink's close worker. */
        void close();
    }
    public interface Factory { Output create(); }
    public interface FailureListener { void accept(long outputEpoch); }
    private static final int FRAME_BYTES = 4;
    private final Factory factory;
    private final FailureListener onFailure;
    private final Object lock = new Object();
    private final Object readerLock = new Object();
    private volatile long connectionGeneration;
    private long carryGeneration;
    private boolean carryGenerationSet;
    private volatile Session active;
    private Session closing;
    private boolean creating, closed;
    private long generation;
    private Thread closer;
    // Only consume's single reader owns carry and write accounting.
    private final byte[] carry = new byte[FRAME_BYTES];
    private int carryLength;
    private Session carrySession;
    private volatile long drained, rendered;
    private static final class Session {
        final Output output;
        long acceptedBytes;
        final long outputEpoch, connectionGeneration;
        Session(Output output, long outputEpoch, long connectionGeneration) {
            this.output = output; this.outputEpoch = outputEpoch; this.connectionGeneration = connectionGeneration;
        }
    }

    public ConnectionPcmSink(Factory factory, Runnable onFailure) {
        this(factory, (FailureListener) epoch -> onFailure.run());
        if (onFailure == null) throw new NullPointerException();
    }
    public ConnectionPcmSink(Factory factory, FailureListener onFailure) {
        if (factory == null || onFailure == null) throw new NullPointerException();
        this.factory = factory; this.onFailure = onFailure;
    }
    public void selectConnection(long generation) {
        synchronized (lock) { connectionGeneration = generation; ++this.generation; }
    }
    /** Explicit user admission only; true also means the existing output is already active. */
    public boolean start() { return start(0); }
    public boolean start(long outputEpoch) {
        final long ticket, sourceGeneration;
        synchronized (lock) {
            if (closed || creating || closing != null) return false;
            if (active != null) return active.connectionGeneration == connectionGeneration;
            creating = true; ticket = ++generation; sourceGeneration = connectionGeneration;
        }
        Session candidate = null;
        boolean failed = false;
        try {
            Output output = factory.create();
            if (output == null) throw new IllegalStateException("No PCM output");
            candidate = new Session(output, outputEpoch, sourceGeneration);
            output.play();
        } catch (RuntimeException e) { failed = true; }
        boolean accepted, notify;
        synchronized (lock) {
            creating = false;
            accepted = !failed && !closed && generation == ticket;
            notify = failed && !closed && generation == ticket;
            if (accepted) active = candidate;
            else if (candidate != null) retireLocked(candidate);
            lock.notifyAll();
        }
        if (notify) notifyFailure(outputEpoch);
        return accepted;
    }
    public void suppress() {
        synchronized (lock) {
            ++generation;
            Session old = active; active = null;
            if (old != null) retireLocked(old);
        }
    }
    /** Permanent disposal is also nonblocking; no future Start can activate this sink. */
    @Override public void close() {
        synchronized (lock) {
            closed = true; ++generation;
            Session old = active; active = null;
            if (old != null) retireLocked(old);
            lock.notifyAll();
        }
    }
    public boolean isActive() { return active != null; }
    /** Teardown is unconfirmed while creation or close is outstanding, including failed close. */
    public boolean hasUnreleasedOutput() {
        synchronized (lock) { return active != null || creating || closing != null; }
    }
    /** Complete source frames consumed, including frames discarded during suppression. */
    public long drainedFrames() { return drained; }
    /** Complete frames accepted by outputs, not proof of audible playback or buffer drain. */
    public long renderedFrames() { return rendered; }

    /** One PCM reader only. Retains fewer than four bytes; no growing buffer or resume queue. */
    public void consume(byte[] data, int offset, int length) {
        consume(connectionGeneration, data, offset, length);
    }
    public void consume(long sourceGeneration, byte[] data, int offset, int length) {
        synchronized (readerLock) {
            if (sourceGeneration != connectionGeneration) return;
            if (!carryGenerationSet || carryGeneration != sourceGeneration) {
                carryLength = 0; carrySession = null;
                carryGeneration = sourceGeneration; carryGenerationSet = true;
            }
            consumeCurrent(sourceGeneration, data, offset, length);
        }
    }
    private void consumeCurrent(long sourceGeneration, byte[] data, int offset, int length) {
        if (data == null) throw new NullPointerException();
        if (offset < 0 || length < 0 || offset > data.length - length) throw new IndexOutOfBoundsException();
        Session target = active; // A read can never redirect its remainder to a replacement output.
        drained += ((long) carryLength + length) / FRAME_BYTES;
        if (carryLength != 0) {
            int count = Math.min(FRAME_BYTES - carryLength, length);
            System.arraycopy(data, offset, carry, carryLength, count);
            carryLength += count; offset += count; length -= count;
            if (carryLength < FRAME_BYTES) return;
            if (carrySession == target) feed(sourceGeneration, target, carry, 0, FRAME_BYTES);
            carryLength = 0; carrySession = null;
        }
        int complete = length - length % FRAME_BYTES;
        if (complete != 0) feed(sourceGeneration, target, data, offset, complete);
        offset += complete; length -= complete;
        if (length != 0) {
            System.arraycopy(data, offset, carry, 0, length);
            carryLength = length; carrySession = target;
        }
    }
    private void feed(long sourceGeneration, Session target, byte[] data, int offset, int length) {
        while (target != null && active == target && sourceGeneration == connectionGeneration &&
                target.connectionGeneration == sourceGeneration && length != 0) {
            final int count;
            try { count = target.output.write(data, offset, length); }
            catch (RuntimeException e) { fail(target); return; }
            if (count <= 0 || count > length) { fail(target); return; }
            long before = target.acceptedBytes / FRAME_BYTES;
            target.acceptedBytes += count;
            rendered += target.acceptedBytes / FRAME_BYTES - before;
            offset += count; length -= count;
        }
    }
    private void fail(Session target) {
        boolean notify = false;
        synchronized (lock) {
            if (active == target) {
                active = null; ++generation; retireLocked(target); notify = true;
            }
        }
        if (notify) notifyFailure(target.outputEpoch);
    }
    private void notifyFailure(long outputEpoch) {
        try { onFailure.accept(outputEpoch); } catch (RuntimeException ignored) { }
    }
    private void retireLocked(Session old) {
        // At most one retired output: Start is denied until this slot is released.
        if (closing != null) throw new IllegalStateException("PCM close already outstanding");
        closing = old;
        if (closer == null) {
            closer = new Thread(this::closeLoop, "ConnectionPcmSink-close");
            closer.setDaemon(true); closer.start();
        }
        lock.notifyAll();
    }
    private void closeLoop() {
        while (true) {
            Session old;
            synchronized (lock) {
                while (closing == null && (!closed || creating)) {
                    try { lock.wait(); } catch (InterruptedException ignored) { }
                }
                if (closing == null) return;
                old = closing;
            }
            try { old.output.close(); }
            catch (RuntimeException failedClose) {
                // An unconfirmed teardown cannot authorize a replacement output.
                synchronized (lock) { closed = true; ++generation; lock.notifyAll(); }
                notifyFailure(old.outputEpoch);
                return;
            }
            synchronized (lock) { closing = null; lock.notifyAll(); }
        }
    }
}
