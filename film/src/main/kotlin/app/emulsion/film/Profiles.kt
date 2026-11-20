package app.emulsion.film

enum class Category(val label: String) {
    FUJIFILM("Fujifilm"),
    RICOH("Ricoh GR"),
    KODAK("Kodak"),
    FUJI_STOCK("Fuji Film"),
    CINEMA("Cinema"),
    INSTANT("Instant"),
    CAMERAS("Cameras"),
    ILFORD("Ilford B&W"),
}

enum class FrameStyle(val label: String) { NONE("None"), INSTANT("Instant"), WHITE("Border"), FILM("Film scan") }

/** Optical / film-structure effects rendered on the GPU after the colour LUT. All 0..1. */
data class Effects(
    val grain: Float = 0f,
    val grainSize: Float = 0.35f,
    val colorGrain: Float = 0.35f,
    val halation: Float = 0f,
    val bloom: Float = 0f,
    val vignette: Float = 0f,
    val aberration: Float = 0f,
    val leak: Float = 0f,
    val dateStamp: Boolean = false,
    val frame: FrameStyle = FrameStyle.NONE,
)

data class FilmProfile(
    val id: String,
    val name: String,
    val code: String,
    val category: Category?,
    val blurb: String,
    val look: Look,
    val fx: Effects = Effects(),
) {
    val isMono get() = look.mono != null
    val isIdentity get() = id == "original"
}

object Profiles {

    private val LUMA = Mono(0.2126f, 0.7152f, 0.0722f)

    val ORIGINAL = FilmProfile("original", "Original", "OG", null, "No film. The image as shot.", Look())

