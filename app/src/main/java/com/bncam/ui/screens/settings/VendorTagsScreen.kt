package com.bncam.ui.screens.settings

import android.hardware.camera2.CameraManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bncam.data.settings.SettingsRepository
import com.bncam.data.settings.VendorTagConfig
import com.bncam.data.settings.VendorTagSource
import com.bncam.data.settings.VendorTagTarget
import com.bncam.data.settings.VendorValueOrigin
import com.bncam.ui.screens.capture.AccentPistachio
import com.bncam.vendor.DynamicVendorTag
import com.bncam.vendor.VendorScanner
import kotlinx.coroutines.launch
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun getBnCamTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    cursorColor = AccentPistachio,
    focusedBorderColor = AccentPistachio,
    focusedLabelColor = AccentPistachio,
    unfocusedLabelColor = Color.Gray
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VendorTagsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }
    val cameraManager = remember {
        context.getSystemService(android.content.Context.CAMERA_SERVICE) as CameraManager
    }
    val coroutineScope = rememberCoroutineScope()

    val configuredTags by settingsRepo.vendorTagsFlow.collectAsState(initial = emptyList())
    val activeSlots by settingsRepo.activeSlotsFlow.collectAsState(initial = emptyList())

    var topTabIndex by remember { mutableIntStateOf(0) }
    var selectedLensIndex by remember { mutableIntStateOf(0) }
    var scanRefreshKey by remember { mutableIntStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }
    var dialogSeed by remember { mutableStateOf<ManualTagSeed?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showConfiguredOnly by remember { mutableStateOf(false) }

    LaunchedEffect(activeSlots.size) {
        if (selectedLensIndex >= activeSlots.size) {
            selectedLensIndex = 0
        }
    }

    val activeLensId = activeSlots.getOrNull(selectedLensIndex)?.first.orEmpty()
    val activeLensLabel = activeSlots.getOrNull(selectedLensIndex)?.second.orEmpty()

    val allLensTags by produceState<List<DynamicVendorTag>>(
        initialValue = emptyList(),
        activeLensId,
        scanRefreshKey
    ) {
        value = emptyList()

        if (activeLensId.isBlank()) {
            return@produceState
        }

        value = withContext(Dispatchers.IO) {
            VendorScanner.scanSensor(
                context = context,
                cameraManager = cameraManager,
                lensId = activeLensId,
                forceRefresh = scanRefreshKey > 0
            )
        }
    }

    val configuredForLens = remember(configuredTags, activeLensId) {
        configuredTags.filter { it.lensId == activeLensId }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(16.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.clickable { onNavigateBack() }
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Vendor Engine",
                        color = Color.White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Light
                    )
                    Text(
                        if (activeLensId.isBlank()) "No active lens slot" else "$activeLensLabel • lens $activeLensId",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            }

            TabRow(
                selectedTabIndex = topTabIndex,
                containerColor = Color.Black,
                contentColor = AccentPistachio,
                indicator = { tabPositions ->
                    SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[topTabIndex]),
                        color = AccentPistachio
                    )
                }
            ) {
                Tab(
                    selected = topTabIndex == 0,
                    onClick = { topTabIndex = 0 },
                    text = { Text("FEATURES") }
                )
                Tab(
                    selected = topTabIndex == 1,
                    onClick = { topTabIndex = 1 },
                    text = { Text("TAGS") }
                )
            }

            if (activeSlots.isEmpty()) {
                EmptyStateCard(
                    title = "No active lens slots",
                    message = "Assign active lens slots first. Vendor tags are stored per lens ID."
                )
            } else {
                LensTabs(
                    activeSlots = activeSlots,
                    selectedLensIndex = selectedLensIndex,
                    onSelect = { selectedLensIndex = it }
                )

                when (topTabIndex) {
                    0 -> FeatureDashboard(
                        lensId = activeLensId,
                        allLensTags = allLensTags,
                        configuredTags = configuredForLens,
                        onEnableFeature = { feature, matchedTags ->
                            coroutineScope.launch {
                                val recipeTags = matchedTags.map { discovered ->
                                    val target = inferTargetForKey(discovered.name, discovered)
                                    VendorTagConfig(
                                        slotName = feature.displayName,
                                        lensId = activeLensId,
                                        keyName = discovered.name,
                                        value = valueToRawString(valueForFeatureTag(feature, discovered)),
                                        type = valueToVendorType(valueForFeatureTag(feature, discovered), discovered.typeName),
                                        enabled = true,
                                        target = target,
                                        source = VendorTagSource.RECIPE,
                                        recipeId = feature.id,
                                        valueOrigin = VendorValueOrigin.RECIPE_DEFAULT,
                                        requiresSessionRebuild = target == VendorTagTarget.SESSION || looksLikeFeatureThatMayNeedOperationModeProbe(discovered.name),
                                        notes = "Enabled from ${feature.displayName}. If a vendor operation mode must be discovered, BnCam performs the one-time probe after this explicit enable action and learns the working mapping per lens."
                                    )
                                }

                                settingsRepo.replaceVendorRecipeTags(
                                    lensId = activeLensId,
                                    recipeId = feature.id,
                                    tags = recipeTags
                                )
                                if (recipeTags.any { it.requiresSessionRebuild }) {
                                    settingsRepo.setVendorOperationModeProbeActive(activeLensId, true)
                                }
                            }
                        },

                        onDisableFeature = { feature ->
                            coroutineScope.launch {
                                settingsRepo.setVendorRecipeEnabled(
                                    lensId = activeLensId,
                                    recipeId = feature.id,
                                    enabled = false
                                )
                                settingsRepo.setVendorOperationModeProbeActive(activeLensId, false)
                            }
                        }
                    )

                    1 -> RawTagConsole(
                        lensId = activeLensId,
                        allLensTags = allLensTags,
                        configuredTags = configuredForLens,
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        showConfiguredOnly = showConfiguredOnly,
                        onConfiguredOnlyChange = { showConfiguredOnly = it },
                        onRefreshScan = {
                            VendorScanner.clearCache()
                            scanRefreshKey++
                        },
                        onAddManual = {
                            dialogSeed = ManualTagSeed(
                                keyName = "",
                                type = "Int",
                                value = "1",
                                target = VendorTagTarget.REPEATING_REQUEST,
                                source = VendorTagSource.MANUAL,
                                slotName = "Manual"
                            )
                            showAddDialog = true
                        },
                        onActivateScannedTag = { scanned ->
                            dialogSeed = ManualTagSeed(
                                keyName = scanned.name,
                                type = scanned.typeName.takeUnless { it == "Unknown" }.orEmpty()
                                    .ifBlank { "Int" },
                                value = scanned.defaultValue.ifBlank { defaultValueForType(scanned.typeName) },
                                target = inferTargetForScannedTag(scanned),
                                source = scanned.source,
                                slotName = scanned.name.substringBeforeLast(".", "Manual")
                            )
                            showAddDialog = true
                        },
                        onToggleConfig = { config, enabled ->
                            coroutineScope.launch {
                                settingsRepo.setVendorTagEnabled(
                                    lensId = config.lensId,
                                    keyName = config.keyName,
                                    target = config.target,
                                    enabled = enabled,
                                    recipeId = config.recipeId
                                )
                            }
                        },
                        onDeleteConfig = { config ->
                            coroutineScope.launch {
                                settingsRepo.removeVendorTag(
                                    lensId = config.lensId,
                                    keyName = config.keyName,
                                    target = config.target,
                                    recipeId = config.recipeId
                                )
                            }
                        }
                    )
                }
            }
        }

        if (topTabIndex == 1 && activeLensId.isNotBlank()) {
            FloatingActionButton(
                onClick = {
                    dialogSeed = ManualTagSeed(
                        keyName = "",
                        type = "Int",
                        value = "1",
                        target = VendorTagTarget.REPEATING_REQUEST,
                        source = VendorTagSource.MANUAL,
                        slotName = "Manual"
                    )
                    showAddDialog = true
                },
                containerColor = AccentPistachio,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
                    .navigationBarsPadding()
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add vendor tag")
            }
        }

        if (showAddDialog && activeLensId.isNotBlank()) {
            VendorTagEditDialog(
                lensId = activeLensId,
                seed = dialogSeed ?: ManualTagSeed(
                    keyName = "",
                    type = "Int",
                    value = "1",
                    target = VendorTagTarget.REPEATING_REQUEST,
                    source = VendorTagSource.MANUAL,
                    slotName = "Manual"
                ),
                onDismiss = { showAddDialog = false },
                onSave = { config ->
                    coroutineScope.launch {
                        settingsRepo.addVendorTag(config)
                        showAddDialog = false
                    }
                }
            )
        }
    }
}

