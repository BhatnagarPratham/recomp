package app.recomp.domain.ai

import kotlin.math.roundToInt

/**
 * The size a photo is sent and kept at (NU-19, NU-20; TRD §6.2): about 1,500 px on its long edge, never enlarged.
 * That's enough for an AI to read a label or portion sizes, keeps a request's image tokens inside
 * `AiPricing.IMAGE_TOKENS`, and keeps a draft's photo small.
 */
object PhotoScale {
    const val MAX_EDGE = 1_500

    /** JPEG quality for a scaled photo: plenty for text, a fraction of the camera's file. */
    const val JPEG_QUALITY = 85

    /** The scaled width and height, keeping the aspect ratio. */
    fun size(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        require(width > 0 && height > 0) { "An empty photo" }
        val long = maxOf(width, height)
        if (long <= maxEdge) return width to height
        val f = maxEdge.toDouble() / long
        return (width * f).roundToInt().coerceAtLeast(1) to (height * f).roundToInt().coerceAtLeast(1)
    }

    /**
     * The largest power-of-two `inSampleSize` that still leaves the long edge at or above [maxEdge], so decoding a
     * 12 MP camera photo doesn't hold the whole thing in memory before the exact scale.
     */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Int {
        var sample = 1
        val long = maxOf(width, height)
        while (long / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }
}
