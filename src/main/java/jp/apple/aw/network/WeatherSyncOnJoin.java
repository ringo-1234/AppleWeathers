package jp.apple.aw.network;

import jp.apple.aw.AppleWeathersCore;
import jp.apple.aw.weather.WeatherManager;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

@Mod.EventBusSubscriber(modid = AppleWeathersCore.ID)
public class WeatherSyncOnJoin {
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            AppleWeathersNetwork.INSTANCE.sendTo(
                    new MessageWeatherSync(WeatherManager.currentWeather), (EntityPlayerMP) event.player);
        }
    }
}
