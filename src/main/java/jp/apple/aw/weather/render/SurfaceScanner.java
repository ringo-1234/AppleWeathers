package jp.apple.aw.weather.render;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 1列 (x, z) について、雨が実際に当たる「上向きの面」を集める。
 *
 * ブロック単位ではなく、描画されているモデルの上向きクアッドを使う。
 * 上から順に面を拾い、すでに上の面で覆われた部分は下の面から引く。
 * 例: 階段の下の段は、上の段に覆われていない部分だけが濡れる。
 *
 * 細い面（柵の支柱・(MCデフォ)レール・板ガラスなど）は、面としても出力せず、雨もさえぎらないものとして扱う。
 * モデルを持たないブロック（チェスト・ベッドなど）はコリジョンボックスで代用する。
 */
public final class SurfaceScanner {

    // ================= 設定 =================

    /** 何ブロック下まで追うか */
    public static int scanLayers = 6;

    /** これより細い面（辺がこの長さ未満）は、出力もしないし雨もさえぎらない */
    public static float thinFaceSize = 0.3f;

    /** 面 1 つぶんの float 数。1面 = [x0, z0, x1, z1, y] */
    public static final int FACE_STRIDE = 5;

    // ================= 内部定数 =================

    private static final float EPS = 1e-4f;
    private static final float MIN_SIZE = 0.01f;
    /** これより傾いた面は「斜め」として対象外にする */
    private static final float FLAT_TOLERANCE = 0.002f;
    private static final int MAX_FACES = 32;
    private static final int MAX_OPEN = 48;
    /** 矩形 1 つぶんの float 数。1矩形 = [x0, z0, x1, z1] */
    private static final int RECT_STRIDE = 4;

    // ================= 作業用バッファ =================

    /** 1ブロックぶんの上向き面（ブロック内座標。高い順） */
    private static final float[] faces = new float[MAX_FACES * FACE_STRIDE];
    private static int faceCount = 0;

    /** まだ雨が届いている領域（セル内座標の矩形）。open と openTmp を入れ替えながら使う */
    private static float[] open = new float[MAX_OPEN * RECT_STRIDE];
    private static float[] openTmp = new float[MAX_OPEN * RECT_STRIDE];
    private static int openCount = 0;

    /** 状態ごとの面のキャッシュ（位置に依存しないブロックだけ） */
    private static final Map<IBlockState, float[]> CACHE = new HashMap<IBlockState, float[]>();
    private static final List<AxisAlignedBB> BOX_BUF = new ArrayList<AxisAlignedBB>();
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();

    private SurfaceScanner() {
    }

    /** モデルを読み込み直したとき（リソースリロード後など）に呼ぶ */
    public static void clearCache() {
        CACHE.clear();
    }

    // ================= 公開 API =================

    /**
     * 列 (x, z) の startY から下へ向かって、雨が当たる面を out に書き込む。
     * 1面 = [x0, z0, x1, z1, y]。x, z は (originX, originZ) からの相対座標。
     *
     * @return 書き込んだ面の数
     */
    public static int collect(World world, int x, int z, int startY,
                              float[] out, int outBase, int maxOut,
                              int originX, int originZ) {
        resetOpenRegion();

        float relX = x - originX;
        float relZ = z - originZ;
        int minY = Math.max(0, startY - scanLayers + 1);
        int written = 0;

        for (int y = startY; y >= minY && openCount > 0; y--) {
            POS.setPos(x, y, z);
            IBlockState state = world.getBlockState(POS);
            Material material = state.getMaterial();
            if (material == Material.AIR) continue;
            if (material.isLiquid()) break;
            
            boolean blocking = material.blocksMovement() || state.isFullCube();
            int faceTotal = blockFaces(world, POS, state);

            for (int f = 0; f < faceTotal; f++) {
                int o = f * FACE_STRIDE;
                float x0 = faces[o];
                float z0 = faces[o + 1];
                float x1 = faces[o + 2];
                float z1 = faces[o + 3];
                float faceY = faces[o + 4];

                if (isThin(x0, z0, x1, z1)) continue;
                written = emitOpenPart(out, outBase, maxOut, written,
                        relX, relZ, y + faceY, x0, z0, x1, z1);
                if (blocking) subtractOpen(x0, z0, x1, z1);
            }
        }
        return written;
    }

