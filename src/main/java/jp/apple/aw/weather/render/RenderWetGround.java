package jp.apple.aw.weather.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

/**
 * 濡れた地面（GLSL）。
 *
 * ここは「濡れ度の管理」と「描画」だけを担当する。
 *  - 濡れを描く面と雨への露出度: {@link WetSurfaceGrid}（屋根の下の設定もそちら）
 *  - 面の形の取得: {@link SurfaceScanner}
 *  - 水たまり・波紋・反射: assets/aw/shaders/wet_ground.fsh
 *
 * 頂点カラー: R = 露出度, G = 水たまりの出やすさ（葉・絨毯・雪などは 0）
 *
 * 使い方:
 *  - ClientTickEvent(END) で onClientTick(intensity)
 *  - RenderWorldLastEvent で render(event)
 */
public class RenderWetGround {

    // ================= 設定（実行中に書き換えてOK） =================

    public static boolean enabled = true;

    // ---- 濡れの進み方 ----
    /** 濡れが増える速さ（1tick・intensity 1 あたり） */
    public static float wetRise = 1f / 1800f;
    /** 濡れが減る速さ（1tick あたり） */
    public static float dryRate = 1f / 3600f;
    /** intensity ごとの濡れ度の上限 [0]=止んでる [1]=小雨 [2]=雨 [3]=豪雨 */
    public static float[] wetCapByIntensity = {0f, 0.5f, 0.8f, 1.0f};

    // ---- 範囲 ----
    /** 描画半径（ブロック） */
    public static int radius = 100;

    // ---- 濡れ・水たまり ----
    /** 濡れの暗さ 0..1 */
    public static float darken = 0.30f;
    /** 水たまりのノイズの細かさ（大きいほど細かい）。0.16 ≒ 6ブロック周期 */
    public static float puddleScale = 0.16f;
    /** 水たまりの広さ。濡れ度 0 のときと 1 のときの閾値（大きいほど広い） */
    public static float puddleMin = 0.05f;
    public static float puddleMax = 0.40f;
    /** 濡れたときのガンマ暗化。色が濃く・彩度が残る暗がり方になる。0 で無効 */
    public static float darkenGamma = 0.15f;
    /** 雨の濃淡パッチの大きさ（0.015 ≒ 70ブロック周期） */
    public static float downfallScale = 0.015f;
    /** パッチが流れる速さ（ブロック/tick） */
    public static float downfallSpeed = 0.06f;

    // ---- 反射 ----
    /** 反射の強さ 0..1 */
    public static float reflectivity = 0.40f;
    /** 水たまり内の粗さ 0(鏡)..1 */
    public static float roughness = 0.30f;
    /** 反射のぼかし倍率 */
    public static float reflectionBlur = 1.0f;
    /** 太陽・月のハイライトの強さ */
    public static float specular = 0.6f;

    // ---- 波紋 ----
    /** 1ブロックあたりのセル数（2.2 ≒ 0.45ブロック角） */
    public static float rippleScale = 2.2f;
    /** 1サイクルの長さ (tick) */
    public static float ripplePeriod = 14f;
    /** 波紋で法線を曲げる強さ */
    public static float rippleStrength = 0.010f;
    /** 1サイクルで波紋を出すセルの割合（雨の強さでさらに増減） */
    public static float rippleDensity = 0.10f;

    // ================= 内部状態 =================

    private static final ResourceLocation VSH = new ResourceLocation("aw", "shaders/wet_ground.vsh");
    private static final ResourceLocation FSH = new ResourceLocation("aw", "shaders/wet_ground.fsh");

    private static final int UNIT_COLOR = 4;
    private static final int UNIT_DEPTH = 5;

    /** 水たまりの「広域のムラ」用ノイズの倍率（puddleScale に対する比）。シェーダーへは uniform で渡す */
    private static final float BROAD_SCALE = 0.28f;

    /** 地表グリッドを作り直す間隔（tick） */
    private static final int REBUILD_INTERVAL = 5;
    /** 面を地面より少し浮かせる量（Zファイト対策） */
    private static final double Y_OFFSET = 0.001;

    private static final ShaderProgram shader = new ShaderProgram();
    private static final FloatBuffer MAT = BufferUtils.createFloatBuffer(16);

    private static float wetness = 0f;
    private static float prevWetness = 0f;
    private static float rainLevel = 0f;
    private static float prevRainLevel = 0f;
    private static int rebuildTimer = 0;
    private static boolean reloadHooked = false;

    // ================= 公開 API =================

    public static float getWetness() {
        return wetness;
    }

    public static void setWetness(float value) {
        wetness = prevWetness = MathHelper.clamp(value, 0f, 1f);
    }

    public static void reset() {
        wetness = prevWetness = 0f;
        rainLevel = prevRainLevel = 0f;
        rebuildTimer = 0;
        WetSurfaceGrid.invalidate();
    }

