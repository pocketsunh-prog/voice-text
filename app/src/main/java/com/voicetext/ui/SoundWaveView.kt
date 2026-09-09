package com.voicetext.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/**
 * Custom view that displays a sound wave animation based on audio amplitude.
 */
class SoundWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var amplitude = 0f // 0.0 to 1.0
    private var phase = 0f

    private val wavePaint = Paint().apply {
        color = Color.parseColor("#E8A87C") // accent color
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val centerPaint = Paint().apply {
        color = Color.parseColor("#5B8C5A") // primary color
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun updateAmplitude(newAmplitude: Float) {
        amplitude = newAmplitude
        phase += 0.15f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height / 2f
        val barWidth = 8f
        val barSpacing = 6f
        val maxBarHeight = height * 0.8f

        // Number of bars to display
        val barCount = (width / (barWidth + barSpacing)).toInt().coerceAtLeast(5)

        for (i in 0 until barCount) {
            val x = i * (barWidth + barSpacing) + barSpacing

            // Create a wave pattern based on position and phase
            val waveFactor = sin(phase + i * 0.3f).toFloat() * 0.5f + 0.5f
            val barHeight = maxBarHeight * amplitude * waveFactor

            // Ensure minimum visibility when recording
            val actualBarHeight = if (amplitude > 0.01f) barHeight.coerceIn(8f, maxBarHeight) else 4f

            // Draw rounded bar
            val left = x
            val top = centerY - actualBarHeight / 2f
            val right = x + barWidth
            val bottom = centerY + actualBarHeight / 2f
            val cornerRadius = barWidth / 2f

            canvas.drawRoundRect(left, top, right, bottom, cornerRadius, cornerRadius, wavePaint)
        }

        // Draw center line
        canvas.drawRect(0f, centerY - 1f, width, centerY + 1f, centerPaint)
    }
}
