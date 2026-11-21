package app.lumobo.render

/** GLSL ES 3.00 sources. Texture row 0 == bitmap top row == uv.y 0 throughout. */
internal object Shaders {

    const val VERTEX = """#version 300 es
in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    private const val COMMON = """
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
vec3 toLin(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
}
vec3 toSrgb(vec3 c) {
    c = max(c, 0.0);
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
}
vec3 softClip(vec3 x) {
    vec3 t = vec3(0.8);
    return mix(x, t + 0.2 * (1.0 - exp(-(x - t) / 0.2)), step(t, x));
}
// Tone (perceptual brightness, 0..1) of an sRGB colour at this photo's exposure. The detail
// tools (Texture, Clarity, Sharpen, HDR) and their edge guides all measure tone the same way.
float toneOf(vec3 s, vec3 gain) {
    float y = dot(clamp(s, 0.0, 1.0), LUMA);
    float g = dot(gain, LUMA);
    if (abs(g - 1.0) < 0.001) return y;
    float l = (y <= 0.04045 ? y / 12.92 : pow((y + 0.055) / 1.055, 2.4)) * g;
    if (g > 1.001 && l > 0.8) l = 0.8 + 0.2 * (1.0 - exp(-(l - 0.8) / 0.2));
    return l <= 0.0031308 ? l * 12.92 : 1.055 * pow(l, 1.0 / 2.4) - 0.055;
}
"""

    /** The photo's Ultra HDR gain map (ProXDR on ColorOS, and most phone cameras now). */
    private const val GAIN = """
uniform float uGmOn;
uniform sampler2D uGainTex;
uniform float uGmSingle;  // 1: one-channel map (in .a)
uniform vec3 uGmMin;      // log2 of ratio min / max
uniform vec3 uGmMax;
uniform vec3 uGmGamma;    // Android's gamma: applied as pow(G, gamma)
uniform vec3 uGmEpsS;
uniform vec3 uGmEpsH;
// Per-channel log2 boost from SDR to the scene's real HDR light at uv (0 without a gain map).
vec3 gmLogBoost(vec2 uv) {
    if (uGmOn < 0.5) return vec3(0.0);
    vec4 t = texture(uGainTex, uv);
    vec3 g = uGmSingle > 0.5 ? vec3(t.a) : t.rgb;
    return mix(uGmMin, uGmMax, pow(clamp(g, 0.0, 1.0), uGmGamma));
}
vec3 gmHdr(vec3 sdrLin, vec3 lb) { return max((sdrLin + uGmEpsS) * exp2(lb) - uGmEpsH, 0.0); }
"""

    /**
     * Extracts light for the low-resolution passes.
     * 0 halation highlights, 1 bloom, 2 star/streak glints, 3 whole image, 4 haze (min channel),
     * 5 tone guide for the edge-aware Clarity and HDR, 6 Glow light.
     * Light for the optical effects (0, 1, 2, 6) is measured with the photographer's exposure and
     * white balance only, not the film's lab-scan correction, so switching to a brighter film
     * doesn't suddenly feed more light into stars, mist and glow and blow them out.
     */
    const val PREFILTER = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uSrc;
uniform vec3 uGain;
uniform int uMode;
uniform vec2 uPeak;   // inner ring radius in uv, for glint detection
uniform sampler2D uStarMask; // star eraser (painted areas = 1)
uniform float uMaskOn;
uniform float uThresh;   // star threshold (0.6 = classic)
uniform float uGlowT;    // Glow: tone where the glow starts (from Range)
uniform float uGlowSat;  // Glow: 0 white light, 1 each light keeps its own colour
uniform float uGlowAll;  // Glow: 1 = the whole photo glows (Orton)
uniform float uGlowPeak; // Glow: 1 = favour point-like lights (Streak)
uniform float uGmBase;    // the photo's typical gain map boost (median), in stops
uniform float uFineLod;   // finer mip level for finding tiny glints
uniform vec2 uFine;       // quarter of an fx texel, in uv
$COMMON
$GAIN
// HDR headroom of the scene here, in stops above the SDR photo. Real lights (sun, its glints
// on water, lamps) carry stops of it; a white wall carries none. From the photo's gain map, or
// estimated when it has none.
float headroom(vec2 uv, vec3 sdrLin) {
    if (uGmOn < 0.5) {
        // No gain map: rebuild it. Light that clipped (was brighter than the sensor could hold)
        // and stands far above its wide surroundings is a light source; a white wall in a bright
        // scene clips too but sits close to its surroundings.
        float mx = max(sdrLin.r, max(sdrLin.g, sdrLin.b));
        float clip = smoothstep(0.80, 0.98, mx);
        float around = dot(toLin(textureLod(uSrc, uv, uFineLod + 4.0).rgb), LUMA);
        float lift = log2((dot(sdrLin, LUMA) + 0.02) / (around + 0.02));
        return clip * clamp(lift * 0.8, 0.0, 3.0);
    }
    vec3 hdr = gmHdr(sdrLin, gmLogBoost(uv));
    float ls = dot(sdrLin, LUMA);
    // Relative to the photo's typical boost: camera gain maps lift the whole picture for its
    // HDR version, so only light standing above that lift is a light source.
    float h = log2(max(dot(hdr, LUMA), 1e-4) / max(ls, 1e-4)) - uGmBase;
    return clamp(h, 0.0, 4.0) * smoothstep(0.06, 0.3, ls);
}
// How bright a light is: its brightest channel counts, so a golden glint or a red neon is as
// much a light as a white one, raised by its HDR headroom.
float lightLevel(vec3 lin, float h) {
    return max(dot(lin, LUMA), 0.85 * max(lin.r, max(lin.g, lin.b))) * exp2(0.5 * h);
}
// Glint detection works on the photo as shot (no exposure gain), so the star count stays
// stable however much the exposure slider is pushed.
float ringLuma(vec2 r, float lod) {
    float s = 0.0;
    for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.7853982 + 0.39;
        s += dot(toLin(textureLod(uSrc, vUv + vec2(cos(a), sin(a)) * r, lod).rgb), LUMA);
    }
    return s / 8.0;
}
void main() {
    vec3 sdr = toLin(texture(uSrc, vUv).rgb);
    vec3 lin = sdr * uGain;
    float l = dot(lin, LUMA);
    if (uMode == 0 || uMode == 1) {
        // Film halation / bloom: bright light, real lights (HDR headroom) carrying more of it.
        float h = headroom(vUv, sdr);
        float ll = lightLevel(lin, h);
        float k = uMode == 0 ? smoothstep(0.50, 0.98, ll) : smoothstep(0.18, 0.95, ll);
        fragColor = vec4(lin * k * min(exp2(0.5 * h), 2.0), 1.0);
        return;
    }
    if (uMode == 3) { fragColor = vec4(lin, 1.0); return; }
    if (uMode == 4) { fragColor = vec4(vec3(min(lin.r, min(lin.g, lin.b))), 1.0); return; }
    if (uMode == 5) {
        // Edge guide for Clarity and HDR: the photo's tone, exactly as the main pass measures it.
        fragColor = vec4(vec3(toneOf(texture(uSrc, vUv).rgb, uGain)), 1.0);
        return;
    }
    if (uMode == 6) {
        // Glow light: tones above Range's knee (soft, so the glow has no hard edge), in their
        // own colour or turned white.
        vec3 s = texture(uSrc, vUv).rgb;
        float h = headroom(vUv, sdr);
        // Tone of the light: its brightest channel counts (coloured lights glow too), and
        // real lights rank above white surfaces by their HDR headroom.
        float y = max(toneOf(s, uGain), 0.9 * toSrgb(vec3(max(lin.r, max(lin.g, lin.b)))).r) + 0.2 * h;
        float w = uGlowAll > 0.5 ? 1.0 : smoothstep(uGlowT - 0.22, uGlowT + 0.04, y);
        if (uGlowPeak > 0.5) {
            // Anamorphic streaks gather on point-like lights: bright AND far above their
            // surroundings. Broad bright areas (a sunlit window) streak only faintly.
            float aw = lightLevel(toLin(textureLod(uSrc, vUv, uFineLod + 3.0).rgb) * uGain, 0.0);
            w *= mix(0.15, 1.0, smoothstep(0.05, 0.5, lightLevel(lin, h) - aw));
        }
        fragColor = vec4(mix(vec3(l), lin, uGlowSat) * w * min(exp2(0.5 * h), 1.6), 1.0);
        return;
    }
    // Star / streak light, modelled on the real filter: a cross-screen grating sends a small
    // share of ALL light into its spikes. From ordinary surfaces that share is invisible; it
    // only shows from light far brighter than the picture can hold - the sun, lamps, glints.
    // So the spikes are fed by the light ABOVE SDR white, read from the photo's HDR gain map (or
    // estimated): the sun, with the most of it, makes one big dominant star, a sunlit wall
    // makes none. Small sparkles that are bright and stand out from their surroundings (sun
    // shimmer on water) still glint, and coloured light counts by its brightest channel.
    // The brightest of four finer samples is used so tiny sparkles survive the low resolution.
    vec2 best = vUv;
    float bl = -1.0;
    for (int i = 0; i < 4; i++) {
        vec2 q = vUv + vec2(i == 0 || i == 2 ? -1.0 : 1.0, i < 2 ? -1.0 : 1.0) * uFine;
        float v = lightLevel(toLin(textureLod(uSrc, q, uFineLod).rgb) * uGain, 0.0);
        if (v > bl) { bl = v; best = q; }
    }
    sdr = toLin(textureLod(uSrc, best, uFineLod).rgb);
    lin = sdr * uGain;
    float hh = headroom(best, sdr);
    l = lightLevel(lin, 0.0);
    float around = 0.0;
    for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.7853982;
        vec2 o = vec2(cos(a), sin(a)) * uPeak;
        around += lightLevel(toLin(textureLod(uSrc, vUv + o, 2.0).rgb) * uGain, 0.0);
    }
    around /= 8.0;
    // Threshold: how bright (in HDR terms) a light must be before it makes a star.
    float lh = l * exp2(hh);
    float pass = smoothstep(uThresh, uThresh + 0.35, lh);
    // Light beyond SDR white. The gain map compresses the real range (the sun is thousands of
    // times a wall, the map stores a few), so the excess is expanded to rank the sun far above
    // a sunlit window.
    float excess = exp2(hh) - 1.0;
    float e2 = excess * excess;
    float hdrStar = min(e2 * e2, 12.0) * 2.2;
    // Sparkles: small bright points standing out from their surroundings (SDR terms).
    float sparkle = smoothstep(0.02, 0.3, max(l - around, 0.0));
    float ks = pass * sparkle;
    // With the photo's real HDR data, only light above the scene's own lift makes stars (as
    // through a real filter: the sun's cores make big stars; leaves against the sky don't).
    // Without it, bright points standing out from their surroundings stand in for that light.
    float energy = pass * hdrStar * 1.6 + (uGmOn > 0.5 ? 0.0 : ks * ks * 1.4);
    float keep = uMaskOn > 0.5 ? 1.0 - texture(uStarMask, vUv).r : 1.0;
    fragColor = vec4(lin * energy * keep, 1.0);
}
"""

