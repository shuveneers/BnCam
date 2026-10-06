package com.bncam.core.quality

import kotlin.test.*

class DefaultRawCamera2PairPolicyTest {
    private val gains = floatArrayOf(2f, 1.2f, 1f, 1.5f)
    private val matrix = floatArrayOf(1.96875f,-1.03125f,.0625f,-.26563f,1.6875f,-.42188f,-.17969f,-.72656f,1.91406f)
    @Test fun `exact matrix survives a weaker static noise reference`() {
        val static = floatArrayOf(1.45f,-.4f,-.05f,-.2f,1.3f,-.1f,-.05f,-.25f,1.3f)
        assertFalse(RawColorTransformEngine.evaluateExactFrameAgainstStaticCalibration(matrix,gains,listOf(static)).trusted)
        val selected=DefaultRawCamera2PairPolicy.resolve(true,false,true,gains,gains,matrix)
        assertContentEquals(matrix,selected)
        assertNotSame(matrix,selected)
    }
    @Test fun `other routes and explicit choices retain their existing owner`() {
        assertNull(DefaultRawCamera2PairPolicy.resolve(false,false,true,gains,gains,matrix))
        assertNull(DefaultRawCamera2PairPolicy.resolve(true,true,true,gains,gains,matrix))
        assertNull(DefaultRawCamera2PairPolicy.resolve(true,false,false,gains,gains,matrix))
    }
    @Test fun `mismatched or missing pair cannot claim exact provenance`() {
        assertNull(DefaultRawCamera2PairPolicy.resolve(true,false,true,gains,floatArrayOf(2f,1f,1f,1.5f),matrix))
        assertNull(DefaultRawCamera2PairPolicy.resolve(true,false,true,gains,null,matrix))
        assertNull(DefaultRawCamera2PairPolicy.resolve(true,false,true,gains,gains,null))
        assertNull(DefaultRawCamera2PairPolicy.resolve(true,false,true,gains,gains,FloatArray(9)))
    }
}
