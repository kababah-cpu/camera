package com.fieldlight.aihdr

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.TextureView
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class CaptureUiResult(val uri: Uri, val diagnostics: String?)

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val scope = rememberCoroutineScope()
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var hdrEnabled by remember { mutableStateOf(true) }
    var exposureBoost by remember { mutableStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var meteringStatus by remember { mutableStateOf("Starting scene analysis…") }
    var lastPhoto by remember { mutableStateOf<Uri?>(null) }
    var hdrReport by remember { mutableStateOf<String?>(null) }
    var frontCamera by remember { mutableStateOf(false) }
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    val preview = remember { CameraPreviewController(context) }
    val camera = remember { Camera2HdrCapture(context) }

    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
        if (it) textureView?.let { view -> preview.attach(view, frontCamera) }
    }

    LaunchedEffect(hasPermission, textureView, frontCamera) {
        if (hasPermission) textureView?.let { view -> preview.attach(view, frontCamera) }
    }

    DisposableEffect(Unit) {
        preview.setMeteringListener { summary, _ -> meteringStatus = summary }
        onDispose {
            preview.setMeteringListener(null)
            preview.release()
            camera.close()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { viewContext ->
                TextureView(viewContext).also { view -> textureView = view }
            },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Familiar camera toolbar.
            Surface(
                color = Color.Black.copy(alpha = 0.52f),
                shape = RoundedCornerShape(22.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("AI CAMERA", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text("SMART EXPOSURE", color = Color.White.copy(alpha = 0.68f), style = MaterialTheme.typography.labelSmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (hdrEnabled) "HDR" else "PHOTO", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.size(8.dp))
                        Switch(checked = hdrEnabled, onCheckedChange = { hdrEnabled = it })
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!hasPermission) {
                    Surface(color = Color.Black.copy(alpha = 0.72f), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Camera access is required to use the viewfinder.", color = Color.White)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { permissionRequest.launch(Manifest.permission.CAMERA) }) {
                                Text("Allow camera")
                            }
                        }
                    }
                } else {
                    Surface(color = Color.Black.copy(alpha = 0.66f), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text("EXPOSURE", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Slider(
                                value = exposureBoost,
                                onValueChange = {
                                    exposureBoost = it
                                    preview.setExposureBias(it)
                                },
                                valueRange = -1f..2f,
                                enabled = !busy
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("−1 EV", color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall)
                                Text(String.format(Locale.US, "%+.1f EV", exposureBoost), color = Color.White, style = MaterialTheme.typography.labelMedium)
                                Text("+2 EV", color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Surface(color = Color.Black.copy(alpha = 0.62f), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
                            Text(meteringStatus, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                            Spacer(Modifier.height(4.dp))
                            Text(status, color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    if (hdrReport != null) {
                        Surface(color = Color.Black.copy(alpha = 0.72f), shape = RoundedCornerShape(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("HDR diagnostics ready", color = Color.White, style = MaterialTheme.typography.labelMedium)
                                Button(
                                    onClick = {
                                        val report = hdrReport ?: return@Button
                                        scope.launch {
                                            val saved = withContext(Dispatchers.IO) {
                                                runCatching { saveHdrReport(context, report) }
                                            }
                                            status = if (saved.isSuccess) "Report saved in Downloads / AI HDR Camera"
                                                else saved.exceptionOrNull()?.message ?: "Could not save report"
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC857), contentColor = Color.Black)
                                ) { Text("Save report") }
                            }
                        }
                    }

                    // Standard gallery / shutter / camera-switch control cluster.
                    Surface(color = Color.Black.copy(alpha = 0.72f), shape = RoundedCornerShape(28.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    lastPhoto?.let { uri ->
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, "image/jpeg")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(intent)
                                    }
                                },
                                enabled = lastPhoto != null,
                                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                            ) { Text("Gallery") }

                            Button(
                                onClick = {
                                    if (busy) return@Button
                                    busy = true
                                    hdrReport = null
                                    status = if (hdrEnabled) "Capturing HDR…" else "Capturing photo…"
                                    preview.stopPreview()
                                    val shutterStartMs = SystemClock.elapsedRealtime()
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            try {
                                                if (hdrEnabled && !OpenCVLoader.initLocal()) {
                                                    error("OpenCV could not initialize. Standard mode is still available.")
                                                }
                                                @Suppress("DEPRECATION")
                                                val displayRotation = activity?.windowManager?.defaultDisplay?.rotation
                                                    ?: Surface.ROTATION_0
                                                val jpegOrientation = camera.jpegOrientation(frontCamera, displayRotation)
                                                if (hdrEnabled) {
                                                    // Keep the reference HDR capture/fusion path unchanged.
                                                    val hdr = camera.captureHdr(exposureBoost.toDouble(), frontCamera, jpegOrientation)
                                                    val uri = savePhoto(context, hdr.jpeg, jpegOrientation).getOrThrow()
                                                    val totalMs = SystemClock.elapsedRealtime() - shutterStartMs
                                                    Result.success(CaptureUiResult(uri, hdr.diagnostics.toReport(totalMs)))
                                                } else {
                                                    val appliedCompensation = preview.currentExposureCompensation()
                                                    val bytes = camera.captureStandard(frontCamera, jpegOrientation, appliedCompensation)
                                                    Result.success(CaptureUiResult(savePhoto(context, bytes, jpegOrientation).getOrThrow(), null))
                                                }
                                            } catch (error: Exception) {
                                                Result.failure<CaptureUiResult>(error)
                                            }
                                        }
                                        result.fold(
                                            onSuccess = {
                                                lastPhoto = it.uri
                                                hdrReport = it.diagnostics
                                                status = "Photo saved"
                                            },
                                            onFailure = { status = it.message ?: "Capture failed" }
                                        )
                                        textureView?.surfaceTexture?.let { preview.startPreview(it) }
                                        busy = false
                                    }
                                },
                                enabled = !busy && hasPermission,
                                shape = CircleShape,
                                modifier = Modifier.size(86.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC857), contentColor = Color.Black)
                            ) {
                                if (busy) CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(30.dp))
                                else Text("SHUTTER", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = {
                                    frontCamera = !frontCamera
                                    preview.setFacing(frontCamera)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                            ) { Text("Flip") }
                        }
                    }
                }
            }
        }
    }
}

