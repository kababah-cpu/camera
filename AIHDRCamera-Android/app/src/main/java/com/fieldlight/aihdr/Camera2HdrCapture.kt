package com.fieldlight.aihdr

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Scalar
import org.opencv.core.Size as CvSize
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.pow

class Camera2HdrCapture(context: Context) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("Camera2Capture").apply { start() }
    private val handler = Handler(thread.looper)

    fun captureStandard(front: Boolean): ByteArray =
        withCamera(front) { device, session, reader, queue, _ ->
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.JPEG_QUALITY, 96.toByte())
            }.build()
            captureJpeg(session, request, queue).first
        }

    fun captureHdr(ev: Double, front: Boolean): ByteArray =
        withCamera(front) { device, session, reader, queue, characteristics ->
            var exposureNs = 10_000_000L
            var sensitivity = 200
            val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            val afMode = if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO))
                CaptureRequest.CONTROL_AF_MODE_AUTO else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE

            val meterRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, afMode)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.JPEG_QUALITY, 96.toByte())
            }.build()

            val (_, meterResult) = captureJpeg(session, meterRequest, queue)
            exposureNs = meterResult.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: exposureNs
            sensitivity = meterResult.get(CaptureResult.SENSOR_SENSITIVITY) ?: sensitivity

            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val manualSensor = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val compensationRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val compensationStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            val canBracketWithAe = compensationRange != null &&
                compensationStep != null && compensationStep.toFloat() > 0f &&
                (compensationRange.lower < 0 || compensationRange.upper > 0)
            val canBracketManually = manualSensor && exposureRange != null && isoRange != null

            // Keep the metering/AF capture above, but submit all three bracketed
            // still requests in one Camera2 burst. Waiting for each JPEG before
            // submitting the next request unnecessarily stretches the capture window.
            val offsets = listOf(-2.0, 0.0, 2.0)
            val requests = offsets.mapIndexed { index, offset ->
                val targetEv = (offset + ev).coerceIn(-3.0, 3.0)
                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, afMode)
                    set(
                        CaptureRequest.CONTROL_AF_TRIGGER,
                        if (index == 0) CaptureRequest.CONTROL_AF_TRIGGER_START
                        else CaptureRequest.CONTROL_AF_TRIGGER_IDLE
                    )
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    if (characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true) {
                        set(CaptureRequest.CONTROL_AWB_LOCK, true)
                    }
                    set(CaptureRequest.JPEG_QUALITY, 96.toByte())

                    when {
                        canBracketManually -> {
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                            val adjustedExposure = (exposureNs * 2.0.pow(targetEv)).toLong()
                                .coerceIn(exposureRange!!.lower, exposureRange.upper)
                            set(CaptureRequest.SENSOR_EXPOSURE_TIME, adjustedExposure)
                            set(CaptureRequest.SENSOR_SENSITIVITY, sensitivity.coerceIn(isoRange!!.lower, isoRange.upper))
                        }
                        canBracketWithAe -> {
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            val stepEv = compensationStep!!.toFloat()
                            val steps = kotlin.math.round(targetEv / stepEv).toInt()
                            set(
                                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                                steps.coerceIn(compensationRange!!.lower, compensationRange.upper)
                            )
                        }
                        else -> {
                            // Devices without bracketing support still capture three
                            // consecutive frames, but cannot provide distinct exposure levels.
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
                        }
                    }
                }.build()
            }
            val frames = captureBurstJpegs(session, requests, queue)
            HdrFusion.merge(frames)
        }

    private fun captureJpeg(
        session: CameraCaptureSession,
        request: CaptureRequest,
        queue: ArrayBlockingQueue<ByteArray>
    ): Pair<ByteArray, TotalCaptureResult> {
        // Remove any image left by a previous request before starting this one.
        queue.clear()
        val completed = CountDownLatch(1)
        var captureResult: TotalCaptureResult? = null
        var captureError: Throwable? = null

        session.capture(request, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                captureResult = result
                completed.countDown()
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure
            ) {
                captureError = IllegalStateException(
                    "Camera capture failed (reason ${failure.reason}, frame ${failure.frameNumber})."
                )
                completed.countDown()
            }

            override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                captureError = IllegalStateException("Camera capture sequence was aborted.")
                completed.countDown()
            }
        }, handler)

        check(completed.await(8, TimeUnit.SECONDS)) { "Camera capture callback timed out." }
        captureError?.let { throw it }
        val jpeg = queue.poll(8, TimeUnit.SECONDS)
            ?: error("Camera completed the capture but did not deliver a JPEG image.")
        return jpeg to (captureResult ?: error("Camera returned no capture metadata."))
    }

    private fun captureBurstJpegs(
        session: CameraCaptureSession,
        requests: List<CaptureRequest>,
        queue: ArrayBlockingQueue<ByteArray>
    ): List<ByteArray> {
        require(requests.size == 3) { "HDR burst must contain exactly three requests." }
        queue.clear()
        val sequenceCompleted = CountDownLatch(1)
        var captureError: Throwable? = null

        session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure
            ) {
                if (captureError == null) {
                    captureError = IllegalStateException(
                        "HDR burst frame failed (reason ${failure.reason}, frame ${failure.frameNumber})."
                    )
                }
            }

            override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                captureError = IllegalStateException("HDR capture burst was aborted.")
                sequenceCompleted.countDown()
            }

            override fun onCaptureSequenceCompleted(
                session: CameraCaptureSession,
                sequenceId: Int,
                frameNumber: Long
            ) {
                sequenceCompleted.countDown()
            }
        }, handler)

        check(sequenceCompleted.await(12, TimeUnit.SECONDS)) {
            "HDR capture burst timed out."
        }
        captureError?.let { throw it }

        return List(requests.size) {
            queue.poll(8, TimeUnit.SECONDS)
                ?: error("HDR burst completed but a JPEG frame was not delivered.")
        }
    }

    private fun <T> withCamera(
        front: Boolean,
        block: (CameraDevice, CameraCaptureSession, ImageReader, ArrayBlockingQueue<ByteArray>, CameraCharacteristics) -> T
    ): T {
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            val facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            facing == if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull() ?: error("No camera is available.")
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: error("Camera stream configuration is unavailable.")
        val size = map.getOutputSizes(ImageFormat.JPEG).orEmpty()
            .filter { it.width.toLong() * it.height <= 12_000_000L }
            .maxByOrNull { it.width.toLong() * it.height } ?: Size(1920, 1080)

        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 4)
        val queue = ArrayBlockingQueue<ByteArray>(4)
        reader.setOnImageAvailableListener({ source ->
            try {
                source.acquireNextImage()?.use { image ->
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    queue.offer(bytes)
                }
            } catch (_: Exception) { }
        }, handler)

        val opened = CountDownLatch(1)
        var openError: Throwable? = null
        var openedDevice: CameraDevice? = null
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                openedDevice = camera
                opened.countDown()
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                openError = IllegalStateException("Camera disconnected.")
                opened.countDown()
            }
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                openError = IllegalStateException("Camera2 error: $error")
                opened.countDown()
            }
        }, handler)

        try {
            check(opened.await(8, TimeUnit.SECONDS)) { "Timed out opening the camera." }
            openError?.let { throw it }
            val device = openedDevice ?: error("Camera failed to open.")

            val configured = CountDownLatch(1)
            var configuredSession: CameraCaptureSession? = null
            var configError: Throwable? = null
            device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(value: CameraCaptureSession) {
                    configuredSession = value
                    configured.countDown()
                }
                override fun onConfigureFailed(value: CameraCaptureSession) {
                    configError = IllegalStateException("Camera session configuration failed.")
                    configured.countDown()
                }
            }, handler)

            check(configured.await(8, TimeUnit.SECONDS)) { "Timed out configuring the camera." }
            configError?.let { throw it }
            val captureSession = configuredSession ?: error("Camera session unavailable.")
            return block(device, captureSession, reader, queue, characteristics)
        } finally {
            try { openedDevice?.close() } catch (_: Exception) { }
            try { reader.close() } catch (_: Exception) { }
        }
    }

    fun close() {
        thread.quitSafely()
    }
}

