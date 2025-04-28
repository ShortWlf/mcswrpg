package com.swplmobinfo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public class Swplmobinfo implements ModInitializer {

	// These distances come from the config.
	private static double modDisplayDistance = 5.0;
	private static double tagDisplayDistance = 10.0;

	private static Map<PlayerEntity, Set<LivingEntity>> playerDisplayedEntities = new HashMap<>();
	// Holds mob configuration data.
	private static Map<String, String> mobConfig = new HashMap<>();

	// Caches for computed properties and names.
	private static Map<LivingEntity, Integer> entityStaticLevel = new WeakHashMap<>();
	private static Map<LivingEntity, String> lastDisplayText = new WeakHashMap<>();
	private static Map<LivingEntity, String> baseMobNameMap = new WeakHashMap<>();
	// Cache for player-applied custom names (via name tags).
	private static Map<LivingEntity, Text> playerNameTagCache = new WeakHashMap<>();

	@Override
	public void onInitialize() {
		loadConfig();

		// Update on every server tick.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (PlayerEntity player : server.getPlayerManager().getPlayerList()) {
				World world = player.getWorld();
				// Get entities within the larger tag range.
				List<LivingEntity> nearbyEntities = world.getEntitiesByClass(
						LivingEntity.class,
						player.getBoundingBox().expand(tagDisplayDistance),
						livingEntity -> true
				);
				Set<LivingEntity> trackedEntities = playerDisplayedEntities.getOrDefault(player, new HashSet<>());

				for (LivingEntity entity : nearbyEntities) {
					double distanceSq = entity.squaredDistanceTo(player);
					double modRangeSq = modDisplayDistance * modDisplayDistance;
					double tagRangeSq = tagDisplayDistance * tagDisplayDistance;

					if (distanceSq <= modRangeSq) {
						// Within mod display range.
						if (entity.hasCustomName() && !isModCustomName(entity)) {
							// Cache the player's custom name if not already cached.
							if (!playerNameTagCache.containsKey(entity)) {
								playerNameTagCache.put(entity, entity.getCustomName());
							}
							Text cachedName = playerNameTagCache.get(entity);
							if (entity.getCustomName() == null ||
									!cachedName.getString().equals(entity.getCustomName().getString()))
							{
								entity.setCustomName(cachedName);
							}
							entity.setCustomNameVisible(true);
						} else {
							// No player-applied tag – use mod-generated info.
							Text newDisplayText = getDisplayName(entity);
							String newTextStr = newDisplayText.getString();
							if (!newTextStr.equals(lastDisplayText.getOrDefault(entity, ""))) {
								entity.setCustomName(newDisplayText);
								entity.setCustomNameVisible(true);
								lastDisplayText.put(entity, newTextStr);
							}
						}
						trackedEntities.add(entity);
					} else if (distanceSq <= tagRangeSq) {
						// Outside mod range but inside tag display range.
						if (playerNameTagCache.containsKey(entity)) {
							// Reapply the cached player-applied tag and make it visible.
							Text cachedName = playerNameTagCache.get(entity);
							if (entity.getCustomName() == null ||
									!cachedName.getString().equals(entity.getCustomName().getString()))
							{
								entity.setCustomName(cachedName);
							}
							entity.setCustomNameVisible(true);
						} else {
							// If mod-generated, clear it so that nothing is shown at this distance.
							if (entity.hasCustomName() && isModCustomName(entity)) {
								entity.setCustomName(null);
								entity.setCustomNameVisible(false);
								lastDisplayText.remove(entity);
								baseMobNameMap.remove(entity);
							}
						}
						trackedEntities.add(entity);
					} else {
						// Outside tag display range.
						if (entity.hasCustomName()) {
							if (isModCustomName(entity)) {
								// Clear mod-generated custom names completely.
								entity.setCustomName(null);
								entity.setCustomNameVisible(false);
							} else {
								// For player-applied names, just hide them without clearing.
								entity.setCustomNameVisible(false);
							}
						}
						trackedEntities.remove(entity);
						lastDisplayText.remove(entity);
						baseMobNameMap.remove(entity);
						// Note: We intentionally preserve playerNameTagCache so the player's tag can be restored.
					}
				}

				// Clean up tracked entities that are no longer nearby.
				trackedEntities.removeIf(entity -> {
					if (!nearbyEntities.contains(entity)) {
						if (entity.hasCustomName() && isModCustomName(entity)) {
							entity.setCustomName(null);
							entity.setCustomNameVisible(false);
						} else if (entity.hasCustomName() && !isModCustomName(entity)) {
							// Hide player-applied custom names when completely out of range.
							entity.setCustomNameVisible(false);
						}
						lastDisplayText.remove(entity);
						baseMobNameMap.remove(entity);
						return true;
					}
					return false;
				});
				playerDisplayedEntities.put(player, trackedEntities);
			}
		});
	}

	/**
	 * Loads the configuration from config/mob_config.json.
	 * The file is expected to have the following structure:
	 *
	 * {
	 *   "modDisplayDistance": 5.0,
	 *   "tagDisplayDistance": 10.0,
	 *   "mobs": {
	 *     "cow": "peaceful",
	 *     "skeleton": "hostile",
	 *     "zombie": "hostile",
	 *     "sheep": "peaceful",
	 *     "creeper": "hostile",
	 *     "end_boss": "hostile",
	 *     "nether_boss": "hostile"
	 *   }
	 * }
	 */
	private void loadConfig() {
		try {
			Path configPath = Path.of("config", "mob_config.json");
			if (!Files.exists(configPath)) {
				createDefaultConfig(configPath);
			}
			Gson gson = new Gson();
			JsonObject root = gson.fromJson(new InputStreamReader(Files.newInputStream(configPath)), JsonObject.class);

			if (root.has("modDisplayDistance")) {
				modDisplayDistance = root.get("modDisplayDistance").getAsDouble();
			}
			if (root.has("tagDisplayDistance")) {
				tagDisplayDistance = root.get("tagDisplayDistance").getAsDouble();
			}
			if (root.has("mobs")) {
				JsonObject mobsObj = root.getAsJsonObject("mobs");
				for (Map.Entry<String, JsonElement> entry : mobsObj.entrySet()) {
					if (entry.getValue().isJsonPrimitive()) {
						mobConfig.put(entry.getKey().toLowerCase(), entry.getValue().getAsString());
					} else {
						System.err.println("Invalid configuration value for key: " + entry.getKey());
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Creates the default configuration file with pretty printing for easier editing.
	 */
	private void createDefaultConfig(Path configPath) {
		try {
			Gson gson = new GsonBuilder().setPrettyPrinting().create();
			JsonObject root = new JsonObject();
			root.addProperty("modDisplayDistance", 5.0);
			root.addProperty("tagDisplayDistance", 10.0);

			JsonObject mobs = new JsonObject();
			mobs.addProperty("cow", "peaceful");
			mobs.addProperty("skeleton", "hostile");
			mobs.addProperty("zombie", "hostile");
			mobs.addProperty("sheep", "peaceful");
			mobs.addProperty("creeper", "hostile");
			mobs.addProperty("end_boss", "hostile");
			mobs.addProperty("nether_boss", "hostile");

			root.add("mobs", mobs);

			Files.createDirectories(configPath.getParent());
			Files.writeString(configPath, gson.toJson(root));
			System.out.println("Default mob_config.json created at: " + configPath);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Constructs the mod's custom display name for an entity.
	 * Uses a cached base name (from the entity type) to avoid repeated appending,
	 * displays current health and a static level computed once from max health.
	 */
	private Text getDisplayName(LivingEntity entity) {
		String baseName;
		if (baseMobNameMap.containsKey(entity)) {
			baseName = baseMobNameMap.get(entity);
		} else {
			baseName = entity.getType().getName().getString();
			baseMobNameMap.put(entity, baseName);
		}

		int level = entityStaticLevel.computeIfAbsent(entity, e -> (int) (e.getMaxHealth() / 2));
		int currentHealth = (int) entity.getHealth();
		String combined = baseName + " HP: " + currentHealth + " Level: " + level;

		String aggression = mobConfig.getOrDefault(baseName.toLowerCase(), "peaceful");
		Formatting color;
		if (isBoss(entity)) {
			color = Formatting.LIGHT_PURPLE;
		} else if ("hostile".equals(aggression)) {
			color = Formatting.RED;
		} else {
			color = Formatting.GREEN;
		}
		return Text.literal(combined).formatted(color);
	}

	/**
	 * Determines whether an entity should be considered a boss.
	 */
	private boolean isBoss(LivingEntity entity) {
		return entity.getType().toString().toLowerCase().contains("boss");
	}

	/**
	 * Checks whether an entity’s custom name was generated by our mod.
	 * We identify mod-generated names by checking for markers like " HP: " and " Level: ".
	 */
	private boolean isModCustomName(LivingEntity entity) {
		if (entity.getCustomName() == null) return false;
		String customName = entity.getCustomName().getString();
		return customName.contains(" HP: ") && customName.contains(" Level: ");
	}
}
