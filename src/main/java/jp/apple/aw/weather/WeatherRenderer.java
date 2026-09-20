package jp.apple.aw.weather;

import jp.apple.aw.AppleWeathersCore;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod.EventBusSubscriber(modid = AppleWeathersCore.ID, value = Side.CLIENT)
public class WeatherRenderer {
    @SubscribeEvent
    public static void onRenderWorldLast(RenderWorldLastEvent event) {
        switch (WeatherManager.currentWeather) {
            case SUNNY:
                break;
            case STORMY:
                break;
            case CLOUDY:
                break;
            case LIGHT_RAINY:
                break;
            case RAINY:
                break;
            case HEAVY_RAINY:
                break;
            case LIGHT_SNOWY:
                break;
            case SNOWY:
                break;
            case HEAVY_SNOWY:
                break;
        }
    }
}
