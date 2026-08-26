package com.bncam.ui.screens.settings.lens_profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bncam.ui.screens.settings.SettingsCard

@Composable
fun ProfileListScreen(
    lensId: String,
    profileCount: Int,
    savedProfileNames: Map<String, String>, // DIT WAS DE MISSENDE PARAMETER!
    onNavigateToProfileDetail: (String, Int) -> Unit,
    onNavigateBack: () -> Unit
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
        // Terug-knop
        Icon(
            imageVector = Icons.Default.ArrowBack,
            contentDescription = "Back",
            tint = Color.White,
            modifier = Modifier.padding(16.dp).clickable { onNavigateBack() }
        )

        // Windows Phone Titel
        Text(
            text = "Profile list",
            color = Color.White,
            fontWeight = FontWeight.Light,
            fontSize = 48.sp,
            letterSpacing = (-1).sp,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)
        )

        // ==========================================
        // KAART: Visible profiles
        // ==========================================
        SettingsCard(
            title = "Visible profiles",
            description = "Manage up to 12 profiles for this lens. Profiles beyond the active count ($profileCount) are locked."
        ) {
            // We laten altijd alle 12 slots zien, maar locken degenen die buiten de count vallen
            for (i in 1..12) {
                val isLocked = i > profileCount
                val profileId = "${lensId}_profile_$i"

                // Haal de opgeslagen naam op, of gebruik de standaard "Profile X"
                val profileName = savedProfileNames[profileId] ?: "Profile $i"

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Lock logica: Alleen clickable als hij niet locked is
                        .then(if (!isLocked) Modifier.clickable { onNavigateToProfileDetail(profileId, i) } else Modifier)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profileName,
                            color = if (!isLocked) Color.White else Color.Gray.copy(alpha = 0.5f),
                            fontSize = 16.sp,
                            fontWeight = if (!isLocked) FontWeight.Normal else FontWeight.Light
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isLocked) "Locked (Increase count in lens settings)" else "Configure shooting mode and processing.",
                            color = if (!isLocked) Color.Gray else Color.DarkGray,
                            fontSize = 12.sp
                        )
                    }

                    if (isLocked) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Locked",
                            tint = Color.DarkGray,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Text(
                            text = "Setup",
                            color = AccentPistachio, // Gebruikt de pistache kleur uit onze style
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}