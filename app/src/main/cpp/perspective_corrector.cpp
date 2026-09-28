#include "perspective_corrector.h"
#include <cmath>
#include <algorithm>

cv::Mat PerspectiveCorrector::correctPerspective(const cv::Mat& src, const std::vector<cv::Point2f>& srcCorners, float targetAspectRatio) {
    if (src.empty() || srcCorners.size() != 4) {
        return src;
    }

    const cv::Point2f& tl = srcCorners[0];
    const cv::Point2f& tr = srcCorners[1];
    const cv::Point2f& br = srcCorners[2];
    const cv::Point2f& bl = srcCorners[3];

    double widthA = std::hypot(br.x - bl.x, br.y - bl.y);
    double widthB = std::hypot(tr.x - tl.x, tr.y - tl.y);
    double heightA = std::hypot(tr.x - br.x, tr.y - br.y);
    double heightB = std::hypot(tl.x - bl.x, tl.y - bl.y);

    double maxW = std::max(widthA, widthB);
    double minW = std::min(widthA, widthB);
    double maxH = std::max(heightA, heightB);
    double minH = std::min(heightA, heightB);

    int maxWidth = std::max(10, static_cast<int>(std::round(maxW)));
    int maxHeight = std::max(10, static_cast<int>(std::round(maxH)));

    if (targetAspectRatio > 0.01f) {
        // Enforce physical paper aspect ratio (W / H)
        float effRatio = targetAspectRatio;
        bool isLandscape = maxW > maxH;
        if (isLandscape) {
            if (effRatio < 1.0f) effRatio = 1.0f / effRatio;
        } else {
            if (effRatio > 1.0f) effRatio = 1.0f / effRatio;
        }

        double wFromH = maxH * effRatio;
        if (wFromH > maxW) {
            maxWidth = std::max(10, static_cast<int>(std::round(wFromH)));
            maxHeight = std::max(10, static_cast<int>(std::round(maxH)));
        } else {
            maxWidth = std::max(10, static_cast<int>(std::round(maxW)));
            maxHeight = std::max(10, static_cast<int>(std::round(maxWidth / effRatio)));
        }
    } else {
        // Free mode: Projective foreshortening vertical recovery estimation
        // Document camera tilt causes perspective convergence of opposite sides and foreshortening along tilt axis.
        double ratioW = (maxW > 1.0) ? (minW / maxW) : 1.0;
        double ratioH = (maxH > 1.0) ? (minH / maxH) : 1.0;

        if (ratioW < ratioH && ratioW > 0.1) {
            // Vertical foreshortening recovery factor: gamma_v = sqrt(1 / ratioW)
            double gammaV = std::clamp(std::sqrt(1.0 / ratioW), 1.0, 1.6);
            maxHeight = std::max(10, static_cast<int>(std::round(maxH * gammaV)));
        } else if (ratioH < ratioW && ratioH > 0.1) {
            // Horizontal foreshortening recovery factor: gamma_h = sqrt(1 / ratioH)
            double gammaH = std::clamp(std::sqrt(1.0 / ratioH), 1.0, 1.6);
            maxWidth = std::max(10, static_cast<int>(std::round(maxW * gammaH)));
        }
    }

    std::vector<cv::Point2f> dstCorners = {
        cv::Point2f(0.0f, 0.0f),
        cv::Point2f(static_cast<float>(maxWidth - 1), 0.0f),
        cv::Point2f(static_cast<float>(maxWidth - 1), static_cast<float>(maxHeight - 1)),
        cv::Point2f(0.0f, static_cast<float>(maxHeight - 1))
    };

    cv::Mat transformMatrix = cv::getPerspectiveTransform(srcCorners, dstCorners);
    cv::Mat warped;
    cv::warpPerspective(src, warped, transformMatrix, cv::Size(maxWidth, maxHeight), cv::INTER_LINEAR);

    return warped;
}

