#include "burst_fusion.h"
#include <vector>
#include <algorithm>
#include <cmath>

// ============================================================================
// 第一级：全局单应性粗配准 (ORB + RANSAC Homography)
// SPEC_10 §2 阶段一
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
    cv::warpPerspective(src, outWarped, H, ref.size(), cv::INTER_LINEAR);
    return true;
}

// ============================================================================
// 高光接缝过渡区光度自适应增益比估算 (SPEC_10 §3.2)
// ============================================================================
float BurstFusionEngine::estimateHighlightAdaptationGain(
    const cv::Mat& labBase,
    const cv::Mat& labCand,
    const cv::Mat& validMask) {

    int rows = labBase.rows;
    int cols = labBase.cols;

    std::vector<float> ratios;
    ratios.reserve(5000);

    // 跨步采样接缝过渡区 (Y0 in [170, 215])，两帧均未完全死白且信噪比高
    int step = std::max(1, static_cast<int>(std::sqrt((rows * cols) / 5000.0f)));
    for (int y = 0; y < rows; y += step) {
        const cv::Vec3b* pB = labBase.ptr<cv::Vec3b>(y);
        const cv::Vec3b* pC = labCand.ptr<cv::Vec3b>(y);
        const uchar* pV = validMask.ptr<uchar>(y);

        for (int x = 0; x < cols; x += step) {
            if (pV[x] == 0) continue;
            uchar l0 = pB[x][0];
            uchar l1 = pC[x][0];
            if (l0 >= 170 && l0 <= 215 && l1 >= 30) {
                ratios.push_back(static_cast<float>(l0) / static_cast<float>(l1));
            }
        }
    }

    if (ratios.size() < 50) {
        return 1.55f; // -2.0 EV 典型 Gamma 压缩空间增益比默认经验值
    }

    size_t midIdx = ratios.size() / 2;
    std::nth_element(ratios.begin(), ratios.begin() + midIdx, ratios.end());
    float medRatio = ratios[midIdx];

    return std::clamp(medRatio, 1.05f, 2.5f);
}

