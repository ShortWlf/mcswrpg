package com.swdncycle;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

public class Swdncycle implements ModInitializer {
	private static final String CONFIG_DIR = "config/daynightcyclemod";
	private static final String CONFIG_FILE = "config.properties";
	private static final String DAY_DURATION_KEY = "dayDurationMinutes";
	private static final String NIGHT_DURATION_KEY = "nightDurationMinutes";
	private static final String DEBUG_MODE_KEY = "debugMode";
	private static final int DEFAULT_DAY_DURATION_MINUTES = 60;
	private static final int DEFAULT_NIGHT_DURATION_MINUTES = 20;
	private static final boolean DEFAULT_DEBUG_MODE = false;

	private int dayDurationTicks = DEFAULT_DAY_DURATION_MINUTES * 1200;
	private int nightDurationTicks = DEFAULT_NIGHT_DURATION_MINUTES * 1200;
	private boolean debugMode = DEFAULT_DEBUG_MODE;
	private CycleState currentState = CycleState.DAY;
	private int elapsedTicks = 0;
	private boolean nightMessageBroadcasted = false;

	@Override
	public void onInitialize() {
		loadConfig();
		ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
		logDebug("DayNightCycleMod initialized with Day Duration: " + (dayDurationTicks / 1200) + " minutes, Night Duration: " + (nightDurationTicks / 1200) + " minutes.");
	}

	private void onServerTick(MinecraftServer server) {
		RegistryKey<World> overworldKey = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld"));
		ServerWorld overworld = server.getWorld(overworldKey);
		if (overworld == null) {
			return;
		}

		long currentWorldTime = overworld.getTimeOfDay();

		switch (currentState) {
			case DAY:
				if (elapsedTicks == 0) {
					setWorldTime(overworld, 1000);
					setDaylightCycle(overworld, false);
					logDebug("Day cycle started. Time set to 1000 and daylight cycle disabled.");
					nightMessageBroadcasted = false;
				}
				elapsedTicks++;
				if (elapsedTicks >= dayDurationTicks - (20 * 1200) && !nightMessageBroadcasted) {
					setDaylightCycle(overworld, true);
					logDebug("Day cycle ending. Releasing daylight cycle for natural transition to night.");
					nightMessageBroadcasted = true;
				}
				if (currentWorldTime >= 13000) {
					currentState = CycleState.NIGHT;
					elapsedTicks = 0;
					logDebug("Transitioned to NIGHT.");
				}
				break;
			case NIGHT:
				if (currentWorldTime == 12000 && !nightMessageBroadcasted) {
					broadcastMessage(server, "The night is approaching!");
					logDebug("Night cycle starting.");
					nightMessageBroadcasted = true;
				}
				if (elapsedTicks == 0) {
					logDebug("Night cycle started.");
				}
				elapsedTicks++;
				if (currentWorldTime >= 24000) {
					resetToDay(overworld);
				}
				break;
		}
	}

	private void resetToDay(ServerWorld overworld) {
		currentState = CycleState.DAY;
		elapsedTicks = 0;
		setDaylightCycle(overworld, false);
		setWorldTime(overworld, 1000);
		nightMessageBroadcasted = false;
		logDebug("Resetting to DAY.");
	}

	private void setWorldTime(ServerWorld world, long time) {
		world.setTimeOfDay(time);
		logDebug("Time set to " + time + " ticks.");
	}

	private void setDaylightCycle(ServerWorld world, boolean enabled) {
		world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(enabled, world.getServer());
		logDebug("Set doDaylightCycle to " + enabled);
	}

	private void broadcastMessage(MinecraftServer server, String message) {
		server.getPlayerManager().broadcast(Text.literal(message), false);
		logDebug("Broadcasted message: \"" + message + "\"");
	}

	private void loadConfig() {
		File configDirectory = new File(CONFIG_DIR);
		if (!configDirectory.exists()) {
			boolean dirsCreated = configDirectory.mkdirs();
			if (!dirsCreated) {
				System.err.println("DayNightCycleMod: Failed to create config directory: " + CONFIG_DIR);
				return;
			}
		}
		File configFile = new File(configDirectory, CONFIG_FILE);
		if (configFile.exists()) {
			try (FileInputStream fis = new FileInputStream(configFile)) {
				Properties props = new Properties();
				props.load(fis);
				int dayMinutes = Integer.parseInt(props.getProperty(DAY_DURATION_KEY, String.valueOf(DEFAULT_DAY_DURATION_MINUTES)));
				int nightMinutes = Integer.parseInt(props.getProperty(NIGHT_DURATION_KEY, String.valueOf(DEFAULT_NIGHT_DURATION_MINUTES)));
				debugMode = Boolean.parseBoolean(props.getProperty(DEBUG_MODE_KEY, String.valueOf(DEFAULT_DEBUG_MODE)));
				dayDurationTicks = dayMinutes * 1200;
				nightDurationTicks = nightMinutes * 1200;
				logDebug("DayNightCycleMod: Loaded config - Day Duration: " + dayMinutes + " minutes, Night Duration: " + nightMinutes + " minutes.");
			} catch (IOException | NumberFormatException e) {
				e.printStackTrace();
				System.err.println("DayNightCycleMod: Failed to load config, using default settings.");
				saveConfig();
			}
		} else {
			saveConfig();
			logDebug("DayNightCycleMod: Config file not found. Created default config.");
		}
	}

	private void saveConfig() {
		Properties props = new Properties();
		props.setProperty(DAY_DURATION_KEY, String.valueOf(DEFAULT_DAY_DURATION_MINUTES));
		props.setProperty(NIGHT_DURATION_KEY, String.valueOf(DEFAULT_NIGHT_DURATION_MINUTES));
		props.setProperty(DEBUG_MODE_KEY, String.valueOf(DEFAULT_DEBUG_MODE));
		try (FileOutputStream fos = new FileOutputStream(new File(CONFIG_DIR, CONFIG_FILE))) {
			props.store(fos, "DayNightCycleMod Configuration");
			logDebug("DayNightCycleMod: Default config saved.");
		} catch (IOException e) {
			e.printStackTrace();
			System.err.println("DayNightCycleMod: Failed to save config.");
		}
	}

	private void logDebug(String message) {
		if (debugMode) {
			System.out.println("[DayNightCycleMod DEBUG]: " + message);
		}
	}

	private enum CycleState {
		DAY,
		NIGHT
	}
}