cv::Mat PerspectiveCorrector::enhanceMagicColor(const cv::Mat& src) {
    if (src.empty()) return src;

    // 1. 色彩解耦：转换至 CIE-Lab 空间，仅处理 L 亮度通道，保护 a,b 色度 (红色印章/彩色笔迹)
    cv::Mat lab;
    cv::cvtColor(src, lab, cv::COLOR_BGR2Lab);

    std::vector<cv::Mat> channels(3);
    cv::split(lab, channels);
    cv::Mat& L = channels[0];

    // 2. 快速低频照度场估计 (Retinex 照度除法流水线):
    // 降采样至 480px 长边估计全局平滑光照，避免在大图上进行数百毫秒的大核膨胀
    int maxDim = std::max(L.cols, L.rows);
    int targetSmall = 480;
    float scale = (maxDim > targetSmall) ? (static_cast<float>(targetSmall) / maxDim) : 1.0f;

    cv::Mat smallL;
    if (scale < 1.0f) {
        cv::resize(L, smallL, cv::Size(), scale, scale, cv::INTER_AREA);
    } else {
        smallL = L.clone();
    }

    int smallKernel = std::max(11, (std::max(smallL.cols, smallL.rows) / 25) | 1);
    cv::Mat strElem = cv::getStructuringElement(cv::MORPH_RECT, cv::Size(smallKernel, smallKernel));
    cv::Mat smallDilated;
    cv::dilate(smallL, smallDilated, strElem); // 快速吞噬文字笔画提取底色

    cv::Mat smallBg;
    int blurKernel = std::min(25, smallKernel | 1);
    cv::medianBlur(smallDilated, smallBg, blurKernel);

    cv::Mat bgIllumination;
    if (scale < 1.0f) {
        cv::resize(smallBg, bgIllumination, L.size(), 0, 0, cv::INTER_LINEAR);
    } else {
        bgIllumination = smallBg;
    }

    // 3. 照度除法：R = (S / L) * 255.0
    cv::Mat L_f, bg_f;
    L.convertTo(L_f, CV_32F);
    bgIllumination.convertTo(bg_f, CV_32F);
    cv::max(bg_f, 1.0f, bg_f); // 防除以零

    cv::Mat corrected_f = (L_f / bg_f) * 255.0f;

    // 4. 白底截断与动态对比度拉伸 (Background Pure-White Clamping)
    float whiteThreshold = 225.0f;
    float scaleFactor = 255.0f / whiteThreshold;

    cv::Mat L_out(L.size(), CV_8UC1);
    int rows = L.rows;
    int cols = L.cols;

    if (corrected_f.isContinuous() && L_out.isContinuous()) {
        cols *= rows;
        rows = 1;
    }

    for (int r = 0; r < rows; ++r) {
        const float* pIn = corrected_f.ptr<float>(r);
        uchar* pOut = L_out.ptr<uchar>(r);
        for (int c = 0; c < cols; ++c) {
            float val = pIn[c];
            if (val >= whiteThreshold) {
                pOut[c] = 255;
            } else {
                pOut[c] = static_cast<uchar>(std::clamp(val * scaleFactor, 0.0f, 255.0f));
            }
        }
    }

    // 5. 轻度非局部锐化（增强文字边缘骨力）
    cv::Mat blurred;
    cv::GaussianBlur(L_out, blurred, cv::Size(0, 0), 1.2);
    cv::addWeighted(L_out, 1.25, blurred, -0.25, 0, channels[0]);

    // 6. 重组色彩空间并还原 BGR
    cv::Mat resultLab, resultBGR;
    cv::merge(channels, resultLab);
    cv::cvtColor(resultLab, resultBGR, cv::COLOR_Lab2BGR);

    return resultBGR;
}

