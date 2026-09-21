package com.bncam.ui.screens.capture

/** Preview-only Camera2 metadata. Capture keeps its independent ring/metadata ownership. */
internal class RawPreviewFrameMetadataCache(
    private val capacity: Int = 24,
    private val maxAgeNs: Long = 1_000_000_000L
) {
    data class Key(val generation: Int, val sensorId: String, val source: ViewfinderEffectiveSource,
                   val timestampNs: Long)

    data class Entry(
        val black: FloatArray?, val white: Int?,
        val lensMap: FloatArray? = null, val lensColumns: Int = 0, val lensRows: Int = 0
    ) {
        fun snapshot() = copy(black = black?.copyOf(), lensMap = lensMap?.copyOf())
    }

    data class Match(val exact: Entry?, val trustedBlack: FloatArray?)

    private data class Stored(val entry: Entry, val arrivedNs: Long)
    private val entries = LinkedHashMap<Key, Stored>()

    @Synchronized fun record(key: Key, entry: Entry, nowNs: Long) {
        if (key.timestampNs <= 0L || key.sensorId.isBlank()) return
        expire(nowNs)
        entries[key] = Stored(entry.snapshot(), nowNs)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    @Synchronized fun hasExact(key: Key, nowNs: Long): Boolean {
        expire(nowNs)
        return entries.containsKey(key)
    }

    @Synchronized fun match(key: Key, nowNs: Long): Match {
        expire(nowNs)
        val exact = entries[key]?.entry?.snapshot()
        val trusted = entries.entries.asSequence()
            .filter { (candidate, stored) ->
                candidate.generation == key.generation && candidate.sensorId == key.sensorId &&
                    candidate.source == key.source && candidate.timestampNs <= key.timestampNs &&
                    key.timestampNs - candidate.timestampNs <= maxAgeNs && stored.entry.black != null
            }
            .maxByOrNull { it.key.timestampNs }?.value?.entry?.black?.copyOf()
        return Match(exact, trusted)
    }

    @Synchronized fun discard(key: Key) { entries.remove(key) }
    @Synchronized fun clear() { entries.clear() }
    @Synchronized fun size(): Int = entries.size

    private fun expire(nowNs: Long) {
        entries.entries.removeAll { nowNs - it.value.arrivedNs > maxAgeNs }
    }
}
