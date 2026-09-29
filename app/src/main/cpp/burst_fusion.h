#ifndef BURST_FUSION_H
#define BURST_FUSION_H

#include <opencv2/opencv.hpp>
#include <vector>

class BurstFusionEngine {
public:
    // 真正过曝饱和嫁接与高光微细节保真 HDR 融合 (SPEC_12)
    // @param burstFrames: 连拍输入序列 (BGR格式, burstFrames[0] 为基准帧 EV=0, burstFrames[1] 为欠曝高光帧 EV=-2)
    // @param removeGlare: 保留兼容接口标志位 (SPEC_12 统一采用连续可导饱和模型)
    // @param isScreenMode: 保留兼容接口标志位
    // @return: 融合后的超清晰、无噪点、零断层、高微细节保真的高动态范围图像
    cv::Mat fuseBurstFrames(const std::vector<cv::Mat>& burstFrames, bool removeGlare = false, bool isScreenMode = false);

    // 第一级：全局单应性粗配准 (ORB + RANSAC Homography)
    bool alignFrameHomography(const cv::Mat& src, const cv::Mat& ref, cv::Mat& outWarped, cv::Mat& outH);

private:
    // 计算欠曝帧到基准帧在高光过渡区 (Y0 in [210, 240]) 的平滑亮度自适应增益比
    float estimateHighlightAdaptationGain(const cv::Mat& bgrBase, const cv::Mat& bgrCand, const cv::Mat& validMask);
};

#endif // BURST_FUSION_H
