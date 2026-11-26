# AI HDR Camera

A native Android Camera2 + Jetpack Compose application with OpenCV exposure fusion.

## Build
Open this directory in Android Studio or run:

```bash
gradle --no-daemon :app:assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Current implementation
- Native Jetpack Compose camera controls and TextureView Camera2 preview.
- Standard capture and a three-exposure Camera2 HDR capture path.
- OpenCV AlignMTB and MergeMertens fusion, followed by LAB CLAHE and finishing.
- Saves captured JPEG output to Pictures/AI HDR Camera through MediaStore.
- No remote WebView or hosted website dependency.

## Device testing
Camera2 capabilities differ by device. Confirm exposure bracketing, focus stability, gallery saving, and output quality on the target phone. The app is not considered production-ready until those tests pass.