package jp.apple.aw.weather.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;
import org.apache.commons.io.IOUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * GLSL 120 用の最小限のシェーダーラッパー。
 * 1.12.2 のレガシー GL コンテキスト（macOS は GL 2.1）を前提にしている。
 */
public class ShaderProgram {

    private int program = 0;
    private int vsh = 0;
    private int fsh = 0;
    private boolean broken = false;

    private final Map<String, Integer> uniforms = new HashMap<String, Integer>();

    public boolean isValid() {
        return program != 0 && !broken;
    }

    public boolean isBroken() {
        return broken;
    }

    /** コンパイル＆リンク。失敗したら false（以後 broken 扱いで再試行しない） */
    public boolean load(ResourceLocation vertex, ResourceLocation fragment) {
        if (broken) return false;
        if (program != 0) return true;
        if (!OpenGlHelper.shadersSupported) {
            broken = true;
            return false;
        }

        try {
            String vsrc = readResource(vertex);
            String fsrc = readResource(fragment);

            vsh = compile(GL20.GL_VERTEX_SHADER, vsrc, vertex.toString());
            fsh = compile(GL20.GL_FRAGMENT_SHADER, fsrc, fragment.toString());
            if (vsh == 0 || fsh == 0) {
                destroy();
                broken = true;
                return false;
            }

            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vsh);
            GL20.glAttachShader(program, fsh);
            GL20.glLinkProgram(program);

            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetProgramInfoLog(program, 4096);
                System.err.println("[AW] shader link failed:\n" + log);
                destroy();
                broken = true;
                return false;
            }
            return true;
        } catch (Exception e) {
            System.err.println("[AW] shader load failed: " + e);
            destroy();
            broken = true;
            return false;
        }
    }

    private static String readResource(ResourceLocation loc) throws Exception {
        IResource res = Minecraft.getMinecraft().getResourceManager().getResource(loc);
        InputStream in = null;
        try {
            in = res.getInputStream();
            return sanitize(IOUtils.toString(in, StandardCharsets.UTF_8));
        } finally {
            IOUtils.closeQuietly(in);
        }
    }

    private static int compile(int type, String source, String name) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, source);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(id, 4096);
            System.err.println("[AW] shader compile failed (" + name + "):\n" + log);
            GL20.glDeleteShader(id);
            return 0;
        }
        return id;
    }

    public void use() {
        GL20.glUseProgram(program);
    }

    public static void unuse() {
        GL20.glUseProgram(0);
    }

    private int loc(String name) {
        Integer cached = uniforms.get(name);
        if (cached != null) return cached.intValue();
        int l = GL20.glGetUniformLocation(program, name);
        uniforms.put(name, Integer.valueOf(l));
        return l;
    }

    public void set1i(String name, int v) {
        int l = loc(name);
        if (l >= 0) GL20.glUniform1i(l, v);
    }

    public void set1f(String name, float v) {
        int l = loc(name);
        if (l >= 0) GL20.glUniform1f(l, v);
    }

    public void set2f(String name, float a, float b) {
        int l = loc(name);
        if (l >= 0) GL20.glUniform2f(l, a, b);
    }

    public void set3f(String name, float a, float b, float c) {
        int l = loc(name);
        if (l >= 0) GL20.glUniform3f(l, a, b, c);
    }

    /** column-major の 16 要素バッファをそのまま渡す */
    public void setMat4(String name, FloatBuffer buf) {
        int l = loc(name);
        if (l >= 0) {
            buf.position(0);
            GL20.glUniformMatrix4(l, false, buf);
        }
    }

    public void destroy() {
        if (program != 0) {
            GL20.glDeleteProgram(program);
            program = 0;
        }
        if (vsh != 0) {
            GL20.glDeleteShader(vsh);
            vsh = 0;
        }
        if (fsh != 0) {
            GL20.glDeleteShader(fsh);
            fsh = 0;
        }
        uniforms.clear();
    }
    private static String sanitize(String src) {
        StringBuilder sb = new StringBuilder(src.length());
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || (c >= 0x20 && c < 0x7F)) sb.append(c);
            else sb.append(' ');
        }
        return sb.toString();
    }
    public void reset() {
        destroy();
        broken = false;
    }
}