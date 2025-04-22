package com.swrpgccrafting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * This mod auto‑generates a datapack (with recipes, advancements, and pack.mcmeta) from a configuration file.
 * The configuration, stored in config/swrpgccrafting.json, defines:
 *
 *   • A pack_mcmeta string (for the datapack metadata).
 *   • A list of recipes (each with its crafting layout, key, result, and which datapack presets it applies to).
 *   • A mapping of datapack presets (which determine the output folder structure).
 *
 * For every recipe, the mod writes the recipe JSON file under the designated folders. In addition, for recipes that
 * are in the base datapack (i.e. in the "base" preset), an advancement file is generated so that the recipe shows up
 * in the in‑game crafting (recipe) guide.
 *
 * Finally, the mod synchronizes the datapack folder by removing any recipe or advancement file that is not defined
 * in the config.
 */
public class Swrpgccrafting implements ModInitializer {
	public static final String MOD_ID = "swrpgccrafting";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Base folder for the datapack (relative to your world folder)
	private static final String DATAPACK_BASE = "world/datapacks/swrpgcrafting";

	// Advancement files are generated in:
	// world/datapacks/swrpgcrafting/data/swrpgccrafting/advancements/recipes/building_blocks/<recipeName>.json
	// (Using our mod id as the namespace for advancements, ensuring recipe IDs are like "swrpgccrafting:name_tag")
	private static final String ADVANCEMENT_FOLDER = DATAPACK_BASE + "/data/" + MOD_ID + "/advancements/recipes/building_blocks/";

	// Path to the mod's configuration file.
	private static final String CONFIG_FILE = "config/swrpgccrafting.json";

	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private Config config;

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Swrpgccrafting mod.");

		// Load (or auto‑create) the configuration.
		loadOrCreateConfig();

		// Build a set of expected file paths (absolute) so that we can later remove obsolete files.
		Set<Path> expectedPaths = new HashSet<>();

		// Process each recipe from the config.
		// For each datapack preset listed in the recipe's "datapacks" array, generate the recipe JSON.
		for (RecipeConfig recipe : config.recipes) {
			for (String presetKey : recipe.datapacks) {
				DatapackPreset preset = config.datapackPresets.get(presetKey);
				if (preset == null) {
					LOGGER.warn("Datapack preset '{}' not found for recipe '{}'", presetKey, recipe.name);
					continue;
				}
				String folderPath;
				String filePath;
				if (preset.directory == null || preset.directory.isEmpty()) {
					// For the base, we now use a namespaced folder (so that the recipe id becomes "swrpgccrafting:<name>")
					folderPath = DATAPACK_BASE + "/" + preset.recipePathPrefix;
					filePath = folderPath + recipe.name + ".json";
				} else {
					folderPath = DATAPACK_BASE + "/" + preset.directory + "/" + preset.recipePathPrefix;
					filePath = folderPath + recipe.name + ".json";
				}
				createFolder(folderPath);

				// Create the JSON structure for the recipe.
				Map<String, Object> jsonMap = new LinkedHashMap<>();
				Map<String, Object> resultMap = new LinkedHashMap<>();
				resultMap.put("id", recipe.result.id);
				resultMap.put("count", recipe.result.count);
				jsonMap.put("result", resultMap);
				jsonMap.put("type", recipe.type);
				jsonMap.put("pattern", recipe.pattern);
				jsonMap.put("key", recipe.key);

				String jsonContent = gson.toJson(jsonMap);
				Path fullFilePath = Paths.get(filePath);
				writeFile(fullFilePath, jsonContent);
				// Add this file's normalized path to the expected files list.
				expectedPaths.add(fullFilePath.toAbsolutePath().normalize());
				LOGGER.info("Generated recipe file: {}", fullFilePath);
			}
		}

