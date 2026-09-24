package jp.apple.aw.weather.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.opengl.GL11;

import java.util.Random;

public class RenderRainy {
    /** 雨テクスチャ */
    public static ResourceLocation texture = new ResourceLocation("aw", "textures/weather/rainy.png");
    /** 雨粒の数/intensity */
    public static int dropsPerIntensity = 400;
    /** 天気の変化に合わせて、密度をどれくらいの速さで目標値へ近づけるか（1tickあたりの割合） */
    public static float intensitySmoothing = 0.03f;
    /** 雨粒数上限 */
    public static int maxDrops = 20000;
    /** 水平方向の描画範囲 */
    public static double rangeXZ = 60.0;
    /** 垂直方向の描画範囲 */
    public static double rangeY = 30.0;
    /** 落下速度の最小値・最大値 (blocks/tick) */
    public static float speedMin = 0.6f;
    public static float speedMax = 1.4f;
    /** 雨粒1本の長さ・幅 (blocks) */
    public static float dropLength = 0.4f;
    public static float dropWidth = 0.03f;
    /** 色（乗算）とアルファ */
    public static float red = 1.0f;
    public static float green = 1.0f;
    public static float blue = 1.0f;
    public static float alpha = 0.3f;
    /** 天気から決まる目標密度へ、毎tick少しずつ近づく現在の密度 */
    private static float intensity = 0f;
    private static float prevIntensity = 0f;

    private static final long SEED = 12345L;

    private static float[] nx = new float[0];
    private static float[] ny = new float[0];
    private static float[] nz = new float[0];
    private static float[] ns = new float[0];

    private static void ensureCapacity(int count) {
        if (nx.length >= count) return;
        Random r = new Random(SEED);
        nx = new float[count];
        ny = new float[count];
        nz = new float[count];
        ns = new float[count];
        for (int i = 0; i < count; i++) {
            nx[i] = r.nextFloat();
            ny[i] = r.nextFloat();
            nz[i] = r.nextFloat();
            ns[i] = r.nextFloat();
        }
    }
    public static float getIntensity() {
        return intensity;
    }

    public static void reset() {
        intensity = prevIntensity = 0f;
    }

    /** targetIntensity は今までの switch-case の値と同じスケール（LIGHT=4, NORMAL=8, HEAVY=15, STORMY=20）。降っていないなら 0。 */
    public static void onClientTick(float targetIntensity) {
        prevIntensity = intensity;
        intensity += (targetIntensity - intensity) * intensitySmoothing;
        if (targetIntensity <= 0f && intensity < 0.05f) intensity = 0f;
    }

    private static double wrap(double v, double range) {
        return v - Math.floor(v / range) * range;
    }

    // ================= 描画 =================
    public static void render(RenderWorldLastEvent event) {
        float pt = event.getPartialTicks();
        float lvl = prevIntensity + (intensity - prevIntensity) * pt;
        if (lvl <= 0.05f) return;

        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc.getRenderViewEntity();
        if (mc.world == null || view == null) return;

        int count = Math.min(Math.round(lvl * dropsPerIntensity), maxDrops);
        if (count <= 0) return;
        ensureCapacity(count);
        double camX = view.lastTickPosX + (view.posX - view.lastTickPosX) * pt;
        double camY = view.lastTickPosY + (view.posY - view.lastTickPosY) * pt;
        double camZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt;
        double time = mc.world.getTotalWorldTime() + pt;

        float yawRad = (float) Math.toRadians(mc.getRenderManager().playerViewY);
        float rx = MathHelper.cos(yawRad) * dropWidth;
        float rz = MathHelper.sin(yawRad) * dropWidth;

        double halfXZ = rangeXZ / 2;
        double halfY = rangeY / 2;
        float speedDelta = speedMax - speedMin;

        mc.entityRenderer.disableLightmap();
        GlStateManager.disableCull();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.enableTexture2D();
        GlStateManager.depthMask(false);

        mc.getTextureManager().bindTexture(texture);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);

        for (int i = 0; i < count; i++) {
            float speed = speedMin + ns[i] * speedDelta;

            double dx = wrap(nx[i] * rangeXZ - camX, rangeXZ) - halfXZ;
            double dz = wrap(nz[i] * rangeXZ - camZ, rangeXZ) - halfXZ;
            double dy = wrap(ny[i] * rangeY - time * speed - camY, rangeY) - halfY;

            double top = dy;
            double bottom = dy - dropLength;
            float v1 = 1f;
            
            int surfaceY = RenderWetGround.getRainTopY(
                    MathHelper.floor(camX + dx), MathHelper.floor(camZ + dz));
            if (surfaceY >= 0) {
                double surface = surfaceY - camY;
                if (top <= surface) continue;
                if (bottom < surface) {
                    bottom = surface;
                    v1 = (float) ((top - bottom) / dropLength);
                }
            }

            buf.pos(dx - rx, top, dz - rz).tex(0, 0).color(red, green, blue, alpha).endVertex();
            buf.pos(dx - rx, bottom, dz - rz).tex(0, v1).color(red, green, blue, alpha).endVertex();
            buf.pos(dx + rx, bottom, dz + rz).tex(1, v1).color(red, green, blue, alpha).endVertex();
            buf.pos(dx + rx, top, dz + rz).tex(1, 0).color(red, green, blue, alpha).endVertex();
        }

        tess.draw();

        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
    }
}
