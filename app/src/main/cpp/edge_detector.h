#ifndef EDGE_DETECTOR_H
#define EDGE_DETECTOR_H

#include <opencv2/opencv.hpp>
#include <vector>

struct DetectionResult {
    bool found;
    std::vector<cv::Point2f> corners; // 顺序恒为: Top-Left, Top-Right, Bottom-Right, Bottom-Left
    bool isCurved;
    std::vector<cv::Point2f> boundaryPoints;
};

class EdgeDetector {
public:
    // 快速端侧边缘检测 (抗杂乱桌面、抗复杂光照、防止沙漏错乱)
    DetectionResult detectDocument(const cv::Mat& grayFrame, bool curvedMode = false);

    // 质心极角排序：彻底根治旋转30°~60°时的角点对角翻转bug
    static std::vector<cv::Point2f> sortCorners(const std::vector<cv::Point2f>& points);

    // 手指磁吸吸附微调：在触摸点周围半径内吸附高对比度强边缘
    std::vector<cv::Point> findMagneticSnapPoint(const cv::Mat& edgeMat, const cv::Point2f& touchPoint, float radius);

    // 边缘直线法向梯度脊线吸附 (SPEC_05 Rev 2 §3.1)
    // 沿直线段采样 N=25 个点，在法向偏移 [-maxOffset, +maxOffset] 范围内寻找边缘梯度积分峰值
    float findMagneticLineOffset(const cv::Mat& edgeMat, const cv::Point2f& p1, const cv::Point2f& p2, float maxOffset);

    // 坐标点区域快速吸附：探测包含 (touchX, touchY) 的凸四边形目标 (SPEC_05 Rev 2 §3.5)
    std::vector<cv::Point2f> findContourAtPoint(const cv::Mat& grayMat, float touchX, float touchY);

    // 基于结构直线段检测 (LSD) 的全局特征提取 (SPEC_06 §2)
    struct StructuralLinesResult {
        std::vector<float> horizontalLines; // [x1, y1, x2, y2, ...]
        std::vector<float> verticalLines;   // [x1, y1, x2, y2, ...]
    };
    StructuralLinesResult detectStructuralLines(const cv::Mat& grayImage, float origWidth = 0.0f, float origHeight = 0.0f);
};

#endif // EDGE_DETECTOR_H
