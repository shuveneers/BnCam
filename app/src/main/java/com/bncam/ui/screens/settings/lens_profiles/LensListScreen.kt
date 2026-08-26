package com.bncam.ui.screens.settings.lens_profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bncam.core.engine.LensInfo
import com.bncam.data.settings.SettingsRepository
import kotlinx.coroutines.launch

val AccentPistachio = Color(0xFFB2D3A8)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LensListScreen(
    allLenses: List<LensInfo>,
    visibleLensIds: Set<String>, // Deze laten we staan voor backwards compatibility in de parameters
    onToggleVisibility: (String, Boolean) -> Unit, // Idem
    onInjectVendorTag: (String, String, String, String) -> Unit, // Idem
    onNavigateToVendorTags: () -> Unit, // NIEUW: Link naar de beheerpagina
    onNavigateToLensDetail: (String) -> Unit,
    onDiscoverManualLenses: suspend () -> List<LensInfo>,
    onResolveManualLensId: suspend (String) -> LensInfo?,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }
    val coroutineScope = rememberCoroutineScope()

    // --- DIALOOG STATES ---
    var showLensPickerForSlot by remember { mutableStateOf<String?>(null) }
    var showNamingForSlot by remember { mutableStateOf<String?>(null) }
    var discoveredManualLenses by remember { mutableStateOf<List<LensInfo>>(emptyList()) }
    var manualLensIdInput by remember { mutableStateOf("") }
    var manualLensValidation by remember { mutableStateOf<String?>(null) }
    var manualDiscoveryRunning by remember { mutableStateOf(false) }
    var manualResolveRunning by remember { mutableStateOf(false) }

    val pickerLenses = remember(allLenses, discoveredManualLenses) {
        (allLenses + discoveredManualLenses)
            .distinctBy { it.id }
            .sortedWith(
                compareBy<LensInfo> { it.id.toIntOrNull() ?: Int.MAX_VALUE }
                    .thenBy { it.id }
            )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding()
        ) {
            // ==========================================
            // TOP BAR (Grote 'Viewfinder' Stijl)
            // ==========================================
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier
                    .padding(16.dp)
                    .clickable { onNavigateBack() }
            )

            Text(
                text = "Lens Configuration",
                color = Color.White,
                fontWeight = FontWeight.Light,
                fontSize = 48.sp,
                letterSpacing = (-1).sp,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp)
            )

            // ==========================================
            // SCROLLBARE INHOUD (DE 3 KAARTEN)
            // ==========================================
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {

                // KAART 1: PRIMARY LENSES
                SlotCardSection(
                    title = "Primary Lenses",
                    description = "The most important cameras. Link the physical lenses to the viewfinder here.",
                    slots = SettingsRepository.PRIMARY_SLOTS,
                    settingsRepo = settingsRepo,
                    allLenses = allLenses,
                    onOpenPicker = { showLensPickerForSlot = it },
                    onOpenNaming = { showNamingForSlot = it },
                    onRowClick = { lensId -> onNavigateToLensDetail(lensId) }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // KAART 2: EXTRA LENSES
                SlotCardSection(
                    title = "Extra Slots",
                    description = "Optional extra slots for specific sensor routes or hidden cameras.",
                    slots = SettingsRepository.EXTRA_SLOTS,
                    settingsRepo = settingsRepo,
                    allLenses = allLenses,
                    onOpenPicker = { showLensPickerForSlot = it },
                    onOpenNaming = { showNamingForSlot = it },
                    onRowClick = { lensId -> onNavigateToLensDetail(lensId) }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // KAART 3: VENDOR TAGS
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF1E1E1E))
                        .padding(vertical = 16.dp)
                ) {
                    Text(text = "Vendor Tags & OpModes", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                    Text(text = "Manage hardware injections and custom OpModes for your assigned lenses.", color = Color.Gray, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp).padding(bottom = 8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToVendorTags() }
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Configured Vendor Tags", color = Color.White, fontSize = 16.sp)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(text = "See and configure the list of all assigned Vendor Tags.", color = Color.Gray, fontSize = 12.sp)
                        }
                        Text(text = "Configure", color = AccentPistachio, fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp))
                    }
                }

                Spacer(modifier = Modifier.height(48.dp))
            }
        }

        // ==========================================
        // DIALOOG 1: LENS ID PICKER
        // ==========================================
        if (showLensPickerForSlot != null) {
            val slotName = showLensPickerForSlot!!
            AlertDialog(
                onDismissRequest = { showLensPickerForSlot = null },
                containerColor = Color(0xFF1E1E1E),
                titleContentColor = Color.White,
                title = { Text("Assign Lens to $slotName") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Optie om leeg te maken
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    coroutineScope.launch { settingsRepo.setSlotLensId(slotName, null) }
                                    showLensPickerForSlot = null
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "None (Unassigned)", color = Color.Red.copy(alpha = 0.8f), fontSize = 16.sp)
                        }
                        Divider(color = Color.DarkGray)

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Manual Lens ID",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Enter any Camera2 Lens ID. BnCam validates it against the HAL before assigning it.",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = manualLensIdInput,
                            onValueChange = {
                                manualLensIdInput = it
                                manualLensValidation = null
                            },
                            label = { Text("Lens ID") },
                            singleLine = true,
                            enabled = !manualResolveRunning,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = AccentPistachio,
                                unfocusedBorderColor = Color.DarkGray,
                                focusedLabelColor = AccentPistachio,
                                cursorColor = AccentPistachio
                            )
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                enabled = manualLensIdInput.isNotBlank() && !manualResolveRunning,
                                onClick = {
                                    val requestedId = manualLensIdInput.trim()
                                    coroutineScope.launch {
                                        manualResolveRunning = true
                                        manualLensValidation = null
                                        val resolved = runCatching { onResolveManualLensId(requestedId) }.getOrNull()
                                        manualResolveRunning = false
                                        if (resolved != null) {
                                            settingsRepo.setSlotLensId(slotName, resolved.id)
                                            discoveredManualLenses = (discoveredManualLenses + resolved).distinctBy { it.id }
                                            manualLensIdInput = ""
                                            showLensPickerForSlot = null
                                        } else {
                                            manualLensValidation = "Lens ID '$requestedId' is not exposed by Camera2 on this device."
                                        }
                                    }
                                }
                            ) {
                                Text(if (manualResolveRunning) "Validating..." else "Validate & assign", color = AccentPistachio)
                            }
                            TextButton(
                                enabled = !manualDiscoveryRunning,
                                onClick = {
                                    coroutineScope.launch {
                                        manualDiscoveryRunning = true
                                        manualLensValidation = null
                                        val discovered = runCatching { onDiscoverManualLenses() }.getOrElse { emptyList() }
                                        manualDiscoveryRunning = false
                                        discoveredManualLenses = discovered
                                        manualLensValidation = if (discovered.isEmpty()) {
                                            "No additional hidden Lens IDs were exposed by Camera2."
                                        } else {
                                            "Found ${discovered.size} Camera2 Lens ID(s)."
                                        }
                                    }
                                }
                            ) {
                                Text(if (manualDiscoveryRunning) "Scanning..." else "Scan hidden IDs", color = Color.White)
                            }
                        }
                        if (manualDiscoveryRunning || manualResolveRunning) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                color = AccentPistachio,
                                trackColor = Color.DarkGray
                            )
                        }
                        manualLensValidation?.let { message ->
                            Text(
                                text = message,
                                color = Color.Gray,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }

                        Divider(color = Color.DarkGray)
                        Spacer(modifier = Modifier.height(4.dp))

                        // Camera2-exposed, physical, probed and manually discovered lenses.
                        pickerLenses.forEach { lens ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        coroutineScope.launch { settingsRepo.setSlotLensId(slotName, lens.id) }
                                        showLensPickerForSlot = null
                                    }
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(text = lens.listTitle, color = Color.White, fontSize = 16.sp)
                                    Text(text = lens.listDescription, color = Color.Gray, fontSize = 12.sp)
                                }
                                Text(text = "ID: ${lens.id}", color = AccentPistachio, fontSize = 14.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLensPickerForSlot = null }) {
                        Text("Cancel", color = Color.Gray)
                    }
                }
            )
        }

        // ==========================================
        // DIALOOG 2: CUSTOM NAMING
        // ==========================================
        if (showNamingForSlot != null) {
            val slotName = showNamingForSlot!!
            // Haal de huidige custom naam op (of de default)
            val currentCustomName by settingsRepo.getSlotCustomNameFlow(slotName).collectAsState(initial = slotName)
            var inputName by remember { mutableStateOf(currentCustomName) }

            AlertDialog(
                onDismissRequest = { showNamingForSlot = null },
                containerColor = Color(0xFF1E1E1E),
                titleContentColor = Color.White,
                title = { Text("Rename $slotName") },
                text = {
                    OutlinedTextField(
                        value = inputName,
                        onValueChange = { inputName = it },
                        label = { Text("Display Name", color = Color.Gray) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = AccentPistachio, unfocusedBorderColor = Color.DarkGray,
                            focusedLabelColor = AccentPistachio, cursorColor = AccentPistachio
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (inputName.isNotBlank()) {
                            coroutineScope.launch { settingsRepo.setSlotCustomName(slotName, inputName.trim()) }
                            showNamingForSlot = null
                        }
                    }) {
                        Text("Save", color = AccentPistachio, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNamingForSlot = null }) {
                        Text("Cancel", color = Color.Gray)
                    }
                }
            )
        }
    }
}

