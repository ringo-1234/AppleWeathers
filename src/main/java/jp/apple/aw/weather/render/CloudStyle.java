package jp.apple.aw.weather.render;

import jp.apple.aw.weather.WeatherType;

import java.util.EnumMap;
import java.util.Map;

/**
 * 空の見た目 1 セット（雲・空のベール・霧）。天気ごとにプリセットを持ち、
 * RenderClouds が今の天気のプリセットへ少しずつ近づけながら描く。RenderFog も同じ値を読む。
 *
 * 色を変えたいときは、プリセットを取り出して書き換える:
 *   CloudStyle.forWeather(WeatherType.STORMY).setDarkRgb(0x101018);
 *   CloudStyle.forWeather(WeatherType.SNOWY).setLitRgb(0xF4F8FF);
 *
 * 霧やベールを変えたいとき:
 *   CloudStyle.forWeather(WeatherType.RAINY).setAtmosphere(0.8f, 0.35f, 0.75f, 0.7f, 0.32f);
 */
public final class CloudStyle {

    // ---- 雲 ----

    /** 雲量 0(快晴)..1(全天曇り) */
    public float coverage;
    /** 雲の濃さ（最大の不透明度）0..1 */
    public float opacity;
    /** 縁のやわらかさ（小さいほどくっきり、大きいほどふわっと） */
    public float softness;
    /** 陰影の強さ（厚い所の暗さ・太陽側との明暗差） */
    public float shade;
    /** 流れる速さ (blocks/tick) */
    public float speed;
    /** 日向の色 RGB 0..1（雲の明るい所） */
    public final float[] lit = new float[3];
    /** 日陰の色 RGB 0..1（雲の暗い所・厚い所） */
    public final float[] dark = new float[3];

    // ---- 空のベールと霧 ----

    /**
     * 空のベール 0..1。曇天の空全体を覆う灰色の膜。
     * 雲の隙間や水平線付近から、バニラの明るい青空・太陽・星が見えるのを隠す。
     */
    public float veil;
    /** 画面全体を乗算で暗くする量 0..1（0 = 変えない） */
    public float ambientDark;
    /** 暗くするときの色味 RGB 0..1（例: 嵐なら少し青みのある暗さ） */
    public final float[] ambientTint = new float[3];
    /** 霧の始まりの距離（描画距離に対する割合）。バニラは 0.75 */
    public float fogStart;
    /** 霧が完全にかかる距離（描画距離に対する割合）。バニラは 1.0 */
    public float fogEnd;
    /** 霧の色を灰色に寄せる割合 0..1 */
    public float fogGray;
    /** 霧の色を暗くする量 0..1（0 = そのまま） */
    public float fogDim;

    public CloudStyle copyFrom(CloudStyle o) {
        coverage = o.coverage;
        opacity = o.opacity;
        softness = o.softness;
        shade = o.shade;
        speed = o.speed;
        System.arraycopy(o.lit, 0, lit, 0, 3);
        System.arraycopy(o.dark, 0, dark, 0, 3);
        veil = o.veil;
        ambientDark = o.ambientDark;
        System.arraycopy(o.ambientTint, 0, ambientTint, 0, 3);
        fogStart = o.fogStart;
        fogEnd = o.fogEnd;
        fogGray = o.fogGray;
        fogDim = o.fogDim;
        return this;
    }

    /** target に k (0..1) の割合だけ近づける */
    public void lerpToward(CloudStyle target, float k) {
        coverage += (target.coverage - coverage) * k;
        opacity += (target.opacity - opacity) * k;
        softness += (target.softness - softness) * k;
        shade += (target.shade - shade) * k;
        speed += (target.speed - speed) * k;
        for (int i = 0; i < 3; i++) {
            lit[i] += (target.lit[i] - lit[i]) * k;
            dark[i] += (target.dark[i] - dark[i]) * k;
        }
        veil += (target.veil - veil) * k;
        ambientDark += (target.ambientDark - ambientDark) * k;
        for (int i = 0; i < 3; i++) {
            ambientTint[i] += (target.ambientTint[i] - ambientTint[i]) * k;
        }
        fogStart += (target.fogStart - fogStart) * k;
        fogEnd += (target.fogEnd - fogEnd) * k;
        fogGray += (target.fogGray - fogGray) * k;
        fogDim += (target.fogDim - fogDim) * k;
    }

    public CloudStyle setLit(float r, float g, float b) {
        lit[0] = r;
        lit[1] = g;
        lit[2] = b;
        return this;
    }

    public CloudStyle setDark(float r, float g, float b) {
        dark[0] = r;
        dark[1] = g;
        dark[2] = b;
        return this;
    }

    /** 0xRRGGBB で指定 */
    public CloudStyle setLitRgb(int rgb) {
        return setLit(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f);
    }

    public CloudStyle setDarkRgb(int rgb) {
        return setDark(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f);
    }

