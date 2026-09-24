package jp.apple.aw.weather;

import jp.apple.aw.AppleWeathersCore;
import jp.apple.aw.weather.render.*;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod.EventBusSubscriber(modid = AppleWeathersCore.ID, value = Side.CLIENT)
public class WeatherRenderer {
    @SubscribeEvent
    public static void onRenderWorldLast(RenderWorldLastEvent event) {
        RenderClouds.render(event);
        RenderAtmosphere.render(event);
        RenderLightning.render(event);
        RenderWetGround.render(event);
        RenderSnowGround.render(event);
        RenderSnow.render(event);
        RenderRainy.render(event);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        int wetIntensity = 0;
        int snowGroundIntensity = 0;
        float snowParticleTarget = 0f;
        float rainParticleTarget = 0f;
        boolean weatherActive = false;
        switch (WeatherManager.currentWeather) {
            case LIGHT_RAINY:
                wetIntensity = 1;
                rainParticleTarget = 4f;
                weatherActive = true;
                break;
            case RAINY:
                wetIntensity = 2;
                rainParticleTarget = 8f;
                weatherActive = true;
                break;
            case HEAVY_RAINY:
                wetIntensity = 3;
                rainParticleTarget = 15f;
                weatherActive = true;
                break;
            case STORMY:
                wetIntensity = 3;
                rainParticleTarget = 20f;
                weatherActive = true;
                break;
            case LIGHT_SNOWY:
                snowGroundIntensity = 1;
                snowParticleTarget = 4f;
                weatherActive = true;
                break;
            case SNOWY:
                snowGroundIntensity = 2;
                snowParticleTarget = 8f;
                weatherActive = true;
                break;
            case HEAVY_SNOWY:
                snowGroundIntensity = 3;
                snowParticleTarget = 15f;
                weatherActive = true;
                break;
            default:
                break;
        }

        boolean surfaceNeeded = weatherActive
                || RenderWetGround.getWetness() > 0.002f
                || RenderSnowGround.getLevel() > 0.002f;

        RenderClouds.onClientTick(WeatherManager.currentWeather);
        RenderLightning.onClientTick(WeatherManager.currentWeather);
        RenderWetGround.onClientTick(wetIntensity, surfaceNeeded);
        RenderSnowGround.onClientTick(snowGroundIntensity);
        RenderSnow.onClientTick(snowParticleTarget);
        RenderRainy.onClientTick(rainParticleTarget);
    }
}
