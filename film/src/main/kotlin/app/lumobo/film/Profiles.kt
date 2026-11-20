package app.lumobo.film

enum class Category(val label: String) {
    VIBES("Vibes"),
    FUJIFILM("Fujifilm"),
    RICOH("Ricoh GR"),
    IPHONE("iPhone"),
    KODAK("Kodak"),
    FUJI_STOCK("Fuji Film"),
    AGFA("Agfa"),
    CINEMA("Cinema"),
    INSTANT("Instant"),
    CAMERAS("Cameras"),
    LOMOGRAPHY("Lomography"),
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
    val leakStyle: Int = 0,
    val dateStamp: Boolean = false,
    val frame: FrameStyle = FrameStyle.NONE,
    // ── Lens recipe (independent of the film)
    val lens: Int = 0,
    val distortion: Float = 0f,
    val fisheyeCircle: Boolean = false,
    val star: Float = 0f,
    val starPoints: Int = 4,
    val starLength: Float = 0.5f,
    val starAngle: Float = 0f,
    val streak: Float = 0f,
    val tiltShift: Float = 0f,
    val focusY: Float = 0.55f,
    val focusSize: Float = 0.25f,
    val softFocus: Float = 0f,
    val swirl: Float = 0f,
    /** Pro-Mist style diffusion: highlights halo softly into their surroundings. */
    val mist: Float = 0f,
    /** Streak colour: 0 = cool anamorphic blue, 1 = warm amber. */
    val streakWarm: Float = 0f,
    /** How bright a point must be to make a star or streak (0.6 = the classic 1.1 look). */
    val starThreshold: Float = 0.6f,
    /** Diffraction colour along the rays (0 = white, 1 = full rainbow). */
    val starSpectral: Float = 0f,
    /** Areas where stars / streaks are erased: (x, y, radius) triples in source uv. */
    val starMask: List<Float> = emptyList(),
) {
    /** Copy the lens recipe from [o] (lens choices survive film changes and effect resets). */
    fun withLensOf(o: Effects) = copy(
        lens = o.lens, distortion = o.distortion, fisheyeCircle = o.fisheyeCircle, star = o.star,
        starPoints = o.starPoints, starLength = o.starLength, starAngle = o.starAngle, streak = o.streak,
        tiltShift = o.tiltShift, focusY = o.focusY, focusSize = o.focusSize, softFocus = o.softFocus, swirl = o.swirl,
        starMask = o.starMask, starThreshold = o.starThreshold, starSpectral = o.starSpectral,
        mist = o.mist, streakWarm = o.streakWarm,
    )
}

/** One-tap lens recipes. Each sets a group of lens parameters that can then be fine-tuned. */
enum class LensPreset(val label: String, val blurb: String) {
    NONE("None", "No lens effect."),
    FISHEYE("Fisheye", "Ultra-wide fisheye bulge, full frame."),
    CIRCLE("Circle Eye", "Circular fisheye with a black surround."),
    WIDE("Wide 24", "Gentle wide-angle barrel distortion."),
    TWINKLE("Twinkle", "4-point star filter. Makes water and lights sparkle."),
    STARBURST("Starburst", "6-point star filter for sun and street lights."),
    SPARKLE("Sparkle", "Short 8-point glints on every highlight."),
    ANAMORPHIC("Anamorphic", "Cinematic horizontal blue streaks from lights."),
    TILT("Tilt-Shift", "Miniature look with a sharp focus band."),
    SOFT("Soft Focus", "Dreamy diffusion, like a vintage portrait lens."),
    SWIRL("Swirl", "Petzval-style swirling, soft edges."),
    PROMIST("Pro-Mist", "Black Pro-Mist diffusion. Highlights halo softly, contrast eases."),
    GLIMMER("Glimmer", "Mist with fine sparkles, like a glimmer-glass filter."),
    DIFFRACTION("Diffraction", "8-point star that splits light into rainbow rays."),
    SUNBURST("Sunburst", "Long 8-point rays for the sun and bright lamps."),
    HELIOS("Helios 44", "Swirly Soviet 50mm: soft swirl, colour fringing, vignette."),
    HOLGA("Holga", "Plastic toy lens: soft blurry corners and a heavy vignette."),
    VELVET("Velvet", "Dreamy portrait lens: glowing, soft and luminous."),
    PRISM("Prism", "Rainbow light streak from bright lights, like a prism filter."),
    AMBER("Amber Streak", "Warm golden anamorphic flares across the frame."),
    ;

    fun applyTo(fx: Effects): Effects {
        // Reset only the lens recipe.
        val base = fx.withLensOf(Effects(starMask = fx.starMask)).copy(lens = ordinal)
        return when (this) {
            NONE -> base.copy(lens = 0)
            FISHEYE -> base.copy(distortion = 0.85f, vignette = maxOf(fx.vignette, 0.25f))
            CIRCLE -> base.copy(distortion = 1f, fisheyeCircle = true)
            WIDE -> base.copy(distortion = 0.3f)
            TWINKLE -> base.copy(star = 0.7f, starPoints = 4, starLength = 0.55f, starAngle = 0.25f)
            STARBURST -> base.copy(star = 0.6f, starPoints = 6, starLength = 0.45f, starAngle = 0f)
            SPARKLE -> base.copy(star = 0.6f, starPoints = 8, starLength = 0.28f, starAngle = 0.1f)
            ANAMORPHIC -> base.copy(streak = 0.7f)
            TILT -> base.copy(tiltShift = 1f, focusY = 0.55f, focusSize = 0.22f)
            SOFT -> base.copy(softFocus = 0.6f, bloom = maxOf(fx.bloom, 0.3f))
            SWIRL -> base.copy(swirl = 0.8f, vignette = maxOf(fx.vignette, 0.3f))
            PROMIST -> base.copy(mist = 0.55f)
            GLIMMER -> base.copy(mist = 0.35f, star = 0.4f, starPoints = 4, starLength = 0.2f, starAngle = 0.25f, starThreshold = 0.7f)
            DIFFRACTION -> base.copy(star = 0.6f, starPoints = 8, starLength = 0.5f, starAngle = 0.06f, starSpectral = 0.85f)
            SUNBURST -> base.copy(star = 0.65f, starPoints = 8, starLength = 0.95f, starAngle = 0f, starThreshold = 0.7f)
            HELIOS -> base.copy(swirl = 0.55f, softFocus = 0.12f, vignette = maxOf(fx.vignette, 0.3f), aberration = maxOf(fx.aberration, 0.3f))
            HOLGA -> base.copy(distortion = 0.12f, swirl = 0.45f, softFocus = 0.15f, vignette = maxOf(fx.vignette, 0.65f))
            VELVET -> base.copy(softFocus = 0.55f, mist = 0.45f)
            PRISM -> base.copy(streak = 0.6f, starSpectral = 1f)
            AMBER -> base.copy(streak = 0.7f, streakWarm = 1f)
        }
    }
}

