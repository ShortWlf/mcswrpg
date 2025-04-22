package com.swloottext;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Swloottext implements ModInitializer {

	private final Map<String, Formatting> rarityColors = new HashMap<>();
	private final Map<String, String> itemRarities = new HashMap<>();
	private boolean debugMode = false;

	private static final String CONFIG_PATH = "config/swloottext.json";

	@Override
	public void onInitialize() {
		loadConfig();

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				World world = player.getWorld();
				for (ItemEntity itemEntity : world.getEntitiesByClass(ItemEntity.class, player.getBoundingBox().expand(10), e -> true)) {
					ItemStack stack = itemEntity.getStack();
					String itemName = stack.getName().getString();
					String itemID = stack.getItem().toString();
					int itemCount = stack.getCount();

					String rarity = itemRarities.getOrDefault(itemID, "common");
					if (debugMode) System.out.println("Item: " + itemID + " | Rarity: " + rarity);

					Formatting color = rarityColors.getOrDefault(rarity, Formatting.WHITE);
					if (debugMode) System.out.println("Item: " + itemID + " | Color: " + color);

					String displayText = itemCount > 1 ? itemName + " (" + itemCount + ")" : itemName;
					itemEntity.setCustomName(Text.literal(displayText).formatted(color));
					itemEntity.setCustomNameVisible(true);
				}
			}
		});
	}

	private void loadConfig() {
		File configFile = new File(CONFIG_PATH);
		File configDir = new File(configFile.getParent());
		if (!configDir.exists()) {
			configDir.mkdirs();
		}
		if (!configFile.exists()) {
			System.out.println("Config file not found. Creating default config.");
			createDefaultConfig(configFile);
		}
		try (FileReader reader = new FileReader(configFile)) {
			Gson gson = new Gson();
			JsonObject jsonConfig = gson.fromJson(reader, JsonObject.class);

			debugMode = jsonConfig.has("debug") && jsonConfig.get("debug").getAsBoolean();
			if (debugMode) System.out.println("Debug mode is enabled.");

			JsonObject rarityColorsJson = jsonConfig.getAsJsonObject("rarity_colors");
			for (String key : rarityColorsJson.keySet()) {
				try {
					Formatting color = Formatting.valueOf(rarityColorsJson.get(key).getAsString().toUpperCase());
					rarityColors.put(key, color);
					if (debugMode) System.out.println("Rarity: " + key + " | Color: " + color);
				} catch (IllegalArgumentException e) {
					System.err.println("Invalid color for rarity " + key + ": " + rarityColorsJson.get(key).getAsString());
				}
			}

			JsonObject itemRaritiesJson = jsonConfig.getAsJsonObject("item_rarities");
			for (String key : itemRaritiesJson.keySet()) {
				itemRarities.put(key, itemRaritiesJson.get(key).getAsString().toLowerCase());
				if (debugMode) System.out.println("Item: " + key + " | Rarity: " + itemRaritiesJson.get(key).getAsString());
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void createDefaultConfig(File configFile) {
		JsonObject defaultConfig = new JsonObject();
		defaultConfig.addProperty("debug", false);

		JsonObject rarityColors = new JsonObject();
		rarityColors.addProperty("common", "white");
		rarityColors.addProperty("uncommon", "yellow");
		rarityColors.addProperty("rare", "light_purple");
		rarityColors.addProperty("legendary", "gold");
		rarityColors.addProperty("epic", "aqua");
		rarityColors.addProperty("mythic", "red");
		defaultConfig.add("rarity_colors", rarityColors);

		JsonObject itemRarities = new JsonObject();

		// --- ARMOR ---
		itemRarities.addProperty("minecraft:leather_helmet", "common");
		itemRarities.addProperty("minecraft:leather_chestplate", "common");
		itemRarities.addProperty("minecraft:leather_leggings", "common");
		itemRarities.addProperty("minecraft:leather_boots", "common");
		itemRarities.addProperty("minecraft:chainmail_helmet", "uncommon");
		itemRarities.addProperty("minecraft:chainmail_chestplate", "uncommon");
		itemRarities.addProperty("minecraft:chainmail_leggings", "uncommon");
		itemRarities.addProperty("minecraft:chainmail_boots", "uncommon");
		itemRarities.addProperty("minecraft:iron_helmet", "uncommon");
		itemRarities.addProperty("minecraft:iron_chestplate", "uncommon");
		itemRarities.addProperty("minecraft:iron_leggings", "uncommon");
		itemRarities.addProperty("minecraft:iron_boots", "uncommon");
		itemRarities.addProperty("minecraft:golden_helmet", "uncommon");
		itemRarities.addProperty("minecraft:golden_chestplate", "uncommon");
		itemRarities.addProperty("minecraft:golden_leggings", "uncommon");
		itemRarities.addProperty("minecraft:golden_boots", "uncommon");
		itemRarities.addProperty("minecraft:diamond_helmet", "rare");
		itemRarities.addProperty("minecraft:diamond_chestplate", "rare");
		itemRarities.addProperty("minecraft:diamond_leggings", "rare");
		itemRarities.addProperty("minecraft:diamond_boots", "rare");
		itemRarities.addProperty("minecraft:netherite_helmet", "epic");
		itemRarities.addProperty("minecraft:netherite_chestplate", "epic");
		itemRarities.addProperty("minecraft:netherite_leggings", "epic");
		itemRarities.addProperty("minecraft:netherite_boots", "epic");
		itemRarities.addProperty("minecraft:turtle_helmet", "uncommon");
		itemRarities.addProperty("minecraft:elytra", "legendary");
		itemRarities.addProperty("minecraft:shield", "common");

		// --- TOOLS ---
		itemRarities.addProperty("minecraft:wooden_pickaxe", "common");
		itemRarities.addProperty("minecraft:wooden_axe", "common");
		itemRarities.addProperty("minecraft:wooden_shovel", "common");
		itemRarities.addProperty("minecraft:wooden_hoe", "common");
		itemRarities.addProperty("minecraft:stone_pickaxe", "common");
		itemRarities.addProperty("minecraft:stone_axe", "common");
		itemRarities.addProperty("minecraft:stone_shovel", "common");
		itemRarities.addProperty("minecraft:stone_hoe", "common");
		itemRarities.addProperty("minecraft:iron_pickaxe", "uncommon");
		itemRarities.addProperty("minecraft:iron_axe", "uncommon");
		itemRarities.addProperty("minecraft:iron_shovel", "uncommon");
		itemRarities.addProperty("minecraft:iron_hoe", "uncommon");
		itemRarities.addProperty("minecraft:golden_pickaxe", "uncommon");
		itemRarities.addProperty("minecraft:golden_axe", "uncommon");
		itemRarities.addProperty("minecraft:golden_shovel", "uncommon");
		itemRarities.addProperty("minecraft:golden_hoe", "uncommon");
		itemRarities.addProperty("minecraft:diamond_pickaxe", "rare");
		itemRarities.addProperty("minecraft:diamond_axe", "rare");
		itemRarities.addProperty("minecraft:diamond_shovel", "rare");
		itemRarities.addProperty("minecraft:diamond_hoe", "rare");
		itemRarities.addProperty("minecraft:netherite_pickaxe", "epic");
		itemRarities.addProperty("minecraft:netherite_axe", "epic");
		itemRarities.addProperty("minecraft:netherite_shovel", "epic");
		itemRarities.addProperty("minecraft:netherite_hoe", "epic");
		itemRarities.addProperty("minecraft:fishing_rod", "common");
		itemRarities.addProperty("minecraft:shears", "common");
		itemRarities.addProperty("minecraft:flint_and_steel", "common");
		itemRarities.addProperty("minecraft:compass", "common");
		itemRarities.addProperty("minecraft:clock", "common");
		itemRarities.addProperty("minecraft:spyglass", "uncommon");
		itemRarities.addProperty("minecraft:brush", "uncommon");

		// --- WEAPONS ---
		itemRarities.addProperty("minecraft:wooden_sword", "common");
		itemRarities.addProperty("minecraft:stone_sword", "common");
		itemRarities.addProperty("minecraft:iron_sword", "uncommon");
		itemRarities.addProperty("minecraft:golden_sword", "uncommon");
		itemRarities.addProperty("minecraft:diamond_sword", "rare");
		itemRarities.addProperty("minecraft:netherite_sword", "epic");
		itemRarities.addProperty("minecraft:bow", "common");
		itemRarities.addProperty("minecraft:crossbow", "uncommon");
		itemRarities.addProperty("minecraft:trident", "rare");
		itemRarities.addProperty("minecraft:arrow", "common");

		// --- FOOD ---
		itemRarities.addProperty("minecraft:apple", "common");
		itemRarities.addProperty("minecraft:bread", "common");
		itemRarities.addProperty("minecraft:porkchop", "common");
		itemRarities.addProperty("minecraft:cooked_porkchop", "common");
		itemRarities.addProperty("minecraft:chicken", "common");
		itemRarities.addProperty("minecraft:cooked_chicken", "common");
		itemRarities.addProperty("minecraft:beef", "common");
		itemRarities.addProperty("minecraft:cooked_beef", "common");
		itemRarities.addProperty("minecraft:rabbit", "common");
		itemRarities.addProperty("minecraft:cooked_rabbit", "common");
		itemRarities.addProperty("minecraft:mutton", "common");
		itemRarities.addProperty("minecraft:cooked_mutton", "common");
		itemRarities.addProperty("minecraft:fish", "common");
		itemRarities.addProperty("minecraft:cooked_salmon", "common");
		itemRarities.addProperty("minecraft:tropical_fish", "uncommon");
		itemRarities.addProperty("minecraft:pufferfish", "uncommon");
		itemRarities.addProperty("minecraft:carrot", "common");
		itemRarities.addProperty("minecraft:golden_carrot", "uncommon");
		itemRarities.addProperty("minecraft:potato", "common");
		itemRarities.addProperty("minecraft:baked_potato", "common");
		itemRarities.addProperty("minecraft:beetroot", "common");
		itemRarities.addProperty("minecraft:beetroot_soup", "common");
		itemRarities.addProperty("minecraft:melon_slice", "common");
		itemRarities.addProperty("minecraft:sweet_berries", "common");
		itemRarities.addProperty("minecraft:glow_berries", "common");
		itemRarities.addProperty("minecraft:honey_bottle", "common");
		itemRarities.addProperty("minecraft:honeycomb", "common");
		itemRarities.addProperty("minecraft:cake", "uncommon");
		itemRarities.addProperty("minecraft:pumpkin_pie", "uncommon");
		itemRarities.addProperty("minecraft:cookie", "common");
		itemRarities.addProperty("minecraft:mushroom_stew", "common");
		itemRarities.addProperty("minecraft:rabbit_stew", "uncommon");
		itemRarities.addProperty("minecraft:suspicious_stew", "uncommon");
		itemRarities.addProperty("minecraft:golden_apple", "rare");
		itemRarities.addProperty("minecraft:enchanted_golden_apple", "legendary");

		// --- POTIONS & ENCHANTED BOOKS ---
		itemRarities.addProperty("minecraft:potion", "uncommon");
		itemRarities.addProperty("minecraft:splash_potion", "uncommon");
		itemRarities.addProperty("minecraft:lingering_potion", "rare");
		itemRarities.addProperty("minecraft:enchanted_book", "rare");

		// --- RARE MATERIALS & MISCELLANEOUS ---
		itemRarities.addProperty("minecraft:diamond", "rare");
		itemRarities.addProperty("minecraft:emerald", "rare");
		itemRarities.addProperty("minecraft:netherite_ingot", "epic");
		itemRarities.addProperty("minecraft:nether_star", "legendary");
		itemRarities.addProperty("minecraft:ancient_debris", "epic");
		itemRarities.addProperty("minecraft:dragon_breath", "rare");
		itemRarities.addProperty("minecraft:totem_of_undying", "legendary");
		itemRarities.addProperty("minecraft:ender_pearl", "uncommon");
		itemRarities.addProperty("minecraft:blaze_rod", "uncommon");
		itemRarities.addProperty("minecraft:ghast_tear", "rare");
		itemRarities.addProperty("minecraft:shulker_shell", "rare");
		itemRarities.addProperty("minecraft:scute", "uncommon");
		itemRarities.addProperty("minecraft:heart_of_the_sea", "legendary");
		itemRarities.addProperty("minecraft:nautilus_shell", "rare");

		defaultConfig.add("item_rarities", itemRarities);

		try (FileWriter writer = new FileWriter(configFile)) {
			Gson gson = new GsonBuilder().setPrettyPrinting().create();
			writer.write(gson.toJson(defaultConfig));
			System.out.println("Default config created at: " + configFile.getPath());
		} catch (IOException e) {
			System.err.println("Failed to create default config file: " + e.getMessage());
		}
	}
}