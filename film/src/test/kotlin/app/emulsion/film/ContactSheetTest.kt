package app.emulsion.film

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Dev tool: renders film looks onto sample photos so they can be judged by eye.
 *   ./gradlew :app:testDebugUnitTest --tests '*ContactSheetTest*' -Dcontact.in=<dir or files> -Dcontact.out=<dir>
 *   -Dcontact.only=index         -> numbered index of the inputs
 *   -Dcontact.only=id1,id2       -> only these profiles
 * Skipped when contact.in is not set, so normal test runs are unaffected.
 */
class ContactSheetTest {

    @Test
    fun render() {
        val inArg = System.getProperty("contact.in").orEmpty()
        assumeTrue(inArg.isNotBlank())
        val outDir = File(System.getProperty("contact.out").orEmpty().ifBlank { "build/contact" }).apply { mkdirs() }
        val only = System.getProperty("contact.only").orEmpty()

        val inputs = inArg.split(',').map { File(it.trim()) }.flatMap { f ->
            if (f.isDirectory) f.listFiles()!!.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") }.sorted() else listOf(f)
        }
        val images = inputs.map { ImageIO.read(it) }

        if (only == "index") {
            writeGrid(images.map { fit(it, 220) }, inputs.mapIndexed { i, f -> "$i ${f.nameWithoutExtension.takeLast(6)}" }, 6, File(outDir, "index.jpg"))
            return
        }

        val cell = 300
        val small = images.map { fit(it, cell) }
        val profiles = if (only.isBlank()) Profiles.all else only.split(',').map { Profiles.byId(it.trim()) }
        val chunk = System.getProperty("contact.chunk").orEmpty().toIntOrNull() ?: 99
        val groups = LinkedHashMap<String, List<FilmProfile>>()
        val base = if (only.isBlank()) profiles.filter { !it.isIdentity }.groupBy { it.category!!.name } else mapOf("selected" to profiles)
        for ((k, v) in base) v.chunked(chunk).forEachIndexed { i, part -> groups[if (i == 0) k else "${k}_$i"] = part }

        for ((name, list) in groups) {
            val withOriginal = listOf(Profiles.ORIGINAL) + list.filter { !it.isIdentity }
            val cells = ArrayList<BufferedImage>()
            val labels = ArrayList<String>()
            for (img in small) for (p in withOriginal) {
                cells += applyLook(img, CompiledLook(p.look))
                labels += p.name
            }
            writeGrid(cells, labels, withOriginal.size, File(outDir, "sheet_$name.jpg"))
        }
    }

    private fun applyLook(src: BufferedImage, look: CompiledLook): BufferedImage {
        val w = src.width
        val h = src.height
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val o = FloatArray(3)
        val t = FloatArray(3)
        for (y in 0 until h) for (x in 0 until w) {
            val c = src.getRGB(x, y)
            look.apply(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f, o, t)
            val r = (o[0] * 255f).roundToInt().coerceIn(0, 255)
            val g = (o[1] * 255f).roundToInt().coerceIn(0, 255)
            val b = (o[2] * 255f).roundToInt().coerceIn(0, 255)
            out.setRGB(x, y, (r shl 16) or (g shl 8) or b)
        }
        return out
    }

    private fun fit(img: BufferedImage, size: Int): BufferedImage {
        val s = size.toFloat() / max(img.width, img.height)
        val w = (img.width * s).roundToInt()
        val h = (img.height * s).roundToInt()
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(img, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    private fun writeGrid(cells: List<BufferedImage>, labels: List<String>, cols: Int, file: File) {
        val cw = cells.maxOf { it.width }
        val ch = cells.maxOf { it.height } + 22
        val rows = (cells.size + cols - 1) / cols
        val sheet = BufferedImage(cols * cw, rows * ch, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.color = Color(24, 24, 24)
        g.fillRect(0, 0, sheet.width, sheet.height)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 15)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        cells.forEachIndexed { i, img ->
            val x = (i % cols) * cw
            val y = (i / cols) * ch
            g.drawImage(img, x, y, null)
            g.color = Color(240, 200, 90)
            g.drawString(labels[i], x + 6, y + ch - 6)
        }
        g.dispose()
        ImageIO.write(sheet, "jpg", file)
    }
}
