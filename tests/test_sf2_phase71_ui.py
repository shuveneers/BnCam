import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def source(name):
    return (ROOT / name).read_text(encoding="utf-8")

class DeveloperUiContract(unittest.TestCase):
    def test_settings_item_is_below_info(self):
        text = source("app/src/main/java/com/bncam/ui/screens/settings/SettingsScreen.kt")
        self.assertGreater(text.index('title = "Developer settings"'), text.index('title = "Info"'))
        self.assertIn("if (BuildConfig.DEBUG)", text)

    def test_navigation_and_return_to_camera(self):
        text = source("app/src/main/java/com/bncam/ui/navigation/AppNavigation.kt")
        self.assertIn('DEVELOPER_SETTINGS = "developer_settings"', text)
        self.assertIn('composable(Routes.DEVELOPER_SETTINGS)', text)
        self.assertIn('navController.popBackStack(Routes.CAMERA, inclusive = false)', text)

    def test_device_controls_use_existing_native_probe(self):
        text = source("app/src/main/java/com/bncam/ui/screens/settings/DeveloperSettingsScreen.kt")
        for marker in ["Sf2Mode(0,", "Sf2Mode(1,", "Sf2Mode(2,", "Sf2Mode(4,", "Sf2Mode(5,",
                       "setPhysicalLumaStageDebug", "setPhysicalLumaDebugEnabled",
                       "validateSf2IntegrationNative()", "physicalLumaAbDirectory",
                       "CameraEventBus.requestCapture", "qualification.txt",
                       "off_repeat.jpg", "MediaStore.Images.Media"]:
            self.assertIn(marker, text)
        self.assertIn("var enabled by mutableStateOf(false)", text)
        self.assertIn("var selectedStage by mutableIntStateOf(0)", text)
        self.assertIn("!BuildConfig.DEBUG", text)
        self.assertIn("Sf2Session.apply()", text)

    def test_phase7_kernels_are_not_modified(self):
        # CI assembles the complete APK, while this UI release intentionally
        # adds zero changes to the existing phase-7 GPU/CPU implementation.
        text = source("docs/single-frame-v2/PHASE7_UI_ADDENDUM.md")
        self.assertIn("No phase-7 native", text)

if __name__ == "__main__":
    unittest.main()
