package com.bncam.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(
    activeProfileName: String,
    activeCaptureMode: String,
    onNavigateBack: () -> Unit,
    onNavigateToAppSettings: () -> Unit,
    onNavigateToViewfinderSettings: () -> Unit,
    onNavigateToLensSettings: () -> Unit,
    onNavigateToConfigSettings: () -> Unit,
    onNavigateToWatermarkSettings: () -> Unit,
    onNavigateToInfo: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(scrollState)
            .systemBarsPadding()
            .padding(bottom = 32.dp)
    ) {
        // ==========================================
        // HEADER: Windows Phone Stijl Titel
        // ==========================================
        Text(
            text = "Settings",
            color = Color.White,
            fontWeight = FontWeight.Light,
            fontSize = 56.sp, // Extreem groot en dun voor die Metro UI look
            letterSpacing = (-1).sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp)
        )

        // ==========================================
        // KAART 1: Current setup
        // ==========================================
        SettingsCard(
            title = "Current setup",
            description = "Quick status only. Detailed controls stay inside their own pages."
        ) {
            StatusRow(label = "Active profile", value = activeProfileName)
            StatusRow(label = "Capture mode", value = activeCaptureMode)
        }

        // ==========================================
        // KAART 2: Camera setup
        // ==========================================
        SettingsCard(
            title = "Camera setup",
            description = "App behavior, preview behavior, lens id behavior and developer lab."
        ) {
            SettingsSubsectionItem(
                title = "App Settings",
                description = "Capture defaults, save behavior, and safe app-level choices.",
                onClick = onNavigateToAppSettings
            )
            SettingsSubsectionItem(
                title = "Viewfinder Settings",
                description = "Preview aids and viewfinder-only settings. These do not affect image processing.",
                onClick = onNavigateToViewfinderSettings
            )
            SettingsSubsectionItem(
                title = "Lens Settings",
                description = "Visible lens ID's and per-lens configuration lab.",
                onClick = onNavigateToLensSettings
            )
        }

        // ==========================================
        // KAART 3: Library
        // ==========================================
        SettingsCard(
            title = "Library",
            description = "Config creation, import and watermark settings."
        ) {
            SettingsSubsectionItem(
                title = "Config settings",
                description = "Save a config or import a config.",
                onClick = onNavigateToConfigSettings
            )
            SettingsSubsectionItem(
                title = "Watermark",
                description = "Watermark options to get your signature on your photo's.",
                onClick = onNavigateToWatermarkSettings
            )
        }

        // ==========================================
        // KAART 4: About
        // ==========================================
        SettingsCard(
            title = "About",
            description = "All information about the app, developper and settings."
        ) {
            SettingsSubsectionItem(
                title = "Info",
                description = "Settings summary library and info.",
                onClick = onNavigateToInfo
            )
        }
    }
}

// ============================================================================
// HERBRUIKBARE COMPONENTEN (Voor een strakke, consistente UI)
// ============================================================================

/**
 * De hoofdkaart container met een subtiele achtergrondkleur en afgeronde hoeken.
 */
@Composable
fun SettingsCard(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp)) // Mooie afgeronde hoeken
            .background(Color.DarkGray.copy(alpha = 0.3f))
            .padding(16.dp) // Interne padding van de kaart
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = description,
            color = Color.Gray,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // Hier worden de rijen/subsecties ingeladen
        content()
    }
}

/**
 * Een klikbare rij voor navigatie naar sub-pagina's met een pijltje.
 */
@Composable
fun SettingsSubsectionItem(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)) // Pill-shape hint bij het indrukken
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(text = description, color = Color.Gray, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Icon(
            imageVector = Icons.Default.KeyboardArrowRight,
            contentDescription = "Ga naar $title",
            tint = Color.Gray,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

/**
 * Een niet-klikbare rij om puur status informatie weer te geven (voor Kaart 1).
 */
@Composable
fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = Color.LightGray, fontSize = 14.sp)
        Text(text = value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}