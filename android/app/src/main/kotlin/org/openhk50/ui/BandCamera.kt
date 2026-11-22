package org.openhk50.ui

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.openhk50.band.BandClient
import org.openhk50.band.Connection
import java.io.File

internal class BandCamera(
    private val activity: ComponentActivity,
    private val band: BandClient,
    private val previewView: PreviewView,
    private val status: TextView,
    private val takePhoto: Button,
    private val remote: Button
) {
    private val executor = ContextCompat.getMainExecutor(activity)
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var capture: ImageCapture? = null
    private var visible = true
    private var resumed = false
    private var saving = false
    private var captureIssue: String? = null
    private var lastShutterAt: Long? = null

    init {
        takePhoto.setOnClickListener { capture() }
        remote.setOnClickListener { band.remoteCamera(::remoteCapture); update() }
        previewView.previewStreamState.observe(activity) { update() }
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            if (visible) {
                runCatching {
                    val camera = future.get().also { provider = it }
                    val selector = if (camera.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA))
                        CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                    val image = ImageCapture.Builder().build()
                    val view = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    camera.bindToLifecycle(activity, selector, view, image)
                    preview = view
                    capture = image
                    if (resumed) band.remoteCamera(::remoteCapture)
                }.onFailure { captureIssue = "Couldn’t open the camera. Close other camera apps and try again." }
                update()
            }
        }, executor)
        update()
    }

    fun resume(active: Boolean) {
        if (resumed == active) return
        resumed = active
        if (!active || capture != null) band.remoteCamera(if (active) ::remoteCapture else null)
        update()
    }

    fun update() {
        val state = band.state
        takePhoto.isEnabled = resumed && !saving && capture != null &&
            previewView.previewStreamState.value == PreviewView.StreamState.STREAMING
        takePhoto.alpha = if (takePhoto.isEnabled) 1f else .55f
        remote.isEnabled = capture != null && state.connection == Connection.CONNECTED
        remote.visibility = if (state.cameraIssue != null) Button.VISIBLE else Button.GONE
        status.text = captureIssue ?: when {
            capture == null -> "Opening camera…"
            state.cameraIssue != null -> state.cameraIssue
            state.cameraActive -> "Move your wrist to take a photo."
            state.connection == Connection.DISCONNECTED -> "Connect the band to use the wrist shutter."
            else -> "Setting up wrist shutter…"
        }
    }

    fun close() {
        resume(false)
        visible = false
        val useCases = listOfNotNull(preview, capture)
        if (useCases.isNotEmpty()) provider?.unbind(*useCases.toTypedArray())
        previewView.previewStreamState.removeObservers(activity)
    }

    private fun remoteCapture() {
        if (!takePhoto.isEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (lastShutterAt?.let { now - it < 1_200 } == true) return
        lastShutterAt = now
        capture()
    }

    private fun capture() {
        val image = capture ?: return
        if (!visible || !takePhoto.isEnabled) return
        saving = true
        captureIssue = null
        update()
        previewView.display?.let { image.targetRotation = it.rotation }
        val name = "OpenHK50-${System.currentTimeMillis()}.jpg"
        val output = if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OpenHK50")
            }
            ImageCapture.OutputFileOptions.Builder(activity.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } else {
            val pictures = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: activity.filesDir
            ImageCapture.OutputFileOptions.Builder(File(pictures, name))
        }.build()
        image.takePicture(output, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                saving = false
                if (!visible) return
                android.widget.Toast.makeText(activity, "Photo saved", android.widget.Toast.LENGTH_SHORT).show()
                update()
            }

            override fun onError(exception: ImageCaptureException) {
                saving = false
                captureIssue = "Couldn’t save the photo. Check phone storage and try again."
                if (visible) update()
            }
        })
    }
}
