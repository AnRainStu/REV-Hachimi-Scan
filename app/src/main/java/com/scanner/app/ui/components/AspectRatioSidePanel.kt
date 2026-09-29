package com.scanner.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scanner.app.R
import com.scanner.app.domain.model.AspectRatioPreset
import com.scanner.app.ui.theme.PrismCyan

@Composable
fun CustomRatioDialog(
    initialRatio: Float? = null,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit
) {
    var widthText by remember { mutableStateOf("") }
    var heightText by remember { mutableStateOf("") }

    val w = widthText.toFloatOrNull()
    val h = heightText.toFloatOrNull()
    val isValid = w != null && h != null && w > 0f && h > 0f

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E293B),
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = stringResource(R.string.custom_ratio_title),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    OutlinedTextField(
                        value = widthText,
                        onValueChange = { input ->
                            if (input.all { it.isDigit() || it == '.' } && input.count { it == '.' } <= 1) {
                                widthText = input
                            }
                        },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.placeholder_input),
                                color = Color.White.copy(alpha = 0.4f),
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        modifier = Modifier.width(96.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Next
                        ),
                        textStyle = LocalTextStyle.current.copy(
                            textAlign = TextAlign.Center,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrismCyan,
                            unfocusedBorderColor = Color(0x44FFFFFF),
                            focusedContainerColor = Color(0x1A00E5FF),
                            unfocusedContainerColor = Color(0x11FFFFFF),
                            cursorColor = PrismCyan,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Text(
                        text = " : ",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    OutlinedTextField(
                        value = heightText,
                        onValueChange = { input ->
                            if (input.all { it.isDigit() || it == '.' } && input.count { it == '.' } <= 1) {
                                heightText = input
                            }
                        },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.placeholder_input),
                                color = Color.White.copy(alpha = 0.4f),
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        modifier = Modifier.width(96.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (isValid) {
                                    onConfirm(w!! / h!!)
                                }
                            }
                        ),
                        textStyle = LocalTextStyle.current.copy(
                            textAlign = TextAlign.Center,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrismCyan,
                            unfocusedBorderColor = Color(0x44FFFFFF),
                            focusedContainerColor = Color(0x1A00E5FF),
                            unfocusedContainerColor = Color(0x11FFFFFF),
                            cursorColor = PrismCyan,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isValid) {
                        onConfirm(w!! / h!!)
                    }
                },
                enabled = isValid,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrismCyan,
                    contentColor = Color(0xFF0F172A),
                    disabledContainerColor = Color(0x2200E5FF),
                    disabledContentColor = Color(0x55FFFFFF)
                )
            ) {
                Text(stringResource(R.string.confirm), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.cancel), color = Color.White.copy(alpha = 0.7f))
            }
        }
    )
}

