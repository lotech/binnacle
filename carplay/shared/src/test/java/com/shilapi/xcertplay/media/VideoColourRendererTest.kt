package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.media.VideoColourRenderer.Companion.RANGE_FULL
import com.shilapi.xcertplay.media.VideoColourRenderer.Companion.RANGE_LIMITED
import com.shilapi.xcertplay.media.VideoColourRenderer.Companion.STANDARD_BT601_PAL
import com.shilapi.xcertplay.media.VideoColourRenderer.Companion.STANDARD_BT709
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoColourRendererTest {
    /** What the shader does: rgb = matrix * (yuv - offset), matrix column-major, 8-bit in and out. */
    private fun convert(standard: Int, range: Int, y: Int, cb: Int, cr: Int, height: Int = 720): IntArray {
        val (m, o) = VideoColourRenderer.yuvToRgb(standard, range, height)
        val v = floatArrayOf(y / 255f - o[0], cb / 255f - o[1], cr / 255f - o[2])
        return IntArray(3) { row ->
            val c = m[row] * v[0] + m[3 + row] * v[1] + m[6 + row] * v[2]
            Math.round(c.coerceIn(0f, 1f) * 255f)
        }
    }

    /** BT.709 full-range encode of an sRGB colour, as the iPhone sends it. */
    private fun encode709Full(r: Int, g: Int, b: Int): IntArray {
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val cb = (b - y) / 1.8556 + 128
        val cr = (r - y) / 1.5748 + 128
        return intArrayOf(Math.round(y).toInt(), Math.round(cb).toInt(), Math.round(cr).toInt())
    }

    private fun assertNear(expected: IntArray, actual: IntArray) {
        for (i in 0..2) assertEquals("channel $i of ${actual.toList()}", expected[i].toDouble(), actual[i].toDouble(), 2.0)
    }

    @Test
    fun fullRangeBt709KeepsNearWhitesAndPastels() {
        // The CarPlay case (13 Nov): these all came out 255 or clipped when treated as TV range.
        for (rgb in listOf(intArrayOf(242, 242, 242), intArrayOf(250, 245, 235), intArrayOf(185, 230, 165), intArrayOf(30, 30, 30))) {
            val (y, cb, cr) = encode709Full(rgb[0], rgb[1], rgb[2])
            assertNear(rgb, convert(STANDARD_BT709, RANGE_FULL, y, cb, cr))
        }
    }

    @Test
    fun fullRangeExtremesMapToBlackAndWhite() {
        assertNear(intArrayOf(255, 255, 255), convert(STANDARD_BT709, RANGE_FULL, 255, 128, 128))
        assertNear(intArrayOf(0, 0, 0), convert(STANDARD_BT709, RANGE_FULL, 0, 128, 128))
    }

    @Test
    fun limitedRangeStretches16To235() {
        assertNear(intArrayOf(255, 255, 255), convert(STANDARD_BT709, RANGE_LIMITED, 235, 128, 128))
        assertNear(intArrayOf(0, 0, 0), convert(STANDARD_BT709, RANGE_LIMITED, 16, 128, 128))
        assertNear(intArrayOf(128, 128, 128), convert(STANDARD_BT601_PAL, RANGE_LIMITED, 126, 128, 128))
    }

    @Test
    fun bt601LimitedRedMatchesTheStandard() {
        // BT.601 TV-range pure red is Y 81, Cb 90, Cr 240.
        assertNear(intArrayOf(255, 0, 0), convert(STANDARD_BT601_PAL, RANGE_LIMITED, 81, 90, 240))
    }

    @Test
    fun unspecifiedFieldsFollowTheUsualDefaults() {
        val (hd, hdOffset) = VideoColourRenderer.yuvToRgb(-1, -1, 720)
        val (bt709, bt709Offset) = VideoColourRenderer.yuvToRgb(STANDARD_BT709, RANGE_LIMITED, 720)
        assertEquals(bt709.toList(), hd.toList())
        assertEquals(bt709Offset.toList(), hdOffset.toList())
        val (sd, _) = VideoColourRenderer.yuvToRgb(-1, -1, 480)
        val (bt601, _) = VideoColourRenderer.yuvToRgb(STANDARD_BT601_PAL, RANGE_LIMITED, 480)
        assertEquals(bt601.toList(), sd.toList())
    }
}
