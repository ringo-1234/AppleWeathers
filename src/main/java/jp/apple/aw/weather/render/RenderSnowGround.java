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
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.opengl.GL11;

/**
 * 積もった雪（GLSL）。
 *
 * {@link WetSurfaceGrid}（RenderWetGround が地表データとして作っている面の一覧）をそのまま再利用し、
 * 各面を「積雪の高さ」ぶんだけ持ち上げたうえで、GLSL でノイズベースの積もり方を描く。
 *  - 積雪の量（level）は、雪の天気のあいだ少しずつ増え、止むとゆっくり融ける（wetness と同じ考え方）
 *  - 面の高さは、その面の「雨（雪）への露出度」で決まる。屋根の下ほど積もらない（高さも低くなる）
 *  - 色の塗り方は水たまり（wet_ground）と同じ発想: ノイズの値をしきい値で切って「覆う/覆わない」を決め、
 *    積雪の量が増えるほどしきい値が下がって覆う範囲が広がる。最初はまだらに、やがて一面を覆う
 *  - 画面のコピーや反射は使わない（雪は水たまりと違って反射させたいものではないため、その分軽い）
 *
 * あくまで見た目だけの雪で、実際のブロック（雪ブロック）は置き換えない。
 * そのため当たり判定は変わらず、エンティティが沈んだりはしない。
 *
 * 使い方:
 *  - ClientTickEvent(END) で onClientTick(intensity)（雪の強さ。降っていないときは 0）
 *  - RenderWorldLastEvent で render(event)。RenderWetGround.render の直後で呼ぶ
 */
public final class RenderSnowGround {

    // ================= 設定 =================

    public static boolean enabled = true;

    // ---- 積もり方の進み方 ----
    /** 積雪が増える速さ（1tick・intensity 1 あたり） */
    public static float riseRate = 1f / 6000f;
    /** 積雪が融ける速さ（1tick あたり） */
    public static float meltRate = 1f / 3000f;
    /** intensity ごとの積雪の上限 [0]=降っていない [1]=小雪 [2]=雪 [3]=大雪 */
    public static float[] capByIntensity = {0f, 0.4f, 0.7f, 1.0f};

    // ---- 見た目 ----
    /**
     * これ未満の露出度の面は、最初から描画しない（負荷軽減のための大まかなふるい）。
     * 屋根の下で消えるかどうかの実際の締まり具合は、シェーダー側の EXPOSURE_POWER で決めている。
     */
    public static float minExposure = 0.15f;

    /** ノイズの細かさ（大きいほど細かい粒立ち）。1ブロックあたりの周期の目安は 1/値 */
    public static float fineScale = 0.6f;
    /** 大きな吹きだまりの形のノイズの細かさ */
    public static float broadScale = 0.05f;

    /** 一番積もったところの色 */
    public static float snowRed = 0.97f;
    public static float snowGreen = 0.99f;
    public static float snowBlue = 1.00f;
    /** 薄いところの色（青みがかった影）。ほぼ真っ白にしたいときは snow の値に近づける */
    public static float shadowRed = 0.75f;
    public static float shadowGreen = 0.80f;
    public static float shadowBlue = 0.90f;

    // ================= 内部状態 =================

    private static final ResourceLocation VSH = new ResourceLocation("aw", "shaders/snow_ground.vsh");
    private static final ResourceLocation FSH = new ResourceLocation("aw", "shaders/snow_ground.fsh");

    /** ブロック面とのZファイト対策。wet ground の Y_OFFSET より少しだけ上にする */
    private static final double Y_OFFSET = 0.002;

    private static final ShaderProgram shader = new ShaderProgram();

    private static float level = 0f;
    private static float prevLevel = 0f;
    private static boolean gridWasValid = false;
    private static boolean reloadHooked = false;

    private RenderSnowGround() {
    }

    // ================= 公開 API =================

    public static float getLevel() {
        return level;
    }

    public static void setLevel(float value) {
        level = prevLevel = MathHelper.clamp(value, 0f, 1f);
    }

    public static void reset() {
        level = prevLevel = 0f;
        gridWasValid = false;
    }

    // ================= Tick =================

    public static void onClientTick(int intensity) {
        Minecraft mc = Minecraft.getMinecraft();
        hookReload(mc);

        if (mc.world == null || mc.player == null) {
            reset();
            return;
        }
        if (mc.isGamePaused()) return;

        // 地表データが「なかった状態」から「ある状態」に戻った直後は、
        // そのとき既にたまっていた積雪量をいきなり見せてしまわないよう、0から積もり直す。
        // (しばらく雪・雨が止んでいた後にまた降り始めたときの「床が急に白くなる」ポップを防ぐ)
        boolean nowValid = WetSurfaceGrid.isValid();
        if (nowValid && !gridWasValid) {
            level = prevLevel = 0f;
        }
        gridWasValid = nowValid;

        prevLevel = level;
        int idx = MathHelper.clamp(intensity, 0, capByIntensity.length - 1);
        float cap = capByIntensity[idx];
        if (level < cap) {
            level = Math.min(cap, level + riseRate * Math.max(1, intensity));
        } else if (level > cap) {
            level = Math.max(cap, level - meltRate);
        }
    }

