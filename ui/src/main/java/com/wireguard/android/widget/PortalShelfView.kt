/*
 * Copyright © 2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.wireguard.android.R

/** Draws the shallow curved glass shelf around the main portal control. */
class PortalShelfView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val path = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = ContextCompat.getColor(context, R.color.rabbit_outline_glass)
        alpha = 145
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f * density
        color = ContextCompat.getColor(context, R.color.rabbit_accent_soft)
        alpha = 115
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val width = w.toFloat()
        val height = h.toFloat()
        val inset = 1.5f * density
        val corner = 27f * density
        val crown = 10f * density

        path.reset()
        path.moveTo(inset + corner, inset + crown)
        path.cubicTo(width * 0.33f, inset + crown, width * 0.42f, inset, width * 0.5f, inset)
        path.cubicTo(width * 0.58f, inset, width * 0.67f, inset + crown, width - inset - corner, inset + crown)
        path.quadTo(width - inset, inset + crown, width - inset, inset + crown + corner)
        path.lineTo(width - inset, height - corner - crown)
        path.quadTo(width - inset, height - crown, width - inset - corner, height - crown)
        path.cubicTo(width * 0.67f, height - crown, width * 0.58f, height - inset, width * 0.5f, height - inset)
        path.cubicTo(width * 0.42f, height - inset, width * 0.33f, height - crown, inset + corner, height - crown)
        path.quadTo(inset, height - crown, inset, height - corner - crown)
        path.lineTo(inset, inset + crown + corner)
        path.quadTo(inset, inset + crown, inset + corner, inset + crown)
        path.close()

        fillPaint.shader = LinearGradient(
            0f,
            0f,
            width,
            height,
            intArrayOf(0x703D1A62, 0x8A120A24.toInt(), 0x56281044),
            null,
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, outlinePaint)

        canvas.save()
        canvas.scale(0.972f, 0.91f, w * 0.5f, h * 0.5f)
        canvas.drawPath(path, accentPaint)
        canvas.restore()
    }
}
