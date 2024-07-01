package stardom.sync;

import java.util.List;

public class WeatherData {
    private List<Weather> weather;

    public List<Weather> getWeather() {
        return weather;
    }

    public void setWeather(List<Weather> weather) {
        this.weather = weather;
    }

    public static class Weather {
        private String main; // Main weather condition, e.g., "Clear", "Rain", "Clouds"

        public String getMain() {
            return main;
        }

        public void setMain(String main) {
            this.main = main;
        }
    }
}