    /** 雨が止まる高さ（雨が通る最初のブロックの Y）。グリッド外・未ロードは -1。RenderRainy などから使える。 */
    public static int getRainTopY(int x, int z) {
        return WetSurfaceGrid.getRainTopY(x, z);
    }

    // ================= Tick =================

    public static void onClientTick(int rainIntensity, boolean surfaceNeeded) {
        Minecraft mc = Minecraft.getMinecraft();
        hookReload(mc);

        if (mc.world == null || mc.player == null) {
            reset();
            return;
        }
        if (mc.isGamePaused()) return;

        prevWetness = wetness;
        int idx = MathHelper.clamp(rainIntensity, 0, wetCapByIntensity.length - 1);
        float cap = wetCapByIntensity[idx];
        if (wetness < cap) {
            wetness = Math.min(cap, wetness + wetRise * Math.max(1, rainIntensity));
        } else if (wetness > cap) {
            wetness = Math.max(cap, wetness - dryRate);
        }

        // 雨の強さは急に切り替わらないよう補間（波紋の出入りを滑らかに）
        prevRainLevel = rainLevel;
        float target = MathHelper.clamp(rainIntensity / 3f, 0f, 1f);
        rainLevel += (target - rainLevel) * 0.05f;

        // 濡れているあいだだけ地表グリッドを更新
        if (wetness > 0.002f || surfaceNeeded) {
            if (!WetSurfaceGrid.isValid() || ++rebuildTimer >= REBUILD_INTERVAL) {
                rebuildTimer = 0;
                Entity view = mc.getRenderViewEntity();
                if (view == null) view = mc.player;
                WetSurfaceGrid.rebuild(mc.world, view, radius);
            }
        } else {
            WetSurfaceGrid.invalidate();
        }
    }

    /** リソースの再読み込み（F3+T・リソースパック切り替え）で、モデルの面のキャッシュとシェーダーを捨てる */
    private static void hookReload(Minecraft mc) {
        if (reloadHooked) return;
        reloadHooked = true;
        ((IReloadableResourceManager) mc.getResourceManager()).registerReloadListener(
                new IResourceManagerReloadListener() {
                    @Override
                    public void onResourceManagerReload(IResourceManager resourceManager) {
                        SurfaceScanner.clearCache();
                        WetSurfaceGrid.invalidate();
                        shader.reset();
                    }
                });
    }

    // ================= 描画 =================

    public static void render(RenderWorldLastEvent event) {
        if (!enabled) return;

        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        Entity view = mc.getRenderViewEntity();
        if (world == null || view == null || !WetSurfaceGrid.isValid()) return;

        float pt = event.getPartialTicks();
        float wet = prevWetness + (wetness - prevWetness) * pt;
        if (wet <= 0.002f) return;

        if (!OpenGlHelper.shadersSupported) return;
        if (!shader.load(VSH, FSH)) return;

        double camX = view.lastTickPosX + (view.posX - view.lastTickPosX) * pt;
        double camY = view.lastTickPosY + (view.posY - view.lastTickPosY) * pt;
        double camZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt;

        // 画面のコピー（オーバーレイを描く前に）
        ScreenCapture.update(mc);
        ScreenCapture.capture();

        GlStateManager.disableCull();
        GlStateManager.disableLighting();
        GlStateManager.disableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        GlStateManager.depthMask(false);
        GlStateManager.enablePolygonOffset();
        GlStateManager.doPolygonOffset(-1.0f, -12.0f);

        shader.use();
        ScreenCapture.bind(UNIT_COLOR, UNIT_DEPTH);
        setUniforms(world, view, wet, pt, camX, camZ);

        drawSurfaces(camX, camY, camZ, camY + view.getEyeHeight());

        ShaderProgram.unuse();
        ScreenCapture.unbind(UNIT_COLOR, UNIT_DEPTH);

        GlStateManager.doPolygonOffset(0.0f, 0.0f);
        GlStateManager.disablePolygonOffset();
        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
    }

    private static void setUniforms(World world, Entity view, float wet, float pt,
                                    double camX, double camZ) {
        shader.set1i("uColorTex", UNIT_COLOR);
        shader.set1i("uDepthTex", UNIT_DEPTH);
        shader.set2f("uViewSize", ScreenCapture.getWidth(), ScreenCapture.getHeight());

        MAT.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, MAT);
        shader.setMat4("uProj", MAT);