    /**
     * 雨をさえぎるブロックか。
     * 柵・板ガラス・格子・開いたトラップドア・ドアなど、上面が細い（thinFaceSize 未満）ものは false。
     * 屋根・床・地面の判定に使う。
     */
    public static boolean isSolid(World world, BlockPos pos, IBlockState state) {
        if (state.isFullCube()) return true;
        if (!state.getMaterial().blocksMovement()) return false;

        int faceTotal = blockFaces(world, pos, state);
        for (int f = 0; f < faceTotal; f++) {
            int o = f * FACE_STRIDE;
            if (!isThin(faces[o], faces[o + 1], faces[o + 2], faces[o + 3])) return true;
        }
        return false;
    }

    private static boolean isThin(float x0, float z0, float x1, float z1) {
        return Math.min(x1 - x0, z1 - z0) < thinFaceSize;
    }

    // ================= 雨が届く領域（矩形の集合） =================

    private static void resetOpenRegion() {
        openCount = 1;
        open[0] = 0f;
        open[1] = 0f;
        open[2] = 1f;
        open[3] = 1f;
    }

    /** 面 (x0, z0)-(x1, z1) のうち、雨が届いている部分を out に書き出す */
    private static int emitOpenPart(float[] out, int outBase, int maxOut, int written,
                                    float relX, float relZ, float surfaceY,
                                    float x0, float z0, float x1, float z1) {
        for (int r = 0; r < openCount; r++) {
            int ro = r * RECT_STRIDE;
            float ix0 = Math.max(x0, open[ro]);
            float iz0 = Math.max(z0, open[ro + 1]);
            float ix1 = Math.min(x1, open[ro + 2]);
            float iz1 = Math.min(z1, open[ro + 3]);
            if (ix1 - ix0 > MIN_SIZE && iz1 - iz0 > MIN_SIZE && written < maxOut) {
                int o = outBase + written * FACE_STRIDE;
                out[o] = relX + ix0;
                out[o + 1] = relZ + iz0;
                out[o + 2] = relX + ix1;
                out[o + 3] = relZ + iz1;
                out[o + 4] = surfaceY;
                written++;
            }
        }
        return written;
    }

    /** 雨が届く領域から矩形 s を引く */
    private static void subtractOpen(float sx0, float sz0, float sx1, float sz1) {
        int kept = 0;
        for (int r = 0; r < openCount; r++) {
            int ro = r * RECT_STRIDE;
            float x0 = open[ro];
            float z0 = open[ro + 1];
            float x1 = open[ro + 2];
            float z1 = open[ro + 3];

            boolean overlaps = !(sx1 <= x0 + EPS || sx0 >= x1 - EPS
                    || sz1 <= z0 + EPS || sz0 >= z1 - EPS);
            if (!overlaps) {
                kept = pushOpen(kept, x0, z0, x1, z1);
                continue;
            }
            
            if (sz0 > z0) kept = pushOpen(kept, x0, z0, x1, sz0);
            if (sz1 < z1) kept = pushOpen(kept, x0, sz1, x1, z1);
            float cz0 = Math.max(z0, sz0);
            float cz1 = Math.min(z1, sz1);
            if (sx0 > x0) kept = pushOpen(kept, x0, cz0, sx0, cz1);
            if (sx1 < x1) kept = pushOpen(kept, sx1, cz0, x1, cz1);
        }

        float[] swap = open;
        open = openTmp;
        openTmp = swap;
        openCount = kept;
    }

    /** openTmp の index 番目に矩形を追加して、次の index を返す（小さすぎる矩形・上限超えは捨てる） */
    private static int pushOpen(int index, float x0, float z0, float x1, float z1) {
        if (x1 - x0 <= MIN_SIZE || z1 - z0 <= MIN_SIZE || index >= MAX_OPEN) return index;
        int o = index * RECT_STRIDE;
        openTmp[o] = x0;
        openTmp[o + 1] = z0;
        openTmp[o + 2] = x1;
        openTmp[o + 3] = z1;
        return index + 1;
    }

    // ================= 1ブロックぶんの上向き面 =================