cv::Mat PerspectiveCorrector::enhanceSauvola(const cv::Mat& src, int windowSize, double k, double r, double darkenFactor) {
    if (src.empty()) return src;

    cv::Mat gray;
    if (src.channels() == 3) {
        cv::cvtColor(src, gray, cv::COLOR_BGR2GRAY);
    } else {
        gray = src.clone();
    }

    int rows = gray.rows;
    int cols = gray.cols;

    // 自适应调整窗口尺寸（约占短边 3%~5%）
    int maxDim = std::max(rows, cols);
    int w = std::max(21, (maxDim / 40) | 1);
    int halfW = w / 2;

    // 利用积分图 (Integral Image) 实现 O(1) 极速局部均值与方差计算
    cv::Mat sum, sqsum;
    cv::integral(gray, sum, sqsum, CV_64F, CV_64F);

    cv::Mat dst(rows, cols, CV_8UC1);

    for (int y = 0; y < rows; ++y) {
        int y1 = std::max(0, y - halfW);
        int y2 = std::min(rows - 1, y + halfW);

        const uchar* pGray = gray.ptr<uchar>(y);
        uchar* pDst = dst.ptr<uchar>(y);

        const double* pSumY1 = sum.ptr<double>(y1);
        const double* pSumY2 = sum.ptr<double>(y2 + 1);
        const double* pSqsumY1 = sqsum.ptr<double>(y1);
        const double* pSqsumY2 = sqsum.ptr<double>(y2 + 1);

        for (int x = 0; x < cols; ++x) {
            int x1 = std::max(0, x - halfW);
            int x2 = std::min(cols - 1, x + halfW);

            int count = (x2 - x1 + 1) * (y2 - y1 + 1);

            // 积分图采样矩形和 (修复 sqsum 差分中加回 sqsum(y1, x1) 的 bug)
            double s = pSumY2[x2 + 1] - pSumY1[x2 + 1] - pSumY2[x1] + pSumY1[x1];
            double sq = pSqsumY2[x2 + 1] - pSqsumY1[x2 + 1] - pSqsumY2[x1] + pSqsumY1[x1];

            double mean = s / count;
            double variance = std::max(0.0, (sq / count) - (mean * mean));
            double stddev = std::sqrt(variance);

            // Sauvola 阈值计算公式 (SPEC_02)
            double threshold = mean * (1.0 + k * ((stddev / r) - 1.0));

            uchar pixel = pGray[x];
            if (pixel >= threshold) {
                pDst[x] = 255; // 纸张纯白
            } else {
                // 文字加深
                pDst[x] = static_cast<uchar>(std::clamp(pixel * darkenFactor, 0.0, 255.0));
            }
        }
    }

    cv::Mat resultBGR;
    cv::cvtColor(dst, resultBGR, cv::COLOR_GRAY2BGR);
    return resultBGR;
}

cv::Mat PerspectiveCorrector::enhanceGrayscale(const cv::Mat& src) {
    if (src.empty()) return src;

    cv::Mat gray;
    if (src.channels() == 3) {
        cv::cvtColor(src, gray, cv::COLOR_BGR2GRAY);
    } else {
        gray = src.clone();
    }

    // 轻度 CLAHE 提升灰度文档对比度
    cv::Ptr<cv::CLAHE> clahe = cv::createCLAHE(2.0, cv::Size(8, 8));
    cv::Mat enhanced;
    clahe->apply(gray, enhanced);

    cv::Mat resultBGR;
    cv::cvtColor(enhanced, resultBGR, cv::COLOR_GRAY2BGR);
    return resultBGR;
}

cv::Mat PerspectiveCorrector::processDocument(const cv::Mat& src, const std::vector<cv::Point2f>& srcCorners, FilterType filter, float targetAspectRatio) {
    cv::Mat warped = correctPerspective(src, srcCorners, targetAspectRatio);
    if (warped.empty()) return src;

    switch (filter) {
        case FilterType::MAGIC_COLOR:
            return enhanceMagicColor(warped);
        case FilterType::BW_SAUVOLA:
            return enhanceSauvola(warped);
        case FilterType::GRAYSCALE:
            return enhanceGrayscale(warped);
        case FilterType::ORIGINAL:
        default:
            return warped;
    }
}
