package com.bncam

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bncam.core.engine.ImageUtils
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoiseModelNativeConnectedTest {
    @Test
    fun productionNativeNoiseModelSamplesVarianceAndChangesConsumer() {
        val proof = ImageUtils.validateNoiseModelImplementation()

        assertTrue(proof, proof.contains("lowSoReceived=["))
        assertTrue(proof, proof.contains("highSoReceived=["))
        assertTrue(proof, proof.contains("offSamples=0"))
        assertTrue(proof, proof.contains("offMultiplier=1"))
        assertTrue(proof, proof.contains("autoSamples=") && !proof.contains("autoSamples=0"))
        assertTrue(proof, proof.contains("lowSamples=") && !proof.contains("lowSamples=0"))
        assertTrue(proof, proof.contains("highSamples=") && !proof.contains("highSamples=0"))
        assertTrue(proof, proof.contains("dynamicMonotonic=true"))
        assertTrue(proof, proof.contains("dynamicBounded=true"))
        assertTrue(proof, proof.contains("iso6400Exact=true"))
        assertTrue(proof, proof.contains("isoAdaptiveMonotonic=true"))
        assertTrue(proof, proof.contains("provenanceValid=true"))
        assertTrue(proof, proof.contains("noRegretRejectedDrift=true"))
        assertTrue(proof, proof.contains("allPassed=true"))
    }
}
