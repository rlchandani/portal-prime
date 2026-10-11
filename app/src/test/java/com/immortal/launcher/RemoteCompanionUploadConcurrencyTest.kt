/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RemoteCompanionUploadConcurrencyTest {
    @Test fun stalledTransferDoesNotBlockUnrelatedUpload() = stalledStage(false)
    @Test fun stalledValidationDoesNotBlockUnrelatedUpload() = stalledStage(true)

    @Test fun publicationWaitsForReadersWithoutExposingPartialDestination() {
        val folder = Files.createTempDirectory("photo-publication").toFile()
        val pool = Executors.newSingleThreadExecutor()
        val publishLock = Any()
        val verified = CountDownLatch(1)
        try {
            val upload = synchronized(publishLock) {
                val future = pool.submit<String> {
                    RemoteCompanionPhotoPolicy.store(folder, "photo.jpg", 4,
                        { it.write(byteArrayOf(1, 2, 3, 4)); 4 },
                        { verified.countDown(); true }, publishLock)
                }
                assertTrue(verified.await(2, TimeUnit.SECONDS))
                assertFalse(future.isDone)
                assertFalse(File(folder, "photo.jpg").exists())
                future
            }
            assertEquals("stored", upload.get(2, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(folder, "photo.jpg").readBytes())
        } finally { pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); folder.deleteRecursively() }
    }

    @Test fun simultaneousSameNameUploadsStageTogetherButOnlyOnePublishes() {
        val folder = Files.createTempDirectory("photo-collision").toFile()
        val pool = Executors.newFixedThreadPool(2)
        val verified = CountDownLatch(2)
        val release = CountDownLatch(1)
        try {
            val uploads = (1..2).map { value -> pool.submit<String> {
                RemoteCompanionPhotoPolicy.store(folder, "photo.jpg", 4,
                    { it.write(ByteArray(4) { value.toByte() }); 4 },
                    { verified.countDown(); check(release.await(5, TimeUnit.SECONDS)); true })
            } }
            assertTrue(verified.await(2, TimeUnit.SECONDS))
            assertFalse(File(folder, "photo.jpg").exists())
            release.countDown()
            val results = uploads.map { it.get(2, TimeUnit.SECONDS) }
            assertEquals(listOf("collision", "stored"), results.sorted())
            val winner = results.indexOf("stored") + 1
            assertArrayEquals(ByteArray(4) { winner.toByte() }, File(folder, "photo.jpg").readBytes())
            assertEquals(listOf("photo.jpg"), folder.list()!!.toList())
        } finally { release.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); folder.deleteRecursively() }
    }

    @Test fun canonicalFolderIsRecheckedAtPublication() {
        val root = Files.createTempDirectory("photo-root").toFile()
        val outside = Files.createTempDirectory("photo-outside").toFile()
        val folder = File(root.canonicalFile, "Pictures").apply { mkdir() }
        val moved = File(root, "old")
        var unrelated: File? = null
        assertEquals(folder, RemoteCompanionPhotoPolicy.resolve(root, "/sdcard/Pictures", true))
        try {
            val result = RemoteCompanionPhotoPolicy.store(folder, "photo.jpg", 4,
                { it.write(byteArrayOf(1, 2, 3, 4)); 4 },
                { staged ->
                    assertTrue(folder.renameTo(moved))
                    Files.createSymbolicLink(folder.toPath(), outside.toPath())
                    unrelated = File(outside, staged.name).apply { writeText("unrelated") }
                    true
                },
                canPublish = { RemoteCompanionPhotoPolicy.resolve(root, "/sdcard/Pictures", true) == folder })
            assertEquals("folder_unavailable", result)
            assertFalse(File(outside, "photo.jpg").exists())
            assertEquals("unrelated", unrelated!!.readText())
        } finally { folder.delete(); root.deleteRecursively(); outside.deleteRecursively() }
    }

    @Test fun existingDestinationDoesNotReceiveOrValidateAnotherBody() {
        val folder = Files.createTempDirectory("photo-existing").toFile()
        try {
            File(folder, "photo.jpg").writeText("original")
            assertEquals("collision", RemoteCompanionPhotoPolicy.store(folder, "photo.jpg", 4,
                { error("body_must_not_be_read") }, { error("image_must_not_be_validated") }))
            assertEquals("original", File(folder, "photo.jpg").readText())
        } finally { folder.deleteRecursively() }
    }

    @Test fun failedBodyCleansTemporaryFileWithoutPublishing() {
        val folder = Files.createTempDirectory("photo-failed").toFile()
        try {
            assertEquals("upload_failed", RemoteCompanionPhotoPolicy.store(folder, "photo.jpg", 4,
                { it.write(1); throw java.io.IOException("disconnected") }, { true }))
            assertEquals(emptyList<String>(), folder.list()!!.toList())
        } finally { folder.deleteRecursively() }
    }

    @Test fun rejectedPublicationCleansValidatedTemporaryFile() {
        val folder = Files.createTempDirectory("photo-rejected").toFile()
        try {
            assertEquals("folder_unavailable", RemoteCompanionPhotoPolicy.store(folder, "photo.jpg", 4,
                { it.write(byteArrayOf(1, 2, 3, 4)); 4 }, { true }, canPublish = { false }))
            assertEquals(emptyList<String>(), folder.list()!!.toList())
        } finally { folder.deleteRecursively() }
    }

    private fun stalledStage(validation: Boolean) {
        val folder = Files.createTempDirectory("photo-concurrency").toFile()
        val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun pause() { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        try {
            val slow = pool.submit<String> {
                RemoteCompanionPhotoPolicy.store(folder, "slow.jpg", 4,
                    { if (!validation) pause(); it.write(byteArrayOf(1, 2, 3, 4)); 4 },
                    { if (validation) pause(); true })
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val other = pool.submit<String> {
                RemoteCompanionPhotoPolicy.store(folder, "other.jpg", 4,
                    { it.write(byteArrayOf(5, 6, 7, 8)); 4 }, { true })
            }
            assertEquals("stored", other.get(500, TimeUnit.MILLISECONDS))
            assertEquals(1L, release.count)
            assertFalse(File(folder, "slow.jpg").exists())
            release.countDown()
            assertEquals("stored", slow.get(2, TimeUnit.SECONDS))
        } finally {
            release.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            folder.deleteRecursively()
        }
    }
}
