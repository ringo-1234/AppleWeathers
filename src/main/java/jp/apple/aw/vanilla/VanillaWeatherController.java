package jp.apple.aw.vanilla;

import jp.apple.aw.AppleWeathersCore;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

@Mod.EventBusSubscriber(modid = AppleWeathersCore.ID)
public class VanillaWeatherController {
    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event){
        if (!event.world.isRemote && event.phase == TickEvent.Phase.START){
            WorldInfo worldInfo = event.world.getWorldInfo();
            
            if (worldInfo.isRaining()){
                worldInfo.setRaining(false);
            }
            if (worldInfo.isThundering()){
                worldInfo.setThundering(false);
            }
            worldInfo.setRainTime(24000);
            worldInfo.setThunderTime(24000);
        }
    }
}
