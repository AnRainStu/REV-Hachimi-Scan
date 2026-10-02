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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scanner.app.R
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
        onDismissRequest = { if (exportState !is ExportState.Exporting) onDismiss() },
        title = {
            Text(
                text = stringResource(R.string.export_document),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SegmentedButton(
                        selected = selectedMode == ExportMode.FOLDER,
                        onClick = { selectedMode = ExportMode.FOLDER },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = {
                            Icon(
                                imageVector = Icons.Default.FolderZip,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    ) {
                        Text(stringResource(R.string.export_folder), fontWeight = FontWeight.Medium)
                    }
                    SegmentedButton(
                        selected = selectedMode == ExportMode.PDF,
                        onClick = { selectedMode = ExportMode.PDF },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = {
                            Icon(
                                imageVector = Icons.Default.PictureAsPdf,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    ) {
                        Text(stringResource(R.string.export_pdf), fontWeight = FontWeight.Medium)
                    }
                }

                OutlinedTextField(
                    value = exportName,
                    onValueChange = { exportName = it },
                    enabled = exportState !is ExportState.Exporting,
                    label = { Text(stringResource(R.string.export_name)) },
                    shape = RoundedCornerShape(20.dp),
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
            Button(
                onClick = {
                    val config = ExportConfig(exportMode = selectedMode, name = exportName)
                    viewModel.export(context, pages, config)
                },
                enabled = pages.isNotEmpty() && exportName.isNotBlank() && exportState !is ExportState.Exporting,
                shape = RoundedCornerShape(20.dp)
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
