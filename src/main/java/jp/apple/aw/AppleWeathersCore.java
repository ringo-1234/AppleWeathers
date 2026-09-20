package jp.apple.aw;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = Tags.MOD_ID,name = Tags.MOD_NAME,version = Tags.VERSION)
public class AppleWeathersCore {
    public static final String ID = Tags.MOD_ID;
    public static final String NAME = Tags.MOD_NAME;
    public static final String VERSION = Tags.VERSION;
            
    public static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME);
    
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("Loaded: {}",Tags.MOD_NAME);
    }
    
}
