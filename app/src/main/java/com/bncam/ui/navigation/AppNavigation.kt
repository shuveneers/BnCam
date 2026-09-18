package com.bncam.ui.navigation

import android.os.SystemClock
import com.bncam.core.debug.Phase0PerformanceTrace
import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bncam.core.engine.BnCameraManager
import com.bncam.core.engine.LensInfo
import com.bncam.data.profile.ProfileManager
import com.bncam.data.settings.SettingsRepository
import com.bncam.data.settings.dataStore
import com.bncam.ui.screens.capture.CameraScreen
import com.bncam.ui.screens.settings.*
import com.bncam.ui.screens.settings.lens_profiles.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle

object Routes {
    const val CAMERA = "camera"
    const val SETTINGS_MAIN = "settings_main"
    const val APP_SETTINGS = "app_settings"
    const val VIEWFINDER = "viewfinder"
    const val CONFIG = "config"
    const val WATERMARK = "watermark"
    const val INFO = "info"
    const val LENS_LIST = "lens_list"
    const val LENS_DETAIL = "lens_detail/{lensId}"
    const val LENS_NOISE_MODEL = "lens_detail/{lensId}/noise_model"
    const val LENS_MANUAL_NOISE_MODEL = "lens_detail/{lensId}/noise_model/manual"
    const val LENS_BLACK_LEVEL = "lens_detail/{lensId}/black_level"
    const val LENS_COLOR_MATRIX = "lens_detail/{lensId}/color_matrix"
    const val LENS_RAW_STREAM_BINDING = "lens_detail/{lensId}/raw_stream_binding"
    const val PROFILE_LIST = "profile_list/{lensId}/{profileCount}"
    const val PROFILE_EDIT = "profile_edit/{lensId}/{profileIndex}"
    const val PROFILE_JPEG = "profile_edit/{lensId}/{profileIndex}/jpeg"
    const val PROFILE_SPECTRA = "profile_edit/{lensId}/{profileIndex}/noise/spectra" // legacy deep link -> Neural Denoise
    const val PROFILE_CAPTURE_EXPOSURE = "profile_edit/{lensId}/{profileIndex}/capture/exposure"
    const val PROFILE_DENOISE = "profile_edit/{lensId}/{profileIndex}/isp/denoise"
    const val PROFILE_LIGHT_SHADOW = "profile_edit/{lensId}/{profileIndex}/isp/light_shadow"
    const val PROFILE_AWB = "profile_edit/{lensId}/{profileIndex}/isp/awb"
    const val PROFILE_COLOR_MANAGER = "profile_edit/{lensId}/{profileIndex}/isp/color_manager"
    const val PROFILE_EXPOSURE = "profile_edit/{lensId}/{profileIndex}/isp/exposure" // legacy deep link
    const val PROFILE_TONAL_RANGE = "profile_edit/{lensId}/{profileIndex}/isp/tonal_range"
    const val PROFILE_CONTRAST_LOCAL_TONE = "profile_edit/{lensId}/{profileIndex}/isp/contrast_local_tone"
    const val PROFILE_CURVES = "profile_edit/{lensId}/{profileIndex}/isp/curves"
    const val PROFILE_PRESENCE = "profile_edit/{lensId}/{profileIndex}/isp/presence"
    const val PROFILE_SHARPNESS = "profile_edit/{lensId}/{profileIndex}/isp/sharpness"
    const val PROFILE_TRANSFER = "profile_edit/{lensId}/{profileIndex}/others/transfer"
    const val PROFILE_OTHER_SETTINGS = "profile_edit/{lensId}/{profileIndex}/others/settings"
    const val PROFILE_MULTI_FRAME = "profile_edit/{lensId}/{profileIndex}/raw/multi_frame"
    const val VENDOR_TAG_MANAGEMENT = "vendor_tag_management"

    const val LENS_ADVANCED_NOISE_CALIBRATION = "lens_detail/{lensId}/noise_model/advanced"

    fun encodeRouteArg(arg: String): String = java.net.URLEncoder.encode(arg, "UTF-8").replace("+", "%20")
    fun parseRouteArg(arg: String?): String {
        if (arg.isNullOrBlank()) return "0"
        return runCatching { java.net.URLDecoder.decode(arg, "UTF-8") }.getOrDefault(arg)
    }

