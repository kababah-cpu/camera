package com.fieldlight.aihdr

import android.content.Context
import android.graphics.Rect
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import android.util.Log
import android.view.Surface
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDouble
import org.opencv.core.Scalar
import org.opencv.core.Size as CvSize
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Locale
import kotlin.math.pow

data class HdrCaptureDiagnostics(
    val meteringMs: Long,
    val detectedFaceCount: Int,
    val subjectMeteringApplied: Boolean,
    val burstMs: Long,
    val frameOffsetsMs: List<Long>,
    val frameExposureMs: List<Double>,
    val frameDurationMs: List<Double>,
    val frameSensitivityIso: List<Int>,
    val minimumJpegFrameDurationMs: Double,
    val decodeMs: Long,
    val alignmentMs: Long,
    val fusionMs: Long,
    val finishingMs: Long,
    val jpegEncodingMs: Long,
    val motionSuppressionMs: Long,
    val motionMaskCoveragePercent: Double,
    val motionMaskRawCoveragePercent: Double,
    val motionMaskThreshold: Double,
    val motionMaskRejected: Boolean,
    val middleExposureContributionPercent: Double,
    val exposureRatio: Double?,
    val opencvThreads: Int,
    val alignmentMaxBits: Int
) {
    fun toReport(shutterToSavedMs: Long): String = buildString {
        appendLine("AI HDR Camera - Capture Diagnostics")
        appendLine("Total shutter-to-save: ${shutterToSavedMs} ms")
        appendLine("Metering capture: ${meteringMs} ms")
        appendLine("Faces detected for metering: ${detectedFaceCount}")
        appendLine("Subject AE region applied: ${if (subjectMeteringApplied) "YES" else "NO"}")
        appendLine("Three-frame burst: ${burstMs} ms")
        frameOffsetsMs.forEachIndexed { index, offset ->
            appendLine("Frame ${index + 1} sensor-time offset: ${offset} ms")
        }
        appendLine("Frame-to-frame gaps: ${frameOffsetsMs.zipWithNext { a, b -> b - a }.joinToString(", ") { "${it} ms" }}")
        appendLine("Sensor exposure durations: ${frameExposureMs.joinToString(", ") { "%.2f".format(Locale.US, it) + " ms" }}")
        appendLine("Sensor frame durations: ${frameDurationMs.joinToString(", ") { "%.2f".format(Locale.US, it) + " ms" }}")
        appendLine("Sensor ISO values: ${frameSensitivityIso.joinToString(", ")}")
        appendLine("Minimum JPEG frame duration: ${"%.2f".format(Locale.US, minimumJpegFrameDurationMs)} ms")
        appendLine("JPEG decode: ${decodeMs} ms")
        appendLine("Alignment: ${alignmentMs} ms")
        appendLine("Exposure fusion: ${fusionMs} ms")
        appendLine("Tone/contrast/sharpening: ${finishingMs} ms")
        appendLine("JPEG encoding: ${jpegEncodingMs} ms")
        appendLine("Motion ghost suppression: ${motionSuppressionMs} ms")
        appendLine("Motion mask raw coverage: ${"%.1f".format(Locale.US, motionMaskRawCoveragePercent)}%")
        appendLine("Motion mask accepted coverage: ${"%.1f".format(Locale.US, motionMaskCoveragePercent)}%")
        appendLine("Middle-exposure contribution: ${"%.1f".format(Locale.US, middleExposureContributionPercent)}%")
        appendLine("Motion mask adaptive threshold: ${"%.1f".format(Locale.US, motionMaskThreshold)} / 255")
        appendLine("Motion mask rejected by HDR-preservation guard: ${if (motionMaskRejected) "YES" else "NO"}")
        appendLine("OpenCV processing threads: ${opencvThreads}")
        appendLine("Alignment pyramid max bits: ${alignmentMaxBits}")
        exposureRatio?.let { appendLine("Measured exposure ratio: ${"%.2f".format(Locale.US, it)}x") }
    }
}

data class HdrCaptureOutput(val jpeg: ByteArray, val diagnostics: HdrCaptureDiagnostics)

private data class CapturedFrameTiming(val exposureNs: Long, val frameDurationNs: Long, val sensitivityIso: Int)
private data class CapturedJpeg(val sensorTimestampNs: Long, val bytes: ByteArray)

