package jp.apple.aw.weather.render;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.opengl.GL11;

/**
 * 天気に応じて画面全体を暗くする（大気の明るさ）。
 *
 * バニラは World の rainStrength で太陽光の明るさを下げるが（World.getSunBrightness 参照）、
 * このMODはバニラの雨を止めているため rainStrength は常に 0 で、天候が荒れても世界は昼の明るさのまま。
 *
 * rainStrength を直接いじる方法はライトマップ（松明の明るさなど）にも影響してゲームプレイに関わるので、
 * ここでは描画済みの画面へ「乗算で暗くする」オーバーレイを重ねるだけにする。あくまで見た目の調整で、
 * 明るさそのもの（Mob の湧き・光源レベルなど）は変えない。
 *
 * RenderClouds の直後・RenderLightning や RenderWetGround より前に呼ぶこと。
 *  - 手前（地形・エンティティ・雲）は暗くなる
 *  - 後（雨粒・稲妻の閃光）は暗くならない。雨粒の見た目を変えたくない、閃光はむしろ明るくしたいため
 *  - 濡れた地面は画面をコピーして反射に使うので、先に暗くしておくと反射も自然に暗くなる
 */
public final class RenderAtmosphere {

    public static boolean enabled = true;

    private RenderAtmosphere() {
    }

    public static void render(RenderWorldLastEvent event) {
        if (!enabled) return;

        CloudStyle s = RenderClouds.current;
        float k = clamp01(s.ambientDark);
        if (k <= 0.002f) return;

        float r = 1f - k * (1f - s.ambientTint[0]);
        float g = 1f - k * (1f - s.ambientTint[1]);
        float b = 1f - k * (1f - s.ambientTint[2]);

        GlStateManager.disableTexture2D();
        GlStateManager.disableDepth();
        GlStateManager.disableCull();
        GlStateManager.disableLighting();
        GlStateManager.disableAlpha();
        GlStateManager.enableBlend();
        // 乗算合成: 出力 = 画面の色 × このクアッドの色。画面のコピーは不要。
        GlStateManager.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ZERO);
        GlStateManager.depthMask(false);

        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        buf.pos(-1.0, -1.0, 0.0).color(r, g, b, 1f).endVertex();
        buf.pos(1.0, -1.0, 0.0).color(r, g, b, 1f).endVertex();
        buf.pos(1.0, 1.0, 0.0).color(r, g, b, 1f).endVertex();
        buf.pos(-1.0, 1.0, 0.0).color(r, g, b, 1f).endVertex();
        tess.draw();

        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.popMatrix();

        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
