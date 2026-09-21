package jp.apple.aw.weather.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;

import java.nio.ByteBuffer;

/**
 * 描画済みのカラーと深度を、そのままテクスチャへコピーする。
 *
 * FBO を自前で作らず glCopyTexSubImage2D で吸い出すだけ
 */
public class ScreenCapture {

    private static int colorTex = 0;
    private static int depthTex = 0;
    private static int width = 0;
    private static int height = 0;
    private static boolean depthFailed = false;

    public static boolean hasDepth() {
        return depthTex != 0 && !depthFailed;
    }

    /** 画面サイズに合わせてテクスチャを用意する（サイズが変わったら作り直し） */
    public static void update(Minecraft mc) {
        int w, h;
        if (OpenGlHelper.isFramebufferEnabled() && mc.getFramebuffer() != null) {
            w = mc.getFramebuffer().framebufferWidth;
            h = mc.getFramebuffer().framebufferHeight;
        } else {
            w = mc.displayWidth;
            h = mc.displayHeight;
        }
        if (w <= 0 || h <= 0) return;
        if (w == width && h == height && colorTex != 0) return;

        destroy();
        width = w;
        height = h;

        colorTex = GL11.glGenTextures();
        GlStateManager.bindTexture(colorTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        depthTex = GL11.glGenTextures();
        GlStateManager.bindTexture(depthTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, w, h, 0,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        GlStateManager.bindTexture(0);
        depthFailed = false;
    }

    public static void capture() {
        if (colorTex == 0) return;

        GlStateManager.bindTexture(colorTex);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);

        if (!depthFailed && depthTex != 0) {
            GlStateManager.bindTexture(depthTex);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
            if (GL11.glGetError() != GL11.GL_NO_ERROR) {
                depthFailed = true;
                System.err.println("[AW] depth copy unsupported; SSR disabled");
            }
        }

        GlStateManager.bindTexture(0);
    }

    public static void bind(int colorUnit, int depthUnit) {
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + colorUnit);
        GlStateManager.bindTexture(colorTex);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + depthUnit);
        GlStateManager.bindTexture(depthTex);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
    }

    public static void unbind(int colorUnit, int depthUnit) {
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + colorUnit);
        GlStateManager.bindTexture(0);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + depthUnit);
        GlStateManager.bindTexture(0);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
    }

    public static void destroy() {
        if (colorTex != 0) {
            GL11.glDeleteTextures(colorTex);
            colorTex = 0;
        }
        if (depthTex != 0) {
            GL11.glDeleteTextures(depthTex);
            depthTex = 0;
        }
        width = height = 0;
    }

    public static int getWidth() { return width; }
    public static int getHeight() { return height; }
}