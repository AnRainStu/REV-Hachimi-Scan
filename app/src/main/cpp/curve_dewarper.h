#ifndef CURVE_DEWARPER_H
#define CURVE_DEWARPER_H

#include <opencv2/opencv.hpp>
#include <vector>

class CurveDewarper {
public:
    cv::Mat dewarpCurved(const cv::Mat& src, 
                         const std::vector<cv::Point2f>& topBoundary, 
                         const std::vector<cv::Point2f>& bottomBoundary, 
                         const std::vector<cv::Point2f>& leftBoundary, 
                         const std::vector<cv::Point2f>& rightBoundary);

private:
    std::vector<cv::Point2f> fitBezierCurve(const std::vector<cv::Point2f>& points, int numSamples);
    cv::Point2f evaluateBezier(const cv::Point2f& p0, const cv::Point2f& c1, const cv::Point2f& c2, const cv::Point2f& p3, float t);
};

#endif // CURVE_DEWARPER_H
