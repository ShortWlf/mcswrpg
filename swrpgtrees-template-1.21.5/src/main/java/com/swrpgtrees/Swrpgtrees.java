package com.swrpgtrees;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.Registries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

public class Swrpgtrees implements ModInitializer {
	public static final String MOD_ID = "swrpgtrees";
	private static final Logger LOGGER = LogManager.getLogger();

	// Sets of valid log and leaf blocks loaded from configuration.
	private final Set<Block> logBlocks = new HashSet<>();
	private final Set<Block> leafBlocks = new HashSet<>();

	// Tag to prevent duplicate processing of a tree.
	private static final Set<BlockPos> processingTrees = new HashSet<>();

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing mod: " + MOD_ID);

		ensureConfigFile();
		loadConfig();

		// Register our block-break event.
		// The tree-felling mechanic triggers only if:
		//   - The call is server-side.
		//   - The player is sneaking.
		//   - The player is using an axe in their main hand.
		//   - The broken block is a registered log that appears to be at the bottom.
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			if (!world.isClient && player.isSneaking()) {
				// Ensure the player is holding an axe.
				if (!(player.getMainHandStack().getItem() instanceof AxeItem)) {
					return; // Not using an axe, so exit.
				}
				Block block = state.getBlock();
				if (logBlocks.contains(block)) {
					// A block is considered the bottom log if it's at Y==0 or the block below isn't a valid log.
					if (pos.getY() == 0 || !logBlocks.contains(world.getBlockState(pos.down()).getBlock())) {
						if (processingTrees.contains(pos)) {
							return; // Already processing this tree.
						}
						processingTrees.add(pos);
						LOGGER.info("Bottom log at " + pos + " detected while sneaking with an axe. Triggering instant tree fall.");
						// Cast the world to ServerWorld and trigger the instant tree fall.
						fallTreeInstantly((ServerWorld) world, pos);
						processingTrees.remove(pos);
					}
				}
			}
		});
	}

	// ---------------- CONFIGURATION HANDLING ----------------

	// Create a default config file if one does not exist.
	private void ensureConfigFile() {
		try {
			String configPath = "config/swrpgtrees.json";
			if (!Files.exists(Paths.get(configPath))) {
				LOGGER.info("Config file not found. Creating default config...");
				JsonObject defaultConfig = new JsonObject();

				// Default log block identifiers.
				JsonArray logs = new JsonArray();
				logs.add(new JsonPrimitive("minecraft:oak_log"));
				logs.add(new JsonPrimitive("minecraft:spruce_log"));
				logs.add(new JsonPrimitive("minecraft:birch_log"));
				logs.add(new JsonPrimitive("minecraft:jungle_log"));
				logs.add(new JsonPrimitive("minecraft:acacia_log"));
				logs.add(new JsonPrimitive("minecraft:dark_oak_log"));
				defaultConfig.add("logs", logs);

				// Default leaf block identifiers.
				JsonArray leaves = new JsonArray();
				leaves.add(new JsonPrimitive("minecraft:oak_leaves"));
				leaves.add(new JsonPrimitive("minecraft:spruce_leaves"));
				leaves.add(new JsonPrimitive("minecraft:birch_leaves"));
				leaves.add(new JsonPrimitive("minecraft:jungle_leaves"));
				leaves.add(new JsonPrimitive("minecraft:acacia_leaves"));
				leaves.add(new JsonPrimitive("minecraft:dark_oak_leaves"));
				defaultConfig.add("leaves", leaves);

				Files.createDirectories(Paths.get("config"));
				try (FileWriter writer = new FileWriter(configPath)) {
					writer.write(defaultConfig.toString());
				}
				LOGGER.info("Default config file created successfully.");
			}
		} catch (IOException e) {
			LOGGER.error("Failed to create config file", e);
		}
	}

	// Load the configuration and populate logBlocks and leafBlocks.
	private void loadConfig() {
		try (FileReader reader = new FileReader("config/swrpgtrees.json")) {
			JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();

			if (jsonObject.has("logs")) {
				JsonArray logs = jsonObject.getAsJsonArray("logs");
				for (JsonElement element : logs) {
					if (element.isJsonPrimitive()) {
						String blockId = element.getAsJsonPrimitive().getAsString();
						Identifier id = Identifier.tryParse(blockId);
						if (id == null) {
							LOGGER.warn("Invalid log identifier format for: " + blockId);
							continue;
						}
						Block block = Registries.BLOCK.get(id);
						if (block != Blocks.AIR) {
							logBlocks.add(block);
						}
					} else {
						LOGGER.warn("Expected string value in logs array.");
					}
				}
			}

			if (jsonObject.has("leaves")) {
				JsonArray leaves = jsonObject.getAsJsonArray("leaves");
				for (JsonElement element : leaves) {
					if (element.isJsonPrimitive()) {
						String blockId = element.getAsJsonPrimitive().getAsString();
						Identifier id = Identifier.tryParse(blockId);
						if (id == null) {
							LOGGER.warn("Invalid leaf identifier format for: " + blockId);
							continue;
						}
						Block block = Registries.BLOCK.get(id);
						if (block != Blocks.AIR) {
							leafBlocks.add(block);
						}
					} else {
						LOGGER.warn("Expected string value in leaves array.");
					}
				}
			}
			LOGGER.info("Loaded log blocks: " + logBlocks);
			LOGGER.info("Loaded leaf blocks: " + leafBlocks);
		} catch (IOException e) {
			LOGGER.error("Failed to load configuration file", e);
		}
	}

	// ---------------- TREE FELLING LOGIC ----------------

	/**
	 * Instantly scans a fixed bounding box around the bottom log and breaks every block in that region
	 * that is registered as a log or leaf.
	 */
	private void fallTreeInstantly(ServerWorld world, BlockPos bottom) {
		final int horizontalRadius = 4;  // Adjust as needed for your tree sizes.
		final int verticalHeight = 12;     // Adjust based on typical tree height.

		int minX = bottom.getX() - horizontalRadius;
		int maxX = bottom.getX() + horizontalRadius;
		int minY = bottom.getY();
		int maxY = bottom.getY() + verticalHeight;
		int minZ = bottom.getZ() - horizontalRadius;
		int maxZ = bottom.getZ() + horizontalRadius;

		// Scan the defined bounding box and break blocks that are logs or leaves.
		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					BlockPos pos = new BlockPos(x, y, z);
					Block current = world.getBlockState(pos).getBlock();
					if (logBlocks.contains(current) || leafBlocks.contains(current)) {
						world.breakBlock(pos, true);
					}
				}
			}
		}
	}
}
