> [!NOTE]
> 本项目的作者完全通过 Vibe Coding 构建了本项目，且并不完全清楚这个 Agent 到底偷偷写了些什么代码。 ¯\\\_(ツ)\_/¯

<div align="center">

<img src="app/src/hachimi/res/mipmap-xxxhdpi/ic_launcher.png" width="128" height="128" style="border-radius: 28px;" alt="Hachimi Scan Logo" />

# Hachimi Scan (哈基米扫描) 🐾

**纯 Vibe Coding 打造的纯本地离线 Android 文档扫描仪。**

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Specs: CC0 1.0](https://img.shields.io/badge/Specs-CC0%201.0-lightgrey.svg)](specs/LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2010%2B-green.svg)](https://developer.android.com)
[![Engine](https://img.shields.io/badge/Engine-OpenCV%204.10%20%2B%20C%2B%2B17-orange.svg)](app/src/main/cpp)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-purple.svg)](https://developer.android.com/jetpack/compose)
[![Engineered with Antigravity](https://img.shields.io/badge/Engineered%20with-Antigravity-6C5CE7?logo=google&logoColor=white)](https://deepmind.google)

*专注本地离线处理的 Android 文档扫描小工具。无广告、零追踪、无需联网权限。*

[English](README.md) | [简体中文](README_zh.md) | [下载最新安装包 (Releases)](https://github.com/eviau512/Hachimi-Scan/releases)

</div>

---

## 🌟 核心特性

### 1. 屏幕拍摄防过曝与连拍 HDR (SPEC_08 Screen HDR)
* **动态 EV 曝光包围流水线**：
  - 针对拍摄高发光屏幕（电脑显示器、笔记本、平板）时的死白过曝痛点，相机在稳定触发时自动执行三帧包围曝光（`EV 0` / `EV -2.0` / `EV 0`）。
  - 将过饱和高光压制至传感器线性响应区，完美还原屏幕内的文字细节、表格边框与浅色 UI。
* **Tom Mertens 多分辨率多曝光金字塔融合**：
  - 基于良好曝光度、对比度与饱和度指标，通过拉普拉斯/高斯金字塔实现多尺度平滑无缝融合，彻底消除明暗交界处的光晕伪影（Halo Artifacts）。
* **亚像素单应性软件防抖与防鬼影**：
  - 全景 ORB 关键点提取 + RANSAC 8 自由度单应性解算，消除手持微颤；
  - 像素级色差防鬼影过滤（$\Delta_{\text{diff}} \le 22$），拒绝重影发虚。
* **硬性高光死白置换**：
  - 基准帧死白截断区域强制采用低曝光帧真实清晰纹理覆盖，确保屏幕文字锐利清晰。

### 2. 图像处理与文档滤镜
* **Retinex 魔法色彩 (Magic Color)**：
  - 基于 CIE-Lab 色彩空间解耦，单独处理 L 通道低频光照场。
  - 照度除法模型（$R = S / L \times 255$）结合动态白平衡，有效消除阴影折痕，还原纸张白度，完美保留彩色笔迹与印章印迹。
* **Sauvola 局部自适应二值化 (B&W Document)**：
  - 积分图极速计算局部动态阈值，在彻底消除复印杂底阴影的同时，保证极细文字笔画不断裂。
* **平滑灰度模式 (Grayscale)**：
  - 针对证件复印、铅笔素描与发票票据提供高保真宽动态范围灰度拉伸。

### 3. 透视校正与画幅比例预设
* **底部比例选择器 Banner**：
  - 采用底部水平胶囊 Banner 设计，彻底解放裁剪画布，杜绝四角调整手柄被遮挡的情况。
  - 丰富画幅比例一键切换：`A4`（标准文档）、`A3`、`4:3`、`16:9`、`8:7`（现代移动传感器满画幅与 PPT 比例）以及自然拉正的 `自定义`。
* **透视缩短补偿恢复 (Foreshortening Recovery)**：
  - 倾斜俯拍时自适应补偿几何纵向压缩，准确还原文档物理纵横比。

### 4. 边缘检测与裁剪微调
* **LSD 结构线段检测与磁吸贴边**：
  - 实时分析文档几何物理边界，支持角点与边缘磁吸吸附。
* **2.8x 悬浮放大镜 (Loupe)**：
  - 拖拽角点手柄时实时显示带十字准星的高清放大镜，支持像素级精准定位。

### 5. 交互体验与手势动画
* **三段式焦点缩放与平移**：
  - 双击手势：`1.0x` $\to$ `2.5x` $\to$ `4.5x` $\to$ `1.0x` 点击点中心对齐缩放；
  - 双指自由捏合缩放（`1.0x ~ 5.0x`）与弹性边界回弹。
* **平滑 90° 旋转过渡动画**。

### 6. 本地离线与隐私保护
* **100% 本地运算**：底层 C++ NDK 与 OpenCV 算法全部运行在设备本地。
* **绝对纯净隐私**：无需注册登录、无任何网络通信权限、无第三方数据收集或统计 SDK。

---

## 🛠️ 构建与运行

### 环境要求
* Android Studio Ladybug (2024.2.1) 或更高版本
* Android SDK 35 (Android 15)
* Android NDK 26+ / 30
* JDK 17 或 JDK 21
* CMake 3.22.1+

### 命令行编译

```bash
# 克隆仓库
git clone https://github.com/eviau512/Hachimi-Scan.git
cd Hachimi-Scan

# 构建哈基米 Release APK (带自签名配置)
./gradlew assembleHachimiRelease

# 或构建标准版 Release APK
./gradlew assembleStandardRelease
```

生成安装包位于 `app/build/outputs/apk/hachimi/release/`。

---

## 📄 开源许可证与版权声明

本项目实行分层开源与版权政策：

* **核心源代码 (Source Code)**：遵循 **[GNU General Public License v3 (GPLv3)](LICENSE)** 开源协议，保障代码自由与开放。
* **技术规范与架构文档 (`specs/`)**：基于 **[Creative Commons Zero v1.0 (CC0 1.0)](specs/LICENSE)** 贡献至公有领域，方便技术交流与洁净室参考。
* **应用图标及衍生美术资产 (Artwork & Visual Assets)**：
  - 本项目曾考虑采用 [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/) 协议，但由于应用内所用到的图标、角色及插画元素衍生自中文互联网社区同人漫画（源自「LLM娘」及大肥鱼漫画相关二创/网络表情文化），开发者并不拥有其原始独立版权；
  - 鉴于原作者的具体版权保留政策以及是否存在许可证延续/传染（ShareAlike）尚未完全明确，本项目**不对相关美术形象主张任何专有版权**；相关素材仅出于开源社区同好交流与非商业展示目的使用；
  - 若原作者对相关美术元素的使用有任何异议或提出要求，请随时提交 Issue，我们将第一时间配合替换或移除；
  - ⚠️ **下游分发提示**：若二次分发、修改发布或商业化衍生本项目，请注意相关美术资产的版权归属风险，强烈建议替换为您自己拥有完整版权的应用图标与插图。

---

## 🤖 开发致谢

本项目由作者通过与 **[Google Antigravity](https://deepmind.google)** 深度结对 Vibe Coding 协作完成，涵盖全链路底层 C++ NDK 图像算法研发及 Jetpack Compose 现代化界面工程。

---

## ⚖️ 免责声明与商标声明

1. **关于“哈基米 / Hachimi”**：
   - “哈基米”源于二次元亚文化流行梗；在中文 AI 与大模型社区中，亦常被戏称为谷歌 **Google Gemini** 的萌化昵称。
   - 本项目名称 `Hachimi Scan (哈基米扫描)` 既是对该社区流行文化的致敬，也巧妙呼应了本项目全程使用 Google Antigravity / Gemini 进行 Vibe Coding 的渊源。
   - **开发者不拥有、亦不对“Hachimi”及“哈基米”词汇主张任何独占性商标权或专有权利**。

2. **第三方权利与非从属声明**：
   - 本项目为独立的自由开源工具，与任何拥有相关文化元素的版权方（包括但不限于 Cygames 等）无官方关联、赞助或背书关系。
   - 文档及代码中出现的所有第三方产品名称、商标及标识，其知识产权均归其各自所有者所有。
