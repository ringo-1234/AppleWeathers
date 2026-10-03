package jp.apple.aw.weather;

import jp.apple.aw.network.AppleWeathersNetwork;
import jp.apple.aw.network.MessageWeatherSync;

public class WeatherManager {
    public static WeatherType currentWeather = WeatherType.SUNNY;
    public static boolean doWeatherCycle = true;
    public static int weatherTickLeft = 12000;
    
    public static void setWeather(WeatherType weather) {
        currentWeather = weather;
        AppleWeathersNetwork.INSTANCE.sendToAll(new MessageWeatherSync(weather));
    }
}
