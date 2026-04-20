/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 * (original licence header preserved)
 *
 * MODIFIED: Added throttled face recognition via ML Kit + MobileFaceNet.
 *           Face recognition runs on its own faceExecutor so it NEVER blocks
 *           the MediaPipe backgroundExecutor.
 *
 * FIX (accuracy):
 *   - toBitmapRgba() now reads imageInfo.rotationDegrees and rotates the
 *     bitmap BEFORE passing it to ML Kit, so bounding boxes land on the
 *     correct pixels and cropFace() receives an upright image.
 *   - InputImage.fromBitmap(bitmap, 0) is now correct because rotation is
 *     already baked into the bitmap.
 */
package com.vampire175.roommatesai.GestureControls.fragment

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.vampire175.roommatesai.FaceRecognition.FaceEmbeddingRepository
import com.vampire175.roommatesai.FaceRecognition.FaceRecognitionHelper
import com.vampire175.roommatesai.GestureControls.GestureRecognizerHelper
import com.vampire175.roommatesai.GestureControls.MainViewModel
import com.vampire175.roommatesai.R
import com.vampire175.roommatesai.databinding.FragmentCameraBinding
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.content.res.Resources
import android.graphics.Color
import com.vampire175.roommatesai.Bluetooth.GetFingerDataAndWrite

