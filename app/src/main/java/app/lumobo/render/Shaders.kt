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
"""

    /**
     * Extracts light for the glow passes. Mode 0: halation highlights, 1: bloom, 2: star /
     * streak glints (only the brightest points), 3: the whole image (lens blur).
     */
    const val PREFILTER = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uSrc;
uniform vec3 uGain;
uniform int uMode;
uniform vec2 uPeak;   // neighbourhood radius in uv, for glint detection
$COMMON
void main() {
    vec3 lin = toLin(texture(uSrc, vUv).rgb) * uGain;
    float l = dot(lin, LUMA);
    float w;
    if (uMode == 0) w = smoothstep(0.50, 0.98, l);
    else if (uMode == 1) w = smoothstep(0.18, 0.95, l);
    else if (uMode == 2) {
        // Glints: bright AND brighter than the surroundings, so sparkles on water or
        // street lamps make stars while big bright areas (sky, windows) don't smear.
        float around = 0.0;
        for (int i = 0; i < 8; i++) {
            float a = float(i) * 0.7853982;
            vec2 o = vec2(cos(a), sin(a)) * uPeak;
            around += dot(toLin(textureLod(uSrc, vUv + o, 2.0).rgb) * uGain, LUMA);
        }
        around /= 8.0;
        float peak = max(l - around, 0.0);
        float k = smoothstep(0.6, 1.0, l) * smoothstep(0.02, 0.3, peak);
        w = k * k * 3.0;
    }
    else w = 1.0;
    fragColor = vec4(lin * w, 1.0);
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
    vec3 acc = vec3(0.0);
    float wsum = 0.0;
    float k = -1.0 / (2.0 * uSigma * uSigma);
    for (int i = -uRadius; i <= uRadius; i++) {
        float fi = float(i);
        float w = exp(fi * fi * k);
        acc += texture(uTex, vUv + uStep * fi).rgb * w;
        wsum += w;
    }
    fragColor = vec4(acc / wsum, 1.0);
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
void main() {
    vec3 acc = vec3(0.0);
    for (int i = 1; i <= uTaps; i++) {
        float fi = float(i);
        float w = exp(-fi * uDecay);
        acc += (texture(uTex, vUv + uDir * fi).rgb + texture(uTex, vUv - uDir * fi).rgb) * w;
    }
    // Normalise by the kernel's total weight so rays carry the source's energy, not 30x it.
    fragColor = vec4(acc * (1.0 - exp(-uDecay)) * uTint, 1.0);
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
uniform sampler2D uLensBlur;
uniform sampler2D uStarTex;

uniform float uLutSize;
uniform float uIntensity;
uniform vec3 uGain;
uniform float uContrast;
uniform float uSaturation;
uniform float uFade;
uniform float uHighlights;
uniform float uShadows;
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
uniform vec4 uSrcRect;     // crop origin (xy) and size (zw), in the displayed image
uniform int uRot;          // quarter turns clockwise
uniform float uAngle;      // straighten, radians
uniform float uFillScale;
uniform float uDispAspect; // displayed (quarter-rotated) width / height
uniform vec2 uFlip;
uniform int uLeakStyle;
// lens
uniform float uDistort;
uniform float uCircle;
uniform float uStar;
uniform float uTilt;
uniform float uFocusY;
uniform float uFocusSize;
uniform float uSoft;
uniform float uSwirl;
$COMMON

// Output (frame) uv -> source texture uv, through crop, straighten, flip, then rotation.
vec2 srcUv(vec2 uv) {
    vec2 r = uSrcRect.xy + uv * uSrcRect.zw;
    if (uAngle != 0.0) {
        vec2 p = (r - 0.5) * vec2(uDispAspect, 1.0);
        float c = cos(uAngle);
        float s = sin(uAngle);
        p = mat2(c, s, -s, c) * p / uFillScale;
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
        // Fill the circle with the whole frame: the rim maps to the frame edge in each direction.
        vec2 dir = p / r;
        // Smooth (superellipse) boundary instead of a hard rectangle, so lines don't crease.
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

// Film colour: exposure/WB in linear, LUT, then the user's tone sliders. sRGB in, sRGB out.
vec3 grade(vec3 src) {
    vec3 lin = toLin(src) * uGain;
    if (max(uGain.r, max(uGain.g, uGain.b)) > 1.001) lin = softClip(lin);
    vec3 c = clamp(toSrgb(lin), 0.0, 1.0);
    vec3 film = texture(uLut, c * ((uLutSize - 1.0) / uLutSize) + 0.5 / uLutSize).rgb;
    c = mix(c, film, uIntensity);
    if (uContrast != 0.0) c = sCurve(clamp(c, 0.0, 1.0), 1.0 + uContrast * (uContrast > 0.0 ? 0.6 : 0.4));
    float l = dot(c, LUMA);
    c += uShadows * 0.12 * bump(l, 0.0, 0.5) + uHighlights * 0.12 * bump(l, 0.5, 1.0);
    if (uSaturation != 0.0) {
        float y = dot(c, LUMA);
        c = mix(vec3(y), c, 1.0 + uSaturation);
    }
    c = clamp(c, 0.0, 1.0);
    return c * (1.0 - uFade * 0.10) + uFade * 0.075;
}

void main() {
    float circle;
    vec2 uv = lensUv(vUv, circle);
    vec2 suv = srcUv(uv);

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
    vec3 c = grade(src);

    // Lens blur: tilt-shift band, soft-focus diffusion, swirling edges.
    if (uTilt > 0.0 || uSoft > 0.0 || uSwirl > 0.0) {
        float m = 0.0;
        if (uTilt > 0.0) {
            float d = abs(vUv.y - uFocusY);
            m = max(m, smoothstep(uFocusSize * 0.5, uFocusSize * 0.5 + 0.28, d) * uTilt);
        }
        if (uSoft > 0.0) m = max(m, uSoft * 0.45);
        vec3 bl;
        if (uSwirl > 0.0) {
            vec2 p = (uv - 0.5) * uAspect;
            float r = length(p) / length(0.5 * uAspect);
            float e = smoothstep(0.28, 0.95, r) * uSwirl;
            m = max(m, e * 0.9);
            vec3 acc = vec3(0.0);
            for (int i = -3; i <= 3; i++) {
                float ang = float(i) * 0.018 * e;
                float cs = cos(ang);
                float sn = sin(ang);
                vec2 q = mat2(cs, sn, -sn, cs) * p;
                acc += texture(uLensBlur, srcUv(0.5 + q / uAspect)).rgb;
            }
            bl = acc / 7.0;
        } else {
            bl = texture(uLensBlur, suv).rgb;
        }
        if (m > 0.0) c = mix(c, grade(toSrgb(bl)), clamp(m, 0.0, 1.0));
        if (uTilt > 0.0) {
            float y = dot(c, LUMA);
            c = clamp(mix(vec3(y), c, 1.0 + 0.18 * uTilt), 0.0, 1.0);
        }
    }

    // Optics in linear light.
    vec3 lin = toLin(c);
    if (uBloomAmt > 0.0) {
        vec3 b = texture(uBloom, suv).rgb;
        lin = 1.0 - (1.0 - lin) * (1.0 - clamp(b * uBloomAmt * 1.4, 0.0, 1.0));
    }
    if (uHalation > 0.0) {
        float h = dot(texture(uHal, suv).rgb, LUMA);
        lin += uHalColor * h * uHalation * 2.0;
    }
    if (uStar > 0.0) {
        vec3 st = texture(uStarTex, suv).rgb;
        lin = 1.0 - (1.0 - lin) * (1.0 - clamp(st * uStar, 0.0, 1.0));
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