@Composable
private fun LensTabs(
    activeSlots: List<Pair<String, String>>,
    selectedLensIndex: Int,
    onSelect: (Int) -> Unit
) {
    ScrollableTabRow(
        selectedTabIndex = selectedLensIndex,
        containerColor = Color.Black,
        contentColor = AccentPistachio,
        edgePadding = 8.dp,
        indicator = { tabPositions ->
            SecondaryIndicator(
                Modifier.tabIndicatorOffset(tabPositions[selectedLensIndex]),
                color = AccentPistachio
            )
        }
    ) {
        activeSlots.forEachIndexed { index, slot ->
            Tab(
                selected = selectedLensIndex == index,
                onClick = { onSelect(index) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(slot.second)
                        Text("ID ${slot.first}", fontSize = 10.sp, color = Color.Gray)
                    }
                }
            )
        }
    }
}

@Composable
private fun FeatureDashboard(
    lensId: String,
    allLensTags: List<DynamicVendorTag>,
    configuredTags: List<VendorTagConfig>,
    onEnableFeature: (com.bncam.vendor.CameraFeature, List<DynamicVendorTag>) -> Unit,
    onDisableFeature: (com.bncam.vendor.CameraFeature) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(com.bncam.vendor.FeatureRegistry.ALL_FEATURES, key = { it.id }) { feature ->

            // FIX VOOR LAG: Door 'remember' scant hij de tags nu maar 1x bij het inladen!
            val matchedTags = androidx.compose.runtime.remember(allLensTags, feature) {
                allLensTags.filter { tag ->
                    feature.searchPatterns.any { pattern -> pattern.containsMatchIn(tag.name) }
                }
            }

            val selectedRecipeTags = androidx.compose.runtime.remember(matchedTags, feature.id) {
                selectRecipeTagsForFeature(feature.id, matchedTags)
            }

            var isExpanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            val recipeStored = configuredTags.any { it.recipeId == feature.id }
            val hasMatches = selectedRecipeTags.isNotEmpty()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF171717))
                    .clickable(enabled = hasMatches) { isExpanded = !isExpanded }
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // FIX VOOR LAG: Bolletje is nu statisch AccentPistachio of DarkGray
                    val statusColor = if (hasMatches) AccentPistachio else Color.DarkGray
                    Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(statusColor))

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(feature.displayName, color = Color.White, fontWeight = FontWeight.Bold)
                        Text(feature.description, color = Color.Gray, fontSize = 12.sp)

                        if (!hasMatches) {
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("No matching tags found on this sensor.", color = Color(0xFFFF7676), fontSize = 12.sp)
                        } else if (!isExpanded) {
                            Text(
                                "Tap to view ${selectedRecipeTags.size} injectable recipe tags" +
                                        if (matchedTags.size != selectedRecipeTags.size) " (${matchedTags.size - selectedRecipeTags.size} read-only/noisy matches ignored)" else "",
                                color = AccentPistachio.copy(alpha = 0.7f),
                                fontSize = 11.sp
                            )
                        }
                    }

                    Switch(
                        checked = recipeStored,
                        enabled = hasMatches,
                        onCheckedChange = { enabled ->
                            if (enabled) onEnableFeature(feature, selectedRecipeTags) else onDisableFeature(feature)
                        }
                    )
                }

                if (isExpanded && hasMatches) {
                    Spacer(modifier = Modifier.size(12.dp))
                    androidx.compose.material3.Divider(color = Color.DarkGray)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Recipe will inject:", color = AccentPistachio, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    selectedRecipeTags.forEach { tag ->
                        Text("• ${tag.name}", color = Color.LightGray, fontSize = 11.sp)
                    }

                    val ignoredCount = matchedTags.size - selectedRecipeTags.size
                    if (ignoredCount > 0) {
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Ignored noisy/read-only matches:", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        matchedTags
                            .filterNot { selected -> selectedRecipeTags.any { it.name == selected.name } }
                            .take(12)
                            .forEach { tag ->
                                Text("• ${tag.name}", color = Color.DarkGray, fontSize = 10.sp)
                            }
                    }
                }
            }
        }
    }
}

