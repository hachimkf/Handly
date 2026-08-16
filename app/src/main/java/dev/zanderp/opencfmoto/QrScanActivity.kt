// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of Handly / OpenCfMoto. Free software under GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class QrScanActivity : AppCompatActivity() {
    companion object {
        const val RESULT_QR = "qr_url"
    }

    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val scanner = BarcodeScanning.getClient()
    private val handled = AtomicBoolean(false)
    private var camera: Camera? = null
    private var isTorchOn = false

    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) decodePhoto(uri)
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "Camera permission required to scan QR", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_qr_scan)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.qr_scan_root)) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            findViewById<View>(R.id.qr_top_bar)?.setPadding(b.left + dp(20), b.top + dp(14), b.right + dp(20), dp(12))
            findViewById<View>(R.id.bottom_sheet_container)?.setPadding(b.left + dp(24), dp(16), b.right + dp(24), b.bottom + dp(20))
            insets
        }

        findViewById<View>(R.id.btn_qr_back)?.setOnClickListener { finish() }

        findViewById<View>(R.id.btn_qr_torch)?.setOnClickListener {
            toggleTorch()
        }

        findViewById<View>(R.id.btn_qr_photo)?.setOnClickListener {
            photoPicker.launch("image/*")
        }

        findViewById<View>(R.id.btn_manual_wifi)?.setOnClickListener {
            ManualWifiPairing.show(this) { raw, _ ->
                if (!handled.compareAndSet(false, true)) return@show
                setResult(RESULT_OK, Intent().putExtra(RESULT_QR, raw))
                finish()
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        analyzerExecutor.shutdown()
        scanner.close()
        super.onDestroy()
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) return
        isTorchOn = !isTorchOn
        cam.cameraControl.enableTorch(isTorchOn)
        findViewById<ImageView>(R.id.btn_qr_torch)?.setColorFilter(
            ContextCompat.getColor(
                this,
                if (isTorchOn) R.color.m3_sys_dark_primary else R.color.m3_sys_dark_on_surface
            )
        )
    }

    private fun startCamera() {
        val previewView = findViewById<PreviewView>(R.id.preview_view)
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analyzerExecutor) { proxy -> analyze(proxy) }
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                )
                setupZoomButtons()
            } catch (e: Exception) {
                findViewById<TextView>(R.id.hint)?.text = "Camera bind failed: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupZoomButtons() {
        findViewById<View>(R.id.btn_zoom_1x)?.setOnClickListener {
            setZoom(1f)
            updateZoomUI(1)
        }
        findViewById<View>(R.id.btn_zoom_2x)?.setOnClickListener {
            setZoom(2f)
            updateZoomUI(2)
        }
        findViewById<View>(R.id.btn_zoom_3x)?.setOnClickListener {
            setZoom(3f)
            updateZoomUI(3)
        }
    }

    private fun updateZoomUI(active: Int) {
        val primary = ContextCompat.getColor(this, R.color.m3_sys_dark_primary)
        val inactive = ContextCompat.getColor(this, R.color.m3_sys_dark_on_surface_variant)

        findViewById<MaterialButton>(R.id.btn_zoom_1x)?.setTextColor(if (active == 1) primary else inactive)
        findViewById<MaterialButton>(R.id.btn_zoom_2x)?.setTextColor(if (active == 2) primary else inactive)
        findViewById<MaterialButton>(R.id.btn_zoom_3x)?.setTextColor(if (active == 3) primary else inactive)
    }

    private fun setZoom(ratio: Float) {
        val cam = camera ?: return
        val state = cam.cameraInfo.zoomState.value ?: return
        val clamped = ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        cam.cameraControl.setZoomRatio(clamped)
    }

    private fun decodePhoto(uri: Uri) {
        if (!handled.compareAndSet(false, true)) return
        try {
            val image = InputImage.fromFilePath(this, uri)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val qr = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
                    if (qr != null) {
                        setResult(RESULT_OK, Intent().putExtra(RESULT_QR, qr))
                        finish()
                    } else {
                        handled.set(false)
                        Toast.makeText(this, getString(R.string.main_invalid_qr), Toast.LENGTH_LONG).show()
                    }
                }
                .addOnFailureListener {
                    handled.set(false)
                    Toast.makeText(this, it.message ?: "QR photo failed", Toast.LENGTH_LONG).show()
                }
        } catch (e: Exception) {
            handled.set(false)
            Toast.makeText(this, e.message ?: "QR photo failed", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(proxy: ImageProxy) {
        if (handled.get()) {
            proxy.close()
            return
        }
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val qr = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
                if (qr != null && handled.compareAndSet(false, true)) {
                    setResult(RESULT_OK, Intent().putExtra(RESULT_QR, qr))
                    finish()
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
