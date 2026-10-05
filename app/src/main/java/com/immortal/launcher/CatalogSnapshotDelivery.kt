/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** A failed network refresh must not downgrade an already loaded hosted catalog. */
internal fun <T> retainedCatalogSnapshot(
    remote: List<T>, cached: List<T>?, bundled: List<T>,
): List<T> = remote.ifEmpty { cached?.takeIf { it.isNotEmpty() } ?: bundled }

/** One catalog refresh with independent, removable interests and an immutable cached snapshot. */
internal class CatalogSnapshotDelivery<T> {
    data class Subscription<T>(
        val startLoad: Boolean, val snapshot: List<T>?, val cancel: () -> Unit)
    private var snapshot: List<T>? = null
    private var loading = false
    private val listeners = LinkedHashMap<Any, (List<T>) -> Unit>()

    @Synchronized fun subscribe(listener: (List<T>) -> Unit): Subscription<T> {
        val identity = Any()
        listeners[identity] = listener
        val start = !loading
        loading = true
        return Subscription(start, snapshot) { synchronized(this) { listeners.remove(identity) } }
    }

    @Synchronized fun publish(value: List<T>): List<(List<T>) -> Unit> {
        snapshot = value.toList()
        return listeners.values.toList()
    }

    @Synchronized fun complete() {
        loading = false
        listeners.clear()
    }

    /** Terminal publication and load ownership change atomically with subscription. */
    @Synchronized fun complete(value: List<T>): List<(List<T>) -> Unit> {
        snapshot = value.toList()
        val delivery = listeners.values.toList()
        loading = false
        listeners.clear()
        return delivery
    }
}

/** Share only identical in-flight requests; completed inputs are not retained. */
internal class CatalogRequestDelivery<K, T> {
    data class Subscription(val startLoad: Boolean, val cancel: () -> Unit)
    private val requests = LinkedHashMap<K, LinkedHashMap<Any, (List<T>) -> Unit>>()

    @Synchronized fun subscribe(key: K, listener: (List<T>) -> Unit): Subscription {
        val identity = Any()
        val start = key !in requests
        val listeners = requests.getOrPut(key) { LinkedHashMap() }
        listeners[identity] = listener
        return Subscription(start) {
            synchronized(this) { requests[key]?.remove(identity) }
        }
    }

    @Synchronized fun complete(key: K): List<(List<T>) -> Unit> =
        requests.remove(key)?.values?.toList().orEmpty()
}
