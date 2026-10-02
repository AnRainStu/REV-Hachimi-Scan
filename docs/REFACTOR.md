# Refactor notes

## Glass interface (0.3.0)

`GlassScaffold` records the Compose content plane with `rememberGraphicsLayer`; floating navigation and docks sample that layer in their local coordinates. Only the sampled backdrop gets a GPU blur. Chrome is drawn afterwards and excluded from the recording, avoiding recursive feedback and blurred labels. Library content can scroll behind the floating controls; document cards keep opaque paper surfaces.

The tint, edge highlight, rounded outline and shadow form an Apple-inspired material without copying platform assets or adding fonts. There are no continuously animated light effects or per-frame bitmap captures. Android 10/11 and the native CameraX viewfinder use a tinted fallback. Reduce transparency is persisted in settings and immediately changes all toolbars to solid surfaces; battery saver disables backdrop blur. The default Compose controls retain native focus, touch feedback and system animation scaling.

CI navigates by accessibility labels/bounds and captures the library in light/dark appearances, editor, crop, export, camera, settings and opaque mode on a real Android emulator. Existing native integration and data/export tests run alongside these navigation checks. Physical-device GPU cost and OEM rendering differences remain manual checks.

## Responsibility boundaries

`PageStore` owns SQLite and metadata serialization. `PageRepository` serializes writes, publishes committed state, removes superseded app-owned files and cleans abandoned drafts on startup. Missing-image records remain visible rather than silently disappearing.

`DocumentProcessor` validates geometry, rotates the source, warps and filters, applies output rotation and saves a unique result. `PageEditor` serializes crop/review work and reads the current record inside the edit lock. Failed edits preserve the committed metadata and output.

`CropViewModel` keeps local geometry, filter and aspect choices. Draft rotation changes `sourceRotation`; review rotation changes `rotation`. Crop geometry is measured after source rotation. Neither operation modifies the original JPEG.

`CameraPreview` owns CameraX binding and the executor. `CameraViewModel` coordinates capture and import. Final images are normalized to upright JPEGs and detected independently of preview coordinates.

`MediaStoreWriter` owns pending exports. Exporters publish after all writes succeed and attempt deletion of all inserted rows on failure. Process interruption can leave pending rows until Android expires them; deletion cannot be guaranteed if the provider refuses it.

## Manual device checks

1. Deny camera permission. View/export saved scans and import an image. Grant permission from the camera screen or settings and return.
2. Capture in portrait and landscape. Check overlay and crop geometry. Compare Normal and Full HDR on a supported physical camera.
3. Crop, rotate and change filters/ratios. Cancel crop rotation and verify the committed result and original are unchanged. Confirm rotation and reopen crop.
4. Reorder edited pages, force-stop and restart. Verify geometry, filters and order survive. Delete pages and verify owned files are removed.
5. Export, open and share PDF/JPEG results. Repeat an export name. Simulate storage failure and verify an error replaces success.
6. Inspect light/dark themes, large fonts, tablet widths and long translations.

## Remaining work

Burst capture still uses temporary JPEG files. Native detection/fusion algorithms retain upstream behavior and need physical-device samples. CameraViewModel can be split further after hardware behavior is characterized. High-resolution fusion can still consume substantial memory.

SQLite schema version 1 retains every model field. Future schema changes require explicit migrations; no destructive fallback is provided. JPEG export copies processed bytes without recompression. OCR and PDF vector text are not implemented.
