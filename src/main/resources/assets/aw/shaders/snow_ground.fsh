#version 120

// ---- ノイズ空間の原点（mod 256 済み）と細かさ ----
uniform vec2  uFineOrigin;
uniform vec2  uBroadOrigin;
uniform float uFineScale;    // 細かい粒立ち
uniform float uBroadScale;   // 大きな吹きだまりの形

// ---- 状態 ----
uniform float uLevel;        // 積雪の量 0..1
uniform float uRadius;       // 描画半径（端をなじませるのに使う）

// ---- 色 ----
uniform vec3 uSnowColor;     // 一番積もったところの色
uniform vec3 uShadowColor;   // 薄いところの色（青みがかった影。ほぼ白にしたいなら uSnowColor に近づける）

varying vec3  vRel;
varying float vExp;

const float HASH_PERIOD = 256.0;
// 露出度をこのべき乗にしてから使う。値を大きくするほど、屋根に近づいたときの消え方が急になる
// （水たまりの「軒先がじんわり湿る」ぐらいの緩やかさは雪には合わないので、雪はここで絞る）
const float EXPOSURE_POWER = 2.2;

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

// ============================ main ============================

void main() {
    float dist = length(vRel.xz);
    float edge = 1.0 - smoothstep(uRadius * 0.65, uRadius, dist);
    if (edge <= 0.002) discard;

    // 屋根に近づいたときの消え方を急にする（雪は軒先のすぐ内側で終わってほしい）
    float exposure = pow(clamp(vExp, 0.0, 1.0), EXPOSURE_POWER);
    float amount = uLevel * exposure;
    if (amount <= 0.003) discard;

    // ---- 積もり方: 大きな吹きだまりの形 + 細かい粒立ち ----
    vec2 fp = vRel.xz * uFineScale + uFineOrigin;
    vec2 bp = vRel.xz * uBroadScale + uBroadOrigin;
    float fine = fbm(fp);
    float broad = fbm(bp);
    float n = mix(fine, broad, 0.55);

    // amount が上がるほど閾値が下がり、覆う範囲が広がる（最初はまだら、やがて一面に）
    float level = mix(-0.12, 0.62, amount);
    float coverage = 1.0 - smoothstep(level - 0.10, level + 0.10, n);
    coverage *= smoothstep(0.0, 0.05, amount);

    // ---- 色 ----
    // 薄いところは少し青みを残しつつ、厚いところは白に近づく
    float thickness = clamp(amount * (0.6 + 0.4 * broad), 0.0, 1.0);
    vec3 col = mix(uShadowColor, uSnowColor, clamp(thickness * 1.3, 0.0, 1.0));

    float alpha = coverage * edge;
    if (alpha <= 0.01) discard;

    gl_FragColor = vec4(col, alpha);
}
