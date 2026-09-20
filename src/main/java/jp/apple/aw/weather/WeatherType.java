package jp.apple.aw.weather;

public enum WeatherType {
    /* 晴れ */
    SUNNY("晴れ", "01"),
    /* 雷雨 */
    STORMY("雷雨", "02"),
    /* 曇り */
    CLOUDY("曇り", "03"),
    /* 雨 */
    LIGHT_RAINY("小雨", "04"),
    RAINY("雨", "05"),
    HEAVY_RAINY("大雨", "06"),
    /* 雪 */
    LIGHT_SNOWY("小雪", "07"),
    SNOWY("雪", "08"),
    HEAVY_SNOWY("大雪", "09");

    private final String label;
    private final String code;

    WeatherType(String label, String code) {
        this.label = label;
        this.code = code;
    }

    public String getLabel() {
        return label;
    }

    public String getCode() {
        return code;
    }
}