@Composable
private fun RawTagConsole(
    lensId: String,
    allLensTags: List<DynamicVendorTag>,
    configuredTags: List<VendorTagConfig>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    showConfiguredOnly: Boolean,
    onConfiguredOnlyChange: (Boolean) -> Unit,
    onRefreshScan: () -> Unit,
    onAddManual: () -> Unit,
    onActivateScannedTag: (DynamicVendorTag) -> Unit,
    onToggleConfig: (VendorTagConfig, Boolean) -> Unit,
    onDeleteConfig: (VendorTagConfig) -> Unit
) {
    // FIX VOOR DE LAG: Haal de status 1x statisch op in plaats van 30x per seconde!
    val echoStatuses by com.bncam.vendor.VendorInjectionEngine.echoStatuses.collectAsState(initial = emptyMap())

    val configuredByName = remember(configuredTags) {
        configuredTags.groupBy { it.keyName }
    }

    val filteredTags = remember(allLensTags, configuredByName, searchQuery, showConfiguredOnly) {
        allLensTags.filter { tag ->
            val matchesSearch = searchQuery.isBlank() ||
                    tag.name.contains(searchQuery, ignoreCase = true)

            val matchesConfigured = !showConfiguredOnly ||
                    configuredByName.containsKey(tag.name)

            matchesSearch && matchesConfigured
        }
    }

    val groupedTags = remember(filteredTags) {
        VendorScanner.groupTagsAsRecipes(filteredTags)
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF151515))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Lens $lensId", color = Color.White, fontWeight = FontWeight.Bold)
                Text(
                    "Scanned tags: ${allLensTags.size} • Configured: ${configuredTags.size}",
                    color = Color.Gray,
                    fontSize = 12.sp
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    label = { Text("Search vendor key") },
                    singleLine = true,
                    colors = getBnCamTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { onConfiguredOnlyChange(!showConfiguredOnly) },
                        label = { Text(if (showConfiguredOnly) "Configured only: ON" else "Configured only: OFF") }
                    )
                    AssistChip(
                        onClick = onRefreshScan,
                        label = { Text("Refresh scan") }
                    )
                    AssistChip(
                        onClick = onAddManual,
                        label = { Text("Manual add") }
                    )
                }
            }
        }

        if (configuredTags.isNotEmpty()) {
            item {
                Text(
                    "Configured tags",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }

            items(configuredTags) { config ->
                ConfiguredTagCard(
                    config = config,
                    echoStatus = echoStatuses[config.keyName], // Geef de status door
                    onToggle = { onToggleConfig(config, it) },
                    onDelete = { onDeleteConfig(config) }
                )
            }
        }

        item {
            Text(
                "Scanned vendor keys",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }

        groupedTags.forEach { (groupName, tagsInGroup) ->
            item(key = groupName) {
                ExpandableVendorGroup(
                    groupName = groupName,
                    tags = tagsInGroup,
                    configuredByName = configuredByName,
                    onActivateScannedTag = onActivateScannedTag
                )
            }
        }
    }
}