        MAT.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MAT);
        shader.setMat4("uModelView", MAT);

        // ノイズ原点は「ノイズ空間で mod 256」。継ぎ目なくタイルする。
        shader.set1f("uPuddleScale", puddleScale);
        shader.set2f("uPuddleOrigin",
                (float) wrap256(camX * puddleScale), (float) wrap256(camZ * puddleScale));
        // 「広域のムラ」用。倍率が整数でないので、専用の原点を渡さないと 256 周期の折り返しで全体が跳ぶ
        shader.set1f("uBroadScale", BROAD_SCALE);
        shader.set2f("uBroadOrigin",
                (float) wrap256(camX * puddleScale * BROAD_SCALE),
                (float) wrap256(camZ * puddleScale * BROAD_SCALE));
        shader.set1f("uRippleScale", rippleScale);
        shader.set2f("uRippleOrigin",
                (float) wrap256(camX * rippleScale), (float) wrap256(camZ * rippleScale));

        double time = world.getTotalWorldTime() + pt;

        // 波紋の時間。周期単位で 256 ごとにループ（シェーダー側の hash も 256 周期）
        shader.set1f("uTime", (float) wrap256(time / Math.max(1.0, ripplePeriod)));

        // 雨の濃淡パッチ。風で流れる分を原点にずらして渡す
        shader.set1f("uDownfallScale", downfallScale);
        double scroll = time * downfallSpeed * downfallScale;
        shader.set2f("uDownfallOrigin",
                (float) wrap256(camX * downfallScale - scroll), (float) wrap256(camZ * downfallScale));

        float rain = prevRainLevel + (rainLevel - prevRainLevel) * pt;
        shader.set1f("uWetness", wet);
        shader.set1f("uRain", rain);

        Vec3d fog = RenderFog.getFogColor(world, pt);
        Vec3d sky = RenderFog.overcastSky(world.getSkyColor(view, pt), fog);
        shader.set1f("uFogStart", RenderFog.getFogStart());
        shader.set1f("uFogEnd", RenderFog.getFogEnd());
        shader.set3f("uSkyColor", (float) sky.x, (float) sky.y, (float) sky.z);
        shader.set3f("uFogColor", (float) fog.x, (float) fog.y, (float) fog.z);

        // 太陽の向き。地平線より下なら月（反対側）に切り替える。
        float a = world.getCelestialAngleRadians(pt);
        float sx = -MathHelper.sin(a);
        float sy = MathHelper.cos(a);
        float bright = world.getSunBrightness(pt);
        if (sy < 0f) {
            sx = -sx;
            sy = -sy;
            bright = 0.12f;
        }
        shader.set3f("uSunDir", sx, sy, 0f);
        shader.set1f("uSunBright", bright);

        shader.set1f("uRadius", radius);
        shader.set1f("uDarken", darken);
        shader.set1f("uDarkenGamma", darkenGamma);
        shader.set1f("uReflect", ScreenCapture.hasDepth() ? reflectivity : reflectivity * 0.6f);
        shader.set1f("uSpecular", specular);
        shader.set1f("uPuddleMin", puddleMin);
        shader.set1f("uPuddleMax", puddleMax);
        shader.set1f("uRoughness", roughness);
        shader.set1f("uBlur", reflectionBlur);
        shader.set1f("uRippleStrength", rippleStrength);
        shader.set1f("uRippleDensity", rippleDensity);
    }

    /** camY は足元の座標（描画は足元基準のカメラ相対）。eyeY は目の高さのワールド座標。 */
    private static void drawSurfaces(double camX, double camY, double camZ, double eyeY) {
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);

        // 描画中は再構築されないので、配列は最初に取り出しておく
        float[] geom = WetSurfaceGrid.qGeom;
        float[] puddles = WetSurfaceGrid.qPuddle;
        float[] vertexExposure = WetSurfaceGrid.qVExp;
        int originX = WetSurfaceGrid.originX;
        int originZ = WetSurfaceGrid.originZ;
        int quadCount = WetSurfaceGrid.quadCount;

        double r2 = (double) radius * radius;

        for (int q = 0; q < quadCount; q++) {
            int o = q * 5;

            // 目の高さより上にある面は裏側しか見えない（葉の隙間から空に重なって見える原因）ので描かない。
            // 足元の座標(camY)で比べると、地面に立っているとき足元の面が全部消えてしまう。
            if (geom[o + 4] > eyeY) continue;
            double yy = geom[o + 4] + Y_OFFSET - camY;

            double wx0 = originX + geom[o];
            double wz0 = originZ + geom[o + 1];
            double wx1 = originX + geom[o + 2];
            double wz1 = originZ + geom[o + 3];

            double cdx = (wx0 + wx1) * 0.5 - camX;
            double cdz = (wz0 + wz1) * 0.5 - camZ;
            if (cdx * cdx + cdz * cdz > r2) continue;

            int v = q * 4;
            float e00 = vertexExposure[v];
            float e01 = vertexExposure[v + 1];
            float e11 = vertexExposure[v + 2];
            float e10 = vertexExposure[v + 3];
            float p = puddles[q];

            buf.pos(wx0 - camX, yy, wz0 - camZ).color(e00, p, 0f, 1f).endVertex();
            buf.pos(wx0 - camX, yy, wz1 - camZ).color(e01, p, 0f, 1f).endVertex();
            buf.pos(wx1 - camX, yy, wz1 - camZ).color(e11, p, 0f, 1f).endVertex();
            buf.pos(wx1 - camX, yy, wz0 - camZ).color(e10, p, 0f, 1f).endVertex();
        }

        tess.draw();
    }

    private static double wrap256(double v) {
        return v - Math.floor(v / 256.0) * 256.0;
    }
}