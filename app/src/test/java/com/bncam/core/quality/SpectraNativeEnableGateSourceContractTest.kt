package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraNativeEnableGateSourceContractTest {
    @Test
    fun `physical so ingress precedes spectra enable gate`() {
        val nativeBridge = File("src/main/cpp/native-lib.cpp").readText()

        val canonicalSoIngress = nativeBridge.indexOf(
            "meta.calibration.effectiveS[ch] = physicalNoise.s[static_cast<std::size_t>(ch)]"
        )
        val physicalReadyGate = nativeBridge.indexOf(
            "const bool physicalNoiseReady = meta.calibration.physicalNoiseModelAvailable()"
        )
        val spectraGate = nativeBridge.indexOf(
            "resolveSpectraProcessingMode(spectraRequested, meta.calibration)"
        )

        assertTrue("canonical physical S/O ingress must exist", canonicalSoIngress >= 0)
        assertTrue("physical-noise readiness gate must exist", physicalReadyGate >= 0)
        assertTrue("SPECTRA native enable gate must exist", spectraGate >= 0)
        assertTrue(
            "canonical physical S/O must be populated before physicalNoiseReady is evaluated",
            canonicalSoIngress < physicalReadyGate
        )
        assertTrue(
            "SPECTRA mode must resolve only after physicalNoiseReady has been evaluated",
            physicalReadyGate < spectraGate
        )
    }
}