@Composable
private fun ConfiguredTagCard(
    config: VendorTagConfig,
    echoStatus: Boolean?, // NIEUW: Ontvang de status
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1E1E1E))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(config.keyName.substringAfterLast("."), color = AccentPistachio, fontWeight = FontWeight.Bold)
                Text(config.keyName, color = Color.Gray, fontSize = 11.sp)
            }

            Switch(checked = config.enabled, onCheckedChange = onToggle)

            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF7676))
            }
        }

        Spacer(modifier = Modifier.size(8.dp))

        Text("Target: ${config.target}", color = Color.White, fontSize = 12.sp)
        Text("Type: ${config.type} • Value: ${config.value}", color = Color.White, fontSize = 12.sp)
        Text("Source: ${config.source} • Recipe: ${config.recipeId.ifBlank { "none" }}", color = Color.Gray, fontSize = 11.sp)

        if (config.requiresSessionRebuild) {
            Text(
                "Session rebuild required; CameraScreen applies this automatically before testing/capture.",
                color = Color(0xFFFFC857),
                fontSize = 11.sp
            )
        }

        Spacer(modifier = Modifier.size(8.dp))

        // NIEUW: De Diagnose Weergave (Echo Tracker)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val echoColor = when(echoStatus) {
                true -> AccentPistachio
                false -> Color(0xFFFFC857) // Geel
                null -> Color.Gray
            }
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(echoColor))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = when(echoStatus) {
                    true -> "Hardware echo confirmed"
                    false -> "Not echoed in result; not proof of rejection"
                    null -> "Waiting for stream..."
                },
                color = echoColor,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ExpandableVendorGroup(
    groupName: String,
    tags: List<DynamicVendorTag>,
    configuredByName: Map<String, List<VendorTagConfig>>,
    onActivateScannedTag: (DynamicVendorTag) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF121212))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(groupName, color = Color.White, fontWeight = FontWeight.Bold)
                Text("${tags.size} keys", color = Color.Gray, fontSize = 11.sp)
            }

            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = Color.Gray
            )
        }

        if (expanded) {
            tags.forEach { tag ->
                val configured = configuredByName[tag.name].orEmpty()
                VendorTagRow(
                    tag = tag,
                    configured = configured,
                    onActivate = { onActivateScannedTag(tag) }
                )
            }
        }
    }
}

