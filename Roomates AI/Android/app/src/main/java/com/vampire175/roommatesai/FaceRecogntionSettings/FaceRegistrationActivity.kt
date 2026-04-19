package com.vampire175.roommatesai.FaceRecognition

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.vampire175.roommatesai.GestureControls.fragment.PermissionsFragment
import com.vampire175.roommatesai.databinding.ActivityFaceRegistrationBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Stand-alone activity for registering new faces.
 * Opens the front camera, lets the user see a live preview, then
 * taps "Capture" → enters a name → saves the embedding.
 *
 * CHANGES vs original:
 *  1. Switched from YUV_420_888 → RGBA_8888 with the SAME extraction path
 *     used by CameraFragment.  Registration and recognition now see
 *     identical-looking bitmaps, so embeddings are directly comparable.
 *  2. imageInfo.rotationDegrees is applied inside the bitmap extractor,
 *     so ML Kit receives an upright image and bounding-box crops are correct.
 *  3. InputImage.fromBitmap(bitmap, 0) is now correct because rotation is
 *     already baked into the bitmap before it reaches ML Kit.
 *  4. Multi-sample registration: the user can capture up to MAX_SAMPLES
 *     frames for the same person; the repository averages them automatically.
 *
 * Launch from LaunchScreen with:
 *   startActivity(Intent(this, FaceRegistrationActivity::class.java))
 */
class FaceRegistrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFaceRegistrationBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var faceHelper: FaceRecognitionHelper
    private lateinit var repo: FaceEmbeddingRepository

    // ── Frame capture ─────────────────────────────────────────────────

    /**
     * Holds the most recent camera frame together with the rotation that was
     * already applied, so we always pass rotation=0 to InputImage.
     */
    private data class FrameData(val bitmap: Bitmap, val rotationApplied: Int)

    /** Updated on the camera executor thread; read on the main thread. */
    @Volatile private var latestFrame: FrameData? = null

    // ── Multi-sample state ────────────────────────────────────────────

    /** Name chosen by the user for the current registration session. */
    private var currentName: String? = null

    /** How many samples have been captured so far for [currentName]. */
    private var samplesCaptured = 0

    companion object {
        private const val TAG = "FaceRegistration"

        /**
         * Number of samples to collect per person before finishing.
         * The repository averages them automatically.
         * Recommended range: 3–5.
         */
        private const val MAX_SAMPLES = 5
    }

    // ── Face detector ─────────────────────────────────────────────────

    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                // FIX: use ACCURATE mode (same as CameraFragment)
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(0.15f)
                .build()
        )
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFaceRegistrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!PermissionsFragment.hasPermissions(this)) {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        faceHelper     = FaceRecognitionHelper(this)
        repo           = FaceEmbeddingRepository(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        startCamera()
        setupButtons()
        refreshRegisteredList()
        updateCaptureButtonLabel()
    }

    override fun onDestroy() {
        super.onDestroy()
        faceHelper.close()
        faceDetector.close()
        cameraExecutor.shutdown()
    }

    // ------------------------------------------------------------------
    // Camera
    // ------------------------------------------------------------------

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            // FIX: use RGBA_8888 – same format as CameraFragment –
            //      so the bitmap extraction pipeline is identical in both
            //      registration and recognition.
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build().also { ia ->
                    ia.setAnalyzer(cameraExecutor) { proxy ->
                        // Recycle the previous frame's bitmap to avoid leaks
                        latestFrame?.bitmap?.recycle()

                        val bmp = proxy.toUprightBitmap(proxy.imageInfo.rotationDegrees)
                        latestFrame = if (bmp != null) FrameData(bmp, proxy.imageInfo.rotationDegrees) else null
                        proxy.close()
                    }
                }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview, analysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private fun setupButtons() {
        binding.btnCapture.setOnClickListener { captureAndRegister() }
        binding.btnDeleteAll.setOnClickListener { confirmDeleteAll() }
        binding.btnBack.setOnClickListener { finish() }
    }

    /**
     * Captures the current frame and either:
     *  - (first tap)        → detects face, asks for name, saves sample 1.
     *  - (subsequent taps)  → adds another sample for the same name.
     */
    private fun captureAndRegister() {
        val frame = latestFrame ?: run {
            Toast.makeText(this, "Camera not ready yet", Toast.LENGTH_SHORT).show()
            return
        }

        // Take a safe copy so the camera thread can continue updating latestFrame
        val bitmap = frame.bitmap.copy(Bitmap.Config.ARGB_8888, false)

        binding.btnCapture.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        showStatus("Detecting face…")

        // FIX: rotation=0 because toUprightBitmap() already applied rotation
        val image = InputImage.fromBitmap(bitmap, 0)

        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                binding.progressBar.visibility = View.GONE

                if (faces.isEmpty()) {
                    showStatus("❌ No face detected – move closer or improve lighting")
                    bitmap.recycle()
                    binding.btnCapture.isEnabled = true
                    return@addOnSuccessListener
                }

                val face    = faces.maxByOrNull { it.boundingBox.width() }!!
                val cropped = cropFace(bitmap, face.boundingBox)
                bitmap.recycle()

                if (cropped == null) {
                    showStatus("❌ Face crop failed – try again")
                    binding.btnCapture.isEnabled = true
                    return@addOnSuccessListener
                }

                if (currentName == null) {
                    // First capture: ask for a name
                    showStatus("✅ Face detected – enter a name")
                    promptForName { name ->
                        if (name.isBlank()) {
                            cropped.recycle()
                            binding.btnCapture.isEnabled = true
                            return@promptForName
                        }
                        currentName    = name.trim()
                        samplesCaptured = 0
                        saveSample(cropped, currentName!!)
                    }
                } else {
                    // Subsequent capture: add another sample for the same person
                    saveSample(cropped, currentName!!)
                }
            }
            .addOnFailureListener { e ->
                showStatus("Detection error: ${e.message}")
                bitmap.recycle()
                binding.btnCapture.isEnabled = true
                binding.progressBar.visibility = View.GONE
            }
    }

    /**
     * Generates an embedding for [cropped], saves it, and updates the UI.
     * When [MAX_SAMPLES] are reached the session is reset.
     */
    private fun saveSample(cropped: Bitmap, name: String) {
        cameraExecutor.execute {
            val embedding = faceHelper.generateEmbedding(cropped)
            cropped.recycle()
            repo.saveEmbedding(name, embedding)
            samplesCaptured++

            runOnUiThread {
                val totalSamples = repo.getSampleCount(name)
                if (samplesCaptured >= MAX_SAMPLES) {
                    // Session complete
                    showStatus(
                        "✅ \"$name\" registered with $totalSamples sample(s)! " +
                                "Tap Capture to register another person."
                    )
                    currentName     = null
                    samplesCaptured = 0
                } else {
                    val remaining = MAX_SAMPLES - samplesCaptured
                    showStatus(
                        "✅ Sample $samplesCaptured/$MAX_SAMPLES saved for \"$name\". " +
                                "Tap Capture $remaining more time(s) from different angles."
                    )
                }
                refreshRegisteredList()
                updateCaptureButtonLabel()
                binding.btnCapture.isEnabled = true
            }
        }
    }

    private fun updateCaptureButtonLabel() {
        binding.btnCapture.text = when {
            currentName != null -> "Add sample ${samplesCaptured + 1}/$MAX_SAMPLES for \"$currentName\""
            else                -> "Capture"
        }
    }

    private fun promptForName(onName: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            hint = "e.g. Alice"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        AlertDialog.Builder(this)
            .setTitle("Enter person's name")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> onName(input.text.toString()) }
            .setNegativeButton("Cancel") { _, _ -> binding.btnCapture.isEnabled = true }
            .show()
    }

    private fun confirmDeleteAll() {
        AlertDialog.Builder(this)
            .setTitle("Delete all faces?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                repo.deleteAll()
                currentName     = null
                samplesCaptured = 0
                refreshRegisteredList()
                updateCaptureButtonLabel()
                showStatus("All faces deleted")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshRegisteredList() {
        val names = repo.getAllNames()
        binding.tvRegistered.text = if (names.isEmpty()) {
            "No faces registered yet"
        } else {
            buildString {
                append("Registered (${names.size}): ")
                names.forEach { name ->
                    append("$name (${repo.getSampleCount(name)} sample(s))  ")
                }
            }.trimEnd()
        }
    }

    private fun showStatus(msg: String) {
        runOnUiThread { binding.tvStatus.text = msg }
    }

    // ------------------------------------------------------------------
    // Bitmap helpers  (IDENTICAL logic to CameraFragment for consistency)
    // ------------------------------------------------------------------

    /**
     * Extracts an upright, front-camera-mirrored [Bitmap] from an RGBA_8888
     * [ImageProxy] without closing it.
     *
     * FIX: [rotationDegrees] (from imageInfo.rotationDegrees) is applied so
     * that the bitmap is upright before it reaches ML Kit, matching the exact
     * pipeline used in CameraFragment.  The caller should pass rotation=0 to
     * InputImage.fromBitmap() because it is already embedded in the pixels.
     */
    @SuppressLint("UnsafeOptInUsageError")
    private fun ImageProxy.toUprightBitmap(rotationDegrees: Int): Bitmap? {
        return try {
            val plane       = planes[0]
            val buffer      = plane.buffer.duplicate()
            val pixelStride = plane.pixelStride
            val rowStride   = plane.rowStride
            val rowPadding  = rowStride - pixelStride * width

            val raw = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
            )
            raw.copyPixelsFromBuffer(buffer)

            // Crop padding columns
            val cropped = Bitmap.createBitmap(raw, 0, 0, width, height)
            if (cropped != raw) raw.recycle()

            // 1. Rotate to upright
            val rotated = rotateBitmap(cropped, rotationDegrees.toFloat())

            // 2. Mirror front camera
            mirrorBitmap(rotated)
        } catch (e: Exception) {
            Log.w(TAG, "Bitmap extraction failed: ${e.message}")
            null
        }
    }

    private fun rotateBitmap(src: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return src
        val m = Matrix().apply { postRotate(degrees) }
        val result = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, false)
        src.recycle()
        return result
    }

    private fun mirrorBitmap(src: Bitmap): Bitmap {
        val m = Matrix().apply { postScale(-1f, 1f, src.width / 2f, src.height / 2f) }
        val mirrored = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, false)
        src.recycle()
        return mirrored
    }

    private fun cropFace(src: Bitmap, box: Rect): Bitmap? {
        val pad    = (box.width() * 0.20f).toInt()
        val left   = (box.left   - pad).coerceAtLeast(0)
        val top    = (box.top    - pad).coerceAtLeast(0)
        val right  = (box.right  + pad).coerceAtMost(src.width)
        val bottom = (box.bottom + pad).coerceAtMost(src.height)
        if (right <= left || bottom <= top) return null
        return Bitmap.createBitmap(src, left, top, right - left, bottom - top)
    }
}