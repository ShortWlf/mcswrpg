package comswrpgservauth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Swrpgservauth implements ModInitializer {
	public static final String MOD_ID = "swrpgservauth";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final String CONFIG_FILE = "config/allowed_players.json";
	private final Set<String> allowedPlayers = new HashSet<>();
	private static final String DEFAULT_USERNAME = "ShortWlf";
	private static final List<String> DEFAULT_PLAYERS = List.of(DEFAULT_USERNAME, "ExampleUser1", "AnotherPlayer");

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Simple Username Auth Mod!");
		loadConfig();

		// Use the JOIN event to manage connections.
		// Instead of disconnecting immediately—which may occur before all connection processes finish—
		// we schedule a task for the next tick to fully disconnect unauthorized players,
		// then remove them from the player manager.
		ServerPlayConnectionEvents.JOIN.register((handler, additions, server) -> {
			ServerPlayerEntity player = handler.getPlayer();
			String username = player.getName().getString();
			if (!allowedPlayers.contains(username)) {
				LOGGER.warn("Unauthorized player '{}' attempted to join.", username);
				server.execute(() -> {
					// Double-check that the player is still present.
					if (!player.isRemoved()) {
						player.networkHandler.disconnect(
								Text.literal("You are not authorized to join this server.").formatted(Formatting.RED)
						);
						// Explicitly remove the player from the player manager to try and avoid ghosting.
						server.getPlayerManager().remove(player);
						LOGGER.warn("Unauthorized player '{}' has been disconnected and removed.", username);
					}
				});
			} else {
				LOGGER.info("Player '{}' joined and is authorized.", username);
				player.sendMessage(Text.literal("Welcome!").formatted(Formatting.GREEN));
			}
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
	}

	public void loadConfig() {
		File configFile = new File(CONFIG_FILE);
		if (configFile.exists()) {
			try (FileReader reader = new FileReader(configFile)) {
				Type type = new TypeToken<List<String>>() {}.getType();
				List<String> loadedPlayers = GSON.fromJson(reader, type);
				if (loadedPlayers != null && !loadedPlayers.isEmpty()) {
					this.allowedPlayers.addAll(loadedPlayers);
					LOGGER.info("Loaded allowed player list from '{}'.", CONFIG_FILE);
				} else {
					this.allowedPlayers.addAll(DEFAULT_PLAYERS);
					saveConfig();
					LOGGER.info("Allowed player list in '{}' was empty, wrote default entries.", CONFIG_FILE);
				}
			} catch (IOException e) {
				LOGGER.error("Error loading allowed player list from '{}': {}", CONFIG_FILE, e.getMessage());
				this.allowedPlayers.addAll(DEFAULT_PLAYERS);
				saveConfig();
			}
		} else {
			this.allowedPlayers.addAll(DEFAULT_PLAYERS);
			saveConfig();
			LOGGER.info("Created default allowed player list in '{}'.", CONFIG_FILE);
		}
	}

	public void saveConfig() {
		List<String> playersToSave = new ArrayList<>(this.allowedPlayers);
		File configDir = new File("config");
		if (!configDir.exists()) {
			configDir.mkdirs(); // The result is ignored intentionally.
		}
		try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
			GSON.toJson(playersToSave, writer);
			LOGGER.info("Saved allowed player list to '{}'.", CONFIG_FILE);
		} catch (IOException e) {
			LOGGER.error("Error saving allowed player list to '{}': {}", CONFIG_FILE, e.getMessage());
		}
	}

	public void addAllowedPlayer(String username) {
		if (allowedPlayers.add(username)) {
			LOGGER.info("Added '{}' to the authorized player list.", username);
			saveConfig();
		} else {
			LOGGER.warn("'{}' is already in the authorized player list.", username);
		}
	}

	public void removeAllowedPlayer(String username) {
		if (allowedPlayers.remove(username)) {
			LOGGER.info("Removed '{}' from the authorized player list.", username);
			saveConfig();
		} else {
			LOGGER.warn("'{}' was not found in the authorized player list.", username);
		}
	}

	public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("addUser")
				.requires(source -> source.hasPermissionLevel(4))
				.then(CommandManager.argument("username", StringArgumentType.string())
						.executes(context -> {
							String username = StringArgumentType.getString(context, "username");
							addAllowedPlayer(username);
							context.getSource().sendFeedback(() ->
									Text.literal("Added user: " + username).formatted(Formatting.GREEN), false);
							return 1;
						})
				)
		);

		dispatcher.register(CommandManager.literal("removeUser")
				.requires(source -> source.hasPermissionLevel(4))
				.then(CommandManager.argument("username", StringArgumentType.string())
						.executes(context -> {
							String username = StringArgumentType.getString(context, "username");
							removeAllowedPlayer(username);
							context.getSource().sendFeedback(() ->
									Text.literal("Removed user: " + username).formatted(Formatting.RED), false);
							return 1;
						})
				)
		);

		dispatcher.register(CommandManager.literal("listUsers")
				.requires(source -> source.hasPermissionLevel(4))
				.executes(context -> {
					String users = String.join(", ", allowedPlayers);
					context.getSource().sendFeedback(() ->
							Text.literal("Allowed users: " + users).formatted(Formatting.YELLOW), false);
					return 1;
				})
		);

		dispatcher.register(CommandManager.literal("refreshConfig")
				.requires(source -> source.hasPermissionLevel(4))
				.executes(context -> {
					loadConfig();
					context.getSource().sendFeedback(() ->
							Text.literal("Configuration refreshed successfully.").formatted(Formatting.AQUA), false);
					return 1;
				})
		);
	}
}
