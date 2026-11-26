package com.fieldlight.aihdr

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
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
            session.capture(request, null, handler)
            queue.poll(8, TimeUnit.SECONDS) ?: error("Camera did not return a JPEG.")
        }

    fun captureHdr(ev: Double, front: Boolean): ByteArray =
        withCamera(front) { device, session, reader, queue, characteristics ->
            var exposureNs = 10_000_000L
            var sensitivity = 200
            val metered = CountDownLatch(1)
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

            session.capture(meterRequest, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: exposureNs
                    sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: sensitivity
                    metered.countDown()
                }
            }, handler)

            check(metered.await(4, TimeUnit.SECONDS)) { "Camera exposure metering timed out." }
            queue.poll(4, TimeUnit.SECONDS) // discard the metering frame

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

            val offsets = listOf(-2.0, 0.0, 2.0)
            val frames = ArrayList<ByteArray>(3)
            for (offset in offsets) {
                val targetEv = (offset + ev).coerceIn(-3.0, 3.0)
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, afMode)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
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
                            val steps = (targetEv / stepEv).toInt()
                            set(
                                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                                steps.coerceIn(compensationRange!!.lower, compensationRange.upper)
                            )
                        }
                        else -> {
                            // Unsupported devices get three same-exposure frames for software
                            // alignment/fusion instead of three requests all silently clamped to zero.
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
                        }
                    }
                }.build()
                session.capture(request, null, handler)
                frames += queue.poll(8, TimeUnit.SECONDS) ?: error("An HDR frame timed out.")
            }
            HdrFusion.merge(frames)
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
        val source = jpegs.map { Imgcodecs.imdecode(MatOfByte(*it), Imgcodecs.IMREAD_COLOR) }
        val aligned = ArrayList<Mat>()
        val fused = Mat()
        val output = Mat()
        try {
            require(source.all { !it.empty() }) { "A captured frame could not be decoded." }
            Photo.createAlignMTB().process(source, aligned)
            Photo.createMergeMertens().process(aligned, fused)
            fused.convertTo(output, CvType.CV_8UC3, 255.0)

            val lab = Mat()
            val channels = ArrayList<Mat>()
            try {
                Imgproc.cvtColor(output, lab, Imgproc.COLOR_BGR2Lab)
                Core.split(lab, channels)
                val clahe = Imgproc.createCLAHE(1.8, CvSize(8.0, 8.0))
                val enhanced = Mat()
                try {
                    clahe.apply(channels[0], enhanced)
                    enhanced.copyTo(channels[0])
                    Core.merge(channels, lab)
                    Imgproc.cvtColor(lab, output, Imgproc.COLOR_Lab2BGR)
                } finally {
                    enhanced.release()
                    clahe.collectGarbage()
                }
            } finally {
                channels.forEach { it.release() }
                lab.release()
            }

            val encoded = MatOfByte()
            try {
                check(Imgcodecs.imencode(".jpg", output, encoded)) { "Could not encode the fused photo." }
                return encoded.toArray()
            } finally {
                encoded.release()
            }
        } finally {
            source.forEach { it.release() }
            aligned.forEach { it.release() }
            fused.release()
            output.release()
        }
    }
}