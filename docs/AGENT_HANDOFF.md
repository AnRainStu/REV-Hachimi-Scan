# Agent 对接文档

更新日期：2026-10-11。本文用于让后续 agent 在不依赖聊天记录和本机目录的情况下接手项目。

## 接手入口与已验证基线

- 当前仓库：[AnRainStu/REV-Hachimi-Scan](https://github.com/AnRainStu/REV-Hachimi-Scan)，日常开发分支为 `main`。
- 上游参考：[eviau512/Hachimi-Scan](https://github.com/eviau512/Hachimi-Scan)，保留其历史与许可证。
- 工作约定：[根目录 AGENTS.md](../AGENTS.md)。架构说明：[REFACTOR.md](REFACTOR.md)。中文介绍：[README_zh.md](../README_zh.md)。
- 已验证代码基线：版本 `0.4.2`、`versionCode = 12`，提交 [a79d212](https://github.com/AnRainStu/REV-Hachimi-Scan/commit/a79d212465a13751e5405572b447eb2da9cd25d4)。
- 该基线的验收于 2026-10-04 完成：[Actions 37181314228](https://github.com/AnRainStu/REV-Hachimi-Scan/actions/runs/37181314228)，构建与模拟器集成均成功。
- 基线结果：12 项单元测试、4 项设备原生测试通过；lint 无错误，有 78 项警告；界面验收生成 28 张截图，并验证背景静态帧与动效录屏。

这些是指定提交的历史结果。接手时先核对当前源码、工作区改动和新的 Actions 结果，不能把它们当作后来提交已经通过的证明。应用版本以 [app/build.gradle.kts](../app/build.gradle.kts) 为准。

## 产品与修复状态

应用为本地文档扫描器，主要能力包括拍照/导入、裁剪、旋转、四种滤镜、批量编辑、排序、持久化、PDF/JPEG 导出和分享。

已完成的重构与修复：

- 扫描页和编辑元数据持久化到 SQLite，原图保存在应用私有的持久目录。
- 图片编辑从原图生成新结果；成功提交后才替换旧结果，失败保留上次成功状态。
- CameraX 生命周期、分析线程、Y 平面步长、稳定判断和预览坐标经过整理。
- 导出先写入 pending MediaStore 项，成功后发布，失败回滚。
- 界面改为悬浮玻璃操作、纸张文档网格、明暗主题与紫色流光背景。
- 修复刚拍完选择黑白、灰度、增强没有画面变化的问题：裁剪页现在直接生成原生滤镜预览，并保护快速切换时的最新结果。
- 裁剪页滤镜预览、旋转和裁剪框调整保持草稿状态；确认才提交，取消保留先前保存状态与原图。

原始 `specs/` 有一些尚未实现的目标；判断能力时以当前源码与验证结果为依据。

## 源码地图

以下 Kotlin 路径均位于 `app/src/main/java/com/scanner/app/`。

| 职责 | 主要入口 |
| --- | --- |
| 初始化与导航 | [ScannerApplication.kt](../app/src/main/java/com/scanner/app/ScannerApplication.kt)、[NavGraph.kt](../app/src/main/java/com/scanner/app/ui/navigation/NavGraph.kt) |
| 拍照、HDR、导入 | [CameraViewModel.kt](../app/src/main/java/com/scanner/app/ui/camera/CameraViewModel.kt)、[CameraPreview.kt](../app/src/main/java/com/scanner/app/ui/camera/CameraPreview.kt)、[FrameAnalyzer.kt](../app/src/main/java/com/scanner/app/data/camera/FrameAnalyzer.kt) |
| 裁剪与即时预览 | [CropScreen.kt](../app/src/main/java/com/scanner/app/ui/crop/CropScreen.kt)、[CropViewModel.kt](../app/src/main/java/com/scanner/app/ui/crop/CropViewModel.kt)、[QuadCropView.kt](../app/src/main/java/com/scanner/app/ui/crop/QuadCropView.kt)、[CropFilterPreview.kt](../app/src/main/java/com/scanner/app/data/image/CropFilterPreview.kt) |
| 文档库与已保存页编辑 | [ReviewScreen.kt](../app/src/main/java/com/scanner/app/ui/review/ReviewScreen.kt)、[ReviewViewModel.kt](../app/src/main/java/com/scanner/app/ui/review/ReviewViewModel.kt)、[ScanImage.kt](../app/src/main/java/com/scanner/app/ui/components/ScanImage.kt) |
| 元数据与文件清理 | [ScannedPage.kt](../app/src/main/java/com/scanner/app/domain/model/ScannedPage.kt)、[PageStore.kt](../app/src/main/java/com/scanner/app/data/repository/PageStore.kt)、[PageRepository.kt](../app/src/main/java/com/scanner/app/data/repository/PageRepository.kt) |
| 完整分辨率处理与提交 | [DocumentProcessor.kt](../app/src/main/java/com/scanner/app/data/image/DocumentProcessor.kt)、[PageEditor.kt](../app/src/main/java/com/scanner/app/data/image/PageEditor.kt) |
| 原生滤镜与几何处理 | [NativePerspective.kt](../app/src/main/java/com/scanner/app/engine/NativePerspective.kt)、[jni_bridge.cpp](../app/src/main/cpp/jni_bridge.cpp)、[perspective_corrector.cpp](../app/src/main/cpp/perspective_corrector.cpp) |
| 导出 | [MediaStoreWriter.kt](../app/src/main/java/com/scanner/app/data/export/MediaStoreWriter.kt)、[PdfExporter.kt](../app/src/main/java/com/scanner/app/data/export/PdfExporter.kt)、[FolderExporter.kt](../app/src/main/java/com/scanner/app/data/export/FolderExporter.kt) |
| 玻璃与流光 | [Glass.kt](../app/src/main/java/com/scanner/app/ui/components/Glass.kt)、[GlassControls.kt](../app/src/main/java/com/scanner/app/ui/components/GlassControls.kt)、[AmbientBackground.kt](../app/src/main/java/com/scanner/app/ui/components/AmbientBackground.kt) |
| 设置与文案 | [SettingsScreen.kt](../app/src/main/java/com/scanner/app/ui/settings/SettingsScreen.kt)、[strings.xml](../app/src/main/res/values/strings.xml)、[中文 strings.xml](../app/src/main/res/values-zh-rCN/strings.xml) |
| CI 与真实界面验收 | [android.yml](../.github/workflows/android.yml)、[capture-ui.py](../.github/scripts/capture-ui.py) |

## 构建与安装

从仓库根目录运行 Gradle wrapper，不需要复用某台机器的 SDK 绝对路径。

| 项目 | 当前固定配置 |
| --- | --- |
| JDK | 17 |
| Gradle wrapper / Android Gradle Plugin | 8.14 / 8.9.1 |
| Kotlin / Compose compiler plugin | 2.0.21 |
| Android SDK | compile/target 35，min 29（Android 10） |
| Build Tools / NDK / CMake | 35.0.0 / 26.3.11579264 / 3.22.1 |
| OpenCV / CameraX | 4.10.0 / 1.4.1 |
| ABI | arm64-v8a、armeabi-v7a、x86_64，另提供 universal APK |

配置 `JAVA_HOME` 和 Android SDK；使用 `ANDROID_HOME` 或未跟踪的 `local.properties` 中的 `sdk.dir`。原生工具链应与 [构建文件](../app/build.gradle.kts) 和 [CI](../.github/workflows/android.yml) 保持一致。

OpenCV 由 Maven AAR/Prefab 提供。源码 namespace 为 `com.scanner.app`，与 flavor 的安装包名不同。同一 checkout 的 Gradle 构建串行执行；多个 agent 可以并行读代码，但不要同时编译同一组输出。

Linux/macOS：

```sh
bash gradlew testHachimiDebugUnitTest lintHachimiDebug assembleHachimiDebug assembleStandardDebug --console=plain
# 需要可用的、用于测试的 Android 设备/模拟器：
bash gradlew connectedHachimiDebugAndroidTest --console=plain
```

Windows PowerShell：

```powershell
.\gradlew.bat testHachimiDebugUnitTest lintHachimiDebug assembleHachimiDebug assembleStandardDebug --console=plain
.\gradlew.bat connectedHachimiDebugAndroidTest --console=plain
# 如果遇到此前出现过的 Windows 增量编译问题，再使用此诊断参数：
.\gradlew.bat assembleHachimiDebug '-Pkotlin.incremental=false' --console=plain
```

两个产品变体使用不同应用 ID：`hachimi` 为 `moe.hachimi.cam`，`standard` 为 `com.open.scan`。调试 APK 位于 `app/build/outputs/apk/<edition>/debug/`；例如 `app-hachimi-arm64-v8a-debug.apk` 与 `app-hachimi-universal-debug.apk`。测试脚本针对 `moe.hachimi.cam`。

`BuildConfig.GIT_HASH` 与 `BUILD_TIME` 在构建时生成。交付安装包前核对版本、提交和签名；仅文档更新无需提升应用版本。同名 debug APK 在不同环境可能使用不同调试证书，能否覆盖安装取决于应用 ID、证书与版本号；不要把 CI 生成的 debug APK 默认视为与既有安装签名一致。

Release 使用 `SIGNING_STORE_FILE`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`，未配置时不签名。签名文件、密码和本机配置不提交到公开仓库。

## 图片编辑的关键链路

### 拍照后的即时滤镜

`CameraScreen → CameraViewModel → ScannedPage → CropScreen`。新拍照/导入记录可以只有 `originalImagePath`，没有 `processedImagePath`；模型的默认滤镜是 `MAGIC_COLOR`。

`CropScreen` 按路径与版本加载直立原图预览，当前最长边上限为 1600。切换滤镜时 `CropFilterPreview.render(source, filter)` 在后台运行 `NativePerspective.applyFilter`；RGBA/BGR 转换必须正确，所有 Mat 在成功和异常路径释放。原图加载与结构线检测不应因每次滤镜切换而重复。

预览任务串行处理原生调用，取消旧请求，并在发布前核对当前路径、版本和所选滤镜。不能让较早的处理结果覆盖最后一次选择。预览不写原图、草稿或已保存结果。

之前的 bug 是只更新 `selectedFilter`，显示的 bitmap 仍从原始草稿读取。回归时必须检查照片像素变化，不能只看按钮高亮。

### 确认、取消与旋转

`CropViewModel.confirmCrop → PageEditor.edit → DocumentProcessor.process → PageRepository.updatePage`。确认使用所选滤镜、裁剪框、比例与源旋转，从原图处理完整分辨率结果。

`sourceRotation` 表示裁剪前的源图方向，`rotation` 表示最终输出方向。裁剪框使用源图旋转后的完整尺寸坐标；结构线从小预览映射回完整坐标。滤镜预览保持原图尺寸与裁剪框，不提前进行透视裁切。预览和保存使用相同滤镜算法，但完整结果还会做透视裁切、比例调整与 JPEG 编码，不能要求两者每个像素完全相同。

原生滤镜映射为 Original=0、Magic=1、B&W=2、Gray=3。黑白采用 Sauvola 处理，暗字仍可能保留暗灰阶，不是严格只有 0/255 的二值图。

SQLite 当前为 `scans.db`、schema 1。数据结构变更需要迁移。自定义宽高比限制为 1/12 至 12，原生透视输出有 5000 万像素分配上限；这些限制不意味着高像素 HDR 的峰值内存已得到真机保证。

## 视觉设计与降级

- 保留 Apple 风格的悬浮玻璃按钮、清晰大标题、纸张预览和紫色柔光背景。
- `GlassScaffold` 记录 Compose 内容平面；玻璃操作采样该背景，文字、图标与其它玻璃操作不参与光学处理。
- Android 13+ 使用边缘折射，Android 12/12L 使用背景模糊，更旧版本及真实 CameraX 取景器使用带色半透明材质。
- 紫色流光约 28 秒循环，最多 30 帧/秒；离开前台、系统动画关闭、省电或「减少透明度」开启时停止运动。
- 保持背景独立图层的裁剪与按视口缓存的渐变 brush，改变这些绘制路径后重新做实际截图与动效验收。
- 真实相机画面不叠加流光；权限提示页使用深色流光背景。
- 需要实际检查明暗主题、窄屏、1.3 倍字体、触控区域和底部系统导航，避免控件遮住文字或纸张的重要内容。

## 验证入口与 CI 注意事项

| 入口 | 覆盖内容 |
| --- | --- |
| [PageRepositoryTest.kt](../app/src/test/java/com/scanner/app/PageRepositoryTest.kt) | 持久化恢复、排序与应用文件清理 |
| [ExportTest.kt](../app/src/test/java/com/scanner/app/ExportTest.kt) | 导出与失败回滚 |
| [DocumentQuadTest.kt](../app/src/test/java/com/scanner/app/DocumentQuadTest.kt) | 裁剪四边形有效性 |
| [NativePipelineTest.kt](../app/src/androidTest/java/com/scanner/app/NativePipelineTest.kt) | 四种滤镜、源/输出旋转、持久化、真实 PDF/JPEG 导出与原图字节不变 |
| [CropFilterPreviewTest.kt](../app/src/androidTest/java/com/scanner/app/CropFilterPreviewTest.kt) | 预览像素变化、灰度通道、黑白/增强区别、反复切换不叠加与旋转后坐标 |
| [capture-ui.py](../.github/scripts/capture-ui.py) | 实际点击、布局、滤镜像素、快速切换、取消/保存、减少透明度与背景运动 |

设备测试会清空扫描仓库并生成六张合成文档，界面脚本最后会删除这些页。只能在专用测试安装上执行，不对用户真实文档库运行。

`NativePipelineTest` 故意把这六页留给后续 UI 脚本，`coast` 样本只有原图且默认选择增强，用来模拟刚拍完的状态。保留工作流中的 `leaveApksInstalledAfterRun=true`，不要在截图前卸载应用或清空这些样本。新增设备测试或更改 fixture 时同时检查这项数据契约。

CI 使用 API 35、Google APIs、x86_64 模拟器，基本视口 480×800；另测 320×640 与 1.3 倍字体。保留 `-gpu swangle -feature -Vulkan`、2048M RAM、测试后停止 Gradle daemon 的配置。CI 只在隔离模拟器中停用捆绑的 Messages，以避开此前遮挡界面的“不响应”对话框；这不属于应用行为。

UI 自动化通过实际可访问性标签和控件 bounds 点击。当前非 Tab 滤镜按钮在 UIAutomator 中使用 `checkable=true, checked=true` 表示选中，不能只断言 `selected=true`。黑白和灰度都应消除色差，增强应保留颜色；必须用有色样本区分它们，白色空白或加载画面不能算成功。

Actions 产物：

- `debug-apks`：两个变体的调试安装包。
- `test-and-lint-reports`：单元测试与 lint。
- `integration-results`：设备测试报告、界面 PNG/XML、诊断日志及背景录屏。产物保留期由 Actions 决定，永久代码与历史结果入口以提交/运行链接为准。

仅文档变化检查 Markdown 相对链接与 `git diff --check` 即可；图片算法、状态、原生层或界面变化按影响范围运行上述回归。截图和合成测试通过不能替代真机相机验收。

## 未完成事项与接手建议

- HDR、OIS、识别准确率、OEM GPU 表现和高像素峰值内存仍需真机样张与性能验证。
- 连拍仍使用临时 JPEG；零拷贝相机处理、OCR 与 PDF 矢量文字尚未实现。
- `CameraViewModel` 仍较大，后续拆分应先固定真机行为与回归样本。
- 进程中断可能留下待发布 MediaStore 项；第三方 provider 拒绝删除时，回滚无法保证删除成功。
- 上游仅存于内存且已经丢失的扫描记录无法恢复。

接手步骤：先检查工作区与目标分支，再阅读用户的新需求和受影响源码；保留已有文档与扫描数据；完成相关验证后，在交付中给出实际提交、安装包版本、验证证据和仍未验证的事项。架构或流程变化同步维护此文及 README。
