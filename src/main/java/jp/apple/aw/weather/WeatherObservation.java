package jp.apple.aw.weather;

import java.time.LocalDateTime;

public class WeatherObservation {
    private final LocalDateTime observationTime;
    private final WeatherType weather;
    private final WindDirectionType windDirection;
    private final double windSpeedMps;
    private final double temperature;

    public WeatherObservation(LocalDateTime observationTime, WeatherType weather,
                              WindDirectionType windDirection, double windSpeedMps, double temperature) {
        this.observationTime = observationTime;
        this.weather = weather;
        this.windDirection = windDirection;
        this.windSpeedMps = windSpeedMps;
        this.temperature = temperature;
    }

    public LocalDateTime getObservationTime() { return observationTime; }
    public WeatherType getWeather() { return weather; }
    public WindDirectionType getWindDirection() { return windDirection; }
    public double getWindSpeedMps() { return windSpeedMps; }
    public double getTemperature() { return temperature; }
}
