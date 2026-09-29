#include "burst_fusion.h"
#include <vector>
#include <algorithm>
#include <cmath>

// ============================================================================
// 第一级：全局单应性粗配准 (ORB + RANSAC Homography)
// SPEC_12 §2.2 (升级为 cv::INTER_CUBIC 双三次插值保留极高频 MTF)
// ============================================================================
bool BurstFusionEngine::alignFrameHomography(const cv::Mat& src, const cv::Mat& ref, cv::Mat& outWarped, cv::Mat& outH) {
    if (src.empty() || ref.empty() || src.size() != ref.size()) {
        return false;
    }

    // 1. 降采样至最长边 960 以获得全局均匀特征且保证计算在数十毫秒内完成
    int maxDim = std::max(ref.cols, ref.rows);
    float scale = (maxDim > 960) ? 960.0f / maxDim : 1.0f;

    cv::Mat graySrc, grayRef;
    if (src.channels() == 3) cv::cvtColor(src, graySrc, cv::COLOR_BGR2GRAY);
    else graySrc = src;

    if (ref.channels() == 3) cv::cvtColor(ref, grayRef, cv::COLOR_BGR2GRAY);
    else grayRef = ref;

    cv::Mat smallSrc, smallRef;
    if (scale < 1.0f) {
        cv::resize(graySrc, smallSrc, cv::Size(), scale, scale, cv::INTER_AREA);
        cv::resize(grayRef, smallRef, cv::Size(), scale, scale, cv::INTER_AREA);
    } else {
        smallSrc = graySrc;
        smallRef = grayRef;
    }

    // 2. ORB 提取关键点
    cv::Ptr<cv::ORB> orb = cv::ORB::create(1200);
    std::vector<cv::KeyPoint> kpSrc, kpRef;
    cv::Mat descSrc, descRef;
    orb->detectAndCompute(smallSrc, cv::noArray(), kpSrc, descSrc);
    orb->detectAndCompute(smallRef, cv::noArray(), kpRef, descRef);

    if (descSrc.empty() || descRef.empty() || kpSrc.size() < 15 || kpRef.size() < 15) {
        return false;
    }

    // 3. 汉明距离交叉匹配
    cv::BFMatcher matcher(cv::NORM_HAMMING, true);
    std::vector<cv::DMatch> matches;
    matcher.match(descSrc, descRef, matches);

    if (matches.size() < 12) return false;

    std::sort(matches.begin(), matches.end(), [](const cv::DMatch& a, const cv::DMatch& b) {
        return a.distance < b.distance;
    });

    int goodCount = std::min(static_cast<int>(matches.size()), 180);
    std::vector<cv::Point2f> ptsSrc, ptsRef;
    ptsSrc.reserve(goodCount);
    ptsRef.reserve(goodCount);

    float invScale = (scale < 1.0f) ? (1.0f / scale) : 1.0f;
    for (int i = 0; i < goodCount; ++i) {
        ptsSrc.push_back(kpSrc[matches[i].queryIdx].pt * invScale);
        ptsRef.push_back(kpRef[matches[i].trainIdx].pt * invScale);
    }

    // 4. RANSAC 求解单应性变换矩阵并校验内点率
    cv::Mat inlierMask;
    cv::Mat H = cv::findHomography(ptsSrc, ptsRef, cv::RANSAC, 3.0, inlierMask);
    if (H.empty()) return false;

    int inlierCount = cv::countNonZero(inlierMask);
    if (inlierCount < 20 || static_cast<float>(inlierCount) / goodCount < 0.30f) {
        return false;
    }

    outH = H;
    // SPEC_12 §2.2: 升级为 cv::INTER_CUBIC 双三次插值，杜绝双线性低通模糊
    cv::warpPerspective(src, outWarped, H, ref.size(), cv::INTER_CUBIC, cv::BORDER_CONSTANT, cv::Scalar(0, 0, 0));
    return true;
}

