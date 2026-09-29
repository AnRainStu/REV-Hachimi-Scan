package com.scanner.app.domain.model

import com.scanner.app.R

enum class AspectRatioPreset(
    val titleRes: Int,
    val ratio: Float?
) {
    A4(R.string.ratio_a4, 210f / 297f),        // 0.7071 (portrait ISO standard)
    A3(R.string.ratio_a3, 297f / 210f),        // 1.4142 (landscape ISO standard)
    RATIO_4_3(R.string.ratio_4_3, 4f / 3f),    // 1.3333
    RATIO_16_9(R.string.ratio_16_9, 16f / 9f), // 1.7778
    RATIO_21_9(R.string.ratio_21_9, 21f / 9f), // 2.3333
    RATIO_8_7(R.string.ratio_8_7, 8f / 7f),    // 1.1429 (PPT projector / sensor native)
    K8(R.string.ratio_8k, 260f / 370f),        // 0.7027 (8开 standard paper)
    K16(R.string.ratio_16k, 185f / 260f),      // 0.7115 (16开 standard textbook)
    SQUARE(R.string.ratio_square, 1.0f),       // 1.0 (1:1 square)
    CUSTOM(R.string.ratio_custom, null);       // Custom aspect ratio

    companion object {
        fun fromRatio(ratio: Float?): AspectRatioPreset {
            if (ratio == null || ratio <= 0.01f) return CUSTOM
            val closest = entries.filter { it.ratio != null }
                .minByOrNull { kotlin.math.abs(it.ratio!! - ratio) }
            if (closest != null && kotlin.math.abs(closest.ratio!! - ratio) < 0.003f) {
                return closest
            }
            return CUSTOM
        }
    }
}
