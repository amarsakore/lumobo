package app.emulsion.render

import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import app.emulsion.film.FrameStyle
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/** Things printed onto / around the frame after the GPU pass: date imprint and borders. */
object Overlays {

    // Seven-segment map: a b c d e f g
    private val DIGITS = arrayOf(
        "abcdef", "bc", "abdeg", "abcdg", "bcfg", "acdfg", "acdefg", "abc", "abcdefg", "abcdfg",
    )

    /** Orange LED date imprint, like a 90s point-and-shoot data back. Draws in place. */
    fun drawDateStamp(bitmap: Bitmap, date: LocalDate) {
        val canvas = Canvas(bitmap)
        val short = min(bitmap.width, bitmap.height).toFloat()
        val h = short * 0.034f
        val w = h * 0.56f
        val t = h * 0.14f
        val text = "'%02d %02d %02d".format(date.year % 100, date.monthValue, date.dayOfMonth)

        // Measure
        var total = 0f
        for (ch in text) total += advance(ch, w, h)
        val x0 = bitmap.width - short * 0.07f - total
        val y0 = bitmap.height - short * 0.065f - h

        val path = Path()
        var x = x0
        for (ch in text) {
            when {
                ch.isDigit() -> addDigit(path, DIGITS[ch - '0'], x, y0, w, h, t)
                ch == '\'' -> path.addRoundRect(RectF(x, y0, x + t, y0 + h * 0.3f), t / 2, t / 2, Path.Direction.CW)
            }
            x += advance(ch, w, h)
        }
        canvas.save()
        canvas.skew(-0.10f, 0f)
        canvas.translate(0.10f * (y0 + h), 0f)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 255, 70, 0)
            maskFilter = BlurMaskFilter(t * 1.8f, BlurMaskFilter.Blur.NORMAL)
            blendMode = BlendMode.SCREEN
        }
        val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 255, 150, 50)
            maskFilter = BlurMaskFilter(t * 0.28f, BlurMaskFilter.Blur.NORMAL)
            blendMode = BlendMode.SCREEN
        }
        val hot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 255, 225, 170)
            maskFilter = BlurMaskFilter(t * 0.2f, BlurMaskFilter.Blur.NORMAL)
            blendMode = BlendMode.SCREEN
        }
        canvas.drawPath(path, glow)
        canvas.drawPath(path, core)
        canvas.drawPath(path, hot)
        canvas.restore()
    }

    private fun advance(ch: Char, w: Float, h: Float) = when {
        ch.isDigit() -> w + h * 0.26f
        ch == '\'' -> h * 0.32f
        else -> w * 0.7f
    }

    private fun addDigit(path: Path, segs: String, x: Float, y: Float, w: Float, h: Float, t: Float) {
        val g = h * 0.035f
        val r = t / 2
        for (s in segs) {
            val rect = when (s) {
                'a' -> RectF(x + r + g, y, x + w - r - g, y + t)
                'd' -> RectF(x + r + g, y + h - t, x + w - r - g, y + h)
                'g' -> RectF(x + r + g, y + h / 2 - t / 2, x + w - r - g, y + h / 2 + t / 2)
                'f' -> RectF(x, y + r + g, x + t, y + h / 2 - g)
                'b' -> RectF(x + w - t, y + r + g, x + w, y + h / 2 - g)
                'e' -> RectF(x, y + h / 2 + g, x + t, y + h - r - g)
                else -> RectF(x + w - t, y + h / 2 + g, x + w, y + h - r - g) // c
            }
            path.addRoundRect(rect, r, r, Path.Direction.CW)
        }
    }

    /** Returns a new bitmap with the frame drawn around [src] (or [src] itself for NONE). */
    fun frame(src: Bitmap, style: FrameStyle, label: String, reuse: Bitmap? = null): Bitmap {
        fun canvasBitmap(w: Int, h: Int): Bitmap =
            if (reuse != null && !reuse.isRecycled && reuse.isMutable && reuse.width == w && reuse.height == h) reuse.also { it.eraseColor(0) }
            else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val w = src.width
        val h = src.height
        val short = min(w, h).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        return when (style) {
            FrameStyle.NONE -> src
            FrameStyle.WHITE -> {
                val b = (short * 0.04f).toInt()
                val out = canvasBitmap(w + 2 * b, h + 2 * b)
                val c = Canvas(out)
                c.drawColor(Color.rgb(251, 251, 248))
                c.drawBitmap(src, b.toFloat(), b.toFloat(), paint)
                out
            }
            FrameStyle.INSTANT -> {
                val side = (short * 0.065f).toInt()
                val bottom = (short * 0.26f).toInt()
                val ow = w + 2 * side
                val oh = h + side + bottom
                val out = canvasBitmap(ow, oh)
                val c = Canvas(out)
                paint.shader = LinearGradient(0f, 0f, ow * 0.3f, oh.toFloat(), Color.rgb(246, 243, 236), Color.rgb(236, 232, 223), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, ow.toFloat(), oh.toFloat(), paint)
                paint.shader = null
                c.drawBitmap(src, side.toFloat(), side.toFloat(), paint)
                // Slight recess where the image sits under the plastic window.
                val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.style = Paint.Style.STROKE
                    strokeWidth = max(2f, short * 0.002f)
                    color = Color.argb(70, 0, 0, 0)
                }
                c.drawRect(side.toFloat(), side.toFloat(), (side + w).toFloat(), (side + h).toFloat(), edge)
                out
            }
            FrameStyle.FILM -> filmScan(src, label, ::canvasBitmap)
        }
    }

    /** 35mm full-gate scan: black rebate, sprocket holes along the long edge and DX-style edge print. */
    private fun filmScan(src: Bitmap, label: String, alloc: (Int, Int) -> Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val landscape = w >= h
        val short = min(w, h).toFloat()
        val rail = (short * 0.15f).toInt()      // border carrying the sprocket holes
        val end = (short * 0.035f).toInt()      // border at the frame ends
        val ow = if (landscape) w + 2 * end else w + 2 * rail
        val oh = if (landscape) h + 2 * rail else h + 2 * end
        val out = alloc(ow, oh)
        val c = Canvas(out)
        c.drawColor(Color.rgb(12, 11, 10))

        val ix = if (landscape) end.toFloat() else rail.toFloat()
        val iy = if (landscape) rail.toFloat() else end.toFloat()
        val radius = short * 0.012f
        c.save()
        val clip = Path().apply { addRoundRect(RectF(ix, iy, ix + w, iy + h), radius, radius, Path.Direction.CW) }
        c.clipPath(clip)
        c.drawBitmap(src, ix, iy, Paint(Paint.FILTER_BITMAP_FLAG))
        c.restore()

        // Sprocket holes (light passes straight through: bright).
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(232, 228, 218) }
        val across = short * 0.058f
        val along = short * 0.040f
        val pitch = short * 0.098f
        val holeR = short * 0.007f
        val railCenterOffset = rail * 0.34f
        val length = if (landscape) ow.toFloat() else oh.toFloat()
        var p = pitch * 0.35f
        while (p + along < length) {
            if (landscape) {
                c.drawRoundRect(RectF(p, railCenterOffset - across / 2, p + along, railCenterOffset + across / 2), holeR, holeR, hole)
                c.drawRoundRect(RectF(p, oh - railCenterOffset - across / 2, p + along, oh - railCenterOffset + across / 2), holeR, holeR, hole)
            } else {
                c.drawRoundRect(RectF(railCenterOffset - across / 2, p, railCenterOffset + across / 2, p + along), holeR, holeR, hole)
                c.drawRoundRect(RectF(ow - railCenterOffset - across / 2, p, ow - railCenterOffset + across / 2, p + along), holeR, holeR, hole)
            }
            p += pitch
        }

        // Edge print between the holes and the image.
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(225, 235, 160, 60)
            textSize = short * 0.030f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.12f
        }
        val textOffset = rail * 0.80f
        val printA = "${label.uppercase()}   ▸ 24"
        val printB = "24A        EMULSION"
        if (landscape) {
            c.drawText(printA, short * 0.2f, textOffset - ink.descent() + ink.textSize * 0.35f, ink)
            c.drawText(printB, short * 0.2f, oh - textOffset + ink.textSize * 0.65f, ink)
        } else {
            c.save()
            c.rotate(90f, 0f, 0f)
            c.drawText(printA, short * 0.2f, -(textOffset - ink.textSize * 0.65f), ink)
            c.restore()
            c.save()
            c.rotate(90f, 0f, 0f)
            c.drawText(printB, short * 0.2f, -(ow - textOffset + ink.textSize * 0.35f), ink)
            c.restore()
        }
        return out
    }
}
