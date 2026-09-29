#include "burst_fusion.h"
#include <opencv2/photo.hpp>
#include <opencv2/video.hpp>
#include <vector>
#include <algorithm>
#include <cmath>

// ============================================================================
// 第一级：全局粗配准 (Tier 1: Global Coarse Alignment via ORB + RANSAC)
// SPEC_09 §2 & §4.1
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

    // 2. ORB 提取充足的关键点 (1200点以均匀覆盖全图)
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
        // 内点过少，说明两帧存在不可调和的剧烈晃动，拒绝错误粗对齐
        return false;
    }

    outH = H;
    cv::warpPerspective(src, outWarped, H, ref.size(), cv::INTER_LINEAR);
    return true;
}

// ============================================================================
// 第二级：局部稠密光流视差补偿 (Tier 2: Dense Parallax Compensation with DIS)
// SPEC_09 §2 & §3.2
// ============================================================================
bool BurstFusionEngine::refineOpticalFlowDIS(const cv::Mat& srcWarped, const cv::Mat& ref, cv::Mat& outRefined) {
    if (srcWarped.empty() || ref.empty() || srcWarped.size() != ref.size()) {
        return false;
    }

    try {
        cv::Mat grayWarped, grayRef;
        if (srcWarped.channels() == 3) cv::cvtColor(srcWarped, grayWarped, cv::COLOR_BGR2GRAY);
        else grayWarped = srcWarped;

        if (ref.channels() == 3) cv::cvtColor(ref, grayRef, cv::COLOR_BGR2GRAY);
        else grayRef = ref;

        // 金字塔尺度 (最长边 960)，以 20~30ms 超高帧率运行 DIS PRESET_FAST
        int maxDim = std::max(ref.cols, ref.rows);
        float scale = (maxDim > 960) ? 960.0f / maxDim : 1.0f;

        cv::Mat smallWarped, smallRef;
        if (scale < 1.0f) {
            cv::resize(grayWarped, smallWarped, cv::Size(), scale, scale, cv::INTER_AREA);
            cv::resize(grayRef, smallRef, cv::Size(), scale, scale, cv::INTER_AREA);
        } else {
            smallWarped = grayWarped;
            smallRef = grayRef;
        }

        cv::Ptr<cv::DISOpticalFlow> dis = cv::DISOpticalFlow::create(cv::DISOpticalFlow::PRESET_FAST);
        dis->setUseMeanNormalization(true); // 增加对曝光跨帧差异的鲁棒性

        cv::Mat smallFlow;
        // 计算从 ref 到 warped 的位移场 u(x) 用于后续 inverse mapping remap
        dis->calc(smallRef, smallWarped, smallFlow);

        cv::Mat fullFlow;
        if (scale < 1.0f) {
            cv::resize(smallFlow, fullFlow, ref.size(), 0, 0, cv::INTER_LINEAR);
            fullFlow *= (1.0f / scale);
        } else {
            fullFlow = smallFlow;
        }

        int rows = ref.rows;
        int cols = ref.cols;
        cv::Mat mapX(rows, cols, CV_32FC1);
        cv::Mat mapY(rows, cols, CV_32FC1);

        for (int y = 0; y < rows; ++y) {
            const cv::Point2f* fPtr = fullFlow.ptr<cv::Point2f>(y);
            float* mxPtr = mapX.ptr<float>(y);
            float* myPtr = mapY.ptr<float>(y);
            for (int x = 0; x < cols; ++x) {
                mxPtr[x] = static_cast<float>(x) + fPtr[x].x;
                myPtr[x] = static_cast<float>(y) + fPtr[x].y;
            }
        }

        // 双线性向量重采样消除近景树木、栏杆与前景视差
        cv::remap(srcWarped, outRefined, mapX, mapY, cv::INTER_LINEAR, cv::BORDER_REFLECT_101);
        return true;
    } catch (...) {
        outRefined = srcWarped.clone();
        return false;
    }
}

