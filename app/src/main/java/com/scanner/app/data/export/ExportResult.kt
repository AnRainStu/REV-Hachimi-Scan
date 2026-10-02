package com.scanner.app.data.export

import android.net.Uri

data class ExportResult(val uris: List<Uri>, val location: String, val mimeType: String)