private object HdrFusion {
    fun merge(jpegs: List<ByteArray>): ByteArray {
        require(jpegs.size == 3) { "HDR processing requires three frames." }
        val encodedInputs = jpegs.map { MatOfByte(*it) }
        val source = ArrayList<Mat>()
        val aligned = ArrayList<Mat>()
        val fused = Mat()
        val output = Mat()
        try {
            encodedInputs.forEach { bytes ->
                source += Imgcodecs.imdecode(bytes, Imgcodecs.IMREAD_COLOR)
            }
            require(source.size == 3 && source.all { !it.empty() }) {
                "HDR capture returned an empty or undecodable frame. Please try again."
            }
            val reference = source.first()
            require(source.all { it.rows() == reference.rows() && it.cols() == reference.cols() }) {
                "HDR frames have different dimensions and cannot be combined."
            }

            try {
                Photo.createAlignMTB().process(source, aligned)
            } catch (_: Exception) {
                // Continue with the decoded frames if alignment is unavailable.
                aligned.clear()
            }
            val fusionInputs = if (aligned.size == source.size && aligned.all { !it.empty() }) aligned else source
            require(fusionInputs.size == 3) { "Three valid images are required for HDR fusion." }

            // Slightly favor well-exposed regions while keeping contrast and saturation
            // weights restrained to avoid an over-processed, overly vivid result.
            Photo.createMergeMertens(0.9f, 0.8f, 1.1f).process(fusionInputs, fused)
            require(!fused.empty()) { "HDR fusion produced no output image." }
            fused.convertTo(output, CvType.CV_8UC3, 255.0)

            // Perform tone mapping on luminance only. This lifts dark architectural
            // detail while leaving the sky's chroma and bright-end detail intact.
            val lab = Mat()
            val channels = ArrayList<Mat>()
            val toneCurve = Mat(1, 256, CvType.CV_8UC1)
            val localContrast = Mat()
            val blendedLuma = Mat()
            val blurredLuma = Mat()
            val sharpenedLuma = Mat()
            try {
                Imgproc.cvtColor(output, lab, Imgproc.COLOR_BGR2Lab)
                Core.split(lab, channels)
                val luma = channels[0]

                val curve = ByteArray(256) { value ->
                    val x = value / 255.0
                    // Gentle toe lift: the previous coefficient of 12.0 clipped most
                    // midtones and highlights to white. Keep the lift subtle and
                    // progressively smaller toward bright values.
                    val mapped = (x + 0.18 * (1.0 - x) * (1.0 - x)).coerceIn(0.0, 1.0)
                    (mapped * 255.0).toInt().toByte()
                }
                toneCurve.put(0, 0, curve)
                Core.LUT(luma, toneCurve, localContrast)

                val clahe = Imgproc.createCLAHE(1.05, CvSize(16.0, 16.0))
                try {
                    val claheLuma = Mat()
                    try {
                        clahe.apply(localContrast, claheLuma)
                        // Blend gently: retain the global tonal structure and use CLAHE
                        // only to reveal local detail in shaded walls, trees and windows.
                        Core.addWeighted(localContrast, 0.85, claheLuma, 0.15, 0.0, blendedLuma)
                    } finally {
                        claheLuma.release()
                    }
                } finally {
                    clahe.collectGarbage()
                }

                // Very mild luminance-only sharpening restores edge definition without
                // creating colored halos around rooflines, branches or building edges.
                Imgproc.GaussianBlur(blendedLuma, blurredLuma, CvSize(0.0, 0.0), 1.0)
                Core.addWeighted(blendedLuma, 1.04, blurredLuma, -0.04, 0.0, sharpenedLuma)
                sharpenedLuma.copyTo(channels[0])
                Core.merge(channels, lab)
                Imgproc.cvtColor(lab, output, Imgproc.COLOR_Lab2BGR)
            } finally {
                channels.forEach { it.release() }
                lab.release()
                toneCurve.release()
                localContrast.release()
                blendedLuma.release()
                blurredLuma.release()
                sharpenedLuma.release()
            }

            val encoded = MatOfByte()
            try {
                check(Imgcodecs.imencode(".jpg", output, encoded)) {
                    "Could not encode the fused photo."
                }
                return encoded.toArray()
            } finally {
                encoded.release()
            }
        } finally {
            encodedInputs.forEach { it.release() }
            source.forEach { it.release() }
            aligned.forEach { it.release() }
            fused.release()
            output.release()
        }
    }
}
