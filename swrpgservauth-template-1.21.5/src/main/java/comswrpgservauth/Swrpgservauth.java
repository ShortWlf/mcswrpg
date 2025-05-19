package comswrpgservauth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.MinecraftServer;
// Import the ban entry type from your server API:
import net.minecraft.server.BannedPlayerEntry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Swrpgservauth implements ModInitializer {

	public static final String MOD_ID = "swrpgservauth";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Global flag: when true, if a player's per-user flag is true then IP verification is enforced.
	private boolean requireIpValidation = true;
	// Config options for unauthorized access handling.
	private boolean banOnUnauthorized = true;
	private boolean kickOnUnauthorized = true;

	// Path to the configuration file.
	private static final String CONFIG_FILE = "config/allowed_players.json";

	// Allowed players stored in a map mapping username to AllowedPlayer.
	private final Map<String, AllowedPlayer> allowedPlayers = new HashMap<>();

	// Default users added first time.
	private static final List<String> DEFAULT_PLAYERS = List.of("ShortWlf", "ExampleUser1", "AnotherPlayer");

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	// Class for allowed player's information.
	public static class AllowedPlayer {
		public boolean verifyIp = false;  // When true, enforce IP matching for this player's connections.
		public List<String> ips = new ArrayList<>();
	}

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Simple Username Auth Mod with per-user IP verification (using reflection)!");
		loadConfig();

		// Register the join event listener.
		ServerPlayConnectionEvents.JOIN.register((handler, additions, server) -> {
			ServerPlayerEntity player = handler.getPlayer();
			String username = player.getName().getString();
			AllowedPlayer entry = allowedPlayers.get(username);

			if (entry == null) {
				LOGGER.warn("Unauthorized player '{}' attempted to join.", username);
				handleUnauthorized(player, "You are not authorized to join this server.", server);
				return;
			}

			// If global validation is enabled and the user is flagged for IP verification, perform the check.
			if (requireIpValidation && entry.verifyIp) {
				if (entry.ips.isEmpty()) {
					LOGGER.warn("User '{}' flagged for IP verification but has no allowed IP set.", username);
					handleUnauthorized(player, "No authorized IP set for your account.", server);
					return;
				}
				SocketAddress socketAddress = getConnectionAddress(handler);
				final String ipAddress;
				if (socketAddress instanceof InetSocketAddress) {
					ipAddress = ((InetSocketAddress) socketAddress).getAddress().getHostAddress();
				} else {
					ipAddress = "";
				}
				LOGGER.debug("User '{}' is connecting from IP: '{}'", username, ipAddress);
				if (!entry.ips.contains(ipAddress)) {
					LOGGER.warn("Unauthorized IP '{}' for user '{}'.", ipAddress, username);
					handleUnauthorized(player, "Your IP address is not authorized for your account.", server);
					return;
				}
			}
			LOGGER.info("Player '{}' joined and is authorized.", username);
			player.sendMessage(Text.literal("Welcome!").formatted(Formatting.GREEN));
		});

		// Register commands.
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
	}

	/**
	 * Handles unauthorized access by kicking, banning, or both—depending on the configuration.
	 *
	 * @param player The player that attempted to join.
	 * @param reason The reason provided for unauthorized access.
	 * @param server The Minecraft server instance.
	 */
	private void handleUnauthorized(ServerPlayerEntity player, String reason, MinecraftServer server) {
		server.execute(() -> {
			if (kickOnUnauthorized && !player.isRemoved()) {
				player.networkHandler.disconnect(Text.literal(reason).formatted(Formatting.RED));
			}
			if (banOnUnauthorized) {
				try {
					// Wrap the player's GameProfile in a proper ban entry.
					BannedPlayerEntry banEntry = new BannedPlayerEntry(
							player.getGameProfile(),
							null,        // Ban date (null can default to the current time)
							MOD_ID,      // Source
							null,        // Expiration date; null means permanent
							reason       // Reason
					);
					server.getPlayerManager().getUserBanList().add(banEntry);
				} catch (Exception e) {
					LOGGER.error("Failed to ban player '{}': {}", player.getGameProfile().getName(), e.getMessage());
				}
			}
			if (kickOnUnauthorized) {
				server.getPlayerManager().remove(player);
			}
			LOGGER.warn("Player '{}' has been handled as unauthorized. Reason: {}", player.getGameProfile().getName(), reason);
		});
	}

	/**
	 * Traverses the ServerPlayNetworkHandler class hierarchy to obtain the ClientConnection's address.
	 *
	 * @param handler The ServerPlayNetworkHandler instance.
	 * @return the SocketAddress of the connection.
	 */
	private SocketAddress getConnectionAddress(ServerPlayNetworkHandler handler) {
		Class<?> clazz = handler.getClass();
		while (clazz != null) {
			for (Field field : clazz.getDeclaredFields()) {
				if (ClientConnection.class.isAssignableFrom(field.getType())) {
					field.setAccessible(true);
					try {
						ClientConnection connection = (ClientConnection) field.get(handler);
						if (connection != null) {
							return connection.getAddress();
						}
					} catch (IllegalAccessException e) {
						LOGGER.error("IllegalAccessException while retrieving connection: ", e);
					}
				}
			}
			clazz = clazz.getSuperclass();
		}
		LOGGER.error("Failed to retrieve connection via reflection for handler: {}", handler.getClass().getName());
		return null;
	}

	/**
	 * Loads the configuration from the JSON file.
	 *
	 * Expected JSON format:
	 * {
	 *   "requireIpValidation": true,
	 *   "banOnUnauthorized": true,
	 *   "kickOnUnauthorized": true,
	 *   "allowedPlayers": {
	 *     "Username": {
	 *       "verifyIp": true,
	 *       "ips": ["ip1", "ip2"]
	 *     },
	 *     ...
	 *   }
	 * }
	 */
	public void loadConfig() {
		File configFile = new File(CONFIG_FILE);
		if (configFile.exists()) {
			try (FileReader reader = new FileReader(configFile)) {
				JsonObject json = GSON.fromJson(reader, JsonObject.class);
				requireIpValidation = json.has("requireIpValidation") && json.get("requireIpValidation").getAsBoolean();
				banOnUnauthorized = json.has("banOnUnauthorized") ? json.get("banOnUnauthorized").getAsBoolean() : true;
				kickOnUnauthorized = json.has("kickOnUnauthorized") ? json.get("kickOnUnauthorized").getAsBoolean() : true;

				JsonObject playersObject = json.getAsJsonObject("allowedPlayers");
				if (playersObject != null && !playersObject.entrySet().isEmpty()) {
					allowedPlayers.clear();
					for (Map.Entry<String, JsonElement> entry : playersObject.entrySet()) {
						AllowedPlayer ap = new AllowedPlayer();
						JsonObject playerObj = entry.getValue().getAsJsonObject();
						if (playerObj.has("verifyIp")) {
							ap.verifyIp = playerObj.get("verifyIp").getAsBoolean();
						}
						if (playerObj.has("ips")) {
							JsonArray arr = playerObj.getAsJsonArray("ips");
							for (JsonElement el : arr) {
								ap.ips.add(el.getAsString());
							}
						}
						allowedPlayers.put(entry.getKey(), ap);
					}
					LOGGER.info("Loaded allowed player list from '{}'.", CONFIG_FILE);
				} else {
					setDefaultConfig();
				}
			} catch (IOException e) {
				LOGGER.error("Error loading allowed player list from '{}': {}", CONFIG_FILE, e.getMessage());
				setDefaultConfig();
			}
		} else {
			setDefaultConfig();
		}
	}

	/**
	 * Creates a default configuration, initializing default users with IP verification enabled.
	 */
	private void setDefaultConfig() {
		requireIpValidation = true;
		banOnUnauthorized = true;
		kickOnUnauthorized = true;
		allowedPlayers.clear();
		for (String user : DEFAULT_PLAYERS) {
			AllowedPlayer ap = new AllowedPlayer();
			ap.verifyIp = requireIpValidation;
			allowedPlayers.put(user, ap);
		}
		saveConfig();
		LOGGER.info("Created default allowed player list in '{}'.", CONFIG_FILE);
	}

	/**
	 * Saves the current configuration to the JSON file.
	 */
	public void saveConfig() {
		File configDir = new File("config");
		if (!configDir.exists()) {
			configDir.mkdirs();
		}
		JsonObject json = new JsonObject();
		json.addProperty("requireIpValidation", requireIpValidation);
		json.addProperty("banOnUnauthorized", banOnUnauthorized);
		json.addProperty("kickOnUnauthorized", kickOnUnauthorized);
		JsonObject playersObject = new JsonObject();
		for (Map.Entry<String, AllowedPlayer> entry : allowedPlayers.entrySet()) {
			JsonObject playerObj = new JsonObject();
			playerObj.addProperty("verifyIp", entry.getValue().verifyIp);
			JsonArray ipArray = new JsonArray();
			for (String ip : entry.getValue().ips) {
				ipArray.add(ip);
			}
			playerObj.add("ips", ipArray);
			playersObject.add(entry.getKey(), playerObj);
		}
		json.add("allowedPlayers", playersObject);
		try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
			GSON.toJson(json, writer);
			LOGGER.info("Saved allowed player list to '{}'.", CONFIG_FILE);
		} catch (IOException e) {
			LOGGER.error("Error saving allowed player list to '{}': {}", CONFIG_FILE, e.getMessage());
		}
	}

	// -------------- Command Methods --------------

	public void addAllowedPlayer(String username) {
		if (!allowedPlayers.containsKey(username)) {
			AllowedPlayer ap = new AllowedPlayer();
			ap.verifyIp = requireIpValidation;
			allowedPlayers.put(username, ap);
			LOGGER.info("Added user '{}' to the allowed list (verifyIp={} ).", username, ap.verifyIp);
			saveConfig();
		} else {
			LOGGER.warn("User '{}' is already in the allowed list.", username);
		}
	}

	public void removeAllowedPlayer(String username) {
		if (allowedPlayers.remove(username) != null) {
			LOGGER.info("Removed user '{}' from the allowed list.", username);
			saveConfig();
		} else {
			LOGGER.warn("User '{}' was not found in the allowed list.", username);
		}
	}

	public void addAllowedIp(String username, String ip) {
		AllowedPlayer ap = allowedPlayers.get(username);
		if (ap == null) {
			LOGGER.warn("User '{}' is not in the allowed list.", username);
			return;
		}
		if (!ap.ips.contains(ip)) {
			ap.ips.add(ip);
			LOGGER.info("Added IP '{}' for user '{}'.", ip, username);
			saveConfig();
		} else {
			LOGGER.warn("IP '{}' is already registered for user '{}'.", ip, username);
		}
	}

	public void removeAllowedIp(String username, String ip) {
		AllowedPlayer ap = allowedPlayers.get(username);
		if (ap == null) {
			LOGGER.warn("User '{}' is not in the allowed list.", username);
			return;
		}
		if (ap.ips.remove(ip)) {
			LOGGER.info("Removed IP '{}' for user '{}'.", ip, username);
			saveConfig();
		} else {
			LOGGER.warn("IP '{}' was not registered for user '{}'.", ip, username);
		}
	}

	public void toggleUserIpVerification(String username) {
		AllowedPlayer ap = allowedPlayers.get(username);
		if (ap == null) {
			LOGGER.warn("User '{}' is not in the allowed list.", username);
			return;
		}
		ap.verifyIp = !ap.verifyIp;
		LOGGER.info("User '{}' IP verification toggled to {}.", username, ap.verifyIp);
		saveConfig();
	}

	// -------------- Command Registration --------------

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

		dispatcher.register(CommandManager.literal("toggleUserIp")
				.requires(source -> source.hasPermissionLevel(4))
				.then(CommandManager.argument("username", StringArgumentType.string())
						.executes(context -> {
							String username = StringArgumentType.getString(context, "username");
							toggleUserIpVerification(username);
							AllowedPlayer ap = allowedPlayers.get(username);
							String status = (ap != null && ap.verifyIp) ? "enabled" : "disabled";
							context.getSource().sendFeedback(() ->
									Text.literal("User '" + username + "' IP verification is now " + status + ".").formatted(Formatting.AQUA), false);
							return 1;
						})
				)
		);

		dispatcher.register(CommandManager.literal("listUsers")
				.requires(source -> source.hasPermissionLevel(4))
				.executes(context -> {
					StringBuilder sb = new StringBuilder();
					allowedPlayers.forEach((user, ap) -> {
						sb.append(user)
								.append(" (verifyIp: ").append(ap.verifyIp)
								.append(", IPs: ").append(ap.ips.isEmpty() ? "none" : String.join(", ", ap.ips))
								.append("), ");
					});
					if (sb.length() >= 2) {
						sb.setLength(sb.length() - 2);
					}
					context.getSource().sendFeedback(() ->
							Text.literal("Allowed users: " + sb).formatted(Formatting.YELLOW), false);
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

		dispatcher.register(CommandManager.literal("addIp")
				.requires(source -> source.hasPermissionLevel(4))
				.then(CommandManager.argument("username", StringArgumentType.string())
						.then(CommandManager.argument("ip", StringArgumentType.string())
								.executes(context -> {
									String username = StringArgumentType.getString(context, "username");
									String ip = StringArgumentType.getString(context, "ip");
									if (!allowedPlayers.containsKey(username)) {
										context.getSource().sendFeedback(() ->
												Text.literal("User '" + username + "' is not in the allowed list.").formatted(Formatting.RED), false);
										return 0;
									}
									addAllowedIp(username, ip);
									context.getSource().sendFeedback(() ->
											Text.literal("Added IP '" + ip + "' for user '" + username + "'.").formatted(Formatting.GREEN), false);
									return 1;
								})
						)
				)
		);

		dispatcher.register(CommandManager.literal("removeIp")
				.requires(source -> source.hasPermissionLevel(4))
				.then(CommandManager.argument("username", StringArgumentType.string())
						.then(CommandManager.argument("ip", StringArgumentType.string())
								.executes(context -> {
									String username = StringArgumentType.getString(context, "username");
									String ip = StringArgumentType.getString(context, "ip");
									if (!allowedPlayers.containsKey(username)) {
										context.getSource().sendFeedback(() ->
												Text.literal("User '" + username + "' is not in the allowed list.").formatted(Formatting.RED), false);
										return 0;
									}
									removeAllowedIp(username, ip);
									context.getSource().sendFeedback(() ->
											Text.literal("Removed IP '" + ip + "' for user '" + username + "'.").formatted(Formatting.RED), false);
									return 1;
								})
						)
				)
		);

		dispatcher.register(CommandManager.literal("toggleGlobalIp")
				.requires(source -> source.hasPermissionLevel(4))
				.executes(context -> {
					requireIpValidation = !requireIpValidation;
					saveConfig();
					String status = requireIpValidation ? "enabled" : "disabled";
					context.getSource().sendFeedback(() ->
							Text.literal("Global IP validation is now " + status + ".").formatted(Formatting.AQUA), false);
					return 1;
				})
		);
	}
}
