package com.fieldlight.aihdr

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.util.Size
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class CameraPreviewController(private val context: Context) : TextureView.SurfaceTextureListener {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("CameraPreview").apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(context.mainLooper)
    private val meter = AdaptiveSceneMeter()

    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var view: TextureView? = null
    @Volatile private var previewSurface: Surface? = null
    @Volatile private var imageReader: ImageReader? = null
    @Volatile private var previewBuilder: CaptureRequest.Builder? = null
    @Volatile private var frontFacing = false
    @Volatile private var currentCompensation = 0
    @Volatile private var exposureBiasEv = 0.0
    @Volatile private var lastSceneTargetEv = 0.0
    @Volatile private var activePreviewSize: Size? = null
    @Volatile private var aeLower = 0
    @Volatile private var aeUpper = 0
    @Volatile private var aeStepEv = 0.0
    @Volatile private var lastAnalysisMs = 0L
    @Volatile private var lastAppliedMs = 0L
    @Volatile private var lastSummary = "ANALYZING SCENE"
    @Volatile private var listener: ((String, Int) -> Unit)? = null
    private val processing = AtomicBoolean(false)

    fun attach(textureView: TextureView, front: Boolean = false) {
        view = textureView
        frontFacing = front
        textureView.surfaceTextureListener = this
        if (textureView.isAvailable) textureView.surfaceTexture?.let { startPreview(it) }
    }

    fun setMeteringListener(callback: ((String, Int) -> Unit)?) {
        listener = callback
        publishStatus()
    }

    fun setExposureBias(ev: Float) {
        exposureBiasEv = ev.toDouble().coerceIn(-1.5, 1.5)
        handler.post { applyMetering(lastSummary, lastSceneTargetEv + exposureBiasEv, force = true) }
    }

    fun currentExposureCompensation(): Int = currentCompensation

    fun setFacing(front: Boolean) {
        if (frontFacing == front) return
        frontFacing = front
        stopPreview()
        view?.surfaceTexture?.let { startPreview(it) }
    }

    @Synchronized fun startPreview(texture: SurfaceTexture) {
        stopPreview()
        try {
            val id = manager.cameraIdList.firstOrNull { cameraId ->
                val facing = manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING)
                facing == if (frontFacing) CameraCharacteristics.LENS_FACING_FRONT
                else CameraCharacteristics.LENS_FACING_BACK
            } ?: return
            val characteristics = manager.getCameraCharacteristics(id)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val previewSize = map?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
                .filter { it.width <= 1920 && it.height <= 1080 }
                .maxByOrNull { it.width.toLong() * it.height }
                ?: map?.getOutputSizes(SurfaceTexture::class.java)?.maxByOrNull { it.width.toLong() * it.height }
                ?: Size(1280, 720)
            activePreviewSize = previewSize
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val surface = Surface(texture)
            previewSurface = surface

            val yuvSize = map?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
                .filter { it.width.toLong() * it.height <= 640L * 480L }
                .maxByOrNull { it.width.toLong() * it.height }
                ?: map?.getOutputSizes(ImageFormat.YUV_420_888)?.minByOrNull { it.width.toLong() * it.height }
                ?: Size(320, 240)
            val analysisReader = ImageReader.newInstance(
                yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 2
            )
            imageReader = analysisReader
            val range = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            aeLower = range?.lower ?: 0
            aeUpper = range?.upper ?: 0
            aeStepEv = if (step != null && step.denominator != 0)
                step.numerator.toDouble() / step.denominator.toDouble() else 0.0
            currentCompensation = 0
            lastAnalysisMs = 0L
            lastAppliedMs = 0L

            analysisReader.setOnImageAvailableListener({ source ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastAnalysisMs < 300L || !processing.compareAndSet(false, true)) {
                    source.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }
                lastAnalysisMs = now
                var image: Image? = null
                try {
                    image = source.acquireLatestImage()
                    if (image != null) {
                        val scene = meter.analyze(image)
                        val summary = scene.summary()
                        lastSceneTargetEv = scene.targetEv
                        val targetEv = scene.targetEv + exposureBiasEv
                        handler.post { applyMetering(summary, targetEv, force = false) }
                    }
                } catch (_: Exception) {
                } finally {
                    image?.close()
                    processing.set(false)
                }
            }, handler)

            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (previewSurface !== surface) {
                        camera.close()
                        surface.release()
                        return
                    }
                    device = camera
                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        addTarget(analysisReader.surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        if (characteristics.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
                                ?.contains(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY) == true) {
                            set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
                        }
                    }
                    previewBuilder = builder
                    camera.createCaptureSession(listOf(surface, analysisReader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(value: CameraCaptureSession) {
                            if (device !== camera || previewSurface !== surface) {
                                value.close()
                                return
                            }
                            session = value
                            configurePreviewTransform(characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0, previewSize)
                            try { value.setRepeatingRequest(builder.build(), null, handler) } catch (_: Exception) { }
                            publishStatus()
                        }
                        override fun onConfigureFailed(value: CameraCaptureSession) {
                            value.close()
                            surface.release()
                        }
                    }, handler)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device === camera) device = null
                    surface.release()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (device === camera) device = null
                    surface.release()
                }
            }, handler)
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun applyMetering(summary: String, targetEv: Double = 0.0, force: Boolean) {
        lastSummary = summary
        if (aeStepEv <= 0.0 || aeLower == aeUpper) {
            publishStatus()
            return
        }
        val target = (targetEv / aeStepEv).roundToInt().coerceIn(aeLower, aeUpper)
        val now = SystemClock.elapsedRealtime()
        if (now - lastAppliedMs < 280L && !force) {
            publishStatus()
            return
        }
        val next = when {
            force -> target
            target > currentCompensation -> currentCompensation + 1
            target < currentCompensation -> currentCompensation - 1
            else -> currentCompensation
        }.coerceIn(aeLower, aeUpper)
        if (next != currentCompensation) {
            currentCompensation = next
            lastAppliedMs = now
            try {
                previewBuilder?.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, next)
                val builder = previewBuilder
                val activeSession = session
                if (builder != null && activeSession != null) {
                    activeSession.setRepeatingRequest(builder.build(), null, handler)
                }
            } catch (_: Exception) {
            }
        }
        publishStatus()
    }

    private fun publishStatus() {
        val ev = if (aeStepEv > 0.0) currentCompensation * aeStepEv else 0.0
        val status = if (aeStepEv > 0.0) {
            "AUTO METER  ${if (ev >= 0) "+" else ""}${"%.1f".format(java.util.Locale.US, ev)} EV  •  $lastSummary"
        } else {
            "AUTO METER  •  AE COMPENSATION UNSUPPORTED"
        }
        mainHandler.post { listener?.invoke(status, currentCompensation) }
    }

    private fun configurePreviewTransform(sensorOrientation: Int, previewSize: Size) {
        val textureView = view ?: return
        val width = textureView.width
        val height = textureView.height
        if (width == 0 || height == 0) return

        val displayDegrees = when (textureView.display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        // Apply the sensor/display correction to the TextureView's buffer
        // matrix. Do not rotate the TextureView itself: doing so rotates its
        // layout bounds and can leave the visible preview sideways.
        val rotation = if (frontFacing) {
            (sensorOrientation + displayDegrees) % 360
        } else {
            (sensorOrientation - displayDegrees + 360) % 360
        }
        val swapsDimensions = rotation == 90 || rotation == 270
        val bufferRect = if (swapsDimensions) {
            RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        } else {
            RectF(0f, 0f, previewSize.width.toFloat(), previewSize.height.toFloat())
        }
        val viewRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val matrix = Matrix()
        matrix.setRectToRect(bufferRect, viewRect, Matrix.ScaleToFit.FILL)

        val centerX = width / 2f
        val centerY = height / 2f
        val scale = max(
            height.toFloat() / bufferRect.height(),
            width.toFloat() / bufferRect.width()
        )
        matrix.postScale(scale, scale, centerX, centerY)
        matrix.postRotate(rotation.toFloat(), centerX, centerY)

        // Mirror the front-facing preview horizontally for a natural selfie
        // view. Captured JPEG orientation is handled separately.
        if (frontFacing) {
            matrix.postScale(-1f, 1f, centerX, centerY)
        }
        textureView.setTransform(matrix)
    }

    @Synchronized fun stopPreview() {
        try { session?.stopRepeating() } catch (_: Exception) { }
        try { session?.close() } catch (_: Exception) { }
        try { device?.close() } catch (_: Exception) { }
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) { }
        try { imageReader?.close() } catch (_: Exception) { }
        try { previewSurface?.release() } catch (_: Exception) { }
        session = null
        device = null
        previewSurface = null
        activePreviewSize = null
        imageReader = null
        previewBuilder = null
        processing.set(false)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        startPreview(surface)
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        val orientation = runCatching {
            val id = manager.cameraIdList.first { cameraId ->
                manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING) ==
                    if (frontFacing) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            }
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        }.getOrDefault(0)
        configurePreviewTransform(orientation, activePreviewSize ?: Size(1280, 720))
    }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        stopPreview()
        return true
    }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    fun release() {
        stopPreview()
        thread.quitSafely()
    }
}