    const val BLUR = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uTex;
uniform vec2 uStep;
uniform float uSigma;
uniform int uRadius;
void main() {
    vec4 acc = vec4(0.0);
    float wsum = 0.0;
    float k = -1.0 / (2.0 * uSigma * uSigma);
    for (int i = -uRadius; i <= uRadius; i++) {
        float fi = float(i);
        float w = exp(fi * fi * k);
        acc += texture(uTex, vUv + uStep * fi) * w;
        wsum += w;
    }
    fragColor = acc / wsum;
}
"""

    /**
     * One direction of a joint bilateral filter on tone, at fx resolution: a Gaussian whose
     * neighbours count less the more their tone differs from this texel's in the guide, so the
     * average stays on its own side of an edge. Writes (filtered tone, guide tone).
     */
    const val BILATERAL = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uTex;    // tone to filter (r)
uniform sampler2D uGuide;  // edge guide (r)
uniform vec2 uStep;
uniform float uSigma;
uniform int uRadius;
uniform float uRange;      // 1 / (2 * range sigma^2)
void main() {
    float gc = texture(uGuide, vUv).r;
    float acc = 0.0;
    float wsum = 0.0;
    float k = -1.0 / (2.0 * uSigma * uSigma);
    for (int i = -uRadius; i <= uRadius; i++) {
        float fi = float(i);
        vec2 o = vUv + uStep * fi;
        float d = texture(uGuide, o).r - gc;
        float w = exp(fi * fi * k - d * d * uRange);
        acc += texture(uTex, o).r * w;
        wsum += w;
    }
    fragColor = vec4(acc / wsum, gc, 0.0, 1.0);
}
"""

