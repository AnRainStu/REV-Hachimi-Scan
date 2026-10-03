# Refactor notes

## Glass interface (0.4.1)

The library uses a large title and an adaptive grid of paper document previews. Navigation, selection, scan and export are separate floating controls. Preview and crop tools use compact filter and aspect selections; camera controls float independently around the full-screen viewfinder. Library content can scroll beneath these controls while document cards keep opaque paper surfaces.

The status edge fades scrolling content away from system icons. Brand and primary tool surfaces defocus background lettering with an 8 dp Gaussian pass before edge refraction, while small clear navigation controls retain more detail. Scan/export controls are centered, and narrow layouts show the app mark without a truncated brand name; the camera's page count uses a badge beside the review icon.

`GlassScaffold` records the Compose content plane with `rememberGraphicsLayer`; glass controls sample it in their local coordinates. On API 33+, a GPU shader refracts the sampled image near each control's rounded edge and applies light diffusion. API 31/32 uses backdrop blur. Foreground labels and icons are drawn afterwards and excluded from the recorded plane, avoiding recursive feedback and blurred text. Older systems and the native CameraX viewfinder use a tinted fallback; the camera's native preview is not captured for refraction. Backdrop processing uses no per-frame bitmap readbacks.

`AmbientBackground` draws violet, lavender and blue-violet light bands on an independent graphics layer. Sine/cosine drift has a nominal 28-second period, capped at 30 fps; the phase is read during drawing without recomposing paper or controls. `repeatOnLifecycle(RESUMED)` pauses updates away from the foreground. A `ContentObserver` responds immediately to system animation changes; a zero animation scale, battery saver or Reduce transparency stops the motion. The camera permission screen reuses the dark ambient background, while the live CameraX preview is not covered by it.

Reduce transparency is persisted in settings and switches glass controls to solid surfaces. Battery saver also skips optical effects. Glass action buttons scale to 0.97 while pressed with a 120 ms response; system animation scaling is respected, including disabled animations. Controls retain native focus, touch semantics and readable foreground colors.

CI seeds six upright document fixtures, navigates by accessibility labels and bounds, and captures the library in light/dark appearances and after scrolling beneath the floating controls. It also exercises a 320 × 640 viewport with font scale 1.3, preview, crop, export, camera, settings, reduced transparency, and the library's empty state after deleting all pages. Background inspection includes static and motion frames and an attempted six-second MP4 recording. Native integration and data/export tests remain alongside these interface checks. Each run publishes screenshots and reports; its Actions result records whether that run passed. Physical-device GPU cost and OEM rendering differences remain manual checks.

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
