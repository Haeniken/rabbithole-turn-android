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
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.wireguard.android.R
import com.wireguard.android.util.MotionPolicyObserver
import kotlin.math.PI
import kotlin.math.abs
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
    private val openLockBody = RectF()
    private val openLockShackle = RectF()
    private val pathMeasure = PathMeasure()
    private val highlightMatrix = Matrix()
    private val points = FloatArray(12)
    private val accent = ContextCompat.getColor(context, R.color.rabbit_accent)
    private val accentSoft = ContextCompat.getColor(context, R.color.rabbit_accent_soft)
    private val connectingColor = ContextCompat.getColor(context, R.color.rabbit_power_connecting)
    private val activeColor = ContextCompat.getColor(context, R.color.rabbit_power_active)

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
    private val idleFacetShaders = arrayOfNulls<Shader>(6)
    private val connectingFacetShaders = arrayOfNulls<Shader>(6)
    private val activeFacetShaders = arrayOfNulls<Shader>(6)
    private var idleFillShader: Shader? = null
    private var connectingFillShader: Shader? = null
    private var activeFillShader: Shader? = null
    private var bevelShader: Shader? = null
    private var connectingBevelShader: Shader? = null
    private var activeBevelShader: Shader? = null
    private var lensShader: Shader? = null
    private var activeLensShader: Shader? = null
    private var highlightShader: Shader? = null
    private var activeHighlightShader: Shader? = null

    private var phase = 0f
    private var requestedAnimating = false
    private var activationProgress = 0f
    private var stateAnimationReady = false
    private var motionAllowed = MotionPolicyObserver.allowsDecorativeMotion(context)
    private val motionPolicyObserver = MotionPolicyObserver(context) { allowed ->
        motionAllowed = allowed
        if (!allowed) {
            activationAnimator.cancel()
            activationProgress = if (isActivated) 1f else 0f
        }
        updateAnimator()
        invalidate()
    }
    private val rotationAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 24_000L
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            phase = it.animatedValue as Float
            postInvalidateOnAnimation()
        }
    }
    private val activationAnimator = ValueAnimator().apply {
        duration = 420L
        addUpdateListener {
            activationProgress = it.animatedValue as Float
            postInvalidateOnAnimation()
        }
    }
    init {
        isClickable = true
        isFocusable = true
        minimumWidth = (72f * density).toInt()
        minimumHeight = (72f * density).toInt()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        activationProgress = if (isActivated) 1f else 0f
        stateAnimationReady = true
    }

    fun setAnimating(animating: Boolean) {
        if (requestedAnimating == animating) return
        val wasAnimating = requestedAnimating
        requestedAnimating = animating
        if (animating) activationAnimator.cancel()
        if (wasAnimating && !animating) animateActivationTo(if (isActivated) 1f else 0f)
        updateAnimator()
        updateAccessibilityState()
        invalidate()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (!stateAnimationReady) return
        if (!requestedAnimating) animateActivationTo(if (isActivated) 1f else 0f) else invalidate()
        updateAccessibilityState()
    }

    private fun animateActivationTo(target: Float) {
        if (!isAttachedToWindow || !motionAllowed) {
            activationAnimator.cancel()
            activationProgress = target
            invalidate()
            return
        }
        if (abs(activationProgress - target) < 0.001f) return
        activationAnimator.cancel()
        activationAnimator.setFloatValues(activationProgress, target)
        activationAnimator.start()
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
            Color.rgb(55, 24, 76),
            Color.rgb(8, 4, 14),
            Shader.TileMode.CLAMP,
        )
        connectingFillShader = RadialGradient(
            cx - radius * 0.18f,
            cy - radius * 0.24f,
            radius * 1.35f,
            Color.rgb(162, 62, 244),
            Color.rgb(49, 10, 83),
            Shader.TileMode.CLAMP,
        )
        activeFillShader = RadialGradient(
            cx - radius * 0.18f,
            cy - radius * 0.24f,
            radius * 1.35f,
            Color.rgb(255, 193, 7),
            Color.rgb(123, 63, 0),
            Shader.TileMode.CLAMP,
        )
        bevelShader = LinearGradient(
            cx - radius,
            cy - radius,
            cx + radius,
            cy + radius,
            intArrayOf(0xFF9C79AE.toInt(), 0xFF633184.toInt(), 0xFF351246.toInt(), 0xFF0B0410.toInt()),
            floatArrayOf(0f, 0.34f, 0.68f, 1f),
            Shader.TileMode.CLAMP,
        )
        connectingBevelShader = LinearGradient(
            cx - radius,
            cy - radius,
            cx + radius,
            cy + radius,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFD898FF.toInt(), 0xFF9A32E8.toInt(), 0xFF3A0A59.toInt()),
            floatArrayOf(0f, 0.32f, 0.67f, 1f),
            Shader.TileMode.CLAMP,
        )
        activeBevelShader = LinearGradient(
            cx - radius,
            cy - radius,
            cx + radius,
            cy + radius,
            intArrayOf(0xFFFFF4C2.toInt(), 0xFFFFD45A.toInt(), 0xFFFFB300.toInt(), 0xFF5A2A00.toInt()),
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
        activeLensShader = RadialGradient(
            cx - radius * 0.36f,
            cy - radius * 0.42f,
            radius * 1.2f,
            intArrayOf(0xCFFFFFFF.toInt(), 0x55FFF1A8, 0x00A85B00),
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
        activeHighlightShader = LinearGradient(
            cx - radius * 2.2f,
            cy - radius * 2.2f,
            cx + radius * 2.2f,
            cy + radius * 2.2f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, 0xD8FFFFFF.toInt(), 0x78FFF0A5, Color.TRANSPARENT, Color.TRANSPARENT),
            floatArrayOf(0f, 0.43f, 0.49f, 0.53f, 0.59f, 1f),
            Shader.TileMode.CLAMP,
        )
        for (index in 0 until 6) {
            val angle = Math.toRadians((-90f + index * 60f).toDouble())
            val x = cx + cos(angle).toFloat() * radius
            val y = cy + sin(angle).toFloat() * radius
            idleFacetShaders[index] = LinearGradient(cx, cy, x, y, accent, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            connectingFacetShaders[index] = LinearGradient(cx, cy, x, y, connectingColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            activeFacetShaders[index] = LinearGradient(cx, cy, x, y, activeColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val pressedScale = if (isPressed) 0.965f else 1f
        val active = activationProgress.coerceIn(0f, 1f)
        val pulseSpeed = if (requestedAnimating) 8f else 6f - active * 2f
        val pulsePhase = phase * TWO_PI * pulseSpeed
        val pulse = (0.5f + 0.34f * sin(pulsePhase) + 0.16f * sin(pulsePhase * 2f + 1.1f)).coerceIn(0f, 1f)
        val idleRadius = 0.378f + pulse * 0.006f
        val activeRadius = 0.389f + pulse * 0.005f
        val radius = size * if (requestedAnimating) 0.386f + pulse * 0.008f else idleRadius + (activeRadius - idleRadius) * active

        canvas.save()
        canvas.scale(pressedScale, pressedScale, cx, cy)
        buildHexagon(cx, cy, radius, -90f)

        if (isEnabled && !requestedAnimating && active < 1f) drawIdlePulse(canvas, cx, cy, 1f - active)
        if (!requestedAnimating && active > 0f) drawActiveAura(canvas, cx, cy, pulse, active)

        if (requestedAnimating) {
            bevelPaint.shader = connectingBevelShader
            bevelPaint.alpha = 242
            canvas.drawPath(crystalPath, bevelPaint)
        } else {
            bevelPaint.shader = bevelShader
            bevelPaint.alpha = 255
            canvas.drawPath(crystalPath, bevelPaint)
            if (active > 0f) {
                bevelPaint.shader = activeBevelShader
                bevelPaint.alpha = (255f * active).toInt()
                canvas.drawPath(crystalPath, bevelPaint)
            }
        }

        val perspective = sin(phase * TWO_PI * 2f)
        val faceScale = 0.862f + perspective * 0.004f
        canvas.save()
        canvas.scale(faceScale, faceScale - perspective * 0.002f, cx, cy)
        if (requestedAnimating) {
            fillPaint.shader = connectingFillShader
            fillPaint.alpha = 232
            canvas.drawPath(crystalPath, fillPaint)
        } else {
            fillPaint.shader = idleFillShader
            fillPaint.alpha = 198
            canvas.drawPath(crystalPath, fillPaint)
            if (active > 0f) {
                fillPaint.shader = activeFillShader
                fillPaint.alpha = (255f * active).toInt()
                canvas.drawPath(crystalPath, fillPaint)
            }
        }

        drawFacets(canvas, cx, cy, active)

        val highlightTravel = ((phase * 5f) % 1f) * radius * 4.4f - radius * 2.2f
        highlightMatrix.reset()
        highlightMatrix.setTranslate(highlightTravel, highlightTravel * 0.72f)
        if (requestedAnimating) {
            drawHighlight(canvas, highlightShader, (42f + pulse * 48f).toInt())
            lensPaint.shader = lensShader
            lensPaint.alpha = (32f + pulse * 44f).toInt()
            canvas.drawPath(crystalPath, lensPaint)
        } else {
            drawHighlight(canvas, highlightShader, ((12f + pulse * 18f) * (1f - active)).toInt())
            drawHighlight(canvas, activeHighlightShader, ((35f + pulse * 38f) * active).toInt())
            lensPaint.shader = lensShader
            lensPaint.alpha = ((18f + pulse * 18f) * (1f - active)).toInt()
            canvas.drawPath(crystalPath, lensPaint)
            lensPaint.shader = activeLensShader
            lensPaint.alpha = ((58f + pulse * 42f) * active).toInt()
            canvas.drawPath(crystalPath, lensPaint)
        }

        if (requestedAnimating) {
            innerOutlinePaint.color = connectingColor
            innerOutlinePaint.alpha = 142
        } else {
            innerOutlinePaint.color = ColorUtils.blendARGB(accent, activeColor, active)
            innerOutlinePaint.alpha = (62f + 106f * active).toInt()
        }
        canvas.drawPath(crystalPath, innerOutlinePaint)
        canvas.restore()

        if (requestedAnimating) {
            outlinePaint.color = accentSoft
            outlinePaint.alpha = 205
        } else {
            outlinePaint.color = ColorUtils.blendARGB(accentSoft, 0xFFFFF4BC.toInt(), active)
            outlinePaint.alpha = (105f + 115f * active).toInt()
        }
        outlinePaint.strokeWidth = 1.1f * density
        canvas.drawPath(crystalPath, outlinePaint)

        if (requestedAnimating) {
            brightEdgePaint.color = Color.WHITE
            brightEdgePaint.alpha = 220
        } else {
            brightEdgePaint.color = ColorUtils.blendARGB(Color.WHITE, 0xFFFFF6C7.toInt(), active)
            brightEdgePaint.alpha = (112f + 123f * active).toInt()
        }
        canvas.drawPath(brightEdgePath, brightEdgePaint)
        darkEdgePaint.color = if (requestedAnimating) {
            0xFF50106F.toInt()
        } else {
            ColorUtils.blendARGB(0xFF170621.toInt(), 0xFF653300.toInt(), active)
        }
        darkEdgePaint.alpha = if (requestedAnimating) 195 else 220
        canvas.drawPath(darkEdgePath, darkEdgePaint)
        outlinePaint.strokeWidth = 1.4f * density

        if (requestedAnimating || active > 0f) {
            drawPerimeterTrace(canvas, pulse, if (requestedAnimating) 1f else active)
        }

        drawCenterGlyph(canvas, cx, cy, radius, active)
        canvas.restore()
    }

    private fun drawHighlight(canvas: Canvas, shader: Shader?, alpha: Int) {
        if (alpha <= 0) return
        shader?.setLocalMatrix(highlightMatrix)
        highlightPaint.shader = shader
        highlightPaint.alpha = alpha
        canvas.drawPath(crystalPath, highlightPaint)
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

    private fun drawFacets(canvas: Canvas, cx: Float, cy: Float, active: Float) {
        for (index in 0 until 6) {
            val next = (index + 1) % 6
            facetPath.reset()
            facetPath.moveTo(cx, cy)
            facetPath.lineTo(points[index * 2], points[index * 2 + 1])
            facetPath.lineTo(points[next * 2], points[next * 2 + 1])
            facetPath.close()
            val speed = when {
                requestedAnimating -> 9f
                else -> 2f + active * 2f
            }
            val wave = 0.5f + 0.5f * sin(phase * TWO_PI * speed - index * TWO_PI / 6f)
            if (requestedAnimating) {
                facetPaint.shader = connectingFacetShaders[index]
                facetPaint.alpha = (24f + wave * 82f).toInt()
                canvas.drawPath(facetPath, facetPaint)
            } else {
                facetPaint.shader = idleFacetShaders[index]
                facetPaint.alpha = ((18f + wave * 42f) * (1f - active)).toInt()
                canvas.drawPath(facetPath, facetPaint)
                facetPaint.shader = activeFacetShaders[index]
                facetPaint.alpha = ((30f + wave * 91f) * active).toInt()
                canvas.drawPath(facetPath, facetPaint)
            }
        }
    }

    private fun drawIdlePulse(canvas: Canvas, cx: Float, cy: Float, strength: Float) {
        for (index in IDLE_PULSE_OFFSETS.indices) {
            val wave = 0.5f + 0.5f * sin(phase * TWO_PI * 6f + IDLE_PULSE_OFFSETS[index])
            val scale = 1.045f + wave * (0.055f + index * 0.018f)
            outlinePaint.color = accentSoft
            outlinePaint.alpha = ((12f + wave * (30f - index * 6f)) * strength).toInt()
            outlinePaint.strokeWidth = (0.8f + wave * 0.55f) * density
            canvas.save()
            canvas.scale(scale, scale, cx, cy)
            canvas.drawPath(crystalPath, outlinePaint)
            canvas.restore()
        }
        outlinePaint.strokeWidth = 1.4f * density
    }

    private fun drawActiveAura(canvas: Canvas, cx: Float, cy: Float, pulse: Float, strength: Float) {
        drawActiveAuraLayer(canvas, cx, cy, 1.105f + pulse * 0.020f, 10.5f + pulse * 2.2f, ((10f + pulse * 8f) * strength).toInt())
        drawActiveAuraLayer(canvas, cx, cy, 1.078f + pulse * 0.016f, 6.4f + pulse * 1.7f, ((22f + pulse * 12f) * strength).toInt())
        drawActiveAuraLayer(canvas, cx, cy, 1.054f + pulse * 0.012f, 3.2f + pulse * 1.3f, ((44f + pulse * 20f) * strength).toInt())
        outlinePaint.strokeWidth = 1.4f * density
    }

    private fun drawActiveAuraLayer(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        scale: Float,
        strokeWidth: Float,
        alpha: Int,
    ) {
        outlinePaint.color = activeColor
        outlinePaint.alpha = alpha
        outlinePaint.strokeWidth = strokeWidth * density
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        canvas.drawPath(crystalPath, outlinePaint)
        canvas.restore()
    }

    private fun drawPerimeterTrace(canvas: Canvas, pulse: Float, strength: Float) {
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

        traceGlowPaint.color = if (requestedAnimating) connectingColor else activeColor
        traceGlowPaint.alpha = ((if (requestedAnimating) 42f + pulse * 34f else 24f + pulse * 18f) * strength).toInt()
        canvas.drawPath(traceSegmentPath, traceGlowPaint)
        tracePaint.color = Color.WHITE
        tracePaint.alpha = ((if (requestedAnimating) 190f + pulse * 58f else 126f + pulse * 54f) * strength).toInt()
        canvas.drawPath(traceSegmentPath, tracePaint)
    }

    private fun drawCenterGlyph(canvas: Canvas, cx: Float, cy: Float, radius: Float, active: Float) {
        val glyphRadius = radius * 0.115f
        if (requestedAnimating) return

        val availableAlpha = if (isEnabled) 235 else 105
        glyphPaint.color = Color.WHITE
        glyphPaint.alpha = (availableAlpha * (1f - active)).toInt()
        if (glyphPaint.alpha > 0) {
            canvas.drawCircle(cx, cy - glyphRadius * 0.35f, glyphRadius, glyphPaint)
            canvas.drawLine(cx, cy + glyphRadius * 0.65f, cx, cy + glyphRadius * 2.05f, glyphPaint)
        }

        glyphPaint.color = 0xFF1B0828.toInt()
        glyphPaint.alpha = (availableAlpha * active).toInt()
        if (glyphPaint.alpha <= 0) return
        glyphPaint.strokeWidth = 2.2f * density
        val bodyTop = cy + glyphRadius * 0.12f
        val bodyBottom = cy + glyphRadius * 1.58f
        val bodyHalfWidth = glyphRadius * 0.92f
        openLockBody.set(cx - bodyHalfWidth, bodyTop, cx + bodyHalfWidth, bodyBottom)
        canvas.drawRoundRect(openLockBody, glyphRadius * 0.24f, glyphRadius * 0.24f, glyphPaint)

        openLockShackle.set(
            cx - glyphRadius * 0.72f,
            cy - glyphRadius * 1.34f,
            cx + glyphRadius * 0.72f,
            cy + glyphRadius * 0.04f,
        )
        canvas.drawLine(cx - glyphRadius * 0.72f, bodyTop, cx - glyphRadius * 0.72f, cy - glyphRadius * 0.65f, glyphPaint)
        canvas.drawArc(openLockShackle, 180f, 155f, false, glyphPaint)
        glyphPaint.strokeWidth = 2.1f * density
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.Button::class.java.name
        info.isCheckable = true
        info.isChecked = isActivated
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            info.stateDescription = accessibilityStateDescription()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        motionPolicyObserver.start()
        updateAnimator()
    }

    override fun onDetachedFromWindow() {
        motionPolicyObserver.stop()
        rotationAnimator.cancel()
        activationAnimator.cancel()
        activationProgress = if (isActivated) 1f else 0f
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimator()
    }

    private fun updateAnimator() {
        if (isAttachedToWindow && windowVisibility == VISIBLE && motionAllowed) {
            if (!rotationAnimator.isStarted) rotationAnimator.start()
        } else {
            rotationAnimator.cancel()
            phase = if (requestedAnimating) 0.18f else 0f
        }
    }

    private fun updateAccessibilityState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            stateDescription = accessibilityStateDescription()
        if (isAttachedToWindow)
            sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    private fun accessibilityStateDescription(): CharSequence = context.getString(
        when {
            requestedAnimating -> R.string.main_power_state_connecting
            isActivated -> R.string.main_power_state_connected
            else -> R.string.main_power_state_disconnected
        },
    )

    private companion object {
        const val TWO_PI = (PI * 2.0).toFloat()
        val IDLE_PULSE_OFFSETS = floatArrayOf(0f, PI.toFloat())
    }
}
