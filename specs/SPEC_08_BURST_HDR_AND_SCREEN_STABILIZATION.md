# 技术规格书 08：屏幕拍摄防过曝、多帧曝光包围 HDR 与亚像素软件防抖引擎规范 (Screen HDR Burst & Subpixel Stabilization Specification)

> **文档性质**：核心算法与工程架构技术规格书 (Technical Specification)  
> **制定目标**：针对手机拍摄发光屏幕（电脑显示器、笔记本、平板等）时的严重过曝死白、反光高光与手持微晃动重影问题，制定涵盖硬件多曝光包围（EV Bracketing）、亚像素单应性几何防抖、防鬼影（Motion De-ghosting）与多分辨率曝光融合（Mertens Exposure Fusion）的全链路纯净技术规范。

---

## 1. 物理机理与技术挑战 (Physics & Optical Challenges)

### 1.1 发光屏幕的大动态范围冲突 (High Dynamic Range Conflict)
* **自发光 vs 环境光**：现代液晶/OLED 屏幕核心发光区域亮度通常为 $300 \sim 1000\text{ nits}$，而室内桌面漫反射环境照度通常仅有 $50 \sim 150\text{ lux}$；
* **单帧截断效应 (Saturation Clipping)**：普通相机单帧自动曝光（AE）以全图平均亮度测光，导致发光屏幕中心区域（如白色网页、Word/PPT 幻灯片、IDE 编辑器）迅速进入感光元件饱和区（像素值打满至 250~255），导致文字细节、表格线条和浅色 UI 元素完全丢失；
* **暗部与亮部的不可调和性**：若强行降低单帧曝光，屏幕外围纸张、键盘或边框将陷入严重噪点与暗黑。

### 1.2 手持连拍生理微抖动 (Micro Hand-Tremor)
* 手持设备在 $100 \sim 300\text{ ms}$ 的短时间间隔内，必然伴随人手微震（通常包含 $1 \sim 3\text{ px}$ 平移与 $\pm 0.3^\circ$ 微小刚体旋转）；
* 多帧直接像素叠加会导致严重的重影（Ghosting）和文字发虚。

---

## 2. 硬件采集策略：动态曝光包围流水线 (EV Bracketing Pipeline)

在触发拍摄时，相机管线必须在稳定状态下执行曝光包围捕获：

```
[相机稳定度检测合格 (_isStable == true)]
                   │
                   ▼
  ┌─────────────────────────────────┐
  │ 连拍第 0 帧 (Base Frame)：      │
  │ • EV = 0 (当前测光基准)         │
  │ • 保障环境暗部与全局空间几何结构 │
  └────────────────┬────────────────┘
                   │ 切换相机曝光补偿 (EV Step)
                   ▼
  ┌─────────────────────────────────┐
  │ 连拍第 1 帧 (Highlight Frame)： │
  │ • EV = -2.0 (或硬件下限)        │
  │ • 强行压暗发光屏幕至非饱和线性区 │
  │ • 完整保留屏幕内高光文字与边缘   │
  └────────────────┬────────────────┘
                   │
                   ▼
  ┌─────────────────────────────────┐
  │ 连拍第 2 帧 (Denoise Frame)：   │
  │ • EV = 0 或 -1.0                │
  │ • 提供时域降噪与防鬼影冗余交叉样本 │
  └────────────────┬────────────────┘
                   │
                   ▼
[相机恢复 EV = 0，送入底层 C++ 融合管线]
```

---

## 3. 亚像素软件防抖与空间配准规范 (Subpixel Stabilization)

针对连拍得到的 $N$ 帧（$N \in [2, 3]$），以第 0 帧 $I_0$ 作为空间几何基准，建立配准矩阵。

### 3.1 尺度不变特征提取与快速配准
1. **多尺度降采样检测**：
   为兼顾实时性与全局视场均匀性，将图像等比缩放至最长边 $\le 960\text{ px}$；
2. **均匀化 ORB 特征点提取**：
   提取 1000 个多尺度角点，通过 FAST 检测子与灰度质心方向向量计算旋转不变描述子；
3. **汉明距离交叉匹配与内点验证**：
   使用 BFMatcher（开启双向交叉验证 `crossCheck = true`），选取最佳匹配对，并按汉明距离排序截取前 180 对高质量关联点；
