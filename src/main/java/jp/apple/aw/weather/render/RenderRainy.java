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
    /** 雨粒数上限 */
    public static int maxDrops = 20000;
    /** 水平方向の描画範囲 */
    public static double rangeXZ = 24.0;
    /** 垂直方向の描画範囲 */
    public static double rangeY = 24.0;
    /** 落下速度の最小値・最大値 (blocks/tick) */
    public static float speedMin = 0.6f;
    public static float speedMax = 1.4f;
    /** 雨粒1本の長さ・幅 (blocks) */
    public static float dropLength = 0.8f;
    public static float dropWidth = 0.03f;
    /** 色（乗算）とアルファ */
    public static float red = 1.0f;
    public static float green = 1.0f;
    public static float blue = 1.0f;
    public static float alpha = 0.5f;

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

    private static double wrap(double v, double range) {
        return v - Math.floor(v / range) * range;
    }

    // ================= 描画 =================
    public static void render(RenderWorldLastEvent event, int intensity) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc.getRenderViewEntity();
        if (mc.world == null || view == null || intensity <= 0) return;

        int count = Math.min(intensity * dropsPerIntensity, maxDrops);
        if (count <= 0) return;
        ensureCapacity(count);

        float pt = event.getPartialTicks();
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

            buf.pos(dx - rx, dy, dz - rz).tex(0, 0).color(red, green, blue, alpha).endVertex();
            buf.pos(dx - rx, dy - dropLength, dz - rz).tex(0, 1).color(red, green, blue, alpha).endVertex();
            buf.pos(dx + rx, dy - dropLength, dz + rz).tex(1, 1).color(red, green, blue, alpha).endVertex();
            buf.pos(dx + rx, dy, dz + rz).tex(1, 0).color(red, green, blue, alpha).endVertex();
        }

        tess.draw();

        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
    }
}
