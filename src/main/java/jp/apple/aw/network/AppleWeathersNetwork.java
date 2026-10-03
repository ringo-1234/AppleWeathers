package jp.apple.aw.network;

import jp.apple.aw.AppleWeathersCore;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public final class AppleWeathersNetwork {

    public static final SimpleNetworkWrapper INSTANCE =
            NetworkRegistry.INSTANCE.newSimpleChannel(AppleWeathersCore.ID);

    private static int nextId = 0;

    private AppleWeathersNetwork() {
    }

    public static void init() {
        INSTANCE.registerMessage(MessageWeatherSync.Handler.class, MessageWeatherSync.class, nextId++, Side.CLIENT);
    }
}
