package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPublicationIntegrityGateTest {
    private val safe = RawPublicationIntegrityGate.Input(
        provenanceSafe = true,
        coreMetadataSafe = true,
        calibrationBindingSafe = true,
        authorityProfileMatch = true,
        cfaMatched = true,
        whiteBalanceValid = true,
        colorMatrixValid = true,
        colorMatrixIdentityFallbackUsed = false,
        blackWhiteRangeValid = true
    )

    @Test fun exactAuthorityPipelinePublishes() {
        assertTrue(RawPublicationIntegrityGate.evaluate(safe).safeForPublication)
    }

    @Test fun logicalOrForeignMetadataCannotPublish() {
        assertEquals(
            "RAW_METADATA_PROVENANCE_UNSAFE",
            RawPublicationIntegrityGate.evaluate(safe.copy(provenanceSafe = false)).reason
        )
    }

    @Test fun mismatchedCalibrationCannotPublish() {
        assertEquals(
            "CALIBRATION_AUTHORITY_PROFILE_MISMATCH",
            RawPublicationIntegrityGate.evaluate(safe.copy(authorityProfileMatch = false)).reason
        )
    }
}
