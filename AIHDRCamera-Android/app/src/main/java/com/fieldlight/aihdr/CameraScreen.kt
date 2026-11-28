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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CamGreen = Color(0xFF00D18F)
private val CamWhite = Color(0xFFF5F5F5)
private val CamBlack = Color(0xFF050505)

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val scope = rememberCoroutineScope()
    var hasPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var hdrEnabled by remember { mutableStateOf(true) }
    var exposureBoost by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("READY") }
    var lastPhoto by remember { mutableStateOf<Uri?>(null) }
    var frontCamera by remember { mutableStateOf(false) }
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var selectedMode by remember { mutableStateOf("AI CAM") }
    var zoom by remember { mutableIntStateOf(1) }
    var showQuickSettings by remember { mutableStateOf(false) }
    val preview = remember { CameraPreviewController(context) }
    val camera = remember { Camera2HdrCapture(context) }

    // Bound outstanding HDR jobs to keep JPEG/native memory use predictable.
    val hdrQueue = remember { Channel<Pair<HdrCapturedFrames, Int>>(Channel.UNLIMITED) }
    val hdrSlots = remember { Semaphore(2) }

    LaunchedEffect(camera) {
        for ((capturedFrames, orientation) in hdrQueue) {
            try {
                val hdr = withContext(Dispatchers.Default) {
                    if (!OpenCVLoader.initLocal()) error("OpenCV initialization failed")
                    camera.processHdrFrames(capturedFrames)
                }
                val uri = withContext(Dispatchers.IO) {
                    savePhoto(context, hdr.jpeg, orientation).getOrThrow()
                }
                lastPhoto = uri
                status = "HDR PHOTO SAVED"
            } catch (error: Exception) {
                status = error.message ?: "HDR PROCESSING FAILED"
            } finally {
                hdrSlots.release()
            }
        }
    }

    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
        if (it) textureView?.let { view -> preview.attach(view, frontCamera) }
    }
    LaunchedEffect(hasPermission, textureView, frontCamera) {
        if (hasPermission) textureView?.let { preview.attach(it, frontCamera) }
    }
    DisposableEffect(Unit) {
        onDispose { hdrQueue.close(); preview.release(); camera.close() }
    }

    Box(Modifier.fillMaxSize().background(CamBlack)) {
        AndroidView(factory = { TextureView(it).also { view -> textureView = view } }, modifier = Modifier.fillMaxSize())

        // Composition grid, matching the reference camera viewfinder.
        Canvas(Modifier.fillMaxSize()) {
            val sx = size.width / 3f
            val sy = size.height / 3f
            val line = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
            for (i in 1..2) {
                drawLine(Color.White.copy(alpha = 0.42f), androidx.compose.ui.geometry.Offset(sx * i, 0f), androidx.compose.ui.geometry.Offset(sx * i, size.height), 1.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.42f), androidx.compose.ui.geometry.Offset(0f, sy * i), androidx.compose.ui.geometry.Offset(size.width, sy * i), 1.dp.toPx())
            }
        }

        Column(Modifier.fillMaxSize()) {
            // Top camera controls.
            Row(
                Modifier.fillMaxWidth().height(76.dp).background(CamBlack).padding(horizontal = 22.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                TopCamControl("◎", "Focus") { status = "Tap the preview to focus" }
                TopCamControl(if (showQuickSettings) "ϟ" else "ϟ⊘", "Flash") { status = "Flash: auto" }
                Box(Modifier.size(68.dp, 44.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFF202020)).clickable { showQuickSettings = !showQuickSettings }, contentAlignment = Alignment.Center) {
                    Text(if (showQuickSettings) "⌄" else "⌃", color = CamWhite, fontSize = 27.sp)
                }
                TopCamControl("50M", "Resolution") { status = "50 MP sensor mode (camera output may be lower)" }
                TopCamControl("♧", "Filters") { status = "Natural colour profile" }
                TopCamControl("▣", "Aspect ratio") { status = "4:3 capture" }
            }

            if (showQuickSettings) {
                Row(Modifier.fillMaxWidth().background(Color(0xDD111111)).padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Text("HDR", color = CamWhite)
                    Switch(checked = hdrEnabled, onCheckedChange = { hdrEnabled = it })
                    Text("Exposure", color = CamWhite)
                    Text(String.format(Locale.US, "%+.1f", exposureBoost), color = CamGreen)
                    Slider(value = exposureBoost, onValueChange = { exposureBoost = it; preview.setExposureBias(it) }, valueRange = -1f..2f, modifier = Modifier.weight(1f))
                }
            }

            Spacer(Modifier.weight(1f))

            // Zoom selector.
            Row(Modifier.fillMaxWidth().padding(bottom = 26.dp), horizontalArrangement = Arrangement.Center) {
                Row(Modifier.clip(CircleShape).background(Color(0xAA292929)).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(1, 2).forEach { factor ->
                        Box(Modifier.size(54.dp, 42.dp).clip(CircleShape).background(if (zoom == factor) Color(0xFF171717) else Color.Transparent).clickable { zoom = factor }, contentAlignment = Alignment.Center) {
                            Text(if (factor == 1) "1x" else "2", color = if (zoom == factor) CamGreen else CamWhite, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            // Mode selector.
            Row(Modifier.fillMaxWidth().height(58.dp).background(Color(0xCC080808)).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                listOf("LAPSE", "VIDEO", "AI CAM", "PORTRAIT", "SLOW").forEach { mode ->
                    Text(mode, color = if (selectedMode == mode) CamGreen else CamWhite.copy(alpha = if (mode == "LAPSE" || mode == "SLOW") .35f else .78f), fontSize = 14.sp, fontWeight = if (selectedMode == mode) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.clickable { selectedMode = mode })
                }
                Text("☰", color = CamGreen, fontSize = 23.sp, modifier = Modifier.clickable { showQuickSettings = !showQuickSettings })
            }

            // Shutter strip.
            Row(Modifier.fillMaxWidth().height(112.dp).background(CamBlack).padding(horizontal = 28.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(62.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF262626)).clickable {
                    lastPhoto?.let { uri ->
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "image/jpeg"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) }
                    }
                }, contentAlignment = Alignment.Center) {
                    Text(if (lastPhoto != null) "▣" else "▧", color = CamWhite, fontSize = 27.sp)
                }

                Box(Modifier.size(84.dp).clip(CircleShape).border(5.dp, Color.White, CircleShape).clickable(enabled = hasPermission && !busy) {
                    if (hdrEnabled && !hdrSlots.tryAcquire()) {
                        status = "HDR QUEUE FULL — WAIT FOR A PHOTO TO FINISH"
                        return@clickable
                    }
                    var reservedHdrSlot = hdrEnabled
                    busy = true
                    status = if (hdrEnabled) "CAPTURING HDR…" else "CAPTURING…"
                    preview.stopPreview()
                    scope.launch {
                        try {
                            @Suppress("DEPRECATION")
                            val rotation = activity?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
                            val orientation = camera.jpegOrientation(frontCamera, rotation)
                            if (hdrEnabled) {
                                val captured = withContext(Dispatchers.IO) {
                                    camera.captureHdrFrames(exposureBoost.toDouble(), frontCamera, orientation)
                                }
                                textureView?.surfaceTexture?.let { preview.startPreview(it) }
                                hdrQueue.send(captured to orientation)
                                status = "HDR PROCESSING IN BACKGROUND"
                                reservedHdrSlot = false
                            } else {
                                val uri = withContext(Dispatchers.IO) {
                                    val comp = preview.currentExposureCompensation()
                                    val jpeg = camera.captureStandard(frontCamera, orientation, comp)
                                    savePhoto(context, jpeg, orientation).getOrThrow()
                                }
                                lastPhoto = uri
                                status = "PHOTO SAVED"
                            }
                        } catch (error: Exception) {
                            status = error.message ?: "CAPTURE FAILED"
                        } finally {
                            if (reservedHdrSlot) hdrSlots.release()
                            textureView?.surfaceTexture?.let { preview.startPreview(it) }
                            busy = false
                        }
                    }
                }, contentAlignment = Alignment.Center) {
                    if (busy) CircularProgressIndicator(Modifier.size(38.dp), color = CamGreen, strokeWidth = 3.dp)
                    else Box(Modifier.size(68.dp).clip(CircleShape).background(Color.White))
                }

                Box(Modifier.size(62.dp).clip(CircleShape).background(Color(0xFF242424)).clickable {
                    frontCamera = !frontCamera
                    preview.setFacing(frontCamera)
                }, contentAlignment = Alignment.Center) {
                    Text("↻", color = CamWhite, fontSize = 31.sp)
                }
            }
            if (status != "READY") {
                Text(status, color = CamWhite.copy(alpha = .75f), fontSize = 10.sp, modifier = Modifier.fillMaxWidth().background(CamBlack).padding(bottom = 3.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }

        if (!hasPermission) {
            Column(Modifier.align(Alignment.Center).padding(28.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xEE111111)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera permission is required", color = CamWhite)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permissionRequest.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            }
        }
    }
}

@Composable
private fun TopCamControl(symbol: String, description: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(symbol, color = CamWhite, fontSize = 23.sp, fontWeight = FontWeight.Light)
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
