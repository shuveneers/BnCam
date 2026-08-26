package com.bncam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

// De vaste accentkleur voor de componenten in dit bestand
val AccentPistachio = Color(0xFFB2D3A8)

/**
 * Een minimalistische rij met een titel/beschrijving links en een Switch (Aan/Uit) rechts.
 */
@Composable
fun SettingToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() } // FIX: Voorkomt vastlopen van de klik

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp)) // Rounded rectangle: keeps straight side walls when text wraps
            .clickable(
                interactionSource = interactionSource,
                indication = null // FIX: Verwijdert de grijze ripple die bleef hangen
            ) { onCheckedChange(!checked) }
            .background(Color.DarkGray.copy(alpha = 0.3f))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(text = description, color = Color.Gray, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color.Gray,
                uncheckedThumbColor = Color.DarkGray,
                uncheckedTrackColor = Color.Black
            )
        )
    }
}

/**
 * Een minimalistische rij met een titel/beschrijving links en een klikbare Waarde rechts.
 */
@Composable
fun SettingValueRow(
    title: String,
    description: String,
    value: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() } // FIX: Voorkomt vastlopen van de klik

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp)) // Rounded rectangle stays balanced for multiline values
            .clickable(
                interactionSource = interactionSource,
                indication = null // FIX: Verwijdert de grijze ripple die bleef hangen
            ) { onClick() }
            .background(Color.DarkGray.copy(alpha = 0.3f))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(text = description, color = Color.Gray, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Text(
            text = value,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            lineHeight = 18.sp,
            modifier = Modifier.widthIn(max = 156.dp)
        )
    }
}

/**
 * NIEUW: Een geavanceerde slider-rij voor numerieke (GCam-stijl) instellingen.
 */
@Composable
fun SettingSliderRow(
    title: String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f, // Standaard range 0.00 tot 1.00
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
    // Formatteert het getal standaard naar 2 decimalen, bijv. "0.47"
    valueFormatter: (Float) -> String = { String.format(Locale.US, "%.2f", it) },
    onValueChangeFinished: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp)) // Slider card keeps visible straight side walls
            .background(Color.DarkGray.copy(alpha = if (enabled) 0.3f else 0.15f))
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(text = title, color = if (enabled) Color.White else Color.Gray, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(text = description, color = Color.Gray.copy(alpha = if (enabled) 1.0f else 0.5f), fontSize = 12.sp, lineHeight = 16.sp)
            }
            // De actuele numerieke waarde
            Text(
                text = valueFormatter(value),
                color = if (enabled) AccentPistachio else Color.Gray,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            onValueChangeFinished = { onValueChangeFinished?.invoke() },
            colors = SliderDefaults.colors(
                thumbColor = if (enabled) Color.White else Color.Gray,
                activeTrackColor = if (enabled) AccentPistachio else Color.Gray,
                inactiveTrackColor = Color.Gray.copy(alpha = 0.3f)
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}