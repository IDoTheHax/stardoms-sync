package stardom.sync;

import com.google.gson.Gson;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalTime;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.mojang.brigadier.builder.RequiredArgumentBuilder.argument;


public class StardomsSync implements ModInitializer {
	public static final String MOD_ID = "sync";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final String WEATHER_API_KEY = "bd704e195e5a0797e38a866df93f8769\n";
	private static final String WEATHER_API_URL = "http://api.openweathermap.org/data/2.5/weather?q=%s&appid=%s";
	private static final long FETCH_INTERVAL_SECONDS = 300; // Fetch weather every 5 minutes

	private final OkHttpClient httpClient = new OkHttpClient.Builder()
			.connectTimeout(30, TimeUnit.SECONDS)
			.readTimeout(30, TimeUnit.SECONDS)
			.build();

	private final Gson gson = new Gson();
	private Timer fetchWeatherTimer;
	private boolean weatherSyncActive = false;

	@Override
	public void onInitialize() {
		ServerTickEvents.END_SERVER_TICK.register(this::syncTime);
		CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> dispatcher.register(LiteralArgumentBuilder.<ServerCommandSource>literal("realtime")
                .executes(StardomsSync.this::executeRealtimeCommand)));

		ServerTickEvents.END_SERVER_TICK.register(this::syncTime);
		CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> dispatcher.register(LiteralArgumentBuilder.<ServerCommandSource>literal("realtime")
				.executes(StardomsSync.this::executeRealtimeCommand)));

		ServerLifecycleEvents.SERVER_STARTED.register(this::serverStarted);
		CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> dispatcher.register(LiteralArgumentBuilder.<ServerCommandSource>literal("realtime")
				.executes(StardomsSync.this::executeRealtimeCommand)));
		CommandRegistrationCallback.EVENT.register(this::registerCommands);

	}

	private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, boolean dedicated) {
        LiteralArgumentBuilder<ServerCommandSource> startWeatherCommand = LiteralArgumentBuilder.<ServerCommandSource>literal("startweather")
				.then(Objects.requireNonNull(argument("city", StringArgumentType.word()))
						.executes(context -> {
							String cityName = StringArgumentType.getString(context, "city");
							startFetchingWeather(context.getSource().getServer(), cityName);
							weatherSyncActive = true;
							context.getSource().sendFeedback((Supplier<Text>) Text.translatable("command.weather.startsync", cityName), false);
							return 1;
						}));

		// Register command to stop weather synchronization
		LiteralArgumentBuilder<ServerCommandSource> stopWeatherCommand = LiteralArgumentBuilder.<ServerCommandSource>literal("stopweather")
				.executes(context -> {
					if (weatherSyncActive) {
						stopFetchingWeather();
						weatherSyncActive = false;
						context.getSource().sendFeedback((Supplier<Text>) Text.translatable("command.weather.stopsync"), false);
					} else {
						context.getSource().sendFeedback((Supplier<Text>) Text.translatable("command.weather.notactive"), false);
					}
					return 1;
				});

		dispatcher.register(startWeatherCommand);
		dispatcher.register(stopWeatherCommand);
	}

	private void serverStarted(MinecraftServer server) {

	}

	private void startFetchingWeather(MinecraftServer server, String cityName) {
		fetchWeatherTimer = new Timer();
		fetchWeatherTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				fetchWeatherData(server, cityName);
			}
		}, 0, FETCH_INTERVAL_SECONDS * 1000); // Schedule to run every FETCH_INTERVAL_SECONDS seconds
	}

	private void stopFetchingWeather() {
		if (fetchWeatherTimer != null) {
			fetchWeatherTimer.cancel();
			fetchWeatherTimer = null;
		}
	}

	private void syncTime(MinecraftServer server) {
		if (!server.getOverworld().isClient) {
			long realSeconds = LocalTime.now().minusHours(6).toSecondOfDay();
			long minecraftTicks = (realSeconds * 24000) / 86400;
			server.getOverworld().setTimeOfDay(minecraftTicks);
		}
	}

	private int executeRealtimeCommand(CommandContext<ServerCommandSource> context) {
		long minecraftTicks = context.getSource().getWorld().getTimeOfDay();
		long realSeconds = (minecraftTicks * 86400) / 24000;
		LocalTime realTime = LocalTime.ofSecondOfDay(realSeconds).plusHours(6);
		Objects.requireNonNull(context.getSource().getPlayer()).sendMessage(Text.of("Real world time: " + realTime), false);
		return 1;
	}

	private void fetchWeatherData(MinecraftServer server, String cityName) {
		String apiUrl = String.format(WEATHER_API_URL, cityName, WEATHER_API_KEY);

		Request request = new Request.Builder()
				.url(apiUrl)
				.build();

		httpClient.newCall(request).enqueue(new Callback() {
			@Override
			public void onResponse(@NotNull okhttp3.Call call, @NotNull Response response) throws IOException {
				try {
					if (response.isSuccessful()) {
						String responseBody = response.body().string();
						WeatherData weatherData = gson.fromJson(responseBody, WeatherData.class);
						applyWeatherToMinecraft(server, weatherData);
					} else {
						LOGGER.error("Failed to fetch weather data: {}", response.code());
					}
				} finally {
					if (response.body() != null) {
						response.body().close();
					}
				}
			}

			@Override
			public void onFailure(@NotNull okhttp3.Call call, @NotNull IOException e) {
				LOGGER.error("Failed to fetch weather data", e);
			}
		});
	}

	private void applyWeatherToMinecraft(MinecraftServer server, WeatherData weatherData) {
		if (weatherData != null && weatherData.getWeather() != null && !weatherData.getWeather().isEmpty()) {
			WeatherData.Weather weather = weatherData.getWeather().get(0); // Get the first weather entry
			String mainWeather = weather.getMain(); // Main weather condition, e.g., "Clear", "Rain", "Clouds"

			// Example: Set Minecraft weather based on external weather condition
			if ("Clear".equalsIgnoreCase(mainWeather)) {
				server.getWorld(World.OVERWORLD).setWeather(0, 600, true, false); // Clear weather
				LOGGER.info("Applied weather condition to Minecraft: {}", mainWeather);
			} else if ("Rain".equalsIgnoreCase(mainWeather) || "Drizzle".equalsIgnoreCase(mainWeather)) {
				server.getWorld(World.OVERWORLD).setWeather(600, 1200, true, true); // Rain
				LOGGER.info("Applied weather condition to Minecraft: {}", mainWeather);
			} else if ("Thunderstorm".equalsIgnoreCase(mainWeather)) {
				server.getWorld(World.OVERWORLD).setWeather(600, 1200, true, true); // Thunderstorm
				LOGGER.info("Applied weather condition to Minecraft: {}", mainWeather);
			} else {
				LOGGER.info("Other Condition: {}", mainWeather);
			}

		} else {
			LOGGER.warn("Weather data is null or empty");
		}
	}

	private static <S> ArgumentBuilder<ServerCommandSource, ?> argument(String name, S type) {
		return null;
	}
}