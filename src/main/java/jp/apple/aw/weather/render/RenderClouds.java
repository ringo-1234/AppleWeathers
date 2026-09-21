package jp.apple.aw.weather.render;

import jp.apple.aw.weather.WeatherType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
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
import net.minecraftforge.client.IRenderHandler;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

/**
 * 雲（GLSL）。天気が何であっても同じ雲を描き、見た目は {@link CloudStyle} で決まる。
 *
 *  - 天気が変わると、今の見た目が新しい天気のプリセットへ少しずつ移り変わる
 *  - 雲は画面全体のシェーダーで描く（空のピクセルだけ）。地形や山より手前には出ない
 *  - バニラの雲は Forge の IRenderHandler で止める
 */
public final class RenderClouds {

    // ================= 設定 =================

    public static boolean enabled = true;
    /** true でバニラの雲を止める（false なら、バニラの雲と重なって描かれる） */
    public static boolean replaceVanillaClouds = true;
    /** ゲーム設定の「雲: オフ」のとき描かない */
    public static boolean respectCloudSetting = true;

    /** 雲の高さ（ワールドY） */
    public static float cloudHeight = 150f;
    /** 雲の細かさ（ノイズ単位/ブロック）。小さいほど大きな雲。1/180 ≒ 雲の塊が 180 ブロック程度 */
    public static float cloudScale = 1f / 180f;
    /** 雲の流れる向き（度）。0 = +X 方向、90 = +Z 方向 */
    public static float windDegree = 0f;
    /** 天気が変わったとき、見た目が移り変わる速さ（1tick あたりの割合。0.01 ≒ 15 秒でほぼ完了） */
    public static float transitionRate = 0.01f;

    /** 今描いている雲の見た目（天気のプリセットへ少しずつ近づく） */
    public static final CloudStyle current = new CloudStyle();

    // ================= 内部状態 =================

    private static final ResourceLocation VSH = new ResourceLocation("aw", "shaders/cloud.vsh");
    private static final ResourceLocation FSH = new ResourceLocation("aw", "shaders/cloud.fsh");

    private static final int UNIT_COLOR = 4;
    private static final int UNIT_DEPTH = 5;

    private static final ShaderProgram shader = new ShaderProgram();
    private static final FloatBuffer MAT = BufferUtils.createFloatBuffer(16);

    /** バニラの雲描画を差し替える「何もしない」ハンドラ */
    private static final IRenderHandler NO_VANILLA_CLOUDS = new IRenderHandler() {
        @Override
        public void render(float partialTicks, WorldClient world, Minecraft mc) {
        }
    };

    private static boolean initialized = false;
    private static boolean reloadHooked = false;
    private static double windX = 0.0;
    private static double windZ = 0.0;

    public static void reset() {
        initialized = false;
    }

    // ================= Tick =================

    public static void onClientTick(WeatherType weather) {
        Minecraft mc = Minecraft.getMinecraft();
        hookReload(mc);

        WorldClient world = mc.world;
        if (world == null) {
            reset();
            return;
        }
        updateVanillaHandler(world);
        if (mc.isGamePaused()) return;

        CloudStyle target = CloudStyle.forWeather(weather);
        if (!initialized) {
            current.copyFrom(target);   // ワールドに入った直後は、その天気の見た目から始める
            initialized = true;
        } else {
            current.lerpToward(target, transitionRate);
        }

        double rad = Math.toRadians(windDegree);
        windX += Math.cos(rad) * current.speed;
        windZ += Math.sin(rad) * current.speed;
    }

    /** ディメンションごとの WorldProvider に、バニラの雲を止めるハンドラを入れる */
    private static void updateVanillaHandler(WorldClient world) {
        IRenderHandler now = world.provider.getCloudRenderer();
        if (replaceVanillaClouds) {
            if (now != NO_VANILLA_CLOUDS) world.provider.setCloudRenderer(NO_VANILLA_CLOUDS);
        } else if (now == NO_VANILLA_CLOUDS) {
            world.provider.setCloudRenderer(null);
        }
    }

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
        WorldClient world = mc.world;
        Entity view = mc.getRenderViewEntity();
        if (world == null || view == null || !world.provider.isSurfaceWorld()) return;
        if (respectCloudSetting && mc.gameSettings.shouldRenderClouds() == 0) return;
        if (current.coverage <= 0.01f || current.opacity <= 0.01f) return;
        if (!OpenGlHelper.shadersSupported) return;
        if (!shader.load(VSH, FSH)) return;