@Composable
private fun VendorTagRow(
    tag: DynamicVendorTag,
    configured: List<VendorTagConfig>,
    onActivate: () -> Unit
) {
    val isEnabled = configured.any { it.enabled }
    val statusColor = when {
        isEnabled -> AccentPistachio
        tag.injectable -> Color.Gray
        else -> Color(0xFFFF7676)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = tag.injectable) { onActivate() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(statusColor)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                tag.name.substringAfterLast("."),
                color = if (tag.injectable) Color.White else Color.Gray,
                fontSize = 13.sp,
                fontWeight = if (isEnabled) FontWeight.Bold else FontWeight.Normal
            )
            Text(tag.name, color = Color.Gray, fontSize = 10.sp)
            Text(
                "target=${tag.target} • type=${tag.typeName} • source=${tag.source} • confidence=${tag.confidence}",
                color = Color.DarkGray,
                fontSize = 10.sp
            )
        }

        Text(
            when {
                isEnabled -> "ON"
                tag.injectable -> "ADD"
                else -> "READ"
            },
            color = statusColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private data class ManualTagSeed(
    val keyName: String,
    val type: String,
    val value: String,
    val target: VendorTagTarget,
    val source: VendorTagSource,
    val slotName: String
)

@Composable
private fun VendorTagEditDialog(
    lensId: String,
    seed: ManualTagSeed,
    onDismiss: () -> Unit,
    onSave: (VendorTagConfig) -> Unit
) {
    var keyName by remember(seed) { mutableStateOf(seed.keyName) }
    var value by remember(seed) { mutableStateOf(seed.value) }
    var type by remember(seed) { mutableStateOf(seed.type.ifBlank { "Int" }) }
    var target by remember(seed) { mutableStateOf(seed.target) }
    var slotName by remember(seed) { mutableStateOf(seed.slotName.ifBlank { "Manual" }) }

    var typeMenuOpen by remember { mutableStateOf(false) }
    var targetMenuOpen by remember { mutableStateOf(false) }

    val typeOptions = listOf(
        "Byte",
        "Short",
        "Int",
        "Long",
        "Float",
        "Double",
        "Boolean",
        "String",
        "ByteArray",
        "ShortArray",
        "IntArray",
        "LongArray",
        "FloatArray",
        "DoubleArray",
        "BooleanArray"
    )

    val targetOptions = listOf(
        VendorTagTarget.SESSION,
        VendorTagTarget.REPEATING_REQUEST,
        VendorTagTarget.STILL_CAPTURE,
        VendorTagTarget.REQUEST_BOTH
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF151515),
        title = {
            Text("Vendor tag", color = Color.White)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = slotName,
                    onValueChange = { slotName = it },
                    label = { Text("Slot / group name") },
                    singleLine = true,
                    colors = getBnCamTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = keyName,
                    onValueChange = { keyName = it },
                    label = { Text("Full key name") },
                    singleLine = false,
                    colors = getBnCamTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                Box {
                    OutlinedTextField(
                        value = type,
                        onValueChange = { type = it },
                        label = { Text("Type") },
                        singleLine = true,
                        colors = getBnCamTextFieldColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { typeMenuOpen = true }
                    )

                    DropdownMenu(
                        expanded = typeMenuOpen,
                        onDismissRequest = { typeMenuOpen = false }
                    ) {
                        typeOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    type = option
                                    if (value.isBlank()) value = defaultValueForType(option)
                                    typeMenuOpen = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Value") },
                    singleLine = true,
                    colors = getBnCamTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                Box {
                    OutlinedTextField(
                        value = target.name,
                        onValueChange = {},
                        label = { Text("Target") },
                        readOnly = true,
                        colors = getBnCamTextFieldColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { targetMenuOpen = true }
                    )

                    DropdownMenu(
                        expanded = targetMenuOpen,
                        onDismissRequest = { targetMenuOpen = false }
                    ) {
                        targetOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.name) },
                                onClick = {
                                    target = option
                                    targetMenuOpen = false
                                }
                            )
                        }
                    }
                }

                Text(
                    "DCG / sensor-mode / SESSION-like tags are stored as SESSION and the camera session will be rebuilt automatically.",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }
        },
        confirmButton = {
            Button(
                enabled = keyName.isNotBlank() && type.isNotBlank(),
                onClick = {
                    onSave(
                        VendorTagConfig(
                            slotName = slotName.ifBlank { "Manual" },
                            lensId = lensId,
                            keyName = keyName.trim(),
                            value = value.trim(),
                            type = type.trim(),
                            enabled = true,
                            target = target,
                            source = seed.source,
                            recipeId = "",
                            valueOrigin = VendorValueOrigin.MANUAL,
                            requiresSessionRebuild = target == VendorTagTarget.SESSION || looksLikeSessionRebuildKey(keyName),
                            notes = "Added from VendorTagsScreen. Session rebuild will be forced automatically when required."
                        )
                    )
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun EmptyStateCard(title: String, message: String) {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF151515))
            .padding(18.dp)
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.size(8.dp))
        Text(message, color = Color.Gray, fontSize = 13.sp)
    }
}

