#ifndef BURST_FUSION_H
#define BURST_FUSION_H

#include <opencv2/opencv.hpp>
#include <opencv2/video.hpp>
#include <vector>

class BurstFusionEngine {
public:
    // 综合多帧 HDR 局部视差光流补偿与基准锚定时域防重影融合 (SPEC_09)
    // @param burstFrames: 2~5 帧连拍输入图像 (BGR格式, burstFrames[0] 为基准帧)
    // @param removeGlare: 是否开启智能高光反光擦除
    // @param isScreenMode: 是否开启屏幕特化防过曝与曝光融合增强
    // @return: 融合后的超清晰、无噪点、零重影的高动态范围图像
    cv::Mat fuseBurstFrames(const std::vector<cv::Mat>& burstFrames, bool removeGlare = true, bool isScreenMode = true);

    // 第一级：全局粗配准 (ORB + RANSAC Homography)
    bool alignFrameHomography(const cv::Mat& src, const cv::Mat& ref, cv::Mat& outWarped, cv::Mat& outH);

    // 第二级：局部稠密光流视差补偿 (Pyramidal DIS Optical Flow)
    bool refineOpticalFlowDIS(const cv::Mat& srcWarped, const cv::Mat& ref, cv::Mat& outRefined);

    // 第三级：基准帧绝对几何锚定与光度鲁棒防重影掩模生成
    cv::Mat computeRobustDeghostMask(const cv::Mat& candRefined, const cv::Mat& baseFrame);

    // 基准锚定多分辨率金字塔 HDR 融合 (权重守恒律: 凡错位区 100% 锁死基准帧)
    cv::Mat blendRobustHDR(const cv::Mat& baseFrame, const std::vector<cv::Mat>& alignedFrames, const std::vector<cv::Mat>& robustMasks);

private:
    // 计算两帧之间的中值光度增益比 (中灰度未饱和区)
    float estimatePhotometricGain(const cv::Mat& grayBase, const cv::Mat& grayCand);
};

#endif // BURST_FUSION_H
