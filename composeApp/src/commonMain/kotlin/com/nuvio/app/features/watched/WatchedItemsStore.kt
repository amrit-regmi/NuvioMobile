package com.nuvio.app.features.watched

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Serializes all access to the mutable watched-items map so concurrent readers
 * (UI queries, publish/persist snapshots) never observe a map that a background
 * sync coroutine is mutating.
 *
 * Hand-ported (adapted to this fork's single-map repository) from upstream
 * NuvioMedia/NuvioMobile 15fcb8d2 "serialize watched state snapshots".
 */
internal class WatchedItemsStore {
    private val lock = SynchronizedObject()
    private val items = mutableMapOf<String, WatchedItem>()

    fun <T> read(block: (items: Map<String, WatchedItem>) -> T): T = synchronized(lock) {
        block(items)
    }

    fun <T> update(block: (items: MutableMap<String, WatchedItem>) -> T): T = synchronized(lock) {
        block(items)
    }
}
