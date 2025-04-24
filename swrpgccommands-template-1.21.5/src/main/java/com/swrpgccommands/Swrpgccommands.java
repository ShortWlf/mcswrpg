package com.swrpgccommands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class Swrpgccommands implements ModInitializer {
	private static final String CONFIG_PATH = "config/swrpgccommands.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Logger LOGGER = LogManager.getLogger("SWRPGCCommands");

	// List to store registered custom command nodes for refresh/reload purposes
	private static final List<CommandNode<ServerCommandSource>> customNodes = new ArrayList<>();

	@Override
	public void onInitialize() {
		LOGGER.info("[SWRPGCCommands] Mod Initialized!");

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			// Static command available for all.
			dispatcher.register(CommandManager.literal("greet")
					.executes(context -> {
						ServerCommandSource source = context.getSource();
						source.sendFeedback(() -> Text.of("Hello from SWRPGCCommands!"), false);
						return 1;
					}));

			// New /coords command to show the player's coordinates (accessible by anyone)
			dispatcher.register(CommandManager.literal("coords")
					.executes(context -> {
						ServerCommandSource source = context.getSource();
						try {
							var player = source.getPlayer();
							double x = player.getX();
							double y = player.getY();
							double z = player.getZ();
							source.sendFeedback(() -> Text.of(String.format("You are at: X: %.1f, Y: %.1f, Z: %.1f", x, y, z)), false);
						} catch (Exception e) {
							source.sendFeedback(() -> Text.of("This command can only be used by players."), false);
						}
						return 1;
					}));

			// Refresh command to reload custom commands live (restricted to ops).
			dispatcher.register(CommandManager.literal("swrrefresh")
					.requires(source -> source.hasPermissionLevel(2))
					.executes(context -> {
						ServerCommandSource source = context.getSource();
						refreshCustomCommands(source);
						source.sendFeedback(() -> Text.of("Custom commands reloaded!"), false);
						return 1;
					}));

			// Load custom commands from configuration.
			loadCommands(dispatcher);
		});
	}

	/**
	 * Loads custom commands from the config file and registers them.
	 * Each command's configuration includes the "requiresOp" flag.
	 */
	private void loadCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		File configFile = new File(CONFIG_PATH);
		if (!configFile.exists()) {
			LOGGER.info("[SWRPGCCommands] Config file not found. Creating default config.");
			createDefaultConfig(configFile);
		}
		try (FileReader reader = new FileReader(configFile)) {
			Type listType = new TypeToken<List<CustomCommand>>() {}.getType();
			List<CustomCommand> commands = GSON.fromJson(reader, listType);

			for (CustomCommand command : commands) {
				// Build the literal for this custom command.
				var literal = CommandManager.literal(command.name());
				// If the command requires op, add a permission check.
				if (command.requiresOp()) {
					literal.requires(source -> source.hasPermissionLevel(2));
				}
				// Set the command execution.
				literal.executes(context -> {
					ServerCommandSource source = context.getSource();
					CommandDispatcher<ServerCommandSource> cmdDispatcher =
							source.getServer().getCommandManager().getDispatcher();
					String actionCommand = command.action();
					if (!command.requiresOp()) {
						// For open commands, replace "@p" with the executing player's name.
						try {
							var player = source.getPlayer();
							actionCommand = actionCommand.replace("@p", player.getName().getString());
						} catch (Exception ignore) {
							// If the source isn't a player, skip replacement.
						}
						// Execute using the server's command source (console) to bypass vanilla op checks.
						cmdDispatcher.execute(
								cmdDispatcher.parse(actionCommand, source.getServer().getCommandSource())
						);
					} else {
						// For op-only commands, execute using the player's own source.
						cmdDispatcher.execute(
								cmdDispatcher.parse(actionCommand, source)
						);
					}
					return 1;
				});
				// Register and store the node for refresh.
				CommandNode<ServerCommandSource> node = dispatcher.register(literal);
				customNodes.add(node);
			}
			LOGGER.info("[SWRPGCCommands] Loaded {} custom commands from config.", commands.size());
		} catch (Exception e) {
			LOGGER.error("[SWRPGCCommands] Error loading commands from config:", e);
		}
	}

	/**
	 * Removes all custom command nodes and reloads them from the configuration.
	 */
	private void refreshCustomCommands(ServerCommandSource source) {
		CommandDispatcher<ServerCommandSource> dispatcher =
				source.getServer().getCommandManager().getDispatcher();
		// Remove all nodes that we registered.
		dispatcher.getRoot().getChildren().removeIf(customNodes::contains);
		customNodes.clear();
		// Load the commands fresh from the config file.
		loadCommands(dispatcher);
	}

	/**
	 * Creates a default configuration file for custom commands.
	 */
	private void createDefaultConfig(File configFile) {
		try {
			if (!configFile.getParentFile().mkdirs() && !configFile.getParentFile().exists()) {
				LOGGER.error("[SWRPGCCommands] Failed to create config directory!");
				return;
			}
			List<CustomCommand> defaultCommands = new ArrayList<>();
			defaultCommands.add(new CustomCommand("spawn", "tp @p 100 64 -200", false));
			defaultCommands.add(new CustomCommand("greet", "say Hello, everyone!", false));
			// Example op-only command:
			defaultCommands.add(new CustomCommand("clearweather", "weather clear", true));
			try (FileWriter writer = new FileWriter(configFile)) {
				GSON.toJson(defaultCommands, writer);
				LOGGER.info("[SWRPGCCommands] Default config created at {}", CONFIG_PATH);
			}
		} catch (IOException e) {
			LOGGER.error("[SWRPGCCommands] Error creating default config:", e);
		}
	}

	// Custom command record. The "requiresOp" field determines if the command is op-restricted.
	public record CustomCommand(String name, String action, boolean requiresOp) {}
}