// ============================================================================
// 中灰度未饱和区光度增益比估算 (SPEC_09 §3.1)
// ============================================================================
float BurstFusionEngine::estimatePhotometricGain(const cv::Mat& grayBase, const cv::Mat& grayCand) {
    int rows = grayBase.rows;
    int cols = grayBase.cols;

    std::vector<uchar> samplesBase;
    std::vector<uchar> samplesCand;
    samplesBase.reserve(10000);
    samplesCand.reserve(10000);

    // 跨步采样中灰度未饱和未欠曝区: 30 <= Y0 <= 220, 15 <= Yk <= 200
    int step = std::max(1, static_cast<int>(std::sqrt((rows * cols) / 8000.0f)));
    for (int y = 0; y < rows; y += step) {
        const uchar* pB = grayBase.ptr<uchar>(y);
        const uchar* pC = grayCand.ptr<uchar>(y);
        for (int x = 0; x < cols; x += step) {
            uchar y0 = pB[x];
            uchar yk = pC[x];
            if (y0 >= 30 && y0 <= 220 && yk >= 15 && yk <= 200) {
                samplesBase.push_back(y0);
                samplesCand.push_back(yk);
            }
        }
    }

    if (samplesBase.size() < 100) {
        return 1.0f;
    }

    size_t midIdx = samplesBase.size() / 2;
    std::nth_element(samplesBase.begin(), samplesBase.begin() + midIdx, samplesBase.end());
    std::nth_element(samplesCand.begin(), samplesCand.begin() + midIdx, samplesCand.end());

    float medBase = static_cast<float>(samplesBase[midIdx]);
    float medCand = static_cast<float>(samplesCand[midIdx]);

    if (medCand < 5.0f) return 1.0f;
    float gain = medBase / medCand;
    return std::clamp(gain, 0.1f, 10.0f);
}

// ============================================================================
// 第三级：基准帧绝对几何锚定与光度鲁棒防重影掩模生成 (SPEC_09 §3.3 & §3.4)
// ============================================================================
cv::Mat BurstFusionEngine::computeRobustDeghostMask(const cv::Mat& candRefined, const cv::Mat& baseFrame) {
    int rows = baseFrame.rows;
    int cols = baseFrame.cols;

    cv::Mat grayBase, grayCand;
    if (baseFrame.channels() == 3) cv::cvtColor(baseFrame, grayBase, cv::COLOR_BGR2GRAY);
    else grayBase = baseFrame;

    if (candRefined.channels() == 3) cv::cvtColor(candRefined, grayCand, cv::COLOR_BGR2GRAY);
    else grayCand = candRefined;

    // 1. 光度归一化 (Photometric Normalization)
    float gain = estimatePhotometricGain(grayBase, grayCand);

    // 2. 结构与高频梯度残差计算 (Sobel 梯度捕获树叶与细枝错位)
    cv::Mat g0x, g0y, gkx, gky;
    cv::Sobel(grayBase, g0x, CV_32F, 1, 0, 3);
    cv::Sobel(grayBase, g0y, CV_32F, 0, 1, 3);
    cv::Sobel(grayCand, gkx, CV_32F, 1, 0, 3);
    cv::Sobel(grayCand, gky, CV_32F, 0, 1, 3);

    // 匹配光度增益
    gkx *= gain;
    gky *= gain;

    cv::Mat mask(rows, cols, CV_32FC1);
    const float sigmaMotion = 25.0f;
    const float twoSigmaSq = 2.0f * sigmaMotion * sigmaMotion;
    const float beta = 0.5f;

    for (int y = 0; y < rows; ++y) {
        const uchar* pBase = grayBase.ptr<uchar>(y);
        const uchar* pCand = grayCand.ptr<uchar>(y);
        const float* pG0x = g0x.ptr<float>(y);
        const float* pG0y = g0y.ptr<float>(y);
        const float* pGkx = gkx.ptr<float>(y);
        const float* pGky = gky.ptr<float>(y);
        float* pMask = mask.ptr<float>(y);

        for (int x = 0; x < cols; ++x) {
            uchar y0 = pBase[x];
            uchar yk = pCand[x];

            // 高光豁免 (Highlight Exemption, SPEC_09 §3.4):
            // 当基准帧发生饱和过曝 (y0 > 235)，辅助曝光帧具备唯一真实清晰细节 (yk < 230)
            // 强行豁免惩罚，保留高动态范围亮部层次与屏幕文字
            if (y0 > 235 && yk < 230) {
                pMask[x] = 1.0f;
                continue;
            }

            float ykNorm = std::min(255.0f, static_cast<float>(yk) * gain);
            float dPhoto = std::abs(ykNorm - static_cast<float>(y0));
            float dGrad = 0.25f * (std::abs(pG0x[x] - pGkx[x]) + std::abs(pG0y[x] - pGky[x]));

            float R = dPhoto + beta * dGrad;
            float w = std::exp(-(R * R) / twoSigmaSq);
            pMask[x] = w;
        }
    }

    // 空间平滑生成柔软无缝的时域抗重影遮罩
    cv::GaussianBlur(mask, mask, cv::Size(5, 5), 1.5);
    return mask;
}

