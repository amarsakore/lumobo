package app.emulsion.render

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

    /** Extracts the light that will spread: highlights for halation, a broader range for bloom. */
    const val PREFILTER = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uSrc;
uniform vec3 uGain;
uniform int uMode;
$COMMON
void main() {
    vec3 lin = toLin(texture(uSrc, vUv).rgb) * uGain;
    float l = dot(lin, LUMA);
    float w = uMode == 0 ? smoothstep(0.50, 0.98, l) : smoothstep(0.18, 0.95, l);
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
$COMMON

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

void main() {
    vec2 uv = vUv;

    // Lens: lateral chromatic aberration grows toward the corners.
    vec3 src;
    if (uAberration > 0.0) {
        vec2 d = uv - 0.5;
        float k = uAberration * 0.006;
        src.r = texture(uSrc, 0.5 + d * (1.0 + k)).r;
        src.g = texture(uSrc, uv).g;
        src.b = texture(uSrc, 0.5 + d * (1.0 - k)).b;
    } else {
        src = texture(uSrc, uv).rgb;
    }

    // Exposure + white balance in linear light, with a film-like shoulder.
    vec3 lin = toLin(src) * uGain;
    if (max(uGain.r, max(uGain.g, uGain.b)) > 1.001) lin = softClip(lin);
    vec3 c = clamp(toSrgb(lin), 0.0, 1.0);

    // Film colour: baked 3D LUT.
    vec3 film = texture(uLut, c * ((uLutSize - 1.0) / uLutSize) + 0.5 / uLutSize).rgb;
    c = mix(c, film, uIntensity);

    // User tone.
    if (uContrast != 0.0) c = sCurve(clamp(c, 0.0, 1.0), 1.0 + uContrast * (uContrast > 0.0 ? 0.6 : 0.4));
    float l = dot(c, LUMA);
    c += uShadows * 0.12 * bump(l, 0.0, 0.5) + uHighlights * 0.12 * bump(l, 0.5, 1.0);
    if (uSaturation != 0.0) {
        float y = dot(c, LUMA);
        c = mix(vec3(y), c, 1.0 + uSaturation);
    }
    c = clamp(c, 0.0, 1.0);
    c = c * (1.0 - uFade * 0.10) + uFade * 0.075;

    // Optics in linear light.
    lin = toLin(c);
    if (uBloomAmt > 0.0) {
        vec3 b = texture(uBloom, uv).rgb;
        lin = 1.0 - (1.0 - lin) * (1.0 - clamp(b * uBloomAmt * 1.4, 0.0, 1.0));
    }
    if (uHalation > 0.0) {
        float h = dot(texture(uHal, uv).rgb, LUMA);
        lin += uHalColor * h * uHalation * 2.0;
    }
    if (uLeak > 0.0) {
        vec3 leak = vec3(1.0, 0.30, 0.05) * exp(-dot((uv - vec2(-0.10, 0.32)) * vec2(1.7, 0.9), (uv - vec2(-0.10, 0.32)) * vec2(1.7, 0.9)) * 6.0);
        leak += vec3(1.0, 0.62, 0.22) * 0.8 * exp(-dot((uv - vec2(-0.03, 0.66)) * vec2(2.4, 1.3), (uv - vec2(-0.03, 0.66)) * vec2(2.4, 1.3)) * 9.0);
        leak += vec3(1.0, 0.12, 0.08) * 0.5 * exp(-dot((uv - vec2(1.06, 0.88)) * vec2(2.0, 1.5), (uv - vec2(1.06, 0.88)) * vec2(2.0, 1.5)) * 8.0);
        lin = 1.0 - (1.0 - clamp(lin, 0.0, 1.0)) * (1.0 - clamp(leak * uLeak, 0.0, 1.0));
    }
    if (uVignette > 0.0) {
        vec2 d = (uv - 0.5) * uAspect;
        float r = length(d) / length(0.5 * uAspect);
        lin *= 1.0 - uVignette * 0.88 * pow(smoothstep(0.22, 1.12, r), 1.5);
    }
    c = toSrgb(lin);

    // Grain: two clumped octaves of luminance noise plus optional dye-cloud colour noise,
    // strongest in the mid-tones like real negative film.
    if (uGrainAmt > 0.0) {
        vec2 gp = uv * uAspect * uGrainScale + uSeed;
        vec4 n1 = (texture(uGrain, gp) - 0.5) * 6.0;
        vec4 n2 = (texture(uGrain, gp * 0.43 + vec2(0.31, 0.71)) - 0.5) * 6.0;
        float n3 = (texture(uGrain, gp * 2.37 + vec2(0.13, 0.57)).a - 0.5) * 6.0;
        float mono = n1.a * 0.75 + n2.a * 0.5 + n3 * 0.35;
        vec3 chroma = n1.rgb - (n1.r + n1.g + n1.b) / 3.0;
        vec3 g = vec3(mono) + chroma * uColorGrain * 1.1;
        float y = clamp(dot(c, LUMA), 0.0, 1.0);
        float w = 0.15 + 0.85 * pow(clamp(4.0 * y * (1.0 - y), 0.0, 1.0), 0.7);
        c += g * uGrainAmt * 0.07 * w;
    }

    c += (hash(uv * uRes + uSeed * 17.0) - 0.5) / 255.0;
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
}
