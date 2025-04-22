package com.swplmobinfo;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;

import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class Swplmobinfo implements ModInitializer {

	private static final double RENDER_DISTANCE = 3.0; // Distance for displaying names
	private static Map<PlayerEntity, Set<LivingEntity>> playerDisplayedEntities = new HashMap<>(); // Track displayed entities for each player
	private static Map<String, String> mobConfig; // Map to hold mob configurations

	@Override
	public void onInitialize() {
		loadConfig(); // Load the configuration at startup

		// Register the event for rendering name above players and mobs
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (PlayerEntity player : server.getPlayerManager().getPlayerList()) {
				World world = player.getWorld();

				// Get all living entities within the defined range
				List<LivingEntity> nearbyEntities = world.getEntitiesByClass(LivingEntity.class, player.getBoundingBox().expand(RENDER_DISTANCE), livingEntity -> true);

				// Track entities displayed to the current player
				Set<LivingEntity> currentDisplayedEntities = playerDisplayedEntities.getOrDefault(player, new HashSet<>());

				// Display names for nearby entities
				for (LivingEntity livingEntity : nearbyEntities) {
					if (!currentDisplayedEntities.contains(livingEntity)) {
						// Create and set the name with health and level
						Text displayName = getDisplayName(livingEntity);
						livingEntity.setCustomName(displayName);
						livingEntity.setCustomNameVisible(true); // Makes the nameplate visible
						currentDisplayedEntities.add(livingEntity); // Add to current player's displayed entities
					}
				}

				// Reset custom names for entities not in range anymore
				currentDisplayedEntities.removeIf(livingEntity -> {
					if (!nearbyEntities.contains(livingEntity)) {
						livingEntity.setCustomName(null); // Clear the name if out of range
						livingEntity.setCustomNameVisible(false);
						return true; // Remove from the displayed set
					}
					return false;
				});

				// Update the player's displayed entities set
				playerDisplayedEntities.put(player, currentDisplayedEntities);
			}
		});
	}

	private void loadConfig() {
		mobConfig = new HashMap<>();
		try {
			Path configPath = Path.of("config", "mob_config.json");

			// Check if the config file exists
			if (!Files.exists(configPath)) {
				// Create the config file with default values
				createDefaultConfig(configPath);
			}

			// Load the configuration file
			Gson gson = new Gson();
			JsonObject configData = gson.fromJson(new InputStreamReader(Files.newInputStream(configPath)), JsonObject.class);

			// Populate the mobConfig map
			for (String key : configData.keySet()) {
				// Check the type of the value
				if (configData.get(key).isJsonPrimitive()) {
					mobConfig.put(key, configData.get(key).getAsString());
				} else {
					System.err.println("Invalid configuration value for key: " + key);
					// Handle this case (e.g., log the error or provide a default value)
				}
			}
		} catch (Exception e) {
			e.printStackTrace(); // Handle the exception (e.g., JSON parsing errors)
		}
	}

	private void createDefaultConfig(Path configPath) {
		try {
			// Create default configuration JSON as a single line, including bosses
			String defaultConfig = "{\"cow\":\"peaceful\"," +
					"\"skeleton\":\"hostile\"," +
					"\"zombie\":\"hostile\"," +
					"\"sheep\":\"peaceful\"," +
					"\"creeper\":\"hostile\"," +
					"\"end_boss\":\"hostile\"," + // Example boss entry
					"\"nether_boss\":\"hostile\"}"; // Another example boss entry

			// Write the default configuration to the file
			Files.createDirectories(configPath.getParent()); // Ensure the parent directory exists
			Files.writeString(configPath, defaultConfig);
			System.out.println("Default mob_config.json created at: " + configPath);
		} catch (Exception e) {
			e.printStackTrace(); // Handle any file writing exceptions
		}
	}

	private Text getDisplayName(LivingEntity entity) {
		int health = (int) entity.getHealth();
		int level = health / 2; // Example: Level is half the health for simplicity
		String mobName = entity.getDisplayName().getString();

		// Determine color based on entity aggression from the config
		String aggression = mobConfig.getOrDefault(mobName.toLowerCase(), "peaceful");
		Formatting color;

		if (isBoss(entity)) {
			color = Formatting.LIGHT_PURPLE; // Bosses will be purple
		} else if ("hostile".equals(aggression)) {
			color = Formatting.RED; // Hostile mobs are red
		} else {
			color = Formatting.GREEN; // Peaceful mobs are green
		}

		// Create multi-line text for display with desired layout
		return Text.literal(mobName + " HP: " + health + " Level: " + level).formatted(color);
	}

	private boolean isBoss(LivingEntity entity) {
		// Add your logic to determine if the entity is a boss
		return entity.getType().toString().contains("boss"); // Adjust as needed
	}
}