private fun savePhoto(context: android.content.Context, jpeg: ByteArray, orientationDegrees: Int): Result<Uri> {
    return try {
        val resolver = context.contentResolver
        val name = "AIHDR_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AI HDR Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create a gallery photo.")
        resolver.openOutputStream(uri)?.use { it.write(jpeg) } ?: error("Could not write the photo.")
        val orientation = when (((orientationDegrees % 360) + 360) % 360) {
            90 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90
            180 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180
            270 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270
            else -> androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
        }
        resolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
            val exif = androidx.exifinterface.media.ExifInterface(descriptor.fileDescriptor)
            val timestamp = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, orientation.toString())
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME, timestamp)
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL, timestamp)
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_SOFTWARE, "AI HDR Camera")
            exif.saveAttributes()
        } ?: error("Could not reopen the saved photo to write EXIF metadata.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val complete = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, complete, null, null)
        }
        Result.success(uri)
    } catch (error: Exception) {
        Result.failure(error)
    }
}

private fun saveHdrReport(context: android.content.Context, report: String): Uri {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        error("Saving a shared diagnostics report requires Android 10 or later.")
    }
    val resolver = context.contentResolver
    val name = "AIHDR_Diagnostics_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AI HDR Camera")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: error("Could not create the diagnostics report in Downloads.")
    try {
        resolver.openOutputStream(uri)?.use { it.write(report.toByteArray(Charsets.UTF_8)) }
            ?: error("Could not write the diagnostics report.")
        val complete = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, complete, null, null)
        return uri
    } catch (error: Exception) {
        resolver.delete(uri, null, null)
        throw error
    }
}
