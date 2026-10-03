package jp.apple.aw.network;

import io.netty.buffer.ByteBuf;
import jp.apple.aw.weather.WeatherManager;
import jp.apple.aw.weather.WeatherType;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MessageWeatherSync implements IMessage {

    private WeatherType weather;
    
    public MessageWeatherSync() {
    }

    public MessageWeatherSync(WeatherType weather) {
        this.weather = weather;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        String name = ByteBufUtils.readUTF8String(buf);
        WeatherType w;
        try {
            w = WeatherType.valueOf(name);
        } catch (IllegalArgumentException e) {
            w = WeatherType.SUNNY;
        }
        weather = w;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, weather.name());
    }
    
    public static class Handler implements IMessageHandler<MessageWeatherSync, IMessage> {
        @Override
        public IMessage onMessage(MessageWeatherSync message, MessageContext ctx) {
            final WeatherType weather = message.weather;
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    WeatherManager.currentWeather = weather;
                }
            });
            return null;
        }
    }
}
