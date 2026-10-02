# REV Hachimi Scan

基于 [eviau512/Hachimi-Scan](https://github.com/eviau512/Hachimi-Scan) 的 Android 本地文档扫描器重构版，保留项目历史、Kotlin、Compose、CameraX 与 OpenCV 技术栈。

## 改动

- 苹果风格界面：悬浮玻璃导航与编辑工具栏、中性色与蓝色操作、大标题、自适应文档网格，保留批量编辑、排序和删除确认。无相机权限仍可查看或导入图片。
- Android 12 及以上为 Compose 内容提供 GPU 背景模糊，文字与图标保持清晰，玻璃带有柔和高光与阴影。旧版 Android 和 CameraX 取景器使用半透明材质；设置中可打开「减少透明度」切换为实色工具栏，省电模式跳过模糊。明暗主题跟随系统。
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

HDR 效果、OIS、识别准确率和高像素内存消耗仍需真机样张验证。HDR 与曲线识别保留上游算法；连拍仍使用临时 JPEG，没有实现零拷贝、OCR 或 PDF 矢量文字。上游已丢失的内存记录无法恢复。

详细架构和手动检查见 [docs/REFACTOR.md](docs/REFACTOR.md)。原始规格保留于 `specs/`，部分描述可能与重构后的应用不同。

## 来源与许可证

原项目：[eviau512/Hachimi-Scan](https://github.com/eviau512/Hachimi-Scan)。代码保持 [GPLv3](LICENSE)，技术规格保持 [CC0](specs/LICENSE)，图像说明保持 [ARTWORK_NOTICE.md](ARTWORK_NOTICE.md)。
