#version 120

// ---- 画面のコピー（オーバーレイを描く前のもの）----
uniform sampler2D uColorTex;
uniform sampler2D uDepthTex;
uniform vec2 uViewSize;          // コピーしたテクスチャの解像度

// ---- 行列 ----
uniform mat4 uProj;
uniform mat4 uModelView;

// ---- ノイズ空間の原点（mod 256 済み）と細かさ ----
uniform vec2  uPuddleOrigin;
uniform vec2  uRippleOrigin;
uniform vec2  uDownfallOrigin;   // 風で流れる分を含む
uniform float uPuddleScale;
uniform float uRippleScale;      // 1ブロックあたりのセル数
uniform float uDownfallScale;

// ---- 状態 ----
uniform float uTime;             // 波紋の時間（周期単位、0..256 でループ）
uniform float uWetness;          // 0..1
uniform float uRain;             // 0..1
uniform float uFogStart;
uniform float uFogEnd;

// ---- 環境 ----
uniform vec3  uSkyColor;
uniform vec3  uFogColor;
uniform vec3  uSunDir;
uniform float uSunBright;

// ---- 見た目の調整 ----
uniform float uRadius;
uniform float uDarken;           // 乗算の暗さ
uniform float uDarkenGamma;      // ガンマ暗化の強さ（濃く・彩度が残る暗化）
uniform float uReflect;
uniform float uSpecular;
uniform float uPuddleMin;
uniform float uPuddleMax;
uniform float uRoughness;
uniform float uBlur;
uniform float uRippleStrength;
uniform float uRippleDensity;

uniform vec2  uBroadOrigin;
uniform float uBroadScale;

varying float vPuddle;
varying vec3  vRel;
varying vec3  vView;
varying float vExp;

const float HASH_PERIOD = 256.0;
const int   SSR_STEPS   = 32;
const int   SSR_REFINE  = 5;
const vec3  WATER_TINT  = vec3(0.80, 0.87, 0.94);

// ============================ ノイズ ============================

