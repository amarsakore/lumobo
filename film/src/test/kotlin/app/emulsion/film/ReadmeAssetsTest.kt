package app.emulsion.film

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Dev tool that renders the README example images (colour only; grain, halation and
 * frames come from real app exports passed in as showcase images).
 *
 *   ./gradlew :film:test --tests '*ReadmeAssetsTest*' -Dassets.in=<dir> -Dassets.out=docs/examples
 *
 * <dir> holds sRGB photos named flower.jpg, building.jpg, river.jpg, window.jpg and,
 * optionally, app exports showcase-*.jpg. Output JPEGs carry no metadata.
 */
class ReadmeAssetsTest {

    private val bg = Color(11, 11, 12)
    private val text = Color(242, 239, 233)
    private val dim = Color(156, 151, 143)
    private val amber = Color(245, 184, 65)
    private val family = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
        .firstOrNull { it == "Helvetica Neue" } ?: Font.SANS_SERIF

    @Test
    fun render() {
        val inDir = File(System.getProperty("assets.in").orEmpty())
        assumeTrue(inDir.isDirectory)
        val out = File(System.getProperty("assets.out").orEmpty().ifBlank { "build/assets" }).apply { mkdirs() }
        val photos = listOf("flower", "building", "river", "window").associateWith { ImageIO.read(File(inDir, "$it.jpg")) }

        // Before / after splits.
        split(photos["flower"]!!, Profiles.byId("classic_chrome"), File(out, "split-classic-chrome.jpg"))
        split(photos["building"]!!, Profiles.byId("velvia"), File(out, "split-velvia.jpg"))
        split(photos["river"]!!, Profiles.byId("gr_negative"), File(out, "split-negative-film.jpg"))

        // One grid per category, each on the photo that suits it best.
        val subject = mapOf(
            Category.FUJIFILM to "flower", Category.RICOH to "building", Category.KODAK to "flower",
            Category.FUJI_STOCK to "river", Category.CINEMA to "window", Category.INSTANT to "flower",
            Category.CAMERAS to "building", Category.ILFORD to "building",
        )
        for ((cat, name) in subject) {
            val films = listOf(Profiles.ORIGINAL) + Profiles.all.filter { it.category == cat }
            grid(squareCrop(photos[name]!!, 600), films, File(out, "grid-${cat.name.lowercase().replace('_', '-')}.jpg"))
        }

        // App exports: downscale + strip metadata.
        inDir.listFiles { f -> f.name.startsWith("showcase-") }!!.sorted().forEach { f ->
            write(fit(ImageIO.read(f), 1400), File(out, f.name))
        }
    }

    // ───────────────────────── helpers ─────────────────────────

