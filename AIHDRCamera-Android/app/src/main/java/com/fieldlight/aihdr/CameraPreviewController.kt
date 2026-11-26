package com.fieldlight.aihdr

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import android.util.Size
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CameraPreviewController(private val context: Context) : TextureView.SurfaceTextureListener {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("CameraPreview").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var view: TextureView? = null
    @Volatile private var frontFacing = false

    fun attach(textureView: TextureView, front: Boolean = false) {
        view = textureView
        frontFacing = front
        textureView.surfaceTextureListener = this
        if (textureView.isAvailable) textureView.surfaceTexture?.let { startPreview(it) }
    }

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
                val facing = manager.getCameraCharacteristics(cameraId)
                    .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                facing == if (frontFacing)
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                else android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            } ?: return
            val characteristics = manager.getCameraCharacteristics(id)
            val map = characteristics.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val size = map?.getOutputSizes(SurfaceTexture::class.java)?.maxByOrNull {
                it.width.toLong() * it.height
            } ?: Size(1280, 720)
            texture.setDefaultBufferSize(size.width, size.height)
            val surface = Surface(texture)
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    }.build()
                    camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(value: CameraCaptureSession) {
                            session = value
                            try { value.setRepeatingRequest(request, null, handler) } catch (_: Exception) { }
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

    @Synchronized fun stopPreview() {
        try { session?.stopRepeating() } catch (_: Exception) { }
        try { session?.close() } catch (_: Exception) { }
        try { device?.close() } catch (_: Exception) { }
        session = null
        device = null
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        startPreview(surface)
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
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