package app.niix.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import app.niix.R

class CropImageView(context: Context) : View(context) {

    private var bitmap: Bitmap? = null
    private val matrix = Matrix()
    private var minScale = 1f
    private var scale = 1f

    private var frameLeft = 0f
    private var frameTop = 0f
    private var frameSize = 0f

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = 0xAA000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.niix_green)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                applyScale(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        },
    )
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                matrix.postTranslate(-dx, -dy)
                clampTranslation()
                invalidate()
                return true
            }
        },
    )

    fun setImageBitmap(bmp: Bitmap) {
        bitmap = bmp
        post { setupInitialTransform() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        frameSize = minOf(w, h) * 0.85f
        frameLeft = (w - frameSize) / 2f
        frameTop = (h - frameSize) / 2f
        bitmap?.let { setupInitialTransform() }
    }

    private fun setupInitialTransform() {
        val bmp = bitmap ?: return
        if (frameSize <= 0f) return

        minScale = maxOf(frameSize / bmp.width, frameSize / bmp.height)
        scale = minScale
        matrix.reset()
        matrix.postScale(scale, scale)
        val scaledW = bmp.width * scale
        val scaledH = bmp.height * scale
        val dx = frameLeft + (frameSize - scaledW) / 2f
        val dy = frameTop + (frameSize - scaledH) / 2f
        matrix.postTranslate(dx, dy)
        invalidate()
    }

    private fun applyScale(factor: Float, focusX: Float, focusY: Float) {
        if (bitmap == null) return
        val newScale = (scale * factor).coerceIn(minScale, minScale * 4f)
        val actualFactor = newScale / scale
        matrix.postScale(actualFactor, actualFactor, focusX, focusY)
        scale = newScale
        clampTranslation()
        invalidate()
    }

    private fun clampTranslation() {
        val bmp = bitmap ?: return
        val values = FloatArray(9)
        matrix.getValues(values)
        val scaledW = bmp.width * scale
        val scaledH = bmp.height * scale

        val minTx = frameLeft + frameSize - scaledW
        val maxTx = frameLeft
        val minTy = frameTop + frameSize - scaledH
        val maxTy = frameTop

        values[Matrix.MTRANS_X] = values[Matrix.MTRANS_X].coerceIn(minOf(minTx, maxTx), maxOf(minTx, maxTx))
        values[Matrix.MTRANS_Y] = values[Matrix.MTRANS_Y].coerceIn(minOf(minTy, maxTy), maxOf(minTy, maxTy))
        matrix.setValues(values)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, matrix, bitmapPaint)

        val w = width.toFloat()
        val h = height.toFloat()
        val frameRight = frameLeft + frameSize
        val frameBottom = frameTop + frameSize

        canvas.drawRect(0f, 0f, w, frameTop, dimPaint)
        canvas.drawRect(0f, frameBottom, w, h, dimPaint)
        canvas.drawRect(0f, frameTop, frameLeft, frameBottom, dimPaint)
        canvas.drawRect(frameRight, frameTop, w, frameBottom, dimPaint)
        canvas.drawRect(frameLeft, frameTop, frameRight, frameBottom, borderPaint)
    }

    fun cropToBitmap(outputSize: Int): Bitmap? {
        val bmp = bitmap ?: return null
        if (frameSize <= 0f) return null
        val output = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val exportMatrix = Matrix(matrix)
        exportMatrix.postTranslate(-frameLeft, -frameTop)
        val exportScale = outputSize / frameSize
        exportMatrix.postScale(exportScale, exportScale)
        canvas.drawBitmap(bmp, exportMatrix, bitmapPaint)
        return output
    }
}
