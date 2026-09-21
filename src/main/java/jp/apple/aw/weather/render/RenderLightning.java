package jp.apple.aw.weather.render;

import jp.apple.aw.weather.WeatherType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.init.SoundEvents;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 雷。嵐（STORMY）のあいだだけ、ランダムな間隔で「空に見える稲妻」を出す。
 *
 *  - 稲妻は実際には落ちない。クライアントだけの演出。
 *  - 稲妻の筋: 雲の高さから地面へ、ギザギザの光の帯を数tickだけ描く。地形の陰には隠れる。
 *  - 空のフラッシュ: 雲が光る（RenderClouds へ強さと向きを渡す）＋画面全体がわずかに明るくなる。
 *  - 雷鳴: バニラの雷の音を、ランダムな遅れで鳴らす。
 */
public final class RenderLightning {

    // ================= 設定 =================

    public static boolean enabled = true;
    /** 稲妻の筋を描く（false なら、フラッシュと雷鳴だけ） */
    public static boolean drawBolt = true;
    /** 雲のフラッシュ */
    public static boolean cloudFlash = true;
    /** 画面全体のフラッシュの強さ 0..1（0 で無効） */
    public static float screenFlash = 0.08f;
    /** 雷鳴を鳴らす */
    public static boolean playSound = true;

    // ---- 発生間隔 (tick) ----
    public static int intervalMin = 100;
    public static int intervalMax = 360;
    /** 続けて落ちる確率と、そのときの間隔 (tick) */
    public static float burstChance = 0.3f;
    public static int burstIntervalMin = 8;
    public static int burstIntervalMax = 40;

    // ---- 位置 ----
    /** 視線の前方（±100°）に出す確率。残りは全方向 */
    public static float lookBias = 0.7f;
    /** 稲妻までの距離（描画距離に対する割合）。描画距離より遠いと遠方クリップで見えない */
    public static float minDistanceRatio = 0.35f;
    public static float maxDistanceRatio = 0.95f;

    // ---- 見た目 ----
    /** 稲妻の太さの下限 (blocks) */
    public static float minWidth = 0.12f;
    /** 稲妻の見かけの太さ（視線に対する角度 rad）。遠くても数ピクセルは見えるようにする */
    public static float angularWidth = 0.0035f;
    /** 光のにじみの幅の倍率と濃さ */
    public static float glowScale = 5.0f;
    public static float glowAlpha = 0.22f;
    /** ギザギザの細かさ（分割の深さ。1つ増えるごとに線分が2倍） */
    public static int boltDetail = 6;
    /** ギザギザの大きさ（線分の長さに対する割合） */
    public static float boltJaggedness = 0.22f;
    /** 枝分かれする確率（分割の途中ごと） */
    public static float boltBranchChance = 0.35f;

    // ---- 雷鳴 ----
    /** 稲妻から雷鳴までの遅れ (tick) */
    public static int thunderDelayMin = 10;
    public static int thunderDelayMax = 80;
    /** 稲妻までの距離に応じて足す遅れ (tick/ブロック)。0 なら距離は無関係（完全にランダム） */
    public static float thunderDelayPerBlock = 0.0f;
    public static float thunderVolume = 2.0f;

    // ================= 内部状態 =================

    private static final Random RND = new Random();
    private static final List<Bolt> bolts = new ArrayList<Bolt>();
    private static final List<PendingThunder> pending = new ArrayList<PendingThunder>();
    private static final float[] flashDir = {0f, 1f, 0f};

    /** 次の稲妻までの tick。-1 = 嵐ではない（次に嵐になったとき決め直す） */
    private static int countdown = -1;

    private static final class Bolt {
        int age = 0;
        int life = 1;
        /** 地面側の点（ワールド座標） */
        double ox, oy, oz;
        /** 視点から雷雲側の点へ向かう向き（雲のフラッシュ用） */
        final float[] dir = new float[3];
        /** 線分 [x0, y0, z0, x1, y1, z1, 強さ]（地面側の点からの相対） */
        float[] seg = new float[7 * 128];
        int segCount = 0;
        /** 閃光 [開始 tick, 長さ tick, 強さ] */
        float[] strokeStart, strokeLen, strokeAmp;

        void addSeg(float x0, float y0, float z0, float x1, float y1, float z1, float strength) {
            if ((segCount + 1) * 7 > seg.length) seg = Arrays.copyOf(seg, seg.length * 2);
            int o = segCount * 7;
            seg[o] = x0;
            seg[o + 1] = y0;
            seg[o + 2] = z0;
            seg[o + 3] = x1;
            seg[o + 4] = y1;
            seg[o + 5] = z1;
            seg[o + 6] = strength;
            segCount++;
        }

