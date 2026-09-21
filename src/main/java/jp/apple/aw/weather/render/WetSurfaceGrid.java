package jp.apple.aw.weather.render;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;

import java.util.Arrays;

/**
 * プレイヤー周辺の「雨に濡れる面」をまとめた地表グリッド。数tickごとに作り直してキャッシュする。
 *
 * 面の集め方（視点の高さに依存しない）:
 *  - 列 (x, z) ごとに、まず「雨が直接当たる面」(Q_OPEN) を集める。
 *  - その下に屋根があって空間がある場合、その床も「屋根の下の面」(Q_SHELTER) として集める。
 *    2階建てなら各階の床が別々の面になる。
 *  - 屋根の厚みが maxRoofThickness を超える固まりは地面・山として扱い、その下（洞窟など）は探さない。
 *
 * 露出度:
 *  - 雨が直接当たる面 = 1.0
 *  - 屋根の下の床 = 同じ高さで雨が届く場所からの距離で下がる
 */
public final class WetSurfaceGrid {

    // ================= 設定 =================

    /** 軒先のすぐ内側での露出度（雨の当たる場所に隣接した床） */
    public static float shelterMax = 0.80f;
    /** 奥まで入ったときの露出度 */
    public static float shelterMin = 0.06f;
    /** 軒先から乾くまでの距離感（ブロック。大きいほどゆっくり乾く） */
    public static float shelterDecay = 2.5f;
    /** 露出度を伝える最大距離（ブロック） */
    public static int shelterMaxDist = 10;
    /** これより厚い固まりは屋根ではなく地面・山として扱う（木の樹冠は 4〜5 層程度） */
    public static int maxRoofThickness = 6;
    /** 屋根の下の空間を、屋根から何ブロック下まで探すか */
    public static int maxGapDepth = 15;
    /** 空の光が届かない場所（洞窟・建物の奥）は濡らさない */
    public static boolean requireSkyLight = true;

    // ================= 定数 =================

    private static final int GRID_MARGIN = 3;
    private static final int MAX_COL_QUADS = 16;
    private static final int INF = Integer.MAX_VALUE;

    private static final byte Q_OPEN = 0;     // 雨が直接当たる面
    private static final byte Q_SHELTER = 1;  // 屋根・木の下の床

    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();

    // ================= 状態（RenderWetGround が描画で読む） =================

    private static boolean valid = false;

    /** グリッドの一辺（列の数） */
    static int size = 0;
    /** グリッド左下の列のワールド座標 */
    static int originX = 0;
    static int originZ = 0;

    /** 面の数と、面ごとのデータ（配列は再構築で作り直されることがあるので、毎回フィールドから読むこと） */
    static int quadCount = 0;
    static float[] qGeom = new float[0];     // [x0, z0, x1, z1, y] x,z はグリッド原点からの相対
    static float[] qPuddle = new float[0];   // 水たまりの出やすさ（葉・絨毯・雪などは 0）
    static float[] qVExp = new float[0];     // 4頂点ぶんの露出度 [e00, e01, e11, e10]

    // ---- 内部だけで使う ----

    // 列ごと
    private static int[] colStart = new int[0];
    private static int[] colCount = new int[0];
    /** 雨が通る最初のブロックの Y（= 雨が止まるブロックの1つ上）。未ロードは INF */
    private static int[] colTop = new int[0];

    // 面ごと
    private static byte[] qKind = new byte[0];
    private static int[] qCol = new int[0];
    private static int[] qDist = new int[0];
    private static float[] qExp = new float[0];
    private static int[] queue = new int[0];

    private WetSurfaceGrid() {
    }

    // ================= 公開 API =================

    public static boolean isValid() {
        return valid;
    }

    /** グリッドを捨てる（雨が止んだとき・リソースリロード時・ワールドを出たとき） */
    public static void invalidate() {
        valid = false;
        quadCount = 0;
    }

