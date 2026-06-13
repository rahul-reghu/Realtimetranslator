package com.realtimetranslator

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator

class AuraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Colors that flow around the ring — purple, cyan, pink, green, back to purple
    private val activeColors = intArrayOf(
        0xFF7B61FF.toInt(),
        0xFF00D4FF.toInt(),
        0xFFFF6B9D.toInt(),
        0xFF00FFC8.toInt(),
        0xFF7B61FF.toInt(),
    )

    private val idleColors = intArrayOf(
        0xFF3A3560.toInt(),
        0xFF1A4060.toInt(),
        0xFF3A3560.toInt(),
        0xFF1A4060.toInt(),
        0xFF3A3560.toInt(),
    )

    private var rotationAngle = 0f
    private var pulseRadius = 1f
    private var glowAlpha = 120f   // 0–255
    private var isActive = false

    private val rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 3500
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotationAngle = it.animatedValue as Float
            invalidate()
        }
    }

    private val pulseAnimator = ValueAnimator.ofFloat(1f, 1.12f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { pulseRadius = it.animatedValue as Float }
    }

    private val alphaAnimator = ValueAnimator.ofFloat(100f, 200f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            glowAlpha = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        // Software layer required for BlurMaskFilter
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        rotationAnimator.start()
    }

    fun setListening(listening: Boolean) {
        isActive = listening
        if (listening) {
            pulseAnimator.start()
            alphaAnimator.start()
        } else {
            pulseAnimator.cancel()
            alphaAnimator.cancel()
            pulseRadius = 1f
            glowAlpha = 60f
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = minOf(cx, cy)
        val r = baseRadius * pulseRadius

        val colors = if (isActive) activeColors else idleColors
        val blurRadius = if (isActive) 28f else 16f
        val alpha = if (isActive) glowAlpha.toInt() else 55

        paint.maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
        paint.alpha = alpha

        canvas.save()
        canvas.rotate(rotationAngle, cx, cy)
        paint.shader = SweepGradient(cx, cy, colors, null)
        canvas.drawCircle(cx, cy, r, paint)
        canvas.restore()

        // Second counter-rotating layer for depth
        paint.alpha = (alpha * 0.5f).toInt()
        paint.maskFilter = BlurMaskFilter(blurRadius * 1.5f, BlurMaskFilter.Blur.NORMAL)
        canvas.save()
        canvas.rotate(-rotationAngle * 0.7f, cx, cy)
        paint.shader = SweepGradient(cx, cy, colors.reversedArray(), null)
        canvas.drawCircle(cx, cy, r * 0.85f, paint)
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        rotationAnimator.cancel()
        pulseAnimator.cancel()
        alphaAnimator.cancel()
    }
}