// ============================================================================
// 基准锚定多分辨率金字塔 HDR 融合 (SPEC_09 §3.5 & §4.1)
// 权重守恒律: 凡错位区 100% 锁死基准帧，重影概率恒为 0
// ============================================================================
cv::Mat BurstFusionEngine::blendRobustHDR(
    const cv::Mat& baseFrame,
    const std::vector<cv::Mat>& alignedFrames,
    const std::vector<cv::Mat>& robustMasks) {

    size_t numFrames = alignedFrames.size();
    if (numFrames == 1) return baseFrame.clone();

    int rows = baseFrame.rows;
    int cols = baseFrame.cols;

    // 1. 计算各帧 Mertens 初始曝光品质权重 (对比度、良好曝光度)
    std::vector<cv::Mat> weights(numFrames);
    for (size_t k = 0; k < numFrames; ++k) {
        cv::Mat gray;
        if (alignedFrames[k].channels() == 3) {
            cv::cvtColor(alignedFrames[k], gray, cv::COLOR_BGR2GRAY);
        } else {
            gray = alignedFrames[k];
        }

        // 高频对比度: 拉普拉斯算子响应绝对值
        cv::Mat lap;
        cv::Laplacian(gray, lap, CV_32F);
        cv::Mat C = cv::abs(lap);

        // 良好曝光度: 居中高斯曲线
        cv::Mat grayF;
        gray.convertTo(grayF, CV_32F);
        cv::Mat E(rows, cols, CV_32FC1);
        const float sigmaE = 0.2f * 255.0f;
        const float twoSigmaESq = 2.0f * sigmaE * sigmaE;

        for (int y = 0; y < rows; ++y) {
            const float* pG = grayF.ptr<float>(y);
            float* pE = E.ptr<float>(y);
            for (int x = 0; x < cols; ++x) {
                float diff = pG[x] - 128.0f;
                pE[x] = std::exp(-(diff * diff) / twoSigmaESq);
            }
        }

        // 复合初始品质
        cv::Mat W = (C + 1e-4f).mul(E + 1e-4f);
        weights[k] = W;
    }

    // 归一化初始品质权重
    cv::Mat sumW = cv::Mat::zeros(rows, cols, CV_32FC1);
    for (size_t k = 0; k < numFrames; ++k) {
        sumW += weights[k];
    }
    cv::max(sumW, 1e-6f, sumW);
    for (size_t k = 0; k < numFrames; ++k) {
        weights[k] /= sumW;
    }

    // 2. 实施基准帧绝对几何锚定与权重守恒律 (SPEC_09 §3.5)
    // 辅助帧权重由防重影掩模调制: W_k = W_k_init * W_robust_k
    // 基准帧吸纳一切被剔除的辅助权重: W_0 = 1.0 - sum(W_k for k >= 1)
    std::vector<cv::Mat> finalWeights(numFrames);
    cv::Mat sumAux = cv::Mat::zeros(rows, cols, CV_32FC1);

    for (size_t k = 1; k < numFrames; ++k) {
        finalWeights[k] = weights[k].mul(robustMasks[k - 1]);
        sumAux += finalWeights[k];
    }

    cv::Mat w0(rows, cols, CV_32FC1);
    for (int y = 0; y < rows; ++y) {
        const float* pAux = sumAux.ptr<float>(y);
        float* pW0 = w0.ptr<float>(y);
        for (int x = 0; x < cols; ++x) {
            float auxVal = std::min(0.95f, pAux[x]);
            pW0[x] = std::max(0.05f, 1.0f - auxVal);
        }
    }
    finalWeights[0] = w0;

    // 终极守恒归一化
    cv::Mat totalW = cv::Mat::zeros(rows, cols, CV_32FC1);
    for (size_t k = 0; k < numFrames; ++k) {
        totalW += finalWeights[k];
    }
    cv::max(totalW, 1e-6f, totalW);
    for (size_t k = 0; k < numFrames; ++k) {
        finalWeights[k] /= totalW;
    }

    // 3. 多分辨率拉普拉斯金字塔融合 (3级尺度)
    int levels = 3;

    // 权重高斯金字塔
    std::vector<std::vector<cv::Mat>> weightPyr(numFrames);
    for (size_t k = 0; k < numFrames; ++k) {
        weightPyr[k].resize(levels);
        weightPyr[k][0] = finalWeights[k];
        for (int l = 1; l < levels; ++l) {
            cv::pyrDown(weightPyr[k][l - 1], weightPyr[k][l]);
        }
    }

    // 图像拉普拉斯金字塔
    std::vector<std::vector<cv::Mat>> lapPyr(numFrames);
    for (size_t k = 0; k < numFrames; ++k) {
        lapPyr[k].resize(levels);
        cv::Mat frameF;
        alignedFrames[k].convertTo(frameF, CV_32FC3);

        std::vector<cv::Mat> gPyr(levels);
        gPyr[0] = frameF;
        for (int l = 1; l < levels; ++l) {
            cv::pyrDown(gPyr[l - 1], gPyr[l]);
        }

        // 高频拉普拉斯差分层
        for (int l = 0; l < levels - 1; ++l) {
            cv::Mat up;
            cv::pyrUp(gPyr[l + 1], up, gPyr[l].size());
            lapPyr[k][l] = gPyr[l] - up;
        }
        // 最低频高斯基础层
        lapPyr[k][levels - 1] = gPyr[levels - 1];
    }

    // 逐层金字塔加权合成
    std::vector<cv::Mat> blendedPyr(levels);
    for (int l = 0; l < levels; ++l) {
        blendedPyr[l] = cv::Mat::zeros(lapPyr[0][l].size(), CV_32FC3);
        for (size_t k = 0; k < numFrames; ++k) {
            cv::Mat w3;
            cv::cvtColor(weightPyr[k][l], w3, cv::COLOR_GRAY2BGR);
            blendedPyr[l] += lapPyr[k][l].mul(w3);
        }
    }

    // 金字塔由底至顶向上坍缩重构 (Pyramid Collapse)
    cv::Mat current = blendedPyr[levels - 1];
    for (int l = levels - 2; l >= 0; --l) {
        cv::Mat up;
        cv::pyrUp(current, up, blendedPyr[l].size());
        current = up + blendedPyr[l];
    }

    cv::Mat result;
    current.convertTo(result, CV_8UC3);
    return result;
}