        float pt = event.getPartialTicks();
        double camX = view.lastTickPosX + (view.posX - view.lastTickPosX) * pt;
        double camY = view.lastTickPosY + (view.posY - view.lastTickPosY) * pt;
        double camZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt;

        // 深度を読むために、描画済みの画面をコピーする
        ScreenCapture.update(mc);
        ScreenCapture.capture();

        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.disableCull();
        GlStateManager.disableLighting();
        GlStateManager.disableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);

        shader.use();
        ScreenCapture.bind(UNIT_COLOR, UNIT_DEPTH);
        setUniforms(world, view, pt, camX, camY, camZ);
        drawFullscreenQuad();

        ShaderProgram.unuse();
        ScreenCapture.unbind(UNIT_COLOR, UNIT_DEPTH);

        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
    }

    private static void setUniforms(WorldClient world, Entity view, float pt,
                                    double camX, double camY, double camZ) {
        shader.set1i("uDepthTex", UNIT_DEPTH);
        shader.set2f("uViewSize", ScreenCapture.getWidth(), ScreenCapture.getHeight());
        shader.set1f("uHasDepth", ScreenCapture.hasDepth() ? 1f : 0f);

        MAT.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, MAT);
        shader.setMat4("uProj", MAT);

        MAT.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MAT);
        shader.setMat4("uModelView", MAT);

        // 雲の位置。風で流れた分を引いて、ノイズ空間で mod 256 にして渡す（継ぎ目なくタイルする）
        double rad = Math.toRadians(windDegree);
        double wx = windX + Math.cos(rad) * current.speed * pt;
        double wz = windZ + Math.sin(rad) * current.speed * pt;
        shader.set1f("uEyeRel", view.getEyeHeight());
        shader.set1f("uCloudRel", (float) (cloudHeight - camY));
        shader.set1f("uScale", cloudScale);
        shader.set2f("uOrigin",
                (float) wrap256((camX - wx) * cloudScale), (float) wrap256((camZ - wz) * cloudScale));

        // 雲の見た目
        shader.set1f("uCoverage", current.coverage);
        shader.set1f("uOpacity", current.opacity);
        shader.set1f("uSoftness", current.softness);
        shader.set1f("uShade", current.shade);
        shader.set3f("uLit", current.lit[0], current.lit[1], current.lit[2]);
        shader.set3f("uDark", current.dark[0], current.dark[1], current.dark[2]);

        // 環境（太陽の向き。地平線より下なら月に切り替える）
        Vec3d fog = world.getFogColor(pt);
        shader.set3f("uFogColor", (float) fog.x, (float) fog.y, (float) fog.z);

        float a = world.getCelestialAngleRadians(pt);
        float sx = -MathHelper.sin(a);
        float sy = MathHelper.cos(a);
        float day = smoothstep(-0.12f, 0.22f, sy);
        if (sy < 0f) {
            sx = -sx;
            sy = -sy;
        }
        shader.set3f("uSunDir", sx, sy, 0f);
        shader.set1f("uDay", day);
        shader.set1f("uFlash", RenderLightning.getSkyFlash(pt));
        float[] fd = RenderLightning.getFlashDirection();
        shader.set3f("uFlashDir", fd[0], fd[1], fd[2]);
    }

    /** 頂点シェーダーが行列を使わないので、-1..1 の四角形で画面全体になる */
    private static void drawFullscreenQuad() {
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        buf.pos(-1.0, -1.0, 0.0).endVertex();
        buf.pos(1.0, -1.0, 0.0).endVertex();
        buf.pos(1.0, 1.0, 0.0).endVertex();
        buf.pos(-1.0, 1.0, 0.0).endVertex();
        tess.draw();
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = MathHelper.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static double wrap256(double v) {
        return v - Math.floor(v / 256.0) * 256.0;
    }
}
