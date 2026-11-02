package com.dot.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.ln
import kotlin.math.pow

object Enhancer {

    private const val MAX_SIDE = 3000

    fun load(file: File): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = BitmapFactory.decodeFile(file.path, opts)
        val rot = when (ExifInterface(file.path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rot != 0f) {
            val m = Matrix().apply { postRotate(rot) }
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (r != bmp) bmp.recycle()
            bmp = r
        }
        return bmp
    }

    fun enhance(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val n = w * h
        val px = IntArray(n)
        src.getPixels(px, 0, w, 0, 0, w, h)

        // histogram + average brightness
        val hist = IntArray(256)
        var sum = 0L
        for (p in px) {
            val l = (54 * ((p shr 16) and 255) + 183 * ((p shr 8) and 255) + 19 * (p and 255)) shr 8
            hist[l]++
            sum += l
        }
        var lo = 0
        var acc = 0
        while (lo < 255 && acc + hist[lo] < n * 0.005) { acc += hist[lo]; lo++ }
        var hi = 255
        acc = 0
        while (hi > 0 && acc + hist[hi] < n * 0.005) { acc += hist[hi]; hi-- }
        if (hi - lo < 100) { lo = 0; hi = 255 }

        val mean = sum.toDouble() / n
        val m = ((mean - lo) / (hi - lo)).coerceIn(0.08, 0.92)
        val g = (ln(0.5) / ln(m)).coerceIn(0.8, 1.25)
        val gamma = 1.0 + (g - 1.0) * 0.7

        // levels + gamma + soft contrast curve
        val lut = IntArray(256)
        for (i in 0..255) {
            var x = ((i - lo).toDouble() / (hi - lo)).coerceIn(0.0, 1.0)
            x = x.pow(gamma)
            val s = x * x * (3 - 2 * x)
            x = x * 0.8 + s * 0.2
            lut[i] = (x * 255.0 + 0.5).toInt().coerceIn(0, 255)
        }

        // apply LUT + saturation
        val sat = 1.12f
        for (i in 0 until n) {
            val p = px[i]
            var r = lut[(p shr 16) and 255].toFloat()
            var gg = lut[(p shr 8) and 255].toFloat()
            var b = lut[p and 255].toFloat()
            val l = 0.299f * r + 0.587f * gg + 0.114f * b
            r = (l + (r - l) * sat).coerceIn(0f, 255f)
            gg = (l + (gg - l) * sat).coerceIn(0f, 255f)
            b = (l + (b - l) * sat).coerceIn(0f, 255f)
            px[i] = (0xFF shl 24) or (r.toInt() shl 16) or (gg.toInt() shl 8) or b.toInt()
        }

        // light sharpen
        val copy = px.copyOf()
        val amt = 0.45f
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val c = copy[i]
                val u = copy[i - w]
                val d = copy[i + w]
                val l = copy[i - 1]
                val r = copy[i + 1]
                px[i] = (0xFF shl 24) or
                    (sh(c, u, d, l, r, 16, amt) shl 16) or
                    (sh(c, u, d, l, r, 8, amt) shl 8) or
                    sh(c, u, d, l, r, 0, amt)
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun sh(c: Int, u: Int, d: Int, l: Int, r: Int, s: Int, amt: Float): Int {
        val cc = (c shr s) and 255
        val ne = ((u shr s) and 255) + ((d shr s) and 255) + ((l shr s) and 255) + ((r shr s) and 255)
        return (cc + amt * (4 * cc - ne)).toInt().coerceIn(0, 255)
    }
}
