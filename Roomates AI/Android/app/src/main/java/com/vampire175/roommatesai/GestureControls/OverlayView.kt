/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 * (original licence header preserved)
 *
 * MODIFIED: Added setFaceResults() and clearFaceLabels() to draw
 *           face bounding boxes and recognition labels on top of hand landmarks.
 */
package com.vampire175.roommatesai.GestureControls

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.vampire175.roommatesai.R
import kotlin.math.max
import kotlin.math.min

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    // ── Hand landmark state (original) ────────────────────────────────
    private var results: GestureRecognizerResult? = null
    private var linePaint  = Paint()
    private var pointPaint = Paint()
    private var scaleFactor: Float = 1f
    private var imageWidth:  Int   = 1
    private var imageHeight: Int   = 1

    // ── Face recognition state (NEW) ─────────────────────────────────
    /** List of (bounding-box-in-image-coords, label) pairs */
    private var faceResults: List<Pair<Rect, String>> = emptyList()
    private var faceImageWidth:  Int = 1
    private var faceImageHeight: Int = 1

    private val facePaint = Paint().apply {
        color     = Color.CYAN
        style     = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val faceLabelBgPaint = Paint().apply {
        color   = Color.parseColor("#AA000000")   // semi-transparent black
        style   = Paint.Style.FILL
    }
    private val faceLabelPaint = Paint().apply {
        color     = Color.WHITE
        textSize  = 40f
        isAntiAlias = true
        isFakeBoldText = true
    }
    private val unknownLabelPaint = Paint().apply {
        color     = Color.parseColor("#FF5252")
        textSize  = 40f
        isAntiAlias = true
    }

    // ──────────────────────────────────────────────────────────────────

    init { initPaints() }

    fun clear() {
        results = null
        faceResults = emptyList()
        linePaint.reset()
        pointPaint.reset()
        invalidate()
        initPaints()
    }

    fun clearFaceLabels() {
        faceResults = emptyList()
        invalidate()
    }

    private fun initPaints() {
        linePaint.color       = ContextCompat.getColor(context!!, R.color.mp_color_primary)
        linePaint.strokeWidth = LANDMARK_STROKE_WIDTH
        linePaint.style       = Paint.Style.STROKE

        pointPaint.color       = Color.YELLOW
        pointPaint.strokeWidth = LANDMARK_STROKE_WIDTH
        pointPaint.style       = Paint.Style.FILL
    }

    // ── Face recognition API (NEW) ────────────────────────────────────

    /**
     * Called from CameraFragment (main thread) with detected faces.
     * @param faces  List of (boundingBox in camera-image coords, label)
     * @param imgH   Height of the camera image that produced these results
     * @param imgW   Width  of the camera image that produced these results
     */
    fun setFaceResults(faces: List<Pair<Rect, String>>, imgH: Int, imgW: Int) {
        faceResults      = faces
        faceImageHeight  = imgH
        faceImageWidth   = imgW
        invalidate()
    }

    // ── draw ─────────────────────────────────────────────────────────

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        drawHandLandmarks(canvas)
        drawFaceResults(canvas)
    }

    private fun drawHandLandmarks(canvas: Canvas) {
        results?.let { result ->
            for (landmark in result.landmarks()) {
                for (normalizedLandmark in landmark) {
                    canvas.drawPoint(
                        normalizedLandmark.x() * imageWidth  * scaleFactor,
                        normalizedLandmark.y() * imageHeight * scaleFactor,
                        pointPaint
                    )
                }
                HandLandmarker.HAND_CONNECTIONS.forEach {
                    canvas.drawLine(
                        result.landmarks()[0][it!!.start()].x() * imageWidth  * scaleFactor,
                        result.landmarks()[0][it.start()].y()   * imageHeight * scaleFactor,
                        result.landmarks()[0][it.end()].x()     * imageWidth  * scaleFactor,
                        result.landmarks()[0][it.end()].y()     * imageHeight * scaleFactor,
                        linePaint
                    )
                }
            }
        }
    }

    private fun drawFaceResults(canvas: Canvas) {
        if (faceResults.isEmpty()) return

        // Use SAME scaling logic as PreviewView (fillStart)
        val scaleX = width.toFloat() / faceImageWidth.toFloat()
        val scaleY = height.toFloat() / faceImageHeight.toFloat()
        val scale = max(scaleX, scaleY)

        // Center offset (VERY IMPORTANT FIX)
        val offsetX = (width - faceImageWidth * scale) / 2f
        val offsetY = (height - faceImageHeight * scale) / 2f

        for ((box, label) in faceResults) {

            val left = box.left * scale + offsetX
            val top = box.top * scale + offsetY
            val right = box.right * scale + offsetX
            val bottom = box.bottom * scale + offsetY

            val rectF = RectF(left, top, right, bottom)

            // 🎯 Draw face box
            canvas.drawRoundRect(rectF, 12f, 12f, facePaint)

            // 📝 Choose text style
            val textPaint = if (label == "Unknown") unknownLabelPaint else faceLabelPaint

            val padding = 12f
            val textWidth = textPaint.measureText(label)
            val textHeight = textPaint.textSize

            // 📦 Background above face
            var labelTop = rectF.top - textHeight - padding

            // Prevent going outside screen
            if (labelTop < 0) {
                labelTop = rectF.top + padding
            }

            val labelLeft = rectF.left

            canvas.drawRoundRect(
                labelLeft,
                labelTop,
                labelLeft + textWidth + padding * 2,
                labelTop + textHeight + padding,
                8f,
                8f,
                faceLabelBgPaint
            )

            // 🏷️ Draw name
            canvas.drawText(
                label,
                labelLeft + padding,
                labelTop + textHeight,
                textPaint
            )
        }
    }

    // ── Hand landmarks API (unchanged) ────────────────────────────────

    fun setResults(
        gestureRecognizerResult: GestureRecognizerResult,
        imageHeight: Int,
        imageWidth: Int,
        runningMode: RunningMode = RunningMode.IMAGE
    ) {
        results          = gestureRecognizerResult
        this.imageHeight = imageHeight
        this.imageWidth  = imageWidth
        scaleFactor = when (runningMode) {
            RunningMode.IMAGE,
            RunningMode.VIDEO      -> min(width * 1f / imageWidth, height * 1f / imageHeight)
            RunningMode.LIVE_STREAM -> max(width * 1f / imageWidth, height * 1f / imageHeight)
        }
        invalidate()
    }

    companion object {
        private const val LANDMARK_STROKE_WIDTH = 8F
    }
}