    fun lensDetail(lensId: String) = "lens_detail/${encodeRouteArg(lensId)}"
    fun lensNoiseModel(lensId: String) = "lens_detail/${encodeRouteArg(lensId)}/noise_model"
    fun lensManualNoiseModel(lensId: String) = "lens_detail/${encodeRouteArg(lensId)}/noise_model/manual"
    fun lensAdvancedNoiseCalibration(lensId: String) = "lens_detail/${encodeRouteArg(lensId)}/noise_model/advanced"
    fun lensBlackLevel(lensId: String) = "lens_detail/${encodeRouteArg(lensId)}/black_level"
    fun lensColorMatrix(lensId: String) = "lens_detail/${encodeRouteArg(lensId)}/color_matrix"
    fun lensRawStreamBinding(lensId: String) = "lens_detail/${encodeRouteArg(lensId)}/raw_stream_binding"
    fun profileList(lensId: String, count: Int) = "profile_list/${encodeRouteArg(lensId)}/$count"
    fun profileEdit(lensId: String, index: Int) = "profile_edit/${encodeRouteArg(lensId)}/$index"
    fun profileJpeg(lensId: String, index: Int) = "profile_edit/${encodeRouteArg(lensId)}/$index/jpeg"
    fun profileSpectra(lensId: String, index: Int) = "profile_edit/${encodeRouteArg(lensId)}/$index/noise/spectra"
    fun profileCaptureExposure(lensId: String, index: Int) = "profile_edit/${encodeRouteArg(lensId)}/$index/capture/exposure"
    fun profileDenoise(lensId: String, index: Int) = "profile_edit/${encodeRouteArg(lensId)}/$index/isp/denoise"
    fun profileLightShadow(lensId: String, index: Int) = "profile_edit/${encodeRouteArg(lensId)}/$index/isp/light_shadow"
    fun profileAwb(lensId: String, index: Int) = "profile_edit/${encodeRouteArg(lensId)}/$index/isp/awb"
    fun profileColorManager(lensId: String, index: Int) = "profile_edit/${encodeRouteArg(lensId)}/$index/isp/color_manager"
    fun profileExposure(lensId: String, index: Int) = "profile_edit/${encodeRouteArg(lensId)}/$index/isp/exposure"
    fun profileTonalRange(lensId: String, index: Int) = "profile_edit/${encodeRouteArg(lensId)}/$index/isp/tonal_range"
    fun profileContrastLocalTone(lensId: String, index: Int) = "profile_edit/${encodeRouteArg(lensId)}/$index/isp/contrast_local_tone"
    fun profileCurves(lensId: String, index: Int) = "profile_edit/${encodeRouteArg(lensId)}/$index/isp/curves"
    fun profilePresence(lensId: String, index: Int) = "profile_edit/${encodeRouteArg(lensId)}/$index/isp/presence"
    fun profileSharpness(lensId: String, index: Int) = "profile_edit/${encodeRouteArg(lensId)}/$index/isp/sharpness"
    fun profileTransfer(lensId: String, index: Int) = "profile_edit/${encodeRouteArg(lensId)}/$index/others/transfer"
    fun profileOtherSettings(lensId: String, index: Int) = "profile_edit/${encodeRouteArg(lensId)}/$index/others/settings"
    fun profileMultiFrame(lensId: String, index: Int) = "profile_edit/${encodeRouteArg(lensId)}/$index/raw/multi_frame"
}

private fun profileEditRouteFor(profileId: String): String? {
    if (profileId.endsWith("_disabled")) return null

    val marker = "_profile_"
    val markerIndex = profileId.lastIndexOf(marker)
    if (markerIndex <= 0) return null

    val lensId = profileId.substring(0, markerIndex)
    val profileIndex = profileId.substring(markerIndex + marker.length).toIntOrNull() ?: return null
    return Routes.profileEdit(lensId, profileIndex)
}

private fun profileIndexFromProfileId(profileId: String): Int? {
    val marker = "_profile_"
    val markerIndex = profileId.lastIndexOf(marker)
    if (markerIndex <= 0) return null
    return profileId.substring(markerIndex + marker.length).toIntOrNull()
}

