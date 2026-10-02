#include <jni.h>
#include <stdexcept>
#include <cmath>
#include <android/bitmap.h>
#include <opencv2/opencv.hpp>
#include "edge_detector.h"
#include "perspective_corrector.h"
#include "curve_dewarper.h"
#include "burst_fusion.h"

namespace {
void throwNativeError(JNIEnv* env, const char* message) {
    if (!env->ExceptionCheck()) {
        jclass type = env->FindClass("java/lang/IllegalStateException");
        if (type) env->ThrowNew(type, message);
    }
}
std::vector<cv::Point2f> readPoints(JNIEnv* env, jfloatArray array, bool quad = false) {
    if (!array) throw std::invalid_argument("Missing corner coordinates");
    const auto length = env->GetArrayLength(array);
    if (length % 2 != 0 || (quad && length != 8) || (!quad && length < 4))
        throw std::invalid_argument("Invalid corner coordinate count");
    std::vector<jfloat> values(length);
    env->GetFloatArrayRegion(array, 0, length, values.data());
    if (env->ExceptionCheck()) throw std::runtime_error("Cannot read corner coordinates");
    std::vector<cv::Point2f> points;
    for (int i = 0; i < length; i += 2) {
        if (!std::isfinite(values[i]) || !std::isfinite(values[i + 1]))
            throw std::invalid_argument("Corner coordinates must be finite");
        points.emplace_back(values[i], values[i + 1]);
    }
    return points;
}
struct BitmapUnlock {
    JNIEnv* env;
    jobject bitmap;
    ~BitmapUnlock() { AndroidBitmap_unlockPixels(env, bitmap); }
};
}

