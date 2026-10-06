package com.bncam.ui.screens.capture

/** Bounded preview-only pairing window. The caller serializes access and retires every discard. */
internal class RawPreviewPendingFrames<T>(private val timestamp: (T) -> Long) {
    private val frames = mutableListOf<T>()
    val size: Int get() = frames.size

    fun offer(frame: T, capacity: Int): List<T> {
        frames.add(frame)
        frames.sortBy(timestamp)
        val discarded = mutableListOf<T>()
        while (frames.size > capacity) discarded.add(frames.removeAt(0))
        return discarded
    }

    data class Poll<T>(val ready: T?, val discarded: List<T>)

    fun poll(ready: (T) -> Boolean, expired: (T) -> Boolean): Poll<T> {
        val discarded = frames.filter(expired).toMutableList()
        frames.removeAll(discarded.toSet())
        val index = frames.indexOfLast(ready)
        if (index < 0) return Poll(null, discarded)
        // A newer unpaired image must not evict the older image whose metadata just arrived.
        val selected = frames[index]
        discarded.addAll(frames.take(index))
        frames.subList(0, index + 1).clear()
        return Poll(selected, discarded)
    }

    fun clear(): List<T> = frames.toList().also { frames.clear() }
}
