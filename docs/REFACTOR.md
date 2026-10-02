# Refactor notes

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
