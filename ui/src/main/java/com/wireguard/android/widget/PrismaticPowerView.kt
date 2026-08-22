/*
 * Copyright © 2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.wireguard.android.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A faceted portal control with three visual states: idle, connecting and active.
 * Connecting hides the keyhole and animates both the facets and a light trace
 * around the perimeter. A second tap still cancels the connection attempt.
 */
class PrismaticPowerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val crystalPath = Path()
    private val facetPath = Path()
    private val brightEdgePath = Path()
    private val darkEdgePath = Path()
    private val traceSegmentPath = Path()
    private val pathMeasure = PathMeasure()
    private val highlightMatrix = Matrix()
    private val points = FloatArray(12)
    private val accent = ContextCompat.getColor(context, R.color.rabbit_accent)
    private val accentSoft = ContextCompat.getColor(context, R.color.rabbit_accent_soft)
    private val activeColor = ContextCompat.getColor(context, R.color.rabbit_power_active)
    private val successColor = ContextCompat.getColor(context, R.color.rabbit_success)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val facetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val brightEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.2f * density
    }
    private val darkEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 3.1f * density
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.4f * density
    }
    private val innerOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 0.7f * density
    }
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.25f * density
    }
    private val traceGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 5.4f * density
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.1f * density
    }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val idleFacetShaders = arrayOfNulls<Shader>(6)
    private val brightFacetShaders = arrayOfNulls<Shader>(6)
    private var idleFillShader: Shader? = null
    private var activeFillShader: Shader? = null
    private var bevelShader: Shader? = null
    private var activeBevelShader: Shader? = null
    private var lensShader: Shader? = null
    private var highlightShader: Shader? = null

    private var phase = 0f
    private var requestedAnimating = false
    private val rotationAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 24_000L
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            phase = it.animatedValue as Float
            postInvalidateOnAnimation()
        }
    }
    init {
        isClickable = true
        isFocusable = true
        minimumWidth = (72f * density).toInt()
        minimumHeight = (72f * density).toInt()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setAnimating(animating: Boolean) {
        if (requestedAnimating == animating) return
        requestedAnimating = animating
        updateAnimator()
        invalidate()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = min(w, h).toFloat()
        if (size <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val radius = size * 0.39f
        idleFillShader = RadialGradient(
            cx - radius * 0.18f,
            cy - radius * 0.24f,
            radius * 1.35f,
            Color.rgb(92, 38, 131),
            Color.rgb(18, 9, 31),
            Shader.TileMode.CLAMP,
        )
        activeFillShader = RadialGradient(
            cx - radius * 0.18f,
            cy - radius * 0.24f,
            radius * 1.35f,
            Color.rgb(162, 62, 244),
            Color.rgb(49, 10, 83),
            Shader.TileMode.CLAMP,
        )
        bevelShader = LinearGradient(
            cx - radius,
            cy - radius,
            cx + radius,
            cy + radius,
            intArrayOf(0xFFF0D7FF.toInt(), 0xFF9C48DE.toInt(), 0xFF571A7C.toInt(), 0xFF210A30.toInt()),
            floatArrayOf(0f, 0.34f, 0.68f, 1f),
            Shader.TileMode.CLAMP,
        )
        activeBevelShader = LinearGradient(
            cx - radius,
            cy - radius,
            cx + radius,
            cy + radius,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFD898FF.toInt(), 0xFF9A32E8.toInt(), 0xFF3A0A59.toInt()),
            floatArrayOf(0f, 0.32f, 0.67f, 1f),
            Shader.TileMode.CLAMP,
        )
        lensShader = RadialGradient(
            cx - radius * 0.36f,
            cy - radius * 0.42f,
            radius * 1.2f,
            intArrayOf(0xB8FFFFFF.toInt(), 0x3EE7B9FF, 0x00501875),
            floatArrayOf(0f, 0.34f, 1f),
            Shader.TileMode.CLAMP,
        )
        highlightShader = LinearGradient(
            cx - radius * 2.2f,
            cy - radius * 2.2f,
            cx + radius * 2.2f,
            cy + radius * 2.2f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, 0xB8FFFFFF.toInt(), 0x50E7C7FF, Color.TRANSPARENT, Color.TRANSPARENT),
            floatArrayOf(0f, 0.43f, 0.49f, 0.53f, 0.59f, 1f),
            Shader.TileMode.CLAMP,
        )
        for (index in 0 until 6) {
            val angle = Math.toRadians((-90f + index * 60f).toDouble())
            val x = cx + cos(angle).toFloat() * radius
            val y = cy + sin(angle).toFloat() * radius
            idleFacetShaders[index] = LinearGradient(cx, cy, x, y, accent, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            brightFacetShaders[index] = LinearGradient(cx, cy, x, y, activeColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val pressedScale = if (isPressed) 0.965f else 1f
        val pulseSpeed = when {
            requestedAnimating -> 8f
            isActivated -> 4f
            else -> 6f
        }
        val pulsePhase = phase * TWO_PI * pulseSpeed
        val pulse = (0.5f + 0.34f * sin(pulsePhase) + 0.16f * sin(pulsePhase * 2f + 1.1f)).coerceIn(0f, 1f)
        val radius = size * when {
            requestedAnimating -> 0.386f + pulse * 0.008f
            isActivated -> 0.389f + pulse * 0.005f
            else -> 0.378f + pulse * 0.006f
        }

        canvas.save()
        canvas.scale(pressedScale, pressedScale, cx, cy)
        buildHexagon(cx, cy, radius, -90f)

        if (isEnabled && !requestedAnimating && !isActivated) drawIdlePulse(canvas, cx, cy)
        if (isActivated && !requestedAnimating) drawActiveAura(canvas, cx, cy, pulse)

        bevelPaint.shader = if (isActivated) activeBevelShader else bevelShader
        bevelPaint.alpha = if (requestedAnimating) 242 else 255
        canvas.drawPath(crystalPath, bevelPaint)

        val perspective = sin(phase * TWO_PI * 2f)
        val faceScale = 0.862f + perspective * 0.004f
        canvas.save()
        canvas.scale(faceScale, faceScale - perspective * 0.002f, cx, cy)
        fillPaint.shader = if (isActivated) activeFillShader else idleFillShader
        fillPaint.alpha = when {
            isActivated -> 255
            requestedAnimating -> 232
            else -> 218
        }
        canvas.drawPath(crystalPath, fillPaint)

        drawFacets(canvas, cx, cy)

        highlightPaint.shader = highlightShader
        highlightPaint.alpha = when {
            requestedAnimating -> (42f + pulse * 48f).toInt()
            isActivated -> (35f + pulse * 38f).toInt()
            else -> (22f + pulse * 24f).toInt()
        }
        val highlightTravel = ((phase * 5f) % 1f) * radius * 4.4f - radius * 2.2f
        highlightMatrix.reset()
        highlightMatrix.setTranslate(highlightTravel, highlightTravel * 0.72f)
        highlightShader?.setLocalMatrix(highlightMatrix)
        canvas.drawPath(crystalPath, highlightPaint)

        lensPaint.shader = lensShader
        lensPaint.alpha = when {
            requestedAnimating -> (32f + pulse * 44f).toInt()
            isActivated -> (58f + pulse * 42f).toInt()
            else -> (34f + pulse * 24f).toInt()
        }
        canvas.drawPath(crystalPath, lensPaint)

        innerOutlinePaint.color = activeColor
        innerOutlinePaint.alpha = if (isActivated) 168 else 92
        canvas.drawPath(crystalPath, innerOutlinePaint)
        canvas.restore()

        outlinePaint.color = if (isActivated) Color.WHITE else accentSoft
        outlinePaint.alpha = if (isActivated) 220 else 155
        outlinePaint.strokeWidth = 1.1f * density
        canvas.drawPath(crystalPath, outlinePaint)

        brightEdgePaint.color = Color.WHITE
        brightEdgePaint.alpha = if (isActivated) 235 else 178
        canvas.drawPath(brightEdgePath, brightEdgePaint)
        darkEdgePaint.color = if (isActivated) 0xFF50106F.toInt() else 0xFF170621.toInt()
        darkEdgePaint.alpha = if (requestedAnimating) 195 else 220
        canvas.drawPath(darkEdgePath, darkEdgePaint)
        outlinePaint.strokeWidth = 1.4f * density

        if (requestedAnimating || isActivated) {
            drawPerimeterTrace(canvas, pulse)
        }

        drawCenterGlyph(canvas, cx, cy, radius)
        if (isActivated && !requestedAnimating) drawSuccessSpark(canvas, cx, cy, radius)
        canvas.restore()
    }

    private fun buildHexagon(cx: Float, cy: Float, radius: Float, rotation: Float) {
        crystalPath.reset()
        for (index in 0 until 6) {
            val angle = Math.toRadians((rotation + index * 60f).toDouble())
            val x = cx + cos(angle).toFloat() * radius
            val y = cy + sin(angle).toFloat() * radius
            points[index * 2] = x
            points[index * 2 + 1] = y
            if (index == 0) crystalPath.moveTo(x, y) else crystalPath.lineTo(x, y)
        }
        crystalPath.close()

        brightEdgePath.reset()
        brightEdgePath.moveTo(points[8], points[9])
        brightEdgePath.lineTo(points[10], points[11])
        brightEdgePath.lineTo(points[0], points[1])
        brightEdgePath.lineTo(points[2], points[3])

        darkEdgePath.reset()
        darkEdgePath.moveTo(points[2], points[3])
        darkEdgePath.lineTo(points[4], points[5])
        darkEdgePath.lineTo(points[6], points[7])
        darkEdgePath.lineTo(points[8], points[9])
    }

    private fun drawFacets(canvas: Canvas, cx: Float, cy: Float) {
        for (index in 0 until 6) {
            val next = (index + 1) % 6
            facetPath.reset()
            facetPath.moveTo(cx, cy)
            facetPath.lineTo(points[index * 2], points[index * 2 + 1])
            facetPath.lineTo(points[next * 2], points[next * 2 + 1])
            facetPath.close()
            val speed = when {
                requestedAnimating -> 9f
                isActivated -> 4f
                else -> 2f
            }
            val wave = 0.5f + 0.5f * sin(phase * TWO_PI * speed - index * TWO_PI / 6f)
            facetPaint.shader = if (requestedAnimating || isActivated) brightFacetShaders[index] else idleFacetShaders[index]
            facetPaint.alpha = when {
                isActivated -> (30f + wave * 91f).toInt()
                requestedAnimating -> (24f + wave * 82f).toInt()
                else -> (18f + wave * 42f).toInt()
            }
            canvas.drawPath(facetPath, facetPaint)
        }
    }

    private fun drawIdlePulse(canvas: Canvas, cx: Float, cy: Float) {
        for (index in IDLE_PULSE_OFFSETS.indices) {
            val wave = 0.5f + 0.5f * sin(phase * TWO_PI * 6f + IDLE_PULSE_OFFSETS[index])
            val scale = 1.045f + wave * (0.055f + index * 0.018f)
            outlinePaint.color = accentSoft
            outlinePaint.alpha = (12f + wave * (30f - index * 6f)).toInt()
            outlinePaint.strokeWidth = (0.8f + wave * 0.55f) * density
            canvas.save()
            canvas.scale(scale, scale, cx, cy)
            canvas.drawPath(crystalPath, outlinePaint)
            canvas.restore()
        }
        outlinePaint.strokeWidth = 1.4f * density
    }

    private fun drawActiveAura(canvas: Canvas, cx: Float, cy: Float, pulse: Float) {
        outlinePaint.color = activeColor
        outlinePaint.alpha = (34 + pulse * 42f).toInt()
        outlinePaint.strokeWidth = (3.2f + pulse * 1.8f) * density
        canvas.save()
        canvas.scale(1.055f + pulse * 0.018f, 1.055f + pulse * 0.018f, cx, cy)
        canvas.drawPath(crystalPath, outlinePaint)
        canvas.restore()
        outlinePaint.strokeWidth = 1.4f * density
    }

    private fun drawPerimeterTrace(canvas: Canvas, pulse: Float) {
        pathMeasure.setPath(crystalPath, true)
        val perimeter = pathMeasure.length
        if (perimeter <= 0f) return
        val rotations = if (requestedAnimating) 18f else 5f
        val start = (phase * rotations % 1f) * perimeter
        val traceLength = perimeter * if (requestedAnimating) 0.24f else 0.12f
        traceSegmentPath.reset()
        val end = start + traceLength
        if (end <= perimeter) {
            pathMeasure.getSegment(start, end, traceSegmentPath, true)
        } else {
            pathMeasure.getSegment(start, perimeter, traceSegmentPath, true)
            pathMeasure.getSegment(0f, end - perimeter, traceSegmentPath, true)
        }

        traceGlowPaint.color = activeColor
        traceGlowPaint.alpha = if (requestedAnimating) (42f + pulse * 34f).toInt() else (24f + pulse * 18f).toInt()
        canvas.drawPath(traceSegmentPath, traceGlowPaint)
        tracePaint.color = Color.WHITE
        tracePaint.alpha = if (requestedAnimating) (190f + pulse * 58f).toInt() else (126f + pulse * 54f).toInt()
        canvas.drawPath(traceSegmentPath, tracePaint)
    }

    private fun drawCenterGlyph(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        glyphPaint.color = Color.WHITE
        glyphPaint.alpha = if (isEnabled) 235 else 105
        val glyphRadius = radius * 0.115f
        if (requestedAnimating) {
            return
        }

        canvas.drawCircle(cx, cy - glyphRadius * 0.35f, glyphRadius, glyphPaint)
        canvas.drawLine(cx, cy + glyphRadius * 0.65f, cx, cy + glyphRadius * 2.05f, glyphPaint)
    }

    private fun drawSuccessSpark(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val x = cx + radius * 0.78f
        val y = cy - radius * 0.62f
        sparkPaint.color = successColor
        sparkPaint.alpha = 255
        canvas.drawCircle(x, y, 3.2f * density, sparkPaint)
        sparkPaint.color = Color.WHITE
        sparkPaint.alpha = 165
        canvas.drawCircle(x - density, y - density, density, sparkPaint)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.Button::class.java.name
        info.isCheckable = true
        info.isChecked = isActivated
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimator()
    }

    override fun onDetachedFromWindow() {
        rotationAnimator.cancel()
        super.onDetachedFromWindow()
    }

    private fun updateAnimator() {
        if (isAttachedToWindow) {
            if (!rotationAnimator.isStarted) rotationAnimator.start()
        } else {
            rotationAnimator.cancel()
            phase = 0f
        }
    }

    private companion object {
        const val TWO_PI = (PI * 2.0).toFloat()
        val IDLE_PULSE_OFFSETS = floatArrayOf(0f, PI.toFloat())
    }
}