		// For each recipe that appears in the base datapack, also generate an advancement file so it unlocks in-game.
		// We assume that if the recipe's datapacks include "base", then the advancement should be generated.
		createFolder(ADVANCEMENT_FOLDER);
		for (RecipeConfig recipe : config.recipes) {
			if (recipe.datapacks.contains("base")) {
				String advFilePath = ADVANCEMENT_FOLDER + recipe.name + ".json";
				Path advPath = Paths.get(advFilePath);
				// Build the advancement JSON.
				// This advancement uses "minecraft:recipes/root" as its parent.
				// It automatically unlocks when the recipe is unlocked.
				Map<String, Object> advMap = new LinkedHashMap<>();
				advMap.put("parent", "minecraft:recipes/root");

				Map<String, Object> criteriaMap = new LinkedHashMap<>();
				Map<String, Object> hasRecipeMap = new LinkedHashMap<>();
				hasRecipeMap.put("trigger", "minecraft:recipe_unlocked");
				Map<String, Object> conditionsMap = new LinkedHashMap<>();
				// The advancement refers to the recipe by its id.
				// Since the base recipe is written to "data/swrpgccrafting/recipes/<name>.json", the id is "swrpgccrafting:<name>"
				conditionsMap.put("recipe", MOD_ID + ":" + recipe.name);
				hasRecipeMap.put("conditions", conditionsMap);
				criteriaMap.put("has_recipe", hasRecipeMap);
				advMap.put("criteria", criteriaMap);

				Map<String, Object> rewardsMap = new LinkedHashMap<>();
				rewardsMap.put("recipes", Collections.singletonList(MOD_ID + ":" + recipe.name));
				advMap.put("rewards", rewardsMap);

				String advJson = gson.toJson(advMap);
				writeFile(advPath, advJson);
				expectedPaths.add(advPath.toAbsolutePath().normalize());
				LOGGER.info("Generated advancement for recipe: {}", recipe.name);
			}
		}

		// Write (or update) the pack.mcmeta file.
		String packMcmetaPath = DATAPACK_BASE + "/pack.mcmeta";
		Path packPath = Paths.get(packMcmetaPath);
		writeFile(packPath, config.pack_mcmeta);
		expectedPaths.add(packPath.toAbsolutePath().normalize());

		// Sync the datapack folder: remove any JSON file (except pack.mcmeta) that isn't expected.
		syncDatapackFiles(expectedPaths);