// ==========================================
// HERBRUIKBARE COMPONENT: SLOT CARD
// ==========================================
@Composable
fun SlotCardSection(
    title: String,
    description: String,
    slots: List<String>,
    settingsRepo: SettingsRepository,
    allLenses: List<LensInfo>,
    onOpenPicker: (String) -> Unit,
    onOpenNaming: (String) -> Unit,
    onRowClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF1E1E1E))
            .padding(vertical = 16.dp)
    ) {
        Text(text = title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Text(text = description, color = Color.Gray, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp).padding(bottom = 8.dp))

        slots.forEach { slotName ->
            val assignedLensId by settingsRepo.getSlotLensIdFlow(slotName).collectAsState(initial = null)
            val customName by settingsRepo.getSlotCustomNameFlow(slotName).collectAsState(initial = slotName)

            val assignedLens = allLenses.find { it.id == assignedLensId }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Alleen klikbaar als er een lens is toegewezen
                    .then(if (assignedLensId != null) Modifier.clickable { onRowClick(assignedLensId!!) } else Modifier)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Linker gedeelte: Naam en ID
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = customName,
                        color = if (assignedLensId != null) Color.White else Color.Gray.copy(alpha = 0.5f),
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = assignedLens?.listTitle ?: "Unassigned",
                        color = if (assignedLensId != null) AccentPistachio else Color.DarkGray,
                        fontSize = 12.sp
                    )
                }

                // Rechter gedeelte: De actie knoppen
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Lens ID Picker Knop
                    Surface(
                        color = Color.DarkGray.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable { onOpenPicker(slotName) }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Icon(Icons.Default.List, contentDescription = "ID", tint = Color.White, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Lens ID", color = Color.White, fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Naming Knop
                    Surface(
                        color = Color.DarkGray.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable { onOpenNaming(slotName) }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = "Name", tint = Color.White, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Naming", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}