// ============================================================================
// 高亮过渡区光度自适应增益比估算 (SPEC_12 §2.3)
// ============================================================================
float BurstFusionEngine::estimateHighlightAdaptationGain(
    const cv::Mat& bgrBase,
    const cv::Mat& bgrCand,
    const cv::Mat& validMask) {

    int rows = bgrBase.rows;
    int cols = bgrBase.cols;

    std::vector<float> ratios;
    ratios.reserve(5000);

    // 跨步采样高亮未溢出过渡区: Y0 in [210, 240]，辅助帧 Y1 >= 30 具备充足信噪比
    int step = std::max(1, static_cast<int>(std::sqrt((rows * cols) / 5000.0f)));
    for (int y = 0; y < rows; y += step) {
        const cv::Vec3b* pB = bgrBase.ptr<cv::Vec3b>(y);
        const cv::Vec3b* pC = bgrCand.ptr<cv::Vec3b>(y);
        const uchar* pV = validMask.ptr<uchar>(y);

        for (int x = 0; x < cols; x += step) {
            if (pV[x] == 0) continue;
            const cv::Vec3b& b0 = pB[x];
            const cv::Vec3b& bk = pC[x];

            // Rec. 601 亮度计算: 0.114*B + 0.587*G + 0.299*R
            float y0 = 0.114f * static_cast<float>(b0[0]) + 0.587f * static_cast<float>(b0[1]) + 0.299f * static_cast<float>(b0[2]);
            float y1 = 0.114f * static_cast<float>(bk[0]) + 0.587f * static_cast<float>(bk[1]) + 0.299f * static_cast<float>(bk[2]);

            if (y0 >= 210.0f && y0 <= 240.0f && y1 >= 30.0f) {
                ratios.push_back(y0 / y1);
            }
        }
    }

    if (ratios.size() < 50) {
        return 1.55f; // -2.0 EV 典型经验默认增益比
    }

    size_t midIdx = ratios.size() / 2;
    std::nth_element(ratios.begin(), ratios.begin() + midIdx, ratios.end());
    float medRatio = ratios[midIdx];

    return std::clamp(medRatio, 1.05f, 2.5f);
}

// ============================================================================
// 真正过曝饱和嫁接与高光微细节保真 HDR 融合总入口 (SPEC_12)
// ============================================================================
cv::Mat BurstFusionEngine::fuseBurstFrames(const std::vector<cv::Mat>& burstFrames, bool removeGlare, bool isScreenMode) {
    if (burstFrames.empty()) return cv::Mat();
    if (burstFrames.size() == 1) return burstFrames[0].clone();

    const cv::Mat& baseFrame = burstFrames[0];
    int rows = baseFrame.rows;
    int cols = baseFrame.cols;

    cv::Mat result = baseFrame.clone();

    // 对辅助帧逐一执行单应性粗对齐与高光单向嫁接 (SPEC_12)
    for (size_t k = 1; k < burstFrames.size(); ++k) {
        const cv::Mat& candFrame = burstFrames[k];
        cv::Mat candWarped, H;
        if (!alignFrameHomography(candFrame, baseFrame, candWarped, H)) {
            continue; // 单应性粗对齐失败跳过该帧
        }

        // 跟踪有效视野掩模 (边界外的填充黑边不参与任何融合)
        cv::Mat validMaskSrc = cv::Mat::ones(candFrame.size(), CV_8UC1) * 255;
        cv::Mat validMask;
        cv::warpPerspective(validMaskSrc, validMask, H, baseFrame.size(), cv::INTER_NEAREST, cv::BORDER_CONSTANT, cv::Scalar(0));

        // 估算高光过渡区光度自适应增益比 (alpha)
        float alpha = estimateHighlightAdaptationGain(baseFrame, candWarped, validMask);

        // 遍历所有像素，执行真正物理过曝平滑饱和嫁接 (SPEC_12 §2.1 & §2.3)
        // 彻底杜绝 8位 Lab 转换量化损失，直接在 BGR 通道进行保真自适应混合
        for (int y = 0; y < rows; ++y) {
            const cv::Vec3b* pBase = baseFrame.ptr<cv::Vec3b>(y);
            const cv::Vec3b* pCandWarped = candWarped.ptr<cv::Vec3b>(y);
            const uchar* pValid = validMask.ptr<uchar>(y);
            cv::Vec3b* pDst = result.ptr<cv::Vec3b>(y);

            for (int x = 0; x < cols; ++x) {
                if (pValid[x] == 0) continue;

                const cv::Vec3b& b0 = pBase[x];
                float y0 = 0.114f * static_cast<float>(b0[0]) + 0.587f * static_cast<float>(b0[1]) + 0.299f * static_cast<float>(b0[2]);

                // 绝对基准帧保护区 (SPEC_12 §2.1):
                // 覆盖 99% 以上画面 (阴影、中间调、树木、建筑、室内白墙、门牌号码、打印文档等)
                // 100% 严格锁定基准帧像素，严禁任何辅助帧像素混合！
                if (y0 < 242.0f) {
                    continue;
                }

                // 过曝平滑过渡区 [242, 252]: 三次 Hermite 样条 (Smoothstep) 保证一阶连续导数
                float u = std::clamp((y0 - 242.0f) / 10.0f, 0.0f, 1.0f);
                float m = 3.0f * u * u - 2.0f * u * u * u;

                const cv::Vec3b& bk = pCandWarped[x];
                for (int c = 0; c < 3; ++c) {
                    float v0 = static_cast<float>(b0[c]);
                    float vkAdapted = std::min(v0, alpha * static_cast<float>(bk[c]));
                    pDst[x][c] = cv::saturate_cast<uchar>((1.0f - m) * v0 + m * vkAdapted);
                }
            }
        }
    }

    return result;
}
