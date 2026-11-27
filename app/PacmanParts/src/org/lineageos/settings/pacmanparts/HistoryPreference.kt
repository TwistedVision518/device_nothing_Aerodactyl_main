// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.pacmanparts

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

internal class HistoryPreference(context: Context, private val fixedMax: Float? = null) :
    Preference(context) {
    private var samples = emptyList<Float?>()

    init {
        isSelectable = false
        widgetLayoutResource = R.layout.parts_history_chart
    }

    fun record(sample: Float?, description: CharSequence) {
        samples = (samples + sample).takeLast(HistoryChart.WINDOW)
        summary = description
        notifyChanged()
    }

    fun clear() {
        samples = emptyList()
        setSummary(R.string.loading)
        notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.history_chart) as? HistoryChart)?.show(samples, fixedMax)
    }
}

class HistoryChart(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val colors = context.obtainStyledAttributes(intArrayOf(android.R.attr.colorAccent))
        color = colors.getColor(0, 0xff5b6bb5.toInt())
        colors.recycle()
        strokeWidth = 2 * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private var samples = emptyList<Float?>()
    private var fixedMax: Float? = null

    internal fun show(samples: List<Float?>, fixedMax: Float?) {
        this.samples = samples
        this.fixedMax = fixedMax
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = line.strokeWidth
        val max = fixedMax ?: samples.filterNotNull().maxOrNull() ?: return
        val availableWidth = width - 2 * inset
        val availableHeight = height - 2 * inset
        if (max <= 0f || availableWidth <= 0 || availableHeight <= 0) return
        var previous: Float? = null
        val step = availableWidth / (WINDOW - 1)
        for ((index, sample) in samples.withIndex()) {
            if (sample == null) {
                previous = null
                continue
            }
            val x = width - inset - (samples.lastIndex - index) * step
            val y = height - inset - (sample / max).coerceIn(0f, 1f) * availableHeight
            previous?.let { canvas.drawLine(x - step, it, x, y, line) }
                ?: canvas.drawPoint(x, y, line)
            previous = y
        }
    }

    companion object {
        const val WINDOW = 60
    }
}