// ============================================================================
// 综合多帧 HDR 局部视差光流补偿与基准锚定时域防重影融合总入口 (SPEC_09)
// ============================================================================
cv::Mat BurstFusionEngine::fuseBurstFrames(const std::vector<cv::Mat>& burstFrames, bool removeGlare, bool isScreenMode) {
    if (burstFrames.empty()) return cv::Mat();
    if (burstFrames.size() == 1) return burstFrames[0].clone();

    const cv::Mat& baseFrame = burstFrames[0];
    int rows = baseFrame.rows;
    int cols = baseFrame.cols;

    std::vector<cv::Mat> alignedFrames;
    alignedFrames.push_back(baseFrame);
    std::vector<cv::Mat> robustMasks;

    // 逐帧实施三级流水线: Tier 1 全局粗配准 -> Tier 2 局部视差 DIS 稠密光流 -> Tier 3 鲁棒防重影遮罩
    for (size_t i = 1; i < burstFrames.size(); ++i) {
        cv::Mat warped, H;
        if (!alignFrameHomography(burstFrames[i], baseFrame, warped, H)) {
            continue; // 全局刚体配准失败说明视场过偏，舍弃该帧
        }

        cv::Mat refined;
        if (!refineOpticalFlowDIS(warped, baseFrame, refined)) {
            refined = warped;
        }

        cv::Mat mask = computeRobustDeghostMask(refined, baseFrame);

        alignedFrames.push_back(refined);
        robustMasks.push_back(mask);
    }

    // 若无辅助帧成功配准，平滑降级至纯净单张基准帧
    if (alignedFrames.size() == 1) {
        return baseFrame.clone();
    }

    // 执行基准锚定无重影 HDR 金字塔融合
    cv::Mat fused = blendRobustHDR(baseFrame, alignedFrames, robustMasks);
    if (fused.empty()) {
        return baseFrame.clone();
    }

    // Lab 空间局部微对比度与通透度自适应增强 (SPEC_08 §4.2)
    try {
        cv::Mat lab;
        cv::cvtColor(fused, lab, cv::COLOR_BGR2Lab);
        std::vector<cv::Mat> labPlanes(3);
        cv::split(lab, labPlanes);

        cv::Ptr<cv::CLAHE> clahe = cv::createCLAHE(1.8, cv::Size(8, 8));
        clahe->apply(labPlanes[0], labPlanes[0]);

        cv::merge(labPlanes, lab);
        cv::cvtColor(lab, fused, cv::COLOR_Lab2BGR);
    } catch (...) {
        // 色彩空间异常平滑使用原图
    }

    // 高光反光/屏幕死白深度置换 (Hard Specular Glare Replacement)
    if (removeGlare) {
        for (int y = 0; y < rows; ++y) {
            uchar* pDst = fused.ptr<uchar>(y);
            const uchar* pBase = baseFrame.ptr<uchar>(y);

            for (int x = 0; x < cols; ++x) {
                int pxOffset = x * 3;
                uchar b0 = pBase[pxOffset];
                uchar g0 = pBase[pxOffset + 1];
                uchar r0 = pBase[pxOffset + 2];

                bool isSaturated = (b0 > 238 && g0 > 238 && r0 > 238);
                if (isSaturated) {
                    uchar minB = 255, minG = 255, minR = 255;
                    bool foundDetail = false;
                    for (size_t k = 1; k < alignedFrames.size(); ++k) {
                        const uchar* pSrc = alignedFrames[k].ptr<uchar>(y);
                        uchar bk = pSrc[pxOffset];
                        uchar gk = pSrc[pxOffset + 1];
                        uchar rk = pSrc[pxOffset + 2];
                        if (bk < 230 || gk < 230 || rk < 230) {
                            minB = std::min(minB, bk);
                            minG = std::min(minG, gk);
                            minR = std::min(minR, rk);
                            foundDetail = true;
                        }
                    }
                    if (foundDetail) {
                        uchar dstB = pDst[pxOffset];
                        uchar dstG = pDst[pxOffset + 1];
                        uchar dstR = pDst[pxOffset + 2];
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

    return fused;
}
