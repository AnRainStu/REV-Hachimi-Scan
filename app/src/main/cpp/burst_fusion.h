#ifndef BURST_FUSION_H
#define BURST_FUSION_H

#include <opencv2/opencv.hpp>
#include <vector>

class BurstFusionEngine {
public:
    // 综合多帧 HDR 基准帧绝对锁定与高光单向嫁接零重影融合 (SPEC_10)
    // @param burstFrames: 连拍输入序列 (BGR格式, burstFrames[0] 为基准帧 EV=0, burstFrames[1] 为欠曝高光帧 EV=-2)
    // @param removeGlare: 是否开启智能高光反光擦除
    // @param isScreenMode: 是否开启屏幕特化防过曝与曝光融合增强
    // @return: 融合后的超清晰、无噪点、零重影、零浮雕的高动态范围图像
    cv::Mat fuseBurstFrames(const std::vector<cv::Mat>& burstFrames, bool removeGlare = true, bool isScreenMode = true);

    // 第一级：全局单应性粗配准 (ORB + RANSAC Homography)
    bool alignFrameHomography(const cv::Mat& src, const cv::Mat& ref, cv::Mat& outWarped, cv::Mat& outH);

private:
    // 计算欠曝帧到基准帧在高光过渡区 (Y0 in [170, 215]) 的平滑亮度自适应增益比
    float estimateHighlightAdaptationGain(const cv::Mat& labBase, const cv::Mat& labCand, const cv::Mat& validMask);
};

#endif // BURST_FUSION_H