    /** 雨が止まる高さ（雨が通る最初のブロックの Y）。グリッド外・未ロードは -1。 */
    public static int getRainTopY(int x, int z) {
        if (!valid) return -1;
        int gx = x - originX;
        int gz = z - originZ;
        if (gx < 0 || gz < 0 || gx >= size || gz >= size) return -1;
        int t = colTop[gz * size + gx];
        return t == INF ? -1 : t;
    }

    /** view を中心に、radius ブロック + 余白ぶんのグリッドを作り直す */
    public static void rebuild(World world, Entity view, int radius) {
        int r = radius + GRID_MARGIN;
        int n = 2 * r + 1;
        ensureGrid(n);

        originX = MathHelper.floor(view.posX) - r;
        originZ = MathHelper.floor(view.posZ) - r;
        quadCount = 0;

        for (int gz = 0; gz < n; gz++) {
            for (int gx = 0; gx < n; gx++) {
                scanColumn(world, originX + gx, originZ + gz, gz * n + gx);
            }
        }
        computeExposure(n);
        computeVertexExposure();
        valid = true;
    }

    // ================= 配列の確保 =================

    private static void ensureGrid(int n) {
        if (n == size && colTop.length == n * n) return;
        int total = n * n;
        colStart = new int[total];
        colCount = new int[total];
        colTop = new int[total];
        size = n;
    }

    private static void ensureQuadCap(int need) {
        if (qKind.length >= need) return;
        int cap = Math.max(need, Math.max(1024, qKind.length * 2));
        qGeom = Arrays.copyOf(qGeom, cap * 5);
        qKind = Arrays.copyOf(qKind, cap);
        qCol = Arrays.copyOf(qCol, cap);
        qPuddle = Arrays.copyOf(qPuddle, cap);
        qDist = Arrays.copyOf(qDist, cap);
        qExp = Arrays.copyOf(qExp, cap);
        qVExp = Arrays.copyOf(qVExp, cap * 4);
        queue = Arrays.copyOf(queue, cap);
    }

    // ================= 面の収集 =================

    /**
     * 列 (x, z) の面を全部集める。視点の高さは使わない。
     *  1. 雨が直接当たる面 (Q_OPEN)
     *  2. 屋根（薄い固まり）の下の空間の床 (Q_SHELTER)。階ごとに繰り返す
     */
    private static void scanColumn(World world, int x, int z, int col) {
        colStart[col] = quadCount;
        colCount[col] = 0;
        colTop[col] = INF;

        if (world.getChunkProvider().getLoadedChunk(x >> 4, z >> 4) == null) return;

        int topY = world.getPrecipitationHeight(POS.setPos(x, 0, z)).getY();
        topY = skipThinBlockers(world, x, z, topY);
        colTop[col] = topY;
        if (topY <= 1) return;

        ensureQuadCap(quadCount + MAX_COL_QUADS);
        int budget = MAX_COL_QUADS;

        // 1. 雨が直接当たる面
        int n = SurfaceScanner.collect(world, x, z, topY, qGeom, quadCount * 5, budget, originX, originZ);
        commit(world, x, z, col, n, Q_OPEN);
        budget -= n;

        // 水面の下は対象外
        if (world.getBlockState(POS.setPos(x, topY - 1, z)).getMaterial().isLiquid()) return;

        // 2. 屋根の下の床を、下へ向かって階ごとに探す
        int y = topY - 1;   // 雨をさえぎっているブロック
        int run = 1;        // 連続する固まりの厚み
        while (budget > 0 && y > 1) {
            y--;
            IBlockState st = world.getBlockState(POS.setPos(x, y, z));
            Material m = st.getMaterial();
            if (m.isLiquid()) return;
            // 柵・格子・開いたトラップドアなど細いものは「固い」に数えない（空間として扱う）
            if (m.blocksMovement() && SurfaceScanner.isSolid(world, POS, st)) {
                if (++run > maxRoofThickness) return;   // 厚い = 地面・山。その下は探さない
                continue;
            }

            // 屋根の下の空間。床（固いブロック）を探す
            int floorY = -1;
            int limit = Math.max(0, y - maxGapDepth);
            for (int yy = y - 1; yy >= limit; yy--) {
                IBlockState fs = world.getBlockState(POS.setPos(x, yy, z));
                Material fm = fs.getMaterial();
                if (fm.isLiquid()) return;
                if (fm.blocksMovement() && SurfaceScanner.isSolid(world, POS, fs)) {
                    floorY = yy;
                    break;
                }
            }
            if (floorY < 0) return;

            int airY = floorY + 1;
            if (!requireSkyLight
                    || world.getLightFor(EnumSkyBlock.SKY, POS.setPos(x, airY, z)) > 0) {
                n = SurfaceScanner.collect(world, x, z, airY, qGeom, quadCount * 5, budget, originX, originZ);
                commit(world, x, z, col, n, Q_SHELTER);
                budget -= n;
            }

            y = floorY;
            run = 1;
        }
    }

