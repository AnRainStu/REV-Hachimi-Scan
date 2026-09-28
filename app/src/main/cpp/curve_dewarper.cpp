#include "curve_dewarper.h"
#include <cmath>
#include <algorithm>

cv::Point2f CurveDewarper::evaluateBezier(const cv::Point2f& p0, const cv::Point2f& c1, const cv::Point2f& c2, const cv::Point2f& p3, float t) {
    float u = 1.0f - t;
    float tt = t * t;
    float uu = u * u;
    float uuu = uu * u;
    float ttt = tt * t;

    cv::Point2f p = uuu * p0;
    p += 3 * uu * t * c1;
    p += 3 * u * tt * c2;
    p += ttt * p3;

    return p;
}

std::vector<cv::Point2f> CurveDewarper::fitBezierCurve(const std::vector<cv::Point2f>& points, int numSamples) {
    std::vector<cv::Point2f> result;
    if (points.size() < 2) return result;

    cv::Point2f p0 = points.front();
    cv::Point2f p3 = points.back();
    cv::Point2f c1 = p0 + (p3 - p0) * 0.33f;
    cv::Point2f c2 = p0 + (p3 - p0) * 0.67f;

    if (points.size() > 4) {
        c1 = points[points.size() / 3];
        c2 = points[points.size() * 2 / 3];
    }

    for (int i = 0; i < numSamples; ++i) {
        float t = static_cast<float>(i) / std::max(1, numSamples - 1);
        result.push_back(evaluateBezier(p0, c1, c2, p3, t));
    }

    return result;
}

cv::Mat CurveDewarper::dewarpCurved(const cv::Mat& src, 
                                    const std::vector<cv::Point2f>& topBoundary, 
                                    const std::vector<cv::Point2f>& bottomBoundary, 
                                    const std::vector<cv::Point2f>& leftBoundary, 
                                    const std::vector<cv::Point2f>& rightBoundary) {
    if (topBoundary.size() < 2 || bottomBoundary.size() < 2 || 
        leftBoundary.size() < 2 || rightBoundary.size() < 2) {
        return src.clone();
    }

    float topLen = cv::norm(topBoundary.front() - topBoundary.back());
    float botLen = cv::norm(bottomBoundary.front() - bottomBoundary.back());
    int outWidth = static_cast<int>(std::max(topLen, botLen));

    float leftLen = cv::norm(leftBoundary.front() - leftBoundary.back());
    float rightLen = cv::norm(rightBoundary.front() - rightBoundary.back());
    int outHeight = static_cast<int>(std::max(leftLen, rightLen));

    if (outWidth <= 10) outWidth = src.cols > 0 ? src.cols : 800;
    if (outHeight <= 10) outHeight = src.rows > 0 ? src.rows : 1000;

    std::vector<cv::Point2f> top = fitBezierCurve(topBoundary, outWidth);
    std::vector<cv::Point2f> bottom = fitBezierCurve(bottomBoundary, outWidth);
    std::vector<cv::Point2f> left = fitBezierCurve(leftBoundary, outHeight);
    std::vector<cv::Point2f> right = fitBezierCurve(rightBoundary, outHeight);

    if (top.empty() || bottom.empty() || left.empty() || right.empty()) {
        return src.clone();
    }

    cv::Mat mapX(outHeight, outWidth, CV_32FC1);
    cv::Mat mapY(outHeight, outWidth, CV_32FC1);

    cv::Point2f P00 = top.front();
    cv::Point2f P10 = top.back();
    cv::Point2f P01 = bottom.front();
    cv::Point2f P11 = bottom.back();

    for (int v = 0; v < outHeight; ++v) {
        float vf = static_cast<float>(v) / (outHeight - 1);
        for (int u = 0; u < outWidth; ++u) {
            float uf = static_cast<float>(u) / (outWidth - 1);

            cv::Point2f pt = (1 - vf) * top[u] + vf * bottom[u] + (1 - uf) * left[v] + uf * right[v];
            cv::Point2f bilinear = (1 - uf) * (1 - vf) * P00 + uf * (1 - vf) * P10 + (1 - uf) * vf * P01 + uf * vf * P11;
            pt -= bilinear;

            mapX.at<float>(v, u) = pt.x;
            mapY.at<float>(v, u) = pt.y;
        }
    }

    cv::Mat dst;
    cv::remap(src, dst, mapX, mapY, cv::INTER_CUBIC);
    return dst;
}
