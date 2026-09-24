package jp.apple.aw.weather.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * 雪。{@link RenderRainy} と同じ「乱数配列 + ワールド座標を mod でループさせる」仕組みで降らせる。
 *
 * 雨粒との違い:
 *  - 画像ファイルを使わず、初回描画時に柔らかい丸のテクスチャを1枚だけ生成する（アセット不要）
 *  - 板は常にカメラの方を向く（ビルボード）。雨粒は視線のヨーだけに合わせた筋
 *  - 落下がゆっくりで、横にふわふわ揺れながら降る
 *  - 密度（intensity）は天気の値へ即座に切り替わらず、内部でなめらかに追従する。
 *    天気が雪でなくなった瞬間に粒が全部消えるのを避けるため
 *
 * 屋根で止まる高さは RenderWetGround（内部の WetSurfaceGrid）が持つ地表データを共有する。
 * 雪のときは地面を濡らさないので、RenderWetGround.onClientTick には濡れの強さ 0・
 * surfaceNeeded=true を渡すこと（地表データだけ作らせる）。
 *
 * 使い方:
 *  - ClientTickEvent(END) で onClientTick(targetIntensity)（降っていないときは 0）
 *  - RenderWorldLastEvent で render(event)。天気を見ずに毎tick呼んでよい（内部の密度が0なら何もしない）
 */
public final class RenderSnow {

    // ================= 設定（実行中に書き換えてOK） =================

    /** 雪片の数/intensity */
    public static int flakesPerIntensity = 250;
    public static int maxFlakes = 12000;

    /** 天気の変化に合わせて、密度をどれくらいの速さで目標値へ近づけるか（1tickあたりの割合） */
    public static float intensitySmoothing = 0.03f;

    /** 水平方向・垂直方向の描画範囲 */
    public static double rangeXZ = 60.0;
    public static double rangeY = 30.0;

    /** 落下速度の最小・最大 (blocks/tick) */
    public static float speedMin = 0.1f;
    public static float speedMax = 0.15f;

    /** 雪片の大きさ (blocks)。個体差は最小・最大のあいだでランダム */
    public static float sizeMin = 0.05f;
    public static float sizeMax = 0.11f;

    /** 横揺れの振れ幅 (blocks) */
    public static float swayAmpMin = 0.05f;
    public static float swayAmpMax = 0.22f;
    /** 横揺れの速さ (rad/tick) */
    public static float swayFreqMin = 0.015f;
    public static float swayFreqMax = 0.04f;

    /** 色とアルファ */
    public static float red = 1.0f;
    public static float green = 1.0f;
    public static float blue = 1.0f;
    public static float alpha = 0.9f;

    // ================= 内部状態 =================

    private static final long SEED = 20240001L;
    /** 生成するテクスチャの一辺 (px) */
    private static final int TEX_SIZE = 32;

    private static int textureId = 0;

    /** 天気から決まる目標密度へ、毎tick少しずつ近づく現在の密度 */
    private static float intensity = 0f;
    private static float prevIntensity = 0f;

    private static float[] nx = new float[0];
    private static float[] ny = new float[0];
    private static float[] nz = new float[0];
    private static float[] nSpeed = new float[0];
    private static float[] nSize = new float[0];
    private static float[] nSwayAmpX = new float[0];
    private static float[] nSwayAmpZ = new float[0];
    private static float[] nSwayFreq = new float[0];
    private static float[] nSwayPhaseX = new float[0];
    private static float[] nSwayPhaseZ = new float[0];

    private RenderSnow() {
    }

    public static float getIntensity() {
        return intensity;
    }

    public static void reset() {
        intensity = prevIntensity = 0f;
    }

    // ================= Tick =================

    /** targetIntensity は RenderRainy と同じスケール（LIGHT=4, NORMAL=8, HEAVY=15 目安）。降っていないなら 0。 */
    public static void onClientTick(float targetIntensity) {
        prevIntensity = intensity;
        intensity += (targetIntensity - intensity) * intensitySmoothing;
        if (targetIntensity <= 0f && intensity < 0.05f) intensity = 0f;   // 完全に0へ収束させる
    }

    private static void ensureCapacity(int count) {
        if (nx.length >= count) return;
        Random r = new Random(SEED);
        nx = new float[count];
        ny = new float[count];
        nz = new float[count];
        nSpeed = new float[count];
        nSize = new float[count];
        nSwayAmpX = new float[count];
        nSwayAmpZ = new float[count];
        nSwayFreq = new float[count];
        nSwayPhaseX = new float[count];
        nSwayPhaseZ = new float[count];
        for (int i = 0; i < count; i++) {
            nx[i] = r.nextFloat();
            ny[i] = r.nextFloat();
            nz[i] = r.nextFloat();
            nSpeed[i] = r.nextFloat();
            nSize[i] = r.nextFloat();
            nSwayAmpX[i] = r.nextFloat();
            nSwayAmpZ[i] = r.nextFloat();
            nSwayFreq[i] = r.nextFloat();
            nSwayPhaseX[i] = r.nextFloat() * (float) (Math.PI * 2.0);
            nSwayPhaseZ[i] = r.nextFloat() * (float) (Math.PI * 2.0);
        }
    }

    private static double wrap(double v, double range) {
        return v - Math.floor(v / range) * range;
    }

