package com.bncam.core.engine

/**
 * Pure ordering/budget policy for SurfaceTexture preview-geometry recovery.
 *
 * The normal CameraScreen policy remains the first-choice geometry owner. This policy is used only
 * after Camera2/HAL has explicitly rejected that preview output. Recovery stays full-FOV and walks
 * toward lower bandwidth before trying a larger geometry.
 */
object PreviewGeometryRecoveryPolicy {
    const val MAX_ATTEMPTS_PER_GENERATION = 3

    data class Extent(val width: Int, val height: Int) {
        val area: Long get() = width.toLong() * height.toLong()
        val valid: Boolean get() = width > 0 && height > 0
        val key: String get() = "${width}x${height}"
    }

    fun rankedAlternatives(
        fullFovCandidates: Collection<Extent>,
        failed: Extent,
        alreadyAttempted: Set<String>,
        maxAttempts: Int = MAX_ATTEMPTS_PER_GENERATION
    ): List<Extent> {
        if (!failed.valid || maxAttempts <= 0) return emptyList()

        val unique = linkedMapOf<String, Extent>()
        fullFovCandidates.asSequence()
            .filter { it.valid }
            .forEach { unique.putIfAbsent(it.key, it) }

        val excluded = alreadyAttempted + failed.key
        val available = unique.values.filter { it.key !in excluded }
        if (available.isEmpty()) return emptyList()

        // Lower-bandwidth candidates are the safest first recovery after an advertised preview
        // output was rejected. Pick the closest smaller geometry first to avoid an unnecessary
        // quality cliff. Only then try larger full-FOV outputs in ascending order.
        val smaller = available
            .filter { it.area < failed.area }
            .sortedWith(compareByDescending<Extent> { it.area }.thenByDescending { it.width }.thenByDescending { it.height })
        val sameArea = available
            .filter { it.area == failed.area }
            .sortedWith(compareBy<Extent> { it.width }.thenBy { it.height })
        val larger = available
            .filter { it.area > failed.area }
            .sortedWith(compareBy<Extent> { it.area }.thenBy { it.width }.thenBy { it.height })

        return (smaller + sameArea + larger).take(maxAttempts)
    }
}
