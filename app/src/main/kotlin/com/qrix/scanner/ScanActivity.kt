package com.qrix.scanner

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

/**
 * Live scanner. The camera preview is clipped to the rounded frame, and only
 * codes that are INSIDE that frame are accepted (the analysis image is cropped to
 * the same view-port as the preview).
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
class ScanActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var permissionView: View
    private lateinit var btnTorch: ImageButton
    private lateinit var btnAutoZoom: ImageButton
    private lateinit var txtZoom: TextView
    private lateinit var zoomChips: List<Pair<TextView, Float>>

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    private var scanner: BarcodeScanner? = null
    @Volatile private var torchOn = false
    @Volatile private var torchManual = false
    @Volatile private var autoFlash = true
    @Volatile private var lastLumaCheck = 0L
    @Volatile private var darkCount = 0

    @Volatile
    private var autoZoom = true

    @Volatile
    private var handled = false

    // Auto zoom bookkeeping (uptimeMillis)
    @Volatile private var lastManualZoom = 0L
    @Volatile private var lastAutoZoom = 0L
    @Volatile private var lastSeen = 0L
    @Volatile private var autoZoomed = false
    private var zoomAnim: ValueAnimator? = null

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                permissionView.visibility = View.GONE
                startCamera()
            } else {
                permissionView.visibility = View.VISIBLE
            }
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                handled = true // pause camera decoding while the image is processed
                ScanFlow.scanImage(this, uri) { found ->
                    if (found) finish() else handled = false
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        Bars.apply(this)

        previewView = findViewById(R.id.previewView)
        permissionView = findViewById(R.id.permissionView)
        btnTorch = findViewById(R.id.btnTorch)
        btnAutoZoom = findViewById(R.id.btnAutoZoom)
        txtZoom = findViewById(R.id.txtZoom)
        zoomChips = listOf(
            findViewById<TextView>(R.id.zoom1) to 1f,
            findViewById<TextView>(R.id.zoom2) to 2f,
            findViewById<TextView>(R.id.zoom3) to 3f
        )

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnGallery).setOnClickListener { launchGallery() }
        findViewById<View>(R.id.btnPermGallery).setOnClickListener { launchGallery() }
        findViewById<View>(R.id.btnGrant).setOnClickListener {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                cameraPermission.launch(Manifest.permission.CAMERA)
            } else {
                // "Don't ask again" – send the user to app settings
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", packageName, null))
                )
            }
        }
        autoFlash = Prefs.autoFlash(this)
        btnTorch.setOnClickListener {
            torchManual = true // user took control, auto flash stays out of the way
            setTorch(!torchOn)
        }

        autoZoom = Prefs.autoZoom(this)
        renderAutoZoomButton()
        btnAutoZoom.setOnClickListener {
            autoZoom = !autoZoom
            Prefs.setAutoZoom(this, autoZoom)
            renderAutoZoomButton()
            Toast.makeText(this, if (autoZoom) "Auto zoom on" else "Auto zoom off", Toast.LENGTH_SHORT).show()
        }
        zoomChips.forEach { (chip, ratio) ->
            chip.setOnClickListener { setZoom(ratio) }
        }
        setupTouch()

        if (hasCameraPermission()) startCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        // Returning from app settings after granting permission
        if (hasCameraPermission() && camera == null) {
            permissionView.visibility = View.GONE
            startCamera()
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun launchGallery() {
        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    // ------------------------------------------------------------------ camera

    private fun startCamera() {
        // The view-port needs a laid-out PreviewView.
        previewView.doOnLayout { bindCamera() }
    }

    private var binding = false

    private fun bindCamera() {
        if (camera != null || binding) return
        binding = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        ).build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val group = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(analysis)
                .apply { previewView.viewPort?.let { setViewPort(it) } }
                .build()

            try {
                provider.unbindAll()
                val cam = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group)
                camera = cam
                btnTorch.visibility =
                    if (cam.cameraInfo.hasFlashUnit()) View.VISIBLE else View.GONE
                cam.cameraInfo.zoomState.observe(this) { zs -> renderZoom(zs.zoomRatio) }

                val max = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 8f
                scanner?.close()
                scanner = buildScanner(max)
                analysis.setAnalyzer(executor) { analyze(it) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            binding = false
        }, ContextCompat.getMainExecutor(this))
    }

    /** ML Kit scanner whose zoom suggestions drive the camera (auto zoom). */
    private fun buildScanner(maxZoom: Float): BarcodeScanner {
        val b = BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
        if (maxZoom > 1.05f) {
            b.setZoomSuggestionOptions(
                ZoomSuggestionOptions.Builder(ZoomSuggestionOptions.ZoomCallback { ratio ->
                    applyAutoZoom(ratio)
                }).setMaxSupportedZoomRatio(maxZoom).build()
            )
        }
        return BarcodeScanning.getClient(b.build())
    }

    /**
     * Called by ML Kit (background thread) when it sees a small code and suggests a zoom.
     * The zoom is eased in instead of jumping, never fights a manual pinch / chip tap, and
     * ignores tiny or too-frequent suggestions so the picture does not pump in and out.
     */
    private fun applyAutoZoom(ratio: Float): Boolean {
        val cam = camera ?: return false
        if (!autoZoom || handled) return false
        val now = SystemClock.uptimeMillis()
        if (now - lastManualZoom < MANUAL_HOLD_MS || now - lastAutoZoom < AUTO_GAP_MS) return false
        val zs = cam.cameraInfo.zoomState.value ?: return false
        val target = ratio.coerceIn(zs.minZoomRatio, min(zs.maxZoomRatio, AUTO_MAX))
        if (abs(target - zs.zoomRatio) < 0.2f) return false
        lastAutoZoom = now
        lastSeen = now
        autoZoomed = true
        previewView.post { animateZoom(target) }
        return true
    }

    /** Smoothly moves the camera zoom to [target]. */
    private fun animateZoom(target: Float) {
        val cam = camera ?: return
        val from = cam.cameraInfo.zoomState.value?.zoomRatio ?: return
        zoomAnim?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled() || abs(target - from) < 0.05f) {
            cam.cameraControl.setZoomRatio(target)
            return
        }
        zoomAnim = ValueAnimator.ofFloat(from, target).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener { cam.cameraControl.setZoomRatio(it.animatedValue as Float) }
            start()
        }
    }

    private fun setTorch(on: Boolean) {
        if (camera?.cameraInfo?.hasFlashUnit() != true) return
        torchOn = on
        camera?.cameraControl?.enableTorch(on)
        btnTorch.setImageResource(if (on) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
    }

    /** Auto flash: samples the preview brightness a few times a second and turns the torch on in the dark. */
    private fun checkDark(proxy: ImageProxy) {
        if (!autoFlash || torchOn || torchManual) return
        val now = SystemClock.uptimeMillis()
        if (now - lastLumaCheck < LUMA_EVERY_MS) return
        lastLumaCheck = now
        val plane = proxy.planes.firstOrNull() ?: return
        val buf = plane.buffer
        val w = proxy.width
        val h = proxy.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var sum = 0L
        var n = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val i = y * rowStride + x * pixelStride
                if (i < buf.limit()) {
                    sum += buf.get(i).toInt() and 0xFF
                    n++
                }
                x += LUMA_STEP
            }
            y += LUMA_STEP
        }
        if (n == 0) return
        if (sum / n < DARK_LUMA) {
            darkCount++
            if (darkCount >= 2) runOnUiThread { if (!torchOn && !torchManual) setTorch(true) }
        } else {
            darkCount = 0
        }
    }

    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        val sc = scanner
        if (media == null || sc == null || handled) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(media, rotation)
        val region = uprightRegion(proxy.cropRect, proxy.width, proxy.height, rotation)

        checkDark(proxy)

        sc.process(input)
            .addOnSuccessListener { list ->
                val now = SystemClock.uptimeMillis()
                if (list.isNotEmpty()) lastSeen = now
                else if (autoZoomed && autoZoom && now - lastSeen > RESET_AFTER_MS &&
                    now - lastManualZoom > MANUAL_HOLD_MS
                ) {
                    autoZoomed = false
                    animateZoom(1f)
                }
                // Only codes whose centre lies inside the visible frame count.
                val cx = region.exactCenterX()
                val cy = region.exactCenterY()
                val hit = list
                    .filter { !it.rawValue.isNullOrEmpty() }
                    .filter { b -> b.boundingBox?.let { region.contains(it.centerX(), it.centerY()) } ?: true }
                    .minByOrNull { b ->
                        b.boundingBox?.let { abs(it.exactCenterX() - cx) + abs(it.exactCenterY() - cy) } ?: 0f
                    }
                if (hit != null && !handled) {
                    handled = true
                    ScanFlow.open(this, hit.rawValue!!, CodeUtils.formatName(hit.format))
                    finish()
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    /** Converts the crop rect (sensor buffer coordinates) into the upright image used by ML Kit. */
    private fun uprightRegion(crop: Rect, w: Int, h: Int, rotation: Int): Rect = when (rotation) {
        90 -> Rect(h - crop.bottom, crop.left, h - crop.top, crop.right)
        180 -> Rect(w - crop.right, h - crop.bottom, w - crop.left, h - crop.top)
        270 -> Rect(crop.top, w - crop.right, crop.bottom, w - crop.left)
        else -> Rect(crop)
    }

    // -------------------------------------------------------------------- zoom

    private fun setZoom(ratio: Float) {
        val cam = camera ?: return
        val zs = cam.cameraInfo.zoomState.value ?: return
        lastManualZoom = SystemClock.uptimeMillis()
        autoZoomed = false
        animateZoom(ratio.coerceIn(zs.minZoomRatio, zs.maxZoomRatio))
    }

    private fun renderZoom(ratio: Float) {
        zoomChips.forEach { (chip, r) -> chip.isSelected = abs(ratio - r) < 0.15f }
        if (ratio > 1.05f) {
            txtZoom.visibility = View.VISIBLE
            txtZoom.text = String.format("%.1f×", ratio)
        } else {
            txtZoom.visibility = View.GONE
        }
    }

    private fun renderAutoZoomButton() {
        btnAutoZoom.setBackgroundResource(if (autoZoom) R.drawable.bg_circle_accent else R.drawable.bg_circle_scan)
        btnAutoZoom.setColorFilter(
            androidx.core.content.ContextCompat.getColor(
                this, if (autoZoom) R.color.qx_on_scan_accent else R.color.qx_text
            )
        )
    }

    /** Pinch to zoom, tap to focus. */
    private fun setupTouch() {
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return true
                val zs = cam.cameraInfo.zoomState.value ?: return true
                val target = (zs.zoomRatio * detector.scaleFactor).coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
                lastManualZoom = SystemClock.uptimeMillis()
                autoZoomed = false
                zoomAnim?.cancel()
                cam.cameraControl.setZoomRatio(target)
                return true
            }
        })
        previewView.setOnTouchListener { v, e ->
            pinch.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_UP && !pinch.isInProgress &&
                e.eventTime - e.downTime < 250
            ) {
                camera?.cameraControl?.let { control ->
                    val point = previewView.meteringPointFactory.createPoint(e.x, e.y)
                    control.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                }
                v.performClick()
            }
            true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        zoomAnim?.cancel()
        scanner?.close()
        executor.shutdown()
    }

    private companion object {
        const val DARK_LUMA = 50 // 0-255, raise to turn the torch on earlier
        const val LUMA_EVERY_MS = 400L
        const val LUMA_STEP = 24
        const val AUTO_MAX = 6f
        const val AUTO_GAP_MS = 350L
        const val MANUAL_HOLD_MS = 3000L
        const val RESET_AFTER_MS = 2500L
    }
}