    /** 初回描画時に、柔らかい丸のテクスチャ（中心が不透明・縁がなめらかに透ける）を1枚だけ作る */
    private static void ensureTexture() {
        if (textureId != 0) return;

        ByteBuffer buf = ByteBuffer.allocateDirect(TEX_SIZE * TEX_SIZE * 4).order(ByteOrder.nativeOrder());
        float center = (TEX_SIZE - 1) / 2f;
        float outerR = TEX_SIZE / 2f;
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                float dx = (x - center) / outerR;
                float dy = (y - center) / outerR;
                float d = MathHelper.sqrt(dx * dx + dy * dy);
                float a = 1f - MathHelper.clamp((d - 0.35f) / 0.65f, 0f, 1f);
                a = a * a * (3f - 2f * a);   // smoothstep
                byte v = (byte) 255;
                buf.put(v).put(v).put(v).put((byte) (int) (a * 255f));
            }
        }
        buf.flip();

        textureId = GL11.glGenTextures();
        GlStateManager.bindTexture(textureId);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, TEX_SIZE, TEX_SIZE, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager.bindTexture(0);
    }

    /**
     * GLコンテキストが失われた場合などに、テクスチャを作り直せるようにしておく。
     * 通常のリソースパック切り替え（F3+T）では OpenGL コンテキストは失われないので、今は呼んでいない。
     */
    public static void resetTexture() {
        if (textureId != 0) {
            GL11.glDeleteTextures(textureId);
            textureId = 0;
        }
    }

    // ================= 描画 =================

    /** 天気を見ずに毎tick呼んでよい。密度がほぼ0のときは何もしない。 */
    public static void render(RenderWorldLastEvent event) {
        float pt = event.getPartialTicks();
        float lvl = prevIntensity + (intensity - prevIntensity) * pt;
        if (lvl <= 0.05f) return;

        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc.getRenderViewEntity();
        if (mc.world == null || view == null) return;

        int count = Math.min(Math.round(lvl * flakesPerIntensity), maxFlakes);
        if (count <= 0) return;
        ensureCapacity(count);
        ensureTexture();

        double camX = view.lastTickPosX + (view.posX - view.lastTickPosX) * pt;
        double camY = view.lastTickPosY + (view.posY - view.lastTickPosY) * pt;
        double camZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt;
        double time = mc.world.getTotalWorldTime() + pt;

        // ビルボードの向き（板が常にカメラの方を向くように、ヨーとピッチの両方に合わせる）
        float yawRad = (float) Math.toRadians(mc.getRenderManager().playerViewY);
        float pitchRad = (float) Math.toRadians(mc.getRenderManager().playerViewX);
        Vec3d right = new Vec3d(1.0, 0.0, 0.0).rotatePitch(-pitchRad).rotateYaw(-yawRad);
        Vec3d up = new Vec3d(0.0, 1.0, 0.0).rotatePitch(-pitchRad).rotateYaw(-yawRad);

        double halfXZ = rangeXZ / 2;
        double halfY = rangeY / 2;
        float speedDelta = speedMax - speedMin;
        float sizeDelta = sizeMax - sizeMin;
        float swayAmpDelta = swayAmpMax - swayAmpMin;
        float swayFreqDelta = swayFreqMax - swayFreqMin;

        mc.entityRenderer.disableLightmap();
        GlStateManager.disableCull();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.enableTexture2D();
        GlStateManager.depthMask(false);
        GlStateManager.bindTexture(textureId);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);

        for (int i = 0; i < count; i++) {
            float speed = speedMin + nSpeed[i] * speedDelta;
            float size = sizeMin + nSize[i] * sizeDelta;
            float swayFreq = swayFreqMin + nSwayFreq[i] * swayFreqDelta;
            float swayX = (swayAmpMin + nSwayAmpX[i] * swayAmpDelta)
                    * MathHelper.sin((float) time * swayFreq + nSwayPhaseX[i]);
            float swayZ = (swayAmpMin + nSwayAmpZ[i] * swayAmpDelta)
                    * MathHelper.sin((float) time * swayFreq * 0.8f + nSwayPhaseZ[i]);

            double dx = wrap(nx[i] * rangeXZ - camX, rangeXZ) - halfXZ + swayX;
            double dz = wrap(nz[i] * rangeXZ - camZ, rangeXZ) - halfXZ + swayZ;
            double dy = wrap(ny[i] * rangeY - time * speed - camY, rangeY) - halfY;

            // 屋根の下は降らせない（RenderWetGround と同じ地表データを使う）
            int surfaceY = RenderWetGround.getRainTopY(
                    MathHelper.floor(camX + dx), MathHelper.floor(camZ + dz));
            if (surfaceY >= 0 && dy <= surfaceY - camY) continue;

            double rx = right.x * size;
            double ry = right.y * size;
            double rz = right.z * size;
            double ux = up.x * size;
            double uy = up.y * size;
            double uz = up.z * size;

            buf.pos(dx - rx - ux, dy - ry - uy, dz - rz - uz).tex(0, 1).color(red, green, blue, alpha).endVertex();
            buf.pos(dx - rx + ux, dy - ry + uy, dz - rz + uz).tex(0, 0).color(red, green, blue, alpha).endVertex();
            buf.pos(dx + rx + ux, dy + ry + uy, dz + rz + uz).tex(1, 0).color(red, green, blue, alpha).endVertex();
            buf.pos(dx + rx - ux, dy + ry - uy, dz + rz - uz).tex(1, 1).color(red, green, blue, alpha).endVertex();
        }

        tess.draw();

        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
    }
}