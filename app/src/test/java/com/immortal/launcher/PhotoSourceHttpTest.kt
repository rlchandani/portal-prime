/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PhotoSourceHttpTest {
    @Test fun announcedOversizeRejectsBeforeAcquiringBodyIncludingLongMaxValue() {
        for (announced in listOf(9L, Long.MAX_VALUE)) {
            val body = Body("{}".toByteArray())
            val connection = prepareFakeConnection(body, announced)

            expectIOException { PhotoSourceHttp.readText(connection, maxBytes = 8) }

            assertEquals("announced=$announced", 0, connection.bodyAcquisitions)
            assertEquals(0, body.delivered)
            assertEquals(0, body.closeCalls)
        }
    }

    @Test fun unknownLengthAtExactBudgetReturnsEntireBodyAndClosesIt() {
        val text = """{"a":1}"""
        val body = Body(text.toByteArray(), maximumDelivered = text.length)
        val connection = prepareFakeConnection(body)

        assertEquals(text, PhotoSourceHttp.readText(connection, maxBytes = text.length))

        assertEquals(text.length, body.delivered)
        assertTrue("Must establish EOF rather than accept a truncated prefix", body.eofReads > 0)
        assertTrue(body.largestBulkRequest <= text.length + 1)
        assertEquals(1, connection.bodyAcquisitions)
        assertEquals(1, body.closeCalls)
    }

    @Test fun unknownOversizeConsumesOnlyBudgetPlusOneFromAnUnmaterializedHugeBody() {
        val limit = 7
        // The fake generates bytes on demand: this test never allocates the huge response.
        val body = Body(
            byteArrayOf(),
            totalBytes = 1_000_000_000L,
            maximumDelivered = limit + 1
        )
        val connection = prepareFakeConnection(body)

        expectIOException { PhotoSourceHttp.readText(connection, maxBytes = limit) }

        assertEquals(limit + 1, body.delivered)
        assertTrue("Read requests must obey the remaining budget", body.largestBulkRequest <= limit + 1)
        assertTrue("Oversize detection must stop work promptly", body.readCalls <= limit + 2)
        assertEquals(1, body.closeCalls)
    }

    @Test fun zeroBudgetAcceptsOnlyEmptyBodiesForKnownAndUnknownLengths() {
        for (announced in listOf<Long?>(0, null)) {
            val body = Body(byteArrayOf())
            val connection = prepareFakeConnection(body, announced)

            assertEquals("", PhotoSourceHttp.readText(connection, maxBytes = 0))

            assertEquals(0, body.delivered)
            assertTrue(body.eofReads > 0)
            assertEquals(1, body.closeCalls)
        }
    }

    @Test fun zeroBudgetRejectsUnknownNonemptyBodyAfterOneByteAndClosesIt() {
        val body = Body(byteArrayOf(), totalBytes = 1_000_000_000L, maximumDelivered = 1)
        val connection = prepareFakeConnection(body)

        expectIOException { PhotoSourceHttp.readText(connection, maxBytes = 0) }

        assertEquals(1, body.delivered)
        assertEquals(1, body.readCalls)
        assertEquals(1, body.closeCalls)
    }

    @Test fun validCompleteJsonStillFailsWhenShorterThanAnnouncedLength() {
        val body = Body("{}".toByteArray())
        val connection = prepareFakeConnection(body, announcedLength = 3)

        expectIOException { PhotoSourceHttp.readText(connection, maxBytes = 8) }

        assertEquals(2, body.delivered)
        assertTrue(body.eofReads > 0)
        assertEquals(1, body.closeCalls)
    }

    @Test fun actualBytesBeyondDeclaredLengthFailEvenWhenWithinBudget() {
        val body = Body("""{"a":1}""".toByteArray())
        val connection = prepareFakeConnection(body, announcedLength = 2)

        expectIOException { PhotoSourceHttp.readText(connection, maxBytes = 16) }

        assertTrue("Must inspect bytes beyond the declared prefix", body.delivered > 2)
        assertEquals(1, body.closeCalls)
    }

    @Test fun utf8UsesByteBudgetAndDecodesIdenticallyForKnownAndUnknownLengths() {
        val text = """{"title":"café 東京 🌅"}"""
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size > text.length)
        for (announced in listOf<Long?>(bytes.size.toLong(), null)) {
            // Splitting every code point across reads must not corrupt UTF-8.
            val body = Body(bytes, maxChunk = 1)
            val connection = prepareFakeConnection(body, announced)

            assertEquals(text, PhotoSourceHttp.readText(connection, maxBytes = bytes.size))

            assertEquals(bytes.size, body.delivered)
            assertEquals(1, body.closeCalls)
        }
    }

    @Test fun utf8CannotFitByCharacterCountWhenItsBytesExceedBudget() {
        val body = Body("éé".toByteArray(Charsets.UTF_8), maximumDelivered = 4)
        val connection = prepareFakeConnection(body)

        expectIOException { PhotoSourceHttp.readText(connection, maxBytes = 3) }

        assertEquals(4, body.delivered)
        assertEquals(1, body.closeCalls)
    }

    @Test fun zeroLengthBulkReadsFallBackToSingleByteReadsAndMakeProgress() {
        val text = """{"a":1}"""
        val body = Body(text.toByteArray(), zeroBulkReads = 2, maxChunk = 1)
        val connection = prepareFakeConnection(body)

        assertEquals(text, PhotoSourceHttp.readText(connection, maxBytes = text.length))

        assertTrue("Zero reads must use a progressing fallback", body.singleReadCalls >= 2)
        assertEquals(text.length, body.delivered)
        assertTrue(body.readCalls <= text.length + 5)
        assertEquals(1, body.closeCalls)
    }

    @Test fun bodyReadFaultPropagatesAndClosesTheAcquiredStream() {
        val fault = IOException("body read failed")
        val body = Body("abcdef".toByteArray(), maxChunk = 2, readFault = fault, faultAfterBytes = 2)
        val connection = prepareFakeConnection(body)

        assertSame(fault, expectIOException { PhotoSourceHttp.readText(connection, maxBytes = 8) })

        assertEquals(2, body.delivered)
        assertEquals(1, body.closeCalls)
    }

    @Test fun readFaultRemainsPrimaryWhenCleanupAlsoFails() {
        val readFault = IOException("body read failed")
        val closeFault = IOException("body close failed")
        val body = Body("{}".toByteArray(), readFault = readFault, closeFault = closeFault)
        val connection = prepareFakeConnection(body)

        val thrown = expectIOException { PhotoSourceHttp.readText(connection, maxBytes = 8) }

        assertSame(readFault, thrown)
        assertTrue("Cleanup failure must remain observable", thrown.suppressed.contains(closeFault))
        assertEquals(1, body.closeCalls)
    }

    @Test fun closeFaultAfterSuccessfulReadStillFailsTheOperation() {
        val closeFault = IOException("body close failed")
        val body = Body("{}".toByteArray(), closeFault = closeFault)
        val connection = prepareFakeConnection(body, announcedLength = 2)

        assertSame(closeFault, expectIOException { PhotoSourceHttp.readText(connection, maxBytes = 8) })

        assertEquals(2, body.delivered)
        assertEquals(1, body.closeCalls)
    }

    @Test fun alreadyInterruptedAbortsBeforeAcquiringBody() {
        val body = Body("{}".toByteArray())
        val connection = prepareFakeConnection(body)
        try {
            Thread.currentThread().interrupt()

            assertTrue(expectIOException {
                PhotoSourceHttp.readText(connection, maxBytes = 8)
            } is InterruptedIOException)

            assertEquals(0, connection.lengthReads)
            assertEquals(0, connection.bodyAcquisitions)
            assertEquals(0, body.delivered)
            assertEquals(0, body.closeCalls)
        } finally {
            // Never leak this deliberately set flag into another JUnit test.
            Thread.interrupted()
        }
    }

    @Test fun interruptionDuringBodyReadAbortsAndClosesStream() {
        val body = Body("abcdef".toByteArray(), maxChunk = 1, beforeFirstRead = {
            Thread.currentThread().interrupt()
        })
        val connection = prepareFakeConnection(body)
        try {
            assertTrue(expectIOException {
                PhotoSourceHttp.readText(connection, maxBytes = 8)
            } is InterruptedIOException)

            assertTrue("Interruption must stop before consuming the full body", body.delivered < 6)
            assertEquals(1, body.closeCalls)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun photoWorkCancellationIsDetectedAfterTransportClearsTheInterruptFlag() = runBlocking {
        val enteredRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val finishedRead = CountDownLatch(1)
        val observed = AtomicReference<Throwable?>()
        val interruptCleared = AtomicBoolean()
        val body = Body("abcdef".toByteArray(), maxChunk = 1, beforeFirstRead = {
            enteredRead.countDown()
            while (releaseRead.count > 0) {
                try {
                    releaseRead.await(100, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    // Model a transport which swallows interruption during blocking I/O.
                }
            }
            Thread.interrupted()
            interruptCleared.set(!Thread.currentThread().isInterrupted)
        })
        val connection = prepareFakeConnection(body)
        val job = launch(Dispatchers.Default) {
            PhotoSourceWork.load {
                try {
                    PhotoSourceHttp.readText(connection, maxBytes = 8)
                } catch (error: Throwable) {
                    observed.set(error)
                } finally {
                    finishedRead.countDown()
                }
                Unit
            }
        }
        try {
            assertTrue("Worker must enter the body read", enteredRead.await(2, TimeUnit.SECONDS))
            withTimeout(2_000) { job.cancelAndJoin() }
            releaseRead.countDown()
            assertTrue("Cancelled reader must finish", finishedRead.await(2, TimeUnit.SECONDS))

            assertTrue("Test must isolate request cancellation", interruptCleared.get())
            assertTrue("Helper must throw for the cancelled request",
                observed.get() is InterruptedIOException)
            assertTrue(body.delivered < 6)
            assertEquals(1, body.closeCalls)
        } finally {
            releaseRead.countDown()
            withTimeout(2_000) { job.cancelAndJoin() }
        }
    }

    @Test fun defaultMetadataBudgetIsSixteenMiBAndRejectsLargerHeaderWithoutReading() {
        val limit = 16 * 1024 * 1024
        assertEquals(limit, PhotoSourceHttp.MAX_METADATA_BYTES)
        val body = Body("{}".toByteArray())
        val connection = prepareFakeConnection(body, announcedLength = limit.toLong() + 1)

        expectIOException { PhotoSourceHttp.readText(connection) }

        assertEquals(0, connection.bodyAcquisitions)
        assertEquals(0, body.delivered)
    }

    @Test fun acceptedBodyPreservesCallerRequestPolicyAndDoesNotInspectStatus() {
        val text = """{"accepted":true}"""
        val body = Body(text.toByteArray())
        val connection = prepareFakeConnection(body, announcedLength = text.length.toLong())

        assertEquals(text, PhotoSourceHttp.readText(connection, maxBytes = 32))

        assertEquals(0, connection.policyWrites)
        assertEquals(0, connection.statusReads)
        assertEquals("POST", connection.requestMethod)
        assertEquals(4_321, connection.connectTimeout)
        assertEquals(6_789, connection.readTimeout)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertTrue(connection.doInput)
        assertTrue(connection.doOutput)
        assertEquals("application/json", connection.getRequestProperty("Accept"))
        assertEquals("prepared", connection.getRequestProperty("X-Photo-Test"))
        assertEquals(1, body.closeCalls)
    }

    private fun expectIOException(block: () -> Unit): IOException {
        try {
            block()
        } catch (error: IOException) {
            return error
        }
        fail("Expected IOException")
        throw AssertionError("unreachable")
    }

    private fun prepareFakeConnection(body: Body, announcedLength: Long? = null) =
        FakeConnection(body, announcedLength).apply {
            requestMethod = "POST"
            connectTimeout = 4_321
            readTimeout = 6_789
            instanceFollowRedirects = false
            useCaches = false
            doInput = true
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Photo-Test", "prepared")
            policyWrites = 0
        }

    private class FakeConnection(
        private val body: InputStream,
        private val announcedLength: Long?
    ) : HttpURLConnection(URL("https://example.invalid/photos")) {
        var bodyAcquisitions = 0
        var lengthReads = 0
        var statusReads = 0
        var policyWrites = 0

        override fun getInputStream(): InputStream {
            bodyAcquisitions++
            return body
        }
        override fun getContentLengthLong(): Long {
            lengthReads++
            return announcedLength ?: -1L
        }
        override fun getContentLength(): Int {
            lengthReads++
            return announcedLength?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() ?: -1
        }
        override fun getHeaderField(name: String?): String? {
            if (!name.equals("Content-Length", ignoreCase = true)) return null
            lengthReads++
            return announcedLength?.toString()
        }
        override fun getResponseCode(): Int {
            statusReads++
            return HTTP_OK
        }
        override fun setRequestMethod(method: String) {
            policyWrites++
            super.setRequestMethod(method)
        }
        override fun setConnectTimeout(timeout: Int) {
            policyWrites++
            super.setConnectTimeout(timeout)
        }
        override fun setReadTimeout(timeout: Int) {
            policyWrites++
            super.setReadTimeout(timeout)
        }
        override fun setInstanceFollowRedirects(follow: Boolean) {
            policyWrites++
            super.setInstanceFollowRedirects(follow)
        }
        override fun setUseCaches(use: Boolean) {
            policyWrites++
            super.setUseCaches(use)
        }
        override fun setDoInput(input: Boolean) {
            policyWrites++
            super.setDoInput(input)
        }
        override fun setDoOutput(output: Boolean) {
            policyWrites++
            super.setDoOutput(output)
        }
        override fun setRequestProperty(key: String, value: String) {
            policyWrites++
            super.setRequestProperty(key, value)
        }
        override fun addRequestProperty(key: String, value: String) {
            policyWrites++
            super.addRequestProperty(key, value)
        }
        override fun connect() {}
        override fun usingProxy() = false
        // Connection lifecycle belongs to the caller; these tests assert only body ownership.
        override fun disconnect() {}
    }

    private class Body(
        private val bytes: ByteArray,
        private val totalBytes: Long = bytes.size.toLong(),
        private val maximumDelivered: Int = Int.MAX_VALUE,
        private val maxChunk: Int = Int.MAX_VALUE,
        zeroBulkReads: Int = 0,
        private val readFault: IOException? = null,
        private val faultAfterBytes: Int = 0,
        private val closeFault: IOException? = null,
        private val beforeFirstRead: (() -> Unit)? = null
    ) : InputStream() {
        var delivered = 0
            private set
        var closeCalls = 0
            private set
        var readCalls = 0
            private set
        var singleReadCalls = 0
            private set
        var eofReads = 0
            private set
        var largestBulkRequest = 0
            private set
        private var zerosRemaining = zeroBulkReads

        private fun beforeRead() {
            readCalls++
            // Fail quickly if a regression spins on zero reads rather than making progress.
            check(readCalls <= 128) { "Unbounded read loop" }
            if (readCalls == 1) beforeFirstRead?.invoke()
            if (readFault != null && delivered >= faultAfterBytes) throw readFault
        }

        private fun byteAt(index: Int): Byte =
            if (index < bytes.size) bytes[index] else 'x'.code.toByte()

        override fun read(): Int {
            singleReadCalls++
            beforeRead()
            if (delivered.toLong() == totalBytes) {
                eofReads++
                return -1
            }
            check(delivered < maximumDelivered) { "Read beyond budget plus one" }
            return (byteAt(delivered++).toInt() and 0xff)
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            largestBulkRequest = maxOf(largestBulkRequest, length)
            beforeRead()
            if (zerosRemaining > 0) {
                zerosRemaining--
                return 0
            }
            if (delivered.toLong() == totalBytes) {
                eofReads++
                return -1
            }
            val count = minOf(length.toLong(), maxChunk.toLong(), totalBytes - delivered).toInt()
            check(delivered.toLong() + count <= maximumDelivered) { "Read beyond budget plus one" }
            repeat(count) { target[offset + it] = byteAt(delivered + it) }
            delivered += count
            return count
        }

        override fun close() {
            closeCalls++
            if (closeFault != null) throw closeFault
        }
    }
}