		LOGGER.info("Datapack generation, including advancements, is complete. Mod is ready!");
	}

	// ---------- Configuration Loading --------------

	/**
	 * Loads the config from CONFIG_FILE. If missing, creates a default configuration.
	 */
	private void loadOrCreateConfig() {
		Path configPath = Paths.get(CONFIG_FILE);
		if (!Files.exists(configPath)) {
			LOGGER.info("Config file not found. Creating default config at {}", CONFIG_FILE);
			Config defaultConfig = getDefaultConfig();
			try {
				Files.createDirectories(configPath.getParent());
				Files.write(configPath, gson.toJson(defaultConfig).getBytes());
				config = defaultConfig;
			} catch (IOException e) {
				LOGGER.error("Failed to create default config file!", e);
			}
		} else {
			try {
				String json = Files.readString(configPath);
				config = gson.fromJson(json, Config.class);
				LOGGER.info("Configuration loaded successfully.");
			} catch (IOException e) {
				LOGGER.error("Failed to read config file!", e);
			}
		}
	}

	// ---------- File Writing and Sync Methods --------------

	/**
	 * Writes content to the specified file, overwriting if it already exists.
	 */
	private void writeFile(Path filePath, String content) {
		try {
			Files.createDirectories(filePath.getParent());
			Files.writeString(filePath, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			LOGGER.info("Wrote file: {}", filePath);
		} catch (IOException e) {
			LOGGER.error("Error writing file: " + filePath, e);
		}
	}

	/**
	 * Recursively creates the specified folder (and parents) if it does not exist.
	 */
	private void createFolder(String folderPath) {
		try {
			Files.createDirectories(Paths.get(folderPath));
			LOGGER.info("Verified folder: {}", folderPath);
		} catch (IOException e) {
			LOGGER.error("Error creating folder: " + folderPath, e);
		}
	}

	/**
	 * Scans the DATAPACK_BASE folder recursively for .json files (excluding pack.mcmeta)
	 * and removes any that are not present in the expectedPaths set.
	 */
	private void syncDatapackFiles(Set<Path> expectedPaths) {
		try {
			Files.walk(Paths.get(DATAPACK_BASE))
					.filter(Files::isRegularFile)
					.filter(path -> path.toString().endsWith(".json"))
					.forEach(path -> {
						if (path.getFileName().toString().equals("pack.mcmeta")) {
							return;
						}
						Path normalized = path.toAbsolutePath().normalize();
						if (!expectedPaths.contains(normalized)) {
							try {
								Files.delete(path);
								LOGGER.info("Removed obsolete file: {}", path);
							} catch (IOException e) {
								LOGGER.error("Failed to remove file: " + path, e);
							}
						}
					});
		} catch (IOException e) {
			LOGGER.error("Error walking through datapack folder for sync", e);
		}
	}

	// ---------- Default Configuration --------------

	/**
	 * Returns a default configuration.
	 *
	 * Note: The default config includes two recipes (name_tag and saddle)
	 * and two datapack presets. The base preset now uses a namespaced folder so that recipes
	 * have IDs like "swrpgccrafting:<recipeName>" (which the advancement file references).
	 */
	private Config getDefaultConfig() {
		Config config = new Config();

		// Use the default pack.mcmeta content.
		config.pack_mcmeta = "{\n" +
				"    \"pack\": {\n" +
				"        \"pack_format\": 48,\n" +
				"        \"supported_formats\": {\n" +
				"            \"min_inclusive\": 48,\n" +
				"            \"max_inclusive\": 71\n" +
				"        },\n" +
				"        \"description\": \"SWRPGCrafting\\nSludgeEnt\"\n" +
				"    },\n" +
				"    \"overlays\": {\n" +
				"        \"entries\": [\n" +
				"            {\n" +
				"                \"formats\": {\n" +
				"                    \"min_inclusive\": 57,\n" +
				"                    \"max_inclusive\": 2147483647\n" +
				"                },\n" +
				"                \"directory\": \"overlay_57\"\n" +
				"            },\n" +
				"            {\n" +
				"                \"formats\": {\n" +
				"                    \"min_inclusive\": 61,\n" +
				"                    \"max_inclusive\": 2147483647\n" +
				"                },\n" +
				"                \"directory\": \"overlay_61\"\n" +
				"            }\n" +
				"        ]\n" +
				"    }\n" +
				"}";

		// Define two recipes.
		RecipeConfig nameTag = new RecipeConfig();
		nameTag.name = "name_tag";
		nameTag.type = "crafting_shaped";
		nameTag.pattern = new String[]{" IS", " PI", "P  "};
		nameTag.key = new LinkedHashMap<>();
		nameTag.key.put("I", "minecraft:iron_ingot");
		nameTag.key.put("P", "minecraft:paper");
		nameTag.key.put("S", "minecraft:string");
		nameTag.result = new ResultConfig("minecraft:name_tag", 1);
		nameTag.datapacks = Arrays.asList("base", "overlay_57");

		RecipeConfig saddle = new RecipeConfig();
		saddle.name = "saddle";
		saddle.type = "crafting_shaped";
		saddle.pattern = new String[]{"LI ", "L S", " I "};
		saddle.key = new LinkedHashMap<>();
		saddle.key.put("L", "minecraft:leather");
		saddle.key.put("I", "minecraft:iron_ingot");
		saddle.key.put("S", "minecraft:string");
		saddle.result = new ResultConfig("minecraft:saddle", 1);
		saddle.datapacks = Arrays.asList("base", "overlay_57");

		config.recipes = Arrays.asList(nameTag, saddle);

		// Datapack presets.
		// The base preset now places recipes in a namespaced folder so that their IDs become "swrpgccrafting:<name>".
		Map<String, DatapackPreset> presets = new LinkedHashMap<>();
		DatapackPreset basePreset = new DatapackPreset();
		basePreset.directory = ""; // base datapack; no extra folder.
		basePreset.recipePathPrefix = "data/" + MOD_ID + "/recipes/";  // e.g., data/swrpgccrafting/recipes/
		presets.put("base", basePreset);

		DatapackPreset overlay57 = new DatapackPreset();
		overlay57.directory = "overlay_57";
		// You can customize this; for example, leave it as originally defined.
		overlay57.recipePathPrefix = "data/craftable_name_tags/recipe/";
		presets.put("overlay_57", overlay57);

		config.datapackPresets = presets;
		return config;
	}

	// ---------- Configuration POJOs --------------

	private static class Config {
		@SerializedName("pack_mcmeta")
		String pack_mcmeta;
		@SerializedName("recipes")
		List<RecipeConfig> recipes;
		@SerializedName("datapackPresets")
		Map<String, DatapackPreset> datapackPresets;
	}

	private static class RecipeConfig {
		// The recipe file name (without extension)
		String name;
		// Recipe type (e.g., "crafting_shaped")
		String type;
		// Crafting pattern (an array of strings representing rows in the crafting grid)
		String[] pattern;
		// Map for the key (e.g., "I" → "minecraft:iron_ingot")
		Map<String, String> key;
		// The result of the recipe.
		ResultConfig result;
		// List of datapack preset keys where this recipe should be generated.
		List<String> datapacks;
	}

	private static class ResultConfig {
		String id;
		int count;

		ResultConfig(String id, int count) {
			this.id = id;
			this.count = count;
		}
	}

	private static class DatapackPreset {
		// For overlays, the directory (if empty, then the base datapack is used).
		String directory;
		// The prefix for where to output the recipe file.
		String recipePathPrefix;
	}
}
