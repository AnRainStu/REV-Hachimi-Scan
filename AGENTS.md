# Agent guide

This guide applies to the whole repository. Start with [the handoff](docs/AGENT_HANDOFF.md), [the refactor notes](docs/REFACTOR.md), and the relevant source files. Explicit user instructions take precedence over this guide. The source and build configuration determine current behavior; upstream `specs/` describe historical intentions and are not evidence that a feature is implemented.

## Project and entry points

- Android document scanner using Kotlin, Jetpack Compose, CameraX and OpenCV/C++ JNI.
- `origin`: https://github.com/AnRainStu/REV-Hachimi-Scan.git. `upstream`: https://github.com/eviau512/Hachimi-Scan.git. Use the destination authorized by the user; the upstream project is a reference.
- Application entry: `ScannerApplication.kt` initializes the repository and OpenCV; `ui/navigation/NavGraph.kt` starts in the document library.
- Camera preview ownership includes its CameraX use cases, analyzer executor and coordinate transforms. Always close analyzed `ImageProxy` frames, and keep preview scaling and overlay transforms aligned; final photos are detected independently.
- Kotlin package root: `app/src/main/java/com/scanner/app/`. Native implementation: `app/src/main/cpp/`.
- Main responsibility boundaries: `data/repository/` for persistence, `data/image/` for edits, `data/export/` for exports, `ui/camera/` for capture, `ui/crop/` for crop and filter preview, `ui/review/` for the library and saved-page editing.
- Shared glass controls and violet background live in `ui/components/`. Add user-facing text through `app/src/main/res/values*/strings.xml`.

## Editing invariants

1. Preserve the original scan file. `DocumentProcessor` renders a unique result from the original; `PageEditor` serializes edits; `PageRepository` publishes only after persistence succeeds. A failed edit must retain the previous saved metadata and image.
2. SQLite `scans.db` currently uses schema version 1. Schema changes need a real migration that preserves scans. Cleanup must remain limited to unreferenced files owned by the application's scan directory.
3. Distinguish `sourceRotation` from output `rotation`. Crop corners and structural lines use full-size upright source coordinates after source rotation. A smaller preview bitmap does not change those coordinates.
4. Capture/import navigates to `CropScreen`. Filter selection must change the visible photo immediately, including when `processedImagePath` is null. Updating the button or enum alone does not implement a preview.
5. `CropFilterPreview.render` uses the same native filter implementation as saving, on an unmodified source bitmap. Keep source decoding/line detection separate from filter changes. Keep the preview bounded, and prevent cancelled or superseded requests from publishing old results.
6. Preview and draft changes do not commit saved edits. Confirm snapshots the choices and renders a result; Cancel preserves the original and previously saved edit. Do not call `PageEditor.edit` merely to display a filter preview.
7. Native filter IDs are explicit: Original = 0, Magic = 1, B&W = 2, Gray = 3. Do not replace this mapping with `ImageFilter.ordinal`; the Kotlin enum order differs.
8. Decode, filter, rotate and export work runs away from the main thread. Release OpenCV matrices and JNI bitmap locks on success and failure. Do not recycle a bitmap while Compose or a native reader still owns it.
9. Exporters use pending MediaStore rows and publish after successful writes; preserve rollback and content-URI sharing behavior.

## Interface constraints

- Retain the Apple-inspired floating glass controls, opaque paper previews, clear typography and violet ambient background.
- Glass optical processing excludes foreground text/icons and other glass controls. API 33+ uses local refraction, API 31/32 backdrop blur, and older/native-preview paths use a tinted fallback.
- The live CameraX viewfinder remains readable. Ambient light belongs behind Compose content and the permission screen, not over the actual camera image.
- Ambient motion has a nominal 28-second period and a 30 fps cap; pause it away from the resumed lifecycle, with disabled system animations, battery saver or reduced transparency.
- Preserve the ambient layer's clipping and viewport-cached gradient brushes. Changes to these drawing paths need actual renderer verification.
- Preserve readable solid controls with Reduce transparency, light/dark appearances, narrow layouts, large fonts, and accessible labels and hit areas.
- UI automation locates controls through accessibility bounds. Non-tab filter choices currently expose their selected state as `checkable/checked` in UIAutomator; see `assert_filter_checked` in the capture script.

## Build and verification

Run commands from the repository root. Use JDK 17 and the pinned toolchain described in [the handoff](docs/AGENT_HANDOFF.md).

Run Gradle serially within a checkout. Concurrent agents can inspect different modules, but overlapping Gradle builds share generated outputs and can corrupt incremental compilation.

```sh
bash gradlew testHachimiDebugUnitTest lintHachimiDebug assembleHachimiDebug assembleStandardDebug --console=plain
# Disposable Android device/emulator:
bash gradlew connectedHachimiDebugAndroidTest --console=plain
```

On Windows use `.\gradlew.bat`. When diagnosing the previously observed Windows incremental compiler issue, pass `'-Pkotlin.incremental=false'` as a quoted argument in PowerShell.

- Run checks appropriate to the change. Native/image-processing changes need device tests; UI changes need actual screenshots and interaction checks. Do not treat a build or a changed button highlight as proof that a filter changed the photo.
- `.github/workflows/android.yml` defines both editions' build checks and an API 35 emulator integration job. `.github/scripts/capture-ui.py` validates controls, filter pixels, cancellation, confirmation, themes, large text, and background motion.
- The native fixture setup leaves six pages for the subsequent UI script, with `coast` deliberately unprocessed. Preserve `leaveApksInstalledAfterRun=true` and the fixture data until UI checks finish.
- Run instrumentation and the capture script only on a disposable test installation: the fixture pipeline clears the scan repository and the UI script deletes all fixture pages. Do not aim it at a user's real scan library.
- Preserve the CI emulator renderer and its isolated Messages workaround unless changing them is part of the task and the replacement has been verified.
- For documentation-only changes, check relative links and `git diff --check`; an application rebuild and version bump are unnecessary.
- Report what was verified and its limits. Synthetic emulator fixtures do not establish physical-camera HDR quality, OIS behavior, detection accuracy or OEM GPU performance.

## Repository hygiene and handoff

- Inspect the worktree before editing and preserve unrelated user changes. Keep native ownership, persistence and UI concerns in their existing layers.
- Keep local SDK paths, signing keys/passwords, tokens, personal scans and generated build products out of Git. Use Actions artifacts for generated reports and APKs.
- Preserve GPLv3 code attribution, CC0 specification attribution and `ARTWORK_NOTICE.md`.
- When a change affects the architecture or verification workflow, update `docs/AGENT_HANDOFF.md` and the relevant README. Date verified baselines and link their exact commit and Actions run; distinguish them from checks that are still pending.