float hash1(vec2 p) {
    p = mod(p, HASH_PERIOD);
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

vec2 hash2(vec2 p) {
    p = mod(p, HASH_PERIOD);
    return fract(sin(vec2(dot(p, vec2(127.1, 311.7)),
    dot(p, vec2(269.5, 183.3)))) * 43758.5453123);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash1(i);
    float b = hash1(i + vec2(1.0, 0.0));
    float c = hash1(i + vec2(0.0, 1.0));
    float d = hash1(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * vnoise(p);
        p *= 2.0;
        a *= 0.5;
    }
    return v;
}

// ============================ 波紋 ============================
vec2 rippleGrad(vec2 p, float t, float density) {
    vec2 g = vec2(0.0);
    vec2 cell = floor(p);
    for (int i = -1; i <= 1; i++) {
        for (int j = -1; j <= 1; j++) {
            vec2 c = cell + vec2(float(i), float(j));
            float phase = hash1(c + vec2(37.3, 11.9));
            float tt = t + phase;
            float k = floor(tt);
            float age = tt - k;
            float km = mod(k, HASH_PERIOD);

            float roll = hash1(c + vec2(km * 7.13, km * 3.71));
            if (roll <= density) {
                vec2 h = hash2(c + vec2(km * 1.37, km * 2.11));
                vec2 center = c + 0.2 + h * 0.6;
                vec2 d = p - center;
                float r = length(d) + 1e-4;

                float rad = age * 0.85;
                float w = r - rad;
                float env = exp(-110.0 * w * w) * (1.0 - age) * (1.0 - age);
                float dwdr = cos(w * 48.0) * 48.0 * env;
                g += (d / r) * dwdr;
            }
        }
    }
    return g;
}

// ============================ 深度 ============================

float linearDepth(float d) {
    float ndc = d * 2.0 - 1.0;
    return -uProj[3][2] / (ndc + uProj[2][2]);
}

// ============================ SSR ============================

vec3 sampleBlur(vec2 uv, float r) {
    vec3 c = texture2D(uColorTex, uv).rgb;
    if (r < 0.0006) return c;
    float ang = hash1(gl_FragCoord.xy) * 6.2831853;
    vec3 sum = c;
    for (int i = 0; i < 6; i++) {
        float a = ang + float(i) * 1.0471976;
        float rr = r * (0.45 + 0.55 * fract(float(i) * 0.618034));
        vec2 o = vec2(cos(a), sin(a)) * rr;
        sum += texture2D(uColorTex, clamp(uv + o, vec2(0.002), vec2(0.998))).rgb;
    }
    return sum / 7.0;
}

void traceSSR(vec3 origin, vec3 dir, float blurBase, out vec3 outCol, out float outWeight) {
    outCol = vec3(0.0);
    outWeight = 0.0;

    vec3 p = origin + dir * 0.06;
    float stepLen = 0.35;

    for (int i = 0; i < SSR_STEPS; i++) {
        vec3 prev = p;
        p += dir * stepLen;
        stepLen *= 1.2;

        vec4 clip = uProj * vec4(p, 1.0);
        if (clip.w <= 0.0) return;
        vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
        if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) return;

        float sceneZ = linearDepth(texture2D(uDepthTex, uv).r);
        float delta = sceneZ - p.z;

        if (delta > 0.0 && delta < stepLen * 3.0 + 0.5) {
            vec3 lo = prev;
            vec3 hi = p;
            for (int k = 0; k < SSR_REFINE; k++) {
                vec3 mid = (lo + hi) * 0.5;
                vec4 c2 = uProj * vec4(mid, 1.0);
                vec2 uv2 = c2.xy / c2.w * 0.5 + 0.5;
                if (linearDepth(texture2D(uDepthTex, uv2).r) - mid.z > 0.0) hi = mid;
                else lo = mid;
            }
            vec4 c3 = uProj * vec4(hi, 1.0);
            vec2 uv3 = c3.xy / c3.w * 0.5 + 0.5;

            float rayLen = length(hi - origin);
            outCol = sampleBlur(uv3, blurBase * clamp(rayLen * 0.12, 0.15, 1.0));

            vec2 e = abs(uv3 - 0.5) * 2.0;
            outWeight = 1.0 - smoothstep(0.75, 1.0, max(e.x, e.y));
            return;
        }
    }
}

// ============================ main ============================

