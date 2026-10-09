# Global Exposure Control — 9 oktober 2026

Exposure Control in App Settings wisselt direct in dezelfde regel tussen Standard Auto en BnC Auto. AE Control is een optioneel toe te wijzen shortcut; de standaardindeling en alle negen plaatsen blijven behouden. Beide bedieningen schrijven dezelfde DataStore-voorkeur.

Standard Auto is de migratiestandaard. Oude profielkeuzes blijven als compatibiliteitsdata bestaan, maar kunnen geen exposure-authority meer krijgen en worden niet meer meegenomen in portable profiles. De profielpagina behoudt Shot Bias en Capture EV.

Handmatige ISO en/of shutter heeft voorrang. Een controllerwissel wist die waarden niet; beide terug op AUTO herstelt de opgeslagen automatische keuze. De bestaande geserialiseerde requestroute verwerkt wijzigingen zonder sessieherbouw. Een generationgebonden epoch-grens sluit oudere warmframes uit van nieuwe selectie en BnC Auto-observaties; bestaande leases en metadata blijven intact. Niet-ondersteunde routes melden de Standard Auto-fallback zonder de voorkeur te wijzigen.

## Validatie

- Debugbuild en Android-test-APK gebouwd; 38 gerichte unittests en één DataStore-device-test slagen.
- Honor BKQ-N49: App Settings en shortcut synchroniseren; daadwerkelijk AE_OFF/BN_AUTO versus AE_ON/Camera2, beide in generation 2. Tegel opnieuw toegewezen en negen plaatsen gecontroleerd.
- ISO-only, shutter-only en beide handmatig gecontroleerd; preferencewisseling behoudt de handmatige waarden. Beide AUTO herstelt BnC Auto. Voorkeur blijft na herstart behouden.
- Lenswissel naar fysieke sensor 4/YUV behoudt BN_AUTO en gebruikt veilig Camera2 AE. Terug naar sensor 2/RAW herstelt BnC Auto.
- RAW10: 419.999981 ms / ISO 102; RAW_SENSOR: 39.999967 ms / ISO 1097. Beide één opname gepubliceerd met FULL_SUCCESS, sluitende triggeraccounting en gecontroleerde JPEG-checksum/RAW-metadata. Daarmee blijft ook de bestaande lange exact-still-route werken.
- Compose UI-tests kunnen op dit Android 17-toestel niet starten door Espresso `InputManager.getInstance`; UI-acties zijn daarom in de echte app via ADB gecontroleerd. De extra oude dial/ring-tests hebben bestaande bron-/fixtureproblemen en behoren niet tot de groene gerichte set.
- Toestel achtergelaten op Standard Auto, RAW10 en JPEG; huidige shortcutindeling behouden. Geen commit/push; geen wijziging aan fotografische parameters of ISP.

Evidence: `work/global-exposure/`, `work/global-exposure-final-tests.log`, `work/global-exposure-repository-connected.log`.

## Gewijzigde bestanden

`SettingsRepository.kt`, `AppSettingsScreen.kt`, `CameraScreen.kt`, `ViewfinderQuickSettingsOverlay.kt`, `ProfileCaptureExposureSettingsScreen.kt`, `CaptureRecipeFactory.kt`, `CaptureExposurePreferences.kt`, `CaptureSettingsSchema.kt`, `LibpatcherProfileResolver.kt`, `BnCameraManager.kt`, `FrameRingBuffer.kt`, `GlobalExposureControlTest.kt`, `GlobalExposureControlConnectedTest.kt`, `FrameRingBufferTest.kt` en dit verslag.
