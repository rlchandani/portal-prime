/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/**
 * One running metadata preparation, one latest pending request and one ready main delivery.
 * The worker transport must enqueue work; request/cancel/close and admission share Main.
 * No Android views or writable cache work are owned here: those begin only in current admission.
 */
internal class LegacySlideshowPreparation(
    private val submitWorker: (() -> Unit) -> Unit,
    private val postMain: (() -> Unit) -> Boolean,
) : AutoCloseable {
  private data class Request(
      val ticket: Long,
      val prepare: (() -> Boolean) -> String,
      val onReady: (String) -> Unit,
      val onFailure: (Exception) -> Unit,
  )
  private data class Delivery(val request: Request, val json: String?, val error: Exception?)
  private val revision = AtomicLong()
  private val lock = Any()
  @Volatile private var closed = false
  private var pending: Request? = null
  private var workerScheduled = false
  private var ready: Delivery? = null
  private var deliveryPosted = false

  fun request(
      prepare: (() -> Boolean) -> String,
      onReady: (String) -> Unit,
      onFailure: (Exception) -> Unit,
  ) {
    val schedule = synchronized(lock) {
      if (closed) return
      pending = Request(revision.incrementAndGet(), prepare, onReady, onFailure)
      ready = null
      if (workerScheduled) false else { workerScheduled = true; true }
    }
    if (schedule) {
      try { submitWorker(::pump) }
      catch (error: Exception) {
        val failed = synchronized(lock) {
          workerScheduled = false
          pending.also { pending = null }
        }
        failed?.let { publish(Delivery(it, null, error)) }
      }
    }
  }

  private fun current(request: Request): Boolean =
      !closed && revision.get() == request.ticket && !Thread.currentThread().isInterrupted

  private fun pump() {
    while (true) {
      val request = synchronized(lock) {
        pending?.also { pending = null } ?: run { workerScheduled = false; return }
      }
      if (!current(request)) continue
      val result = try {
        Delivery(request, request.prepare { current(request) }, null)
      } catch (_: CancellationException) {
        continue
      } catch (error: Exception) {
        Delivery(request, null, error)
      }
      if (current(request)) publish(result)
    }
  }

  private fun publish(delivery: Delivery) {
    val post = synchronized(lock) {
      if (!current(delivery.request)) return
      ready = delivery
      if (deliveryPosted) false else { deliveryPosted = true; true }
    }
    if (post) {
      val accepted = runCatching { postMain(::deliver) }.getOrDefault(false)
      if (!accepted) synchronized(lock) {
        ready = null
        deliveryPosted = false
      }
    }
  }

  private fun deliver() {
    val delivery = synchronized(lock) {
      deliveryPosted = false
      ready.also { ready = null }
    } ?: return
    if (!current(delivery.request)) return
    val error = delivery.error
    if (error != null) delivery.request.onFailure(error)
    else {
      try { delivery.request.onReady(checkNotNull(delivery.json)) }
      catch (failure: Exception) {
        // Attachment can fail after allocating a view. Its Main-owned failure callback
        // must retire that resource, but cannot tear down a reentrant newer admission.
        if (current(delivery.request)) delivery.request.onFailure(failure)
      }
    }
  }

  fun cancel() = synchronized(lock) {
    revision.incrementAndGet()
    pending = null
    ready = null
  }

  override fun close() = synchronized(lock) {
    closed = true
    cancel()
  }

  companion object {
    /** Same array order, optional fields and US rounding as the controller's legacy bridge. */
    fun photosJson(
        photoUrls: List<String>,
        dims: Map<String, Pair<Int, Int>>,
        focusFor: (String) -> FaceFocus.Focus?,
        weightFor: (String) -> Float,
        isCurrent: () -> Boolean = { true },
    ): String {
      fun checkpoint() {
        if (!isCurrent()) throw CancellationException("Legacy metadata superseded")
      }
      checkpoint()
      val photos = JSONArray()
      photoUrls.forEach { u ->
        checkpoint()
        val o = JSONObject().put("url", u)
        dims[u]?.let { (w, h) -> o.put("w", w).put("h", h) }
        val focus = focusFor(u)
        checkpoint()
        focus?.let { f ->
          o.put("fx", String.format(Locale.US, "%.3f", f.fx).toDouble())
          o.put("fy", String.format(Locale.US, "%.3f", f.fy).toDouble())
          f.bounds?.takeIf { it.isValid() }?.let { bounds ->
            // Preserve the full region: focal-point rounding would move some edges inward.
            o.put("fb", JSONArray().put(bounds.left.toDouble()).put(bounds.top.toDouble())
                .put(bounds.right.toDouble()).put(bounds.bottom.toDouble()))
          }
        }
        val weight = weightFor(u)
        checkpoint()
        weight.takeIf { it > 0f }?.let { pw ->
          o.put("pw", String.format(Locale.US, "%.2f", pw).toDouble())
        }
        photos.put(o)
      }
      checkpoint()
      return photos.toString().also { checkpoint() }
    }
  }
}