private fun looksLikeReadOnlyOrCapabilityKey(keyName: String): Boolean {
    val key = keyName.lowercase()
    return key.contains(".capabilities.") ||
            key.contains("capabilities.") ||
            key.contains("supported") ||
            key.contains("properties_sensor") ||
            key.contains("sensor_meta_data") ||
            key.contains("sensormodetable") ||
            key.contains("sensormodes") ||
            key.contains("customsensormode") ||
            key.endsWith(".info") ||
            key.contains(".info.")
}

private fun selectRecipeTagsForFeature(
    featureId: String,
    matchedTags: List<DynamicVendorTag>
): List<DynamicVendorTag> {
    fun hasAny(name: String, vararg needles: String): Boolean {
        val key = name.lowercase()
        return needles.any { key.contains(it.lowercase()) }
    }

    val filtered = when (featureId) {
        "dcg_active" -> matchedTags.filter { tag ->
            val key = tag.name
            !looksLikeReadOnlyOrCapabilityKey(key) &&
                    hasAny(
                        key,
                        "EnableHDRDCGMode",
                        "sessionParameters.DCGMode",
                        "stats_control.DCGMode",
                        "ReprocessableSessionModeTag",
                        "sensorHdrMode"
                    )
        }

        "pro_mode_master" -> matchedTags.filter { tag ->
            val key = tag.name
            !looksLikeReadOnlyOrCapabilityKey(key) &&
                    hasAny(
                        key,
                        "ReprocessableSessionModeTag",
                        "sessionoperationmode",
                        "operationmode",
                        "professionalMode",
                        "professionalFocusMode",
                        "profFocusAssistFlashMode",
                        "imagePostProcessMode",
                        "postProcessForegroundReturnMode"
                    )
        }

        "sensor_direct_master" -> matchedTags.filter { tag ->
            val key = tag.name
            !looksLikeReadOnlyOrCapabilityKey(key) &&
                    hasAny(
                        key,
                        "SensorCurrentMode",
                        "SensorModeSwitched",
                        "sensorMode",
                        "sensorModeType",
                        "mode_index",
                        "current_mode",
                        "sensor_mode_index"
                    )
        }

        else -> matchedTags.filter { tag ->
            !looksLikeReadOnlyOrCapabilityKey(tag.name) &&
                    (tag.isRequestKey || tag.isSessionKey || tag.target == VendorTagTarget.REPEATING_REQUEST || tag.target == VendorTagTarget.SESSION)
        }
    }

    return filtered.distinctBy { it.name }
}