/** Light-leak shapes rendered by the shader. */
enum class LeakStyle(val label: String) { AMBER("Amber edge"), CORNER("Red corner"), SUNSET("Sunset top"), BURN("Film burn") }

/**
 * Crop and rotation. [rotation] is in quarter turns clockwise, [angle] is a fine straighten
 * in degrees (the image auto-zooms so no empty corners appear), [flipH]/[flipV] mirror the
 * frame. The crop rect is normalised (0..1) in the displayed image. [aspect] names the preset.
 */
data class Geometry(
    val rotation: Int = 0,
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
    val aspect: String = "original",
    val angle: Float = 0f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val perspV: Float = 0f,
    val perspH: Float = 0f,
) {
    val isIdentity get() = rotation == 0 && left == 0f && top == 0f && right == 1f && bottom == 1f &&
        angle == 0f && !flipH && !flipV && perspV == 0f && perspH == 0f
    val width get() = right - left
    val height get() = bottom - top
    /** Same orientation, straighten, perspective and flips, uncropped. */
    fun uncropped() = Geometry(rotation = rotation, angle = angle, flipH = flipH, flipV = flipV, perspV = perspV, perspH = perspH)

    /** Keystone coefficients in centred, height-normalised display coordinates. */
    fun keystone(dispAspect: Float): Pair<Float, Float> = (perspH * 0.7f / dispAspect) to (-perspV * 0.7f)

    /**
     * Zoom needed so straighten + perspective never reveal empty corners. Shared by the GPU
     * renderer (which maps display -> source with exactly this transform) and the crop UI.
     */
    fun fillScale(dispAspect: Float): Float {
        if (angle == 0f && perspV == 0f && perspH == 0f) return 1f
        val th = Math.toRadians(angle.toDouble())
        val c = kotlin.math.cos(th).toFloat()
        val s = kotlin.math.sin(th).toFloat()
        val (kh, kv) = keystone(dispAspect)
        val hx = dispAspect / 2f
        fun inside(z: Float): Boolean {
            for (sx in floatArrayOf(-1f, 1f)) for (sy in floatArrayOf(-1f, 1f)) {
                val px = sx * hx / z
                val py = sy * 0.5f / z
                val rx = c * px - s * py
                val ry = s * px + c * py
                val w = 1f + kh * rx + kv * ry
                if (w <= 0.05f) return false
                if (kotlin.math.abs(rx / w) > hx + 1e-4f || kotlin.math.abs(ry / w) > 0.5f + 1e-4f) return false
            }
            return true
        }
        var lo = 1f
        var hi = 8f
        if (inside(lo)) return 1f
        repeat(28) {
            val mid = (lo + hi) / 2f
            if (inside(mid)) hi = mid else lo = mid
        }
        return hi
    }
}

/** Strength of the film texture added to every look (1 = each film's own flavour). */
data class CharacterLevels(val grain: Float = 1f, val halation: Float = 1f, val bloom: Float = 1f)

object Character {
    /** The grain / halation / bloom that suits each family of looks. */
    private fun flavour(p: FilmProfile): Effects = when (p.category) {
        Category.VIBES -> Effects(grain = 0.24f, grainSize = 0.40f, halation = 0.20f, bloom = 0.16f)
        Category.IPHONE -> Effects(grain = 0f, grainSize = 0.2f, halation = 0.04f, bloom = 0.05f)
        Category.FUJIFILM -> Effects(grain = 0.18f, grainSize = 0.28f, halation = 0.14f, bloom = 0.10f)
        Category.RICOH -> Effects(grain = 0.22f, grainSize = 0.32f, halation = 0.12f, bloom = 0.08f)
        Category.KODAK, Category.FUJI_STOCK -> Effects(grain = 0.26f, grainSize = 0.40f, halation = 0.22f, bloom = 0.10f)
        Category.CINEMA -> Effects(grain = 0.28f, grainSize = 0.42f, halation = 0.32f, bloom = 0.14f)
        Category.INSTANT -> Effects(grain = 0.14f, grainSize = 0.30f, halation = 0.10f, bloom = 0.24f)
        Category.CAMERAS -> Effects(grain = 0.22f, grainSize = 0.38f, halation = 0.16f, bloom = 0.14f)
        Category.ILFORD -> Effects(grain = 0.34f, grainSize = 0.45f, halation = 0.10f, bloom = 0.06f)
        Category.LOMOGRAPHY -> Effects(grain = 0.28f, grainSize = 0.44f, halation = 0.18f, bloom = 0.12f)
        Category.AGFA -> Effects(grain = 0.26f, grainSize = 0.40f, halation = 0.18f, bloom = 0.10f)
        null -> Effects(grain = 0.16f, grainSize = 0.30f, halation = 0.12f, bloom = 0.08f)
    }