void main() {
    float dist = length(vRel.xz);
    float edge = 1.0 - smoothstep(uRadius * 0.65, uRadius, dist);
    if (edge <= 0.002) discard;
    if (uFogEnd > uFogStart) edge *= clamp((uFogEnd - length(vView)) / (uFogEnd - uFogStart), 0.0, 1.0);

    // 雨への露出度。屋根の下は Java 側で小さい値が入ってくる。
    float exposure = clamp(vExp, 0.0, 1.0);
    if (uWetness * exposure <= 0.003) discard;

    // このピクセルの元の地面の色（オーバーレイ前のコピー）
    vec3 base = texture2D(uColorTex, gl_FragCoord.xy / uViewSize).rgb;

    // 画面上でのセルの細かさ（遠いほど大きい）。分岐の外で取る。
    float lod = length(fwidth(vRel.xz * uRippleScale));

    // ---- 雨の濃淡：大きなパッチが風で流れていく ----
    float downfall = smoothstep(0.30, 0.70, fbm(vRel.xz * uDownfallScale + uDownfallOrigin));

    // 濃いパッチの下ほど、より濡れる
    float w = uWetness * exposure;
    w = clamp(w + downfall * (1.0 - w) * 0.30 * clamp(uRain, 0.0, 1.0) * exposure, 0.0, 1.0);

    // ---- 濡れ／水たまりの分布（ワールド座標の連続ノイズ） ----
    vec2 pp = vRel.xz * uPuddleScale + uPuddleOrigin;
    float n     = fbm(pp);
    float broad = fbm(vRel.xz * (uPuddleScale * uBroadScale) + uBroadOrigin + vec2(17.0, 5.0));

    float level  = mix(uPuddleMin, uPuddleMax, w);
    float puddle = 1.0 - smoothstep(level - 0.04, level + 0.06, n);
    puddle *= smoothstep(0.30, 0.75, w);
    puddle *= vPuddle;

    float damp = w * (0.6 + 0.4 * broad);

    // ---- 法線 ----
    vec3 nrm = vec3(0.0, 1.0, 0.0);
    // 微細な凹凸。濡れるほど表面が平らになる
    vec2 micro = vec2(vnoise(pp * 16.0), vnoise(pp * 16.0 + vec2(31.7, 12.3))) - 0.5;
    nrm.xz += micro * 0.12 * (1.0 - 0.7 * w * w);

    // 波紋は「雨が直接当たる水たまり」の中だけ
    float rainHit = smoothstep(0.85, 1.0, exposure) * clamp(uRain, 0.0, 1.0);
    if (rainHit > 0.001 && puddle > 0.01) {
        vec2 rp = vRel.xz * uRippleScale + uRippleOrigin;
        // 濃いパッチでは波紋が増える
        float dens = clamp(uRippleDensity * rainHit * (0.35 + 1.3 * downfall), 0.0, 0.6);
        vec2 g = rippleGrad(rp, uTime, dens);

        float lodFade  = 1.0 / (1.0 + lod * 4.0);      // 遠くは弱める（ちらつき防止）
        float contrast = mix(0.6, 1.3, w);              // 濡れているほどはっきり
        nrm.xz -= g * uRippleStrength * puddle * lodFade * contrast;
    }
    nrm = normalize(nrm);

    // ---- 反射 ----
    vec3 viewDirW = normalize(vRel);
    vec3 reflW = reflect(viewDirW, nrm);
    reflW.y = abs(reflW.y);

    float cosT = clamp(dot(nrm, -viewDirW), 0.0, 1.0);
    float fres = min(0.02 + 0.98 * pow(1.0 - cosT, 5.0), 0.7);

    float reflMask = smoothstep(0.10, 0.85, puddle);
    reflMask = max(reflMask, damp * 0.15 * vPuddle);
    float rough = mix(0.9, uRoughness, smoothstep(0.0, 0.6, reflMask));

    float reflAmt = clamp(uReflect * fres * reflMask, 0.0, 0.85);

    vec3 reflCol = vec3(0.0);
    if (reflAmt > 0.01) {
        vec3 skyRefl = mix(uFogColor, uSkyColor, clamp(reflW.y * 2.2, 0.0, 1.0));
        vec3 reflV = normalize(mat3(uModelView) * reflW);
        vec3 ssrCol;
        float hit;
        traceSSR(vView, reflV, uBlur * rough * 0.03, ssrCol, hit);
        reflCol = mix(skyRefl, ssrCol, hit) * WATER_TINT;
    }

    float spec = pow(max(dot(reflW, uSunDir), 0.0), 160.0)
    * uSunBright * uSpecular * reflMask * (1.0 - 0.5 * rough);

    // ---- 濡れた地面の色 ----
    // 濡れ度が低いうちに暗化が最大まで進む。ガンマで暗くするので色が濃く・彩度が残る。
    float df = clamp(w / 0.2, 0.0, 1.0);
    vec3 wetBase = pow(max(base, vec3(0.0001)), vec3(1.0 + uDarkenGamma * df));
    wetBase *= 1.0 - clamp(uDarken * damp * (0.5 + 0.5 * puddle), 0.0, 0.9);

    vec3 wetFinal = mix(wetBase, reflCol, reflAmt) + vec3(spec);

    // 範囲の端は元の色へ滑らかに戻す。元の色を自前で混ぜるので不透明で出力する。
    gl_FragColor = vec4(mix(base, wetFinal, edge), 1.0);
}
