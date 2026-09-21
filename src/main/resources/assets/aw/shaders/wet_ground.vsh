#version 120

// カメラ相対のワールド座標（頂点はそのまま camera-relative で積んである）
varying vec3 vRel;
// ビュー空間座標（SSR のレイ開始点）
varying vec3 vView;
// 雨への露出度 0..1（頂点カラーの R に Java 側が入れている）
varying float vExp;

varying float vPuddle;

void main() {
    vRel  = gl_Vertex.xyz;
    vView = (gl_ModelViewMatrix * gl_Vertex).xyz;
    vExp  = gl_Color.r;
    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
    vPuddle = gl_Color.g;
}
