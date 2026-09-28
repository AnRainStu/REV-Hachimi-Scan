package com.scanner.app.engine

import org.opencv.core.Mat

class NativeBurstFusion {

    init {
        System.loadLibrary("doc_scanner_engine")
    }

    /**
     * 将多帧连拍图像进行单应性配准、时域中值降噪并消除表面高光反光
     * @param burstFrames 3~5 帧连拍输入 Mat 列表
     * @param removeGlare 是否开启智能反光擦除 (Anti-Glare)
     * @return 融合后的高清纯净基准 Mat
     */
    fun fuseBurstFrames(burstFrames: List<Mat>, removeGlare: Boolean = true): Mat {
        if (burstFrames.isEmpty()) return Mat()
        if (burstFrames.size == 1) return burstFrames[0].clone()

        val addrs = burstFrames.map { it.nativeObjAddr }.toLongArray()
        val resultAddr = nativeFuseBurstFrames(addrs, removeGlare)
        return Mat(resultAddr)
    }

    private external fun nativeFuseBurstFrames(matAddrs: LongArray, removeGlare: Boolean): Long
}
