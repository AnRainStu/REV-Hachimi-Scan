# REV Hachimi Scan

Refactored offline Android document scanner based on [eviau512/Hachimi-Scan](https://github.com/eviau512/Hachimi-Scan). Kotlin, Compose, CameraX and OpenCV.

[简体中文](README_zh.md) · [Builds and checks](https://github.com/AnRainStu/REV-Hachimi-Scan/actions)

## Changes

- The 0.4.1 interface uses large titles, an adaptive grid of paper document previews and separate floating glass controls. Scan and export stay distinct; editing tools use compact filter and aspect selections. Batch editing, ordering and deletion confirmation remain available. Camera permission is needed only for shooting.
- Android 13+ samples the Compose backdrop on the GPU, with local edge refraction and light diffusion. Android 12/12L uses backdrop blur; older systems and the native CameraX viewfinder use tinted materials. Text and icons stay outside the optical effect. Settings → Reduce transparency switches to solid controls; battery saver skips optical effects. Light and dark appearances follow the system. Glass action buttons scale to 0.97 on press with a 120 ms response and respect system animation scaling.
- Violet, lavender and blue-violet light bands drift across the background on a nominal 28-second cycle, leaving documents and controls still. Motion runs only while the screen is resumed, at up to 30 fps, and stops when system animations are disabled, battery saver is on, or transparency is reduced. The camera permission screen uses the dark background; the live CameraX viewfinder stays unobstructed.
- SQLite transactions persist pages, crop geometry, filters, rotations and ordering. Originals live in private durable storage rather than cache.
- Shared processing and serialized edits render new outputs from originals. Crop rotation uses a temporary draft and independent source orientation; failed edits retain the previous output.
- Dedicated CameraX preview lifecycle, executor disposal, correct stability results and Y-plane stride handling. Final photos are detected independently of preview orientation; overlay scaling matches the viewfinder.
- Checked PDF/JPEG exports use pending MediaStore rows, content URIs, failure rollback and direct sharing. PDFs go to `Download/HachiCam`; JPEG batches go to `Pictures/HachiCam/<name>`.
- JNI validates arrays, translates exceptions and releases bitmap locks and matrices on failure. Custom ratios and output allocation are bounded.
- Standard debug signing and environment-based release signing replace embedded passwords.

## Build

JDK 17, SDK 35, Build Tools 35.0.0, NDK 26.3.11579264 and CMake 3.22.1 are required. Set `ANDROID_HOME` or `sdk.dir` in untracked `local.properties`.

```sh
bash gradlew testHachimiDebugUnitTest lintHachimiDebug assembleHachimiDebug assembleStandardDebug
# With an Android 10+ device/emulator:
bash gradlew connectedHachimiDebugAndroidTest
```

Windows: use `gradlew.bat`. APKs: `app/build/outputs/apk/<edition>/debug/`. Universal APKs support arm64-v8a, armeabi-v7a and x86_64. GitHub Actions builds both editions, runs regression checks and tests the native pipeline on an Android 15 emulator.

Release signing uses `SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`; then run `assembleHachimiRelease`. Without those variables, release APKs are unsigned. The debug signature cannot update upstream-signed installations.

## Validation and limitations

Regression tests cover persistence, ordering, safe deletion, invalid geometry and export rollback. Device integration covers four native filters, source/output rotation, repository recovery, real PDF/JPEG export and original-file integrity.

CI interface checks use six upright document fixtures and capture light/dark appearances, content scrolling beneath floating controls, a 320 × 640 viewport at font scale 1.3, the library, preview, crop, export, camera and settings screens, reduced transparency, and the empty state after deleting all pages. Static and motion frames are collected, with a six-second MP4 recording attempted for motion inspection. Screenshots and reports are attached to each run; check the [Actions result](https://github.com/AnRainStu/REV-Hachimi-Scan/actions) for that run's status.

HDR quality, OIS behavior, detection accuracy and high-resolution memory use still require physical-device samples. The upstream HDR and curved-detection algorithms remain. Burst processing still uses temporary JPEGs; zero-copy capture, OCR and PDF vector text are not implemented. Already-lost upstream in-memory scans cannot be recovered.

See [refactor notes and manual checks](docs/REFACTOR.md). Upstream [algorithm specs](specs/) are preserved and may differ from the refactored application.

## Attribution

Original code and history: [eviau512/Hachimi-Scan](https://github.com/eviau512/Hachimi-Scan). Source remains [GPLv3](LICENSE), specs remain [CC0](specs/LICENSE), and [artwork notices](ARTWORK_NOTICE.md) are preserved.
