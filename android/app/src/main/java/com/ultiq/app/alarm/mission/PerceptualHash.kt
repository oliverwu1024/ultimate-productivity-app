package com.ultiq.app.alarm.mission

import android.graphics.Bitmap
import androidx.core.graphics.scale
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 64-bit DCT perceptual hash (§8.9). Algorithm:
 *
 *   1. Down-sample the bitmap to [SIZE]×[SIZE] (32×32).
 *   2. Convert to 8-bit grayscale.
 *   3. Run a 2-D DCT-II over the 32×32 matrix (separable: rows then columns,
 *      O(N³) instead of the naïve O(N⁴)).
 *   4. Take the top-left [LOW]×[LOW] (8×8) low-frequency block.
 *   5. Compute the median of those 64 coefficients; each coefficient ≥ median
 *      contributes a `1` bit, otherwise `0`. Bit ordering is row-major.
 *
 * Two images are "the same scene" when their hashes' Hamming distance is
 * small. The roadmap (§8.4 example config) uses **tolerance ≤ 12**.
 *
 * Note: the DC term at `[0][0]` is included in the hash and the median, even
 * though it tends to dominate brightness. This makes the median pull toward
 * it and the DC bit almost-always 1 in practice — but keeps the 64 bits all
 * meaningful (a brightness inversion still flips bits).
 */
object PerceptualHash {

    const val SIZE = 32
    const val LOW = 8

    /** Compute the 64-bit pHash of [bitmap]. Bitmap is not recycled. */
    fun compute(bitmap: Bitmap): Long {
        val scaled = bitmap.scale(SIZE, SIZE)
        val gray = toGrayscale(scaled)
        if (scaled !== bitmap) scaled.recycle()

        val dct = dct2(gray, SIZE)

        // Flatten the 8×8 low-frequency block and find its median.
        val coeffs = DoubleArray(LOW * LOW)
        var k = 0
        for (u in 0 until LOW) {
            for (v in 0 until LOW) {
                coeffs[k++] = dct[u][v]
            }
        }
        val median = medianOf(coeffs)

        var hash = 0L
        var bit = 0
        for (u in 0 until LOW) {
            for (v in 0 until LOW) {
                if (dct[u][v] >= median) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }

    /** Hamming distance between two 64-bit hashes (0..64). */
    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    // ── internals ─────────────────────────────────────────────────────────

    /** Returns a `size`×`size` grayscale matrix. */
    private fun toGrayscale(scaled: Bitmap): Array<DoubleArray> {
        val size = scaled.width
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        val out = Array(size) { DoubleArray(size) }
        for (y in 0 until size) {
            for (x in 0 until size) {
                val px = pixels[y * size + x]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                out[y][x] = 0.299 * r + 0.587 * g + 0.114 * b
            }
        }
        return out
    }

    /**
     * Separable 2-D DCT-II. The 2-D transform is the composition of two
     * 1-D transforms (rows, then columns), making it O(N³) instead of the
     * naïve O(N⁴). For N=32 that's ~32k multiply-adds — ~30× faster than
     * the direct formulation, with identical output.
     */
    private fun dct2(src: Array<DoubleArray>, n: Int): Array<DoubleArray> {
        val cosTable = Array(n) { u ->
            DoubleArray(n) { x ->
                cos((2.0 * x + 1.0) * u * Math.PI / (2.0 * n))
            }
        }
        val alpha = DoubleArray(n) { u ->
            if (u == 0) sqrt(1.0 / n) else sqrt(2.0 / n)
        }

        // Pass 1: DCT each row.
        val rowDct = Array(n) { DoubleArray(n) }
        for (y in 0 until n) {
            for (u in 0 until n) {
                var sum = 0.0
                val cu = cosTable[u]
                for (x in 0 until n) sum += src[y][x] * cu[x]
                rowDct[y][u] = alpha[u] * sum
            }
        }

        // Pass 2: DCT each column of the row-transformed matrix.
        val out = Array(n) { DoubleArray(n) }
        for (u in 0 until n) {
            for (v in 0 until n) {
                var sum = 0.0
                val cv = cosTable[v]
                for (y in 0 until n) sum += rowDct[y][u] * cv[y]
                out[v][u] = alpha[v] * sum
            }
        }
        return out
    }

    private fun medianOf(values: DoubleArray): Double {
        val copy = values.copyOf()
        copy.sort()
        val n = copy.size
        return if (n % 2 == 0) (copy[n / 2 - 1] + copy[n / 2]) / 2.0
        else copy[n / 2]
    }
}
