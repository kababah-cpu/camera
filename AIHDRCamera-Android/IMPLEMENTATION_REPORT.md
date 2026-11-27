# AI HDR Camera — Implementation Report
**Report date:** 2026-11-27  
**Repository:** `kababah-cpu/camera`  
**Android project:** `AIHDRCamera-Android`  
**Application ID:** `com.fieldlight.aihdr`

## 1. Executive summary

The repository already contains a native Android application built with Camera2, Jetpack Compose, and OpenCV. It is a useful starting point for the requested computational-camera experience. A GitHub Actions workflow already builds and uploads an APK when files under this Android project change.

The current implementation is a functional HDR capture prototype, not yet the full continuously metered, single-frame local-tone-mapping camera specified in the product brief. This report records the verified baseline and defines the implementation sequence. No feature-completion claim is made for functionality that has not yet been implemented or device-tested.

## 2. Verified baseline

| Area | Present in repository | Current limitation |
|---|---|---|
| Android app | Kotlin, Camera2, Jetpack Compose; min SDK 26, compile/target SDK 35 | Device compatibility still requires testing |
| Live viewfinder | Camera2 preview rendered through TextureView | Preview controller currently uses automatic camera settings; no live scene-analysis loop |
| Capture | Standard capture and a Camera2 multi-exposure HDR path | HDR path uses multiple exposures; this does not meet the requested single-instant, no-frame-averaging capture policy |
| HDR processing | OpenCV 4.10.0 dependency; AlignMTB / MergeMertens pipeline documented | Not yet the requested edge-preserving bilateral local tone-mapping pipeline |
| Exposure controls | One lighting-boost EV slider in the Compose screen | No separate shutter, ISO, shadow lift, highlight guard, or fusion controls |
| Camera controls | Front/rear switch | Focus reticle, AE/AF lock, grid, torch, and lens zoom controls are not present in the inspected screen |
| Output | JPEG saved to Pictures/AI HDR Camera | Original and balanced variants are not both retained for comparison |
| Diagnostics | Capture timing report can be saved to Downloads | No continuous 48-bin histogram, clipping/crush metrics, dynamic-range estimate, or scene classifier |
| Test workflow | Android GitHub Actions workflow builds debug APK by default | Signed release build requires repository signing secrets |

## 3. Product requirements translated into work packages

### P0 — Build reliability and baseline verification
- Keep the project native Android; do not introduce a hosted WebView dependency.
- Build the debug APK through the existing GitHub Actions workflow.
- Record build result, artifact name, commit, and limitations in the report.
- Validate install and camera permission flow on a physical Android device.

### P1 — Camera interaction and capture safety
- Add tap-to-meter focus reticle, AE/AF lock, rule-of-thirds grid, torch, zoom steps, and front/rear switching.
- Read camera characteristics before exposing controls; disable unsupported hardware features rather than pretending they are active.
- Preserve a crisp single-frame original capture. Keep any multi-frame HDR mode clearly identified as optional and separate until alignment and motion handling are validated.
- Save original and processed outputs as separate files with matching capture metadata.

### P2 — Fast scene metering
- Analyze low-resolution preview frames asynchronously, with a measured latency target below 200 ms.
- Calculate a 48-bin luminance histogram, highlight clipping, shadow crush, foreground/perimeter luminance, and an estimated dynamic range.
- Add a scene classifier for backlit, high-dynamic-range, low-light, bright-daylight, and balanced scenes.
- Smooth control recommendations and clearly distinguish measured values from estimates.

### P3 — Local tone mapping and preview
- Implement an edge-preserving local-luminance base layer and restrained highlight/shadow reconstruction.
- Use the same resolution-independent tone-mapping parameters for preview and full-resolution processing.
- Benchmark frame time and memory on representative devices. Treat 60 fps as a target, not a guarantee across all hardware.
- Avoid halos and false detail; provide a neutral/original comparison.

### P4 — Pro controls, review, and calibration
- Add Auto Balance / Pro Manual modes with EV, shutter, ISO, local fusion, shadow lift, highlight guard, and AWB controls where supported.
- Add split comparison, Original/Balanced toggles, capture review, and photo import for offline testing.
- Include repeatable high-contrast calibration scenes and document expected behavior.

## 4. Performance and validation criteria

| Metric | Target | Validation method |
|---|---:|---|
| Preview analysis | under 200 ms | Timestamped frame-analysis measurements on device |
| Live rendering | 60 fps target | Frame-time profiling; report device-specific results |
| Capture sharpness | single-instant original retained | Compare original bytes and inspect motion/edge detail |
| JPEG quality | quality setting 97% or higher | Verify encoder configuration and output metadata |
| Metering | 48 histogram bins | Unit tests using known synthetic luminance distributions |
| Exposure behavior | smooth convergence under 1 second | Record control trajectory on repeatable test scenes |
| Local HDR | no visible halos in test set | Side-by-side review of backlit edges and bright windows |
| Device controls | capability-aware | Test Camera2 characteristics and request results per device |

## 5. Risks and constraints

- Camera2 capabilities vary by manufacturer, sensor, and camera mode. Exposure time, sensitivity, torch, zoom, and AF/AE behavior must be capability-gated.
- A preview analysis target below 200 ms is feasible only with a reduced-resolution analysis path and careful threading; it is not a guaranteed end-to-end capture latency.
- True multi-exposure fusion can introduce motion artifacts. The original single-frame capture must remain available and must not be replaced by an unaligned average.
- A generic Gray-World white-balance estimate can be wrong under colored lighting; AWB should remain optional and conservative.
- A debug APK is suitable for testing. A release APK intended for repeatable upgrades needs a private signing keystore and GitHub Actions secrets. Never commit signing keys or passwords.

## 6. Immediate next steps

1. Build the current baseline using the existing `.github/workflows/android.yml` workflow.
2. Inspect build logs and fix compile or packaging errors before feature work.
3. Implement P1 camera controls and original/balanced capture retention.
4. Implement and unit-test the preview metering engine independently of the UI.
5. Integrate local tone mapping only after benchmark and image-quality tests pass.
6. Update this report after each build with commit SHA, workflow result, artifact, device tests, and known limitations.

## 7. Current status

**Status at report creation:** Baseline source inspected; build workflow exists; full product requirements are not yet complete.  
**Next gate:** Successful CI debug APK build, followed by physical-device installation and camera smoke test.  
**Release signing:** Not verified; depends on private signing secrets configured by the repository owner.
