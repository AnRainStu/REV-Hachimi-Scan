> [!NOTE]
> The owner of this repo built this entirely via vibe coding, and isn't quite sure what this Agent actually does. ¯\\\_(ツ)\_/¯

<div align="center">

<img src="app/src/hachimi/res/mipmap-xxxhdpi/ic_launcher.png" width="128" height="128" alt="Hachimi Scan Logo" />

# Hachimi Scan (哈基米扫描) 🐾

**A lightweight, privacy-first Android document scanner, built 100% via vibe coding.**

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Specs: CC0 1.0](https://img.shields.io/badge/Specs-CC0%201.0-lightgrey.svg)](specs/LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2010%2B-green.svg)](https://developer.android.com)
[![Engine](https://img.shields.io/badge/Engine-OpenCV%204.10%20%2B%20C%2B%2B20-orange.svg)](app/src/main/cpp)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-purple.svg)](https://developer.android.com/jetpack/compose)
[![Engineered with Antigravity](https://img.shields.io/badge/Engineered%20with-Antigravity-6C5CE7?logo=google&logoColor=white)](https://deepmind.google)

*纯 Vibe Coding 出品、专注本地离线处理的 Android 文档扫描小工具。无广告、零追踪、无需联网。*

[下载 APK (Releases)](https://github.com/eviau512/Hachimi-Scanner/releases) | [English](#features) | [中文说明](#核心特性) | [构建指南](#构建与运行) | [开发致谢](#-开发致谢--acknowledgments) | [免责声明](#免责声明与商标声明--disclaimer--trademark-notice)

</div>

---

## 🌟 核心特性 (Features)

### 1. 图像处理与文档滤镜 (Image Processing & Filters)
* **Retinex 魔术彩色 (Magic Color)**：
  - 基于 CIE-Lab 色彩空间解耦，单独处理 L 通道低频光照场。
  - 照度除法模型（$R = S / L \times 255$）结合动态白平衡，淡化纸张阴影折痕，较好保留彩色笔迹与印章。
* **Sauvola 局部自适应二值化 (B&W Document)**：
  - 基于积分图快速计算局部均值与方差，保留文字边缘并抑制浅色背景噪点。
* **平滑灰阶模式 (Grayscale)**：
  - 针对素描、黑白凭证提供宽动态范围灰度拉伸。

### 2. 透视校正与画幅比例预设 (Perspective Correction & Aspect Ratio)
* **文档导出画幅比例选择**：
  - 悬浮比例选择侧栏，提供常用输出画幅预设：`A4`（标准竖版）、`A3`（横向 A4）、`4:3`、`16:9`、`8:7`（PPT 投影与全画幅传感器）以及自然拉正的 `自定义`。
  - 裁剪四边形角点保持 100% 自由拖动，比例预设严格作用于输出拉正后的文档画幅。
* **透视缩短补偿算法 (Foreshortening Recovery)**：
  - 自定义模式下，针对倾斜俯拍时因透视缩短造成的纵向形变，自适应恢复物体物理纵横比。

### 3. 边缘检测与裁剪微调 (Edge Detection & Crop)
* **LSD 结构线段检测**：
  - 辅助识别纸张边界并提供磁吸吸附。
* **悬浮放大镜**：
  - 拖拽裁剪顶点时显示十字准星放大镜（Loupe），方便对齐角点。

### 4. 手势交互与动画 (Interactions & Gestures)
* **双缩放手势支持**：
  - **双击 3 段式焦点缩放**：`1.0x` $\to$ `2.5x` $\to$ `4.5x` $\to$ `1.0x`，中心对齐点击点。
  - **双指自由捏合与拖拽平移**：支持 `1.0x ~ 5.0x` 缩放与视口边界吸附。
* **90° 旋转动画**：
  - 异步处理图像变换，界面带平滑旋转过渡效果。

### 5. 本地离线与隐私 (Offline & Privacy)
* **完全离线**：核心图像算法（C++ NDK + OpenCV）均在本地运行。
* **注重隐私**：无需注册登录，不包含第三方数据分析与追踪 SDK，无需联网。

---

## 🛠️ 构建与运行 (Build & Run)

### 环境要求
* Android Studio Ladybug (2024.2.1) 或更高版本
* Android SDK 35 (Android 15)
* Android NDK 26+ / 30
* JDK 17 或 JDK 21
* CMake 3.22.1+

### 命令行编译

```bash
# 克隆仓库
git clone https://github.com/eviau512/Hachimi-Scanner.git
cd Hachimi-Scanner

# 构建标准 Release APK (签名已配置自动回退)
./gradlew assembleStandardRelease

# 或构建哈基米定制版本
./gradlew assembleHachimiRelease
```

生成安装包位于 `app/build/outputs/apk/`。

---

## 📄 开源许可证与版权声明 (License & Copyright)

本项目采用分层的开源许可与声明政策：

* **核心源代码 (Source Code)**：遵循 **[GNU General Public License v3 (GPLv3)](LICENSE)** 协议开源，保障代码层面的自由与开放。修改或二次分发请遵守开源协议。
* **技术规范与架构文档 (`specs/`)**：基于 **[Creative Commons Zero v1.0 (CC0 1.0)](specs/LICENSE)** 贡献至公有领域，方便行业交流与参考。
* **应用图标及衍生美术资产 (Artwork & Visual Assets)**：
  - 本项目曾考虑采用 [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/) 协议，但由于应用内所用到的图标、角色及插画元素衍生自中文互联网社区同人漫画（源自「LLM娘」及大肥鱼漫画相关二创/网络表情文化），开发者并不拥有其原始独立版权；
  - 鉴于原作者的具体版权保留政策以及是否存在许可证延续/传染（ShareAlike）尚未完全明确，本项目**不对相关美术形象主张任何专有版权**；相关素材仅出于开源社区同好交流与非商业展示目的使用；
  - 若原作者对相关美术元素的使用有任何异议或提出要求，请随时提交 Issue，我们将第一时间配合替换或移除；
  - ⚠️ **下游分发提示**：若二次分发、修改发布或商业化衍生本项目，请注意相关美术资产的版权归属风险，强烈建议替换为您自己拥有完整版权的应用图标与插图。

---

## 🤖 开发致谢 / Acknowledgments

本项目由作者通过与 **[Google Antigravity](https://deepmind.google)**（Advanced Agentic Coding AI）结对编码（Vibe Coding）协作完成，涵盖底层 C++ 图像处理算法及 Jetpack Compose 界面交互。

This project was developed through collaborative vibe coding between the author and **Google Antigravity** (Advanced Agentic Coding AI), covering native C++ image processing routines and Jetpack Compose UI interactions.

---

## ⚖️ 免责声明与商标声明 / Disclaimer & Trademark Notice

1. **关于“哈基米 / Hachimi”**：
   - “哈基米”为源于二次元亚文化及中文互联网的社区流行萌宠/猫咪文化词汇。
   - 本项目名称 `Hachimi Scan (哈基米扫描)` 仅出于社区文化认同及开源项目代号使用。
   - **开发者不拥有、亦不对“Hachimi”及“哈基米”词汇主张任何独占性商标权或专有权利**。

2. **第三方权利与非从属声明**：
   - 本项目为独立的自由开源工具，与任何拥有相关文化元素的版权方（包括但不限于 Cygames 等）无官方关联、赞助或背书关系。
   - 文档及代码中出现的所有第三方产品名称、商标及标识，其知识产权均归其各自所有者所有。

---

1. **Regarding "Hachimi"**:
   - "Hachimi" is an internet cultural term and community meme originating from ACG pop culture.
   - The name `Hachimi Scan` is used purely as an open-source project identifier and cultural tribute.
   - **The developers do not own, nor do they claim, any exclusive trademark or proprietary rights over the word "Hachimi".**

2. **Non-Affiliation & Trademarks**:
   - This project is an independent free and open-source software and is not affiliated with, endorsed by, or associated with Cygames or any other entities.
   - All product names, trademarks, and registered trademarks mentioned herein are the property of their respective owners.