// ============================================================================
// 基准帧绝对锁定与高光单向嫁接零重影 HDR 融合总入口 (SPEC_10)
// ============================================================================
cv::Mat BurstFusionEngine::fuseBurstFrames(const std::vector<cv::Mat>& burstFrames, bool removeGlare, bool isScreenMode) {
    if (burstFrames.empty()) return cv::Mat();
    if (burstFrames.size() == 1) return burstFrames[0].clone();

    const cv::Mat& baseFrame = burstFrames[0];
    int rows = baseFrame.rows;
    int cols = baseFrame.cols;

    cv::Mat result = baseFrame.clone();

    // 转换基准帧到 CIE-Lab 色彩空间
    cv::Mat labBase;
    cv::cvtColor(baseFrame, labBase, cv::COLOR_BGR2Lab);

    // 对辅助帧逐一执行单应性粗对齐与高光单向嫁接 (SPEC_10)
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

        // 辅助帧转 CIE-Lab
        cv::Mat labCand;
        cv::cvtColor(candWarped, labCand, cv::COLOR_BGR2Lab);

        // 估算高光过渡区光度自适应增益比 (alpha)
        float alpha = estimateHighlightAdaptationGain(labBase, labCand, validMask);

        // 构建嫁接 Lab 图像
        cv::Mat labGrafted = labBase.clone();

        for (int y = 0; y < rows; ++y) {
            const cv::Vec3b* pBaseLab = labBase.ptr<cv::Vec3b>(y);
            const cv::Vec3b* pCandLab = labCand.ptr<cv::Vec3b>(y);
            const uchar* pValid = validMask.ptr<uchar>(y);
            cv::Vec3b* pGraftLab = labGrafted.ptr<cv::Vec3b>(y);

            for (int x = 0; x < cols; ++x) {
                if (pValid[x] == 0) continue;

                uchar l0 = pBaseLab[x][0];

                // 树木、建筑与暗部中间调绝对保护律 (SPEC_10 §3.1)
                // Y_0 <= 190 时 M(x, y) 恒为 0，100% 锁死基准帧！
                if (l0 <= 190) {
                    continue;
                }

                // 过渡区与高光嫁接区权重计算: smoothstep Hermite 曲线 (190 < Y_0 < 235)
                float m = 1.0f;
                if (l0 < 235) {
                    float u = (static_cast<float>(l0) - 190.0f) / 45.0f;
                    m = 3.0f * u * u - 2.0f * u * u * u;
                }

                uchar l1 = pCandLab[x][0];
                float l1Adapted = std::min(static_cast<float>(l0), std::clamp(alpha * static_cast<float>(l1), 0.0f, 255.0f));

                float lFinal = (1.0f - m) * static_cast<float>(l0) + m * l1Adapted;

                uchar a0 = pBaseLab[x][1];
                uchar b0 = pBaseLab[x][2];
                uchar a1 = pCandLab[x][1];
                uchar b1 = pCandLab[x][2];

                float aFinal = (1.0f - m) * static_cast<float>(a0) + m * static_cast<float>(a1);
                float bFinal = (1.0f - m) * static_cast<float>(b0) + m * static_cast<float>(b1);

                pGraftLab[x] = cv::Vec3b(
                    cv::saturate_cast<uchar>(lFinal),
                    cv::saturate_cast<uchar>(aFinal),
                    cv::saturate_cast<uchar>(bFinal)
                );
            }
        }

        // 将嫁接完成的高光 Lab 图像转换回 BGR
        cv::Mat bgrGrafted;
        cv::cvtColor(labGrafted, bgrGrafted, cv::COLOR_Lab2BGR);

        // 仅在 Y_0 > 190 的高光及过渡像素应用嫁接，暗部与中间调严格零读写
        for (int y = 0; y < rows; ++y) {
            const cv::Vec3b* pBaseLab = labBase.ptr<cv::Vec3b>(y);
            const cv::Vec3b* pBaseBgr = baseFrame.ptr<cv::Vec3b>(y);
            const cv::Vec3b* pGraftBgr = bgrGrafted.ptr<cv::Vec3b>(y);
            const uchar* pValid = validMask.ptr<uchar>(y);
            cv::Vec3b* pDst = result.ptr<cv::Vec3b>(y);

            for (int x = 0; x < cols; ++x) {
                if (pValid[x] == 0) continue;

                uchar l0 = pBaseLab[x][0];
                if (l0 <= 190) {
                    continue; // 树枝与全图暗部严格 100% 保持基准帧不变，零浮雕、零描边
                }

                float m = 1.0f;
                if (l0 < 235) {
                    float u = (static_cast<float>(l0) - 190.0f) / 45.0f;
                    m = 3.0f * u * u - 2.0f * u * u * u;
                }

                const cv::Vec3b& cBase = pBaseBgr[x];
                const cv::Vec3b& cGraft = pGraftBgr[x];

                pDst[x][0] = cv::saturate_cast<uchar>((1.0f - m) * cBase[0] + m * cGraft[0]);
                pDst[x][1] = cv::saturate_cast<uchar>((1.0f - m) * cBase[1] + m * cGraft[1]);
                pDst[x][2] = cv::saturate_cast<uchar>((1.0f - m) * cBase[2] + m * cGraft[2]);
            }
        }

        // 高光反光/屏幕死白深度置换 (Hard Specular Glare Replacement)
        if (removeGlare) {
            for (int y = 0; y < rows; ++y) {
                cv::Vec3b* pDst = result.ptr<cv::Vec3b>(y);
                const cv::Vec3b* pBase = baseFrame.ptr<cv::Vec3b>(y);
                const cv::Vec3b* pSrc = candWarped.ptr<cv::Vec3b>(y);
                const uchar* pValid = validMask.ptr<uchar>(y);

                for (int x = 0; x < cols; ++x) {
                    if (pValid[x] == 0) continue;

                    const cv::Vec3b& b0 = pBase[x];
                    bool isSaturated = (b0[0] > 238 && b0[1] > 238 && b0[2] > 238);
                    if (isSaturated) {
                        const cv::Vec3b& bk = pSrc[x];
                        if (bk[0] < 230 || bk[1] < 230 || bk[2] < 230) {
                            const cv::Vec3b& dst = pDst[x];
                            if (dst[0] > 220 && dst[1] > 220 && dst[2] > 220) {
                                pDst[x] = bk;
                            }
                        }
                    }
                }
            }
        }
    }

    return result;
}
