package com.scanner.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.scanner.app.R
import com.scanner.app.domain.model.AspectRatioPreset

@Composable
fun CustomRatioDialog(initialRatio: Float? = null, onDismiss: () -> Unit, onConfirm: (Float) -> Unit) {
    var width by remember { mutableStateOf(initialRatio?.toString() ?: "") }
    var height by remember { mutableStateOf("1") }
    val ratio = width.toFloatOrNull()?.let { w -> height.toFloatOrNull()?.let { h -> if (h > 0) w / h else null } }
    val valid = ratio != null && ratio.isFinite() && ratio >= 1f / 12f && ratio <= 12
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.custom_ratio_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.custom_ratio_hint))
            OutlinedTextField(width, { width = it }, label = { Text(stringResource(R.string.ratio_width)) }, singleLine = true)
            OutlinedTextField(height, { height = it }, label = { Text(stringResource(R.string.ratio_height)) }, singleLine = true)
        } },
        confirmButton = { TextButton(onClick = { ratio?.let(onConfirm) }, enabled = valid) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
fun AspectRatioBanner(selectedRatio: AspectRatioPreset, customRatioValue: Float? = null,
    onSelectRatio: (AspectRatioPreset) -> Unit, onSelectCustomRatio: (Float) -> Unit = {}, modifier: Modifier = Modifier) {
    var custom by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AspectRatioPreset.entries.forEach { preset ->
            FilterChip(selected = selectedRatio == preset && (preset != AspectRatioPreset.CUSTOM || customRatioValue == null),
                onClick = { onSelectRatio(preset) }, label = {
                    Text(stringResource(if (preset == AspectRatioPreset.CUSTOM) R.string.ratio_free else preset.titleRes))
                })
        }
        FilterChip(selected = selectedRatio == AspectRatioPreset.CUSTOM && customRatioValue != null,
            onClick = { custom = true }, label = { Text(stringResource(R.string.ratio_custom)) })
    }
    if (custom) CustomRatioDialog(customRatioValue, { custom = false }) {
        custom = false; onSelectCustomRatio(it)
    }
}
