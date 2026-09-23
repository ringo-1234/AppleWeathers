#version 120

// ---- 描画済みの深度（地形・山・エンティティより手前の雲は描かない）----
uniform sampler2D uDepthTex;
uniform vec2  uViewSize;
uniform float uHasDepth;         // 1.0 = 深度あり

// ---- 行列 ----
uniform mat4 uProj;
uniform mat4 uModelView;

// ---- 雲の位置 ----
uniform float uEyeRel;           // 目の高さ（足元基準）
uniform float uCloudRel;         // 雲の高さ（足元基準）
uniform vec2  uOrigin;           // ノイズ空間でのカメラ位置（風で流れた分を含む、mod 256 済み）
uniform float uScale;            // ノイズ単位 / ブロック

// ---- 雲の見た目（CloudStyle）----
uniform float uCoverage;
uniform float uOpacity;
uniform float uSoftness;
uniform float uShade;
uniform vec3  uLit;              // 日向の色
uniform vec3  uDark;             // 日陰の色
uniform float uVeil;             // 空のベール 0..1（曇天の空全体を覆う膜）

uniform float uFlash;            // 雷のフラッシュ 0..1
uniform vec3  uFlashDir;         // 稲妻の向き（ワールド）

// ---- 環境 ----
uniform vec3  uFogColor;
uniform vec3  uSunDir;           // 夜は月の向き
uniform float uDay;              // 0(夜)..1(昼)

varying vec2 vNdc;

const float HASH_PERIOD = 256.0;

// ============================ ノイズ ============================

float hash1(vec2 p) {
    p = mod(p, HASH_PERIOD);
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
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

float linearDepth(float d) {
    float ndc = d * 2.0 - 1.0;
    return -uProj[3][2] / (ndc + uProj[2][2]);
}

// ============================ 色の共通処理 ============================

// 朝夕は赤み、夜は暗い青にする
vec3 applyDayNight(vec3 base, float sunH) {
    float warmth = (1.0 - smoothstep(0.02, 0.40, sunH)) * uDay;
    vec3 dayCol = base * mix(vec3(1.0), vec3(1.20, 0.78, 0.58), warmth);
    vec3 nightCol = base * vec3(0.10, 0.13, 0.22);
    return mix(nightCol, dayCol, uDay);
}

// 雷のフラッシュ：全体が明るくなり、稲妻の方向は特に強く光る
vec3 applyFlash(vec3 col, vec3 dirW) {
    float fd = max(dot(dirW, uFlashDir), 0.0);
    float glow = clamp(uFlash * (0.30 + 0.70 * pow(fd, 4.0)), 0.0, 1.0);
    return mix(col, vec3(0.80, 0.87, 1.0), glow * 0.85);
}

// ============================ 雲 ============================

// t = 雲の高さの面までの距離。返り値は (色, 不透明度)
vec4 shadeCloud(vec3 dirW, float t, float dy, float h) {
    vec2 p = uOrigin + dirW.xz * (t * uScale);
    float f = fbm(p);
    float th = mix(0.72, -0.10, uCoverage);        // 雲量が多いほど閾値が下がる
    float tau = f - th;                            // 雲の厚み
    if (tau <= 0.0) return vec4(0.0);
    float dens = smoothstep(0.0, max(uSoftness, 0.01), tau);

    // 陰影：太陽側の方が厚ければ影、薄ければ日向
    float sunH = clamp(uSunDir.y, 0.0, 1.0);
    vec2 sd = uSunDir.xz;
    float sl = length(sd);
    sd = sl > 0.001 ? sd / sl : vec2(1.0, 0.0);
    float stepLen = 0.05 + 0.25 * (1.0 - sunH);
    float tauSun = 0.5 * (max(fbm(p + sd * stepLen) - th, 0.0)
                        + max(fbm(p + sd * stepLen * 2.5) - th, 0.0));
    float light = clamp(0.5 + (tau - tauSun) * uShade * 6.0, 0.0, 1.0);
    float thick = clamp(tau * uShade, 0.0, 1.0);
    float lit = light * (1.0 - 0.5 * thick);

    vec3 col = applyDayNight(mix(uDark, uLit, lit), sunH);

    // 太陽の近くの薄い縁は、透けて明るい
    float sunDot = max(dot(dirW, uSunDir), 0.0);
    col += uLit * pow(sunDot, 10.0) * (1.0 - dens) * 0.6 * uDay;

    col = applyFlash(col, dirW);

    // 水平線付近は霧の色へ溶かす
    col = mix(uFogColor, col, smoothstep(0.02, 0.30, abs(dy)));
    float alpha = dens * uOpacity
                * smoothstep(0.0, 0.10, abs(dy))     // 水平線でフェードアウト
                * smoothstep(0.5, 8.0, abs(h));      // 雲の高さに近いときは薄く
    return vec4(col, alpha);
}

// ============================ 空のベール ============================

// 曇天の空全体を覆う膜。雲の隙間・水平線付近・水平線より下から、
// バニラの明るい空（と太陽・月・星）が見えるのを隠す。
// 水平線では霧の色（= 地形が溶けていく色）、真上へ向かうほど雲の色に近づく。
vec4 shadeVeil(vec3 dirW) {
    float sunH = clamp(uSunDir.y, 0.0, 1.0);
    vec3 top = applyFlash(applyDayNight(mix(uDark, uLit, 0.35), sunH), dirW);
    float up = smoothstep(0.0, 1.0, clamp(dirW.y * 1.6, 0.0, 1.0));
    return vec4(mix(uFogColor, top, up), uVeil);
}

// ============================ main ============================

void main() {
    // このピクセルの視線（ビュー空間 → ワールド空間）
    vec3 dirV = normalize(vec3(vNdc.x / uProj[0][0], vNdc.y / uProj[1][1], -1.0));
    vec3 dirW = dirV * mat3(uModelView);
    float dy = dirW.y;
    float h  = uCloudRel - uEyeRel;

    // 空のピクセルは深度が 1.0。それ以外は、そこまでの距離を求める
    bool isSky = true;
    float sceneDist = 1.0e9;
    if (uHasDepth > 0.5) {
        float depth = texture2D(uDepthTex, gl_FragCoord.xy / uViewSize).r;
        if (depth < 0.99999) {
            isSky = false;
            sceneDist = linearDepth(depth) / dirV.z;
        }
    }

    // 雲：地形やエンティティが雲より手前にあれば描かない
    vec4 cloud = vec4(0.0);
    if (abs(dy) >= 0.01 && abs(h) >= 0.5) {
        float t = h / dy;
        if (t > 0.0 && t <= sceneDist) cloud = shadeCloud(dirW, t, dy, h);
    }

    // 空のベール：空のピクセルにだけ
    vec4 veil = vec4(0.0);
    if (isSky && uVeil > 0.001) veil = shadeVeil(dirW);

    // 雲をベールの上に重ねる
    float a = cloud.a + veil.a * (1.0 - cloud.a);
    if (a <= 0.002) discard;
    vec3 rgb = (cloud.rgb * cloud.a + veil.rgb * veil.a * (1.0 - cloud.a)) / a;

    gl_FragColor = vec4(rgb, a);
}