    val all: List<FilmProfile> = listOf(
        ORIGINAL,

        // ───────────────────────────── Fujifilm camera simulations ─────────────────────────────
        FilmProfile(
            "provia", "Provia", "STD", Category.FUJIFILM,
            "Standard. Faithful colour with a gentle slide-film punch.",
            Look(
                curve = Curve(contrast = 1.10f, shadows = -0.10f),
                saturation = 1.06f,
                bands = listOf(
                    Band(Hue.SKY, 45f, shift = -3f, sat = 1.06f),
                    Band(Hue.FOLIAGE, 35f, shift = -2f, sat = 1.04f),
                    Band(Hue.SKIN, 30f, sat = 0.98f),
                ),
            ),
        ),
        FilmProfile(
            "velvia", "Velvia", "V", Category.FUJIFILM,
            "Vivid. Saturated landscape slide film with deep blacks and rich skies.",
            Look(
                curve = Curve(contrast = 1.30f, shadows = -0.30f, highlights = 0.05f, blacks = -0.2f),
                saturation = 1.26f, vibrance = 0.1f, shadowSat = 1.08f,
                bands = listOf(
                    Band(Hue.RED, 35f, shift = -4f, sat = 1.12f, light = -0.02f),
                    Band(Hue.SKIN, 22f, sat = 0.94f),
                    Band(Hue.YELLOW, 30f, sat = 1.10f),
                    Band(Hue.FOLIAGE, 40f, shift = 6f, sat = 1.18f, light = -0.03f),
                    Band(Hue.SKY, 45f, shift = 10f, sat = 1.14f, light = -0.10f),
                ),
            ),
        ),
        FilmProfile(
            "astia", "Astia", "S", Category.FUJIFILM,
            "Soft. Gentle tonality for skin, while keeping skies vivid.",
            Look(
                curve = Curve(contrast = 1.02f, shadows = 0.08f, highlights = -0.10f),
                saturation = 1.07f,
                bands = listOf(
                    Band(Hue.SKIN, 30f, sat = 0.92f, light = 0.02f),
                    Band(Hue.SKY, 45f, sat = 1.15f, light = -0.02f),
                    Band(Hue.FOLIAGE, 40f, sat = 1.05f),
                    Band(Hue.RED, 25f, sat = 1.04f),
                ),
            ),
        ),
        FilmProfile(
            "classic_chrome", "Classic Chrome", "CC", Category.FUJIFILM,
            "Documentary slide look. Muted colour, hard shadows, deep teal skies.",
            Look(
                curve = Curve(contrast = 1.12f, shadows = -0.28f, highlights = -0.06f, blacks = -0.1f),
                rgbCurveMix = 0.5f, saturation = 0.80f, highlightSat = 0.85f,
                bands = listOf(
                    Band(Hue.RED, 30f, shift = 2f, sat = 0.85f, light = -0.01f),
                    Band(Hue.SKIN, 25f, sat = 0.96f),
                    Band(Hue.YELLOW, 30f, shift = -6f, sat = 0.78f),
                    Band(Hue.FOLIAGE, 40f, shift = -10f, sat = 0.72f, light = -0.02f),
                    Band(Hue.SKY, 50f, shift = -8f, sat = 0.88f, light = -0.07f),
                ),
                shadowTone = Tone(205f, 0.012f), highlightTone = Tone(80f, 0.010f),
            ),
        ),
        FilmProfile(
            "classic_neg", "Classic Neg", "NC", Category.FUJIFILM,
            "Superia-inspired. Hard contrast, teal shadows, warm highlights, magenta reds.",
            Look(
                curve = Curve(contrast = 1.20f, shadows = -0.32f, highlights = -0.04f, whites = -0.15f),
                rgbCurveMix = 0.85f, saturation = 0.98f, highlightSat = 0.9f,
                bands = listOf(
                    Band(Hue.RED, 30f, shift = -5f, sat = 1.05f, light = -0.02f),
                    Band(Hue.SKIN, 25f, shift = -3f),
                    Band(Hue.YELLOW, 30f, shift = -8f, sat = 0.85f),
                    Band(Hue.FOLIAGE, 40f, shift = 16f, sat = 0.80f, light = -0.02f),
                    Band(Hue.SKY, 50f, shift = -10f, sat = 0.92f, light = -0.03f),
                ),
                shadowTone = Tone(175f, 0.028f), highlightTone = Tone(65f, 0.018f),
                black = Rgb(0f, 0.012f, 0.02f),
            ),
        ),
        FilmProfile(
            "nostalgic_neg", "Nostalgic Neg", "NN", Category.FUJIFILM,
            "70s American colour. Amber highlights, soft roll-off, rich shadows.",
            Look(
                temp = 0.06f,
                curve = Curve(contrast = 1.06f, shadows = -0.08f, highlights = -0.18f, whites = -0.2f),
                saturation = 1.04f,
                bands = listOf(
                    Band(Hue.RED, 30f, sat = 1.08f),
                    Band(Hue.SKIN, 30f, shift = 3f, sat = 1.04f),
                    Band(Hue.YELLOW, 30f, shift = -5f, sat = 1.05f),
                    Band(Hue.FOLIAGE, 40f, shift = -6f, sat = 0.90f),
                    Band(Hue.SKY, 45f, shift = -4f, sat = 0.92f),
                ),
                highlightTone = Tone(72f, 0.040f), midTone = Tone(60f, 0.010f),
            ),
        ),
        FilmProfile(
            "reala_ace", "Reala Ace", "RA", Category.FUJIFILM,
            "Faithful colour with a crisp, hard tonality.",
            Look(
                curve = Curve(contrast = 1.16f, shadows = -0.16f, highlights = 0.02f),
                saturation = 1.04f,
                bands = listOf(
                    Band(Hue.SKIN, 30f, sat = 0.97f),
                    Band(Hue.FOLIAGE, 40f, shift = 2f, sat = 1.03f),
                    Band(Hue.SKY, 45f, sat = 1.04f),
                ),
            ),
        ),
        FilmProfile(
            "pro_neg_hi", "Pro Neg Hi", "NH", Category.FUJIFILM,
            "Portrait negative with a little extra contrast.",
            Look(
                curve = Curve(contrast = 1.12f, shadows = -0.12f, highlights = -0.04f),
                saturation = 0.94f,
                bands = listOf(Band(Hue.SKIN, 30f, sat = 0.95f, light = 0.01f)),
            ),
        ),
        FilmProfile(
            "pro_neg_std", "Pro Neg Std", "NS", Category.FUJIFILM,
            "Soft studio negative. Low contrast, natural, flattering skin.",
            Look(
                curve = Curve(contrast = 0.94f, shadows = 0.06f, highlights = -0.08f),
                saturation = 0.86f,
                bands = listOf(
                    Band(Hue.SKIN, 30f, sat = 0.95f, light = 0.015f),
                    Band(Hue.SKY, 45f, sat = 0.90f),
                ),
            ),
        ),
        FilmProfile(
            "eterna", "Eterna", "ET", Category.FUJIFILM,
            "Motion-picture stock. Flat, low saturation, teal shadows.",
            Look(
                curve = Curve(contrast = 0.86f, shadows = 0.14f, highlights = -0.16f, whites = -0.15f),
                rgbCurveMix = 0.4f, saturation = 0.70f,
                bands = listOf(
                    Band(Hue.SKIN, 30f, sat = 1.12f),
                    Band(Hue.FOLIAGE, 40f, shift = 10f, sat = 0.85f),
                    Band(Hue.SKY, 50f, shift = -6f, sat = 0.95f),
                    Band(Hue.RED, 25f, sat = 0.90f),
                ),
                shadowTone = Tone(200f, 0.018f), highlightTone = Tone(80f, 0.008f),
                black = Rgb.gray(0.02f),
            ),
        ),
        FilmProfile(
            "bleach_bypass", "Eterna Bleach", "EB", Category.FUJIFILM,
            "Skipped-bleach cinema process. Silver-retained, harsh and desaturated.",
            Look(
                curve = Curve(contrast = 1.38f, shadows = -0.30f, highlights = 0.12f),
                rgbCurveMix = 0.6f, saturation = 0.42f, highlightSat = 0.7f,
                shadowTone = Tone(210f, 0.008f),
            ),
        ),
        FilmProfile(
            "acros", "Acros", "A", Category.FUJIFILM,
            "Fine-grain black & white with rich, deep tonality.",
            Look(mono = Mono(0.30f, 0.58f, 0.12f), curve = Curve(contrast = 1.22f, shadows = -0.18f, highlights = 0.06f, blacks = -0.1f)),
            Effects(grain = 0.22f, grainSize = 0.2f),
        ),
        FilmProfile(
            "acros_r", "Acros + R", "A+R", Category.FUJIFILM,
            "Acros through a red filter. Dark dramatic skies, bright skin.",
            Look(mono = Mono(0.62f, 0.33f, 0.05f), curve = Curve(contrast = 1.24f, shadows = -0.18f, highlights = 0.06f, blacks = -0.1f)),
            Effects(grain = 0.22f, grainSize = 0.2f),
        ),
        FilmProfile(
            "acros_ye", "Acros + Ye", "A+Y", Category.FUJIFILM,
            "Acros through a yellow filter. Gently deepened skies.",
            Look(mono = Mono(0.42f, 0.50f, 0.08f), curve = Curve(contrast = 1.22f, shadows = -0.18f, highlights = 0.06f, blacks = -0.1f)),
            Effects(grain = 0.22f, grainSize = 0.2f),
        ),
        FilmProfile(
            "acros_g", "Acros + G", "A+G", Category.FUJIFILM,
            "Acros through a green filter. Smooth skin, lighter foliage.",
            Look(mono = Mono(0.22f, 0.70f, 0.08f), curve = Curve(contrast = 1.22f, shadows = -0.18f, highlights = 0.06f, blacks = -0.1f)),
            Effects(grain = 0.22f, grainSize = 0.2f),
        ),
        FilmProfile(
            "sepia", "Sepia", "SEP", Category.FUJIFILM,
            "Warm brown-toned monochrome.",
            Look(
                mono = LUMA, curve = Curve(contrast = 0.98f, shadows = 0.04f, highlights = -0.06f),
                shadowTone = Tone(55f, 0.03f), midTone = Tone(70f, 0.035f), highlightTone = Tone(80f, 0.02f),
                black = Rgb(0.04f, 0.03f, 0.02f),
            ),
        ),

        // ───────────────────────────── Ricoh GR image controls ─────────────────────────────
        FilmProfile(
            "gr_standard", "GR Standard", "STD", Category.RICOH,
            "The GR's clean, crisp default rendering.",
            Look(curve = Curve(contrast = 1.08f, shadows = -0.08f), saturation = 1.05f),
        ),
        FilmProfile(
            "gr_positive", "Positive Film", "PF", Category.RICOH,
            "Slide film punch. Warm, saturated, contrasty with yellow-tinted highlights.",
            Look(
                temp = 0.04f,
                curve = Curve(contrast = 1.24f, shadows = -0.24f, highlights = 0.06f),
                saturation = 1.18f,
                bands = listOf(
                    Band(Hue.RED, 30f, sat = 1.12f),
                    Band(Hue.YELLOW, 30f, shift = -5f, sat = 1.15f),
                    Band(Hue.FOLIAGE, 40f, shift = -4f, sat = 1.08f),
                    Band(Hue.SKY, 45f, shift = 4f, sat = 1.16f, light = -0.05f),
                    Band(Hue.SKIN, 25f, sat = 0.98f),
                ),
                highlightTone = Tone(95f, 0.018f), shadowTone = Tone(250f, 0.008f),
            ),
        ),
        FilmProfile(
            "gr_negative", "Negative Film", "NF", Category.RICOH,
            "Low-contrast print look. Faded cyan blacks, muted colour.",
            Look(
                curve = Curve(contrast = 0.90f, shadows = 0.10f, highlights = -0.12f, whites = -0.1f),
                rgbCurveMix = 0.6f, saturation = 0.80f,
                bands = listOf(
                    Band(Hue.FOLIAGE, 40f, shift = 12f, sat = 0.82f),
                    Band(Hue.SKY, 50f, shift = -10f, sat = 0.85f),
                    Band(Hue.RED, 30f, sat = 0.88f),
                    Band(Hue.SKIN, 25f, shift = 2f, sat = 0.95f),
                ),
                shadowTone = Tone(190f, 0.030f), highlightTone = Tone(75f, 0.014f),
                black = Rgb(0.035f, 0.055f, 0.06f), white = Rgb(0.98f, 0.97f, 0.94f),
            ),
            Effects(grain = 0.12f, grainSize = 0.3f),
        ),
        FilmProfile(
            "gr_hicon_bw", "Hi-Contrast B&W", "HC", Category.RICOH,
            "The GR street classic. Gritty, crushed and graphic.",
            Look(mono = Mono(0.33f, 0.52f, 0.15f), curve = Curve(contrast = 1.8f, shadows = -0.35f, highlights = 0.25f, blacks = -0.3f)),
            Effects(grain = 0.55f, grainSize = 0.65f, vignette = 0.35f),
        ),
        FilmProfile(
            "gr_bleach", "Bleach Bypass", "BB", Category.RICOH,
            "Desaturated, metallic and contrasty.",
            Look(
                curve = Curve(contrast = 1.35f, shadows = -0.22f, highlights = 0.12f),
                saturation = 0.5f, highlightSat = 0.75f,
                shadowTone = Tone(190f, 0.01f),
            ),
        ),
        FilmProfile(
            "gr_retro", "Retro", "RT", Category.RICOH,
            "Faded and warm, like an old print in a shoebox.",
            Look(
                temp = 0.12f,
                curve = Curve(contrast = 0.92f, shadows = 0.08f, highlights = -0.10f),
                saturation = 0.78f,
                highlightTone = Tone(85f, 0.04f), shadowTone = Tone(45f, 0.02f),
                black = Rgb(0.07f, 0.055f, 0.04f), white = Rgb(0.98f, 0.95f, 0.86f),
            ),
            Effects(grain = 0.2f, vignette = 0.2f),
        ),
        FilmProfile(
            "gr_cross", "Cross Process", "XP", Category.RICOH,
            "Slide film in negative chemistry. Yellow-green highs, blue shadows.",
            Look(
                curve = Curve(contrast = 1.15f), rgbCurveMix = 1f,
                red = Curve(contrast = 1.25f, highlights = 0.08f),
                green = Curve(contrast = 1.12f, highlights = 0.12f, shadows = -0.05f),
                blue = Curve(contrast = 0.72f, shadows = 0.05f),
                saturation = 1.18f,
                black = Rgb(0f, 0.02f, 0.12f), white = Rgb(1f, 1f, 0.80f),
            ),
        ),
        FilmProfile(
            "gr_hikey", "Hi-Key", "HK", Category.RICOH,
            "Bright, airy and soft.",
            Look(exposure = 0.45f, temp = -0.04f, curve = Curve(contrast = 0.92f, shadows = 0.18f, highlights = 0.10f), saturation = 0.86f),
        ),

        // ───────────────────────────── Kodak ─────────────────────────────
        FilmProfile(
            "portra400", "Portra 400", "P400", Category.KODAK,
            "The portrait standard. Warm skin, soft contrast, pastel greens.",
            Look(
                temp = 0.06f,
                curve = Curve(contrast = 0.92f, shadows = 0.06f, highlights = -0.14f, whites = -0.12f),
                rgbCurveMix = 0.55f, saturation = 0.94f,
                bands = listOf(
                    Band(Hue.RED, 30f, shift = 4f, sat = 0.96f),
                    Band(Hue.SKIN, 30f, shift = 2f, sat = 1.03f, light = 0.015f),
                    Band(Hue.YELLOW, 30f, shift = -4f, sat = 0.95f),
                    Band(Hue.FOLIAGE, 40f, shift = -6f, sat = 0.84f),
                    Band(Hue.SKY, 50f, shift = -3f, sat = 0.90f),
                ),
                shadowTone = Tone(205f, 0.012f), highlightTone = Tone(70f, 0.018f),
                black = Rgb.gray(0.015f),
            ),
            Effects(grain = 0.22f, grainSize = 0.35f, halation = 0.08f),
        ),
        FilmProfile(
            "portra160", "Portra 160", "P160", Category.KODAK,
            "Finer and cooler than 400. Clean pastel daylight.",
            Look(
                temp = 0.02f,
                curve = Curve(contrast = 0.90f, shadows = 0.06f, highlights = -0.12f, whites = -0.1f),
                rgbCurveMix = 0.55f, saturation = 0.88f,
                bands = listOf(
                    Band(Hue.SKIN, 30f, shift = 1f, sat = 1.0f, light = 0.015f),
                    Band(Hue.FOLIAGE, 40f, shift = -2f, sat = 0.82f),
                    Band(Hue.SKY, 50f, shift = -4f, sat = 0.90f),
                ),
                shadowTone = Tone(200f, 0.012f), highlightTone = Tone(70f, 0.010f),
                black = Rgb.gray(0.012f),
            ),
            Effects(grain = 0.14f, grainSize = 0.25f, halation = 0.06f),
        ),
        FilmProfile(
            "portra800", "Portra 800", "P800", Category.KODAK,
            "Pushed portrait stock. Warmer, richer and grainier.",
            Look(
                temp = 0.08f,
                curve = Curve(contrast = 1.0f, shadows = -0.04f, highlights = -0.10f, whites = -0.1f),
                rgbCurveMix = 0.6f, saturation = 1.02f,
                bands = listOf(
                    Band(Hue.SKIN, 30f, shift = 3f, sat = 1.05f),
                    Band(Hue.RED, 30f, shift = 3f, sat = 1.04f),
                    Band(Hue.FOLIAGE, 40f, shift = -6f, sat = 0.88f),
                    Band(Hue.SKY, 50f, shift = -6f, sat = 0.92f),
                ),
                shadowTone = Tone(205f, 0.014f), highlightTone = Tone(68f, 0.022f),
                black = Rgb.gray(0.02f),
            ),
            Effects(grain = 0.34f, grainSize = 0.45f, halation = 0.12f),
        ),
        FilmProfile(
            "ektar100", "Ektar 100", "E100", Category.KODAK,
            "The world's finest-grain colour negative. Ultra-saturated and punchy.",
            Look(
                curve = Curve(contrast = 1.16f, shadows = -0.14f, highlights = 0.04f),
                saturation = 1.26f,
                bands = listOf(
                    Band(Hue.RED, 30f, shift = -3f, sat = 1.15f),
                    Band(Hue.SKIN, 25f, shift = -4f, sat = 1.02f),
                    Band(Hue.YELLOW, 30f, sat = 1.06f),
                    Band(Hue.FOLIAGE, 40f, shift = 4f, sat = 1.10f),
                    Band(Hue.SKY, 50f, shift = 4f, sat = 1.22f, light = -0.05f),
                ),
                highlightTone = Tone(240f, 0.005f),
            ),
            Effects(grain = 0.10f, grainSize = 0.2f, halation = 0.05f),
        ),
        FilmProfile(
            "gold200", "Gold 200", "G200", Category.KODAK,
            "Golden-hour in a box. Warm yellows and nostalgic sunshine.",
            Look(
                temp = 0.12f,
                curve = Curve(contrast = 1.06f, shadows = -0.04f, highlights = -0.06f),
                saturation = 1.08f,
                bands = listOf(
                    Band(Hue.YELLOW, 35f, shift = -6f, sat = 1.15f),
                    Band(Hue.SKIN, 30f, shift = 3f, sat = 1.04f),
                    Band(Hue.FOLIAGE, 40f, shift = -10f, sat = 0.92f),
                    Band(Hue.SKY, 50f, shift = -4f, sat = 0.90f),
                    Band(Hue.RED, 30f, shift = 4f, sat = 1.04f),
                ),
                highlightTone = Tone(85f, 0.03f), midTone = Tone(70f, 0.012f),
                black = Rgb(0.02f, 0.015f, 0.01f),
            ),
            Effects(grain = 0.28f, grainSize = 0.42f, halation = 0.08f),
        ),
        FilmProfile(
            "colorplus200", "ColorPlus 200", "CP", Category.KODAK,
            "Budget everyday film. Warm, slightly soft, honest.",
            Look(
                temp = 0.10f,
                curve = Curve(contrast = 1.0f, shadows = 0.02f, highlights = -0.08f),
                saturation = 1.0f,
                bands = listOf(
                    Band(Hue.FOLIAGE, 40f, shift = -8f, sat = 0.90f),
                    Band(Hue.SKY, 50f, sat = 0.90f),
                ),
                highlightTone = Tone(80f, 0.03f), shadowTone = Tone(30f, 0.012f),
            ),
            Effects(grain = 0.32f, grainSize = 0.45f, halation = 0.08f),
        ),
        FilmProfile(
            "ultramax400", "UltraMax 400", "UM", Category.KODAK,
            "Consumer all-rounder. Saturated reds and warm daylight.",
            Look(
                temp = 0.07f,
                curve = Curve(contrast = 1.10f, shadows = -0.08f, highlights = -0.04f),
                saturation = 1.14f,
                bands = listOf(
                    Band(Hue.RED, 30f, sat = 1.10f),
                    Band(Hue.YELLOW, 30f, sat = 1.10f),
                    Band(Hue.SKY, 50f, sat = 1.06f),
                ),
                highlightTone = Tone(85f, 0.015f),
            ),
            Effects(grain = 0.32f, grainSize = 0.42f, halation = 0.08f),
        ),
        FilmProfile(
            "kodachrome64", "Kodachrome 64", "KR", Category.KODAK,
            "The legendary slide film. Deep reds, dense blacks, timeless warmth.",
            Look(
                temp = 0.05f,
                curve = Curve(contrast = 1.28f, shadows = -0.30f, blacks = -0.25f),
                rgbCurveMix = 0.85f, saturation = 1.12f, highlightSat = 0.88f,
                bands = listOf(
                    Band(Hue.RED, 30f, shift = -2f, sat = 1.18f, light = -0.03f),
                    Band(Hue.SKIN, 25f, shift = 2f),
                    Band(Hue.YELLOW, 30f, shift = -6f, sat = 1.10f),
                    Band(Hue.FOLIAGE, 40f, shift = -6f, sat = 0.95f, light = -0.04f),
                    Band(Hue.SKY, 50f, shift = -2f, sat = 1.12f, light = -0.07f),
                ),
                highlightTone = Tone(80f, 0.012f), shadowTone = Tone(30f, 0.008f),
            ),
            Effects(grain = 0.14f, grainSize = 0.25f),
        ),
        FilmProfile(
            "ektachrome", "Ektachrome E100", "E6", Category.KODAK,
            "Modern slide film. Clean, cool and vivid blues.",
            Look(
                temp = -0.05f,
                curve = Curve(contrast = 1.20f, shadows = -0.18f, highlights = 0.04f),
                saturation = 1.14f,
                bands = listOf(
                    Band(Hue.SKY, 50f, sat = 1.20f, light = -0.03f),
                    Band(Hue.FOLIAGE, 40f, shift = 4f, sat = 1.08f),
                    Band(Hue.SKIN, 25f, sat = 0.97f),
                ),
                shadowTone = Tone(240f, 0.008f),
            ),
            Effects(grain = 0.10f, grainSize = 0.2f),
        ),
        FilmProfile(
            "trix400", "Tri-X 400", "TX", Category.KODAK,
            "The photojournalist's B&W. Punchy contrast, honest grain.",
            Look(mono = Mono(0.30f, 0.56f, 0.14f), curve = Curve(contrast = 1.32f, shadows = -0.20f, highlights = 0.10f, blacks = -0.15f)),
            Effects(grain = 0.45f, grainSize = 0.5f, vignette = 0.12f),
        ),
        FilmProfile(
            "tmax100", "T-Max 100", "TMX", Category.KODAK,
            "Tabular-grain B&W. Smooth, sharp and modern.",
            Look(mono = Mono(0.28f, 0.58f, 0.14f), curve = Curve(contrast = 1.14f, shadows = -0.10f, highlights = 0.04f)),
            Effects(grain = 0.14f, grainSize = 0.2f),
        ),

        // ───────────────────────────── Fuji film stocks ─────────────────────────────
        FilmProfile(
            "superia400", "Superia 400", "S400", Category.FUJI_STOCK,
            "Fuji's consumer classic. Cool greens and that tell-tale cyan shadow.",
            Look(
                temp = -0.03f, tint = -0.03f,
                curve = Curve(contrast = 1.10f, shadows = -0.10f, highlights = -0.04f),
                saturation = 1.08f,
                bands = listOf(
                    Band(Hue.FOLIAGE, 40f, shift = 8f, sat = 1.12f),
                    Band(Hue.SKY, 50f, shift = -6f, sat = 1.02f),
                    Band(Hue.RED, 30f, shift = -4f, sat = 1.05f),
                    Band(Hue.SKIN, 25f, sat = 0.98f),
                ),
                shadowTone = Tone(160f, 0.022f), highlightTone = Tone(95f, 0.008f),
            ),
            Effects(grain = 0.32f, grainSize = 0.42f, halation = 0.08f),
        ),
        FilmProfile(
            "pro400h", "Pro 400H", "400H", Category.FUJI_STOCK,
            "Wedding-photographer favourite. Airy, minty greens, soft pastel skin.",
            Look(
                exposure = 0.25f,
                curve = Curve(contrast = 0.88f, shadows = 0.10f, highlights = -0.06f, whites = -0.1f),
                rgbCurveMix = 0.5f, saturation = 0.86f,
                bands = listOf(
                    Band(Hue.FOLIAGE, 45f, shift = 16f, sat = 0.82f, light = 0.03f),
                    Band(Hue.SKY, 50f, shift = -10f, sat = 0.90f, light = 0.02f),
                    Band(Hue.SKIN, 30f, shift = -3f, sat = 0.92f, light = 0.02f),
                    Band(Hue.YELLOW, 30f, sat = 0.88f),
                ),
                shadowTone = Tone(195f, 0.018f), highlightTone = Tone(160f, 0.006f),
                black = Rgb(0.03f, 0.035f, 0.04f),
            ),
            Effects(grain = 0.20f, grainSize = 0.35f, halation = 0.06f),
        ),
        FilmProfile(
            "c200", "Fujicolor C200", "C200", Category.FUJI_STOCK,
            "Cheap and cheerful. Slightly green, a little grainy, very lovable.",
            Look(
                tint = -0.04f,
                curve = Curve(contrast = 1.05f, shadows = -0.06f, highlights = -0.04f),
                bands = listOf(
                    Band(Hue.FOLIAGE, 40f, shift = 6f, sat = 1.08f),
                    Band(Hue.SKY, 50f, shift = -6f, sat = 0.95f),
                    Band(Hue.RED, 30f, sat = 0.96f),
                ),
                shadowTone = Tone(170f, 0.02f), highlightTone = Tone(90f, 0.012f),
            ),
            Effects(grain = 0.30f, grainSize = 0.4f, halation = 0.08f),
        ),

        // ───────────────────────────── Cinema ─────────────────────────────
        FilmProfile(
            "cinestill800t", "CineStill 800T", "800T", Category.CINEMA,
            "Tungsten cine film without remjet. Cool cast and glowing red halation.",
            Look(
                temp = -0.36f, tint = 0.05f,
                curve = Curve(contrast = 1.05f, shadows = -0.04f, highlights = -0.08f),
                saturation = 1.04f,
                bands = listOf(
                    Band(Hue.SKIN, 30f, shift = -4f),
                    Band(Hue.RED, 30f, shift = -4f, sat = 1.10f),
                    Band(Hue.FOLIAGE, 40f, shift = 14f, sat = 0.85f),
                    Band(Hue.SKY, 50f, shift = -8f),
                ),
                shadowTone = Tone(200f, 0.04f), midTone = Tone(215f, 0.012f), highlightTone = Tone(20f, 0.012f),
            ),
            Effects(grain = 0.36f, grainSize = 0.45f, halation = 0.65f, bloom = 0.08f),
        ),
        FilmProfile(
            "cinestill50d", "CineStill 50D", "50D", Category.CINEMA,
            "Daylight cine stock. Clean, crisp and saturated with a soft glow.",
            Look(
                temp = -0.03f,
                curve = Curve(contrast = 1.08f, shadows = -0.06f, highlights = -0.04f),
                saturation = 1.08f,
                bands = listOf(Band(Hue.SKY, 50f, sat = 1.10f)),
                shadowTone = Tone(210f, 0.01f), highlightTone = Tone(70f, 0.01f),
            ),
            Effects(grain = 0.12f, grainSize = 0.25f, halation = 0.35f),
        ),
        FilmProfile(
            "vision250d", "Vision3 250D", "250D", Category.CINEMA,
            "Hollywood daylight negative. Wide latitude, natural skin, gentle teal.",
            Look(
                curve = Curve(contrast = 0.90f, shadows = 0.08f, highlights = -0.12f, whites = -0.1f),
                rgbCurveMix = 0.5f, saturation = 0.90f,
                bands = listOf(
                    Band(Hue.SKIN, 30f, shift = 2f, sat = 1.05f),
                    Band(Hue.FOLIAGE, 40f, shift = 6f, sat = 0.85f),
                ),
                shadowTone = Tone(195f, 0.018f), highlightTone = Tone(70f, 0.014f),
                black = Rgb.gray(0.015f),
            ),
            Effects(grain = 0.18f, grainSize = 0.3f, halation = 0.15f),
        ),
        FilmProfile(
            "vision500t", "Vision3 500T", "500T", Category.CINEMA,
            "Night-time cine negative. Cool shadows, warm practical lights.",
            Look(
                temp = -0.20f,
                curve = Curve(contrast = 0.94f, shadows = 0.04f, highlights = -0.10f),
                saturation = 0.92f,
                bands = listOf(
                    Band(Hue.FOLIAGE, 40f, shift = 14f, sat = 0.80f),
                    Band(Hue.SKIN, 30f),
                ),
                shadowTone = Tone(205f, 0.028f), highlightTone = Tone(55f, 0.02f),
            ),
            Effects(grain = 0.30f, grainSize = 0.4f, halation = 0.30f),
        ),
        FilmProfile(
            "blockbuster", "Blockbuster", "T&O", Category.CINEMA,
            "Teal and orange colour grade. Big, warm skin against cool shadows.",
            Look(
                curve = Curve(contrast = 1.12f, shadows = -0.10f, highlights = -0.04f),
                saturation = 1.0f,
                bands = listOf(
                    Band(Hue.SKIN, 35f, shift = 3f, sat = 1.16f),
                    Band(Hue.FOLIAGE, 50f, shift = 25f, sat = 0.70f),
                    Band(Hue.SKY, 50f, shift = -12f, sat = 1.05f),
                ),
                shadowTone = Tone(200f, 0.045f), highlightTone = Tone(60f, 0.038f),
            ),
            Effects(grain = 0.1f, halation = 0.1f),
        ),

        // ───────────────────────────── Instant ─────────────────────────────
        FilmProfile(
            "polaroid600", "Polaroid 600", "600", Category.INSTANT,
            "Instant integral film. Creamy whites, inky blue-black, dreamy colour.",
            Look(
                exposure = 0.12f, temp = 0.03f,
                curve = Curve(contrast = 0.86f, shadows = 0.08f, highlights = -0.08f, whites = -0.15f),
                rgbCurveMix = 0.5f, saturation = 0.80f,
                bands = listOf(
                    Band(Hue.FOLIAGE, 40f, shift = 10f, sat = 0.85f),
                    Band(Hue.SKY, 50f, shift = -8f, sat = 0.85f),
                    Band(Hue.RED, 30f, shift = 4f, sat = 0.92f),
                ),
                shadowTone = Tone(215f, 0.028f), highlightTone = Tone(90f, 0.02f),
                black = Rgb(0.07f, 0.075f, 0.10f), white = Rgb(0.97f, 0.95f, 0.88f),
            ),
            Effects(grain = 0.12f, bloom = 0.15f, vignette = 0.28f, frame = FrameStyle.INSTANT),
        ),
        FilmProfile(
            "sx70", "SX-70", "SX", Category.INSTANT,
            "Vintage instant. Warm, washed-out and soft-focus.",
            Look(
                temp = 0.14f,
                curve = Curve(contrast = 0.82f, shadows = 0.10f, highlights = -0.12f, whites = -0.2f),
                rgbCurveMix = 0.4f, saturation = 0.66f,
                bands = listOf(
                    Band(Hue.FOLIAGE, 40f, shift = -10f, sat = 0.80f),
                    Band(Hue.SKY, 50f, sat = 0.75f),
                ),
                highlightTone = Tone(75f, 0.03f), shadowTone = Tone(40f, 0.02f),
                black = Rgb(0.10f, 0.08f, 0.07f), white = Rgb(0.96f, 0.92f, 0.82f),
            ),
            Effects(grain = 0.12f, bloom = 0.25f, vignette = 0.35f, aberration = 0.15f, frame = FrameStyle.INSTANT),
        ),
        FilmProfile(
            "instax", "Instax Mini", "MINI", Category.INSTANT,
            "Bright, poppy party instant with cool shadows.",
            Look(
                exposure = 0.18f, temp = -0.04f,
                curve = Curve(contrast = 1.04f, shadows = 0.04f, highlights = 0.10f),
                saturation = 1.04f,
                bands = listOf(
                    Band(Hue.SKIN, 30f, sat = 0.95f, light = 0.02f),
                    Band(Hue.FOLIAGE, 40f, shift = 8f),
                    Band(Hue.SKY, 50f, sat = 1.06f),
                ),
                shadowTone = Tone(195f, 0.02f),
                black = Rgb(0.03f, 0.04f, 0.05f),
            ),
            Effects(bloom = 0.1f, vignette = 0.15f, frame = FrameStyle.INSTANT),
        ),

        // ───────────────────────────── Camera styles ─────────────────────────────
        FilmProfile(
            "disposable", "Disposable", "QS", Category.CAMERAS,
            "Single-use flash camera. Warm, grainy, vignetted, date-stamped.",
            Look(
                temp = 0.08f,
                curve = Curve(contrast = 1.14f, shadows = -0.08f, highlights = 0.08f),
                saturation = 1.12f,
                bands = listOf(
                    Band(Hue.FOLIAGE, 40f, shift = -6f),
                    Band(Hue.SKIN, 30f, sat = 1.06f),
                    Band(Hue.YELLOW, 30f, sat = 1.10f),
                ),
                highlightTone = Tone(85f, 0.02f), shadowTone = Tone(160f, 0.012f),
                black = Rgb(0.02f, 0.02f, 0.03f),
            ),
            Effects(grain = 0.38f, grainSize = 0.5f, vignette = 0.45f, aberration = 0.35f, bloom = 0.1f, dateStamp = true),
        ),
        FilmProfile(
            "ccd", "CCD Digicam", "CCD", Category.CAMERAS,
            "Y2K compact digital. Cool, punchy, bright highlights with a CCD glow.",
            Look(
                temp = -0.08f,
                curve = Curve(contrast = 1.14f, shadows = -0.06f, highlights = 0.14f, whites = 0.1f),
                saturation = 1.18f,
                bands = listOf(
                    Band(Hue.SKY, 50f, shift = 6f, sat = 1.15f),
                    Band(Hue.FOLIAGE, 40f, shift = 6f, sat = 1.12f),
                    Band(Hue.SKIN, 30f, shift = -4f, sat = 1.02f, light = 0.02f),
                    Band(Hue.RED, 30f, sat = 1.10f),
                ),
                shadowTone = Tone(250f, 0.012f),
            ),
            Effects(grain = 0.08f, grainSize = 0.1f, colorGrain = 0.9f, bloom = 0.22f, vignette = 0.12f, aberration = 0.15f, dateStamp = true),
        ),
        FilmProfile(
            "leica", "Leica M", "M", Category.CAMERAS,
            "Rangefinder rendering. Dense blacks, warm mids, restrained greens.",
            Look(
                temp = 0.03f,
                curve = Curve(contrast = 1.18f, shadows = -0.22f, highlights = -0.02f, blacks = -0.15f),
                saturation = 0.98f,
                bands = listOf(
                    Band(Hue.RED, 30f, shift = -2f, sat = 1.10f, light = -0.01f),
                    Band(Hue.SKIN, 25f, shift = 2f),
                    Band(Hue.FOLIAGE, 40f, shift = -4f, sat = 0.86f, light = -0.03f),
                    Band(Hue.SKY, 50f, shift = -4f, sat = 0.96f, light = -0.03f),
                ),
                highlightTone = Tone(75f, 0.008f),
            ),
            Effects(grain = 0.06f, vignette = 0.22f),
        ),
        FilmProfile(
            "hasselblad", "Hasselblad", "HNCS", Category.CAMERAS,
            "Natural colour solution. Smooth gradation and true-to-life skin.",
            Look(
                curve = Curve(contrast = 1.05f, shadows = -0.04f, highlights = -0.06f),
                saturation = 1.02f,
                bands = listOf(
                    Band(Hue.SKIN, 30f, sat = 0.98f, light = 0.01f),
                    Band(Hue.FOLIAGE, 40f, shift = -2f),
                ),
            ),
        ),
        FilmProfile(
            "contax_t2", "Contax T2", "T2", Category.CAMERAS,
            "Premium point-and-shoot on colour neg. Warm, sharp, contrasty.",
            Look(
                temp = 0.08f,
                curve = Curve(contrast = 1.16f, shadows = -0.16f, highlights = 0.02f),
                saturation = 1.08f,
                bands = listOf(
                    Band(Hue.SKIN, 30f, shift = 2f, sat = 1.04f),
                    Band(Hue.YELLOW, 30f, shift = -4f, sat = 1.08f),
                    Band(Hue.FOLIAGE, 40f, shift = -6f, sat = 0.95f),
                    Band(Hue.SKY, 50f, sat = 1.05f, light = -0.03f),
                ),
                highlightTone = Tone(80f, 0.02f),
            ),
            Effects(grain = 0.24f, grainSize = 0.38f, vignette = 0.2f, halation = 0.08f),
        ),
        FilmProfile(
            "lca", "Lomo LC-A", "LCA", Category.CAMERAS,
            "Shoot from the hip. Saturated, contrasty, heavy vignette.",
            Look(
                curve = Curve(contrast = 1.32f, shadows = -0.25f, highlights = 0.10f),
                saturation = 1.30f,
                green = Curve(highlights = 0.06f), blue = Curve(contrast = 0.9f, shadows = 0.06f),
                bands = listOf(
                    Band(Hue.SKY, 50f, shift = 8f, sat = 1.20f),
                    Band(Hue.FOLIAGE, 40f, shift = 6f, sat = 1.15f),
                ),
                highlightTone = Tone(100f, 0.02f), shadowTone = Tone(260f, 0.02f),
            ),
            Effects(grain = 0.30f, grainSize = 0.45f, vignette = 0.7f, aberration = 0.3f),
        ),
        FilmProfile(
            "toycam", "Toy Camera", "TOY", Category.CAMERAS,
            "Plastic lens, leaky body. Dreamy glow, soft corners, light leaks.",
            Look(
                temp = 0.06f,
                curve = Curve(contrast = 1.2f, shadows = -0.18f, highlights = 0.06f),
                saturation = 1.15f,
                highlightTone = Tone(80f, 0.02f), shadowTone = Tone(200f, 0.015f),
            ),
            Effects(grain = 0.30f, grainSize = 0.5f, vignette = 0.8f, bloom = 0.25f, aberration = 0.6f, leak = 0.45f),
        ),

        // ───────────────────────────── Ilford B&W ─────────────────────────────
        FilmProfile(
            "hp5", "HP5 Plus 400", "HP5", Category.ILFORD,
            "Versatile, forgiving B&W with classic medium grain.",
            Look(mono = Mono(0.29f, 0.58f, 0.13f), curve = Curve(contrast = 1.16f, shadows = -0.10f, highlights = 0.06f)),
            Effects(grain = 0.42f, grainSize = 0.48f),
        ),
        FilmProfile(
            "fp4", "FP4 Plus 125", "FP4", Category.ILFORD,
            "Fine-grain, medium-contrast B&W with lovely mid-tones.",
            Look(mono = Mono(0.28f, 0.60f, 0.12f), curve = Curve(contrast = 1.18f, shadows = -0.12f, highlights = 0.04f)),
            Effects(grain = 0.20f, grainSize = 0.3f),
        ),
        FilmProfile(
            "panf50", "Pan F Plus 50", "PANF", Category.ILFORD,
            "Slow, razor-fine B&W. Deep blacks and luminous highlights.",
            Look(mono = Mono(0.32f, 0.58f, 0.10f), curve = Curve(contrast = 1.36f, shadows = -0.22f, highlights = 0.08f, blacks = -0.2f)),
            Effects(grain = 0.08f, grainSize = 0.15f),
        ),
        FilmProfile(
            "delta3200", "Delta 3200", "D32", Category.ILFORD,
            "Ultra-fast B&W. Bold, gritty grain for low light.",
            Look(mono = Mono(0.30f, 0.56f, 0.14f), curve = Curve(contrast = 1.22f, shadows = -0.04f, highlights = 0.10f), black = Rgb.gray(0.03f)),
            Effects(grain = 0.75f, grainSize = 0.75f),
        ),
    )

    private val byId = all.associateBy { it.id }
    fun byId(id: String): FilmProfile = byId[id] ?: ORIGINAL
    fun inCategory(c: Category?): List<FilmProfile> = if (c == null) all else all.filter { it.category == c || it.isIdentity }
}
