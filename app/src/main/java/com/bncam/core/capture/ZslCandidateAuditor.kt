package com.bncam.core.capture

import android.util.Log
import com.bncam.core.buffer.ZslFramePair

object ZslCandidateAuditor {
    private const val TAG = "ZslCandidateAuditor"

    data class AuditEntry(
        val timestampNs: Long,
        val sharpnessScore: Double,
        val motionScore: Double,
        val aeState: Int,
        val awbState: Int,
        val afState: Int,
        val accepted: Boolean,
        val selectedBase: Boolean,
        val rejectReason: String
    )

    private val auditLogs = mutableListOf<AuditEntry>()

    @Synchronized
    fun recordDecision(
        frame: ZslFramePair,
        sharpnessScore: Double,
        motionScore: Double,
        aeState: Int,
        awbState: Int,
        afState: Int,
        accepted: Boolean,
        selectedBase: Boolean,
        rejectReason: String
    ) {
        val entry = AuditEntry(
            timestampNs = frame.timestamp,
            sharpnessScore = sharpnessScore,
            motionScore = motionScore,
            aeState = aeState,
            awbState = awbState,
            afState = afState,
            accepted = accepted,
            selectedBase = selectedBase,
            rejectReason = rejectReason
        )
        auditLogs.add(entry)
        try {
            Log.i(TAG, "ZSL Audit: ts=${frame.timestamp} sharp=${String.format("%.4f", sharpnessScore)} motion=${String.format("%.4f", motionScore)} afState=$afState accepted=$accepted base=$selectedBase reason=$rejectReason")
        } catch (_: Throwable) {}
    }

    @Synchronized
    fun getLogs(): List<AuditEntry> = auditLogs.toList()

    @Synchronized
    fun clear() {
        auditLogs.clear()
    }
}