class CameraFragment : Fragment(),
    GestureRecognizerHelper.GestureRecognizerListener {

    companion object {
        private const val TAG = "Hand gesture recognizer"
        /**
         * Run face recognition every N frames.
         * MediaPipe runs ~30 fps → 20 means face recognition every ~0.67 s.
         */
        private const val FACE_RECOGNITION_INTERVAL = 1
    }

    // ── View binding ──────────────────────────────────────────────────
    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    // ── Gesture (MediaPipe) ───────────────────────────────────────────
    val gestureHandler = GetFingerDataAndWrite()
    private lateinit var gestureRecognizerHelper: GestureRecognizerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var defaultNumResults = 1
    private val gestureRecognizerResultAdapter: GestureRecognizerResultsAdapter by lazy {
        GestureRecognizerResultsAdapter().apply { updateAdapterSize(defaultNumResults) }
    }

    // ── Camera ────────────────────────────────────────────────────────
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    // ── Executors ─────────────────────────────────────────────────────
    /** MediaPipe executor – single thread, owned by original code */
    private lateinit var backgroundExecutor: ExecutorService
    /** Separate executor for face detection + embedding (never blocks MediaPipe) */
    private lateinit var faceExecutor: ExecutorService

    // ── Face recognition components ───────────────────────────────────
    private lateinit var faceRecognitionHelper: FaceRecognitionHelper
    private lateinit var faceEmbeddingRepository: FaceEmbeddingRepository

    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(0.10f)
                .enableTracking()
                .build()
        )
    }

    /** Frame counter used for throttling */
    @Volatile private var frameCounter = 0
    /** Guard against concurrent face-recognition calls */
    @Volatile private var faceRecognitionInProgress = false

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                .navigate(R.id.action_camera_to_permissions)
        }
        backgroundExecutor.execute {
            if (gestureRecognizerHelper.isClosed()) gestureRecognizerHelper.setupGestureRecognizer()
        }
    }

    override fun onPause() {
        super.onPause()
        if (this::gestureRecognizerHelper.isInitialized) {
            viewModel.setMinHandDetectionConfidence(gestureRecognizerHelper.minHandDetectionConfidence)
            viewModel.setMinHandTrackingConfidence(gestureRecognizerHelper.minHandTrackingConfidence)
            viewModel.setMinHandPresenceConfidence(gestureRecognizerHelper.minHandPresenceConfidence)
            viewModel.setDelegate(gestureRecognizerHelper.currentDelegate)
            backgroundExecutor.execute { gestureRecognizerHelper.clearGestureRecognizer() }
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
        faceExecutor.shutdown()
        faceExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
        if (this::faceRecognitionHelper.isInitialized) faceRecognitionHelper.close()
        faceDetector.close()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        with(fragmentCameraBinding.recyclerviewResults) {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = gestureRecognizerResultAdapter
        }

        // ── Executors ─────────────────────────────────────────────────
        backgroundExecutor = Executors.newSingleThreadExecutor()
        faceExecutor        = Executors.newSingleThreadExecutor()

        // ── Face recognition init ──────────────────────────────────────
        faceEmbeddingRepository = FaceEmbeddingRepository(requireContext())
        faceExecutor.execute {
            faceRecognitionHelper = FaceRecognitionHelper(requireContext())
        }

        // ── Camera ────────────────────────────────────────────────────
        fragmentCameraBinding.viewFinder.post { setUpCamera() }

        // ── MediaPipe gesture recognizer ──────────────────────────────
        backgroundExecutor.execute {
            gestureRecognizerHelper = GestureRecognizerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minHandDetectionConfidence = viewModel.currentMinHandDetectionConfidence,
                minHandTrackingConfidence  = viewModel.currentMinHandTrackingConfidence,
                minHandPresenceConfidence  = viewModel.currentMinHandPresenceConfidence,
                currentDelegate            = viewModel.currentDelegate,
                gestureRecognizerListener  = this
            )
        }

        initBottomSheetControls()
    }

    // ── Bottom sheet ──────────────────────────────────────────────────

    private fun initBottomSheetControls() {
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(Locale.US, "%.2f", viewModel.currentMinHandDetectionConfidence)
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(Locale.US, "%.2f", viewModel.currentMinHandTrackingConfidence)
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(Locale.US, "%.2f", viewModel.currentMinHandPresenceConfidence)

        fragmentCameraBinding.bottomSheetLayout.detectionThresholdMinus.setOnClickListener {
            if (gestureRecognizerHelper.minHandDetectionConfidence >= 0.2) {
                gestureRecognizerHelper.minHandDetectionConfidence -= 0.1f; updateControlsUi()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdPlus.setOnClickListener {
            if (gestureRecognizerHelper.minHandDetectionConfidence <= 0.8) {
                gestureRecognizerHelper.minHandDetectionConfidence += 0.1f; updateControlsUi()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdMinus.setOnClickListener {
            if (gestureRecognizerHelper.minHandTrackingConfidence >= 0.2) {
                gestureRecognizerHelper.minHandTrackingConfidence -= 0.1f; updateControlsUi()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdPlus.setOnClickListener {
            if (gestureRecognizerHelper.minHandTrackingConfidence <= 0.8) {
                gestureRecognizerHelper.minHandTrackingConfidence += 0.1f; updateControlsUi()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdMinus.setOnClickListener {
            if (gestureRecognizerHelper.minHandPresenceConfidence >= 0.2) {
                gestureRecognizerHelper.minHandPresenceConfidence -= 0.1f; updateControlsUi()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdPlus.setOnClickListener {
            if (gestureRecognizerHelper.minHandPresenceConfidence <= 0.8) {
                gestureRecognizerHelper.minHandPresenceConfidence += 0.1f; updateControlsUi()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(viewModel.currentDelegate, false)
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                    try { gestureRecognizerHelper.currentDelegate = p2; updateControlsUi() }
                    catch (e: UninitializedPropertyAccessException) {
                        Log.e(TAG, "GestureRecognizerHelper not initialized yet.")
                    }
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
    }

    private fun updateControlsUi() {
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(Locale.US, "%.2f", gestureRecognizerHelper.minHandDetectionConfidence)
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(Locale.US, "%.2f", gestureRecognizerHelper.minHandTrackingConfidence)
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(Locale.US, "%.2f", gestureRecognizerHelper.minHandPresenceConfidence)
        backgroundExecutor.execute {
            gestureRecognizerHelper.clearGestureRecognizer()
            gestureRecognizerHelper.setupGestureRecognizer()
        }
        fragmentCameraBinding.overlay.clear()
    }

    // ── Camera setup ──────────────────────────────────────────────────

    private fun setUpCamera() {
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            cameraProvider = future.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun runFaceRecognitionSafe(bitmap: Bitmap) {
        if (faceRecognitionInProgress) {
            bitmap.recycle()
            return
        }

        faceRecognitionInProgress = true

        faceExecutor.execute {
            try {
                runFaceRecognition(bitmap)
            } finally {
                faceRecognitionInProgress = false
                bitmap.recycle()
            }
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")
        val selector = CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also {
                it.setAnalyzer(backgroundExecutor) { image ->

                    // 🔹 STEP A: Decide if we should run face recognition
                    val shouldRunFace = (
                            frameCounter % FACE_RECOGNITION_INTERVAL == 0 &&
                                    !faceRecognitionInProgress &&
                                    faceEmbeddingRepository.hasFaces()
                            )

                    // 🔹 STEP B: Extract bitmap BEFORE image is closed.
                    //    FIX: pass rotationDegrees so the bitmap is upright
                    //    before ML Kit processes it.
                    val bitmap = if (shouldRunFace) {
                        image.toBitmapRgba(image.imageInfo.rotationDegrees)
                    } else null

                    // 🔹 STEP C: Run MediaPipe (this closes image internally)
                    recognizeHand(image)

                    // 🔹 STEP D: Run face recognition using safe bitmap
                    if (bitmap != null) {
                        runFaceRecognitionSafe(bitmap)
                    }

                    frameCounter++
                }
            }

        provider.unbindAll()
        try {
            camera = provider.bindToLifecycle(this, selector, preview, imageAnalyzer)
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    // ── MediaPipe gesture ─────────────────────────────────────────────

    private fun recognizeHand(imageProxy: ImageProxy) {
        gestureRecognizerHelper.recognizeLiveStream(imageProxy = imageProxy)
        // NOTE: imageProxy.close() is handled inside GestureRecognizerHelper
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation = fragmentCameraBinding.viewFinder.display.rotation
    }

    override fun onResults(resultBundle: GestureRecognizerHelper.ResultBundle) {
        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null) {
                val gestureCategories = resultBundle.results.first().gestures()
                if (gestureCategories.isNotEmpty()) {
                    gestureRecognizerResultAdapter.updateResults(gestureCategories.first())
                } else {
                    gestureRecognizerResultAdapter.updateResults(emptyList())
                }
                fragmentCameraBinding.bottomSheetLayout.inferenceTimeVal.text =
                    String.format("%d ms", resultBundle.inferenceTime)
                fragmentCameraBinding.overlay.setResults(
                    resultBundle.results.first(),
                    resultBundle.inputImageHeight,
                    resultBundle.inputImageWidth,
                    RunningMode.LIVE_STREAM
                )
                fragmentCameraBinding.overlay.invalidate()
            }
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            gestureRecognizerResultAdapter.updateResults(emptyList())
            if (errorCode == GestureRecognizerHelper.GPU_ERROR) {
                fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
                    GestureRecognizerHelper.DELEGATE_CPU, false)
            }
        }
    }

    // ── Face recognition ──────────────────────────────────────────────

    private fun runFaceRecognition(bitmap: Bitmap) {
        if (!this::faceRecognitionHelper.isInitialized) return

        // FIX: rotation=0 because the bitmap is already rotated to upright
        //      in toBitmapRgba(rotationDegrees).
        val image = InputImage.fromBitmap(bitmap, 0)

        val faces = try {
            com.google.android.gms.tasks.Tasks.await(faceDetector.process(image))
        } catch (e: Exception) {
            Log.w(TAG, "Face detection failed: ${e.message}")
            ChangeHGRStartedText(false)
            return

        }

        if (faces.isEmpty()) {
            Log.d(TAG, "No face detected")
            gestureRecognizerResultAdapter.getVerified(false)
            activity?.runOnUiThread {
                fragmentCameraBinding.overlay.clearFaceLabels()
            }
            ChangeHGRStartedText(false)
            return
        }

        Log.d(TAG, "Face detected: ${faces.size} face(s)")

        val storedEmbeddings = faceEmbeddingRepository.getAllEmbeddings()
        val results = mutableListOf<Pair<Rect, String>>()

        for (face in faces) {
            val box     = face.boundingBox
            val cropped = cropFace(bitmap, box) ?: continue

            val emb   = faceRecognitionHelper.generateEmbedding(cropped)
            cropped.recycle()

            val match = faceRecognitionHelper.findBestMatch(emb, storedEmbeddings)
            val label = match?.first ?: "Unknown"

            Log.d(TAG, "Face recognized: $label")
            if(label!="Unknown"){
                gestureRecognizerResultAdapter.getVerified(true)
                ChangeHGRStartedText(true)
            }
            else{
                gestureRecognizerResultAdapter.getVerified(false)
                ChangeHGRStartedText(false)
            }

            if (match != null) {
                Log.d(TAG, "Match distance: ${match.second}")
            }

            results.add(Pair(box, label))
        }

        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null) {
                fragmentCameraBinding.overlay.setFaceResults(
                    results,
                    bitmap.height,
                    bitmap.width
                )
            }
        }
    }

    // ── Bitmap helpers ────────────────────────────────────────────────

    /**
     * Extracts an upright, front-camera-mirrored [Bitmap] from an RGBA_8888
     * [ImageProxy] WITHOUT closing it.
     *
     * FIX vs original:
     *   [rotationDegrees] (from imageInfo.rotationDegrees) is applied BEFORE
     *   the mirror so that ML Kit receives an upright image and its bounding
     *   boxes map correctly onto the pixels we crop.
     */
    private fun ImageProxy.toBitmapRgba(rotationDegrees: Int): Bitmap? {
        return try {
            val plane       = planes[0]
            val buffer      = plane.buffer.duplicate()      // don't drain the original
            val pixelStride = plane.pixelStride
            val rowStride   = plane.rowStride
            val rowPadding  = rowStride - pixelStride * width

            val raw = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
            )
            raw.copyPixelsFromBuffer(buffer)

            // Crop away row-padding columns
            val cropped = Bitmap.createBitmap(raw, 0, 0, width, height)
            if (cropped != raw) raw.recycle()

            // 1. Rotate to upright  (FIX)
            val rotated = rotateBitmap(cropped, rotationDegrees.toFloat())

            // 2. Mirror for front camera
            mirrorBitmap(rotated)
        } catch (e: Exception) {
            Log.w(TAG, "Bitmap extraction failed: ${e.message}")
            null
        }
    }

    /**
     * Rotates [src] by [degrees] clockwise and recycles the source.
     * Returns [src] unchanged if [degrees] is 0.
     */
    private fun rotateBitmap(src: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return src
        val m = Matrix().apply { postRotate(degrees) }
        val result = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, false)
        src.recycle()
        return result
    }

    /** Flips [src] horizontally (front-camera mirror) and recycles the source. */
    private fun mirrorBitmap(src: Bitmap): Bitmap {
        val m = Matrix().apply { postScale(-1f, 1f, src.width / 2f, src.height / 2f) }
        val mirrored = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, false)
        src.recycle()
        return mirrored
    }

    /**
     * Crops [box] out of [src] with 20 % padding, clamped to image bounds.
     */
    private fun cropFace(src: Bitmap, box: Rect): Bitmap? {
        val pad    = (box.width() * 0.20f).toInt()
        val left   = (box.left   - pad).coerceAtLeast(0)
        val top    = (box.top    - pad).coerceAtLeast(0)
        val right  = (box.right  + pad).coerceAtMost(src.width)
        val bottom = (box.bottom + pad).coerceAtMost(src.height)
        if (right <= left || bottom <= top) return null
        return Bitmap.createBitmap(src, left, top, right - left, bottom - top)
    }

    // ── HGR text helper ───────────────────────────────────────────────

    public fun ChangeHGRStartedText(started: Boolean) {
        if (started) {
            fragmentCameraBinding.textView2.setTextColor(Color.GREEN)
            fragmentCameraBinding.textView2.text = "Hand Gesture Recognition : ON"
        } else {
            fragmentCameraBinding.textView2.setTextColor(Color.RED)
            fragmentCameraBinding.textView2.text = "Hand Gesture Recognition : OFF"
        }
    }
}