@Composable
fun AspectRatioBanner(
    selectedRatio: AspectRatioPreset,
    customRatioValue: Float? = null,
    onSelectRatio: (AspectRatioPreset) -> Unit,
    onSelectCustomRatio: (Float) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var showCustomDialog by remember { mutableStateOf(false) }

    if (showCustomDialog) {
        CustomRatioDialog(
            initialRatio = customRatioValue,
            onDismiss = { showCustomDialog = false },
            onConfirm = { ratio ->
                showCustomDialog = false
                onSelectCustomRatio(ratio)
                onSelectRatio(AspectRatioPreset.CUSTOM)
            }
        )
    }

    // Direct access items requested in bottom row: A4, 4:3, 8:7, 21:9
    val rowPresets = listOf(
        AspectRatioPreset.A4,
        AspectRatioPreset.RATIO_4_3,
        AspectRatioPreset.RATIO_8_7,
        AspectRatioPreset.RATIO_21_9
    )

    // Items inside the pull-up list: A3, 16:9, 8开, 16开, 正方形
    val dropdownPresets = listOf(
        AspectRatioPreset.A3,
        AspectRatioPreset.RATIO_16_9,
        AspectRatioPreset.K8,
        AspectRatioPreset.K16,
        AspectRatioPreset.SQUARE
    )

    val isDropdownSelected = selectedRatio in dropdownPresets

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        // Quick items: A4, 4:3, 8:7, 21:9
        rowPresets.forEach { preset ->
            val isSelected = selectedRatio == preset
            val bg = if (isSelected) PrismCyan.copy(alpha = 0.25f) else Color(0x22FFFFFF)
            val border = if (isSelected) PrismCyan else Color(0x22FFFFFF)
            val contentColor = if (isSelected) PrismCyan else Color(0xFFE2E8F0)

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(bg)
                    .border(1.dp, border, RoundedCornerShape(14.dp))
                    .clickable { onSelectRatio(preset) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (preset) {
                        AspectRatioPreset.A4 -> "A4"
                        AspectRatioPreset.RATIO_4_3 -> "4:3"
                        AspectRatioPreset.RATIO_8_7 -> "8:7"
                        AspectRatioPreset.RATIO_21_9 -> "21:9"
                        else -> stringResource(preset.titleRes)
                    },
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = contentColor
                )
            }
        }

        // Custom Ratio Button: 自定义 -> opens CustomRatioDialog
        val isCustomSelected = selectedRatio == AspectRatioPreset.CUSTOM
        val customBg = if (isCustomSelected) PrismCyan.copy(alpha = 0.25f) else Color(0x22FFFFFF)
        val customBorder = if (isCustomSelected) PrismCyan else Color(0x22FFFFFF)
        val customContentColor = if (isCustomSelected) PrismCyan else Color(0xFFE2E8F0)

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(customBg)
                .border(1.dp, customBorder, RoundedCornerShape(14.dp))
                .clickable { showCustomDialog = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.ratio_custom),
                fontSize = 12.sp,
                fontWeight = if (isCustomSelected) FontWeight.Bold else FontWeight.Medium,
                color = customContentColor
            )
        }

        // Pull-up dropdown box for A3, 8开, 16开, 正方形
        Box {
            val dropBtnBg = if (isDropdownSelected) PrismCyan.copy(alpha = 0.25f) else Color(0x22FFFFFF)
            val dropBtnBorder = if (isDropdownSelected) PrismCyan else Color(0x22FFFFFF)
            val dropBtnTint = if (isDropdownSelected) PrismCyan else Color(0xFFE2E8F0)

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(dropBtnBg)
                    .border(1.dp, dropBtnBorder, RoundedCornerShape(14.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (isDropdownSelected) {
                        when (selectedRatio) {
                            AspectRatioPreset.A3 -> "A3"
                            AspectRatioPreset.RATIO_16_9 -> "16:9"
                            AspectRatioPreset.K8 -> stringResource(R.string.ratio_8k)
                            AspectRatioPreset.K16 -> stringResource(R.string.ratio_16k)
                            AspectRatioPreset.SQUARE -> stringResource(R.string.ratio_square)
                            else -> stringResource(selectedRatio.titleRes)
                        }
                    } else stringResource(R.string.aspect_ratio),
                    fontSize = 12.sp,
                    fontWeight = if (isDropdownSelected) FontWeight.Bold else FontWeight.Medium,
                    color = dropBtnTint
                )
                Spacer(modifier = Modifier.width(3.dp))
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "More ratios",
                    tint = dropBtnTint,
                    modifier = Modifier.size(16.dp)
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .background(Color(0xF00F172A))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
            ) {
                dropdownPresets.forEach { preset ->
                    val isItemActive = selectedRatio == preset
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = when (preset) {
                                    AspectRatioPreset.A3 -> "A3 (横版 A4)"
                                    AspectRatioPreset.RATIO_16_9 -> "16:9"
                                    AspectRatioPreset.K8 -> "8开"
                                    AspectRatioPreset.K16 -> "16开"
                                    AspectRatioPreset.SQUARE -> "正方形 (1:1)"
                                    else -> stringResource(preset.titleRes)
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

@Composable
fun AspectRatioSidePanel(
    selectedRatio: AspectRatioPreset,
    customRatioValue: Float? = null,
    onSelectRatio: (AspectRatioPreset) -> Unit,
    onSelectCustomRatio: (Float) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var showCustomDialog by remember { mutableStateOf(false) }

    if (showCustomDialog) {
        CustomRatioDialog(
            initialRatio = customRatioValue,
            onDismiss = { showCustomDialog = false },
            onConfirm = { ratio ->
                showCustomDialog = false
                onSelectCustomRatio(ratio)
                onSelectRatio(AspectRatioPreset.CUSTOM)
            }
        )
    }

    val quickItems = listOf(
        AspectRatioPreset.A4,
        AspectRatioPreset.RATIO_4_3,
        AspectRatioPreset.RATIO_8_7,
        AspectRatioPreset.RATIO_21_9,
        AspectRatioPreset.CUSTOM
    )

    val dropdownPresets = listOf(
        AspectRatioPreset.A3,
        AspectRatioPreset.RATIO_16_9,
        AspectRatioPreset.K8,
        AspectRatioPreset.K16,
        AspectRatioPreset.SQUARE
    )

    val isDropdownSelected = selectedRatio in dropdownPresets

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
            quickItems.forEach { preset ->
                val isSelected = selectedRatio == preset
                RatioSideButton(
                    label = when (preset) {
                        AspectRatioPreset.A4 -> "A4"
                        AspectRatioPreset.RATIO_4_3 -> "4:3"
                        AspectRatioPreset.RATIO_8_7 -> "8:7"
                        AspectRatioPreset.RATIO_21_9 -> "21:9"
                        AspectRatioPreset.CUSTOM -> stringResource(R.string.ratio_custom)
                        else -> stringResource(preset.titleRes)
                    },
                    isSelected = isSelected,
                    onClick = {
                        if (preset == AspectRatioPreset.CUSTOM) {
                            showCustomDialog = true
                        } else {
                            onSelectRatio(preset)
                        }
                    }
                )
            }

            HorizontalDivider(
                modifier = Modifier
                    .width(42.dp)
                    .padding(vertical = 2.dp),
                thickness = 0.8.dp,
                color = Color(0x33FFFFFF)
            )

            // Dropdown button opening full ratio list (A3, 8开, 16开, 正方形)
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
                                AspectRatioPreset.K8 -> stringResource(R.string.ratio_8k)
                                AspectRatioPreset.K16 -> stringResource(R.string.ratio_16k)
                                AspectRatioPreset.SQUARE -> stringResource(R.string.ratio_square)
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
                    dropdownPresets.forEach { preset ->
                        val isItemActive = selectedRatio == preset
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = when (preset) {
                                        AspectRatioPreset.A3 -> "A3 (横版 A4)"
                                        AspectRatioPreset.RATIO_16_9 -> "16:9"
                                        AspectRatioPreset.K8 -> "8开"
                                        AspectRatioPreset.K16 -> "16开"
                                        AspectRatioPreset.SQUARE -> "正方形 (1:1)"
                                        else -> stringResource(preset.titleRes)
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