    /**
     * The film's own defaults, topped up with its flavour of grain, halation and bloom
     * when [enabled]; [levels] scale the result (0 = none, 1 = default, 2 = double).
     */
    fun effects(p: FilmProfile, enabled: Boolean, levels: CharacterLevels): Effects {
        // Original stays untouched: film texture and lighting belong to films.
        if (p.isIdentity) return p.fx
        return texture(p, p.fx, enabled, levels)
    }

    private fun texture(p: FilmProfile, base: Effects, enabled: Boolean, levels: CharacterLevels): Effects {
        if (!enabled) return base.copy(
            grain = (p.fx.grain * levels.grain).coerceIn(0f, 1f),
            halation = (p.fx.halation * levels.halation).coerceIn(0f, 1f),
            bloom = (p.fx.bloom * levels.bloom).coerceIn(0f, 1f),
        )
        val f = flavour(p)
        val ownGrain = p.fx.grain > 0f
        return base.copy(
            grain = (maxOf(p.fx.grain, f.grain) * levels.grain).coerceIn(0f, 1f),
            grainSize = if (ownGrain) p.fx.grainSize else f.grainSize,
            halation = (maxOf(p.fx.halation, f.halation) * levels.halation).coerceIn(0f, 1f),
            bloom = (maxOf(p.fx.bloom, f.bloom) * levels.bloom).coerceIn(0f, 1f),
        )
    }
}

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

        // ───────────────────────────── Vibes: moody analog recipes ─────────────────────────────
        FilmProfile(
            "vibe_daydream", "Daydream", "DRM", Category.VIBES,
            "Soft pastel warmth, glowing highlights and a stray red leak.",
            Look(
                temp = 0.06f,
                curve = Curve(contrast = 0.92f, shadows = 0.10f, highlights = -0.12f, whites = -0.15f),
                rgbCurveMix = 0.5f, saturation = 0.96f,
                bands = listOf(
                    Band(Hue.SKIN, 30f, shift = 2f, sat = 0.98f, light = 0.02f),
                    Band(Hue.FOLIAGE, 40f, shift = -4f, sat = 0.85f),
                    Band(Hue.SKY, 50f, shift = -6f, sat = 0.92f),
                ),
                shadowTone = Tone(205f, 0.016f), highlightTone = Tone(70f, 0.030f),
                black = Rgb(0.045f, 0.04f, 0.04f), white = Rgb(1f, 0.98f, 0.95f),
            ),
            Effects(grain = 0.24f, grainSize = 0.4f, halation = 0.18f, bloom = 0.38f, leak = 0.42f, leakStyle = 1, dateStamp = true),
        ),
        FilmProfile(
            "vibe_basement", "Basement Tape", "BT", Category.VIBES,
            "Late-night snapshot. Teal shadows, amber glow, heavy corners.",
            Look(
                temp = 0.04f,
                curve = Curve(contrast = 1.06f, shadows = -0.04f, highlights = -0.10f, whites = -0.12f),
                rgbCurveMix = 0.6f, saturation = 0.92f,
                bands = listOf(
                    Band(Hue.SKIN, 30f, shift = 3f, sat = 1.04f),
                    Band(Hue.FOLIAGE, 40f, shift = 12f, sat = 0.80f),
                    Band(Hue.SKY, 50f, shift = -8f, sat = 0.90f),
                ),
                shadowTone = Tone(195f, 0.036f), highlightTone = Tone(65f, 0.030f),
                black = Rgb(0.04f, 0.05f, 0.06f),
            ),
            Effects(grain = 0.30f, grainSize = 0.45f, halation = 0.18f, bloom = 0.16f, vignette = 0.45f, leak = 0.36f, leakStyle = 0),
        ),
        FilmProfile(
            "vibe_summer99", "Summer '99", "S99", Category.VIBES,
            "Point-and-shoot holiday prints. Sunny, saturated, date-stamped.",
            Look(
                temp = 0.14f,
                curve = Curve(contrast = 1.10f, shadows = -0.04f, highlights = 0.06f),
                saturation = 1.12f,
                bands = listOf(
                    Band(Hue.YELLOW, 30f, shift = -4f, sat = 1.12f),
                    Band(Hue.SKIN, 30f, shift = 2f, sat = 1.04f),
                    Band(Hue.SKY, 50f, shift = -4f, sat = 1.04f),
                ),
                highlightTone = Tone(85f, 0.03f), shadowTone = Tone(30f, 0.01f),
            ),
            Effects(grain = 0.34f, grainSize = 0.48f, halation = 0.16f, bloom = 0.12f, vignette = 0.35f, leak = 0.28f, leakStyle = 2, dateStamp = true),
        ),
        FilmProfile(
            "vibe_nightdrive", "Night Drive", "ND", Category.VIBES,
            "Neon-soaked and cinematic. Cyan night, glowing red halos.",
            Look(
                temp = -0.18f, tint = 0.03f,
                curve = Curve(contrast = 1.08f, shadows = -0.10f, highlights = -0.06f),
                saturation = 1.05f,
                bands = listOf(
                    Band(Hue.RED, 30f, shift = -4f, sat = 1.12f),
                    Band(Hue.SKIN, 30f, shift = -2f),
                    Band(Hue.FOLIAGE, 40f, shift = 18f, sat = 0.80f),
                ),
                shadowTone = Tone(205f, 0.045f), highlightTone = Tone(40f, 0.025f),
            ),
            Effects(grain = 0.34f, grainSize = 0.45f, halation = 0.55f, bloom = 0.26f, vignette = 0.2f),
        ),
        FilmProfile(
            "vibe_golden", "Golden Hour", "GH", Category.VIBES,
            "Late sun forever. Honeyed highlights and a warm haze.",
            Look(
                temp = 0.18f,
                curve = Curve(contrast = 1.02f, shadows = 0.02f, highlights = -0.08f, whites = -0.1f),
                saturation = 1.08f,
                bands = listOf(
                    Band(Hue.SKIN, 30f, shift = 3f, sat = 1.04f),
                    Band(Hue.FOLIAGE, 40f, shift = -8f, sat = 0.92f),
                ),
                highlightTone = Tone(75f, 0.04f), midTone = Tone(60f, 0.015f),
            ),
            Effects(grain = 0.22f, grainSize = 0.38f, halation = 0.26f, bloom = 0.30f, leak = 0.16f, leakStyle = 2),
        ),
        FilmProfile(
            "vibe_bluehour", "Blue Hour", "BH", Category.VIBES,
            "Cool, quiet and a little melancholic.",
            Look(
                temp = -0.12f,
                curve = Curve(contrast = 0.95f, shadows = 0.04f, highlights = -0.06f),
                rgbCurveMix = 0.55f, saturation = 0.86f,
                bands = listOf(
                    Band(Hue.FOLIAGE, 40f, shift = 15f, sat = 0.70f),
                    Band(Hue.SKIN, 30f, sat = 0.95f),
                ),
                shadowTone = Tone(240f, 0.03f), highlightTone = Tone(210f, 0.008f),
                black = Rgb(0.03f, 0.04f, 0.07f),
            ),
            Effects(grain = 0.26f, grainSize = 0.4f, halation = 0.12f, bloom = 0.16f, vignette = 0.25f),
        ),
        FilmProfile(
            "vibe_faded", "Faded Memory", "FM", Category.VIBES,
            "A print left in the sun. Washed, creamy and nostalgic.",
            Look(
                temp = 0.08f,
                curve = Curve(contrast = 0.80f, shadows = 0.12f, highlights = -0.12f, whites = -0.15f),
                rgbCurveMix = 0.4f, saturation = 0.70f,
                highlightTone = Tone(80f, 0.03f), shadowTone = Tone(30f, 0.015f),
                black = Rgb(0.10f, 0.09f, 0.09f), white = Rgb(0.95f, 0.92f, 0.85f),
            ),
            Effects(grain = 0.24f, grainSize = 0.42f, bloom = 0.20f, vignette = 0.30f, leak = 0.12f, leakStyle = 3),
        ),
        FilmProfile(
            "vibe_rose", "Rose Film", "RF", Category.VIBES,
            "Experimental stock with rosy highlights and cool shadows.",
            Look(
                tint = 0.08f,
                curve = Curve(contrast = 1.12f, shadows = -0.08f, highlights = 0.04f),
                saturation = 1.04f,
                bands = listOf(
                    Band(Hue.RED, 30f, sat = 1.10f),
                    Band(Hue.FOLIAGE, 40f, shift = 10f, sat = 0.85f),
                ),
                highlightTone = Tone(355f, 0.042f), midTone = Tone(350f, 0.012f), shadowTone = Tone(220f, 0.022f),
            ),
            Effects(grain = 0.30f, grainSize = 0.42f, halation = 0.34f, bloom = 0.12f),
        ),
        FilmProfile(
            "vibe_noir", "Noir", "NOIR", Category.VIBES,
            "Hard-boiled black & white with glowing highlights.",
            Look(mono = Mono(0.35f, 0.50f, 0.15f), curve = Curve(contrast = 1.45f, shadows = -0.30f, highlights = 0.15f, blacks = -0.2f)),
            Effects(grain = 0.45f, grainSize = 0.55f, halation = 0.20f, bloom = 0.22f, vignette = 0.40f),
        ),

        // ───────────────────────────── iPhone filters & Photographic Styles ─────────────────────────────
        FilmProfile(
            "ip_vivid", "Vivid", "VIV", Category.IPHONE,
            "iPhone Photos filter. Punchy, saturated, bright colour.",
            Look(curve = Curve(contrast = 1.10f, shadows = -0.04f, highlights = 0.04f), saturation = 1.22f, vibrance = 0.12f),
        ),
        FilmProfile(
            "ip_vivid_warm", "Vivid Warm", "VIVW", Category.IPHONE,
            "Vivid with a warm, golden lift.",
            Look(temp = 0.12f, curve = Curve(contrast = 1.10f, shadows = -0.04f, highlights = 0.04f), saturation = 1.20f, vibrance = 0.10f, highlightTone = Tone(75f, 0.015f)),
        ),
        FilmProfile(
            "ip_vivid_cool", "Vivid Cool", "VIVC", Category.IPHONE,
            "Vivid with crisp, cool blues.",
            Look(temp = -0.12f, curve = Curve(contrast = 1.10f, shadows = -0.04f, highlights = 0.04f), saturation = 1.18f, vibrance = 0.10f, shadowTone = Tone(240f, 0.010f)),
        ),
        FilmProfile(
            "ip_dramatic", "Dramatic", "DRA", Category.IPHONE,
            "Deep contrast with restrained colour.",
            Look(curve = Curve(contrast = 1.26f, shadows = -0.22f, highlights = -0.10f, blacks = -0.1f), saturation = 0.86f),
        ),
        FilmProfile(
            "ip_dramatic_warm", "Dramatic Warm", "DRAW", Category.IPHONE,
            "Dramatic, with warm amber shadows and light.",
            Look(temp = 0.14f, curve = Curve(contrast = 1.26f, shadows = -0.22f, highlights = -0.10f), saturation = 0.92f, midTone = Tone(65f, 0.012f)),
        ),
        FilmProfile(
            "ip_dramatic_cool", "Dramatic Cool", "DRAC", Category.IPHONE,
            "Dramatic, steely blue and moody.",
            Look(temp = -0.16f, curve = Curve(contrast = 1.26f, shadows = -0.22f, highlights = -0.10f), saturation = 0.82f, shadowTone = Tone(230f, 0.02f)),
        ),
        FilmProfile(
            "ip_mono", "Mono", "MONO", Category.IPHONE,
            "Clean, even black & white.",
            Look(mono = Mono(0.2126f, 0.7152f, 0.0722f), curve = Curve(contrast = 1.04f)),
        ),
        FilmProfile(
            "ip_silvertone", "Silvertone", "SLVR", Category.IPHONE,
            "Bright, silvery B&W with a cool sheen.",
            Look(mono = Mono(0.25f, 0.65f, 0.10f), curve = Curve(contrast = 1.12f, highlights = 0.10f, shadows = 0.04f), highlightTone = Tone(230f, 0.008f), shadowTone = Tone(240f, 0.010f)),
        ),
        FilmProfile(
            "ip_noir", "Noir", "NOIR", Category.IPHONE,
            "High-contrast B&W with inky shadows.",
            Look(mono = Mono(0.30f, 0.59f, 0.11f), curve = Curve(contrast = 1.50f, shadows = -0.35f, highlights = 0.18f, blacks = -0.2f)),
        ),
        FilmProfile(
            "ip_amber", "Amber", "AMB", Category.IPHONE,
            "Photographic Style. A soft amber glow over skin and light.",
            Look(temp = 0.10f, curve = Curve(contrast = 1.02f, shadows = 0.02f), saturation = 1.0f, midTone = Tone(70f, 0.018f), highlightTone = Tone(75f, 0.02f)),
        ),
        FilmProfile(
            "ip_gold", "Gold", "GOLD", Category.IPHONE,
            "Photographic Style. Rich golden warmth.",
            Look(temp = 0.16f, tint = 0.02f, curve = Curve(contrast = 1.05f), saturation = 1.04f, highlightTone = Tone(85f, 0.025f), midTone = Tone(80f, 0.012f)),
        ),
        FilmProfile(
            "ip_rose_gold", "Rose Gold", "ROSE", Category.IPHONE,
            "Photographic Style. Warm with a gentle rose blush.",
            Look(temp = 0.08f, tint = 0.07f, curve = Curve(contrast = 1.02f, shadows = 0.03f), saturation = 0.98f, highlightTone = Tone(20f, 0.02f)),
        ),
        FilmProfile(
            "ip_neutral", "Neutral", "NEUT", Category.IPHONE,
            "Photographic Style. Neutralises warmth for true-to-life tones.",
            Look(temp = -0.06f, curve = Curve(contrast = 0.98f, highlights = -0.04f), saturation = 0.96f),
        ),
        FilmProfile(
            "ip_cool_rose", "Cool Rose", "CROS", Category.IPHONE,
            "Photographic Style. Cool light with a hint of pink.",
            Look(temp = -0.10f, tint = 0.06f, curve = Curve(contrast = 1.02f), saturation = 0.98f, highlightTone = Tone(340f, 0.012f)),
        ),
        FilmProfile(
            "ip_rich", "Rich Contrast", "RICH", Category.IPHONE,
            "Photographic Style. Deeper shadows, richer colour.",
            Look(curve = Curve(contrast = 1.18f, shadows = -0.16f, highlights = 0.02f), saturation = 1.10f),
        ),
        FilmProfile(
            "ip_vibrant", "Vibrant", "VIBR", Category.IPHONE,
            "Photographic Style. Bright, lively colour.",
            Look(exposure = 0.08f, curve = Curve(contrast = 1.06f, highlights = 0.04f), saturation = 1.16f, vibrance = 0.15f),
        ),
        FilmProfile(
            "ip_luminous", "Luminous", "LUMI", Category.IPHONE,
            "Photographic Style. Bright and airy with open shadows.",
            Look(exposure = 0.18f, curve = Curve(contrast = 0.94f, shadows = 0.14f, highlights = -0.04f), saturation = 1.02f),
        ),
        FilmProfile(
            "ip_quiet", "Quiet", "QUIE", Category.IPHONE,
            "Photographic Style. Soft, faded, muted calm.",
            Look(curve = Curve(contrast = 0.86f, shadows = 0.10f, highlights = -0.08f), rgbCurveMix = 0.5f, saturation = 0.78f, black = Rgb.gray(0.05f)),
        ),
        FilmProfile(
            "ip_cozy", "Cozy", "COZY", Category.IPHONE,
            "Photographic Style. Warm, dim and intimate.",
            Look(exposure = -0.08f, temp = 0.12f, curve = Curve(contrast = 1.10f, shadows = -0.12f, highlights = -0.08f), saturation = 0.96f, midTone = Tone(60f, 0.012f)),
        ),
        FilmProfile(
            "ip_ethereal", "Ethereal", "ETHR", Category.IPHONE,
            "Photographic Style. Dreamy pastel softness.",
            Look(exposure = 0.10f, tint = 0.03f, curve = Curve(contrast = 0.84f, shadows = 0.12f, highlights = -0.06f), rgbCurveMix = 0.4f, saturation = 0.82f, highlightTone = Tone(330f, 0.012f), shadowTone = Tone(230f, 0.012f), black = Rgb(0.05f, 0.05f, 0.07f)),
            Effects(bloom = 0.12f),
        ),
        FilmProfile(
            "ip_muted_bw", "Muted B&W", "MBW", Category.IPHONE,
            "Photographic Style. Gentle, low-contrast monochrome.",
            Look(mono = Mono(0.2126f, 0.7152f, 0.0722f), curve = Curve(contrast = 0.86f, shadows = 0.10f, highlights = -0.08f), black = Rgb.gray(0.06f)),
        ),
        FilmProfile(
            "ip_stark_bw", "Stark B&W", "SBW", Category.IPHONE,
            "Photographic Style. Bold, graphic monochrome.",
            Look(mono = Mono(0.30f, 0.59f, 0.11f), curve = Curve(contrast = 1.40f, shadows = -0.25f, highlights = 0.12f)),
        ),

        // ───────────────────────────── Fujifilm camera simulations ─────────────────────────────
        FilmProfile(
            "provia", "Provia", "STD", Category.FUJIFILM,
            "Standard. Faithful colour with a gentle slide-film punch.",
            Look(
                density = 0.025f,
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
                density = 0.07f,
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
                density = 0.015f,
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
                density = 0.05f,
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
                density = 0.02f, scan = 1f,
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
                density = 0.015f, scan = 1f,
                temp = 0.06f,
                curve = Curve(contrast = 1.06f, shadows = -0.08f, highlights = -0.18f, whites = -0.2f),
                saturation = 1.04f,
                bands = listOf(
                    Band(Hue.RED, 30f, sat = 1.08f),
                    Band(Hue.SKIN, 30f, shift = 3f, sat = 0.90f),
                    Band(Hue.YELLOW, 30f, shift = -5f, sat = 1.05f),
                    Band(Hue.FOLIAGE, 40f, shift = -6f, sat = 0.90f),
                    Band(Hue.SKY, 45f, shift = -4f, sat = 0.92f),
                ),
                highlightTone = Tone(72f, 0.040f), midTone = Tone(60f, 0.006f),
            ),
        ),
        FilmProfile(
            "reala_ace", "Reala Ace", "RA", Category.FUJIFILM,
            "Faithful colour with a crisp, hard tonality.",
            Look(
                density = 0.02f, scan = 1f,
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
                density = 0.015f, scan = 1f,
                curve = Curve(contrast = 1.12f, shadows = -0.12f, highlights = -0.04f),
                saturation = 0.94f,
                bands = listOf(Band(Hue.SKIN, 30f, sat = 0.95f, light = 0.01f)),
            ),
        ),
        FilmProfile(
            "pro_neg_std", "Pro Neg Std", "NS", Category.FUJIFILM,
            "Soft studio negative. Low contrast, natural, flattering skin.",
            Look(
                density = 0.01f, scan = 1f,
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
                density = 0.01f, scan = 1f,
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
            Effects(grain = 0.12f, grainSize = 0.18f),
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
                density = 0.04f,
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
                density = 0.01f, scan = 1f,
                temp = 0.06f,
                curve = Curve(contrast = 0.92f, shadows = 0.06f, highlights = -0.14f, whites = -0.12f),
                rgbCurveMix = 0.55f, saturation = 1.0f,
                bands = listOf(
                    Band(Hue.RED, 30f, shift = 4f, sat = 0.98f),
                    Band(Hue.SKIN, 30f, shift = 2f, sat = 0.98f, light = 0.015f),
                    Band(Hue.YELLOW, 30f, shift = -4f, sat = 0.97f),
                    Band(Hue.FOLIAGE, 40f, shift = -6f, sat = 0.90f),
                    Band(Hue.SKY, 50f, shift = -3f, sat = 0.98f),
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
                density = 0.01f, scan = 1f,
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
                density = 0.012f, scan = 1f,
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
                density = 0.035f, scan = 1f,
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
                density = 0.015f, scan = 1f,
                temp = 0.12f,
                curve = Curve(contrast = 1.06f, shadows = -0.04f, highlights = -0.06f),
                saturation = 1.08f,
                bands = listOf(
                    Band(Hue.YELLOW, 35f, shift = -6f, sat = 1.15f),
                    Band(Hue.SKIN, 30f, shift = 3f, sat = 0.90f),
                    Band(Hue.FOLIAGE, 40f, shift = -10f, sat = 0.92f),
                    Band(Hue.SKY, 50f, shift = -4f, sat = 1.22f),
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
                density = 0.01f, scan = 1f,
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
                density = 0.015f, scan = 1f,
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
                density = 0.06f,
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
            Effects(grain = 0.10f, grainSize = 0.2f),
        ),
        FilmProfile(
            "ektachrome", "Ektachrome E100", "E6", Category.KODAK,
            "Modern slide film. Clean, cool and vivid blues.",
            Look(
                density = 0.04f,
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
                density = 0.015f, scan = 1f,
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
                density = 0.005f, scan = 1f,
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
                density = 0.01f, scan = 1f,
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
                density = 0.01f, scan = 1f,
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
                density = 0.015f, scan = 1f,
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
                density = 0.01f, scan = 1f,
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
                density = 0.01f, scan = 1f,
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
                density = 0.02f,
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
                    Band(Hue.SKY, 50f, shift = -12f, sat = 1.9f),
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
                density = 0.01f, scan = 1f,
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
                density = 0.02f,
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
                density = 0.01f,
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
                density = 0.02f, scan = 1f,
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
                density = 0.03f, scan = 1f,
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
                density = 0.015f, scan = 1f,
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
            Look(mono = Mono(0.30f, 0.56f, 0.14f), curve = Curve(contrast = 1.10f, shadows = -0.02f, highlights = 0.10f), black = Rgb.gray(0.03f)),
            Effects(grain = 0.75f, grainSize = 0.75f),
        ),
        // ── More stocks
        FilmProfile(
            "fuji_mono", "Monochrome", "FMO", Category.FUJIFILM,
            "Fujifilm's standard black & white. Neutral, faithful grey tones.",
            Look(mono = Mono(0.30f, 0.59f, 0.11f), curve = Curve(contrast = 1.08f, shadows = -0.04f)),
        ),
        FilmProfile(
            "proimage100", "Pro Image 100", "PI", Category.KODAK,
            "Warm portrait stock made for hot climates. Gentle contrast, golden skin.",
            Look(
                density = 0.01f, scan = 1f,
                temp = 0.07f,
                curve = Curve(contrast = 0.96f, shadows = 0.04f, highlights = -0.10f, whites = -0.08f),
                rgbCurveMix = 0.6f, saturation = 1.02f,
                bands = listOf(
                    Band(Hue.SKIN, 30f, shift = 3f, sat = 0.96f, light = 0.01f),
                    Band(Hue.FOLIAGE, 40f, shift = -6f, sat = 0.92f),
                    Band(Hue.SKY, 50f, shift = -4f, sat = 1.08f),
                    Band(Hue.RED, 30f, shift = 3f, sat = 1.04f),
                ),
                highlightTone = Tone(72f, 0.02f),
                black = Rgb.gray(0.012f),
            ),
            Effects(grain = 0.16f, grainSize = 0.3f, halation = 0.08f),
        ),
        FilmProfile(
            "aerochrome", "Aerochrome", "AERO", Category.KODAK,
            "Infrared false colour. Leaves turn crimson and pink, skies go deep blue.",
            Look(
                density = 0.02f,
                curve = Curve(contrast = 1.15f, shadows = -0.10f),
                saturation = 1.12f,
                bands = listOf(
                    // One broad band for all greens (overlapping bands would stack their shifts).
                    Band(135f, 50f, shift = -125f, sat = 1.5f, light = 0.02f),
                    Band(Hue.YELLOW, 18f, shift = -40f, sat = 1.05f),
                    Band(Hue.RED, 25f, shift = 28f, sat = 1.05f),
                    Band(Hue.SKY, 50f, shift = 8f, sat = 1.25f, light = -0.06f),
                    Band(Hue.SKIN, 22f, shift = -6f, sat = 0.95f),
                ),
            ),
            Effects(grain = 0.2f, grainSize = 0.35f, halation = 0.1f),
        ),
        FilmProfile(
            "natura1600", "Natura 1600", "N16", Category.FUJI_STOCK,
            "Low-light legend. Cool, clean shadows and natural skin under any light.",
            Look(
                density = 0.01f, scan = 1f,
                temp = -0.03f,
                curve = Curve(contrast = 1.02f, shadows = 0.02f, highlights = -0.08f, whites = -0.08f),
                saturation = 0.98f,
                bands = listOf(
                    Band(Hue.SKIN, 28f, shift = -2f, sat = 0.98f),
                    Band(Hue.FOLIAGE, 40f, shift = 6f, sat = 0.95f),
                    Band(Hue.SKY, 50f, shift = -6f, sat = 1.02f),
                ),
                shadowTone = Tone(200f, 0.016f),
                black = Rgb(0.01f, 0.018f, 0.022f),
            ),
            Effects(grain = 0.4f, grainSize = 0.5f, halation = 0.1f),
        ),
        FilmProfile(
            "pro160ns", "Pro 160NS", "160", Category.FUJI_STOCK,
            "Soft studio negative. Natural skin, gentle greens, airy highlights.",
            Look(
                density = 0.008f, scan = 1f,
                curve = Curve(contrast = 0.90f, shadows = 0.06f, highlights = -0.10f, whites = -0.10f),
                rgbCurveMix = 0.55f, saturation = 0.92f,
                bands = listOf(
                    Band(Hue.SKIN, 30f, sat = 0.95f, light = 0.015f),
                    Band(Hue.FOLIAGE, 40f, shift = 10f, sat = 0.86f),
                    Band(Hue.SKY, 50f, shift = -6f, sat = 0.92f),
                ),
                highlightTone = Tone(185f, 0.008f),
                black = Rgb.gray(0.015f),
            ),
            Effects(grain = 0.14f, grainSize = 0.26f, halation = 0.06f),
        ),
        FilmProfile(
            "vista200", "Vista 200", "AV2", Category.AGFA,
            "Agfa's cheerful negative. Punchy reds, warm yellows, cyan-leaning skies.",
            Look(
                density = 0.015f, scan = 1f,
                temp = 0.03f,
                curve = Curve(contrast = 1.10f, shadows = -0.06f, highlights = -0.04f),
                saturation = 1.12f,
                bands = listOf(
                    Band(Hue.RED, 30f, shift = -4f, sat = 1.18f),
                    Band(Hue.SKIN, 22f, sat = 0.9f),
                    Band(Hue.YELLOW, 30f, shift = -4f, sat = 1.08f),
                    Band(Hue.SKY, 50f, shift = -10f, sat = 1.05f),
                    Band(Hue.FOLIAGE, 40f, shift = 4f, sat = 1.04f),
                ),
                highlightTone = Tone(85f, 0.015f),
            ),
            Effects(grain = 0.3f, grainSize = 0.42f, halation = 0.1f),
        ),
        FilmProfile(
            "precisa", "Precisa 100", "CT", Category.AGFA,
            "Agfa slide film. Cool, crisp and saturated with bright blues.",
            Look(
                density = 0.04f,
                temp = -0.03f,
                curve = Curve(contrast = 1.24f, shadows = -0.20f, highlights = 0.04f, blacks = -0.1f),
                saturation = 1.2f,
                bands = listOf(
                    Band(Hue.SKY, 50f, shift = 4f, sat = 1.18f, light = -0.04f),
                    Band(Hue.FOLIAGE, 40f, shift = 6f, sat = 1.08f),
                    Band(Hue.RED, 30f, sat = 1.06f),
                    Band(Hue.SKIN, 25f, sat = 0.94f),
                ),
                shadowTone = Tone(250f, 0.012f),
            ),
            Effects(grain = 0.1f, grainSize = 0.2f),
        ),
        FilmProfile(
            "print2383", "2383 Print", "2383", Category.CINEMA,
            "Kodak print film. Rich blacks, teal shadows, warm skin: the classic cinema finish.",
            Look(
                density = 0.025f,
                curve = Curve(contrast = 1.22f, shadows = -0.18f, highlights = -0.06f, whites = -0.12f),
                rgbCurveMix = 0.85f, saturation = 1.02f,
                bands = listOf(
                    Band(Hue.SKIN, 28f, shift = 2f, sat = 0.94f),
                    Band(Hue.SKY, 50f, shift = -8f, sat = 0.95f, light = -0.03f),
                    Band(Hue.FOLIAGE, 40f, shift = 10f, sat = 0.85f),
                    Band(Hue.RED, 30f, shift = -2f, sat = 1.05f),
                ),
                shadowTone = Tone(195f, 0.03f), highlightTone = Tone(70f, 0.018f),
                white = Rgb(0.98f, 0.97f, 0.93f),
            ),
            Effects(grain = 0.16f, grainSize = 0.32f, halation = 0.18f),
        ),
        FilmProfile(
            "vision50d", "Vision3 50D", "V50", Category.CINEMA,
            "Kodak's finest daylight motion-picture stock. Clean, wide latitude, natural colour.",
            Look(
                density = 0.01f, scan = 1f,
                curve = Curve(contrast = 0.92f, shadows = 0.06f, highlights = -0.12f, whites = -0.10f),
                saturation = 0.94f,
                bands = listOf(
                    Band(Hue.FOLIAGE, 40f, shift = 4f, sat = 0.9f),
                    Band(Hue.SKY, 50f, sat = 0.96f),
                ),
                shadowTone = Tone(200f, 0.008f),
                black = Rgb.gray(0.02f),
            ),
            Effects(grain = 0.08f, grainSize = 0.2f, halation = 0.12f),
        ),
        FilmProfile(
            "polaroid_expired", "Expired Polaroid", "EXP", Category.INSTANT,
            "Out-of-date instant film. Faded magenta and teal shifts, creamy lifted blacks.",
            Look(
                temp = 0.04f, tint = 0.08f,
                curve = Curve(contrast = 0.84f, shadows = 0.08f, highlights = -0.12f, whites = -0.16f),
                rgbCurveMix = 0.4f, saturation = 0.8f,
                bands = listOf(
                    Band(Hue.SKY, 50f, shift = -25f, sat = 0.75f),
                    Band(Hue.FOLIAGE, 40f, shift = 20f, sat = 0.75f),
                    Band(Hue.SKIN, 28f, shift = 6f),
                ),
                shadowTone = Tone(190f, 0.03f), highlightTone = Tone(350f, 0.03f),
                black = Rgb(0.12f, 0.10f, 0.12f), white = Rgb(0.95f, 0.90f, 0.88f),
            ),
            Effects(grain = 0.16f, bloom = 0.25f, vignette = 0.4f, frame = FrameStyle.INSTANT),
        ),
        FilmProfile(
            "lomo_purple", "LomoChrome Purple", "LCP", Category.LOMOGRAPHY,
            "Greens turn purple, blues turn teal, skin stays warm.",
            Look(
                density = 0.02f,
                curve = Curve(contrast = 1.12f, shadows = -0.08f),
                saturation = 1.08f,
                bands = listOf(
                    // Greens (incl. yellow-greens) -> violet; true yellows -> pink; blues -> teal.
                    Band(130f, 50f, shift = 172f, sat = 1.2f, light = -0.03f),
                    Band(Hue.SKY, 55f, shift = -85f, sat = 1.1f),
                    Band(92f, 16f, shift = -80f, sat = 0.9f),
                    Band(Hue.SKIN, 22f, sat = 0.98f),
                ),
            ),
            Effects(grain = 0.3f, grainSize = 0.42f, halation = 0.12f),
        ),
        FilmProfile(
            "lomo_metropolis", "Metropolis", "MET", Category.LOMOGRAPHY,
            "Desaturated urban film. Muted colour, punchy contrast, greenish shadows.",
            Look(
                scan = 1f,
                curve = Curve(contrast = 1.28f, shadows = -0.16f, highlights = 0.04f),
                saturation = 0.55f,
                bands = listOf(
                    Band(Hue.RED, 30f, sat = 1.3f),
                    Band(Hue.YELLOW, 30f, shift = -6f, sat = 0.8f),
                ),
                shadowTone = Tone(150f, 0.015f), highlightTone = Tone(80f, 0.012f),
            ),
            Effects(grain = 0.3f, grainSize = 0.4f, halation = 0.1f),
        ),
        FilmProfile(
            "lomo800", "Color 800", "L800", Category.LOMOGRAPHY,
            "Lomography's fast colour negative. Warm, punchy, saturated and grainy.",
            Look(
                density = 0.015f, scan = 1f,
                temp = 0.06f,
                curve = Curve(contrast = 1.12f, shadows = -0.06f, highlights = -0.04f),
                saturation = 1.16f,
                bands = listOf(
                    Band(Hue.RED, 30f, sat = 1.1f),
                    Band(Hue.SKIN, 28f, shift = 3f, sat = 0.95f),
                    Band(Hue.FOLIAGE, 40f, shift = -4f, sat = 1.08f),
                    Band(Hue.SKY, 50f, sat = 1.12f),
                ),
                highlightTone = Tone(75f, 0.02f),
                black = Rgb(0.02f, 0.012f, 0.008f),
            ),
            Effects(grain = 0.42f, grainSize = 0.5f, halation = 0.14f),
        ),
        FilmProfile(
            "xp2", "XP2 Super", "XP2", Category.ILFORD,
            "Chromogenic B&W. Smooth tones, almost no grain, gentle contrast.",
            Look(mono = Mono(0.29f, 0.60f, 0.11f), curve = Curve(contrast = 1.04f, shadows = 0.04f, highlights = -0.06f), black = Rgb.gray(0.02f)),
            Effects(grain = 0.08f, grainSize = 0.2f),
        ),
        FilmProfile(
            "sfx200", "SFX 200", "SFX", Category.ILFORD,
            "Extended-red B&W. Dark skies, glowing foliage and skin, a near-infrared feel.",
            Look(mono = Mono(0.62f, 0.36f, 0.02f), curve = Curve(contrast = 1.24f, shadows = -0.12f, highlights = 0.08f)),
            Effects(grain = 0.26f, grainSize = 0.36f),
        ),
    )

    private val byId = all.associateBy { it.id }
    fun byId(id: String): FilmProfile = byId[id] ?: ORIGINAL
    fun inCategory(c: Category?): List<FilmProfile> = if (c == null) all else all.filter { it.category == c || it.isIdentity }
}
