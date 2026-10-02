package com.scanner.app.domain.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExportConfig(
    val exportMode: ExportMode = ExportMode.PDF,
    val name: String = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()),
    val quality: Int = 90
) {
    fun validatedName(): String {
        val value = name.trim()
        require(value.isNotEmpty() && value.length <= 100 && value != "." && value != ".." &&
            value.none { it == '/' || it == '\\' || it.code < 32 || it in ":*?\"<>|" }) {
            "Use a name of 1–100 characters without path separators or special characters"
        }
        return value
    }
}

enum class ExportMode {
    FOLDER, PDF
}
