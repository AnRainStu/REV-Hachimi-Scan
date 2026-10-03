# REV Hachimi Scan

基于 [eviau512/Hachimi-Scan](https://github.com/eviau512/Hachimi-Scan) 的 Android 本地文档扫描器重构版，保留项目历史、Kotlin、Compose、CameraX 与 OpenCV 技术栈。

## 改动

- 0.4.1 界面使用大标题、自适应纸张文档网格和独立悬浮玻璃操作；扫描与导出分别呈现，编辑使用紧凑的滤镜和比例选择。保留批量编辑、排序和删除确认。无相机权限仍可查看或导入图片。
- Android 13 及以上在 GPU 上采样 Compose 背景，进行局部边缘折射与轻微扩散；Android 12/12L 使用背景模糊，旧系统和原生 CameraX 取景器使用带色半透明材质。文字与图标不参与光学效果。「减少透明度」切换为实色操作，省电模式跳过光学效果；明暗主题跟随系统。玻璃操作按钮按压时缩放至 0.97，反馈为 120 毫秒，并尊重系统动画缩放设置。
- 紫色、薰衣草和蓝紫色柔光带以约 28 秒为周期缓慢漂移，纸张与控件保持静止。背景仅在页面处于前台时更新，最多 30 帧/秒；系统动画关闭、省电模式或「减少透明度」开启时停止运动。相机权限页复用深色背景，真实 CameraX 取景画面不叠加柔光带。
- SQLite 持久化页序、裁剪框、滤镜和旋转；原图使用应用私有持久目录，避免缓存清理和进程退出导致丢失。
- 裁剪与预览共用图片处理服务；编辑生成新文件，成功后提交。裁剪旋转使用草稿，取消不会修改原图。
- 相机生命周期与分析线程独立管理；修复稳定判断、Y 平面步长、预览边框缩放与横竖拍坐标。
- PDF/JPEG 写完才发布，失败回滚，导出后可直接分享。PDF 位于 `Download/HachiCam`，JPEG 位于 `Pictures/HachiCam/文件名`。
- JNI 校验数组并转换异常，修复失败路径资源清理；限制比例与图片输出尺寸。移除硬编码签名密码。

## 构建

需要 JDK 17、SDK 35、Build Tools 35.0.0、NDK 26.3.11579264、CMake 3.22.1；设置 `ANDROID_HOME` 或本地 `local.properties` 的 `sdk.dir`。

```sh
./gradlew testHachimiDebugUnitTest lintHachimiDebug assembleHachimiDebug assembleStandardDebug
./gradlew connectedHachimiDebugAndroidTest
```

Windows 使用 `gradlew.bat`。APK 位于 `app/build/outputs/apk/<edition>/debug/`。[GitHub Actions](https://github.com/AnRainStu/REV-Hachimi-Scan/actions) 提供构建与测试报告。

Release 签名通过 `SIGNING_STORE_FILE`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD` 配置；未配置则不签名。重构版的调试签名不能覆盖安装上游签名版本。

## 验证边界

回归测试覆盖恢复、排序、文件清理、非法裁剪框和导出回滚；设备集成测试覆盖四种原生滤镜、源图/输出旋转、恢复、真实 PDF/JPEG 导出和原图不变性。

CI 界面检查使用六张竖向文档样本，记录明暗主题、内容在悬浮操作下滚动、320 × 640 视口与 1.3 倍字体，以及文档库、预览、裁剪、导出、相机、设置、减少透明度和删空后的状态。另记录静态与动效帧，并尝试录制 6 秒 MP4 用于检查背景运动。每次运行附带截图与报告；具体结果以该次 [Actions 记录](https://github.com/AnRainStu/REV-Hachimi-Scan/actions) 为准。

HDR 效果、OIS、识别准确率和高像素内存消耗仍需真机样张验证。HDR 与曲线识别保留上游算法；连拍仍使用临时 JPEG，没有实现零拷贝、OCR 或 PDF 矢量文字。上游已丢失的内存记录无法恢复。

详细架构和手动检查见 [docs/REFACTOR.md](docs/REFACTOR.md)。原始规格保留于 `specs/`，部分描述可能与重构后的应用不同。

## 来源与许可证

原项目：[eviau512/Hachimi-Scan](https://github.com/eviau512/Hachimi-Scan)。代码保持 [GPLv3](LICENSE)，技术规格保持 [CC0](specs/LICENSE)，图像说明保持 [ARTWORK_NOTICE.md](ARTWORK_NOTICE.md)。
