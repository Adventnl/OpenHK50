package org.openhk50.ui

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
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
    private val remote: Button,
    private val timer: Button,
    private val lens: Button
) {
    private val executor = ContextCompat.getMainExecutor(activity)
    private val main = Handler(Looper.getMainLooper())
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var capture: ImageCapture? = null
    private var visible = true
    private var resumed = false
    private var saving = false
    private var captureIssue: String? = null
    private var lastShutterAt: Long? = null
    private var useFront = false
    private var timerEnabled = false
    private var shutterAt = 0L
    private val countdown = object : Runnable {
        override fun run() {
            if (shutterAt == 0L) return
            val remaining = shutterAt - SystemClock.elapsedRealtime()
            if (remaining > 0) {
                update()
                main.postDelayed(this, minOf(remaining, 1_000))
            } else {
                shutterAt = 0L
                capture()
                update()
            }
        }
    }

    init {
        takePhoto.setOnClickListener { requestPhoto() }
        remote.setOnClickListener { band.remoteCamera(::remoteCapture); update() }
        timer.setOnClickListener { timerEnabled = !timerEnabled; update() }
        lens.visibility = View.GONE
        lens.setOnClickListener { useFront = !useFront; bindCamera() }
        previewView.previewStreamState.observe(activity) { update() }
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            if (visible) {
                runCatching {
                    val camera = future.get().also { provider = it }
                    val back = camera.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                    val front = camera.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                    useFront = !back && front
                    lens.visibility = if (back && front) View.VISIBLE else View.GONE
                    bindCamera()
                }.onFailure { captureIssue = OPEN_ISSUE }
                update()
            }
        }, executor)
        update()
    }

    fun resume(active: Boolean) {
        if (resumed == active) return
        resumed = active
        if (!active) cancelCountdown()
        if (!active || capture != null) band.remoteCamera(if (active) ::remoteCapture else null)
        update()
    }

    fun update() {
        val state = band.state
        val counting = shutterAt != 0L
        takePhoto.isEnabled = canCapture() && !counting
        takePhoto.alpha = if (takePhoto.isEnabled) 1f else .6f
        timer.isEnabled = !counting && !saving
        timer.text = if (timerEnabled) "Timer · 3 seconds" else "Timer off"
        lens.isEnabled = !counting && !saving
        lens.text = if (useFront) "Use rear camera" else "Use front camera"
        remote.isEnabled = capture != null && state.connection == Connection.CONNECTED
        remote.visibility = if (state.cameraIssue != null && remote.isEnabled) View.VISIBLE else View.GONE
        status.text = captureIssue ?: when {
            counting -> "Taking photo in ${((shutterAt - SystemClock.elapsedRealtime() + 999) / 1_000).coerceAtLeast(1)}…"
            saving -> "Saving photo…"
            capture == null -> "Opening camera…"
            state.cameraIssue != null -> state.cameraIssue
            state.cameraActive -> "Move your wrist to take a photo."
            state.connection == Connection.DISCONNECTED -> "Connect your band to use the wrist shutter."
            else -> "Connecting the remote shutter…"
        }
    }

    fun close() {
        resume(false)
        visible = false
        unbindCamera()
        previewView.previewStreamState.removeObservers(activity)
    }

    private fun bindCamera() {
        val camera = provider ?: return
        unbindCamera()
        captureIssue = null
        runCatching {
            val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val capture = ImageCapture.Builder().build()
            camera.bindToLifecycle(activity, selector, preview, capture)
            this.preview = preview
            this.capture = capture
            if (resumed && !band.state.cameraActive && band.state.cameraIssue == null) band.remoteCamera(::remoteCapture)
        }.onFailure { captureIssue = OPEN_ISSUE }
        update()
    }

    private fun unbindCamera() {
        val useCases = listOfNotNull(preview, capture)
        if (useCases.isNotEmpty()) provider?.unbind(*useCases.toTypedArray())
        preview = null
        capture = null
    }

    private fun cancelCountdown() {
        main.removeCallbacks(countdown)
        shutterAt = 0L
    }

    private fun remoteCapture() {
        if (!takePhoto.isEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (lastShutterAt?.let { now - it < 1_200 } == true) return
        lastShutterAt = now
        requestPhoto()
    }

    private fun canCapture() = visible && resumed && !saving && capture != null &&
        previewView.previewStreamState.value == PreviewView.StreamState.STREAMING

    private fun requestPhoto() {
        if (!canCapture() || shutterAt != 0L) return
        captureIssue = null
        if (timerEnabled) {
            shutterAt = SystemClock.elapsedRealtime() + 3_000L
            countdown.run()
        } else {
            capture()
        }
    }

    private fun capture() {
        val image = capture ?: return
        if (!canCapture()) return
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

    private companion object {
        const val OPEN_ISSUE = "Couldn’t open the camera. Close other camera apps and reopen Camera remote."
    }
}