    /** One arm pair of a star / streak: exponentially fading line in both directions. */
    const val STREAK = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uTex;
uniform vec2 uDir;
uniform int uTaps;
uniform float uDecay;
uniform vec3 uTint;
uniform float uSpectral; // diffraction rainbow along the ray (0 = white)
uniform float uOneSided; // 1: light trails only toward +uDir (motion), 0: both ways
uniform float uDisperse; // natural dispersion: longer wavelengths spread further (0 = none)
uniform float uDensOn;   // 1: each light's ray length follows its total output (density)
uniform float uDensLod;  // mip level whose texel is about one light's extent
uniform float uRayLod;   // mip level the rays read a big light from (softer, thicker rays); small lights stay crisp
// How far a light's rays reach (fraction of the full arm): from the total light it puts out,
// summed over its surroundings. A dense, big light (the sun) throws long rays - one big star
// or a long anamorphic streak from its centre; a small light only twinkles.
// Also gathers a big light's rays toward its brightest core (where it outshines its own
// surroundings), so the sun throws one star from its centre instead of a blob of overlapping
// stars from every pixel of its disc. Returns the reach; `core` scales the light's energy.
float lightReach(vec2 p, vec3 s, out float core) {
    core = 1.0;
    if (uDensOn < 0.5) return 1.0;
    float mean = dot(textureLod(uTex, p, uDensLod).rgb, vec3(0.2126, 0.7152, 0.0722));
    float total = mean * exp2(2.0 * uDensLod);
    float r = dot(s, vec3(0.2126, 0.7152, 0.0722)) / (mean + 1e-3);
    core = clamp(r * r * 0.25, 0.0, 3.0);
    return clamp(0.12 + 0.11 * log2(1.0 + total), 0.12, 1.0);
}
void main() {
    // Diffraction (star filters, aperture blades) and anamorphic flare spread light along the
    // ray with a power-law falloff, ~1/r^2: a bright core and a long faint tail, not the quick
    // exponential cut-off. Light diffracts in proportion to its wavelength, so red reaches a
    // little further than blue and a real star's rays end with a faint warm fringe.
    float n = float(uTaps);
    float K = uDecay * n / 0.693;   // same half-length as the old exponential ray
    vec3 reach = vec3(1.0 + uDisperse, 1.0, 1.0 - uDisperse);
    vec3 acc = vec3(0.0);
    for (int i = 1; i <= uTaps; i++) {
        float fi = float(i);
        float t = fi / n;
        float fade = 1.0 - smoothstep(0.72, 1.0, t);
        // Each source's own ray: its reach scales the falloff; dividing by the ray's total
        // weight keeps its energy, so a short ray is brighter near the light.
        vec2 pa = vUv - uDir * fi;
        vec3 sa = texture(uTex, pa).rgb;
        float ca;
        float ra = lightReach(pa, sa, ca);
        float ka = K / ra;
        vec3 xa = t * ka / reach;
        // The ray carries the light's core read slightly blurred: a thick, soft ray as wide as
        // the light, while only the core (not the whole bright area) feeds it.
        vec3 s = textureLod(uTex, pa, uRayLod * ra).rgb * ca * fade / (1.0 + xa * xa) / (n * atan(ka) / ka);
        if (uOneSided < 0.5) {
            vec2 pb = vUv + uDir * fi;
            vec3 sb = texture(uTex, pb).rgb;
            float cb;
            float rb = lightReach(pb, sb, cb);
            float kb = K / rb;
            vec3 xb = t * kb / reach;
            s += textureLod(uTex, pb, uRayLod * rb).rgb * cb * fade / (1.0 + xb * xb) / (n * atan(kb) / kb);
        }
        if (uSpectral > 0.0) {
            // A diffraction grating spreads each order into a spectrum, blue nearest the light
            // (shorter wavelength, smaller angle) and red furthest; the next order repeats it
            // further out, fainter.
            float ord = t * 2.6;
            float ph = fract(ord);
            vec3 spec = clamp(1.0 - abs(vec3(ph) - vec3(0.80, 0.52, 0.24)) * 3.4, 0.0, 1.0) / (1.0 + 0.6 * floor(ord));
            float y = dot(s, vec3(0.2126, 0.7152, 0.0722));
            s = mix(s, y * spec * 1.6, uSpectral * smoothstep(0.03, 0.2, t));
        }
        acc += s;
    }
    fragColor = vec4(acc * uTint, 1.0);
}
"""

    /**
     * Star filter and anamorphic flare, drawn per light source (found by LightMap): one star
     * per light, as a real filter makes. Rays fall off as a power law along their length and
     * are as wide as the light's core (soft) or a fine line (crisp), tapering out; longer
     * wavelengths reach a little further; the rainbow of a diffraction filter runs blue (near)
     * to red (far) per order. The anamorphic flare is a thin line in a broad soft band.
     */
    const val STARS = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform int uCount;
uniform vec4 uPt[48];     // x, y (source uv), base ray length, core radius (short-side units)
uniform vec4 uCol[48];    // colour x brightness of each light
uniform int uArms;        // ray pairs (lines through the light)
uniform vec2 uDir[4];     // their directions (source pixel space, unit)
uniform vec2 uAspect;     // fx size over its short side
uniform float uPx;        // one fx texel, short-side units
uniform float uStarAmt;   // 0 = no star
uniform float uStarLen;   // ray length multiplier (Star size)
uniform float uSoft;      // ray softness
uniform float uSpectral;  // rainbow
uniform float uStreakAmt; // 0 = no anamorphic flare
uniform vec2 uStreakDir;
uniform float uStreakLen;
uniform vec3 uStreakTint;
uniform float uStreakSpectral;
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
// A diffraction order's spectrum along the ray: blue nearest the light, red furthest; the
// next order repeats it further out, fainter.
vec3 spectrum(float t) {
    float ord = t * 2.6;
    float ph = fract(ord);
    return clamp(1.0 - abs(vec3(ph) - vec3(0.80, 0.52, 0.24)) * 3.4, 0.0, 1.0) / (1.0 + 0.6 * floor(ord));
}
vec3 along(float t, float k) {
    vec3 x = t * k / vec3(1.06, 1.0, 0.94);   // red reaches a little further than blue
    return (1.0 - smoothstep(0.7, 1.0, t)) / (1.0 + x * x);
}
void main() {
    vec2 p = vUv * uAspect;
    vec3 acc = vec3(0.0);
    for (int i = 0; i < 48; i++) {
        if (i >= uCount) break;
        vec2 d = p - uPt[i].xy * uAspect;
        float base = uPt[i].z;
        float rc = max(uPt[i].w, uPx);
        float reach = max(base * uStarLen * step(0.001, uStarAmt), base * uStreakLen * step(0.001, uStreakAmt)) + 4.0 * rc;
        if (dot(d, d) > reach * reach) continue;
        vec3 col = uCol[i].rgb;
        if (uStarAmt > 0.0) {
            float L = base * uStarLen;
            // The light itself, scattered a little by the filter: a small soft core glow.
            float g = 1.5 * rc;
            acc += col * uStarAmt * 0.04 * exp(-0.5 * dot(d, d) / (g * g));
            for (int a = 0; a < 4; a++) {
                if (a >= uArms) break;
                vec2 dir = uDir[a];
                float al = abs(dot(d, dir));
                float t = al / L;
                if (t >= 1.0) continue;
                // Spikes come out of the light's edge; they don't pile up on the light itself.
                float emerge = mix(0.12, 1.0, smoothstep(0.5 * rc, 3.0 * rc, al));
                float ac = abs(dot(d, vec2(-dir.y, dir.x)));
                float w0 = max(rc * mix(0.3, 1.0, uSoft), uPx * 0.7);
                float w = mix(w0, max(w0 * 0.3, uPx * 0.6), t);
                vec3 c = col;
                if (uSpectral > 0.0) c = mix(col, dot(col, LUMA) * spectrum(t) * 1.6, uSpectral * smoothstep(0.03, 0.18, t));
                acc += c * uStarAmt * emerge * along(t, 3.2) * exp(-0.5 * ac * ac / (w * w));
            }
        }
        if (uStreakAmt > 0.0) {
            float L = base * uStreakLen;
            float al = abs(dot(d, uStreakDir));
            float t = al / L;
            if (t < 1.0) {
                float emerge = mix(0.1, 1.0, smoothstep(0.5 * rc, 5.0 * rc, al));
                float ac = abs(dot(d, vec2(-uStreakDir.y, uStreakDir.x)));
                float lw = max(rc * 0.3, uPx * 0.7);
                float bw = rc * 2.5 + uPx * 3.0;
                float prof = exp(-0.5 * ac * ac / (lw * lw)) + 0.3 * exp(-0.5 * ac * ac / (bw * bw));
                vec3 tint = uStreakTint;
                if (uStreakSpectral > 0.0) tint = mix(tint, spectrum(t) * 1.6, uStreakSpectral * smoothstep(0.03, 0.18, t));
                acc += dot(col, LUMA) * tint * uStreakAmt * emerge * along(t, 2.4) * prof;
            }
        }
    }
    fragColor = vec4(acc, 1.0);
}
"""

