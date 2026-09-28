package com.scanner.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scanner.app.R
import com.scanner.app.domain.model.AspectRatioPreset
import com.scanner.app.ui.theme.PrismCyan
import com.scanner.app.ui.theme.SteadyEmerald

@Composable
fun AspectRatioSidePanel(
    selectedRatio: AspectRatioPreset,
    onSelectRatio: (AspectRatioPreset) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    // Direct access items requested by user: A4, 4:3, 自定义 (Custom), plus dropdown
    val quickItems = listOf(
        AspectRatioPreset.A4,
        AspectRatioPreset.RATIO_4_3,
        AspectRatioPreset.CUSTOM
    )

    val isDropdownSelected = selectedRatio !in quickItems

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color(0xCC0F172A),
        border = BorderStroke(1.dp, Color(0x33FFFFFF)),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 8.dp, horizontal = 6.dp)
                .width(IntrinsicSize.Min),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Quick preset buttons: A4, 4:3, Custom
            quickItems.forEach { preset ->
                val isSelected = selectedRatio == preset
                RatioSideButton(
                    label = when (preset) {
                        AspectRatioPreset.A4 -> "A4"
                        AspectRatioPreset.RATIO_4_3 -> "4:3"
                        AspectRatioPreset.CUSTOM -> stringResource(R.string.ratio_custom)
                        else -> stringResource(preset.titleRes)
                    },
                    isSelected = isSelected,
                    onClick = { onSelectRatio(preset) }
                )
            }

            HorizontalDivider(
                modifier = Modifier
                    .width(42.dp)
                    .padding(vertical = 2.dp),
                thickness = 0.8.dp,
                color = Color(0x33FFFFFF)
            )

            // Dropdown button opening full ratio list (A4, A3, 4:3, 16:9, 8:7, 自定义)
            Box {
                val dropBtnBg = if (isDropdownSelected) PrismCyan.copy(alpha = 0.25f) else Color.Transparent
                val dropBtnBorder = if (isDropdownSelected) PrismCyan else Color.Transparent
                val dropBtnTint = if (isDropdownSelected) PrismCyan else Color.White.copy(alpha = 0.85f)

                Row(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(dropBtnBg)
                        .then(
                            if (isDropdownSelected) Modifier.border(1.2.dp, dropBtnBorder, RoundedCornerShape(10.dp))
                            else Modifier
                        )
                        .clickable { expanded = true }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isDropdownSelected) {
                        Text(
                            text = when (selectedRatio) {
                                AspectRatioPreset.A3 -> "A3"
                                AspectRatioPreset.RATIO_16_9 -> "16:9"
                                AspectRatioPreset.RATIO_8_7 -> "8:7"
                                else -> stringResource(selectedRatio.titleRes)
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrismCyan
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "All aspect ratios",
                        tint = dropBtnTint,
                        modifier = Modifier.size(20.dp)
                    )
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier
                        .background(Color(0xF00F172A))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                ) {
                    val allPresets = listOf(
                        AspectRatioPreset.A4,
                        AspectRatioPreset.A3,
                        AspectRatioPreset.RATIO_4_3,
                        AspectRatioPreset.RATIO_16_9,
                        AspectRatioPreset.RATIO_8_7,
                        AspectRatioPreset.CUSTOM
                    )

                    allPresets.forEach { preset ->
                        val isItemActive = selectedRatio == preset
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = when (preset) {
                                        AspectRatioPreset.A4 -> "A4"
                                        AspectRatioPreset.A3 -> "A3"
                                        AspectRatioPreset.RATIO_4_3 -> "4:3"
                                        AspectRatioPreset.RATIO_16_9 -> "16:9"
                                        AspectRatioPreset.RATIO_8_7 -> "8:7"
                                        AspectRatioPreset.CUSTOM -> stringResource(R.string.ratio_custom)
                                    },
                                    color = if (isItemActive) PrismCyan else Color.White,
                                    fontWeight = if (isItemActive) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp
                                )
                            },
                            trailingIcon = {
                                if (isItemActive) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = PrismCyan,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            },
                            onClick = {
                                onSelectRatio(preset)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RatioSideButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (isSelected) PrismCyan.copy(alpha = 0.25f) else Color.Transparent
    val border = if (isSelected) PrismCyan else Color.Transparent
    val contentColor = if (isSelected) PrismCyan else Color.White.copy(alpha = 0.85f)

    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 50.dp)
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .then(
                if (isSelected) Modifier.border(1.2.dp, border, RoundedCornerShape(10.dp))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1
        )
    }
}
