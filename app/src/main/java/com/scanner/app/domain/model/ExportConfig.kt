package com.scanner.app.domain.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExportConfig(
    val exportMode: ExportMode = ExportMode.PDF,
    val name: String = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()),
    val quality: Int = 90
)

enum class ExportMode {
    FOLDER, PDF
}
