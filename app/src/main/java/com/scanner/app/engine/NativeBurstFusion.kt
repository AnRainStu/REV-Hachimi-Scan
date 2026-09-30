package com.scanner.app.engine

import org.opencv.core.Mat

class NativeBurstFusion {

    init {
        System.loadLibrary("doc_scanner_engine")
    }

    /**
     * 将多帧连拍图像进行单应性配准、时域中值降噪、屏幕防过曝与曝光融合 (SPEC_08)
     * @param burstFrames 2~5 帧连拍输入 Mat 列表
     * @param removeGlare 是否开启智能反光擦除 (Anti-Glare)
     * @param isScreenMode 是否开启屏幕特化防过曝增强与 Mertens 曝光融合
     * @return 融合后的高清纯净基准 Mat
     */
    fun fuseBurstFrames(
        burstFrames: List<Mat>,
        removeGlare: Boolean = true,
        isScreenMode: Boolean = true,
        superResolution: Boolean = false
    ): Mat {
        if (burstFrames.isEmpty()) return Mat()
        if (burstFrames.size == 1 && !superResolution) return burstFrames[0].clone()

        val addrs = burstFrames.map { it.nativeObjAddr }.toLongArray()
        val resultAddr = nativeFuseBurstFrames(addrs, removeGlare, isScreenMode, superResolution)
        return Mat(resultAddr)
    }

    private external fun nativeFuseBurstFrames(
        matAddrs: LongArray,
        removeGlare: Boolean,
        isScreenMode: Boolean,
        superResolution: Boolean
    ): Long
}