    /**
     * getPrecipitationHeight は柵や格子も「雨が止まる」と数える。
     * 細いものは雨を通すので、固いブロック（または水面）に当たるまで高さを下げる。
     * 見つからなければ元の高さのまま。
     */
    private static int skipThinBlockers(World world, int x, int z, int topY) {
        int y = topY;
        int limit = Math.max(1, topY - maxGapDepth);
        while (y > limit) {
            IBlockState st = world.getBlockState(POS.setPos(x, y - 1, z));
            Material m = st.getMaterial();
            if (m.isLiquid()) return y;
            if (m.blocksMovement() && SurfaceScanner.isSolid(world, POS, st)) return y;
            y--;
        }
        return topY;
    }

    /** collect() が qGeom に書いた n 個の面に、種類・列・水たまりの出やすさを付けて確定する */
    private static void commit(World world, int x, int z, int col, int n, byte kind) {
        for (int i = 0; i < n; i++) {
            int q = quadCount + i;
            float qy = qGeom[q * 5 + 4];
            int by = Math.max(0, (int) Math.ceil(qy - 1e-3f) - 1);   // この面を持つブロックの Y
            qKind[q] = kind;
            qCol[q] = col;
            qPuddle[q] = puddleFactor(world.getBlockState(POS.setPos(x, by, z)).getMaterial());
        }
        quadCount += n;
        colCount[col] += n;
    }

    /** 水たまりができにくい素材は 0（濡れて暗くなるだけ）。 */
    private static float puddleFactor(Material m) {
        if (m == Material.LEAVES || m == Material.PLANTS || m == Material.VINE
                || m == Material.CACTUS || m == Material.CLOTH || m == Material.CARPET
                || m == Material.SNOW || m == Material.CRAFTED_SNOW
                || m == Material.WEB || m == Material.SPONGE) {
            return 0f;
        }
        return 1f;
    }

    // ================= 露出度 =================