private fun profileIdForLens(lensId: String, profileIndex: Int): String {
    return "${lensId}_profile_$profileIndex"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val isRootCameraRoute = currentBackStackEntry?.destination?.route == Routes.CAMERA
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val cameraManager = remember { BnCameraManager(context) }
    val settingsRepo = remember { SettingsRepository(context) }

    fun logNavigationEvent(event: String, route: String) {
        Log.i(
            "BnCamNavigationDiag",
            "event=$event route=$route elapsedRealtimeNs=${SystemClock.elapsedRealtimeNanos()} " +
                "cameraSessionAction=none"
        )
    }

    // 1. Lens Assignments (Slot Systeem)
    val assignedLensIds by settingsRepo.assignedLensIdsFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    // Camera enumeration can cross Binder into CameraService. Keep it out of composition;
    // startup below publishes the first catalog and later assignment changes refresh it on IO.
    var realLenses by remember { mutableStateOf<List<LensInfo>>(emptyList()) }
    val slotAssignments by settingsRepo.slotAssignmentsFlow.collectAsStateWithLifecycle(initialValue = emptyMap())
    val lensAutoAssignmentVersion by settingsRepo.lensAutoAssignmentVersionFlow.collectAsStateWithLifecycle(initialValue = 0)

    // Auto-assign only fills missing primary slots. It never overwrites a manual slot value.
    LaunchedEffect(slotAssignments, lensAutoAssignmentVersion) {
        if (lensAutoAssignmentVersion < SettingsRepository.LENS_AUTO_ASSIGNMENT_VERSION) {
            val autoAssignments = withContext(Dispatchers.IO) {
                cameraManager.getAutoAssignedSlots()
            }
            settingsRepo.applyMissingPrimaryAutoAssignments(autoAssignments)
        }
    }

    // Filter de échte lenzen op basis van wat er aan slots is toegewezen
    val assignedLenses = remember(assignedLensIds, realLenses) {
        realLenses.filter { assignedLensIds.contains(it.id) }
    }

    val savedActiveLensId by settingsRepo.activeLensIdFlow.collectAsStateWithLifecycle(initialValue = null)
    val savedActiveProfileId by settingsRepo.activeProfileIdFlow.collectAsStateWithLifecycle(initialValue = null)

    var activeLens by remember {
        mutableStateOf(assignedLenses.firstOrNull() ?: realLenses.firstOrNull { it.id == "0" } ?: LensInfo("0", "Main", 1, false))
    }
    var startupLensStateResolved by remember { mutableStateOf(false) }
    var initialCameraRouteReleased by remember { mutableStateOf(false) }
    var lensSwitchResolutionInFlight by remember { mutableStateOf(false) }
    var profileCountByLens by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    // Do not let CameraScreen open a fallback/default camera before DataStore has told us
    // which lens actually owns the previous session. Opening camera 0 first and then restoring
    // a persisted physical lens creates an unnecessary CameraDevice power-cycle at every cold
    // start, including audible AF/OIS actuator clicks on some devices.
    LaunchedEffect(settingsRepo) {
        val (persistedAssignedLensIds, persistedActiveLensId) = withContext(Dispatchers.IO) {
            settingsRepo.assignedLensIdsFlow.first() to settingsRepo.activeLensIdFlow.first()
        }
        val startupCatalog = withContext(Dispatchers.IO) {
            cameraManager.getAvailableLenses(
                additionalLensIds = persistedAssignedLensIds
            )
        }
        realLenses = startupCatalog
        val startupAssigned = startupCatalog.filter { persistedAssignedLensIds.contains(it.id) }
        val startupAvailable = startupAssigned.ifEmpty { startupCatalog }
        val startupLens = persistedActiveLensId?.let { savedId ->
            startupAvailable.firstOrNull { it.id == savedId }
        } ?: startupAvailable.firstOrNull()

        if (startupLens != null) {
            activeLens = startupLens
        }
        startupLensStateResolved = true
    }

    LaunchedEffect(assignedLensIds, startupLensStateResolved) {
        if (!startupLensStateResolved) return@LaunchedEffect
        val refreshedCatalog = withContext(Dispatchers.IO) {
            cameraManager.getAvailableLenses(additionalLensIds = assignedLensIds)
        }
        if (refreshedCatalog.isNotEmpty()) {
            realLenses = refreshedCatalog
        }
    }

    LaunchedEffect(assignedLenses, savedActiveLensId, startupLensStateResolved) {
        if (!startupLensStateResolved) return@LaunchedEffect
        val availableLenses = assignedLenses.ifEmpty { realLenses }
        if (availableLenses.isEmpty()) return@LaunchedEffect

        val restoredLens = savedActiveLensId?.let { savedId ->
            availableLenses.firstOrNull { it.id == savedId }
        }
        val currentStillAvailable = availableLenses.any { it.id == activeLens.id }
        val nextLens = restoredLens ?: if (currentStillAvailable) activeLens else availableLenses.first()

        if (activeLens.id != nextLens.id) {
            activeLens = nextLens
        }
    }

    // Preload the tiny per-lens profile-count metadata while the viewfinder is already running.
    // Lens taps must not wait on a DataStore `first()` before CameraScreen can publish the target
    // sensor and start its Camera2 handover.
    LaunchedEffect(assignedLenses, startupLensStateResolved) {
        if (!startupLensStateResolved || assignedLenses.isEmpty()) return@LaunchedEffect
        val counts = withContext(Dispatchers.IO) {
            assignedLenses.associate { lens ->
                lens.id to settingsRepo.getProfileCountFlow(lens.id).first()
            }
        }
        profileCountByLens = counts
    }

    // 2. Profiel Namen Persistentie
    val preferences by context.dataStore.data.collectAsStateWithLifecycle(initialValue = null as Preferences?)
    val savedProfileNames = remember(preferences, activeLens.id) {
        val namesMap = mutableMapOf<String, String>()
        preferences?.asMap()?.forEach { (key, value) ->
            if (key.name.startsWith("profile_name_") && value is String) {
                namesMap[key.name.removePrefix("profile_name_")] = value
            }
        }
        namesMap
    }
    val savedProfileModes = remember(preferences, activeLens.id) {
        val modesMap = mutableMapOf<String, com.bncam.core.engine.CaptureStrategy>()
        preferences?.asMap()?.forEach { (key, value) ->
            if (key.name.startsWith("profile_mode_") && value is String) {
                try {
                    modesMap[key.name.removePrefix("profile_mode_")] = com.bncam.core.engine.CaptureStrategy.valueOf(value)
                } catch (_: Exception) {}
            }
        }
        modesMap
    }

    // 3. Profiel Logica
    val activeProfileCount by settingsRepo.getProfileCountFlow(activeLens.id).collectAsStateWithLifecycle(initialValue = 3)
    val allLensProfiles = remember(activeLens.id, activeProfileCount, savedProfileNames, savedProfileModes) {
        ProfileManager.getProfilesForLens(activeLens.id, activeProfileCount, savedProfileNames, savedProfileModes)
    }
    val visibleProfiles = allLensProfiles.filter { it.isVisibleInUi }

    var activeProfile by remember {
        mutableStateOf(visibleProfiles.firstOrNull() ?: allLensProfiles.first())
    }

    var requestedProfileIndexAfterLensSwitch by remember {
        mutableStateOf<Int?>(null)
    }

    LaunchedEffect(activeLens.id, visibleProfiles, allLensProfiles, savedActiveProfileId) {
        val requestedProfileForNewLens = requestedProfileIndexAfterLensSwitch?.let { requestedIndex ->
            visibleProfiles.firstOrNull { it.id == profileIdForLens(activeLens.id, requestedIndex) }
        }

        val restoredProfile = savedActiveProfileId?.let { savedId ->
            visibleProfiles.firstOrNull { it.id == savedId }
        }

        val sameSlotOnNewLens = profileIndexFromProfileId(activeProfile.id)?.let { currentIndex ->
            visibleProfiles.firstOrNull { it.id == profileIdForLens(activeLens.id, currentIndex) }
        }

        val currentStillAvailable = visibleProfiles.any { it.id == activeProfile.id }

        val nextProfile = requestedProfileForNewLens
            ?: restoredProfile
            ?: sameSlotOnNewLens
            ?: if (currentStillAvailable) {
                visibleProfiles.firstOrNull { it.id == activeProfile.id } ?: activeProfile
            } else {
                visibleProfiles.firstOrNull() ?: allLensProfiles.first()
            }

        if (activeProfile != nextProfile) {
            activeProfile = nextProfile
        }

        if (requestedProfileForNewLens != null) {
            requestedProfileIndexAfterLensSwitch = null
        }
    }

    // Cold-start gating is a one-time latch. Once lens/profile state has converged and the
    // persistent camera host has been released, later runtime lens changes must never remove
    // CameraScreen from composition just because one persisted flow is a frame behind another.
    LaunchedEffect(startupLensStateResolved, activeLens.id, activeProfile.targetLensId) {
        if (!initialCameraRouteReleased &&
            startupLensStateResolved &&
            activeProfile.targetLensId == activeLens.id
        ) {
            initialCameraRouteReleased = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black) // Dit zorgt dat de 'ondergrond' altijd zwart is
    ) {
        // The camera/viewfinder is a persistent host. In-app destinations are drawn above it, so
        // opening Settings or a profile editor does not dispose the GL SurfaceTexture or trigger
        // any Camera2/ImageReader/ring-buffer session transition. Hardware lifetime follows the
        // Activity lifecycle and real lens/format transitions, never NavBackStackEntry lifetime.
        if (initialCameraRouteReleased) {
            LaunchedEffect(Unit) {
                Phase0PerformanceTrace.cameraScreenComposed()
                logNavigationEvent("CAMERA_SCREEN_COMPOSED", Routes.CAMERA)
            }
            CameraScreen(
                onNavigateToSettings = {
                    logNavigationEvent("SETTINGS_NAV_REQUEST", Routes.SETTINGS_MAIN)
                    navController.navigate(Routes.SETTINGS_MAIN)
                },
                onNavigateToProfileSettings = { profileId ->
                    // Long-pressing a profile means the user is entering *that profile's*
                    // editor. Promote it to the active profile before navigation so returning
                    // to the persistent viewfinder cannot silently fall back to the profile
                    // that happened to be active before the long press.
                    visibleProfiles.firstOrNull { it.id == profileId }?.let { selectedProfile ->
                        if (activeProfile.id != selectedProfile.id) {
                            activeProfile = selectedProfile
                            coroutineScope.launch {
                                settingsRepo.setActiveProfileId(selectedProfile.id)
                            }
                        }
                    }
                    profileEditRouteFor(profileId)?.let { route ->
                        logNavigationEvent("PROFILE_SETTINGS_NAV_REQUEST", route)
                        navController.navigate(route)
                    }
                },
                onCapture = { /* ... blijft hetzelfde ... */ },
                activeProfile = activeProfile,
                visibleProfiles = visibleProfiles,
                onProfileSelected = { selectedProfile ->
                    activeProfile = selectedProfile
                    coroutineScope.launch {
                        settingsRepo.setActiveProfileId(selectedProfile.id)
                    }
                },
                activeLens = activeLens,
                visibleLenses = assignedLenses,
                bnCameraManager = cameraManager,
                isRootCameraRoute = isRootCameraRoute,
                onLensSelected = { selectedLens ->
                    if (selectedLens.id != activeLens.id && !lensSwitchResolutionInFlight) {
                        Phase0PerformanceTrace.beginLensSwitch(
                            fromLensId = activeLens.id,
                            targetLensId = selectedLens.id
                        )
                        val currentProfileIndex = profileIndexFromProfileId(activeProfile.id)
                        val targetProfileCount = preferences
                            ?.get(intPreferencesKey("profile_count_${selectedLens.id}"))
                            ?: profileCountByLens[selectedLens.id]
                            ?: 3
                        val targetProfiles = ProfileManager.getProfilesForLens(
                            selectedLens.id,
                            targetProfileCount,
                            savedProfileNames,
                            savedProfileModes
                        ).filter { it.isVisibleInUi }
                        val targetProfile = currentProfileIndex?.let { currentIndex ->
                            targetProfiles.firstOrNull {
                                it.id == profileIdForLens(selectedLens.id, currentIndex)
                            }
                        } ?: targetProfiles.firstOrNull()

                        if (targetProfile != null) {
                            Phase0PerformanceTrace.lensRouteResolutionDone(
                                targetLensId = selectedLens.id,
                                detail = "profile=${targetProfile.id}"
                            )
                            lensSwitchResolutionInFlight = true
                            // Publish the coherent lens/profile pair immediately. Camera2 can start
                            // its transition in this frame instead of waiting for DataStore I/O.
                            Phase0PerformanceTrace.lensTransitionStarted(selectedLens.id)
                            activeProfile = targetProfile
                            activeLens = selectedLens
                            requestedProfileIndexAfterLensSwitch = null

                            coroutineScope.launch {
                                try {
                                    settingsRepo.setActiveLensAndProfile(
                                        lensId = selectedLens.id,
                                        profileId = targetProfile.id
                                    )
                                } finally {
                                    lensSwitchResolutionInFlight = false
                                }
                            }
                        } else {
                            Phase0PerformanceTrace.lensSwitchCancelled(
                                targetLensId = selectedLens.id,
                                reason = "no_target_profile"
                            )
                        }
                    }
                }
            )
        } else {
            // Keep startup visually quiet while persisted lens/profile state converges. Crucially,
            // CameraScreen is not composed yet, so a fallback CameraDevice is never opened first.
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        }

        NavHost(
            navController = navController,
            startDestination = Routes.CAMERA,
            modifier = Modifier.fillMaxSize(),
            // --- HORIZONTALE SWIPE ANIMATIES ---
            enterTransition = {
                val slide = slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                )
                // The persistent CameraScreen deliberately lives below NavHost. Fading two
                // non-camera destinations makes both temporarily translucent and exposes that
                // live viewfinder through Settings/Profile pages. Preserve the slide animation
                // but use opacity only when Camera is actually one side of the transition.
                if (initialState.destination.route == Routes.CAMERA ||
                    targetState.destination.route == Routes.CAMERA
                ) slide + fadeIn(animationSpec = tween(300)) else slide
            },
            exitTransition = {
                val slide = slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                )
                if (initialState.destination.route == Routes.CAMERA ||
                    targetState.destination.route == Routes.CAMERA
                ) slide + fadeOut(animationSpec = tween(300)) else slide
            },
            popEnterTransition = {
                val slide = slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                )
                if (initialState.destination.route == Routes.CAMERA ||
                    targetState.destination.route == Routes.CAMERA
                ) slide + fadeIn(animationSpec = tween(300)) else slide
            },
            popExitTransition = {
                val slide = slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                )
                if (initialState.destination.route == Routes.CAMERA ||
                    targetState.destination.route == Routes.CAMERA
                ) slide + fadeOut(animationSpec = tween(300)) else slide
            }
        ) {

            // Camera content is hosted persistently below NavHost. The route itself intentionally
            // owns no Camera2/Surface resources.
            composable(Routes.CAMERA) {
                LaunchedEffect(Unit) {
                    logNavigationEvent("CAMERA_SCREEN_VISIBLE", Routes.CAMERA)
                }
            }

            composable(Routes.SETTINGS_MAIN) {
                LaunchedEffect(Unit) {
                    logNavigationEvent("SETTINGS_VISIBLE", Routes.SETTINGS_MAIN)
                }
                SettingsScreen(
                    activeProfileName = activeProfile.name,
                    activeCaptureMode = activeProfile.captureStrategy.name,
                    onNavigateBack = {
                        logNavigationEvent("BACK_REQUEST", Routes.SETTINGS_MAIN)
                        navController.popBackStack()
                    },
                    onNavigateToAppSettings = { navController.navigate(Routes.APP_SETTINGS) },
                    onNavigateToViewfinderSettings = { navController.navigate(Routes.VIEWFINDER) },
                    onNavigateToLensSettings = { navController.navigate(Routes.LENS_LIST) },
                    onNavigateToConfigSettings = { navController.navigate(Routes.CONFIG) },
                    onNavigateToWatermarkSettings = { navController.navigate(Routes.WATERMARK) },
                    onNavigateToInfo = { navController.navigate(Routes.INFO) }
                )
            }

            // Statische Settings Schermen
            composable(Routes.APP_SETTINGS) {
                AppSettingsScreen(activeProfileId = activeProfile.id) {
                    navController.popBackStack()
                }
            }
            composable(Routes.VIEWFINDER) { ViewfinderScreen { navController.popBackStack() } }
            composable(Routes.CONFIG) {
                ConfigScreen(
                    activeLensId = activeLens.id,
                    activeProfileId = activeProfile.id,
                    activeProfileCount = activeProfileCount,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Routes.WATERMARK) { WatermarkScreen { navController.popBackStack() } }
            composable(Routes.INFO) { InfoScreen { navController.popBackStack() } }

            // Beheer van geconfigureerde vendor tags
            composable(Routes.VENDOR_TAG_MANAGEMENT) {
                VendorTagsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // Lens & Profile Flow
            composable(Routes.LENS_LIST) {
                LensListScreen(
                    allLenses = realLenses,
                    // FIX: Oude parameters die we niet meer gebruiken vullen we leeg in
                    visibleLensIds = emptySet(),
                    onToggleVisibility = { _, _ -> },
                    onInjectVendorTag = { _, _, _, _ -> },
                    // NIEUW: Navigatie naar de beheerpagina
                    onNavigateToVendorTags = {
                        navController.navigate(Routes.VENDOR_TAG_MANAGEMENT)
                    },
                    onNavigateToLensDetail = { id -> navController.navigate(Routes.lensDetail(id)) },
                    onDiscoverManualLenses = {
                        withContext(Dispatchers.IO) {
                            cameraManager.getManualDiscoveryLenses(assignedLensIds)
                        }
                    },
                    onResolveManualLensId = { requestedId ->
                        withContext(Dispatchers.IO) {
                            cameraManager.resolveManualLensId(requestedId)
                        }
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Routes.LENS_DETAIL,
                arguments = listOf(navArgument("lensId") { type = NavType.StringType })
            ) { entry ->
                val lensId = entry.arguments?.getString("lensId") ?: "0"
                val count by settingsRepo.getProfileCountFlow(lensId).collectAsStateWithLifecycle(initialValue = 3)

                LensDetailScreen(
                    lensId = lensId,
                    initialProfileAmount = count.toString(),
                    onProfileAmountChanged = { newCount ->
                        coroutineScope.launch {
                            settingsRepo.setProfileCount(
                                lensId,
                                newCount.toInt()
                            )
                        }
                    },
                    onNavigateToProfileList = { id ->
                        navController.navigate(
                            Routes.profileList(
                                id,
                                count
                            )
                        )
                    },
                    onNavigateToNoiseModel = {
                        navController.navigate(Routes.lensNoiseModel(lensId))
                    },
                    onNavigateToBlackLevel = {
                        navController.navigate(Routes.lensBlackLevel(lensId))
                    },
                    onNavigateToColorMatrix = {
                        navController.navigate(Routes.lensColorMatrix(lensId))
                    },
                    onNavigateToRawStreamBinding = {
                        navController.navigate(Routes.lensRawStreamBinding(lensId))
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Routes.LENS_NOISE_MODEL,
                arguments = listOf(navArgument("lensId") { type = NavType.StringType })
            ) { entry ->
                val parsedLensId = Routes.parseRouteArg(entry.arguments?.getString("lensId"))
                NoiseModelSettingsScreen(
                    lensId = parsedLensId,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToManualNoiseModel = {
                        navController.navigate(Routes.lensManualNoiseModel(parsedLensId))
                    }
                )
            }

            composable(
                route = Routes.LENS_COLOR_MATRIX,
                arguments = listOf(navArgument("lensId") { type = NavType.StringType })
            ) { entry ->
                ColorMatrixSettingsScreen(
                    lensId = Routes.parseRouteArg(entry.arguments?.getString("lensId")),
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Routes.LENS_RAW_STREAM_BINDING,
                arguments = listOf(navArgument("lensId") { type = NavType.StringType })
            ) { entry ->
                RawStreamBindingSettingsScreen(
                    lensId = Routes.parseRouteArg(entry.arguments?.getString("lensId")),
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Routes.LENS_MANUAL_NOISE_MODEL,
                arguments = listOf(navArgument("lensId") { type = NavType.StringType })
            ) { entry ->
                val parsedLensId = Routes.parseRouteArg(entry.arguments?.getString("lensId"))
                ManualNoiseModelSettingsScreen(
                    lensId = parsedLensId,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToAdvancedCalibration = {
                        navController.navigate(Routes.lensAdvancedNoiseCalibration(parsedLensId))
                    }
                )
            }

            composable(
                route = Routes.LENS_ADVANCED_NOISE_CALIBRATION,
                arguments = listOf(navArgument("lensId") { type = NavType.StringType })
            ) { entry ->
                val parsedLensId = Routes.parseRouteArg(entry.arguments?.getString("lensId"))
                com.bncam.ui.screens.settings.lens_profiles.AdvancedSensorNoiseCalibrationScreen(
                    lensId = parsedLensId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Routes.LENS_BLACK_LEVEL,
                arguments = listOf(navArgument("lensId") { type = NavType.StringType })
            ) { entry ->
                BlackLevelSettingsScreen(
                    lensId = entry.arguments?.getString("lensId") ?: "0",
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Routes.PROFILE_LIST,
                arguments = listOf(
                    navArgument("lensId") { type = NavType.StringType },
                    navArgument("profileCount") { type = NavType.IntType }
                )
            ) { entry ->
                val lensId = entry.arguments?.getString("lensId") ?: "0"
                val count = entry.arguments?.getInt("profileCount") ?: 3

                ProfileListScreen(
                    lensId = lensId,
                    profileCount = count,
                    savedProfileNames = savedProfileNames,
                    onNavigateToProfileDetail = { _, index ->
                        navController.navigate(
                            Routes.profileEdit(
                                lensId,
                                index
                            )
                        )
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(
                route = Routes.PROFILE_EDIT,
                arguments = listOf(
                    navArgument("lensId") { type = NavType.StringType },
                    navArgument("profileIndex") { type = NavType.IntType }
                )
            ) { entry ->
                val lensId = entry.arguments?.getString("lensId") ?: "0"
                val index = entry.arguments?.getInt("profileIndex") ?: 1
                val profileId = "${lensId}_profile_$index"

                val initialName = savedProfileNames[profileId] ?: "Profile $index"

                // NIEUW: Haal de opgeslagen mode op (met SINGLE_FRAME_ZSL als fallback)
                val initialMode by settingsRepo.getProfileCaptureModeFlow(profileId)
                    .collectAsStateWithLifecycle(initialValue = com.bncam.core.engine.CaptureStrategy.SINGLE_FRAME_ZSL)

                ProfileEditScreen(
                    lensId = lensId,
                    profileIndex = index,
                    initialName = initialName,
                    initialMode = initialMode, // NIEUW
                    onSaveName = { newName ->
                        coroutineScope.launch { settingsRepo.setProfileName(profileId, newName) }
                    },
                    onSaveMode = { newStrategy -> // NIEUW
                        coroutineScope.launch {
                            settingsRepo.setProfileCaptureMode(
                                profileId,
                                newStrategy
                            )
                        }
                    },
                    onNavigateToJpegTuning = {
                        navController.navigate(Routes.profileJpeg(lensId, index))
                    },
                    onNavigateToShotBias = {
                        navController.navigate(Routes.profileCaptureExposure(lensId, index))
                    },
                    onNavigateToDenoise = {
                        navController.navigate(Routes.profileDenoise(lensId, index))
                    },
                    onNavigateToLightShadow = {
                        navController.navigate(Routes.profileLightShadow(lensId, index))
                    },
                    onNavigateToCurves = {
                        navController.navigate(Routes.profileCurves(lensId, index))
                    },
                    onNavigateToAwb = {
                        navController.navigate(Routes.profileAwb(lensId, index))
                    },
                    onNavigateToColorManager = {
                        navController.navigate(Routes.profileColorManager(lensId, index))
                    },
                    onNavigateToSharpness = {
                        navController.navigate(Routes.profileSharpness(lensId, index))
                    },
                    onNavigateToProfileTransfer = {
                        navController.navigate(Routes.profileTransfer(lensId, index))
                    },
                    onNavigateToOtherSettings = {
                        navController.navigate(Routes.profileOtherSettings(lensId, index))
                    },
                    onNavigateToMultiFrame = {
                        navController.navigate(Routes.profileMultiFrame(lensId, index))
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Routes.PROFILE_SPECTRA,
                arguments = listOf(
                    navArgument("lensId") { type = NavType.StringType },
                    navArgument("profileIndex") { type = NavType.IntType }
                )
            ) { entry ->
                ProfileDenoiseSettingsScreen(
                    lensId = Routes.parseRouteArg(entry.arguments?.getString("lensId")),
                    profileIndex = entry.arguments?.getInt("profileIndex") ?: 1,
                    onNavigateBack = { navController.popBackStack() }
                )
            }


            composable(
                route = Routes.PROFILE_CAPTURE_EXPOSURE,
                arguments = listOf(
                    navArgument("lensId") { type = NavType.StringType },
                    navArgument("profileIndex") { type = NavType.IntType }
                )
            ) { entry ->
                ProfileCaptureExposureSettingsScreen(
                    lensId = Routes.parseRouteArg(entry.arguments?.getString("lensId")),
                    profileIndex = entry.arguments?.getInt("profileIndex") ?: 1,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Routes.PROFILE_DENOISE,
                arguments = listOf(
                    navArgument("lensId") { type = NavType.StringType },
                    navArgument("profileIndex") { type = NavType.IntType }
                )
            ) { entry ->
                ProfileDenoiseSettingsScreen(
                    lensId = Routes.parseRouteArg(entry.arguments?.getString("lensId")),
                    profileIndex = entry.arguments?.getInt("profileIndex") ?: 1,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Routes.PROFILE_LIGHT_SHADOW,
                arguments = listOf(
                    navArgument("lensId") { type = NavType.StringType },
                    navArgument("profileIndex") { type = NavType.IntType }
                )
            ) { entry ->
                ProfileLightShadowSettingsScreen(
                    lensId = Routes.parseRouteArg(entry.arguments?.getString("lensId")),
                    profileIndex = entry.arguments?.getInt("profileIndex") ?: 1,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Routes.PROFILE_AWB,
                arguments = listOf(
                    navArgument("lensId") { type = NavType.StringType },
                    navArgument("profileIndex") { type = NavType.IntType }
                )
            ) { entry ->
                val lensId = Routes.parseRouteArg(entry.arguments?.getString("lensId"))
                val profileIndex = entry.arguments?.getInt("profileIndex") ?: 1
                AwbCalibrationSettingsScreen(
                    profileId = "${lensId}_profile_$profileIndex",
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Routes.PROFILE_COLOR_MANAGER,
                arguments = listOf(
                    navArgument("lensId") { type = NavType.StringType },
                    navArgument("profileIndex") { type = NavType.IntType }
                )
            ) { entry ->
                ProfilePresenceSettingsScreen(
                    lensId = Routes.parseRouteArg(entry.arguments?.getString("lensId")),
                    profileIndex = entry.arguments?.getInt("profileIndex") ?: 1,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Routes.PROFILE_EXPOSURE,
                arguments = listOf(
                    navArgument("lensId") { type = NavType.StringType },
                    navArgument("profileIndex") { type = NavType.IntType }
                )
            ) { entry ->
                ProfileExposureSettingsScreen(
                    lensId = Routes.parseRouteArg(entry.arguments?.getString("lensId")),
                    profileIndex = entry.arguments?.getInt("profileIndex") ?: 1,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Routes.PROFILE_TONAL_RANGE,
                arguments = listOf(
                    navArgument("lensId") { type = NavType.StringType },
                    navArgument("profileIndex") { type = NavType.IntType }
                )
            ) { entry ->
                ProfileTonalRangeSettingsScreen(
                    lensId = Routes.parseRouteArg(entry.arguments?.getString("lensId")),
                    profileIndex = entry.arguments?.getInt("profileIndex") ?: 1,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Routes.PROFILE_CONTRAST_LOCAL_TONE,
                arguments = listOf(
                    navArgument("lensId") { type = NavType.StringType },
                    navArgument("profileIndex") { type = NavType.IntType }
                )
            ) { entry ->
                ProfileContrastLocalToneSettingsScreen(
                    lensId = Routes.parseRouteArg(entry.arguments?.getString("lensId")),
                    profileIndex = entry.arguments?.getInt("profileIndex") ?: 1,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Routes.PROFILE_CURVES,
                arguments = listOf(
                    navArgument("lensId") { type = NavType.StringType },
                    navArgument("profileIndex") { type = NavType.IntType }
                )
            ) { entry ->
                ProfileCurveSettingsScreen(
                    lensId = Routes.parseRouteArg(entry.arguments?.getString("lensId")),
                    profileIndex = entry.arguments?.getInt("profileIndex") ?: 1,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Routes.PROFILE_PRESENCE,
                arguments = listOf(
                    navArgument("lensId") { type = NavType.StringType },
                    navArgument("profileIndex") { type = NavType.IntType }
                )
            ) { entry ->
                ProfilePresenceSettingsScreen(
                    lensId = Routes.parseRouteArg(entry.arguments?.getString("lensId")),
                    profileIndex = entry.arguments?.getInt("profileIndex") ?: 1,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Routes.PROFILE_SHARPNESS,
                arguments = listOf(
                    navArgument("lensId") { type = NavType.StringType },
                    navArgument("profileIndex") { type = NavType.IntType }
                )
            ) { entry ->
                ProfileSharpnessSettingsScreen(
                    lensId = Routes.parseRouteArg(entry.arguments?.getString("lensId")),
                    profileIndex = entry.arguments?.getInt("profileIndex") ?: 1,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Routes.PROFILE_TRANSFER,
                arguments = listOf(
                    navArgument("lensId") { type = NavType.StringType },
                    navArgument("profileIndex") { type = NavType.IntType }
                )
            ) { entry ->
                ProfileImportExportScreen(
                    lensId = Routes.parseRouteArg(entry.arguments?.getString("lensId")),
                    profileIndex = entry.arguments?.getInt("profileIndex") ?: 1,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Routes.PROFILE_OTHER_SETTINGS,
                arguments = listOf(
                    navArgument("lensId") { type = NavType.StringType },
                    navArgument("profileIndex") { type = NavType.IntType }
                )
            ) { entry ->
                ProfileOtherSettingsScreen(
                    lensId = Routes.parseRouteArg(entry.arguments?.getString("lensId")),
                    profileIndex = entry.arguments?.getInt("profileIndex") ?: 1,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Routes.PROFILE_MULTI_FRAME,
                arguments = listOf(
                    navArgument("lensId") { type = NavType.StringType },
                    navArgument("profileIndex") { type = NavType.IntType }
                )
            ) { entry ->
                ProfileMultiFrameSettingsScreen(
                    lensId = Routes.parseRouteArg(entry.arguments?.getString("lensId")),
                    profileIndex = entry.arguments?.getInt("profileIndex") ?: 1,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Routes.PROFILE_JPEG,
                arguments = listOf(
                    navArgument("lensId") { type = NavType.StringType },
                    navArgument("profileIndex") { type = NavType.IntType }
                )
            ) { entry ->
                JpegTuningScreen(
                    lensId = entry.arguments?.getString("lensId") ?: "0",
                    profileIndex = entry.arguments?.getInt("profileIndex") ?: 1,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
