/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One process-wide remote pipeline, separate from the shared pixel decoder.
 * Encoded sources are session-owned and disk-bounded; tiers reuse one transfer.
 */
internal class EngineRemoteImageCache(
    private val dir: File,
    private val download: (String, File, () -> Boolean) -> Boolean,
    private val prepare: (File, Int, () -> Boolean, CompletableFuture<Unit>) -> File?,
    private val owner: EngineDerivativeWork.Owner = networkWork.owner(),
    private val cleanup: (() -> Unit) -> Unit = { cleanupWork.execute(it) },
    private val isActive: () -> Boolean = { true },
    private val onCancel: () -> Unit = {},
    private val encodedLimit: Int = SlideshowDecodeBudget.MAX_ENCODED_BYTES,
    private val sourceBudgetBytes: Long = 64L * 1024 * 1024,
) : AutoCloseable {
  init { require(encodedLimit in 1..SlideshowDecodeBudget.MAX_ENCODED_BYTES); require(sourceBudgetBytes >= encodedLimit) }
  private val requests = EngineImageRequests(owner::submit)
  private val results = ConcurrentHashMap<String, File>()
  private val sourceAccess = HashMap<String, Long>() // Serialized remote pipeline only.
  private val cancellation = CompletableFuture<Unit>()
  private val lifecycle = Any()
  @Volatile private var closed = false
  private var running = false // lifecycle lock; only one pipeline operation for this owner.
  init { activeDirectories.add(dir.absolutePath) }

  fun ready(url: String, requestedEdge: Int): File? {
    if (closed) return null
    val key = resultKey(url, EngineScaledCache.derivativeEdge(requestedEdge))
    return results[key]?.takeIf { it.isFile && it.length() > 0 }
  }

  fun request(url: String, requestedEdge: Int): CompletableFuture<File?> {
    if (closed) return CompletableFuture.completedFuture(null)
    ready(url, requestedEdge)?.let { return CompletableFuture.completedFuture(it) }
    val edge = EngineScaledCache.derivativeEdge(requestedEdge)
    val key = resultKey(url, edge)
    return requests.request(key) { owned ->
      val admitted = synchronized(lifecycle) {
        if (closed) false else { running = true; true }
      }
      if (!admitted) return@request null
      fun current(): Boolean = !closed && owned() && isActive()
      try {
        if (!current() || (!dir.isDirectory && !dir.mkdirs())) return@request null
        if (dir.parentFile?.name == "ss_remote") dir.parentFile?.listFiles()?.filter {
          it.isDirectory && it != dir && it.absolutePath !in activeDirectories
        }?.forEach { it.deleteRecursively() }
        val source = File(dir, "${digest(url)}.source")
        if (!source.isFile || source.length() !in 1..encodedLimit.toLong()) {
          val temp = File(dir, "${source.name}.${UUID.randomUUID()}.part")
          try {
            if (!download(url, temp, ::current) || !current() ||
                temp.length() !in 1..encodedLimit.toLong() || !temp.renameTo(source)) return@request null
          } finally { temp.delete() }
        }
        // Decoder identity includes mtime; LRU touches must not invalidate an in-flight decode.
        sourceAccess[source.name] = System.nanoTime()
        if (!current()) return@request null
        val result = prepare(source, edge, ::current, cancellation)
        if (current() && result?.isFile == true && result.length() > 0) {
          results[key] = result
          result
        } else null
      } finally {
        if (!closed) trimSources()
        val remove = synchronized(lifecycle) { running = false; closed }
        if (remove) removeOwnedDirectory()
      }
    }
  }

  /** The WebView request thread may wait; Main and JS only call ready(). */
  fun prepareForLoad(url: String, edge: Int): File? = try {
    request(url, edge).get(25, TimeUnit.SECONDS)
  } catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    null
  } catch (_: Exception) { null }

  private fun trimSources() {
    val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".source") }
        ?.sortedBy { sourceAccess[it.name] ?: 0L }.orEmpty()
    var bytes = files.sumOf { it.length() }
    for (file in files) {
      if (bytes <= sourceBudgetBytes) break
      val size = file.length()
      if (file.delete()) { bytes -= size; sourceAccess.remove(file.name) }
    }
  }

  override fun close() {
    val remove = synchronized(lifecycle) { closed = true; !running }
    cancellation.complete(Unit)
    requests.close()
    owner.close()
    onCancel()
    results.clear()
    if (remove) cleanup(::removeOwnedDirectory)
  }

  private fun removeOwnedDirectory() {
    dir.deleteRecursively()
    activeDirectories.remove(dir.absolutePath)
  }

  private fun resultKey(url: String, edge: Int): String = "${digest(url)}:$edge"
  private fun digest(url: String): String = MessageDigest.getInstance("SHA-256")
      .digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

  private companion object {
    val networkWork = EngineDerivativeWork(queueCapacity = 16)
    val activeDirectories = ConcurrentHashMap.newKeySet<String>()
    val cleanupWork = Executors.newSingleThreadExecutor { task ->
      Thread(task, "ss-remote-cleanup").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
  }
}
