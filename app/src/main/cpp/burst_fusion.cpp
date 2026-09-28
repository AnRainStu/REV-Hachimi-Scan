#include "burst_fusion.h"
#include <opencv2/photo.hpp>
#include <vector>
#include <algorithm>
#include <cmath>

bool BurstFusionEngine::alignFrame(const cv::Mat& src, const cv::Mat& ref, cv::Mat& outAligned) {
    if (src.empty() || ref.empty() || src.size() != ref.size()) {
        return false;
    }

    // 1. 降采样用于快速且全局均匀的特征检测 (最长边960以保证高分辨率细节)
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

    // 2. ORB 提取充足的关键点 (1000点以覆盖文档上下全部区域)
    cv::Ptr<cv::ORB> orb = cv::ORB::create(1000);
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
    cv::Mat H = cv::findHomography(ptsSrc, ptsRef, cv::RANSAC, 2.5, inlierMask);
    if (H.empty()) return false;

    int inlierCount = cv::countNonZero(inlierMask);
    if (inlierCount < 20 || static_cast<float>(inlierCount) / goodCount < 0.35f) {
        // 内点过少，说明两帧存在非刚体形变或剧烈晃动，拒绝错误对齐
        return false;
    }

    // 5. 将辅助帧严格变换对齐至基准帧像素网格
    cv::warpPerspective(src, outAligned, H, ref.size(), cv::INTER_LINEAR);
    return true;
}