    private fun g2(img: BufferedImage): Graphics2D = img.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    }

    private fun apply(src: BufferedImage, p: FilmProfile): BufferedImage {
        val look = CompiledLook(p.look)
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        val o = FloatArray(3)
        val t = FloatArray(3)
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val c = src.getRGB(x, y)
            look.apply(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f, o, t)
            out.setRGB(
                x, y,
                ((o[0] * 255f).roundToInt().coerceIn(0, 255) shl 16) or
                    ((o[1] * 255f).roundToInt().coerceIn(0, 255) shl 8) or
                    (o[2] * 255f).roundToInt().coerceIn(0, 255),
            )
        }
        return out
    }

    private fun fit(img: BufferedImage, longEdge: Int): BufferedImage {
        val s = min(1f, longEdge.toFloat() / max(img.width, img.height))
        return scaled(img, (img.width * s).roundToInt(), (img.height * s).roundToInt())
    }

    private fun scaled(img: BufferedImage, w: Int, h: Int): BufferedImage {
        // Two-step downscale for clean results.
        var cur = img
        while (cur.width / 2 >= w && cur.height / 2 >= h) {
            val half = BufferedImage(cur.width / 2, cur.height / 2, BufferedImage.TYPE_INT_RGB)
            g2(half).apply { drawImage(cur, 0, 0, half.width, half.height, null); dispose() }
            cur = half
        }
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        g2(out).apply { drawImage(cur, 0, 0, w, h, null); dispose() }
        return out
    }

    private fun squareCrop(img: BufferedImage, size: Int): BufferedImage {
        val s = min(img.width, img.height)
        // Bias toward the upper-middle for portrait shots (subjects sit there).
        val y = ((img.height - s) * 0.45f).roundToInt()
        return scaled(img.getSubimage((img.width - s) / 2, y, s, s), size, size)
    }

    private fun pill(g: Graphics2D, label: String, x: Int, y: Int, alignRight: Boolean, fill: Color, ink: Color, size: Float) {
        g.font = Font(family, Font.BOLD, size.roundToInt())
        val fm = g.fontMetrics
        val padX = (size * 0.8f).roundToInt()
        val h = (size * 1.9f).roundToInt()
        val w = fm.stringWidth(label) + padX * 2
        val left = if (alignRight) x - w else x
        g.color = fill
        g.fill(RoundRectangle2D.Float(left.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), h.toFloat(), h.toFloat()))
        g.color = ink
        g.drawString(label, left + padX, y + (h + fm.ascent - fm.descent) / 2)
    }

    private fun split(photo: BufferedImage, p: FilmProfile, file: File) {
        val base = fit(photo, 1400)
        val film = apply(base, p)
        val out = BufferedImage(base.width, base.height, BufferedImage.TYPE_INT_RGB)
        val g = g2(out)
        val mid = base.width / 2
        g.drawImage(base.getSubimage(0, 0, mid, base.height), 0, 0, null)
        g.drawImage(film.getSubimage(mid, 0, base.width - mid, base.height), mid, 0, null)
        g.color = Color(255, 255, 255, 230)
        g.stroke = BasicStroke(4f)
        g.drawLine(mid, 0, mid, base.height)
        val m = (base.width * 0.03f).roundToInt()
        val fs = base.width * 0.026f
        pill(g, "ORIGINAL", m, m, false, Color(0, 0, 0, 150), text, fs)
        pill(g, p.name.uppercase(), base.width - m, m, true, amber, bg, fs)
        g.dispose()
        write(out, file)
    }

    private fun grid(square: BufferedImage, films: List<FilmProfile>, file: File) {
        val cols = 5
        val cell = 360
        val gap = 14
        val labelH = 64
        val pad = 24
        val rows = (films.size + cols - 1) / cols
        val w = pad * 2 + cols * cell + (cols - 1) * gap
        val h = pad * 2 + rows * (cell + labelH) + (rows - 1) * gap - 8
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = g2(out)
        g.color = bg
        g.fillRect(0, 0, w, h)
        val small = scaled(square, cell, cell)
        films.forEachIndexed { i, p ->
            val x = pad + (i % cols) * (cell + gap)
            val y = pad + (i / cols) * (cell + labelH + gap)
            val img = if (p.isIdentity) small else apply(small, p)
            val clip = RoundRectangle2D.Float(x.toFloat(), y.toFloat(), cell.toFloat(), cell.toFloat(), 28f, 28f)
            g.clip = clip
            g.drawImage(img, x, y, null)
            g.clip = null
            if (p.isIdentity) {
                g.color = Color(255, 255, 255, 60)
                g.stroke = BasicStroke(2f)
                g.draw(clip)
            }
            g.font = Font(family, Font.BOLD, 21)
            g.color = text
            g.drawString(p.name, x + 4, y + cell + 30)
            g.font = Font(Font.MONOSPACED, Font.BOLD, 15)
            g.color = if (p.isIdentity) dim else amber
            g.drawString(p.code, x + 4, y + cell + 52)
        }
        g.dispose()
        write(out, file)
    }

    private fun write(img: BufferedImage, file: File) {
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = 0.9f
        }
        file.outputStream().use { os ->
            ImageIO.createImageOutputStream(os).use { ios ->
                writer.output = ios
                writer.write(null, IIOImage(img, null, null), param)
            }
        }
        writer.dispose()
    }
}
