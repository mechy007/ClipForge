package com.clipforge.app

import java.util.Locale

/** Pure FFmpeg crop-expression builder; safe to exercise in local JVM tests. */
object FaceCropFilterBuilder {
    fun build(path: List<CropFocus>, startMs: Long, endMs: Long): String {
        require(path.isNotEmpty() && endMs > startMs)
        val ordered = path.sortedBy { it.timeMs }
        val before = ordered.lastOrNull { it.timeMs <= startMs } ?: ordered.first()
        val inside = ordered.filter { it.timeMs in startMs..endMs }
        val after = ordered.firstOrNull { it.timeMs >= endMs } ?: ordered.last()
        val samples = (listOf(before) + inside + listOf(after))
            .map { focus ->
                ((focus.timeMs - startMs).coerceIn(0L, endMs - startMs) / 1000.0) to focus
            }
            .distinctBy { String.format(Locale.US, "%.3f", it.first) }
            .sortedBy { it.first }
        val width = ordered.first().width.coerceIn(0.05f, 1f)
        val height = ordered.first().height.coerceIn(0.05f, 1f)
        fun interpolate(selector: (CropFocus) -> Float): String {
            if (samples.size == 1) return number(selector(samples.first().second))
            var expression = number(selector(samples.last().second))
            for (index in samples.size - 2 downTo 0) {
                val (timeA, a) = samples[index]
                val (timeB, b) = samples[index + 1]
                val valueA = selector(a)
                val valueB = selector(b)
                val span = (timeB - timeA).coerceAtLeast(0.001)
                val lerp = "${number(valueA)}+(${number(valueB - valueA)})*(t-${number(timeA.toFloat())})/${number(span.toFloat())}"
                expression = "if(lt(t,${number(timeB.toFloat())}),$lerp,$expression)"
            }
            return expression
        }
        val centerX = interpolate { it.centerX }
        val centerY = interpolate { it.centerY }
        val x = "max(0,min(iw-ow,($centerX)*iw-ow/2))"
        val y = "max(0,min(ih-oh,($centerY)*ih-oh/2))"
        return "crop=w=iw*${number(width)}:h=ih*${number(height)}:x='$x':y='$y':eval=frame"
    }

    private fun number(value: Float): String = String.format(Locale.US, "%.6f", value)
    private fun number(value: Double): String = String.format(Locale.US, "%.6f", value)
}
