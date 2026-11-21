package app.lumobo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumobo.editor.EditorController
import app.lumobo.film.Glow
import app.lumobo.film.GlowStyle
import app.lumobo.ui.components.FilmSlider
import app.lumobo.ui.components.PanelColumn
import app.lumobo.ui.theme.Palette
import app.lumobo.ui.theme.Type
import kotlin.math.roundToInt

/**
 * Glow: Lightroom's Bloom, Diffusion, Halation, Motion and Streak, plus Orton. Beyond
 * Lightroom: lights can glow in their own colour, Warmth + Tint give full colour control
 * (Halation included), and SHOW previews just the light that glows.
 */
@Composable
internal fun GlowPanel(editor: EditorController) {
    val g = editor.adj.glow
    fun set(n: Glow) = editor.updateAdj(editor.adj.copy(glow = n))
    Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Row(Modifier.fillMaxWidth().height(38.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("GLOW", style = Type.Label.copy(color = Palette.Text, fontWeight = FontWeight.Bold))
                Text(
                    if (g.isOn) g.style.blurb else "Light that spreads from the brightest parts of the photo.",
                    style = Type.Body.copy(fontSize = 11.5.sp, lineHeight = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (g.isOn) {
                // Show just the light that glows, to set Range and Spread precisely.
                val on = editor.showGlow
                Text(
                    "SHOW", style = Type.Label.copy(color = if (on) Palette.Bg else Palette.Text, fontWeight = FontWeight.Bold),
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .then(if (on) Modifier.background(Palette.Amber) else Modifier.border(1.dp, Palette.Line, RoundedCornerShape(50)))
                        .clickable { editor.previewGlow(!on) }.padding(horizontal = 12.dp, vertical = 5.dp),
                )
                Text(
                    "RESET", style = Type.Label.copy(color = Palette.Amber),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { editor.previewGlow(false); set(Glow()) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(listOf<GlowStyle?>(null) + GlowStyle.entries) { style ->
                val sel = if (style == null) !g.isOn else g.isOn && g.style == style
                Column(
                    Modifier.width(56.dp).clickable {
                        if (style == null) { editor.previewGlow(false); set(g.copy(amount = 0f)) } else set(g.withStyle(style))
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(50.dp).clip(RoundedCornerShape(13.dp)).background(Palette.Surface2)
                            .border(if (sel) 2.dp else 0.dp, if (sel) Palette.Amber else Color.Transparent, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) { GlowIcon(style, if (sel) Palette.Amber else Palette.TextDim) }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        style?.label ?: "Off", fontSize = 10.5.sp, maxLines = 1, color = if (sel) Palette.Text else Palette.TextDim,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
        if (g.isOn) {
            val s = g.style
            PanelColumn(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
                FilmSlider("Amount", g.amount, { set(editor.adj.glow.copy(amount = it.coerceAtLeast(0.001f))) }, min = 0f, max = 1f, default = 0.5f)
                // Range: how far down the tones the glow reaches (Orton: how bright the soft copy is).
                FilmSlider(if (s == GlowStyle.ORTON) "Brightness" else "Range", g.range, { set(editor.adj.glow.copy(range = it)) },
                    min = 0f, max = 1f, default = s.defaultRange)
                FilmSlider(if (s.directional) "Length" else "Spread", g.spread, { set(editor.adj.glow.copy(spread = it)) }, min = 0f, max = 1f, default = 0.5f)
                if (s.directional) {
                    FilmSlider("Direction", g.direction / 360f, { set(editor.adj.glow.copy(direction = it * 360f)) }, min = 0f, max = 1f, default = 0f,
                        format = { "${(it * 360f).roundToInt()}°" })
                    FilmSlider("Softness", g.softness, { set(editor.adj.glow.copy(softness = it)) }, min = 0f, max = 1f, default = 0.15f)
                }
                FilmSlider(if (s == GlowStyle.HALATION) "Colour" else "Warmth", g.warmth, { set(editor.adj.glow.copy(warmth = it)) },
                    format = if (s == GlowStyle.HALATION) { v -> if (v < -0.33f) "Yellow" else if (v > 0.33f) "Red" else "Orange" }
                    else { v -> val i = (v * 100).roundToInt(); if (i > 0) "+$i" else "$i" })
                FilmSlider("Tint", g.tint, { set(editor.adj.glow.copy(tint = it)) })
                if (s != GlowStyle.HALATION && s != GlowStyle.ORTON) {
                    // Lightroom's Bloom / Streak are white light only; here each light can keep its colour.
                    FilmSlider("Light colour", g.color, { set(editor.adj.glow.copy(color = it)) }, min = 0f, max = 1f, default = 1f,
                        format = { if (it < 0.005f) "White" else "${(it * 100).roundToInt()}" })
                }
            }
        }
    }
}

@Composable
private fun GlowIcon(style: GlowStyle?, color: Color) {
    Canvas(Modifier.size(30.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        val line = 1.8.dp.toPx()
        when (style) {
            null -> {
                drawCircle(color, r * 0.8f, c, style = Stroke(line))
                drawLine(color, Offset(c.x - r * 0.55f, c.y + r * 0.55f), Offset(c.x + r * 0.55f, c.y - r * 0.55f), line)
            }
            GlowStyle.BLOOM -> {
                for (i in 4 downTo 1) drawCircle(color.copy(alpha = 0.16f * (5 - i)), r * i / 4.2f, c)
                drawCircle(color, r * 0.18f, c)
            }
            GlowStyle.DIFFUSION -> {
                drawCircle(color.copy(alpha = 0.22f), r * 0.62f, Offset(c.x - r * 0.3f, c.y + r * 0.12f))
                drawCircle(color.copy(alpha = 0.22f), r * 0.62f, Offset(c.x + r * 0.3f, c.y - r * 0.12f))
                drawCircle(color.copy(alpha = 0.35f), r * 0.42f, c)
            }
            GlowStyle.HALATION -> {
                drawCircle(color.copy(alpha = 0.35f), r * 0.86f, c, style = Stroke(r * 0.34f))
                drawCircle(color, r * 0.3f, c)
            }
            GlowStyle.ORTON -> {
                drawCircle(color.copy(alpha = 0.25f), r * 0.95f, c)
                drawCircle(color.copy(alpha = 0.45f), r * 0.66f, c)
                drawCircle(color, r * 0.38f, c, style = Stroke(line))
            }
            GlowStyle.MOTION -> {
                for (i in 0 until 3) {
                    val y = c.y + (i - 1) * r * 0.42f
                    drawLine(color.copy(alpha = 1f - i * 0.25f), Offset(c.x - r * 0.35f, y), Offset(c.x + r * (0.95f - i * 0.18f), y), line)
                }
                drawCircle(color, r * 0.26f, Offset(c.x - r * 0.5f, c.y))
            }
            GlowStyle.STREAK -> {
                drawLine(color.copy(alpha = 0.45f), Offset(0f, c.y), Offset(size.width, c.y), r * 0.22f)
                drawLine(color, Offset(r * 0.15f, c.y), Offset(size.width - r * 0.15f, c.y), line)
                drawCircle(color, r * 0.24f, c)
            }
        }
    }
}
