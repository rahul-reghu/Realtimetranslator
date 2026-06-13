package com.realtimetranslator

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator

/**
 * Draws an animated glowing border that traces the card's rounded rect outline.
 * Idle = slow dim glow. Listening = bright colorful pulsing border.
 */
class AuraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 18f
    }

    private val cornerRadius = 48f   // match the CardView 18dp corner

    private val activeColors = intArrayOf(
        0xFF7B61FF.toInt(),
        0xFF00D4FF.toInt(),
        0xFF00FFC8.toInt(),
        0xFFFF6B9D.toInt(),
        0xFF7B61FF.toInt(),
    )

    private val idleColors = intArrayOf(
        0xFF3A3070.toInt(),
        0xFF1A3060.toInt(),
        0xFF3A3070.toInt(),
        0xFF1A3060.toInt(),
        0xFF3A3070.toInt(),
    )

    private var rotationAngle = 0f
    private var pulseAlpha = 180f
    private var isActive = false

    private val rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 3000
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotationAngle = it.animatedValue as Float
            invalidate()
        }
    }

    private val pulseAnimator = ValueAnimator.ofFloat(140f, 255f).apply {
        duration = 800
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            pulseAlpha = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        rotationAnimator.start()
    }

    fun setListening(listening: Boolean) {
        isActive = listening
        if (listening) {
            pulseAnimator.start()
        } else {
            pulseAnimator.cancel()
            pulseAlpha = 100f
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return

        val cx = width / 2f
        val cy = height / 2f

        val colors = if (isActive) activeColors else idleColors
        val alpha = if (isActive) pulseAlpha.toInt() else 70

        val shader = SweepGradient(cx, cy, colors, null)
        val matrix = Matrix()
        matrix.setRotate(rotationAngle, cx, cy)
        shader.setLocalMatrix(matrix)

        // Wide inner glow — strokes from the edge inward so it stays within the card
        val glowInset = 9f
        val glowRect = RectF(glowInset, glowInset, width - glowInset, height - glowInset)
        glowPaint.shader = shader
        glowPaint.alpha = (alpha * 0.6f).toInt()
        glowPaint.maskFilter = BlurMaskFilter(16f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawRoundRect(glowRect, cornerRadius, cornerRadius, glowPaint)

        // Crisp thin border right on the inner edge
        val strokeInset = 3f
        val strokeRect = RectF(strokeInset, strokeInset, width - strokeInset, height - strokeInset)
        strokePaint.shader = shader
        strokePaint.alpha = alpha
        strokePaint.maskFilter = BlurMaskFilter(2f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawRoundRect(strokeRect, cornerRadius, cornerRadius, strokePaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        rotationAnimator.cancel()
        pulseAnimator.cancel()
    }
}