private data class HdrFusionTimings(
    val decodeMs: Long,
    val alignmentMs: Long,
    val fusionMs: Long,
    val finishingMs: Long,
    val jpegEncodingMs: Long,
    val motionSuppressionMs: Long,
    val motionMaskCoveragePercent: Double,
    val motionMaskRawCoveragePercent: Double,
    val motionMaskThreshold: Double,
    val motionMaskRejected: Boolean,
    val middleExposureContributionPercent: Double
)

private data class HdrFusionOutput(val jpeg: ByteArray, val timings: HdrFusionTimings)

private data class MotionSuppressionResult(
    val rawCoveragePercent: Double,
    val acceptedCoveragePercent: Double,
    val adaptiveThreshold: Double,
    val rejected: Boolean,
    val middleExposureContributionPercent: Double
)

class Camera2HdrCapture(context: Context) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("Camera2Capture").apply { start() }
    private val handler = Handler(thread.looper)

    fun jpegOrientation(front: Boolean, displayRotation: Int): Int {
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            val facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            facing == if (front) CameraCharacteristics.LENS_FACING_FRONT
            else CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull() ?: error("No camera is available.")
        val sensorOrientation = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val deviceDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (front) {
            (sensorOrientation + deviceDegrees) % 360
        } else {
            (sensorOrientation - deviceDegrees + 360) % 360
        }
    }

    fun captureStandard(front: Boolean, jpegOrientation: Int): ByteArray =
        withCamera(front) { device, session, reader, queue, _ ->
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.JPEG_QUALITY, 96.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            }.build()
            captureJpeg(session, request, queue).first
        }

    fun captureHdr(ev: Double, front: Boolean, jpegOrientation: Int): HdrCaptureOutput =
        withCamera(front) { device, session, reader, queue, characteristics ->
            val meterStart = SystemClock.elapsedRealtime()
            var exposureNs = 10_000_000L
            var sensitivity = 200
            val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            val afMode = if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO))
                CaptureRequest.CONTROL_AF_MODE_AUTO else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE

            val faceModes = characteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES) ?: intArrayOf()
            val faceMode = when {
                faceModes.contains(CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL) -> CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL
                faceModes.contains(CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE) -> CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE
                else -> CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
            }
            val meterRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, afMode)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                if (faceMode != CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF) {
                    set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceMode)
                }
                set(CaptureRequest.JPEG_QUALITY, 96.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            }.build()

            val (_, faceResult) = captureJpeg(session, meterRequest, queue)
            val detectedFaces = faceResult.get(CaptureResult.STATISTICS_FACES).orEmpty()
            val maxAeRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
            val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val selectedFace = detectedFaces.filter { it.score >= 50 }.maxByOrNull { face ->
                face.bounds.width().toLong() * face.bounds.height().toLong()
            }
            val subjectRegion = if (maxAeRegions > 0 && activeArray != null && selectedFace != null) {
                val bounds = selectedFace.bounds
                // Include the face and a generous upper-body margin, while excluding
                // as much bright background as practical.
                val halfWidth = (bounds.width() * 1.0f).toInt()
                val topMargin = (bounds.height() * 0.5f).toInt()
                val bottomMargin = (bounds.height() * 2.5f).toInt()
                val regionRect = Rect(
                    bounds.left - halfWidth,
                    bounds.top - topMargin,
                    bounds.right + halfWidth,
                    bounds.bottom + bottomMargin
                )
                if (regionRect.intersect(activeArray)) MeteringRectangle(regionRect, MeteringRectangle.METERING_WEIGHT_MAX) else null
            } else null
            val meterResult = if (subjectRegion != null) {
                val subjectMeterRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(subjectRegion))
                    set(CaptureRequest.CONTROL_AF_MODE, afMode)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    if (faceMode != CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF) {
                        set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceMode)
                    }
                    set(CaptureRequest.JPEG_QUALITY, 96.toByte())
                    set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                }.build()
                captureJpeg(session, subjectMeterRequest, queue).second
            } else faceResult
            exposureNs = meterResult.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: exposureNs
            sensitivity = meterResult.get(CaptureResult.SENSOR_SENSITIVITY) ?: sensitivity
            val meteringMs = SystemClock.elapsedRealtime() - meterStart

            val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSize = streamMap?.getOutputSizes(ImageFormat.JPEG).orEmpty()
                .filter { it.width.toLong() * it.height <= 12_000_000L }
                .maxByOrNull { it.width.toLong() * it.height }
            val minimumJpegFrameDurationNs = if (jpegSize != null)
                streamMap?.getOutputMinFrameDuration(ImageFormat.JPEG, jpegSize) ?: 0L else 0L
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
            val requests = offsets.mapIndexed { index, offset ->
                val targetEv = (offset + ev).coerceIn(-3.0, 3.0)
                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    setTag(index)
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, afMode)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    if (characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true) {
                        set(CaptureRequest.CONTROL_AWB_LOCK, true)
                    }
                    set(CaptureRequest.JPEG_QUALITY, 96.toByte())
                    set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)

                    when {
                        canBracketManually -> {
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                            val adjustedExposure = (exposureNs * 2.0.pow(targetEv)).toLong()
                                .coerceIn(exposureRange!!.lower, exposureRange.upper)
                            set(CaptureRequest.SENSOR_EXPOSURE_TIME, adjustedExposure)
                            set(CaptureRequest.SENSOR_SENSITIVITY, sensitivity.coerceIn(isoRange!!.lower, isoRange.upper))
                            // Request the fastest supported JPEG cadence without shortening exposure.
                            if (minimumJpegFrameDurationNs > 0L) {
                                set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(minimumJpegFrameDurationNs, adjustedExposure))
                            }
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
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
                        }
                    }
                }.build()
            }

            val burstStart = SystemClock.elapsedRealtime()
            val (frames, sensorTimestamps, frameTimings) = captureBurstJpegs(session, requests, queue)
            val burstMs = SystemClock.elapsedRealtime() - burstStart
            val firstTimestamp = sensorTimestamps.firstOrNull() ?: 0L
            val frameOffsetsMs = sensorTimestamps.map { timestamp ->
                if (firstTimestamp > 0L && timestamp >= firstTimestamp) (timestamp - firstTimestamp) / 1_000_000L else -1L
            }
            val fusionOutput = HdrFusion.merge(frames)
            val diagnostics = HdrCaptureDiagnostics(
                meteringMs = meteringMs,
                detectedFaceCount = detectedFaces.size,
                subjectMeteringApplied = subjectRegion != null,
                burstMs = burstMs,
                frameOffsetsMs = frameOffsetsMs,
                frameExposureMs = frameTimings.map { it.exposureNs / 1_000_000.0 },
                frameDurationMs = frameTimings.map { it.frameDurationNs / 1_000_000.0 },
                frameSensitivityIso = frameTimings.map { it.sensitivityIso },
                minimumJpegFrameDurationMs = minimumJpegFrameDurationNs / 1_000_000.0,
                decodeMs = fusionOutput.timings.decodeMs,
                alignmentMs = fusionOutput.timings.alignmentMs,
                fusionMs = fusionOutput.timings.fusionMs,
                finishingMs = fusionOutput.timings.finishingMs,
                jpegEncodingMs = fusionOutput.timings.jpegEncodingMs,
                motionSuppressionMs = fusionOutput.timings.motionSuppressionMs,
                motionMaskCoveragePercent = fusionOutput.timings.motionMaskCoveragePercent,
                motionMaskRawCoveragePercent = fusionOutput.timings.motionMaskRawCoveragePercent,
                motionMaskThreshold = fusionOutput.timings.motionMaskThreshold,
                motionMaskRejected = fusionOutput.timings.motionMaskRejected,
                middleExposureContributionPercent = fusionOutput.timings.middleExposureContributionPercent,
                exposureRatio = null,
                opencvThreads = Core.getNumThreads(),
                alignmentMaxBits = HdrFusion.ALIGNMENT_MAX_BITS
            )
            HdrCaptureOutput(fusionOutput.jpeg, diagnostics)
        }

    private fun captureJpeg(
        session: CameraCaptureSession,
        request: CaptureRequest,
        queue: ArrayBlockingQueue<CapturedJpeg>
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
        val result = captureResult ?: error("Camera returned no capture metadata.")
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
            ?: error("Camera returned no sensor timestamp for the JPEG.")
        val jpeg = pollJpegForTimestamp(queue, timestamp, 8)
            ?: error("Camera completed the capture but did not deliver the matching JPEG image.")
        return jpeg.bytes to result
    }


    private fun pollJpegForTimestamp(queue: ArrayBlockingQueue<CapturedJpeg>, timestampNs: Long, timeoutSeconds: Long): CapturedJpeg? {
        val deadline = SystemClock.elapsedRealtime() + timeoutSeconds * 1000L
        val unmatched = ArrayList<CapturedJpeg>()
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                val image = queue.poll(remaining, TimeUnit.MILLISECONDS) ?: return null
                if (image.sensorTimestampNs == timestampNs) return image
                unmatched += image
            }
            return null
        } finally {
            unmatched.forEach { queue.offer(it) }
        }
    }

    private fun captureBurstJpegs(
        session: CameraCaptureSession,
        requests: List<CaptureRequest>,
        queue: ArrayBlockingQueue<CapturedJpeg>
    ): Triple<List<ByteArray>, List<Long>, List<CapturedFrameTiming>> {
        require(requests.size == 3) { "HDR burst must contain exactly three requests." }
        queue.clear()
        val sequenceCompleted = CountDownLatch(1)
        var captureError: Throwable? = null
        val completedResults = arrayOfNulls<TotalCaptureResult>(requests.size)
        val sensorTimestamps = arrayOfNulls<Long>(requests.size)
        val frameTimings = arrayOfNulls<CapturedFrameTiming>(requests.size)

        session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                val index = request.tag as? Int
                if (index != null && index in completedResults.indices) {
                    completedResults[index] = result
                    sensorTimestamps[index] = result.get(CaptureResult.SENSOR_TIMESTAMP)
                    val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                    val frameDuration = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L
                    val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                    frameTimings[index] = CapturedFrameTiming(exposure, frameDuration, iso)
                    val compensation = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
                    Log.i(
                        "AIHDR-Bracket",
                        "Frame $index: exposureNs=$exposure, ISO=$iso, AE compensation=$compensation"
                    )
                } else {
                    Log.w("AIHDR-Bracket", "Capture result did not contain a valid bracket index.")
                }
            }

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

        check(completedResults.all { it != null }) {
            "HDR burst completed without metadata for every exposure."
        }
        val measured = completedResults.mapNotNull { nullableResult ->
            val result = nullableResult ?: return@mapNotNull null
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return@mapNotNull null
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return@mapNotNull null
            exposure.toDouble() * iso.toDouble()
        }
        if (measured.size == requests.size) {
            val minExposure = measured.minOrNull() ?: 0.0
            val maxExposure = measured.maxOrNull() ?: 0.0
            val ratio = if (minExposure > 0.0) maxExposure / minExposure else 0.0
            Log.i("AIHDR-Bracket", "Effective bracket exposure ratio: $ratio")
            if (ratio < 2.5) {
                Log.w(
                    "AIHDR-Bracket",
                    "Camera applied only a narrow exposure range; HDR recovery may be limited."
                )
            }
        }

        val resultsByIndex = completedResults.map { it ?: error("Missing HDR capture result.") }
        val images = ArrayList<CapturedJpeg>(requests.size)
        repeat(requests.size) {
            images += queue.poll(8, TimeUnit.SECONDS)
                ?: error("HDR capture burst completed but a JPEG frame was not delivered.")
        }
        val imagesByTimestamp = images.associateBy { it.sensorTimestampNs }
        val frames = resultsByIndex.mapIndexed { index, result ->
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                ?: error("HDR frame ${index + 1} has no sensor timestamp.")
            (imagesByTimestamp[timestamp]
                ?: error("HDR JPEG/result timestamp mismatch for frame ${index + 1}.")).bytes
        }
        val timestamps = resultsByIndex.map { it.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L }
        val timings = frameTimings.map { it ?: CapturedFrameTiming(0L, 0L, 0) }
        return Triple(frames, timestamps, timings)
    }

    private fun <T> withCamera(
        front: Boolean,
        block: (CameraDevice, CameraCaptureSession, ImageReader, ArrayBlockingQueue<CapturedJpeg>, CameraCharacteristics) -> T
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
        val queue = ArrayBlockingQueue<CapturedJpeg>(4)
        reader.setOnImageAvailableListener({ source ->
            try {
                source.acquireNextImage()?.use { image ->
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    queue.offer(CapturedJpeg(image.timestamp, bytes))
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
    const val ALIGNMENT_MAX_BITS = 5
    private const val OPENCV_THREADS = 4
    private const val MAX_MOTION_MASK_COVERAGE_PERCENT = 80.0

    fun merge(jpegs: List<ByteArray>): HdrFusionOutput {
        require(jpegs.size == 3) { "HDR processing requires three frames." }
        // The Hot 60i has a heterogeneous 8-core CPU. Four OpenCV workers are
        // a conservative starting point; the timing report records this setting.
        Core.setNumThreads(OPENCV_THREADS)
        val decodeStart = SystemClock.elapsedRealtime()
        val encodedInputs = jpegs.map { MatOfByte(*it) }
        val source = ArrayList<Mat>()
        val aligned = ArrayList<Mat>()
        val fused = Mat()
        val output = Mat()
        var decodeMs = 0L
        var alignmentMs = 0L
        var fusionMs = 0L
        var finishingMs = 0L
        var jpegEncodingMs = 0L
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
            decodeMs = SystemClock.elapsedRealtime() - decodeStart

            // Use the middle exposure as the alignment reference. This reduces
            // the chance that moving subjects are anchored to the darkest frame.
            val alignmentSource = listOf(source[1], source[0], source[2])
            val alignmentStart = SystemClock.elapsedRealtime()
            try {
                val aligner = Photo.createAlignMTB()
                aligner.setMaxBits(ALIGNMENT_MAX_BITS)
                aligner.process(alignmentSource, aligned)
            } catch (_: Exception) {
                aligned.clear()
            }
            alignmentMs = SystemClock.elapsedRealtime() - alignmentStart
            val fusionInputs = if (aligned.size == alignmentSource.size && aligned.all { !it.empty() })
                aligned else alignmentSource
            require(fusionInputs.size == 3) { "Three valid images are required for HDR fusion." }

            val fusionStart = SystemClock.elapsedRealtime()
            Photo.createMergeMertens(0.9f, 0.8f, 1.1f).process(fusionInputs, fused)
            require(!fused.empty()) { "HDR fusion produced no output image." }
            fusionMs = SystemClock.elapsedRealtime() - fusionStart

            val motionStart = SystemClock.elapsedRealtime()
            val motionResult = suppressMotionGhosts(fusionInputs, fused)
            val motionSuppressionMs = SystemClock.elapsedRealtime() - motionStart

            val finishingStart = SystemClock.elapsedRealtime()
            fused.convertTo(output, CvType.CV_8UC3, 255.0)
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
                        Core.addWeighted(localContrast, 0.85, claheLuma, 0.15, 0.0, blendedLuma)
                    } finally {
                        claheLuma.release()
                    }
                } finally {
                    clahe.collectGarbage()
                }
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
            finishingMs = SystemClock.elapsedRealtime() - finishingStart

            val encodeStart = SystemClock.elapsedRealtime()
            val encoded = MatOfByte()
            try {
                check(Imgcodecs.imencode(".jpg", output, encoded)) {
                    "Could not encode the fused photo."
                }
                jpegEncodingMs = SystemClock.elapsedRealtime() - encodeStart
                return HdrFusionOutput(
                    encoded.toArray(),
                    HdrFusionTimings(
                        decodeMs, alignmentMs, fusionMs, finishingMs, jpegEncodingMs,
                        motionSuppressionMs,
                        motionResult.acceptedCoveragePercent,
                        motionResult.rawCoveragePercent,
                        motionResult.adaptiveThreshold,
                        motionResult.rejected,
                        motionResult.middleExposureContributionPercent
                    )
                )
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

    /**
     * Detects localized structural differences after average-brightness
     * normalization and low-pass filtering. A broad or unreliable mask is
     * rejected rather than allowing motion correction to replace most of HDR.
     */
    private fun suppressMotionGhosts(images: List<Mat>, fused: Mat): MotionSuppressionResult {
        require(images.size == 3)
        val reference = images[0]
        val referenceGray = Mat()
        val referenceFloat = Mat()
        val combinedMask = Mat.zeros(reference.rows(), reference.cols(), CvType.CV_8UC1)
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE,
            CvSize(7.0, 7.0)
        )
        val dilatedMask = Mat()
        val softMask = Mat()
        val maskFloat = Mat()
        val inverseMask = Mat()
        val onesMask = Mat()
        val referenceChannels = ArrayList<Mat>()
        val fusedChannels = ArrayList<Mat>()
        val compositeChannels = ArrayList<Mat>()
        val composite = Mat()
        try {
            Imgproc.cvtColor(reference, referenceGray, Imgproc.COLOR_BGR2GRAY)
            referenceGray.convertTo(referenceFloat, CvType.CV_32FC1)
            val referenceMean = Core.mean(referenceFloat).\`val\`[0].coerceAtLeast(1.0)
            val threshold = maxOf(8.0, referenceMean * 0.14)

            for (index in 1..2) {
                val gray = Mat()
                val normalized = Mat()
                val difference = Mat()
                val detected = Mat()
                try {
                    Imgproc.cvtColor(images[index], gray, Imgproc.COLOR_BGR2GRAY)
                    gray.convertTo(normalized, CvType.CV_32FC1)
                    val frameMean = Core.mean(normalized).\`val\`[0]
                    if (frameMean > 1.0) {
                        Core.multiply(normalized, Scalar(referenceMean / frameMean), normalized)
                    }
                    Core.absdiff(referenceFloat, normalized, difference)
                    Core.compare(difference, Scalar(threshold), detected, Core.CMP_GT)
                    Core.bitwise_or(combinedMask, detected, combinedMask)
                } finally {
                    gray.release()
                    normalized.release()
                    difference.release()
                    detected.release()
                }
            }

            val totalPixels = reference.rows().toDouble() * reference.cols().toDouble()
            val rawCoverage = Core.countNonZero(combinedMask).toDouble() * 100.0 / totalPixels
            Imgproc.dilate(combinedMask, dilatedMask, kernel)
            val acceptedCoverage = Core.countNonZero(dilatedMask).toDouble() * 100.0 / totalPixels
            // Restore the original, proven high-coverage fallback: do not reject
            // broad masks. This deliberately prioritizes eliminating visible
            // double edges/ghosts over retaining fusion in pixels flagged as motion.
            dilatedMask.convertTo(maskFloat, CvType.CV_32FC1, 1.0 / 255.0)
            Imgproc.GaussianBlur(maskFloat, softMask, CvSize(0.0, 0.0), 2.0)
            onesMask.create(softMask.size(), CvType.CV_32FC1)
            onesMask.setTo(Scalar(1.0))
            Core.subtract(onesMask, softMask, inverseMask)

            reference.convertTo(composite, CvType.CV_32FC3, 1.0 / 255.0)
            Core.split(composite, referenceChannels)
            Core.split(fused, fusedChannels)
            for (channelIndex in 0..2) {
                val staticPart = Mat()
                val movingPart = Mat()
                val outputChannel = Mat()
                try {
                    Core.multiply(fusedChannels[channelIndex], inverseMask, staticPart)
                    Core.multiply(referenceChannels[channelIndex], softMask, movingPart)
                    Core.add(staticPart, movingPart, outputChannel)
                    compositeChannels += outputChannel
                } finally {
                    staticPart.release()
                    movingPart.release()
                }
            }
            Core.merge(compositeChannels, composite)
            composite.copyTo(fused)

            val middleContribution = Core.mean(softMask).\`val\`[0] * 100.0
            return MotionSuppressionResult(
                rawCoveragePercent = rawCoverage,
                acceptedCoveragePercent = acceptedCoverage,
                adaptiveThreshold = threshold,
                rejected = false,
                middleExposureContributionPercent = middleContribution
            )
        } finally {
            referenceGray.release()
            referenceFloat.release()
            combinedMask.release()
            kernel.release()
            dilatedMask.release()
            softMask.release()
            maskFloat.release()
            inverseMask.release()
            onesMask.release()
            referenceChannels.forEach { it.release() }
            fusedChannels.forEach { it.release() }
            compositeChannels.forEach { it.release() }
            composite.release()
        }
    }
}
