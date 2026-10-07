package com.bncam.core.capture

import java.util.UUID

/** Owner-scoped identity for an admitted shutter, independent of logging and frame origin. */
object CaptureIds {
    private val sessionId = UUID.randomUUID().toString()

    fun forAttempt(attemptId: Long, ownerId: String = sessionId): String {
        require(attemptId > 0L)
        require(ownerId.isNotBlank())
        return "$ownerId-$attemptId"
    }

    fun newId(): String = UUID.randomUUID().toString()
}