    /** リソースの再読み込み（F3+T・リソースパック切り替え）で、シェーダーを作り直せるようにする */
    private static void hookReload(Minecraft mc) {
        if (reloadHooked) return;
        reloadHooked = true;
        ((IReloadableResourceManager) mc.getResourceManager()).registerReloadListener(
                new IResourceManagerReloadListener() {
                    @Override
                    public void onResourceManagerReload(IResourceManager resourceManager) {
                        shader.reset();
                    }
                });
    }

    // ================= 描画 =================

    public static void render(RenderWorldLastEvent event) {
        if (!enabled) return;

        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc.getRenderViewEntity();
        if (mc.world == null || view == null || !WetSurfaceGrid.isValid()) return;

        float pt = event.getPartialTicks();
        float lvl = prevLevel + (level - prevLevel) * pt;
        if (lvl <= 0.002f) return;

        if (!OpenGlHelper.shadersSupported) return;
        if (!shader.load(VSH, FSH)) return;

        double camX = view.lastTickPosX + (view.posX - view.lastTickPosX) * pt;
        double camY = view.lastTickPosY + (view.posY - view.lastTickPosY) * pt;
        double camZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt;
        double eyeY = camY + view.getEyeHeight();

        GlStateManager.disableTexture2D();
        GlStateManager.disableCull();
        GlStateManager.disableLighting();
        GlStateManager.disableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        GlStateManager.enablePolygonOffset();
        GlStateManager.doPolygonOffset(-1.0f, -8.0f);

        shader.use();
        setUniforms(lvl, camX, camZ);
        drawSurfaces(camX, camY, camZ, eyeY, lvl, RenderWetGround.radius);
        ShaderProgram.unuse();

        GlStateManager.doPolygonOffset(0.0f, 0.0f);
        GlStateManager.disablePolygonOffset();
        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
    }

    private static void setUniforms(float lvl, double camX, double camZ) {
        shader.set1f("uLevel", lvl);
        shader.set1f("uRadius", RenderWetGround.radius);

        // ノイズ原点は「ノイズ空間で mod 256」。継ぎ目なくタイルする（wet_ground と同じやり方）。
        shader.set1f("uFineScale", fineScale);
        shader.set2f("uFineOrigin", (float) wrap256(camX * fineScale), (float) wrap256(camZ * fineScale));
        shader.set1f("uBroadScale", broadScale);
        shader.set2f("uBroadOrigin", (float) wrap256(camX * broadScale), (float) wrap256(camZ * broadScale));

        shader.set3f("uSnowColor", snowRed, snowGreen, snowBlue);
        shader.set3f("uShadowColor", shadowRed, shadowGreen, shadowBlue);
    }

    private static void drawSurfaces(double camX, double camY, double camZ, double eyeY,
                                     float lvl, int radius) {
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);

        // 描画中は再構築されないので、配列は最初に取り出しておく
        float[] geom = WetSurfaceGrid.qGeom;
        float[] vertexExposure = WetSurfaceGrid.qVExp;
        int originX = WetSurfaceGrid.originX;
        int originZ = WetSurfaceGrid.originZ;
        int quadCount = WetSurfaceGrid.quadCount;

        double r2 = (double) radius * radius;

        for (int q = 0; q < quadCount; q++) {
            int o = q * 5;
            float surfaceY = geom[o + 4];
            if (surfaceY > eyeY) continue;   // 目の高さより上の面は裏側しか見えない

            int v = q * 4;
            float e00 = vertexExposure[v];
            float e01 = vertexExposure[v + 1];
            float e11 = vertexExposure[v + 2];
            float e10 = vertexExposure[v + 3];
            if (Math.max(Math.max(e00, e01), Math.max(e11, e10)) < minExposure) continue;

            double wx0 = originX + geom[o];
            double wz0 = originZ + geom[o + 1];
            double wx1 = originX + geom[o + 2];
            double wz1 = originZ + geom[o + 3];

            double cdx = (wx0 + wx1) * 0.5 - camX;
            double cdz = (wz0 + wz1) * 0.5 - camZ;
            if (cdx * cdx + cdz * cdz > r2) continue;

            double baseY = surfaceY + Y_OFFSET - camY;

            // R チャンネルに露出度だけ渡す。色そのものはシェーダー側でノイズから決める。
            buf.pos(wx0 - camX, baseY, wz0 - camZ).color(e00, 0f, 0f, 1f).endVertex();
            buf.pos(wx0 - camX, baseY, wz1 - camZ).color(e01, 0f, 0f, 1f).endVertex();
            buf.pos(wx1 - camX, baseY, wz1 - camZ).color(e11, 0f, 0f, 1f).endVertex();
            buf.pos(wx1 - camX, baseY, wz0 - camZ).color(e10, 0f, 0f, 1f).endVertex();
        }

        tess.draw();
    }

    private static double wrap256(double v) {
        return v - Math.floor(v / 256.0) * 256.0;
    }
}