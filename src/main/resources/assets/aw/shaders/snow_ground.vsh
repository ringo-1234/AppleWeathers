#version 120

// カメラ相対のワールド座標（頂点はそのまま camera-relative で積んである。Y は積雪の高さぶん持ち上げ済み）
varying vec3 vRel;
// 雨（雪）への露出度 0..1（頂点カラーの R に Java 側が入れている）
varying float vExp;

void main() {
    vRel = gl_Vertex.xyz;
    vExp = gl_Color.r;
    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
}