cv::Mat BurstFusionEngine::fuseBurstFrames(const std::vector<cv::Mat>& burstFrames, bool removeGlare, bool isScreenMode) {
    if (burstFrames.empty()) return cv::Mat();
    if (burstFrames.size() == 1) return burstFrames[0].clone();

    const cv::Mat& baseFrame = burstFrames[0];
    std::vector<cv::Mat> alignedFrames;
    alignedFrames.push_back(baseFrame);

    // 将其余帧逐一精确配准到基准帧 (SPEC_08 亚像素软件防抖与单应性对齐)
    for (size_t i = 1; i < burstFrames.size(); ++i) {
        cv::Mat aligned;
        if (alignFrame(burstFrames[i], baseFrame, aligned)) {
            alignedFrames.push_back(aligned);
        }
    }

    // 若配准成功的帧少于2帧，直接回退单张基准帧
    if (alignedFrames.size() == 1) {
        return baseFrame.clone();
    }

    int rows = baseFrame.rows;
    int cols = baseFrame.cols;
    size_t numFrames = alignedFrames.size();

    // 1. 如果开启了屏幕 HDR 模式且有多帧对齐，优先使用 Tom Mertens 多分辨率曝光融合 (SPEC_08)
    bool mertensSuccess = false;
    cv::Mat mertensFused;
    if (isScreenMode && numFrames >= 2) {
        try {
            // Mertens 算法通过高斯/拉普拉斯金字塔在对比度、饱和度与良好曝光度三个维度进行多尺度无缝融合
            cv::Ptr<cv::MergeMertens> mergeMertens = cv::createMergeMertens(1.0f, 1.0f, 1.0f);
            cv::Mat fusion32F;
            mergeMertens->process(alignedFrames, fusion32F);
            if (!fusion32F.empty() && fusion32F.rows == rows && fusion32F.cols == cols) {
                fusion32F.convertTo(mertensFused, CV_8UC3, 255.0);
                mertensSuccess = true;
            }
        } catch (...) {
            mertensSuccess = false;
        }
    }

    // 2. 针对屏幕死白/高光区域实施硬性高光补丁覆盖 (Hard Specular Glare Replacement)
    if (mertensSuccess) {
        if (removeGlare) {
            for (int y = 0; y < rows; ++y) {
                uchar* pDst = mertensFused.ptr<uchar>(y);
                const uchar* pBase = baseFrame.ptr<uchar>(y);

                for (int x = 0; x < cols; ++x) {
                    int pxOffset = x * 3;
                    uchar b0 = pBase[pxOffset];
                    uchar g0 = pBase[pxOffset + 1];
                    uchar r0 = pBase[pxOffset + 2];

                    // 基准帧过曝死白截断 (RGB 均 > 238)
                    bool isSaturated = (b0 > 238 && g0 > 238 && r0 > 238);
                    if (isSaturated) {
                        uchar minB = 255, minG = 255, minR = 255;
                        bool foundClearText = false;
                        for (size_t k = 1; k < numFrames; ++k) {
                            const uchar* pSrc = alignedFrames[k].ptr<uchar>(y);
                            uchar bk = pSrc[pxOffset];
                            uchar gk = pSrc[pxOffset + 1];
                            uchar rk = pSrc[pxOffset + 2];
                            // 在低曝光帧中找到了未饱和的清晰笔迹细节
                            if (bk < 230 || gk < 230 || rk < 230) {
                                minB = std::min(minB, bk);
                                minG = std::min(minG, gk);
                                minR = std::min(minR, rk);
                                foundClearText = true;
                            }
                        }
                        if (foundClearText) {
                            uchar dstB = pDst[pxOffset];
                            uchar dstG = pDst[pxOffset + 1];
                            uchar dstR = pDst[pxOffset + 2];
                            // 若融合结果依然偏亮，强制使用未饱和清晰样本覆盖
                            if (dstB > 220 && dstG > 220 && dstR > 220) {
                                pDst[pxOffset] = minB;
                                pDst[pxOffset + 1] = minG;
                                pDst[pxOffset + 2] = minR;
                            }
                        }
                    }
                }
            }
        }
        return mertensFused;
    }

    // 3. 降级保护路径 (Graceful Degradation per SPEC_08 Section 5.2):
    // 像素级防鬼影 (Motion De-ghosting, 色差阈值 22) 与加权中值去反光融合流水线
    cv::Mat result(rows, cols, CV_8UC3);
    const int GHOST_DIFF_THRESHOLD = 22;

    std::vector<uchar> validB, validG, validR;
    validB.reserve(numFrames);
    validG.reserve(numFrames);
    validR.reserve(numFrames);

    for (int y = 0; y < rows; ++y) {
        uchar* pDst = result.ptr<uchar>(y);
        const uchar* pBase = baseFrame.ptr<uchar>(y);

        for (int x = 0; x < cols; ++x) {
            int pxOffset = x * 3;

            uchar b0 = pBase[pxOffset];
            uchar g0 = pBase[pxOffset + 1];
            uchar r0 = pBase[pxOffset + 2];

            // 智能反光与发光屏幕死白置换
            bool isGlare = (b0 > 238 && g0 > 238 && r0 > 238);
            if (removeGlare && isGlare) {
                uchar minB = 255, minG = 255, minR = 255;
                bool foundClearText = false;
                for (size_t k = 1; k < numFrames; ++k) {
                    const uchar* pSrc = alignedFrames[k].ptr<uchar>(y);
                    uchar bk = pSrc[pxOffset];
                    uchar gk = pSrc[pxOffset + 1];
                    uchar rk = pSrc[pxOffset + 2];
                    if (bk < 230 || gk < 230 || rk < 230) {
                        minB = std::min(minB, bk);
                        minG = std::min(minG, gk);
                        minR = std::min(minR, rk);
                        foundClearText = true;
                    }
                }
                if (foundClearText) {
                    pDst[pxOffset] = minB;
                    pDst[pxOffset + 1] = minG;
                    pDst[pxOffset + 2] = minR;
                    continue;
                }
            }

            // 像素级防鬼影 (Motion De-ghosting)
            validB.clear();
            validG.clear();
            validR.clear();

            validB.push_back(b0);
            validG.push_back(g0);
            validR.push_back(r0);

            for (size_t k = 1; k < numFrames; ++k) {
                const uchar* pSrc = alignedFrames[k].ptr<uchar>(y);
                uchar bk = pSrc[pxOffset];
                uchar gk = pSrc[pxOffset + 1];
                uchar rk = pSrc[pxOffset + 2];

                int diff = (std::abs(static_cast<int>(bk) - b0) +
                            std::abs(static_cast<int>(gk) - g0) +
                            std::abs(static_cast<int>(rk) - r0)) / 3;

                // 若该帧当前像素存在由于微动或局部非刚性形变导致的色差过大（鬼影），直接剔除该帧采样点！
                if (diff <= GHOST_DIFF_THRESHOLD) {
                    validB.push_back(bk);
                    validG.push_back(gk);
                    validR.push_back(rk);
                }
            }

            // 若只有基准帧自身有效，严格保留基准帧的高清真迹
            if (validB.size() == 1) {
                pDst[pxOffset] = b0;
                pDst[pxOffset + 1] = g0;
                pDst[pxOffset + 2] = r0;
            } else {
                std::sort(validB.begin(), validB.end());
                std::sort(validG.begin(), validG.end());
                std::sort(validR.begin(), validR.end());

                size_t midIdx = validB.size() / 2;
                pDst[pxOffset] = validB[midIdx];
                pDst[pxOffset + 1] = validG[midIdx];
                pDst[pxOffset + 2] = validR[midIdx];
            }
        }
    }

    return result;
}
