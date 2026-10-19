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

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConnectionPcmSinkTest {
    @Test public void fragmentedFramesAndPositivePartialWritesPreserveEveryByte() throws Exception {
        Fake output = new Fake(); output.partial = 1;
        try (ConnectionPcmSink sink = new ConnectionPcmSink(() -> output, () -> fail("failure"))) {
            assertTrue(sink.start());
            byte[] pcm = {0,1,2,3,4,5,6,7,8,9,10,11};
            sink.consume(pcm, 0, 3); sink.consume(pcm, 3, 2); sink.consume(pcm, 5, 7);
            assertArrayEquals(pcm, output.bytes.toByteArray());
            assertEquals(3, sink.drainedFrames()); assertEquals(3, sink.renderedFrames());
        }
        assertTrue(output.closed.await(1, TimeUnit.SECONDS));
    }

    @Test public void suppressionContinuouslyDrainsWithoutAutomaticResume() throws Exception {
        Fake output = new Fake(); AtomicInteger created = new AtomicInteger();
        try (ConnectionPcmSink sink = new ConnectionPcmSink(() -> { created.incrementAndGet(); return output; }, () -> {})) {
            sink.consume(new byte[5], 0, 5); sink.consume(new byte[3], 0, 3);
            assertEquals(2, sink.drainedFrames()); assertEquals(0, created.get());
            assertTrue(sink.start()); sink.consume(new byte[4], 0, 4);
            sink.suppress(); assertFalse(sink.isActive());
            assertTrue(output.closed.await(1, TimeUnit.SECONDS));
            sink.consume(new byte[9600], 0, 9600);
            assertEquals(2403, sink.drainedFrames()); assertEquals(1, sink.renderedFrames());
            assertEquals(1, created.get()); assertFalse(sink.isActive());
        }
    }

    @Test public void zeroNegativeAndOversizedWritesFailClosedOnce() throws Exception {
        for (int result : new int[]{0, -1, 5}) {
            Fake output = new Fake(); output.result = result; AtomicInteger failures = new AtomicInteger();
            try (ConnectionPcmSink sink = new ConnectionPcmSink(() -> output, failures::incrementAndGet)) {
                assertTrue(sink.start()); sink.consume(new byte[4], 0, 4);
                assertFalse(sink.isActive()); assertEquals(1, failures.get());
                sink.consume(new byte[4], 0, 4);
                assertEquals(2, sink.drainedFrames()); assertEquals(0, sink.renderedFrames());
                assertEquals(1, output.writes); assertEquals(1, failures.get());
            }
            assertTrue(output.closed.await(1, TimeUnit.SECONDS));
        }
    }

    @Test public void creationAndPlayFailuresCannotActivateOutput() throws Exception {
        AtomicInteger failures = new AtomicInteger();
        try (ConnectionPcmSink sink = new ConnectionPcmSink(() -> { throw new IllegalStateException(); }, failures::incrementAndGet)) {
            assertFalse(sink.start()); assertFalse(sink.isActive()); assertEquals(1, failures.get());
        }
        Fake output = new Fake(); output.badPlay = true;
        try (ConnectionPcmSink sink = new ConnectionPcmSink(() -> output, failures::incrementAndGet)) {
            assertFalse(sink.start()); assertFalse(sink.isActive()); assertEquals(2, failures.get());
        }
        assertTrue(output.closed.await(1, TimeUnit.SECONDS));
    }

    @Test(timeout = 3000) public void suppressionDoesNotWaitForBlockedWriterOrCloseAndOldFeedCannotReachNewOutput() throws Exception {
        Fake old = new Fake(); old.blockWrite = true; old.blockClose = true;
        Fake next = new Fake(); AtomicInteger creations = new AtomicInteger();
        try (ConnectionPcmSink sink = new ConnectionPcmSink(() -> creations.getAndIncrement() == 0 ? old : next, () -> fail("cancelled write"))) {
            assertTrue(sink.start());
            Thread reader = new Thread(() -> sink.consume(new byte[8], 0, 8)); reader.setDaemon(true); reader.start();
            try {
                assertTrue(old.enteredWrite.await(1, TimeUnit.SECONDS));
                sink.suppress(); assertFalse(sink.isActive());
                assertTrue(old.enteredClose.await(1, TimeUnit.SECONDS));
                assertTrue(sink.hasUnreleasedOutput());
                assertFalse(sink.start()); assertEquals(1, creations.get());
                old.releaseClose.countDown();
                startEventually(sink);
                old.releaseWrite.countDown(); reader.join(1000); assertFalse(reader.isAlive());
                assertEquals(0, next.writes);
                sink.consume(new byte[4], 0, 4); assertEquals(1, next.writes);
            } finally { old.releaseClose.countDown(); old.releaseWrite.countDown(); reader.join(1000); }
        }
    }

    @Test public void aCarriedFrameFromRetiredOutputCannotPlayOnReplacement() throws Exception {
        Fake old = new Fake(); Fake next = new Fake(); AtomicInteger count = new AtomicInteger();
        try (ConnectionPcmSink sink = new ConnectionPcmSink(() -> count.getAndIncrement() == 0 ? old : next, () -> {})) {
            assertTrue(sink.start()); sink.consume(new byte[]{1,2,3}, 0, 3);
            sink.suppress(); startEventually(sink);
            sink.consume(new byte[]{4}, 0, 1); assertEquals(0, next.writes);
            sink.consume(new byte[]{5,6,7,8}, 0, 4);
            assertArrayEquals(new byte[]{5,6,7,8}, next.bytes.toByteArray());
            assertEquals(2, sink.drainedFrames()); assertEquals(1, sink.renderedFrames());
        }
    }

    @Test public void repeatedExplicitCyclesStayBoundedAndClosePermanentlyDisablesStart() throws Exception {
        List<Fake> outputs = new ArrayList<>(); AtomicInteger alive = new AtomicInteger(); AtomicInteger maximum = new AtomicInteger();
        Set<Thread> closers = ConcurrentHashMap.newKeySet();
        ConnectionPcmSink sink = new ConnectionPcmSink(() -> {
            int current = alive.incrementAndGet(); maximum.accumulateAndGet(current, Math::max);
            Fake output = new Fake() { @Override public void close() {
                closers.add(Thread.currentThread()); super.close(); alive.decrementAndGet();
            } };
            outputs.add(output); return output;
        }, () -> fail("failure"));
        try {
            for (int i = 0; i < 100; i++) {
                startEventually(sink); assertTrue(sink.start());
                sink.consume(new byte[4], 0, 4); sink.suppress();
            }
            assertEquals(100, outputs.size()); assertEquals(1, maximum.get());
            assertEquals(1, closers.size());
            assertEquals(100, sink.renderedFrames());
        } finally { sink.close(); }
        assertFalse(sink.start());
        assertTrue(outputs.get(99).closed.await(1, TimeUnit.SECONDS));
    }

    @Test(timeout = 3000) public void disposeDuringCreationStillRetiresCandidateWithAnExistingCloser() throws Exception {
        Fake first = new Fake(), candidate = new Fake();
        CountDownLatch creating = new CountDownLatch(1), releaseFactory = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        ConnectionPcmSink sink = new ConnectionPcmSink(() -> {
            if (created.incrementAndGet() == 1) return first;
            creating.countDown(); Fake.await(releaseFactory); return candidate;
        }, () -> fail("cancelled creation"));
        assertTrue(sink.start()); sink.suppress();
        assertTrue(first.closed.await(1, TimeUnit.SECONDS));
        AtomicBoolean accepted = new AtomicBoolean();
        Thread starter = new Thread(() -> {
            try {
                while (created.get() < 2) {
                    if (sink.start()) { accepted.set(true); break; }
                    Thread.sleep(1);
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        starter.setDaemon(true); starter.start();
        try {
            assertTrue(creating.await(1, TimeUnit.SECONDS));
            assertTrue(sink.hasUnreleasedOutput()); sink.close();
            assertTrue(sink.hasUnreleasedOutput());
            releaseFactory.countDown(); starter.join(1000); assertFalse(starter.isAlive());
            assertFalse(accepted.get());
            assertTrue(candidate.closed.await(1, TimeUnit.SECONDS));
            awaitReleased(sink); assertFalse(sink.hasUnreleasedOutput());
            assertFalse(sink.isActive()); assertFalse(sink.start());
        } finally { releaseFactory.countDown(); sink.close(); starter.join(1000); }
    }

    @Test public void failedClosePermanentlyPreventsASecondOutput() throws Exception {
        AtomicInteger created = new AtomicInteger(), closes = new AtomicInteger();
        CountDownLatch failed = new CountDownLatch(1);
        Fake output = new Fake() { @Override public void close() {
            closes.incrementAndGet(); throw new IllegalStateException("unconfirmed close");
        }};
        try (ConnectionPcmSink sink = new ConnectionPcmSink(() -> { created.incrementAndGet(); return output; }, failed::countDown)) {
            assertTrue(sink.start()); sink.suppress();
            assertTrue(failed.await(1, TimeUnit.SECONDS));
            assertTrue(sink.hasUnreleasedOutput());
            assertFalse(sink.start()); assertFalse(sink.isActive());
            assertEquals(1, created.get()); assertEquals(1, closes.get());
        }
    }

    @Test public void retiredSourceCannotFeedReplacementAndCarryResetsPerConnection() throws Exception {
        Fake old = new Fake(), next = new Fake(); AtomicInteger count = new AtomicInteger();
        try (ConnectionPcmSink sink = new ConnectionPcmSink(() -> count.getAndIncrement() == 0 ? old : next, () -> {})) {
            sink.selectConnection(1); assertTrue(sink.start(10));
            sink.consume(1, new byte[]{1,2,3}, 0, 3); sink.suppress(); awaitReleased(sink);
            sink.selectConnection(2); assertTrue(sink.start(20));
            sink.consume(1, new byte[]{4,5,6,7}, 0, 4);
            sink.consume(2, new byte[]{8,9,10,11}, 0, 4);
            assertArrayEquals(new byte[]{8,9,10,11}, next.bytes.toByteArray());
            assertEquals(1, sink.drainedFrames()); assertEquals(1, sink.renderedFrames());
        }
    }

    @Test public void delayedFailedCloseReportsRetiredSessionEpoch() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), failed = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicLong token = new java.util.concurrent.atomic.AtomicLong(-1);
        Fake old = new Fake() { @Override public void close() {
            entered.countDown(); Fake.await(release); throw new IllegalStateException();
        }};
        try (ConnectionPcmSink sink = new ConnectionPcmSink(() -> old, (long epoch) -> { token.set(epoch); failed.countDown(); })) {
            assertTrue(sink.start(17)); sink.suppress(); assertTrue(entered.await(1, TimeUnit.SECONDS));
            sink.selectConnection(99); assertFalse(sink.start(99)); release.countDown();
            assertTrue(failed.await(1, TimeUnit.SECONDS)); assertEquals(17, token.get());
            assertTrue(sink.hasUnreleasedOutput());
        } finally { release.countDown(); }
    }

    private static void awaitReleased(ConnectionPcmSink sink) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (sink.hasUnreleasedOutput()) {
            if (System.nanoTime() >= end) fail("output was not released"); Thread.sleep(1);
        }
    }
    private static void startEventually(ConnectionPcmSink sink) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!sink.start()) { if (System.nanoTime() >= end) fail("close did not complete"); Thread.sleep(1); }
    }
    private static class Fake implements ConnectionPcmSink.Output {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final CountDownLatch closed = new CountDownLatch(1), enteredWrite = new CountDownLatch(1),
            enteredClose = new CountDownLatch(1), releaseWrite = new CountDownLatch(1), releaseClose = new CountDownLatch(1);
        int partial = Integer.MAX_VALUE, result = Integer.MIN_VALUE, writes;
        boolean badPlay, blockWrite, blockClose;
        public void play() { if (badPlay) throw new IllegalStateException(); }
        public int write(byte[] data, int offset, int length) {
            writes++; enteredWrite.countDown(); if (blockWrite) await(releaseWrite);
            if (result != Integer.MIN_VALUE) return result;
            int count = Math.min(length, partial); bytes.write(data, offset, count); return count;
        }
        public void close() { enteredClose.countDown(); if (blockClose) await(releaseClose); closed.countDown(); }
        private static void await(CountDownLatch latch) {
            try { if (!latch.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("test blocked"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        }
    }
}