    /**
     * 屋根の下の面への「雨の届きやすさ」を、同じ高さの面をたどって広げる。
     *  - 始点: 雨が直接当たる面（距離 0）、および横の列が同じ高さで空に開いている屋根の下の面（距離 1）
     *  - 隣の列へは、高さの差が 1 ブロック以内の面にだけ伝わる（壁や別の階は越えない）
     */
    private static void computeExposure(int n) {
        int head = 0;
        int tail = 0;

        for (int q = 0; q < quadCount; q++) {
            if (qKind[q] == Q_OPEN) {
                qDist[q] = 0;
                queue[tail++] = q;
            } else {
                qDist[q] = INF;
            }
        }

        for (int q = 0; q < quadCount; q++) {
            if (qKind[q] != Q_SHELTER) continue;
            int col = qCol[q];
            int gx = col % n;
            int gz = col / n;
            int airY = (int) Math.floor(qGeom[q * 5 + 4] + 1e-3f);   // 面のすぐ上の空間のブロックY
            if ((gx > 0 && colTop[col - 1] <= airY)
                    || (gx < n - 1 && colTop[col + 1] <= airY)
                    || (gz > 0 && colTop[col - n] <= airY)
                    || (gz < n - 1 && colTop[col + n] <= airY)) {
                qDist[q] = 1;
                queue[tail++] = q;
            }
        }

        while (head < tail) {
            int q = queue[head++];
            int d = qDist[q];
            if (d >= shelterMaxDist) continue;
            int col = qCol[q];
            int gx = col % n;
            int gz = col / n;
            float y = qGeom[q * 5 + 4];
            int nd = d + 1;
            if (gx > 0) tail = relax(col - 1, y, nd, tail);
            if (gx < n - 1) tail = relax(col + 1, y, nd, tail);
            if (gz > 0) tail = relax(col - n, y, nd, tail);
            if (gz < n - 1) tail = relax(col + n, y, nd, tail);
        }

        for (int q = 0; q < quadCount; q++) {
            if (qKind[q] == Q_OPEN) {
                qExp[q] = 1f;
            } else {
                int d = qDist[q];
                qExp[q] = d == INF
                        ? shelterMin
                        : shelterMin + (shelterMax - shelterMin) * (float) Math.exp(-d / shelterDecay);
            }
        }
    }

    private static int relax(int c, float y, int nd, int tail) {
        int s = colStart[c];
        int e = s + colCount[c];
        for (int k = s; k < e; k++) {
            if (qKind[k] == Q_SHELTER && qDist[k] == INF
                    && Math.abs(qGeom[k * 5 + 4] - y) <= 1.01f) {
                qDist[k] = nd;
                queue[tail++] = k;
            }
        }
        return tail;
    }

    /** 各面の4頂点の露出度を、隣の列の「同じ高さの面」と滑らかにつなぐ（毎フレームではなく再構築時に1回だけ） */
    private static void computeVertexExposure() {
        for (int q = 0; q < quadCount; q++) {
            int o = q * 5;
            double wx0 = originX + qGeom[o];
            double wz0 = originZ + qGeom[o + 1];
            double wx1 = originX + qGeom[o + 2];
            double wz1 = originZ + qGeom[o + 3];
            float y = qGeom[o + 4];
            int v = q * 4;
            qVExp[v] = exposureAt(wx0, wz0, y, q);
            qVExp[v + 1] = exposureAt(wx0, wz1, y, q);
            qVExp[v + 2] = exposureAt(wx1, wz1, y, q);
            qVExp[v + 3] = exposureAt(wx1, wz0, y, q);
        }
    }

    /** ワールド座標 (wx, wz)・高さ y の露出度。周囲4列から、高さが 1 ブロック以内で一番近い面だけを使って補間する。 */
    private static float exposureAt(double wx, double wz, float y, int self) {
        double gx = wx - originX - 0.5;
        double gz = wz - originZ - 0.5;
        int x0 = (int) Math.floor(gx);
        int z0 = (int) Math.floor(gz);
        float fx = (float) (gx - x0);
        float fz = (float) (gz - z0);

        float sum = 0f;
        float wsum = 0f;
        for (int dz = 0; dz <= 1; dz++) {
            for (int dx = 0; dx <= 1; dx++) {
                int cx = x0 + dx;
                int cz = z0 + dz;
                if (cx < 0 || cz < 0 || cx >= size || cz >= size) continue;
                int c = cz * size + cx;

                int best = -1;
                float bestD = 1.01f;
                int s = colStart[c];
                int e = s + colCount[c];
                for (int k = s; k < e; k++) {
                    float d = Math.abs(qGeom[k * 5 + 4] - y);
                    if (d <= bestD) {
                        bestD = d;
                        best = k;
                    }
                }
                if (best < 0) continue;

                float w = (dx == 0 ? 1f - fx : fx) * (dz == 0 ? 1f - fz : fz);
                sum += qExp[best] * w;
                wsum += w;
            }
        }
        return wsum > 1e-4f ? sum / wsum : qExp[self];
    }
}