extern "C" {

JNIEXPORT jfloatArray JNICALL
Java_com_scanner_app_engine_NativeEdgeDetector_nativeDetectDocument(JNIEnv* env, jobject /* this */, jlong matAddr, jboolean curvedMode, jfloat touchX, jfloat touchY) try {
    cv::Mat* grayFrame = reinterpret_cast<cv::Mat*>(matAddr);
    if (!grayFrame) return env->NewFloatArray(0);

    EdgeDetector detector;
    DetectionResult result = detector.detectDocument(*grayFrame, curvedMode, touchX, touchY);

    std::vector<float> outData;
    outData.push_back(result.found ? 1.0f : 0.0f);
    outData.push_back(result.isCurved ? 1.0f : 0.0f);

    if (!result.isCurved) {
        outData.push_back(static_cast<float>(result.corners.size()));
        for (const auto& pt : result.corners) {
            outData.push_back(pt.x);
            outData.push_back(pt.y);
        }
    } else {
        outData.push_back(static_cast<float>(result.boundaryPoints.size()));
        for (const auto& pt : result.boundaryPoints) {
            outData.push_back(pt.x);
            outData.push_back(pt.y);
        }
    }

    jfloatArray retArray = env->NewFloatArray(outData.size());
    if (!retArray) return nullptr;
    env->SetFloatArrayRegion(retArray, 0, outData.size(), outData.data());
    return retArray;
} catch (const std::exception& error) {
    throwNativeError(env, error.what());
    return nullptr;
} catch (...) {
    throwNativeError(env, "Unexpected native image processing failure");
    return nullptr;
}

JNIEXPORT jlong JNICALL
Java_com_scanner_app_engine_NativePerspective_nativeCorrectPerspective(JNIEnv* env, jobject /* this */, jlong srcMatAddr, jfloatArray corners, jfloat targetAspectRatio) try {
    cv::Mat* src = reinterpret_cast<cv::Mat*>(srcMatAddr);
    if (!src) return 0;

    auto srcCorners = readPoints(env, corners, true);
    if (src->empty()) throw std::invalid_argument("Source image is empty");
    if (!std::isfinite(targetAspectRatio) || targetAspectRatio < 0 || targetAspectRatio > 12 || (targetAspectRatio > 0 && targetAspectRatio < 1.0f / 12.0f))
        throw std::invalid_argument("Invalid aspect ratio");

    PerspectiveCorrector corrector;
    cv::Mat warped = corrector.correctPerspective(*src, srcCorners, static_cast<float>(targetAspectRatio));
    
    cv::Mat* result = new cv::Mat(warped);
    return reinterpret_cast<jlong>(result);
} catch (const std::exception& error) {
    throwNativeError(env, error.what());
    return 0;
} catch (...) {
    throwNativeError(env, "Unexpected native image processing failure");
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_scanner_app_engine_NativePerspective_nativeProcessDocument(JNIEnv* env, jobject /* this */, jlong srcMatAddr, jfloatArray corners, jint filterType, jfloat targetAspectRatio) try {
    cv::Mat* src = reinterpret_cast<cv::Mat*>(srcMatAddr);
    if (!src) return 0;

    auto srcCorners = readPoints(env, corners, true);
    if (src->empty()) throw std::invalid_argument("Source image is empty");
    if (!std::isfinite(targetAspectRatio) || targetAspectRatio < 0 || targetAspectRatio > 12 || (targetAspectRatio > 0 && targetAspectRatio < 1.0f / 12.0f))
        throw std::invalid_argument("Invalid aspect ratio");

    PerspectiveCorrector corrector;
    cv::Mat processed = corrector.processDocument(*src, srcCorners, static_cast<FilterType>(filterType), static_cast<float>(targetAspectRatio));

    cv::Mat* result = new cv::Mat(processed);
    return reinterpret_cast<jlong>(result);
} catch (const std::exception& error) {
    throwNativeError(env, error.what());
    return 0;
} catch (...) {
    throwNativeError(env, "Unexpected native image processing failure");
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_scanner_app_engine_NativePerspective_nativeApplyFilter(JNIEnv* env, jobject /* this */, jlong srcMatAddr, jint filterType) try {
    cv::Mat* src = reinterpret_cast<cv::Mat*>(srcMatAddr);
    if (!src) return 0;

    PerspectiveCorrector corrector;
    cv::Mat out;
    switch (static_cast<FilterType>(filterType)) {
        case FilterType::MAGIC_COLOR:
            out = corrector.enhanceMagicColor(*src);
            break;
        case FilterType::BW_SAUVOLA:
            out = corrector.enhanceSauvola(*src);
            break;
        case FilterType::GRAYSCALE:
            out = corrector.enhanceGrayscale(*src);
            break;
        case FilterType::ORIGINAL:
        default:
            out = src->clone();
            break;
    }

    cv::Mat* result = new cv::Mat(out);
    return reinterpret_cast<jlong>(result);
} catch (const std::exception& error) {
    throwNativeError(env, error.what());
    return 0;
} catch (...) {
    throwNativeError(env, "Unexpected native image processing failure");
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_scanner_app_engine_NativeBurstFusion_nativeFuseBurstFrames(JNIEnv* env, jobject /* this */, jlongArray matAddrs, jboolean removeGlare, jboolean isScreenMode, jboolean superResolution) try {
    if (!matAddrs) return 0;

    jsize len = env->GetArrayLength(matAddrs);
    if (len == 0) return 0;

    std::vector<jlong> addrs(len);
    env->GetLongArrayRegion(matAddrs, 0, len, addrs.data());
    if (env->ExceptionCheck()) return 0;
    std::vector<cv::Mat> frames;
    frames.reserve(len);

    for (int i = 0; i < len; ++i) {
        cv::Mat* pMat = reinterpret_cast<cv::Mat*>(addrs[i]);
        if (pMat && !pMat->empty()) {
            frames.push_back(*pMat);
        }
    }

    if (frames.empty()) return 0;

    BurstFusionEngine engine;
    cv::Mat fused = engine.fuseBurstFrames(frames, removeGlare, isScreenMode, superResolution);

    cv::Mat* result = new cv::Mat(fused);
    return reinterpret_cast<jlong>(result);
} catch (const std::exception& error) {
    throwNativeError(env, error.what());
    return 0;
} catch (...) {
    throwNativeError(env, "Unexpected native image processing failure");
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_scanner_app_engine_NativeCurveDewarper_nativeDewarpCurved(JNIEnv* env, jobject /* this */, jlong srcMatAddr, jfloatArray topPts, jfloatArray bottomPts, jfloatArray leftPts, jfloatArray rightPts) try {
    cv::Mat* src = reinterpret_cast<cv::Mat*>(srcMatAddr);
    if (!src) return 0;

    auto top = readPoints(env, topPts);
    auto bottom = readPoints(env, bottomPts);
    auto left = readPoints(env, leftPts);
    auto right = readPoints(env, rightPts);

    CurveDewarper dewarper;
    cv::Mat dewarped = dewarper.dewarpCurved(*src, top, bottom, left, right);

    cv::Mat* result = new cv::Mat(dewarped);
    return reinterpret_cast<jlong>(result);
} catch (const std::exception& error) {
    throwNativeError(env, error.what());
    return 0;
} catch (...) {
    throwNativeError(env, "Unexpected native image processing failure");
    return 0;
}

JNIEXPORT jfloatArray JNICALL
Java_com_scanner_app_engine_NativeEdgeDetector_nativeFindSnapPoint(JNIEnv* env, jobject /* this */, jlong edgeMatAddr, jfloat touchX, jfloat touchY, jfloat radius) try {
    cv::Mat* edges = reinterpret_cast<cv::Mat*>(edgeMatAddr);
    if (!edges) return env->NewFloatArray(0);

    EdgeDetector detector;
    std::vector<cv::Point> pts = detector.findMagneticSnapPoint(*edges, cv::Point2f(touchX, touchY), radius);

    std::vector<float> outData;
    if (!pts.empty()) {
        outData.push_back(1.0f); // found
        outData.push_back(static_cast<float>(pts[0].x));
        outData.push_back(static_cast<float>(pts[0].y));
    } else {
        outData.push_back(0.0f); // not found
        outData.push_back(0.0f);
        outData.push_back(0.0f);
    }

    jfloatArray retArray = env->NewFloatArray(outData.size());
    if (!retArray) return nullptr;
    env->SetFloatArrayRegion(retArray, 0, outData.size(), outData.data());
    return retArray;
} catch (const std::exception& error) {
    throwNativeError(env, error.what());
    return nullptr;
} catch (...) {
    throwNativeError(env, "Unexpected native image processing failure");
    return nullptr;
}

JNIEXPORT jfloat JNICALL
Java_com_scanner_app_engine_NativeEdgeDetector_nativeFindLineOffset(
    JNIEnv* env, jobject /* this */,
    jlong edgeMatAddr,
    jfloat p1x, jfloat p1y,
    jfloat p2x, jfloat p2y,
    jfloat maxOffset
) try {
    cv::Mat* edgeMat = reinterpret_cast<cv::Mat*>(edgeMatAddr);
    if (!edgeMat || edgeMat->empty()) {
        return std::numeric_limits<float>::quiet_NaN();
    }

    EdgeDetector detector;
    return detector.findMagneticLineOffset(*edgeMat, cv::Point2f(p1x, p1y), cv::Point2f(p2x, p2y), maxOffset);
} catch (const std::exception& error) {
    throwNativeError(env, error.what());
    return std::numeric_limits<float>::quiet_NaN();
} catch (...) {
    throwNativeError(env, "Unexpected native image processing failure");
    return std::numeric_limits<float>::quiet_NaN();
}

JNIEXPORT jfloatArray JNICALL
Java_com_scanner_app_engine_NativeEdgeDetector_nativeFindContourAtPoint(
    JNIEnv* env, jobject /* this */,
    jlong grayMatAddr,
    jfloat touchX, jfloat touchY
) try {
    cv::Mat* grayMat = reinterpret_cast<cv::Mat*>(grayMatAddr);
    if (!grayMat || grayMat->empty()) {
        std::vector<float> emptyData = {0.0f};
        jfloatArray retArray = env->NewFloatArray(1);
        if (!retArray) return nullptr;
    env->SetFloatArrayRegion(retArray, 0, 1, emptyData.data());
        return retArray;
    }

    EdgeDetector detector;
    std::vector<cv::Point2f> corners = detector.findContourAtPoint(*grayMat, touchX, touchY);

    if (corners.size() == 4) {
        std::vector<float> outData(9);
        outData[0] = 1.0f; // found
        for (int i = 0; i < 4; ++i) {
            outData[1 + 2 * i] = corners[i].x;
            outData[2 + 2 * i] = corners[i].y;
        }
        jfloatArray retArray = env->NewFloatArray(9);
        if (!retArray) return nullptr;
    env->SetFloatArrayRegion(retArray, 0, 9, outData.data());
        return retArray;
    } else {
        std::vector<float> emptyData = {0.0f};
        jfloatArray retArray = env->NewFloatArray(1);
        if (!retArray) return nullptr;
    env->SetFloatArrayRegion(retArray, 0, 1, emptyData.data());
        return retArray;
    }
} catch (const std::exception& error) {
    throwNativeError(env, error.what());
    return nullptr;
} catch (...) {
    throwNativeError(env, "Unexpected native image processing failure");
    return nullptr;
}

JNIEXPORT jobjectArray JNICALL
Java_com_scanner_app_engine_NativeEdgeDetector_nativeDetectStructuralLines(
    JNIEnv* env, jobject /* this */,
    jobject bitmap
) try {
    jclass floatArrayClass = env->FindClass("[F");
    if (!floatArrayClass) return nullptr;
    if (!bitmap) {
        jobjectArray result = env->NewObjectArray(2, floatArrayClass, nullptr);
        jfloatArray emptyH = env->NewFloatArray(0);
        jfloatArray emptyV = env->NewFloatArray(0);
        env->SetObjectArrayElement(result, 0, emptyH);
        env->SetObjectArrayElement(result, 1, emptyV);
        return result;
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        jobjectArray result = env->NewObjectArray(2, floatArrayClass, nullptr);
        jfloatArray emptyH = env->NewFloatArray(0);
        jfloatArray emptyV = env->NewFloatArray(0);
        env->SetObjectArrayElement(result, 0, emptyH);
        env->SetObjectArrayElement(result, 1, emptyV);
        return result;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0 || !pixels) {
        jobjectArray result = env->NewObjectArray(2, floatArrayClass, nullptr);
        jfloatArray emptyH = env->NewFloatArray(0);
        jfloatArray emptyV = env->NewFloatArray(0);
        env->SetObjectArrayElement(result, 0, emptyH);
        env->SetObjectArrayElement(result, 1, emptyV);
        return result;
    }

    BitmapUnlock unlock{env, bitmap};
    cv::Mat gray;
    if (info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
        cv::Mat rgba(info.height, info.width, CV_8UC4, pixels, info.stride);
        cv::cvtColor(rgba, gray, cv::COLOR_RGBA2GRAY);
    } else if (info.format == ANDROID_BITMAP_FORMAT_RGB_565) {
        cv::Mat rgb565(info.height, info.width, CV_8UC2, pixels, info.stride);
        cv::cvtColor(rgb565, gray, cv::COLOR_BGR5652GRAY);
    } else if (info.format == ANDROID_BITMAP_FORMAT_A_8) {
        cv::Mat a8(info.height, info.width, CV_8UC1, pixels, info.stride);
        gray = a8.clone();
    }


    if (gray.empty()) {
        jobjectArray result = env->NewObjectArray(2, floatArrayClass, nullptr);
        jfloatArray emptyH = env->NewFloatArray(0);
        jfloatArray emptyV = env->NewFloatArray(0);
        env->SetObjectArrayElement(result, 0, emptyH);
        env->SetObjectArrayElement(result, 1, emptyV);
        return result;
    }

    EdgeDetector detector;
    EdgeDetector::StructuralLinesResult linesResult = detector.detectStructuralLines(
        gray,
        static_cast<float>(info.width),
        static_cast<float>(info.height)
    );

    jobjectArray result = env->NewObjectArray(2, floatArrayClass, nullptr);

    jfloatArray hArray = env->NewFloatArray(linesResult.horizontalLines.size());
    if (!linesResult.horizontalLines.empty()) {
        env->SetFloatArrayRegion(hArray, 0, linesResult.horizontalLines.size(), linesResult.horizontalLines.data());
    }
    env->SetObjectArrayElement(result, 0, hArray);

    jfloatArray vArray = env->NewFloatArray(linesResult.verticalLines.size());
    if (!linesResult.verticalLines.empty()) {
        env->SetFloatArrayRegion(vArray, 0, linesResult.verticalLines.size(), linesResult.verticalLines.data());
    }
    env->SetObjectArrayElement(result, 1, vArray);

    return result;
} catch (const std::exception& error) {
    throwNativeError(env, error.what());
    return nullptr;
} catch (...) {
    throwNativeError(env, "Unexpected native image processing failure");
    return nullptr;
}

} // extern "C"


