package jp.apple.aw.vanilla;

import jp.apple.aw.AppleWeathersCore;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber(modid = AppleWeathersCore.ID)
public class VanillaWeatherCommand {
    @SubscribeEvent
    public static void onCommand(CommandEvent event) {
        if (event.getCommand().getName().equals("weather")) {
            event.setCanceled(true);
            
            event.getSender().sendMessage(new TextComponentString(
                    "§c[AppleWeathers] このワールドでそのコマンドは使えません。"
            ));
        }
    }
}
