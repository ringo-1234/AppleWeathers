package jp.apple.aw.weather.render;

import jp.apple.aw.AppleWeathersCore;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.MobEffects;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * 天気に応じた霧（距離と色）。
 *
 * 値は RenderClouds.current（CloudStyle）から読むので、天気が変わると雲と一緒に少しずつ移り変わる。
 *  - 距離: 地形の霧の始まり・終わりを、描画距離に対する割合で決める
 *  - 色: バニラの霧の色を、明るさを保ったまま灰色に寄せて暗くする（夜は暗いまま）
 *
 * バニラは雨の強さ (rainStrength) で空と霧を暗くするが、このMODはバニラの天気を止めているので、
 * 霧の色も空も明るいまま。それをここと RenderClouds の「空のベール」で補う。
 * 霧の色はクリア色と地形の霧に使われるので、雲のシェーダーにも同じ色を渡して水平線をつなげる。
 *
 * イベントは @Mod.EventBusSubscriber で自動登録される（WeatherRenderer への追加は不要）。
 */
@Mod.EventBusSubscriber(modid = AppleWeathersCore.ID, value = Side.CLIENT)
public final class RenderFog {

    // ================= 設定 =================

    public static boolean enabled = true;
    /** 霧の距離を天気に合わせる */
    public static boolean adjustDistance = true;
    /** 霧の色を天気に合わせる */
    public static boolean adjustColor = true;

    /** 灰色の色味（RGB の倍率）。少し青みを持たせると雨雲らしくなる */
    private static final double[] GRAY_TINT = {0.96, 1.00, 1.06};

    // ================= 内部状態 =================

    /** 今フレームの FogColors で色を変えたか */
    private static boolean colorApplied = false;
    private static double fogR, fogG, fogB;
    /** 今フレームの霧の距離（ブロック）。0 = 変えていない */
    private static float fogStart = 0f;
    private static float fogEnd = 0f;

    private RenderFog() {
    }

    // ================= 公開 API（シェーダーへ渡す用） =================

    /**
     * 今の霧の色。画面のクリア色・地形の霧と同じ色を返す。
     * まだ適用されていない（イベントが来ていない・水中など）ときは、world の霧色を灰色に寄せて返す。
     */
    public static Vec3d getFogColor(World world, float pt) {
        if (colorApplied) return new Vec3d(fogR, fogG, fogB);
        return tint(world.getFogColor(pt), RenderClouds.current);
    }

    /** 空の色を、空のベール（曇天）の分だけ霧の色へ寄せる。濡れた地面の反射に使う */
    public static Vec3d overcastSky(Vec3d sky, Vec3d fog) {
        double v = enabled ? clamp01(RenderClouds.current.veil) : 0.0;
        return new Vec3d(sky.x + (fog.x - sky.x) * v,
                sky.y + (fog.y - sky.y) * v,
                sky.z + (fog.z - sky.z) * v);
    }

    /** 今フレームの霧の始まり（ブロック）。0 なら霧を変えていない */
    public static float getFogStart() {
        return fogStart;
    }

    /** 今フレームの霧の終わり（ブロック）。0 なら霧を変えていない */
    public static float getFogEnd() {
        return fogEnd;
    }

    // ================= イベント =================

    @SubscribeEvent
    public static void onFogColors(EntityViewRenderEvent.FogColors event) {
        colorApplied = false;
        if (!adjustColor || !shouldApply(event.getEntity(), event.getState())) return;

        Vec3d c = tint(new Vec3d(event.getRed(), event.getGreen(), event.getBlue()), RenderClouds.current);
        event.setRed((float) c.x);
        event.setGreen((float) c.y);
        event.setBlue((float) c.z);

        fogR = c.x;
        fogG = c.y;
        fogB = c.z;
        colorApplied = true;
    }

    @SubscribeEvent
    public static void onRenderFog(EntityViewRenderEvent.RenderFogEvent event) {
        boolean terrainPass = event.getFogMode() >= 0;
        if (terrainPass) fogStart = fogEnd = 0f;
        if (!adjustDistance || !shouldApply(event.getEntity(), event.getState())) return;

        CloudStyle s = RenderClouds.current;
        float far = event.getFarPlaneDistance();

        if (terrainPass) {
            fogStart = far * s.fogStart;
            fogEnd = far * s.fogEnd;
            GlStateManager.setFogStart(fogStart);
            GlStateManager.setFogEnd(fogEnd);
        } else {
            // 空を描く pass。始まりはそのまま（0）、終わりだけ合わせる → 空の縁も同じ距離感で霧の色へ溶ける
            GlStateManager.setFogEnd(far * s.fogEnd);
        }
    }

    // ================= 内部 =================

    private static boolean shouldApply(Entity entity, IBlockState state) {
        if (!enabled) return false;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || !mc.world.provider.isSurfaceWorld()) return false;
        if (state != null && state.getMaterial().isLiquid()) return false;   // 水中・溶岩の中はバニラのまま
        if (entity instanceof EntityLivingBase
                && ((EntityLivingBase) entity).isPotionActive(MobEffects.BLINDNESS)) return false;

        return isReady(RenderClouds.current);
    }

    /** CloudStyle がまだ初期化されていない（全部 0）ときは何もしない */
    private static boolean isReady(CloudStyle s) {
        return s.fogEnd > 0f && s.fogEnd > s.fogStart;
    }

    /** 明るさ（輝度）を保ったまま灰色に寄せ、暗くする。夜の暗い霧は暗いまま */
    private static Vec3d tint(Vec3d c, CloudStyle s) {
        if (!enabled || !adjustColor || !isReady(s)) return c;

        double lum = 0.299 * c.x + 0.587 * c.y + 0.114 * c.z;
        double gray = clamp01(s.fogGray);
        double dim = 1.0 - clamp01(s.fogDim);

        double r = (c.x + (lum * GRAY_TINT[0] - c.x) * gray) * dim;
        double g = (c.y + (lum * GRAY_TINT[1] - c.y) * gray) * dim;
        double b = (c.z + (lum * GRAY_TINT[2] - c.z) * gray) * dim;
        return new Vec3d(clamp01(r), clamp01(g), clamp01(b));
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }
}