private fun looksLikeSessionRebuildKey(keyName: String): Boolean {
    val key = keyName.lowercase()
    return key.contains("sessionparameters") ||
            key.contains("session.parameters") ||
            key.contains("session_parameters") ||
            key.contains("sessionoperation") ||
            key.contains("session.operation") ||
            key.contains("reprocessablesessionmodetag")
}

private fun looksLikeFeatureThatMayNeedOperationModeProbe(keyName: String): Boolean {
    val key = keyName.lowercase()
    return key.contains("dcg") ||
            key.contains("dualconversion") ||
            key.contains("dual_conversion") ||
            key.contains("dual.gain") ||
            key.contains("dualgain") ||
            key.contains("idcg") ||
            key.contains("sensormode") ||
            key.contains("sensor.mode") ||
            key.contains("sensorcurrentmode") ||
            key.contains("customizedsensormode") ||
            key.contains("isz") ||
            key.contains("insensorzoom")
}

private fun inferTargetForScannedTag(tag: DynamicVendorTag): VendorTagTarget {
    val charOnly = tag.isCharacteristicKey && !tag.isRequestKey && !tag.isSessionKey
    val resultOnly = tag.isResultKey && !tag.isRequestKey && !tag.isSessionKey

    return when {
        charOnly -> VendorTagTarget.CHARACTERISTIC_ONLY
        resultOnly -> VendorTagTarget.RESULT_ONLY
        tag.isSessionKey || looksLikeSessionRebuildKey(tag.name) -> VendorTagTarget.SESSION
        tag.isRequestKey -> VendorTagTarget.REQUEST_BOTH
        else -> VendorTagTarget.UNKNOWN
    }
}

