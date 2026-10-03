package com.scanner.app.ui.export

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scanner.app.R
import com.scanner.app.ui.components.GlassButton
import com.scanner.app.domain.model.ExportConfig
import com.scanner.app.domain.model.ExportMode
import com.scanner.app.domain.model.ScannedPage
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportDialog(
    pages: List<ScannedPage>,
    onDismiss: () -> Unit,
    viewModel: ExportViewModel = viewModel()
) {
    val context = LocalContext.current
    val exportState by viewModel.exportState.collectAsState()

    var selectedMode by remember { mutableStateOf(ExportMode.PDF) }
    var exportName by remember {
        val format = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault())
        mutableStateOf(format.format(Date()))
    }

    LaunchedEffect(Unit) { viewModel.resetState() }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surface,
        onDismissRequest = { if (exportState !is ExportState.Exporting) onDismiss() },
        title = {
            Text(
                text = stringResource(R.string.export_document),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(ExportMode.FOLDER, ExportMode.PDF).forEach { mode ->
                        Surface(onClick = { selectedMode = mode }, enabled = exportState !is ExportState.Exporting,
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(20.dp),
                            color = if (selectedMode == mode) MaterialTheme.colorScheme.primary.copy(alpha = .12f)
                                else MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = if (selectedMode == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(if (mode == ExportMode.PDF) Icons.Default.PictureAsPdf else Icons.Default.FolderZip,
                                    null, Modifier.size(28.dp))
                                Text(stringResource(if (mode == ExportMode.PDF) R.string.export_pdf else R.string.export_folder),
                                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }

                TextField(
                    value = exportName,
                    onValueChange = { exportName = it },
                    enabled = exportState !is ExportState.Exporting,
                    label = { Text(stringResource(R.string.export_name)) },
                    shape = RoundedCornerShape(20.dp),
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                when (val state = exportState) {
                    is ExportState.Exporting -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text(
                                    text = stringResource(R.string.exporting),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                    is ExportState.Success -> {
                        TextButton(onClick = {
                            val intent = android.content.Intent(
                                if (state.result.uris.size == 1) android.content.Intent.ACTION_SEND
                                else android.content.Intent.ACTION_SEND_MULTIPLE
                            ).apply {
                                type = state.result.mimeType
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                clipData = android.content.ClipData.newUri(context.contentResolver, "Scanned document", state.result.uris.first()).apply {
                                    state.result.uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) }
                                }
                                if (state.result.uris.size == 1) putExtra(android.content.Intent.EXTRA_STREAM, state.result.uris.first())
                                else putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(state.result.uris))
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.export)))
                        }) { Text(stringResource(R.string.share)) }
                        Text(
                            text = stringResource(R.string.export_success, state.result.location),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    is ExportState.Error -> {
                        Text(
                            text = stringResource(R.string.export_error, state.message),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    ExportState.Idle -> {}
                }
            }
        },
        confirmButton = {
            GlassButton(
                onClick = {
                    val config = ExportConfig(exportMode = selectedMode, name = exportName)
                    viewModel.export(context, pages, config)
                },
                enabled = pages.isNotEmpty() && exportName.isNotBlank() && exportState !is ExportState.Exporting,
                primary = true,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(stringResource(R.string.export), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                enabled = exportState !is ExportState.Exporting,
                onClick = {
                    viewModel.resetState()
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.close))
            }
        },
        shape = RoundedCornerShape(28.dp)
    )
}