    public CloudStyle setAtmosphere(float veil, float fogStart, float fogEnd, float fogGray, float fogDim) {
        this.veil = veil;
        this.fogStart = fogStart;
        this.fogEnd = fogEnd;
        this.fogGray = fogGray;
        this.fogDim = fogDim;
        return this;
    }

    public CloudStyle setAmbient(float dark, int tintRgb) {
        ambientDark = dark;
        ambientTint[0] = ((tintRgb >> 16) & 0xFF) / 255f;
        ambientTint[1] = ((tintRgb >> 8) & 0xFF) / 255f;
        ambientTint[2] = (tintRgb & 0xFF) / 255f;
        return this;
    }

    // ================= 天気ごとのプリセット =================

    private static final Map<WeatherType, CloudStyle> PRESETS =
            new EnumMap<WeatherType, CloudStyle>(WeatherType.class);

    private static CloudStyle preset(WeatherType weather, float coverage, float opacity, float softness,
                                     float shade, float speed, int litRgb, int darkRgb) {
        CloudStyle s = new CloudStyle();
        s.coverage = coverage;
        s.opacity = opacity;
        s.softness = softness;
        s.shade = shade;
        s.speed = speed;
        s.setLitRgb(litRgb).setDarkRgb(darkRgb);
        PRESETS.put(weather, s);
        return s;
    }

    static {
        // 雲:      天気              雲量  濃さ  やわらかさ 陰影  速さ   日向色    日陰色
        // 大気:                                        ベール 霧始  霧終  灰色化 暗さ
        preset(WeatherType.SUNNY,        0.22f, 0.92f, 0.14f, 0.90f, 0.03f, 0xFFFFFF, 0xA8B8D6)
                .setAtmosphere(0.00f, 0.75f, 1.00f, 0.00f, 0.00f)
                .setAmbient(0.00f, 0xFFFFFF);
        preset(WeatherType.CLOUDY,       0.78f, 0.96f, 0.18f, 1.00f, 0.04f, 0xE6E8F0, 0x808A9E)
                .setAtmosphere(0.20f, 0.65f, 1.00f, 0.25f, 0.10f)
                .setAmbient(0.10f, 0xC9D2E0);
        preset(WeatherType.LIGHT_RAINY,  0.88f, 0.97f, 0.20f, 1.10f, 0.05f, 0xB8BCC8, 0x666E80)
                .setAtmosphere(0.55f, 0.50f, 0.90f, 0.50f, 0.20f)
                .setAmbient(0.20f, 0xB9C4D6);
        preset(WeatherType.RAINY,        0.96f, 0.98f, 0.22f, 1.20f, 0.06f, 0x94989F, 0x4C5566)
                .setAtmosphere(0.80f, 0.35f, 0.75f, 0.70f, 0.32f)
                .setAmbient(0.32f, 0xA3AEC4);
        preset(WeatherType.HEAVY_RAINY,  1.00f, 0.99f, 0.25f, 1.30f, 0.08f, 0x70757F, 0x333846)
                .setAtmosphere(0.92f, 0.20f, 0.55f, 0.85f, 0.45f)
                .setAmbient(0.42f, 0x8F9AB4);
        preset(WeatherType.STORMY,       1.00f, 1.00f, 0.25f, 1.50f, 0.14f, 0x4D4F5E, 0x171A24)
                .setAtmosphere(1.00f, 0.10f, 0.45f, 0.90f, 0.60f)
                .setAmbient(0.55f, 0x707C9C);
        preset(WeatherType.LIGHT_SNOWY,  0.88f, 0.97f, 0.22f, 0.90f, 0.04f, 0xEBF0FA, 0xA3ADC4)
                .setAtmosphere(0.45f, 0.45f, 0.85f, 0.35f, 0.05f)
                .setAmbient(0.12f, 0xD8DEEC);
        preset(WeatherType.SNOWY,        0.96f, 0.98f, 0.24f, 1.00f, 0.05f, 0xE0E6F2, 0x8F9CB5)
                .setAtmosphere(0.70f, 0.30f, 0.70f, 0.45f, 0.08f)
                .setAmbient(0.22f, 0xC7D0E4);
        preset(WeatherType.HEAVY_SNOWY,  1.00f, 0.99f, 0.26f, 1.05f, 0.07f, 0xD4DBEB, 0x808DA8)
                .setAtmosphere(0.85f, 0.12f, 0.45f, 0.55f, 0.10f)
                .setAmbient(0.30f, 0xB8C3DC);
    }

    /** 天気のプリセット（書き換えれば、その天気の見た目が変わる） */
    public static CloudStyle forWeather(WeatherType weather) {
        CloudStyle s = weather == null ? null : PRESETS.get(weather);
        return s != null ? s : PRESETS.get(WeatherType.CLOUDY);
    }
}