private fun inferTargetForKey(
    keyName: String,
    discovered: DynamicVendorTag?
): VendorTagTarget {
    if (discovered != null) {
        return inferTargetForScannedTag(discovered)
    }

    return if (looksLikeSessionRebuildKey(keyName)) {
        VendorTagTarget.SESSION
    } else {
        VendorTagTarget.REQUEST_BOTH
    }
}

private fun looksLikeSessionOperationModeKey(keyName: String): Boolean {
    val key = keyName.lowercase()
    return key.contains("reprocessablesessionmodetag") ||
            key.contains("sessionoperationmode") ||
            key.contains("session.operation.mode") ||
            key.contains("session_operation_mode") ||
            key.contains("operationmode") ||
            key.contains("operation.mode") ||
            key.contains("operation_mode")
}

private fun valueForFeatureTag(
    feature: com.bncam.vendor.CameraFeature,
    discovered: DynamicVendorTag
): Any {
    if (feature.id != "pro_mode_master") {
        return feature.targetValue
    }

    val key = discovered.name.lowercase()

    // 32772 is the Camera2 vendor session operation mode.
    // It belongs in SessionConfiguration(sessionType = 32772), and only the matching
    // session-operation tag is stored with 32772 so BnCameraManager can resolve it.
    if (looksLikeSessionOperationModeKey(discovered.name)) {
        return intArrayOf(0x8004)
    }

    // The debug already showed Honor pro-mode request tags echoing hardware=[4] after a bad
    // requested value of 32772. Keep request-level pro-mode tags at the real pro-mode enum.
    if (key.contains("professionalmode") ||
        key.contains("professional.mode") ||
        key.contains("profocus") ||
        key.contains("pro_mode")
    ) {
        return intArrayOf(4)
    }

    // Do not spray 32772 into ordinary metadata/capability keys.
    return intArrayOf(1)
}

private fun valueToRawString(value: Any): String {
    return when (value) {
        is ByteArray -> value.joinToString(",")
        is ShortArray -> value.joinToString(",")
        is IntArray -> value.joinToString(",")
        is LongArray -> value.joinToString(",")
        is FloatArray -> value.joinToString(",")
        is DoubleArray -> value.joinToString(",")
        is BooleanArray -> value.joinToString(",")
        else -> value.toString()
    }
}

private fun valueToVendorType(value: Any, fallback: String?): String {
    if (!fallback.isNullOrBlank() && fallback != "Unknown") return fallback

    return when (value) {
        is Byte -> "Byte"
        is Short -> "Short"
        is Int -> "Int"
        is Long -> "Long"
        is Float -> "Float"
        is Double -> "Double"
        is Boolean -> "Boolean"
        is String -> "String"
        is ByteArray -> "ByteArray"
        is ShortArray -> "ShortArray"
        is IntArray -> "IntArray"
        is LongArray -> "LongArray"
        is FloatArray -> "FloatArray"
        is DoubleArray -> "DoubleArray"
        is BooleanArray -> "BooleanArray"
        else -> "Int"
    }
}

private fun defaultValueForType(typeName: String): String {
    val normalized = typeName.lowercase()

    return when {
        normalized == "byte" -> "1"
        normalized == "short" -> "1"
        normalized == "int" || normalized == "integer" -> "1"
        normalized == "long" -> "1"
        normalized == "float" -> "1.0"
        normalized == "double" -> "1.0"
        normalized == "boolean" -> "true"
        normalized == "string" -> ""
        normalized.contains("bytearray") || normalized.contains("byte[]") -> "1"
        normalized.contains("shortarray") || normalized.contains("short[]") -> "1"
        normalized.contains("intarray") || normalized.contains("integer[]") || normalized.contains("int[]") -> "1"
        normalized.contains("longarray") || normalized.contains("long[]") -> "1"
        normalized.contains("floatarray") || normalized.contains("float[]") -> "1.0"
        normalized.contains("doublearray") || normalized.contains("double[]") -> "1.0"
        normalized.contains("booleanarray") || normalized.contains("boolean[]") -> "true"
        else -> "1"
    }
}