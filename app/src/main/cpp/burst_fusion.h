#ifndef BURST_FUSION_H
#define BURST_FUSION_H

#include <opencv2/opencv.hpp>
#include <vector>

class BurstFusionEngine {
public:
    // 将多张连拍图进行亚像素单应性对齐、时域中值降噪并智能消除表面强光反光
    // @param burstFrames: 3~5 帧短曝光或连拍输入图像 (BGR格式)
    // @param removeGlare: 是否开启智能高光反光擦除
    // @return: 融合后的超清晰、无噪点、无反光基准图像
    cv::Mat fuseBurstFrames(const std::vector<cv::Mat>& burstFrames, bool removeGlare = true);

private:
    // 计算辅助帧到基准帧的单应性配准矩阵并拉正
    bool alignFrame(const cv::Mat& src, const cv::Mat& ref, cv::Mat& outAligned);
};

#endif // BURST_FUSION_H