4. **RANSAC 求解 8 自由度单应性矩阵 (Homography)**：
   $$p_0 \sim H_k \cdot p_k, \quad H_k = \begin{bmatrix} h_{11} & h_{12} & h_{13} \\ h_{21} & h_{22} & h_{23} \\ h_{31} & h_{32} & 1 \end{bmatrix}$$
   - 重投影容差门限：$\epsilon \le 2.5\text{ px}$；
   - 鲁棒性保护：内点数必须 $\ge 20$，且内点率（Inlier Ratio）$\ge 35\%$。若未达标则判定辅助帧存在剧烈晃动，拒绝错误投影并剔除该辅助帧；
5. **亚像素双线性反向映射 (`warpPerspective`)**：
   将辅助帧变换对齐至基准帧 $I_0$ 的像素坐标系中，输出精确对齐帧集合 $\{I_0, I_1', I_2'\}$。

---

## 4. 拍屏幕防过曝与 HDR 融合算法 (Screen HDR Fusion)

### 4.1 像素级防鬼影门限 (Motion De-ghosting Filter)
在融合每一像素点 $(x, y)$ 时，计算辅助帧对齐像素与基准帧像素在 RGB 空间的色差范数：
$$\Delta_{\text{diff}}(k) = \frac{1}{3} \sum_{c \in \{B, G, R\}} \left| I_k'^c(x, y) - I_0^c(x, y) \right|$$
* 若 $\Delta_{\text{diff}}(k) > 22$，判定该局部区域在拍摄间隔中发生了非刚体位移或光流错位（如屏幕动态刷新、手部遮挡），该帧在当前像素点的样本不计入融合池，严格保障不产生文字重影。

### 4.2 屏幕高光强置换与 Tom Mertens 曝光融合
对配准后的多曝光序列，采用多分辨率曝光融合模型：
1. **良好曝光度度量 (Well-exposedness Measure)**：
   $$W_{\text{exp}}(I) = \exp\left( -\frac{(I - 0.5)^2}{2\sigma^2} \right), \quad \sigma = 0.2$$
   - 当像素接近 $1.0$（即 8-bit 的 $255$ 饱和过曝区）时，$W_{\text{exp}} \to 0$；
   - 当像素处于 $0.5$（中间调，$128$ 灰阶）时，$W_{\text{exp}} = 1.0$。
2. **对比度与饱和度度量**：
   - 对比度 $W_{\text{con}}$：拉普拉斯算子响应响应幅值，衡量文字笔迹边缘清晰度；
   - 饱和度 $W_{\text{sat}}$：RGB 三通道标准差，衡量色彩纯净度；
3. **复合像素权重**：
   $$W_k(x, y) = \left[ W_{\text{con}}(I_k) \right]^{w_c} \times \left[ W_{\text{sat}}(I_k) \right]^{w_s} \times \left[ W_{\text{exp}}(I_k) \right]^{w_e}$$
4. **拉普拉斯金字塔多尺度无缝融合**：
   通过高斯金字塔对权重图平滑分解，通过拉普拉斯金字塔对图像细节分频重构，有效消除明暗交界处的边缘光晕（Halo Artifacts）。
5. **硬性高光补丁覆盖 (Hard Specular Glare Replacement)**：
   在基准帧 $I_0(x, y)$ 发生饱和截断（$\min(B_0, G_0, R_0) > 238$）的区域，若低曝光帧 $I_1'$ 存在未饱和清晰纹理（$\max(B_1, G_1, R_1) < 230$），强制采用低曝光帧的真实清晰细节覆盖，**彻底解决发光屏幕中心一片死白看不清字的问题**。

---

## 5. 模块接口契约规范 (Interface Contract)

### 5.1 C++ 底层核心接口
```cpp
namespace scanner {

class BurstFusionEngine {
public:
    /**
     * 多帧曝光包围 HDR 软件防抖融合算子
     * @param burstFrames: 2~3 帧连拍输入 (CV_8UC3, BGR 格式)
     * @param evOffsets: 各帧对应的曝光补偿值 (如 [0.0f, -2.0f, 0.0f])
     * @param isScreenMode: 是否开启屏幕特化防过曝增强
     * @return: 合成后的高动态范围、无反光、无重影图像 (CV_8UC3)
     */
    cv::Mat fuseScreenHDR(
        const std::vector<cv::Mat>& burstFrames,
        const std::vector<float>& evOffsets = {},
        bool isScreenMode = true
    );
};

} // namespace scanner
```

### 5.2 降级保护机制 (Graceful Degradation)
* 若输入单帧或特征点匹配对 $< 12$ 对：直接返回原始基准帧；
* 若底层曝光融合异常：回退至时域加权中值与高光置换流水线，确保 APP 0 崩溃。
