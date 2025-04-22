package com.seopcmdoveride;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HashMap;

import static net.minecraft.server.command.CommandManager.literal;

public class Seopcmdoveride implements ModInitializer {

	// Global configuration & state
	public static boolean opCommandsEnabled = false;
	public static final Set<String> disabledCommands = new HashSet<>();
	public static final Map<UUID, Integer> warningsMap = new HashMap<>();
	// We still keep these for configuration even though our new local check will use IP prefixes.
	public static boolean ipWhitelistEnabled = false;
	public static final Set<String> allowedIPs = new HashSet<>();
	public static int warningLimit = 3;

	// Flag to ensure IP retrieval errors are logged only once.
	private static boolean ipErrorWarned = false;

	// Configuration file path (placed in the server's config folder)
	private static final Path CONFIG_PATH = Paths.get("config", "seopcmdoveride.json");

	@Override
	public void onInitialize() {
		loadConfig();
		registerReloadCommand();
		registerJoinEvent();
		registerDisabledCommandOverrides();
		registerOpAndGamemodeCheckOnTick();
		System.out.println("[seopcmdoveride] Mod initialized.");
	}

	// ─── Command Registration ─────────────────────────────────────────────

	private void registerReloadCommand() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(literal("seopreload")
					.requires(source -> source.hasPermissionLevel(2))
					.executes(context -> {
						loadConfig();
						registerDisabledCommandOverrides(); // Refresh override commands
						context.getSource().sendFeedback(() -> Text.literal("seopcmdoveride config reloaded!"), false);
						return 1;
					}));
		});
	}

	// ─── Event Registrations ─────────────────────────────────────────────

	/**
	 * When a player joins:
	 * - Forces the player's gamemode to Survival via executing a server command.
	 * - Retrieves the player's IP.
	 * - Checks if the IP is local by verifying if it starts with "127.", "10." or "192."
	 *   (if ipWhitelistEnabled is true). If not local and op commands are disabled,
	 *   the player's op status will be removed and they will be disconnected.
	 */
	private void registerJoinEvent() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayerEntity player = handler.getPlayer();
			// Force gamemode to Survival.
			forceSurvival(player, server);

			String ip = getPlayerIP(player);
			boolean bypass = false;
			if (ipWhitelistEnabled && ip != null) {
				String trimmedIp = ip.trim();
				if (trimmedIp.startsWith("127.") || trimmedIp.startsWith("10.") || trimmedIp.startsWith("192.")) {
					bypass = true;
				}
			}
			if (!opCommandsEnabled && !bypass &&
					server.getPlayerManager().isOperator(player.getGameProfile())) {
				server.getPlayerManager().getOpList().remove(player.getGameProfile());
				player.sendMessage(Text.literal("Your op status has been removed because op commands are disabled."), false);
				player.networkHandler.disconnect(Text.literal("Disconnected: Unauthorized op status."));
			}
		});
	}

	/**
	 * Registers override commands for each dangerous command.
	 * When executed by a player who does not have a local IP (based on our prefix check) while op commands are disabled,
	 * that player's op status is removed and they are disconnected.
	 */
	private void registerDisabledCommandOverrides() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			for (String cmd : disabledCommands) {
				dispatcher.register(literal(cmd)
						.requires(source -> source.hasPermissionLevel(0))
						.executes(context -> {
							ServerCommandSource src = context.getSource();
							if (!opCommandsEnabled) {
								if (src.getEntity() instanceof ServerPlayerEntity) {
									ServerPlayerEntity player = (ServerPlayerEntity) src.getEntity();
									String ip = getPlayerIP(player);
									boolean bypass = false;
									if (ipWhitelistEnabled && ip != null) {
										String trimmedIp = ip.trim();
										if (trimmedIp.startsWith("127.") || trimmedIp.startsWith("10.") || trimmedIp.startsWith("192.")) {
											bypass = true;
										}
									}
									if (!bypass) {
										player.getServer().getPlayerManager().getOpList().remove(player.getGameProfile());
										src.sendFeedback(() -> Text.literal("Disabled op command /" + cmd +
												" attempted. You have been de-opped and kicked."), false);
										player.networkHandler.disconnect(Text.literal("Kicked: Unauthorized op command attempt."));
										return 0;
									}
								}
							}
							return 1;
						}));
			}
		});
	}

	/**
	 * On every server tick:
	 * - If a player's IP is not local (does not start with "127.", "10." or "192.") and they are op,
	 *   remove op status and disconnect them.
	 * - If their gamemode is not Survival, force it to Survival.
	 */
	private void registerOpAndGamemodeCheckOnTick() {
		ServerTickEvents.START_SERVER_TICK.register(server -> {
			if (!opCommandsEnabled) {
				for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
					String ip = getPlayerIP(player);
					boolean bypass = false;
					if (ipWhitelistEnabled && ip != null) {
						String trimmedIp = ip.trim();
						if (trimmedIp.startsWith("127.") || trimmedIp.startsWith("10.") || trimmedIp.startsWith("192.")) {
							bypass = true;
						}
					}
					if (!bypass && server.getPlayerManager().isOperator(player.getGameProfile())) {
						server.getPlayerManager().getOpList().remove(player.getGameProfile());
						player.sendMessage(Text.literal("Your op status has been removed."), false);
						player.networkHandler.disconnect(Text.literal("Disconnected: Unauthorized op status."));
						continue; // Skip further checks for this player.
					}
					if (!bypass && player.getGameMode() != GameMode.SURVIVAL) {
						forceSurvival(player, server);
						player.sendMessage(Text.literal("Your gamemode has been reset to Survival."), false);
					}
				}
			}
		});
	}

	// ─── Helper Methods ─────────────────────────────────────────────

	/**
	 * Retrieves the player's IP address.
	 * This method uses reflection to access the private "connection" field on the player's network handler,
	 * then calls getAddress() (or, as a fallback, getRemoteAddress()) on that connection.
	 * If errors occur, they are logged only once.
	 */
	public static String getPlayerIP(ServerPlayerEntity player) {
		try {
			java.lang.reflect.Field connectionField = player.networkHandler.getClass().getDeclaredField("connection");
			connectionField.setAccessible(true);
			Object connection = connectionField.get(player.networkHandler);
			java.lang.reflect.Method getAddressMethod;
			try {
				getAddressMethod = connection.getClass().getMethod("getAddress");
			} catch (NoSuchMethodException nsme) {
				getAddressMethod = connection.getClass().getMethod("getRemoteAddress");
			}
			Object addrObj = getAddressMethod.invoke(connection);
			if (addrObj instanceof InetSocketAddress) {
				InetSocketAddress isa = (InetSocketAddress) addrObj;
				return isa.getAddress().getHostAddress();
			}
		} catch (Exception e) {
			if (!ipErrorWarned) {
				System.err.println("[seopcmdoveride] Error retrieving IP address: " + e.getMessage());
				ipErrorWarned = true;
			}
		}
		return null;
	}

	/**
	 * Forces the player's gamemode to Survival by executing the server command "gamemode survival <player>".
	 * This simulates a command block command, avoiding direct use of protected methods.
	 * Uses player.getGameProfile().getName() to obtain the player's name.
	 */
	private static void forceSurvival(ServerPlayerEntity player, MinecraftServer server) {
		try {
			String playerName = player.getGameProfile().getName();
			String command = "gamemode survival " + playerName;
			ServerCommandSource src = server.getCommandSource().withSilent().withLevel(4);
			server.getCommandManager().getDispatcher().execute(command, src);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Loads configuration from config/seopcmdoveride.json.
	 * If the file does not exist, creates a default pretty-printed configuration.
	 *
	 * Expected JSON format:
	 * {
	 *   "opCommandsEnabled": false,
	 *   "disabledCommands": ["ban", "ban-ip", "banlist", "kick", "op", "deop", "gamemode",
	 *                          "gmc", "gms", "gma", "gmsp", "defaultgamemode", "difficulty",
	 *                          "stop", "whitelist", "pardon", "pardon-ip", "spectator"],
	 *   "ipWhitelist": { "enabled": false, "allowedIPs": [] },
	 *   "warningLimit": 3
	 * }
	 */
	public static void loadConfig() {
		// Set default values.
		opCommandsEnabled = false;
		disabledCommands.clear();
		disabledCommands.add("ban");
		disabledCommands.add("ban-ip");
		disabledCommands.add("banlist");
		disabledCommands.add("kick");
		disabledCommands.add("op");
		disabledCommands.add("deop");
		disabledCommands.add("gamemode");
		disabledCommands.add("gmc");
		disabledCommands.add("gms");
		disabledCommands.add("gma");
		disabledCommands.add("gmsp");
		disabledCommands.add("defaultgamemode");
		disabledCommands.add("difficulty");
		disabledCommands.add("stop");
		disabledCommands.add("whitelist");
		disabledCommands.add("pardon");
		disabledCommands.add("pardon-ip");
		disabledCommands.add("spectator");

		// Although these values are in the config, they are ignored
		// in our local IP mode since we check for IP prefixes.
		ipWhitelistEnabled = false;
		allowedIPs.clear();
		warningLimit = 3;

		if (!Files.exists(CONFIG_PATH)) {
			try {
				Files.createDirectories(CONFIG_PATH.getParent());
				String defaultConfig = "{\n" +
						"  \"opCommandsEnabled\": false,\n" +
						"  \"disabledCommands\": [\n" +
						"    \"ban\",\n" +
						"    \"ban-ip\",\n" +
						"    \"banlist\",\n" +
						"    \"kick\",\n" +
						"    \"op\",\n" +
						"    \"deop\",\n" +
						"    \"gamemode\",\n" +
						"    \"gmc\",\n" +
						"    \"gms\",\n" +
						"    \"gma\",\n" +
						"    \"gmsp\",\n" +
						"    \"defaultgamemode\",\n" +
						"    \"difficulty\",\n" +
						"    \"stop\",\n" +
						"    \"whitelist\",\n" +
						"    \"pardon\",\n" +
						"    \"pardon-ip\",\n" +
						"    \"spectator\"\n" +
						"  ],\n" +
						"  \"ipWhitelist\": {\n" +
						"    \"enabled\": false,\n" +
						"    \"allowedIPs\": []\n" +
						"  },\n" +
						"  \"warningLimit\": 3\n" +
						"}";
				Files.write(CONFIG_PATH, defaultConfig.getBytes());
				System.out.println("[seopcmdoveride] Default config created at " + CONFIG_PATH);
			} catch (IOException e) {
				System.err.println("[seopcmdoveride] Error creating default config: " + e.getMessage());
			}
		}

		try (BufferedReader reader = Files.newBufferedReader(CONFIG_PATH)) {
			JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
			opCommandsEnabled = json.get("opCommandsEnabled").getAsBoolean();

			disabledCommands.clear();
			JsonArray arr = json.getAsJsonArray("disabledCommands");
			for (JsonElement el : arr) {
				disabledCommands.add(el.getAsString().toLowerCase());
			}

			if (json.has("ipWhitelist")) {
				JsonObject ipWhitelist = json.getAsJsonObject("ipWhitelist");
				ipWhitelistEnabled = ipWhitelist.get("enabled").getAsBoolean();
				allowedIPs.clear();
				JsonArray ips = ipWhitelist.getAsJsonArray("allowedIPs");
				for (JsonElement ipEl : ips) {
					allowedIPs.add(ipEl.getAsString());
				}
			}

			if (json.has("warningLimit")) {
				warningLimit = json.get("warningLimit").getAsInt();
			}

			System.out.println("[seopcmdoveride] Config loaded: opCommandsEnabled=" + opCommandsEnabled +
					", disabledCommands=" + disabledCommands +
					", ipWhitelistEnabled=" + ipWhitelistEnabled +
					", allowedIPs=" + allowedIPs +
					", warningLimit=" + warningLimit);
		} catch (IOException e) {
			System.err.println("[seopcmdoveride] Error loading config: " + e.getMessage());
		}
	}
}
