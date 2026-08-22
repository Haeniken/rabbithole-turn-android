/*
 * Copyright © 2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.wireguard.android.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * The animated cavern behind the main screen.
 *
 * The artwork moves with a deliberately slow, seamless parallax cycle. Lightning
 * geometry is regenerated only while fully transparent, then revealed with two
 * smooth pulses. This keeps every frame continuous while allowing strikes to
 * appear at genuinely different positions in the rift.
 */
class DeepPortalBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val background: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.rabbit_rift_background)
    private val activeBackground: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.rabbit_rift_active)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 248 }
    private val activeBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backgroundBounds = RectF()
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val illuminationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val depthIlluminationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lightningGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.rabbit_accent)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 4.8f * density
    }
    private val lightningHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.rabbit_accent_soft)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.55f * density
    }
    private val lightningCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 0.52f * density
    }
    private val lightningPath = Path()
    private val lightningBranches = Path()
    private val lightningFineBranches = Path()
    private val lightningPoints = FloatArray(MAX_LIGHTNING_POINTS * 2)
    private val random = Random(System.nanoTime())

    private var driftPhase = 0f
    private var flashPhase = 0f
    private var lightningCenterX = 0f
    private var lightningCenterY = 0f
    private var lightningRadius = 0f
    private var depthIlluminationRadius = 0f
    private var depthIlluminationStrength = 1f
    private var waitingForLightning = false
    private var connecting = false
    private var activeProgress = 0f
    private var activeTarget = false

    private val driftAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 24_000L
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            driftPhase = it.animatedValue as Float
            postInvalidateOnAnimation()
        }
    }
    private val flashAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 920L
        interpolator = LinearInterpolator()
        addUpdateListener {
            flashPhase = it.animatedValue as Float
            postInvalidateOnAnimation()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                flashPhase = 0f
                if (isAttachedToWindow) scheduleNextLightning()
            }
        })
    }
    private val activeAnimator = ValueAnimator().apply {
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            activeProgress = it.animatedValue as Float
            postInvalidateOnAnimation()
        }
    }
    private val lightningRunnable = Runnable {
        waitingForLightning = false
        if (!isAttachedToWindow || width <= 0 || height <= 0) return@Runnable
        generateLightning()
        flashAnimator.start()
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setConnecting(connecting: Boolean) {
        if (this.connecting == connecting) return
        this.connecting = connecting
        if (!isAttachedToWindow) return
        removeCallbacks(lightningRunnable)
        waitingForLightning = false
        if (!flashAnimator.isRunning) scheduleNextLightning(initial = true)
    }

    fun setPortalActive(active: Boolean) {
        if (activeTarget == active && activeAnimator.isRunning) return
        activeTarget = active
        val target = if (active) 1f else 0f
        if (!isAttachedToWindow) {
            activeAnimator.cancel()
            activeProgress = target
            invalidate()
            return
        }
        if (abs(activeProgress - target) < 0.001f) return
        activeAnimator.cancel()
        activeAnimator.setFloatValues(activeProgress, target)
        activeAnimator.duration = (1_900f * abs(activeProgress - target)).toLong().coerceAtLeast(420L)
        activeAnimator.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0 || background.width <= 0 || background.height <= 0) return
        val coverScale = max(w / background.width.toFloat(), h / background.height.toFloat()) * 1.035f
        val drawWidth = background.width * coverScale
        val drawHeight = background.height * coverScale
        backgroundBounds.set(
            (w - drawWidth) * 0.5f,
            (h - drawHeight) * 0.5f,
            (w + drawWidth) * 0.5f,
            (h + drawHeight) * 0.5f,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val intensity = lightningIntensity(flashPhase)
        val cycle = driftPhase * TWO_PI
        val breathe = sin(cycle)
        val sway = sin(cycle * 2f + 0.7f)
        val zoom = 1.006f + 0.005f * breathe + intensity * 0.004f
        canvas.save()
        canvas.translate(sway * 1.7f * density, breathe * 2.4f * density)
        canvas.scale(zoom, zoom, width * 0.5f, height * 0.67f)
        canvas.drawBitmap(background, null, backgroundBounds, backgroundPaint)
        if (activeProgress > 0f) {
            activeBackgroundPaint.alpha = (255f * activeProgress).toInt()
            canvas.drawBitmap(activeBackground, null, backgroundBounds, activeBackgroundPaint)
        }
        canvas.restore()

        drawMotes(canvas, cycle)
        if (intensity > 0f) drawLightning(canvas, intensity)
    }

    private fun drawMotes(canvas: Canvas, cycle: Float) {
        for (index in MOTE_X.indices) {
            val localCycle = cycle + MOTE_PHASE[index]
            val x = width * MOTE_X[index] + sin(localCycle) * 4f * density
            val y = height * MOTE_Y[index] + cos(localCycle * 0.7f) * 7f * density
            val shimmer = 0.5f + 0.5f * sin(localCycle * 1.8f)
            val radius = density * MOTE_SIZE[index]
            particlePaint.color = 0xFF9F57E9.toInt()
            particlePaint.alpha = (8f + shimmer * 15f).toInt()
            canvas.drawCircle(x, y, radius * 3.6f, particlePaint)
            particlePaint.color = 0xFFE3C4FF.toInt()
            particlePaint.alpha = (34f + shimmer * 48f).toInt()
            canvas.drawCircle(x, y, radius, particlePaint)
        }
    }

    private fun generateLightning() {
        lightningPath.reset()
        lightningBranches.reset()
        lightningFineBranches.reset()

        val yStart = height * random.nextFloat(0.42f, 0.88f)
        val visualDepth = (yStart / height).coerceIn(0.42f, 0.88f)
        val perspectiveScale = 1.18f - visualDepth * 0.62f
        val length = height * random.nextFloat(0.10f, 0.21f) * perspectiveScale
        val segmentCount = random.nextInt(20, 29)
        val normalizedY = yStart / height
        val halfRiftWidth = width * (0.29f - normalizedY * 0.09f)
        val startX = width * 0.5f + random.nextFloat(-halfRiftWidth, halfRiftWidth)
        val endY = (yStart + length).coerceAtMost(height * 0.94f)
        val endX = (
            startX + random.nextFloat(-0.07f, 0.07f) * width +
                (width * 0.5f - startX) * random.nextFloat(0.08f, 0.28f)
            ).coerceIn(width * 0.15f, width * 0.85f)
        val stepY = (endY - yStart) / segmentCount
        var carriedJitter = 0f

        var xSum = 0f
        var ySum = 0f
        for (index in 0..segmentCount) {
            val progress = index / segmentCount.toFloat()
            val envelope = sin(progress * PI.toFloat())
            carriedJitter = carriedJitter * 0.28f + random.nextFloat(-1f, 1f) * 0.72f
            val x = (
                startX + (endX - startX) * progress +
                    carriedJitter * width * (0.005f + 0.008f * envelope) * perspectiveScale
                ).coerceIn(width * 0.13f, width * 0.87f)
            val y = if (index == 0 || index == segmentCount) {
                yStart + (endY - yStart) * progress
            } else {
                yStart + (endY - yStart) * progress + random.nextFloat(-0.16f, 0.16f) * stepY
            }
            lightningPoints[index * 2] = x
            lightningPoints[index * 2 + 1] = y
            if (index == 0) lightningPath.moveTo(x, y) else lightningPath.lineTo(x, y)
            xSum += x
            ySum += y
        }

        val branchCount = random.nextInt(2, 6)
        for (branchIndex in 0 until branchCount) {
            val sourceIndex = random.nextInt(3, segmentCount - 2)
            val sourceX = lightningPoints[sourceIndex * 2]
            val sourceY = lightningPoints[sourceIndex * 2 + 1]
            val direction = if (random.nextBoolean()) 1f else -1f
            val branchSegments = random.nextInt(5, 10)
            val branchWidth = width * random.nextFloat(0.025f, 0.075f) * perspectiveScale
            val branchHeight = height * random.nextFloat(0.012f, 0.045f) * perspectiveScale
            lightningBranches.moveTo(sourceX, sourceY)
            var branchX = sourceX
            var branchY = sourceY
            for (step in 1..branchSegments) {
                val progress = step / branchSegments.toFloat()
                branchX = sourceX + direction * branchWidth * progress +
                    random.nextFloat(-0.0045f, 0.0045f) * width * (1f - progress * 0.45f)
                branchY = sourceY + branchHeight * progress + random.nextFloat(-0.10f, 0.10f) * stepY
                lightningBranches.lineTo(branchX, branchY)
            }

            if (branchIndex < 3) {
                val twigDirection = -direction
                lightningFineBranches.moveTo(branchX, branchY)
                val twigSegments = random.nextInt(3, 6)
                val twigWidth = width * random.nextFloat(0.012f, 0.027f)
                val twigHeight = height * random.nextFloat(0.008f, 0.022f)
                for (step in 1..twigSegments) {
                    val progress = step / twigSegments.toFloat()
                    lightningFineBranches.lineTo(
                        branchX + twigDirection * twigWidth * progress + random.nextFloat(-0.002f, 0.002f) * width,
                        branchY + twigHeight * progress,
                    )
                }
            }
        }

        lightningCenterX = xSum / (segmentCount + 1)
        lightningCenterY = ySum / (segmentCount + 1)
        lightningRadius = max(width, height) * 0.20f
        val normalizedDepth = (lightningCenterY / height).coerceIn(0.35f, 0.96f)
        depthIlluminationRadius = lightningRadius * (1.42f + normalizedDepth * 0.78f)
        depthIlluminationStrength = 0.72f + normalizedDepth * 0.72f
        illuminationPaint.shader = RadialGradient(
            lightningCenterX,
            lightningCenterY,
            lightningRadius,
            intArrayOf(0xA0642DA0.toInt(), 0x383C175F, Color.TRANSPARENT),
            floatArrayOf(0f, 0.38f, 1f),
            Shader.TileMode.CLAMP,
        )
        depthIlluminationPaint.shader = RadialGradient(
            lightningCenterX,
            lightningCenterY + lightningRadius * (0.05f + normalizedDepth * 0.28f),
            depthIlluminationRadius,
            intArrayOf(0x9A7130B5.toInt(), 0x4C421A70, 0x16200C38, Color.TRANSPARENT),
            floatArrayOf(0f, 0.28f, 0.66f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private fun drawLightning(canvas: Canvas, intensity: Float) {
        depthIlluminationPaint.alpha = (148f * intensity * depthIlluminationStrength).toInt().coerceAtMost(255)
        canvas.drawCircle(
            lightningCenterX,
            lightningCenterY + lightningRadius * (depthIlluminationStrength - 0.65f) * 0.38f,
            depthIlluminationRadius,
            depthIlluminationPaint,
        )
        illuminationPaint.alpha = (105f * intensity).toInt()
        canvas.drawCircle(lightningCenterX, lightningCenterY, lightningRadius, illuminationPaint)

        lightningGlowPaint.alpha = (52f * intensity).toInt()
        lightningHaloPaint.alpha = (172f * intensity).toInt()
        lightningCorePaint.alpha = (255f * intensity).toInt()
        canvas.drawPath(lightningPath, lightningGlowPaint)
        canvas.drawPath(lightningPath, lightningHaloPaint)
        canvas.drawPath(lightningPath, lightningCorePaint)

        lightningGlowPaint.strokeWidth = 2.8f * density
        lightningHaloPaint.strokeWidth = 0.92f * density
        lightningCorePaint.strokeWidth = 0.34f * density
        lightningGlowPaint.alpha = (24f * intensity).toInt()
        lightningHaloPaint.alpha = (92f * intensity).toInt()
        lightningCorePaint.alpha = (174f * intensity).toInt()
        canvas.drawPath(lightningBranches, lightningGlowPaint)
        canvas.drawPath(lightningBranches, lightningHaloPaint)
        canvas.drawPath(lightningBranches, lightningCorePaint)

        lightningGlowPaint.strokeWidth = 1.7f * density
        lightningHaloPaint.strokeWidth = 0.62f * density
        lightningCorePaint.strokeWidth = 0.26f * density
        lightningGlowPaint.alpha = (15f * intensity).toInt()
        lightningHaloPaint.alpha = (62f * intensity).toInt()
        lightningCorePaint.alpha = (132f * intensity).toInt()
        canvas.drawPath(lightningFineBranches, lightningGlowPaint)
        canvas.drawPath(lightningFineBranches, lightningHaloPaint)
        canvas.drawPath(lightningFineBranches, lightningCorePaint)
        lightningGlowPaint.strokeWidth = 4.8f * density
        lightningHaloPaint.strokeWidth = 1.55f * density
        lightningCorePaint.strokeWidth = 0.52f * density
    }

    private fun lightningIntensity(progress: Float): Float {
        if (progress <= 0f || progress >= 1f) return 0f
        val firstPulse = if (progress < 0.27f) {
            val local = progress / 0.27f
            sin(local * PI.toFloat()).let { it * it }
        } else {
            0f
        }
        val secondPulse = if (progress in 0.31f..0.67f) {
            val local = (progress - 0.31f) / 0.36f
            sin(local * PI.toFloat()).let { it * it } * 0.82f
        } else {
            0f
        }
        val afterglow = if (progress > 0.58f) {
            val local = (progress - 0.58f) / 0.42f
            sin(local * PI.toFloat()).coerceAtLeast(0f) * 0.18f
        } else {
            0f
        }
        return max(max(firstPulse, secondPulse), afterglow).coerceIn(0f, 1f)
    }

    private fun scheduleNextLightning(initial: Boolean = false) {
        if (!isAttachedToWindow || waitingForLightning) return
        waitingForLightning = true
        val delay = if (initial) {
            if (connecting) random.nextLong(420L, 1_250L) else random.nextLong(1_800L, 4_800L)
        } else if (connecting) {
            random.nextLong(750L, 2_100L)
        } else {
            random.nextLong(3_200L, 9_400L)
        }
        postDelayed(lightningRunnable, delay)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!driftAnimator.isStarted) driftAnimator.start()
        setPortalActive(activeTarget)
        scheduleNextLightning(initial = true)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(lightningRunnable)
        waitingForLightning = false
        driftAnimator.cancel()
        flashAnimator.cancel()
        activeAnimator.cancel()
        flashPhase = 0f
        super.onDetachedFromWindow()
    }

    private fun Random.nextFloat(from: Float, until: Float): Float = from + nextFloat() * abs(until - from)

    private companion object {
        const val TWO_PI = (PI * 2.0).toFloat()
        const val MAX_LIGHTNING_POINTS = 32
        val MOTE_X = floatArrayOf(0.12f, 0.84f, 0.27f, 0.73f, 0.18f, 0.89f, 0.42f, 0.62f, 0.34f)
        val MOTE_Y = floatArrayOf(0.12f, 0.19f, 0.42f, 0.49f, 0.68f, 0.76f, 0.87f, 0.61f, 0.28f)
        val MOTE_SIZE = floatArrayOf(0.65f, 0.85f, 0.55f, 0.72f, 0.60f, 0.82f, 0.66f, 0.48f, 0.58f)
        val MOTE_PHASE = floatArrayOf(0.2f, 1.7f, 2.9f, 4.2f, 0.8f, 3.5f, 5.3f, 2.2f, 4.9f)
    }
}
