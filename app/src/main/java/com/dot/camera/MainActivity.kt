package com.dot.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var shutter: View

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null

    private val bg = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var pending = 0

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startCamera() else {
                Toast.makeText(this, "لازم تسمح للكاميرا", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        shutter = findViewById(R.id.shutter)
        shutter.setOnClickListener { shoot() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else permLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val ic = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.HD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                ).build()
            val vc = VideoCapture.withOutput(recorder)

            provider.unbindAll()
            try {
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, ic, vc
                )
                videoCapture = vc
            } catch (e: Exception) {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, ic
                )
                videoCapture = null
            }
            imageCapture = ic
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun shoot() {
        val ic = imageCapture ?: return
        if (pending > 0) return
        val vc = videoCapture
        val ts = stamp()

        if (vc != null) {
            pending = 2
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "DOT_LIVE_$ts")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Dot")
            }
            val opts = MediaStoreOutputOptions.Builder(
                contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ).setContentValues(cv).build()
            val rec: Recording = vc.output.prepareRecording(this, opts)
                .start(ContextCompat.getMainExecutor(this)) { ev ->
                    if (ev is VideoRecordEvent.Finalize) done()
                }
            handler.postDelayed({ rec.stop() }, 3000)
            handler.postDelayed({ takePhoto(ic, ts) }, 1500)
        } else {
            pending = 1
            takePhoto(ic, ts)
        }
        status.text = "جاري التصوير..."
    }

    private fun takePhoto(ic: ImageCapture, ts: String) {
        val tmp = File(cacheDir, "raw_$ts.jpg")
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(tmp).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    bg.execute {
                        try {
                            val src = Enhancer.load(tmp)
                            val out = Enhancer.enhance(src)
                            savePhoto(out, ts)
                            src.recycle()
                            out.recycle()
                        } catch (e: Throwable) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "حصل خطأ في المعالجة", Toast.LENGTH_SHORT).show()
                            }
                        } finally {
                            tmp.delete()
                            runOnUiThread { done() }
                        }
                    }
                }

                override fun onError(e: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "فشل التصوير", Toast.LENGTH_SHORT).show()
                    done()
                }
            }
        )
    }

    private fun savePhoto(bmp: Bitmap, ts: String) {
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "DOT_$ts.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Dot")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        cv.clear()
        cv.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, cv, null, null)
    }

    private fun done() {
        pending--
        if (pending <= 0) {
            pending = 0
            status.text = "✔ اتحفظت"
            handler.postDelayed({ status.text = "" }, 1500)
        }
    }
}