        /** t (tick) の時点の光の強さ 0..1。閃光は一瞬で最大になり、すぐ弱まる */
        float flash(float t) {
            float f = 0f;
            for (int i = 0; i < strokeStart.length; i++) {
                float local = t - strokeStart[i];
                if (local >= 0f && local < strokeLen[i]) {
                    float k = 1f - local / strokeLen[i];
                    f = Math.max(f, strokeAmp[i] * k * k);
                }
            }
            return f;
        }
    }

    private static final class PendingThunder {
        int ticks;
        final float dx, dz;
        final float pitch;

        PendingThunder(int ticks, float dx, float dz, float pitch) {
            this.ticks = ticks;
            this.dx = dx;
            this.dz = dz;
            this.pitch = pitch;
        }
    }

    public static void reset() {
        bolts.clear();
        pending.clear();
        countdown = -1;
    }

    // ================= 公開 API =================

    /** 雲のフラッシュの強さ 0..1（RenderClouds が読む） */
    public static float getSkyFlash(float pt) {
        if (!enabled || !cloudFlash) return 0f;
        return maxFlash(pt, true);
    }

    /** 一番強く光っている稲妻の向き。getSkyFlash の直後に読む */
    public static float[] getFlashDirection() {
        return flashDir;
    }

    /** 今すぐ1本落とす */
    public static void strikeNow() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world != null && mc.player != null) strike(mc, mc.world);
    }

    // ================= Tick =================

    public static void onClientTick(WeatherType weather) {
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc.world;
        if (world == null || mc.player == null) {
            reset();
            return;
        }
        if (mc.isGamePaused()) return;

        for (int i = bolts.size() - 1; i >= 0; i--) {
            if (++bolts.get(i).age >= bolts.get(i).life) bolts.remove(i);
        }

        for (int i = pending.size() - 1; i >= 0; i--) {
            PendingThunder t = pending.get(i);
            if (--t.ticks <= 0) {
                playThunder(mc, world, t);
                pending.remove(i);
            }
        }

        boolean storm = enabled && weather == WeatherType.STORMY && world.provider.isSurfaceWorld();
        if (!storm) {
            countdown = -1;
            return;
        }
        if (countdown < 0) countdown = 20 + RND.nextInt(Math.max(1, intervalMin));   // 嵐になって最初の1本
        if (--countdown > 0) return;

        strike(mc, world);
        if (RND.nextFloat() < burstChance) {
            countdown = burstIntervalMin + RND.nextInt(Math.max(1, burstIntervalMax - burstIntervalMin + 1));
        } else {
            countdown = intervalMin + RND.nextInt(Math.max(1, intervalMax - intervalMin + 1));
        }
    }

    private static void strike(Minecraft mc, WorldClient world) {
        Entity view = mc.getRenderViewEntity();
        if (view == null) view = mc.player;

        // 稲妻の位置: 視線の前方に偏らせる。描画距離の内側に収める（遠方クリップで消えないように）
        float range = mc.gameSettings.renderDistanceChunks * 16f;
        float ratio = minDistanceRatio + RND.nextFloat() * Math.max(0f, maxDistanceRatio - minDistanceRatio);
        float dist = Math.max(range * ratio, 30f);
        double yaw = Math.toRadians(view.rotationYaw);
        double az = RND.nextFloat() < lookBias
                ? yaw + (RND.nextDouble() * 2.0 - 1.0) * 1.75
                : RND.nextDouble() * Math.PI * 2.0;
        double dx = -Math.sin(az);
        double dz = Math.cos(az);
        double ox = view.posX + dx * dist;
        double oz = view.posZ + dz * dist;

        // 地面の高さ（読み込まれていなければ足元の高さ）と、雲の下面の高さ
        double groundY = view.posY;
        BlockPos gp = new BlockPos(ox, 0, oz);
        if (world.getChunkProvider().getLoadedChunk(gp.getX() >> 4, gp.getZ() >> 4) != null) {
            groundY = world.getHeight(gp).getY();
        }
        double topY = RenderClouds.cloudHeight - 3.0;
        if (topY - groundY < 30.0) return;   // 雲が近すぎる・地面が高すぎるときは出さない

        Bolt b = new Bolt();
        b.ox = ox;
        b.oy = groundY;
        b.oz = oz;

        // 筋の形: 雲側の点（少し横にずらす）から地面まで
        float h = (float) (topY - groundY);
        float tx = (RND.nextFloat() * 2f - 1f) * h * 0.25f;
        float tz = (RND.nextFloat() * 2f - 1f) * h * 0.25f;
        subdivide(b, tx, h, tz, 0f, 0f, 0f, boltDetail, 1f);

        // 雲のフラッシュ用の向き
        float ddx = (float) (ox + tx - view.posX);
        float ddy = (float) (topY - view.posY);
        float ddz = (float) (oz + tz - view.posZ);
        float dl = MathHelper.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
        if (dl < 1e-3f) dl = 1f;
        b.dir[0] = ddx / dl;
        b.dir[1] = ddy / dl;
        b.dir[2] = ddz / dl;

        // 閃光: 1〜3回。2回目以降は少し弱く、間に短い暗がりが入る
        int n = 1 + RND.nextInt(3);
        b.strokeStart = new float[n];
        b.strokeLen = new float[n];
        b.strokeAmp = new float[n];
        float t = 0f;
        for (int i = 0; i < n; i++) {
            b.strokeStart[i] = t;
            b.strokeLen[i] = 1.5f + RND.nextFloat() * 2.5f;
            b.strokeAmp[i] = i == 0 ? 1f : 0.5f + 0.5f * RND.nextFloat();
            t += b.strokeLen[i] + 1f + RND.nextFloat() * 2.5f;
        }
        b.life = (int) Math.ceil(t) + 1;
        bolts.add(b);

        // 雷鳴（ランダムな遅れ。）
        if (playSound) {
            int delay = thunderDelayMin
                    + RND.nextInt(Math.max(1, thunderDelayMax - thunderDelayMin + 1))
                    + Math.round(dist * thunderDelayPerBlock);
            pending.add(new PendingThunder(delay, (float) dx, (float) dz, 0.8f + RND.nextFloat() * 0.2f));
        }
    }

    /** start → end を中点変位でギザギザにして b に積む。本体からは枝分かれもする */
    private static void subdivide(Bolt b, float x0, float y0, float z0,
                                  float x1, float y1, float z1, int depth, float strength) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float dz = z1 - z0;
        float len = MathHelper.sqrt(dx * dx + dy * dy + dz * dz);
        if (depth <= 0 || len < 1e-3f) {
            b.addSeg(x0, y0, z0, x1, y1, z1, strength);
            return;
        }

        // 中点を、線分に垂直な向きへランダムにずらす
        float rx = RND.nextFloat() * 2f - 1f;
        float ry = RND.nextFloat() * 2f - 1f;
        float rz = RND.nextFloat() * 2f - 1f;
        float d = (rx * dx + ry * dy + rz * dz) / (len * len);
        rx -= d * dx;
        ry -= d * dy;
        rz -= d * dz;
        float rl = MathHelper.sqrt(rx * rx + ry * ry + rz * rz);
        if (rl < 1e-4f) rl = 1f;
        float disp = len * boltJaggedness * (0.3f + 0.7f * RND.nextFloat());
        float mx = (x0 + x1) * 0.5f + rx / rl * disp;
        float my = (y0 + y1) * 0.5f + ry / rl * disp;
        float mz = (z0 + z1) * 0.5f + rz / rl * disp;

        subdivide(b, x0, y0, z0, mx, my, mz, depth - 1, strength);
        subdivide(b, mx, my, mz, x1, y1, z1, depth - 1, strength);

        // 枝分かれ: 本体の途中から、下向きに横へ流れる短い筋（枝はさらに枝分かれしない）
        if (strength >= 0.99f && depth >= 3 && depth <= 5 && RND.nextFloat() < boltBranchChance) {
            float sgn = RND.nextBoolean() ? 1f : -1f;
            float ex = mx + dx * 0.8f + rx / rl * len * 0.7f * sgn;
            float ey = my + dy * 0.8f;
            float ez = mz + dz * 0.8f + rz / rl * len * 0.7f * sgn;
            subdivide(b, mx, my, mz, ex, ey, ez, depth - 2, 0.5f);
        }
    }

    private static void playThunder(Minecraft mc, WorldClient world, PendingThunder t) {
        Entity view = mc.getRenderViewEntity();
        if (view == null) view = mc.player;
        // 稲妻の方向に少しずらした位置で鳴らす（左右の定位だけつける。距離での減衰はさせない）
        world.playSound(view.posX + t.dx * 6.0, view.posY, view.posZ + t.dz * 6.0,
                SoundEvents.ENTITY_LIGHTNING_THUNDER, SoundCategory.WEATHER,
                thunderVolume, t.pitch, false);
    }

    // ================= 描画 =================

    public static void render(RenderWorldLastEvent event) {
        if (!enabled || bolts.isEmpty()) return;

        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc.getRenderViewEntity();
        if (mc.world == null || view == null) return;

        float pt = event.getPartialTicks();
        double camX = view.lastTickPosX + (view.posX - view.lastTickPosX) * pt;
        double camY = view.lastTickPosY + (view.posY - view.lastTickPosY) * pt;
        double camZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt;

        if (drawBolt) drawBolts(pt, camX, camY, camZ);

        if (screenFlash > 0f) {
            float f = maxFlash(pt, false);
            if (f > 0.01f) drawScreenFlash(f * screenFlash);
        }
    }

    /** 一番強い稲妻の光。updateDir が true なら、その向きを flashDir に入れる */
    private static float maxFlash(float pt, boolean updateDir) {
        float best = 0f;
        Bolt bestBolt = null;
        for (Bolt b : bolts) {
            float f = b.flash(b.age + pt);
            if (f > best) {
                best = f;
                bestBolt = b;
            }
        }
        if (updateDir && bestBolt != null) {
            flashDir[0] = bestBolt.dir[0];
            flashDir[1] = bestBolt.dir[1];
            flashDir[2] = bestBolt.dir[2];
        }
        return best;
    }

    private static void drawBolts(float pt, double camX, double camY, double camZ) {
        GlStateManager.disableTexture2D();
        GlStateManager.disableCull();
        GlStateManager.disableLighting();
        GlStateManager.disableAlpha();
        GlStateManager.enableBlend();
        // 加算合成（光は重なるほど明るく）。深度テストは有効のまま → 地形の陰には隠れる
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        GlStateManager.depthMask(false);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);

        for (Bolt b : bolts) {
            float f = b.flash(b.age + pt);
            if (f <= 0.01f) continue;

            double bx = b.ox - camX;
            double by = b.oy - camY;
            double bz = b.oz - camZ;
            for (int s = 0; s < b.segCount; s++) {
                int o = s * 7;
                addRibbon(buf,
                        bx + b.seg[o], by + b.seg[o + 1], bz + b.seg[o + 2],
                        bx + b.seg[o + 3], by + b.seg[o + 4], bz + b.seg[o + 5],
                        b.seg[o + 6] * f);
            }
        }

        tess.draw();

        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
    }

    /** 線分 a→b を、画面に正対する帯（芯 + にじみ）として積む。座標はカメラ相対 */
    private static void addRibbon(BufferBuilder buf,
                                  double ax, double ay, double az,
                                  double bx, double by, double bz, float strength) {
        double mx = (ax + bx) * 0.5;
        double my = (ay + by) * 0.5;
        double mz = (az + bz) * 0.5;
        double dist = Math.sqrt(mx * mx + my * my + mz * mz);

        // 帯の幅の向き = 線分 × 視線
        double lx = bx - ax;
        double ly = by - ay;
        double lz = bz - az;
        double sx = ly * mz - lz * my;
        double sy = lz * mx - lx * mz;
        double sz = lx * my - ly * mx;
        double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl < 1e-6) return;

        double w = Math.max(minWidth, dist * angularWidth);
        double k = w / sl;

        // にじみ（太く薄く）
        double kg = k * glowScale;
        addQuad(buf, ax, ay, az, bx, by, bz, sx * kg, sy * kg, sz * kg,
                0.50f, 0.62f, 1.00f, Math.min(1f, strength * glowAlpha));
        // 芯（細く白く）
        addQuad(buf, ax, ay, az, bx, by, bz, sx * k, sy * k, sz * k,
                0.95f, 0.97f, 1.00f, Math.min(1f, strength));
    }

    private static void addQuad(BufferBuilder buf,
                                double ax, double ay, double az,
                                double bx, double by, double bz,
                                double ox, double oy, double oz,
                                float r, float g, float bl, float a) {
        buf.pos(ax - ox, ay - oy, az - oz).color(r, g, bl, a).endVertex();
        buf.pos(ax + ox, ay + oy, az + oz).color(r, g, bl, a).endVertex();
        buf.pos(bx + ox, by + oy, bz + oz).color(r, g, bl, a).endVertex();
        buf.pos(bx - ox, by - oy, bz - oz).color(r, g, bl, a).endVertex();
    }

    /** 画面全体を、加算で青白くうっすら光らせる */
    private static void drawScreenFlash(float alpha) {
        GlStateManager.disableTexture2D();
        GlStateManager.disableDepth();
        GlStateManager.disableCull();
        GlStateManager.disableLighting();
        GlStateManager.disableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
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
        float a = Math.min(1f, alpha);
        buf.pos(-1.0, -1.0, 0.0).color(0.75f, 0.82f, 1.0f, a).endVertex();
        buf.pos(1.0, -1.0, 0.0).color(0.75f, 0.82f, 1.0f, a).endVertex();
        buf.pos(1.0, 1.0, 0.0).color(0.75f, 0.82f, 1.0f, a).endVertex();
        buf.pos(-1.0, 1.0, 0.0).color(0.75f, 0.82f, 1.0f, a).endVertex();
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
}
