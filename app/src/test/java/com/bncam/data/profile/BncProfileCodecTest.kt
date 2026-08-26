package com.bncam.data.profile

import com.bncam.data.settings.ProfileSettingSpec
import com.bncam.data.settings.ProfileSettingValueType
import com.bncam.data.settings.ProfileSettingsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BncProfileCodecTest {
    private val specs = listOf(
        ProfileSettingSpec("float_setting", ProfileSettingValueType.FLOAT, "0.25"),
        ProfileSettingSpec("int_setting", ProfileSettingValueType.INT, "7"),
        ProfileSettingSpec("bool_setting", ProfileSettingValueType.BOOLEAN, "false"),
        ProfileSettingSpec("string_setting", ProfileSettingValueType.STRING, "Default")
    )

    @Test
    fun `current bnc round trip preserves identity metadata and all typed settings`() {
        val original = BncProfileDocument(
            profileUuid = UUID.randomUUID().toString(),
            profileName = "Cross Device Natural",
            captureMode = "MULTI_FRAME_ZSL",
            preferredFrameSource = "RAW10",
            settings = ProfileSettingsSnapshot(
                floatValues = mapOf("float_setting" to -0.4f),
                intValues = mapOf("int_setting" to 12),
                booleanValues = mapOf("bool_setting" to true),
                stringValues = mapOf("string_setting" to "Custom")
            ),
            metadata = BncProfileMetadata(
                bncamVersion = "1.0",
                exportedAtEpochMs = 123456789L,
                sourceStableLensKey = "lens_v2_main",
                defaultIspVersion = "isp-v1",
                sourceFrameSources = setOf("YUV", "RAW10")
            )
        )

        val encoded = BncProfileCodec.encode(original)
        val decoded = BncProfileCodec.decode(encoded, specs)

        assertEquals(BncProfileCodec.CURRENT_SCHEMA_VERSION, decoded.sourceSchemaVersion)
        assertFalse(decoded.migrated)
        assertEquals(original, decoded.document)
        assertTrue(decoded.ignoredUnknownSettingKeys.isEmpty())
        assertTrue(decoded.fallbackSettingKeys.isEmpty())
        assertFalse(encoded.contains("blackLevel", ignoreCase = true))
        assertFalse(encoded.contains("sensorCalibration", ignoreCase = true))
    }

    @Test
    fun `schema1 migrates and fills settings introduced after old schema`() {
        val uuid = UUID.randomUUID().toString()
        val schema1 = """
            {
              "format": "BNCAM_PROFILE",
              "schemaVersion": 1,
              "profileUuid": "$uuid",
              "profileName": "Old Profile",
              "captureMode": "SINGLE_FRAME_ZSL",
              "frameSource": "RAW_SENSOR",
              "bncamVersion": "0.9",
              "settings": {
                "floatValues": {"float_setting": 0.75},
                "intValues": {},
                "booleanValues": {},
                "stringValues": {}
              }
            }
        """.trimIndent()

        val decoded = BncProfileCodec.decode(schema1, specs)

        assertTrue(decoded.migrated)
        assertEquals(1, decoded.sourceSchemaVersion)
        assertEquals("RAW_SENSOR", decoded.document.preferredFrameSource)
        assertEquals(0.75f, decoded.document.settings.floatValues.getValue("float_setting"))
        assertEquals(7, decoded.document.settings.intValues.getValue("int_setting"))
        assertEquals(false, decoded.document.settings.booleanValues.getValue("bool_setting"))
        assertEquals("Default", decoded.document.settings.stringValues.getValue("string_setting"))
        assertEquals(setOf("int_setting", "bool_setting", "string_setting"), decoded.fallbackSettingKeys)
    }

    @Test
    fun `unknown fields and unknown setting keys are ignored without corrupting known values`() {
        val uuid = UUID.randomUUID().toString()
        val raw = """
            {
              "format":"BNCAM_PROFILE",
              "schemaVersion":2,
              "futureEnvelope":{"anything":true},
              "profile":{
                "uuid":"$uuid",
                "name":"Forward Compatible",
                "captureMode":"SINGLE_FRAME_ZSL",
                "frameSource":"YUV",
                "futureProfileField":42,
                "settings":{
                  "floatValues":{"float_setting":0.5,"future_float":99.0},
                  "intValues":{"int_setting":8},
                  "booleanValues":{"bool_setting":true},
                  "stringValues":{"string_setting":"Known","future_string":"ignored"}
                }
              },
              "metadata":{"bncamVersion":"1.0","hardwareCalibrationIncluded":false,"futureMetadata":"safe"}
            }
        """.trimIndent()

        val decoded = BncProfileCodec.decode(raw, specs)
        assertEquals(0.5f, decoded.document.settings.floatValues.getValue("float_setting"))
        assertEquals(setOf("future_float", "future_string"), decoded.ignoredUnknownSettingKeys)
    }

    @Test
    fun `malformed corrupt file is rejected before import`() {
        val broken = """{"format":"BNCAM_PROFILE","schemaVersion":2,"profile":{"uuid":"not-a-uuid""""
        val result = runCatching { BncProfileCodec.decode(broken, specs) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is BncProfileFormatException)
    }

    @Test
    fun `file declaring physical calibration is rejected`() {
        val uuid = UUID.randomUUID().toString()
        val raw = """
            {
              "format":"BNCAM_PROFILE",
              "schemaVersion":2,
              "profile":{
                "uuid":"$uuid","name":"Unsafe","captureMode":"SINGLE_FRAME_ZSL","frameSource":"YUV",
                "settings":{"floatValues":{},"intValues":{},"booleanValues":{},"stringValues":{}}
              },
              "metadata":{"hardwareCalibrationIncluded":true}
            }
        """.trimIndent()
        val result = runCatching { BncProfileCodec.decode(raw, specs) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("hardware calibration", ignoreCase = true) == true)
    }

    @Test
    fun `legacy complete json config migrates into portable bnc document`() {
        val legacy = """
            {
              "format":"BNCAM_PROFILE_SETTINGS_CONFIG",
              "version":3,
              "schemaNumber":3,
              "defaultIspVersion":"legacy-isp",
              "profiles":[{
                "profileId":"lens0_profile_1",
                "profileName":"Legacy Complete",
                "captureMode":"MULTI_FRAME_ZSL",
                "frameSource":"RAW10",
                "settings":{
                  "floatValues":{"float_setting":0.1},
                  "intValues":{"int_setting":9},
                  "booleanValues":{"bool_setting":true},
                  "stringValues":{"string_setting":"Legacy"}
                }
              }]
            }
        """.trimIndent()

        val decoded = BncProfileCodec.decode(legacy, specs)
        assertTrue(decoded.migrated)
        assertEquals("Legacy Complete", decoded.document.profileName)
        assertEquals("RAW10", decoded.document.preferredFrameSource)
        assertEquals(9, decoded.document.settings.intValues.getValue("int_setting"))
        UUID.fromString(decoded.document.profileUuid)
    }

    @Test
    fun `missing target capability falls back predictably while supported sources remain unchanged`() {
        val target = BncTargetCapabilities(setOf("YUV", "RAW10"))
        val unsupported = BncProfileCompatibility.resolveFrameSource("RAW_SENSOR", target)
        val supported = BncProfileCompatibility.resolveFrameSource("RAW10", target)

        assertTrue(unsupported.fallbackOccurred)
        assertEquals("RAW10", unsupported.effective)
        assertFalse(supported.fallbackOccurred)
        assertEquals("RAW10", supported.effective)
    }
    @Test
    fun `future settings schema is rejected instead of being guessed`() {
        val uuid = UUID.randomUUID().toString()
        val raw = """
            {
              "format":"BNCAM_PROFILE",
              "schemaVersion":2,
              "settingsSchemaVersion":99,
              "profile":{
                "uuid":"$uuid","name":"Future Settings","captureMode":"SINGLE_FRAME_ZSL","frameSource":"YUV",
                "settings":{"floatValues":{},"intValues":{},"booleanValues":{},"stringValues":{}}
              },
              "metadata":{"hardwareCalibrationIncluded":false}
            }
        """.trimIndent()

        val result = runCatching { BncProfileCodec.decode(raw, specs) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("settings schema", ignoreCase = true) == true)
    }

    @Test
    fun `profile filename is safe while profile name itself remains untouched`() {
        assertEquals("Night_Portrait_Test.bnc", BncProfileCodec.suggestedFileName("Night/Portrait:Test"))
    }

    @Test
    fun `legacy file declaring physical calibration is rejected too`() {
        val raw = """
            {
              "format":"BNCAM_PROFILE_SETTINGS_CONFIG",
              "version":3,
              "schemaNumber":3,
              "hardwareCalibrationIncluded":true,
              "profiles":[{
                "profileName":"Unsafe Legacy",
                "captureMode":"SINGLE_FRAME_ZSL",
                "frameSource":"YUV",
                "settings":{"floatValues":{},"intValues":{},"booleanValues":{},"stringValues":{}}
              }]
            }
        """.trimIndent()
        val result = runCatching { BncProfileCodec.decode(raw, specs) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("hardware calibration", ignoreCase = true) == true)
    }

}