    const val MAIN = """#version 300 es
precision highp float;
precision highp sampler3D;
in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uSrc;
uniform sampler3D uLut;
uniform sampler2D uGrain;
uniform sampler2D uHal;
uniform sampler2D uBloom;
uniform sampler2D uLocal;
uniform sampler2D uStarTex;
uniform sampler2D uGlowWide;  // Glow: wide blur of the glow light
uniform sampler2D uGlowSrc;   // Glow: the glow light itself (fx resolution, unblurred)
uniform sampler2D uGlowTight; // Glow: tight blur, or the streaks for Motion / Streak
uniform sampler2D uHaze;
uniform sampler2D uCurve;

uniform float uLutSize;
uniform float uIntensity;
uniform vec3 uGain;
// light
uniform float uContrast;
uniform float uHighlights;
uniform float uShadows;
uniform float uWhites;
uniform float uBlacks;
uniform float uFade;
// colour
uniform float uSaturation;
uniform float uVibrance;
uniform float uMixerOn;
uniform vec3 uMixer[8];
uniform float uGradeOn;
uniform vec2 uGradeS;
uniform vec2 uGradeM;
uniform vec2 uGradeH;
uniform float uBalance;
uniform float uBlending;
uniform float uCurveOn;
// detail
uniform float uTexture;
uniform float uClarity;
uniform float uDehaze;
uniform float uSharpen;
uniform float uHdr;
// glow
uniform int uGlowStyle;    // -1 off, else GlowStyle ordinal
uniform float uGlowAmt;
uniform vec3 uGlowTint;    // colour of the glow light (Warmth / Tint; Halation's hue)
uniform float uGlowShow;   // 1: show only the glow light, to see what glows and how far
uniform float uGlowLift;   // Orton: brightness of the soft copy (from Range)
uniform float uGlowT;      // tone where the glow starts (from Range), as in the glow light pass
uniform float uGlowSat;    // 0 white light, 1 each light keeps its own colour
uniform vec3 uLightGain;   // the gain the glow light was measured with (exposure + white balance)
uniform float uTexLod;
uniform sampler2D uGuide;  // the photo's tone at fx resolution, unblurred (Clarity's edge guide)
uniform vec2 uGuideTexel;  // one fx texel, in source uv
uniform float uClarR;      // Clarity radius (Gaussian sigma), in fx texels
uniform sampler2D uHdrBase; // HDR's edge-aware wide tone (r) and its guide tone (g), fx resolution
// film & optics
uniform float uHalation;
uniform vec3 uHalColor;
uniform float uBloomAmt;
uniform float uVignette;
uniform float uAberration;
uniform float uLeak;
uniform float uGrainAmt;
uniform float uGrainScale;
uniform float uColorGrain;
uniform vec2 uAspect;
uniform vec2 uRes;
uniform float uSeed;
// geometry
uniform vec4 uSrcRect;     // crop origin (xy) and size (zw), in the displayed image
uniform int uRot;          // quarter turns clockwise
uniform float uGeoOn;
uniform float uAngle;      // straighten, radians
uniform float uFillScale;
uniform vec2 uKeystone;    // perspective (horizontal, vertical)
uniform float uDispAspect; // displayed (quarter-rotated) width / height
uniform vec2 uFlip;
uniform int uLeakStyle;
// lens
uniform float uDistort;
uniform float uCircle;
uniform float uStar;
uniform float uMist;
uniform float uTilt;
uniform float uFocusY;
uniform float uFocusSize;
uniform float uSoft;
uniform float uSwirl;
// HDR
uniform float uRecover;    // 0..1: read the photo's real HDR light when darkening (highlight recovery)
uniform int uGainPass;     // 1: write the edited photo's gain map instead of its colour
uniform float uHdrAmt;     // 0..1: how much of the photo's HDR headroom the edit keeps
uniform float uOutLogMax;  // log2 of the output gain map's ratio max
uniform float uAroundLod;  // mip level of a wide neighbourhood (HDR estimate without a gain map)
$COMMON
$GAIN

const float BAND_HUES[8] = float[8](29.0, 58.0, 108.0, 142.0, 195.0, 264.0, 296.0, 333.0);

vec3 linToOklab(vec3 c) {
    c = max(c, 0.0);
    float l = pow(0.4122214708 * c.r + 0.5363325363 * c.g + 0.0514459929 * c.b, 1.0 / 3.0);
    float m = pow(0.2119034982 * c.r + 0.6806995451 * c.g + 0.1073969566 * c.b, 1.0 / 3.0);
    float s = pow(0.0883024619 * c.r + 0.2817188376 * c.g + 0.6299787005 * c.b, 1.0 / 3.0);
    return vec3(0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s);
}
vec3 oklabToLin(vec3 o) {
    float l = o.x + 0.3963377774 * o.y + 0.2158037573 * o.z;
    float m = o.x - 0.1055613458 * o.y - 0.0638541728 * o.z;
    float s = o.x - 0.0894841775 * o.y - 1.2914855480 * o.z;
    l = l * l * l; m = m * m * m; s = s * s * s;
    return vec3(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
               -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
               -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s);
}
float hueDiff(float a, float b) { return mod(a - b + 540.0, 360.0) - 180.0; }

// Output (frame) uv -> source texture uv, through crop, straighten, perspective, flip, rotation.
vec2 srcUv(vec2 uv) {
    vec2 r = uSrcRect.xy + uv * uSrcRect.zw;
    if (uGeoOn > 0.5) {
        vec2 p = (r - 0.5) * vec2(uDispAspect, 1.0) / uFillScale;
        float c = cos(uAngle);
        float s = sin(uAngle);
        p = vec2(c * p.x - s * p.y, s * p.x + c * p.y);
        p /= 1.0 + uKeystone.x * p.x + uKeystone.y * p.y;
        r = p / vec2(uDispAspect, 1.0) + 0.5;
    }
    if (uFlip.x > 0.5) r.x = 1.0 - r.x;
    if (uFlip.y > 0.5) r.y = 1.0 - r.y;
    if (uRot == 1) return vec2(r.y, 1.0 - r.x);
    if (uRot == 2) return vec2(1.0 - r.x, 1.0 - r.y);
    if (uRot == 3) return vec2(1.0 - r.y, r.x);
    return r;
}

// Fisheye / wide-angle projection on the frame. Returns frame uv to sample and a circle mask.
vec2 lensUv(vec2 uv, out float mask) {
    mask = 1.0;
    if (uDistort <= 0.0) return uv;
    vec2 p = (uv - 0.5) * uAspect;
    float r = length(p);
    if (r < 1e-5) return uv;
    float a = uDistort * 1.25;
    float rs;
    if (uCircle > 0.5) {
        float R = 0.5 * min(uAspect.x, uAspect.y);
        float rn = r / R;
        mask = 1.0 - smoothstep(0.985, 1.0, rn);
        vec2 dir = p / r;
        vec2 q = abs(dir) / (0.5 * uAspect);
        float rmax = 1.0 / pow(pow(q.x, 4.0) + pow(q.y, 4.0), 0.25);
        rs = tan(a * min(rn, 1.0)) / tan(a) * rmax;
    } else {
        float R = length(0.5 * uAspect);
        rs = tan(a * r / R) / tan(a) * R;
    }
    return 0.5 + p * (rs / r) / uAspect;
}

float bump(float x, float a, float b) {
    float t = clamp((x - a) / (b - a), 0.0, 1.0);
    float s = sin(3.14159265 * t);
    return s * s;
}
vec3 sCurve(vec3 x, float k) {
    vec3 lo = 0.5 * pow(max(2.0 * x, vec3(0.0)), vec3(k));
    vec3 hi = 1.0 - 0.5 * pow(max(2.0 * (1.0 - x), vec3(0.0)), vec3(k));
    return mix(lo, hi, step(0.5, x));
}
float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
float blob(vec2 uv, vec2 c, vec2 s, float k) {
    vec2 d = (uv - c) * s;
    return exp(-dot(d, d) * k);
}
// Soft limit: full strength on small differences, tapering for big ones. Peaks at k / 2.
float tame(float d, float k) { return d / (1.0 + (d * d) / (k * k)); }

// Tone of the source at uv, as the detail tools compare it.
float srcTone(vec2 uv, float lod) { return toneOf(textureLod(uSrc, uv, lod).rgb, uGain); }

// Edge-aware local mean of tone (a bilateral filter): rings of neighbours around uv, each
// counting less the more its tone differs from this pixel's (y0). Neighbours across an edge,
// much brighter or darker, are left out, so the mean follows outlines instead of bleeding
// across them. A plain blur there makes the bright side of every outline glow white and the
// dark side get a dark rim as Texture / Clarity go up. sigma is in texels of `texel`.
float edgeMean(sampler2D t, bool isSrc, vec2 uv, float y0, vec2 texel, float sigma, float lod, int rings) {
    const float RANGE = 1.0 / (2.0 * 0.06 * 0.06);
    float gc = isSrc ? srcTone(uv, lod) : texture(t, uv).r;
    float dc = gc - y0;
    float wc = 0.36 * exp(-dc * dc * RANGE);
    float acc = wc * gc;
    float wsum = wc;
    for (int ring = 0; ring < rings; ring++) {
        float r = 0.6 + 0.7 * float(ring);       // ring radius, in sigmas
        float ws = r * exp(-0.5 * r * r);        // Gaussian weight of the ring's area
        float rot = 0.3927 * float(ring);
        for (int i = 0; i < 8; i++) {
            float a = float(i) * 0.7853982 + rot;
            vec2 q = uv + vec2(cos(a), sin(a)) * (r * sigma) * texel;
            float g = isSrc ? srcTone(q, lod) : texture(t, q).r;
            float d = g - y0;
            float w = ws * exp(-d * d * RANGE);
            acc += w * g;
            wsum += w;
        }
    }
    return (acc + 1e-4 * y0) / (wsum + 1e-4);
}

// Upsamples an fx-resolution edge-aware tone (r) to this pixel, trusting only the texels whose
// guide tone (g) matches this pixel's, so the low resolution doesn't smear it across an edge.
float edgeUpsample(sampler2D t, vec2 uv, float y0) {
    vec2 size = vec2(textureSize(t, 0));
    vec2 p = uv * size - 0.5;
    vec2 f = fract(p);
    ivec2 i0 = ivec2(floor(p));
    ivec2 hi = ivec2(size) - 1;
    float acc = 0.0;
    float wsum = 0.0;
    for (int k = 0; k < 4; k++) {
        ivec2 o = ivec2(k & 1, k >> 1);
        vec2 v = texelFetch(t, clamp(i0 + o, ivec2(0), hi), 0).rg;
        float wb = (o.x == 1 ? f.x : 1.0 - f.x) * (o.y == 1 ? f.y : 1.0 - f.y) + 0.001;
        float d = v.y - y0;
        float w = wb * exp(-d * d * 50.0);
        acc += w * v.x;
        wsum += w;
    }
    return (acc + 1e-4 * y0) / (wsum + 1e-4);
}

// Sharpening with edge detection. A Sobel filter on the 3 x 3 neighbourhood finds edges; the
// unsharp mask is then clamped to that neighbourhood's own darkest and brightest tones, so an
// outline gets crisper but can never overshoot into a white glow or a dark rim. Flat areas (sky,
// skin) are left alone so their noise isn't sharpened, and the strongest outlines, already
// crisp, get a lighter touch. Returns the change in tone.
float sharpenDelta(vec2 uv, float y0) {
    vec2 px = 1.25 / vec2(textureSize(uSrc, 0));
    float n = srcTone(uv + vec2(0.0, -px.y), 0.0);
    float s = srcTone(uv + vec2(0.0, px.y), 0.0);
    float e = srcTone(uv + vec2(px.x, 0.0), 0.0);
    float w = srcTone(uv + vec2(-px.x, 0.0), 0.0);
    float ne = srcTone(uv + vec2(px.x, -px.y), 0.0);
    float nw = srcTone(uv - px, 0.0);
    float se = srcTone(uv + px, 0.0);
    float sw = srcTone(uv + vec2(-px.x, px.y), 0.0);
    float gx = (ne + 2.0 * e + se) - (nw + 2.0 * w + sw);
    float gy = (sw + 2.0 * s + se) - (nw + 2.0 * n + ne);
    float edge = length(vec2(gx, gy)) * 0.25;   // ~ the tone step across this pixel
    float blur = (4.0 * y0 + 2.0 * (n + s + e + w) + ne + nw + se + sw) / 16.0;
    float lo = min(min(min(n, s), min(e, w)), min(min(ne, nw), min(se, sw)));
    float hi = max(max(max(n, s), max(e, w)), max(max(ne, nw), max(se, sw)));
    lo = min(lo, y0);
    hi = max(hi, y0);
    float amt = uSharpen * 3.2 * smoothstep(0.006, 0.03, edge) * (1.0 - 0.45 * smoothstep(0.12, 0.4, edge));
    return clamp(y0 + amt * (y0 - blur), lo, hi) - y0;
}

// Detail, film colour and the user's tone/colour tools. sRGB in, sRGB out.
vec3 grade(vec3 src, vec2 suv, bool detail) {
    vec3 lin = toLin(src) * uGain;
    if (uRecover > 0.0 && uGmOn > 0.5) {
        // HDR highlight recovery: when Exposure or Highlights come down, read the scene's real
        // light from the gain map, so a clipped sky or lamp comes back with its detail and
        // colour instead of turning flat grey. The film's shoulder below rolls it off.
        lin = mix(lin, gmHdr(toLin(src), gmLogBoost(suv)) * uGain, uRecover);
    }
    if (max(uGain.r, max(uGain.g, uGain.b)) > 1.001 || uRecover > 0.0) lin = softClip(lin);
    // Detail tools compare tones before Dehaze, like their references (otherwise Dehaze would
    // read as detail everywhere and shift the whole photo's brightness).
    float y0 = toneOf(src, uGain);

    // Dehaze: remove (or add) the veil estimated from the blurred dark channel.
    if (uDehaze > 0.0) {
        // Never remove more veil than the pixel itself holds (its darkest channel), so
        // shadows keep their detail instead of crushing to black.
        float mn = min(lin.r, min(lin.g, lin.b));
        float t = min(clamp(uDehaze * texture(uHaze, suv).r * 0.95, 0.0, 0.85), mn * 0.85);
        lin = max(lin - t, 0.0) / (1.0 - t);
    } else if (uDehaze < 0.0) {
        lin = mix(lin, vec3(0.62), -uDehaze * 0.42);
    }
    vec3 c = clamp(toSrgb(lin), 0.0, 1.0);

    // Local contrast family, on luminance so colours don't fringe.
    if (detail) {
        float y = dot(c, LUMA);
        float dy = 0.0;
        if (uTexture != 0.0) {
            // Fine-scale mean from the mip chain, edge-aware so outlines don't glow.
            vec2 tx = 1.0 / vec2(textureSize(uSrc, 0));
            float yb = edgeMean(uSrc, true, suv, y0, tx, 0.45 * exp2(uTexLod), max(uTexLod - 1.0, 0.0), 3);
            dy += uTexture * 2.2 * tame(y0 - yb, 0.10);
        }
        if (uSharpen > 0.0) dy += sharpenDelta(suv, y0);
        if (uClarity != 0.0) {
            float mid = 1.0 - pow(abs(2.0 * y - 1.0), 2.0);
            float yb = edgeMean(uGuide, false, suv, y0, uGuideTexel, uClarR, 0.0, 3);
            dy += uClarity * 1.6 * tame(y0 - yb, 0.14) * (0.35 + 0.65 * mid);
        }
        if (uHdr != 0.0) {
            // Wide edge-aware tone: lifts the shadows and tames the sky as whole areas, without
            // the bright band a plain blur leaves along a skyline.
            float yw = edgeUpsample(uHdrBase, suv, y0);
            dy += uHdr * (1.4 * tame(y0 - yw, 0.18) + 0.30 * (0.48 - yw));
        }
        if (dy != 0.0) c = clamp(c + dy, 0.0, 1.0);
        if (uDehaze > 0.0) {
            float yy = dot(c, LUMA);
            c = clamp(mix(vec3(yy), c, 1.0 + uDehaze * 0.25), 0.0, 1.0);
        }
    }

    // Film colour: baked 3D LUT.
    vec3 film = texture(uLut, c * ((uLutSize - 1.0) / uLutSize) + 0.5 / uLutSize).rgb;
    c = mix(c, film, uIntensity);

    // Light.
    if (uContrast != 0.0) c = sCurve(clamp(c, 0.0, 1.0), 1.0 + uContrast * (uContrast > 0.0 ? 0.6 : 0.4));
    float l = dot(c, LUMA);
    c += uShadows * 0.12 * bump(l, 0.0, 0.5) + uHighlights * 0.12 * bump(l, 0.5, 1.0);
    if (uWhites != 0.0 || uBlacks != 0.0) {
        float bp = -uBlacks * 0.10;
        float wp = 1.0 - uWhites * 0.16;
        c = (c - bp) / (wp - bp);
    }
    c = clamp(c, 0.0, 1.0);

    // Point curves (master + channels baked into one RGB lookup).
    if (uCurveOn > 0.5) {
        vec3 t = c * (255.0 / 256.0) + 0.5 / 256.0;
        c = vec3(texture(uCurve, vec2(t.r, 0.5)).r, texture(uCurve, vec2(t.g, 0.5)).g, texture(uCurve, vec2(t.b, 0.5)).b);
    }

    // Vibrance protects already-saturated colours and skin; saturation is uniform.
    if (uVibrance != 0.0) {
        float mx = max(c.r, max(c.g, c.b));
        float mn = min(c.r, min(c.g, c.b));
        float skin = clamp((c.r - c.g) * 5.0, 0.0, 1.0) * clamp((c.g - c.b) * 5.0, 0.0, 1.0);
        float amt = uVibrance * (1.0 - (mx - mn)) * (uVibrance > 0.0 ? 1.0 - 0.6 * skin : 1.0);
        float y = dot(c, LUMA);
        c = mix(vec3(y), c, 1.0 + amt);
    }
    if (uSaturation != 0.0) {
        float y = dot(c, LUMA);
        c = mix(vec3(y), c, 1.0 + uSaturation);
    }
    c = clamp(c, 0.0, 1.0);

    // Colour mixer and grading in OKLab (perceptually even hue bands, no fringing).
    if (uMixerOn > 0.5 || uGradeOn > 0.5) {
        vec3 o = linToOklab(toLin(c));
        if (uMixerOn > 0.5) {
            float C = length(o.yz);
            float h = degrees(atan(o.z, o.y));
            float cw = smoothstep(0.0, 0.05, C);
            vec3 acc = vec3(0.0);
            for (int i = 0; i < 8; i++) {
                float d = hueDiff(h, BAND_HUES[i]);
                float nb = d >= 0.0 ? BAND_HUES[(i + 1) % 8] : BAND_HUES[(i + 7) % 8];
                float span = abs(hueDiff(nb, BAND_HUES[i]));
                float t = clamp(abs(d) / span, 0.0, 1.0);
                float w = cos(1.5707963 * t);
                acc += uMixer[i] * w * w;
            }
            h += acc.x * 32.0 * cw;
            C *= max(0.0, 1.0 + acc.y * cw);
            o.x += acc.z * 0.14 * cw;
            o.yz = C * vec2(cos(radians(h)), sin(radians(h)));
        }
        if (uGradeOn > 0.5) {
            float L = o.x;
            float bal = uBalance * 0.18;
            float bw = 0.06 + uBlending * 0.30;
            float ms = 1.0 - smoothstep(0.42 + bal - bw, 0.42 + bal + bw, L);
            float mh = smoothstep(0.72 + bal - bw, 0.72 + bal + bw, L);
            float mm = clamp(1.0 - ms - mh, 0.0, 1.0);
            o.yz += uGradeS * ms + uGradeM * mm + uGradeH * mh;
        }
        c = clamp(toSrgb(oklabToLin(o)), 0.0, 1.0);
    }
    return c * (1.0 - uFade * 0.10) + uFade * 0.075;
}

// Light gathered over a disc of radius rho (short-side units) around frame uv: a lens's
// defocus. Averaged in light, so bright points spread into discs of light (bokeh).
vec3 discBlur(vec2 uv, float rho) {
    vec2 ts = vec2(textureSize(uSrc, 0)) * uSrcRect.zw;
    float px = rho * min(ts.x, ts.y);
    float lod = log2(max(px * 0.35, 1.0));
    vec3 acc = toLin(textureLod(uSrc, srcUv(uv), lod).rgb);
    float n = 1.0;
    for (int ring = 1; ring <= 2; ring++) {
        float rr = rho * float(ring) * 0.5;
        int taps = ring * 8;
        for (int i = 0; i < 16; i++) {
            if (i >= taps) break;
            float a = (float(i) + 0.5 * float(ring)) * 6.2831853 / float(taps);
            acc += toLin(textureLod(uSrc, srcUv(uv + vec2(cos(a), sin(a)) * rr / uAspect), lod).rgb);
            n += 1.0;
        }
    }
    return toSrgb(acc / n);
}

// Light smeared along the circle through uv round the frame centre, over a length len
// (short-side units): the swirl of a Helios or Petzval lens.
vec3 swirlBlur(vec2 uv, float len) {
    vec2 p = (uv - 0.5) * uAspect;
    vec2 t = normalize(vec2(-p.y, p.x) + vec2(1e-6));
    vec2 ts = vec2(textureSize(uSrc, 0)) * uSrcRect.zw;
    float lod = log2(max(len * min(ts.x, ts.y) / 6.0, 1.0));
    vec3 acc = vec3(0.0);
    for (int i = -6; i <= 6; i++) {
        float f = float(i) / 6.0;
        // Along the arc (a slight inward bend keeps it on the circle).
        vec2 q = p + t * len * f - p * (len * f) * (len * f) / max(2.0 * dot(p, p), 1e-4);
        acc += toLin(textureLod(uSrc, srcUv(0.5 + q / uAspect), lod).rgb);
    }
    return toSrgb(acc / 13.0);
}

vec3 screen(vec3 a, vec3 b) { return 1.0 - (1.0 - clamp(a, 0.0, 1.0)) * (1.0 - clamp(b, 0.0, 1.0)); }

// Glow, in linear light. `layer` returns the glow light on its own.
vec3 applyGlow(vec3 lin, vec2 suv, out vec3 layer) {
    vec3 gs = texture(uGlowSrc, suv).rgb;
    vec3 gt = texture(uGlowTight, suv).rgb;
    vec3 gw = texture(uGlowWide, suv).rgb;
    float a = uGlowAmt;
    if (uGlowStyle == 0) {
        // Bloom: a long-tailed haze (tight core + wide skirt, like a real lens) over the photo.
        layer = (0.4 * gt + 0.6 * gw) * uGlowTint * 1.5;
        return screen(lin, layer * a);
    }
    if (uGlowStyle == 1) {
        // Diffusion happens in front of the film (see diffuse()); here only for SHOW.
        layer = (0.5 * gt + 0.5 * gw) * uGlowTint;
        return lin;
    }
    if (uGlowStyle == 2) {
        // Halation: light bounced back through the film. A coloured halo hugs each light; the
        // light itself (already exposed) doesn't take the colour.
        float h = max(dot(0.65 * gt + 0.35 * gw, LUMA) - 0.6 * dot(gs, LUMA), 0.0);
        layer = uGlowTint * h * 2.6;
        return lin + layer * a;
    }
    if (uGlowStyle == 3) {
        // Orton: the photo screened with itself, multiplied by a brightened soft copy.
        // Luminous, painterly highlights with deep, rich colour.
        vec3 c = toSrgb(lin);
        vec3 b = clamp(toSrgb((0.35 * gt + 0.65 * gw) * uGlowTint * uGlowLift), 0.0, 1.0);
        vec3 cs = 1.0 - (1.0 - clamp(c, 0.0, 1.0)) * (1.0 - clamp(c, 0.0, 1.0));
        vec3 bs = 1.0 - (1.0 - b) * (1.0 - b);
        layer = toLin(bs);
        return toLin(mix(c, cs * bs, a));
    }
    // Motion / Streak: trails of light added over the photo.
    layer = gt * uGlowTint * 1.5;
    return screen(lin, layer * a);
}

// Diffusion filter, in front of the film like the real glass: a share of the light in the
// chosen tones (Range) leaves its place and spreads around it. Detail in those tones softens,
// the shadows next to them lift with their light, and the film then responds to the result.
vec3 diffuse(vec3 src, vec2 suv) {
    vec3 sl = toLin(src);
    float w = smoothstep(uGlowT - 0.22, uGlowT + 0.04, toneOf(src, uLightGain));
    vec3 sel = mix(vec3(dot(sl, LUMA)), sl, uGlowSat) * w;
    vec3 spread = (0.5 * texture(uGlowTight, suv).rgb + 0.5 * texture(uGlowWide, suv).rgb) / max(uLightGain, vec3(1e-3));
    float k = uGlowAmt * 0.8;
    return toSrgb(max(sl + k * (spread * uGlowTint - sel), 0.0));
}

// HDR headroom of the scene at suv, in stops: from the gain map, or estimated without one
// (light that clipped and stands far above its wide surroundings is a light source).
float sceneHeadroom(vec2 suv) {
    if (uGmOn > 0.5) return max(dot(gmLogBoost(suv), LUMA), 0.0);
    vec3 sl = toLin(texture(uSrc, suv).rgb);
    float clip = smoothstep(0.80, 0.98, max(sl.r, max(sl.g, sl.b)));
    float around = dot(toLin(textureLod(uSrc, suv, uAroundLod).rgb), LUMA);
    return clip * clamp(0.8 * log2((dot(sl, LUMA) + 0.02) / (around + 0.02)), 0.0, 3.0);
}

// The edited photo's gain map: the scene's own HDR headroom carried through the edit, plus the
// light the edit adds (stars, streaks, bloom, mist, halation, Glow are light, so they shine
// above SDR white on an HDR screen).
float editedHeadroom(vec2 uv, vec2 suv, float circle) {
    float L = sceneHeadroom(suv) * uHdrAmt;
    float add = 0.0;
    if (uStar > 0.0) add += dot(texture(uStarTex, suv).rgb, LUMA);
    if (uBloomAmt > 0.0) add += dot(texture(uBloom, suv).rgb, LUMA) * uBloomAmt * 1.4;
    if (uMist > 0.0) add += dot(texture(uBloom, suv).rgb, LUMA) * uMist * 1.5;
    if (uHalation > 0.0) add += dot(texture(uHal, suv).rgb, LUMA) * uHalation * 2.0;
    if (uGlowStyle >= 0 && uGlowStyle != 3) {
        vec3 layer;
        applyGlow(vec3(0.0), suv, layer);
        add += dot(layer, LUMA) * uGlowAmt;
    }
    L += log2(1.0 + 1.5 * add) * uHdrAmt;
    return clamp(L / uOutLogMax, 0.0, 1.0) * circle;
}

void main() {
    float circle;
    vec2 uv = lensUv(vUv, circle);
    vec2 suv = srcUv(uv);
    if (uGainPass == 1) {
        fragColor = vec4(vec3(editedHeadroom(uv, suv, circle)), 1.0);
        return;
    }

    // Lens: lateral chromatic aberration grows toward the corners.
    vec3 src;
    if (uAberration > 0.0) {
        vec2 d = uv - 0.5;
        float k = uAberration * 0.006;
        src.r = texture(uSrc, srcUv(0.5 + d * (1.0 + k))).r;
        src.g = texture(uSrc, suv).g;
        src.b = texture(uSrc, srcUv(0.5 + d * (1.0 - k))).b;
    } else {
        src = texture(uSrc, suv).rgb;
    }
    if (uGlowStyle == 1) src = diffuse(src, suv);
    vec3 c = grade(src, suv, true);

    // Lens blur, all mixed in light (as a lens does), so bright points spread instead of greying.
    if (uTilt > 0.0 || uSoft > 0.0 || uSwirl > 0.0) {
        vec3 cl = toLin(c);
        if (uSoft > 0.0) {
            // Soft focus (Velvet, portrait soft lenses): uncorrected spherical aberration lays a
            // glowing, out-of-focus copy of the image over the sharp one; highlights bloom.
            vec3 halo = texture(uLocal, suv).rgb / max(uGain, vec3(1e-3));
            cl = mix(cl, toLin(grade(toSrgb(halo), suv, false)), uSoft * 0.45);
        }
        if (uTilt > 0.0) {
            // Tilt-shift: the plane of focus is tilted, so blur grows steadily with distance
            // from the focus band (miniature look), as discs of light (bokeh).
            float d = abs(vUv.y - uFocusY) - uFocusSize * 0.5;
            float rho = uTilt * 0.032 * clamp(d / 0.45, 0.0, 1.3);
            if (rho > 0.0005) {
                vec3 b = toLin(grade(discBlur(uv, rho), suv, false));
                cl = mix(cl, b, smoothstep(0.0005, 0.003, rho));
            }
        }
        if (uSwirl > 0.0) {
            // Swirl (Helios 44, Petzval, toy lenses): cat's-eye vignetting and off-axis
            // aberrations smear the image along circles round the centre, growing to the edges.
            vec2 p = (uv - 0.5) * uAspect;
            float r = length(p) / length(0.5 * uAspect);
            float len = uSwirl * 0.04 * pow(smoothstep(0.18, 1.0, r), 1.5);
            if (len > 0.0005) {
                vec3 b = toLin(grade(swirlBlur(uv, len), suv, false));
                cl = mix(cl, b, smoothstep(0.0005, 0.003, len));
            }
        }
        c = clamp(toSrgb(cl), 0.0, 1.0);
        if (uTilt > 0.0) {
            float y = dot(c, LUMA);
            c = clamp(mix(vec3(y), c, 1.0 + 0.18 * uTilt), 0.0, 1.0);
        }
    }

    // Optics in linear light.
    vec3 lin = toLin(c);
    if (uGlowStyle >= 0) {
        vec3 layer;
        lin = applyGlow(lin, suv, layer);
        if (uGlowShow > 0.5) {
            // Show glow: just the light that glows, on black.
            fragColor = vec4(clamp(toSrgb(layer * (0.6 + uGlowAmt)), 0.0, 1.0), 1.0);
            return;
        }
    }
    if (uBloomAmt > 0.0) {
        vec3 b = texture(uBloom, suv).rgb;
        lin = 1.0 - (1.0 - lin) * (1.0 - clamp(b * uBloomAmt * 1.4, 0.0, 1.0));
    }
    if (uMist > 0.0) {
        // Black Pro-Mist: particles in the glass scatter a share of each light's energy into a
        // wide soft halo around it, so the light itself eases a little as its halo forms. The
        // black specks absorb the scatter from dark tones, so shadows stay deep (a white mist
        // would fog them); only a faint veil eases contrast.
        vec3 b = texture(uBloom, suv).rgb;
        float yl = dot(lin, LUMA);
        float s = uMist * 0.55;
        lin = max(lin - s * lin * smoothstep(0.35, 0.95, yl), 0.0);
        lin = 1.0 - (1.0 - clamp(lin, 0.0, 1.0)) * (1.0 - clamp(b * s * 2.2, 0.0, 1.0));
        lin = lin * (1.0 - 0.04 * uMist) + 0.008 * uMist;
    }
    if (uHalation > 0.0) {
        float h = dot(texture(uHal, suv).rgb, LUMA);
        lin += uHalColor * h * uHalation * 2.0;
    }
    if (uStar > 0.0) {
        vec3 st = texture(uStarTex, suv).rgb;
        lin = 1.0 - (1.0 - clamp(lin, 0.0, 1.0)) * (1.0 - clamp(st, 0.0, 1.0));
    }
    if (uLeak > 0.0) {
        vec3 leak;
        if (uLeakStyle == 1) {          // red corner
            leak = vec3(1.0, 0.10, 0.06) * 1.3 * blob(vUv, vec2(1.02, 1.00), vec2(1.15, 0.95), 4.0);
            leak += vec3(1.0, 0.45, 0.12) * 0.8 * blob(vUv, vec2(0.96, 1.04), vec2(1.9, 1.6), 6.0);
        } else if (uLeakStyle == 2) {   // sunset from the top edge
            leak = vec3(1.0, 0.55, 0.15) * blob(vUv, vec2(0.62, -0.10), vec2(0.8, 2.2), 6.0);
            leak += vec3(1.0, 0.22, 0.10) * 0.7 * blob(vUv, vec2(0.20, -0.04), vec2(1.4, 2.8), 8.0);
        } else if (uLeakStyle == 3) {   // film burn: hot white-orange streak on the left
            leak = vec3(1.0, 0.85, 0.65) * blob(vUv, vec2(-0.02, 0.50), vec2(5.0, 0.7), 5.0);
            leak += vec3(1.0, 0.35, 0.05) * blob(vUv, vec2(0.02, 0.45), vec2(2.4, 0.6), 5.0);
        } else {                        // amber edge
            leak = vec3(1.0, 0.30, 0.05) * blob(vUv, vec2(-0.10, 0.32), vec2(1.7, 0.9), 6.0);
            leak += vec3(1.0, 0.62, 0.22) * 0.8 * blob(vUv, vec2(-0.03, 0.66), vec2(2.4, 1.3), 9.0);
            leak += vec3(1.0, 0.12, 0.08) * 0.5 * blob(vUv, vec2(1.06, 0.88), vec2(2.0, 1.5), 8.0);
        }
        lin = 1.0 - (1.0 - clamp(lin, 0.0, 1.0)) * (1.0 - clamp(leak * uLeak, 0.0, 1.0));
    }
    if (uVignette > 0.0) {
        vec2 d = (vUv - 0.5) * uAspect;
        float r = length(d) / length(0.5 * uAspect);
        lin *= 1.0 - uVignette * 0.88 * pow(smoothstep(0.22, 1.12, r), 1.5);
    }
    lin *= circle;
    c = toSrgb(lin);

    // Grain: two clumped octaves of luminance noise plus optional dye-cloud colour noise,
    // strongest in the mid-tones like real negative film.
    if (uGrainAmt > 0.0) {
        vec2 gp = vUv * uAspect * uGrainScale + uSeed;
        vec4 n1 = (texture(uGrain, gp) - 0.5) * 6.0;
        vec4 n2 = (texture(uGrain, gp * 0.43 + vec2(0.31, 0.71)) - 0.5) * 6.0;
        float n3 = (texture(uGrain, gp * 2.37 + vec2(0.13, 0.57)).a - 0.5) * 6.0;
        float mono = n1.a * 0.75 + n2.a * 0.5 + n3 * 0.35;
        vec3 chroma = n1.rgb - (n1.r + n1.g + n1.b) / 3.0;
        vec3 g = vec3(mono) + chroma * uColorGrain * 1.1;
        float y = clamp(dot(c, LUMA), 0.0, 1.0);
        float w = 0.15 + 0.85 * pow(clamp(4.0 * y * (1.0 - y), 0.0, 1.0), 0.7);
        c += g * uGrainAmt * 0.07 * w * circle;
    }

    c += (hash(vUv * uRes + uSeed * 17.0) - 0.5) / 255.0;
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
}