    /** 結果は faces / faceCount（高い順）。面の数を返す。 */
    private static int blockFaces(World world, BlockPos pos, IBlockState state) {
        faceCount = 0;
        
        if (state.isFullCube()) {
            addFace(0f, 0f, 1f, 1f, 1f);
            return faceCount;
        }

        IBlockState actual = state.getActualState(world, pos);
        IBlockState extended = actual.getBlock().getExtendedState(actual, world, pos);
        boolean cacheable = extended == actual;

        if (cacheable) {
            float[] cached = CACHE.get(actual);
            if (cached != null) {
                System.arraycopy(cached, 0, faces, 0, cached.length);
                faceCount = cached.length / FACE_STRIDE;
                return faceCount;
            }
        }

        EnumBlockRenderType renderType = actual.getRenderType();
        if (renderType == EnumBlockRenderType.MODEL) {
            try {
                gatherModelFaces(pos, actual, extended);
            } catch (Exception e) {
                faceCount = 0;
                gatherCollisionFaces(world, pos, actual);
            }
        } else if (renderType != EnumBlockRenderType.INVISIBLE) {
            gatherCollisionFaces(world, pos, actual);
        }

        sortFacesByHeight();
        if (cacheable) CACHE.put(actual, Arrays.copyOf(faces, faceCount * FACE_STRIDE));
        return faceCount;
    }

    private static void gatherModelFaces(BlockPos pos, IBlockState actual, IBlockState extended) {
        IBakedModel model = Minecraft.getMinecraft().getBlockRendererDispatcher().getModelForState(actual);
        long rand = MathHelper.getPositionRandom(pos);
        addUpQuads(model.getQuads(extended, null, rand));
        addUpQuads(model.getQuads(extended, EnumFacing.UP, rand));
    }

    /** モデルのクアッドのうち、上向きで水平なものを faces に追加する */
    private static void addUpQuads(List<BakedQuad> quads) {
        if (quads == null) return;
        for (BakedQuad quad : quads) {
            if (quad.getFace() != EnumFacing.UP) continue;

            int[] data = quad.getVertexData();
            VertexFormat format = quad.getFormat();
            int stride = format.getIntegerSize();
            if (stride < 3 || data.length < stride * 4) continue;

            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                float vx = Float.intBitsToFloat(data[i * stride]);
                float vy = Float.intBitsToFloat(data[i * stride + 1]);
                float vz = Float.intBitsToFloat(data[i * stride + 2]);
                minX = Math.min(minX, vx);
                maxX = Math.max(maxX, vx);
                minY = Math.min(minY, vy);
                maxY = Math.max(maxY, vy);
                minZ = Math.min(minZ, vz);
                maxZ = Math.max(maxZ, vz);
            }

            if (maxY - minY > FLAT_TOLERANCE) continue;
            addFace(minX, minZ, maxX, maxZ, maxY);
        }
    }

    private static void gatherCollisionFaces(World world, BlockPos pos, IBlockState state) {
        BOX_BUF.clear();
        state.addCollisionBoxToList(world, pos, new AxisAlignedBB(pos).grow(1.0), BOX_BUF, null, false);
        for (AxisAlignedBB box : BOX_BUF) {
            addFace((float) (box.minX - pos.getX()), (float) (box.minZ - pos.getZ()),
                    (float) (box.maxX - pos.getX()), (float) (box.maxZ - pos.getZ()),
                    (float) (box.maxY - pos.getY()));
        }
    }

    private static void addFace(float x0, float z0, float x1, float z1, float y) {
        if (faceCount >= MAX_FACES) return;
        setFace(faceCount * FACE_STRIDE, x0, z0, x1, z1, y);
        faceCount++;
    }

    private static void setFace(int offset, float x0, float z0, float x1, float z1, float y) {
        faces[offset] = x0;
        faces[offset + 1] = z0;
        faces[offset + 2] = x1;
        faces[offset + 3] = z1;
        faces[offset + 4] = y;
    }

    /** 高い面を先に処理したいので、高さの降順に並べる（面の数は少ないので挿入ソート） */
    private static void sortFacesByHeight() {
        for (int i = 1; i < faceCount; i++) {
            int o = i * FACE_STRIDE;
            float x0 = faces[o];
            float z0 = faces[o + 1];
            float x1 = faces[o + 2];
            float z1 = faces[o + 3];
            float y = faces[o + 4];

            int j = i - 1;
            while (j >= 0 && faces[j * FACE_STRIDE + 4] < y) {
                System.arraycopy(faces, j * FACE_STRIDE, faces, (j + 1) * FACE_STRIDE, FACE_STRIDE);
                j--;
            }
            setFace((j + 1) * FACE_STRIDE, x0, z0, x1, z1, y);
        }
    }
}