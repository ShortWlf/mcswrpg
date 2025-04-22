package com.swadminpw;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Swadminpw implements ModInitializer {
	public static final String MOD_ID = "swadminpw";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Map tracking players waiting for authentication (keyed by their UUID and join timestamp).
	public static final Map<UUID, Long> pendingAuth = new ConcurrentHashMap<>();

	/**
	 * Configuration for authentication.
	 * Although we no longer teleport the player, we still load:
	 * - The authentication timeout (in milliseconds)
	 * - A mapping of usernames to passwords.
	 */
	public static class SwadminpwConfig {
		public static double authLobbyX = 0.0;
		public static double authLobbyY = 100.0;
		public static double authLobbyZ = 0.0;
		public static long authTimeoutMs = 60000;
		public static Map<String, String> users = new HashMap<>();

		private static final Path CONFIG_PATH = Paths.get("config", MOD_ID + ".json");
		private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

		public static void loadConfig() {
			try {
				if (Files.notExists(CONFIG_PATH)) {
					// Create default configuration.
					JsonObject defaultConfig = new JsonObject();
					defaultConfig.addProperty("authLobbyX", authLobbyX);
					defaultConfig.addProperty("authLobbyY", authLobbyY);
					defaultConfig.addProperty("authLobbyZ", authLobbyZ);
					defaultConfig.addProperty("authTimeoutMs", authTimeoutMs);

					// Example: only "admin" requires authentication.
					JsonObject defaultUsers = new JsonObject();
					defaultUsers.addProperty("admin", "password123");
					defaultConfig.add("users", defaultUsers);

					Files.createDirectories(CONFIG_PATH.getParent());
					Files.write(CONFIG_PATH, GSON.toJson(defaultConfig).getBytes(StandardCharsets.UTF_8));
					LOGGER.info("Default config created at {}", CONFIG_PATH.toAbsolutePath());

					authLobbyX = defaultConfig.get("authLobbyX").getAsDouble();
					authLobbyY = defaultConfig.get("authLobbyY").getAsDouble();
					authLobbyZ = defaultConfig.get("authLobbyZ").getAsDouble();
					authTimeoutMs = defaultConfig.get("authTimeoutMs").getAsLong();
					users.clear();
					for (String key : defaultUsers.keySet()) {
						users.put(key, defaultUsers.get(key).getAsString());
					}
				} else {
					// Load the existing config.
					String jsonStr = new String(Files.readAllBytes(CONFIG_PATH), StandardCharsets.UTF_8);
					JsonObject jsonConfig = GSON.fromJson(jsonStr, JsonObject.class);
					authLobbyX = jsonConfig.get("authLobbyX").getAsDouble();
					authLobbyY = jsonConfig.get("authLobbyY").getAsDouble();
					authLobbyZ = jsonConfig.get("authLobbyZ").getAsDouble();
					authTimeoutMs = jsonConfig.get("authTimeoutMs").getAsLong();

					users.clear();
					JsonObject jsonUsers = jsonConfig.getAsJsonObject("users");
					for (String key : jsonUsers.keySet()) {
						users.put(key, jsonUsers.get(key).getAsString());
					}
					LOGGER.info("Config loaded from {}", CONFIG_PATH.toAbsolutePath());
				}
			} catch (IOException e) {
				LOGGER.error("Error loading config: {}", e.getMessage());
			}
		}
	}

	/**
	 * Allowed players configuration.
	 * The allowed players list is loaded from config/allowed_players.json.
	 * If the file is missing, it is created with default values.
	 */
	private static final Path ALLOWED_CONFIG_PATH = Paths.get("config", "allowed_players.json");
	private static final Gson ALLOWED_GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Set<String> allowedPlayers = new HashSet<>();
	private static final Set<String> DEFAULT_ALLOWED_PLAYERS =
			Set.of("ShortWlf", "ExampleUser1", "AnotherPlayer");

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Swadminpw Mod...");

		// Load allowed players from configuration.
		loadAllowedPlayers();

		// Load authentication configuration.
		SwadminpwConfig.loadConfig();

		// Register the /auth command.
		AuthCommand.register();

		// Register the JOIN event.
		// Note: The first parameter is of type ServerPlayNetworkHandler.
		ServerPlayConnectionEvents.JOIN.register(
				(ServerPlayNetworkHandler handler, PacketSender sender, MinecraftServer server) -> {
					ServerPlayerEntity player = handler.getPlayer();
					String username = player.getName().getString();
					if (!allowedPlayers.contains(username)) {
						player.networkHandler.disconnect(
								Text.literal("You are not authorized to join this server.")
										.formatted(Formatting.RED)
						);
						LOGGER.warn("Unauthorized player '{}' tried to join.", username);
						return;
					}
					LOGGER.info("Player '{}' is allowed to join.", username);

					// If authentication is required (username exists in the users mapping),
					// send a message and add the player to the pendingAuth map.
					if (SwadminpwConfig.users.containsKey(username)) {
						player.sendMessage(
								Text.literal("Please verify using /auth <password> within " +
										(SwadminpwConfig.authTimeoutMs / 1000) + " seconds."),
								false
						);
						pendingAuth.put(player.getUuid(), System.currentTimeMillis());
					} else {
						// Otherwise, simply welcome the player.player.sendMessage(Text.literal("").formatted(Formatting.GREEN), false);
					}
				}
		);

		// Register an event on each server tick to enforce the authentication timeout.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			long currentTime = System.currentTimeMillis();
			Iterator<Map.Entry<UUID, Long>> iterator = pendingAuth.entrySet().iterator();
			while (iterator.hasNext()) {
				Map.Entry<UUID, Long> entry = iterator.next();
				if (currentTime - entry.getValue() > SwadminpwConfig.authTimeoutMs) {
					ServerPlayerEntity p = server.getPlayerManager().getPlayer(entry.getKey());
					if (p != null) {
						p.networkHandler.disconnect(Text.literal("Timed out verifying credentials."));
					}
					iterator.remove();
				}
			}
		});
	}

	// Loads the allowed players list from allowed_players.json (or writes defaults if missing).
	private static void loadAllowedPlayers() {
		File configFile = ALLOWED_CONFIG_PATH.toFile();
		if (configFile.exists()) {
			try (FileReader reader = new FileReader(configFile)) {
				Type type = new TypeToken<Set<String>>() {}.getType();
				Set<String> loaded = ALLOWED_GSON.fromJson(reader, type);
				if (loaded != null && !loaded.isEmpty()) {
					allowedPlayers.addAll(loaded);
					LOGGER.info("Loaded allowed players from '{}'.", ALLOWED_CONFIG_PATH.toAbsolutePath());
				} else {
					allowedPlayers.addAll(DEFAULT_ALLOWED_PLAYERS);
					saveAllowedPlayers();
					LOGGER.info("Allowed players list was empty. Wrote default entries.");
				}
			} catch (IOException e) {
				LOGGER.error("Error loading allowed players: {}", e.getMessage());
				allowedPlayers.addAll(DEFAULT_ALLOWED_PLAYERS);
				saveAllowedPlayers();
			}
		} else {
			allowedPlayers.addAll(DEFAULT_ALLOWED_PLAYERS);
			saveAllowedPlayers();
			LOGGER.info("Created default allowed players config at '{}'.", ALLOWED_CONFIG_PATH.toAbsolutePath());
		}
	}

	// Saves the allowed players list to allowed_players.json.
	private static void saveAllowedPlayers() {
		try {
			Files.createDirectories(ALLOWED_CONFIG_PATH.getParent());
			try (FileWriter writer = new FileWriter(ALLOWED_CONFIG_PATH.toFile())) {
				ALLOWED_GSON.toJson(allowedPlayers, writer);
			}
			LOGGER.info("Saved allowed players list to '{}'.", ALLOWED_CONFIG_PATH.toAbsolutePath());
		} catch (IOException e) {
			LOGGER.error("Error saving allowed players: {}", e.getMessage());
		}
	}

	/**
	 * The /auth command validates a player's password.
	 * If the provided password is correct, the player is notified and removed
	 * from the pending authentication map.
	 */
	public static class AuthCommand {
		public static void register() {
			CommandRegistrationCallback.EVENT.register((CommandDispatcher<ServerCommandSource> dispatcher, boolean dedicated) -> {
				registerAuthCommand(dispatcher);
			});
		}

		private static void registerAuthCommand(CommandDispatcher<ServerCommandSource> dispatcher) {
			dispatcher.register(
					CommandManager.literal("auth")
							.then(CommandManager.argument("password", StringArgumentType.string())
									.executes(context -> {
										ServerCommandSource source = context.getSource();
										ServerPlayerEntity player = source.getPlayerOrThrow();
										String password = StringArgumentType.getString(context, "password");
										String username = player.getName().getString();
										String expectedPassword = SwadminpwConfig.users.get(username);

										if (expectedPassword != null && expectedPassword.equals(password)) {
											player.sendMessage(Text.literal("Authentication successful!"), false);
											pendingAuth.remove(player.getUuid());
										} else {
											player.sendMessage(Text.literal("Invalid password. Please try again."), false);
										}
										return 1;
									})
							)
			);
		}
	}
}
