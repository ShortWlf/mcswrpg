package com.swrpgmotd;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Swrpgmotd implements ModInitializer {
	public static final String MOD_ID = "swrpgmotd";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private JsonObject config;

	@Override
	public void onInitialize() {
		LOGGER.info("MOTD System Initialized!");
		loadConfig();

		// When a player joins, send the MOTD via a tellraw command.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayerEntity player = handler.getPlayer();
			sendMotd(player);
		});

		// Register the /motd command with "reload" and "show" subcommands.
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
			dispatcher.register(CommandManager.literal("motd")
					.then(CommandManager.literal("reload")
							.executes(context -> {
								loadConfig();
								context.getSource().sendFeedback(() -> Text.literal("MOTD configuration reloaded!"), false);
								return 1;
							}))
					.then(CommandManager.literal("show")
							.executes(context -> {
								ServerCommandSource source = context.getSource();
								if (source.getEntity() instanceof ServerPlayerEntity) {
									sendMotd((ServerPlayerEntity) source.getEntity());
								} else {
									source.getServer().getPlayerManager().getPlayerList().forEach(this::sendMotd);
								}
								return 1;
							}))
			);
		});
	}

	// Loads configuration from config/swrpgmotd.json.
	// If missing, auto-creates the default configuration.
	private void loadConfig() {
		Path configPath = Paths.get("config", "swrpgmotd.json");

		if (!configPath.toFile().exists()) {
			// Ignore the boolean result of mkdirs() – this warning is benign.
			configPath.getParent().toFile().mkdirs();
			try (Writer writer = new FileWriter(configPath.toFile())) {
				String defaultConfig = """
                    {
                      "motd": "Welcome to our server! Check out what's new:",
                      "wiki_link": "https://yourwiki.com",
                      "link_text": "[Wiki]"
                    }
                    """;
				writer.write(defaultConfig);
				writer.flush();
				LOGGER.info("Default config created at: {}", configPath.toAbsolutePath());
			} catch (IOException e) {
				LOGGER.error("Failed to create default config file!", e);
			}
		}

		try (FileReader reader = new FileReader(configPath.toFile())) {
			config = JsonParser.parseReader(reader).getAsJsonObject();
			LOGGER.info("Config loaded successfully!");
		} catch (IOException e) {
			LOGGER.error("Failed to load config file!", e);
		}
	}

	// Sends the MOTD message (with a clickable, blue-underlined web link) to the specified player.
	// This method builds a JSON string in the tellraw format and executes it using the command dispatcher.
	private void sendMotd(ServerPlayerEntity player) {
		if (config == null) return;

		String motdText = config.get("motd").getAsString();
		String wikiLink = config.get("wiki_link").getAsString();
		String linkText = config.get("link_text").getAsString();

		// Build the JSON string in standard tellraw format.
		String json = String.format(
				"{\"text\":\"%s \",\"extra\":["
						+ "{\"text\":\"%s\",\"color\":\"blue\",\"underlined\":true,"
						+ "\"clickEvent\":{\"action\":\"open_url\",\"value\":\"%s\"},"
						+ "\"hoverEvent\":{\"action\":\"show_text\",\"value\":{\"text\":\"Click to open the wiki!\"}}},"
						+ "{\"text\":\" (%s)\"}"
						+ "]}",
				escapeJson(motdText),
				escapeJson(linkText),
				escapeJson(wikiLink),
				escapeJson(wikiLink)
		);

		// Construct the tellraw command using @s to target the player.
		String command = "tellraw @s " + json;

		try {
			// Use the command manager's dispatcher to execute the tellraw command.
			player.getServer().getCommandManager().getDispatcher().execute(command, player.getCommandSource());
		} catch (Exception e) {
			LOGGER.error("Error executing tellraw command", e);
			// Fallback: if the tellraw command fails, send a plain text message.
			player.sendMessage(Text.literal(motdText + " " + linkText + " (" + wikiLink + ")"), false);
		}
	}

	// Basic JSON escape method (escapes backslashes and quotes).
	private String escapeJson(String text) {
		return text.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
