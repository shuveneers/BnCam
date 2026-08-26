package com.bncam.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun InfoScreen(onNavigateBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        Icon(
            imageVector = Icons.Default.ArrowBack,
            contentDescription = "Back",
            tint = Color.White,
            modifier = Modifier.padding(16.dp).clickable { onNavigateBack() }
        )

        Text(
            text = "About",
            color = Color.White,
            fontWeight = FontWeight.Light,
            fontSize = 48.sp,
            letterSpacing = (-1).sp,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)
        )

        Column(modifier = Modifier.padding(horizontal = 32.dp)) {
            Text(text = "BnCam v1.0.0", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "The ultimate custom camera engine.", color = Color.Gray, fontSize = 14.sp)
        }
    }
}