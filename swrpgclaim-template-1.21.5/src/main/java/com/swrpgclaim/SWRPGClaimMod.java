package com.swrpgclaim;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.block.BedBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("deprecation")
public class SWRPGClaimMod implements ModInitializer {

	private static final Logger LOGGER = LogManager.getLogger("SWRPGClaimMod");
	public static ClaimConfig config;
	private static int tickCounter = 0;
	private static final Map<UUID, String> playerRegionCache = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> lastMessageSent = new ConcurrentHashMap<>();

	@Override
	public void onInitialize() {
		config = ClaimConfig.load();

		// --- Block Break Protection ---
		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
			for (Map.Entry<String, ClaimConfig.ClaimedArea> entry : config.playerClaims.entrySet()) {
				ClaimConfig.ClaimedArea claim = entry.getValue();
				if (isWithinClaimFlat(pos, claim)) {
					// If edit mode is on, allow everyone to break blocks.
					if (claim.editMode) {
						return true;
					}
					// If edit mode is off, only allow the owner to break blocks.
					if (entry.getKey().equals(player.getName().getString())) {
						return true;
					}
					// Otherwise, cancel block breaking.
					player.sendMessage(Text.literal("You cannot destroy blocks here. This area is claimed!"), false);
					return false;
				}
			}
			return true;
		});

		// --- Block Placement and Interactive Use Protection ---
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			BlockPos pos = hitResult.getBlockPos();
			String playerName = player.getName().getString();
			BlockState state = world.getBlockState(pos);
			Block block = state.getBlock();
			String translationKey = block.getTranslationKey();

			// Determine whether this block is interactive.
			boolean isInteractiveBlock = (block instanceof ChestBlock ||
					block instanceof DoorBlock ||
					block instanceof BedBlock ||
					translationKey.contains("chest") ||
					translationKey.contains("door") ||
					translationKey.contains("bed"));

			// Find the claim covering this position.
			ClaimConfig.ClaimedArea claim = null;
			String claimOwner = "";
			for (Map.Entry<String, ClaimConfig.ClaimedArea> entry : config.playerClaims.entrySet()) {
				if (isWithinClaimFlat(pos, entry.getValue())) {
					claim = entry.getValue();
					claimOwner = entry.getKey();
					break;
				}
			}
			if (claim == null) return ActionResult.PASS;

			// If edit mode is enabled, then let anyone interact.
			if (claim.editMode) {
				return ActionResult.PASS;
			}

			// Now, when edit mode is off:
			if (isInteractiveBlock) {
				// Only allow the claim owner to interact (unless allowUse is enabled).
				if (claimOwner.equals(playerName)) {
					return ActionResult.PASS;
				}
				if (claim.allowUse) {
					return state.onUse(world, player, hitResult);
				}
				player.sendMessage(Text.literal("You cannot use objects in " + claimOwner + "'s claim."), false);
				return ActionResult.FAIL;
			}

			// For block placement (non-interactive):
			boolean isPlacement = player.getStackInHand(hand).getItem() instanceof BlockItem;
			if (isPlacement) {
				// Again, only allow the claim owner to place blocks when edit mode is off.
				if (claimOwner.equals(playerName)) {
					return ActionResult.PASS;
				}
				player.sendMessage(Text.literal("Block placement denied: This area is claimed by " + claimOwner), false);
				return ActionResult.FAIL;
			}

			return ActionResult.PASS;
		});

		// --- Command Registration ---
		CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {

			// /claimarea - Player claims a flat area centered at their position.
			dispatcher.register(CommandManager.literal("claimarea")
					.requires(source -> source.hasPermissionLevel(0))
					.executes(context -> {
						ServerPlayerEntity player = context.getSource().getPlayer();
						if (player == null) {
							context.getSource().sendError(Text.literal("This command can only be used by a player."));
							return 0;
						}
						BlockPos pos = player.getBlockPos();
						String playerName = player.getName().getString();
						if (!config.playerClaims.containsKey(playerName)) {
							context.getSource().sendFeedback(() ->
									Text.literal("You are not registered to claim an area. Ask an admin to use /addclaimplayer."), false);
							return 0;
						}
						int half = config.allowedClaimSize / 2;
						ClaimConfig.ClaimedArea newClaim = new ClaimConfig.ClaimedArea(
								pos.getX() - half, pos.getY(), pos.getZ() - half, config.allowedClaimSize);
						ClaimConfig.ClaimedArea oldClaim = config.playerClaims.get(playerName);
						if (oldClaim != null && oldClaim.areaName != null && !oldClaim.areaName.isEmpty()) {
							newClaim.areaName = oldClaim.areaName;
						}
						if (claimsOverlap(newClaim, config.playerClaims, playerName)) {
							context.getSource().sendFeedback(() ->
									Text.literal("This claim overlaps with an existing claim. Please choose another area."), false);
							return 0;
						}
						config.playerClaims.put(playerName, newClaim);
						config.save();
						context.getSource().sendFeedback(() ->
								Text.literal("Area claimed for " + playerName + " centered on (" +
										pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")."), false);
						return 1;
					})
			);

			// /addclaimplayer <player> - Admin registers a player.
			dispatcher.register(CommandManager.literal("addclaimplayer")
					.requires(source -> source.hasPermissionLevel(2))
					.then(CommandManager.argument("player", StringArgumentType.word())
							.executes(context -> {
								String targetPlayer = StringArgumentType.getString(context, "player");
								if (config.playerClaims.containsKey(targetPlayer)) {
									context.getSource().sendFeedback(() ->
											Text.literal("Player " + targetPlayer + " is already registered."), false);
									return 0;
								}
								config.playerClaims.put(targetPlayer, new ClaimConfig.ClaimedArea(0, 0, 0, config.allowedClaimSize));
								config.save();
								context.getSource().sendFeedback(() ->
										Text.literal("Player " + targetPlayer + " has been registered with default claim size " + config.allowedClaimSize + "."), false);
								return 1;
							})
					)
			);

			// /setclaimsize <player> <size> - Admin command to update a player's claim size.
			dispatcher.register(CommandManager.literal("setclaimsize")
					.requires(source -> source.hasPermissionLevel(2))
					.then(CommandManager.argument("player", StringArgumentType.word())
							.then(CommandManager.argument("size", IntegerArgumentType.integer(1))
									.executes(context -> {
										String target = StringArgumentType.getString(context, "player");
										int newSize = IntegerArgumentType.getInteger(context, "size");
										ClaimConfig.ClaimedArea area = config.playerClaims.get(target);
										if (area == null) {
											context.getSource().sendFeedback(() ->
													Text.literal("No claim data found for player: " + target), false);
											return 0;
										}
										area.size = newSize;
										config.save();
										context.getSource().sendFeedback(() ->
												Text.literal("Claim size for " + target + " updated to " + newSize), false);
										return 1;
									})
							)
					)
			);

			// /refreshclaims - Reload configuration from disk.
			// Only change: Now everyone can use it.
			dispatcher.register(CommandManager.literal("refreshclaims")
					.requires(source -> true)
					.executes(context -> {
						config = ClaimConfig.load();
						context.getSource().sendFeedback(() ->
								Text.literal("Configuration refreshed from disk."), false);
						return 1;
					})
			);

			// /setclaimname <newName> - Player sets their claim name.
			dispatcher.register(CommandManager.literal("setclaimname")
					.requires(source -> source.hasPermissionLevel(0))
					.then(CommandManager.argument("newName", StringArgumentType.greedyString())
							.executes(context -> {
								ServerPlayerEntity player = context.getSource().getPlayer();
								if (player == null) {
									context.getSource().sendError(Text.literal("This command can only be used by a player."));
									return 0;
								}
								String playerName = player.getName().getString();
								String newName = StringArgumentType.getString(context, "newName");
								if (!config.playerClaims.containsKey(playerName)) {
									context.getSource().sendFeedback(() ->
											Text.literal("You are not registered. Use /addclaimplayer first."), false);
									return 0;
								}
								ClaimConfig.ClaimedArea area = config.playerClaims.get(playerName);
								area.areaName = newName;
								config.save();
								context.getSource().sendFeedback(() ->
										Text.literal("Your claim name has been updated to: " + newName), false);
								return 1;
							})
					)
			);

			// /setclaimnameadmin <player> <newName> - Admin sets another player's claim name.
			dispatcher.register(CommandManager.literal("setclaimnameadmin")
					.requires(source -> source.hasPermissionLevel(2))
					.then(CommandManager.argument("player", StringArgumentType.word())
							.then(CommandManager.argument("newName", StringArgumentType.greedyString())
									.executes(context -> {
										String target = StringArgumentType.getString(context, "player");
										String newName = StringArgumentType.getString(context, "newName");
										if (!config.playerClaims.containsKey(target)) {
											context.getSource().sendFeedback(() ->
													Text.literal("Player " + target + " does not have a claim registered."), false);
											return 0;
										}
										ClaimConfig.ClaimedArea area = config.playerClaims.get(target);
										area.areaName = newName;
										config.save();
										context.getSource().sendFeedback(() ->
												Text.literal("Claim name for " + target + " updated to: " + newName), false);
										return 1;
									})
							)
					)
			);

			// /removeclaim - Player removes their claim.
			dispatcher.register(CommandManager.literal("removeclaim")
					.requires(source -> source.hasPermissionLevel(0))
					.executes(context -> {
						ServerPlayerEntity player = context.getSource().getPlayer();
						if (player == null) {
							context.getSource().sendError(Text.literal("This command can only be used by a player."));
							return 0;
						}
						String playerName = player.getName().getString();
						if (!config.playerClaims.containsKey(playerName)) {
							context.getSource().sendFeedback(() ->
									Text.literal("You do not have a claim to remove."), false);
							return 0;
						}
						config.playerClaims.remove(playerName);
						config.save();
						context.getSource().sendFeedback(() ->
								Text.literal("Your claim has been removed."), false);
						return 1;
					})
			);

			// /removeclaimadmin <player> - Admin removes another player's claim.
			dispatcher.register(CommandManager.literal("removeclaimadmin")
					.requires(source -> source.hasPermissionLevel(2))
					.then(CommandManager.argument("player", StringArgumentType.word())
							.executes(context -> {
								String target = StringArgumentType.getString(context, "player");
								if (!config.playerClaims.containsKey(target)) {
									context.getSource().sendFeedback(() ->
											Text.literal("Player " + target + " does not have a claim."), false);
									return 0;
								}
								config.playerClaims.remove(target);
								config.save();
								context.getSource().sendFeedback(() ->
										Text.literal("Claim for " + target + " has been removed."), false);
								return 1;
							})
					)
			);

			// /editclaim - Toggle edit mode for the player's claim.
			dispatcher.register(CommandManager.literal("editclaim")
					.requires(source -> source.hasPermissionLevel(0))
					.executes(context -> {
						ServerPlayerEntity player = context.getSource().getPlayer();
						if (player == null) {
							context.getSource().sendError(Text.literal("This command can only be used by a player."));
							return 0;
						}
						String playerName = player.getName().getString();
						ClaimConfig.ClaimedArea claim = config.playerClaims.get(playerName);
						if (claim == null) {
							context.getSource().sendFeedback(() ->
									Text.literal("You do not have a claim registered. Use /addclaimplayer first."), false);
							return 0;
						}
						claim.editMode = !claim.editMode;
						config.save();
						context.getSource().sendFeedback(() ->
								Text.literal("Your claim is now " + (claim.editMode ? "editable" : "protected.")), false);
						return 1;
					})
			);

			// /swclaimuse - Toggle object-use permission for the player's claim.
			dispatcher.register(CommandManager.literal("swclaimuse")
					.requires(source -> source.hasPermissionLevel(0))
					.executes(context -> {
						ServerPlayerEntity player = context.getSource().getPlayer();
						if (player == null) {
							context.getSource().sendError(Text.literal("This command can only be used by a player."));
							return 0;
						}
						String playerName = player.getName().getString();
						ClaimConfig.ClaimedArea claim = config.playerClaims.get(playerName);
						if (claim == null) {
							context.getSource().sendFeedback(() ->
									Text.literal("You do not have a claim registered. Use /addclaimplayer first."), false);
							return 0;
						}
						claim.allowUse = !claim.allowUse;
						config.save();
						context.getSource().sendFeedback(() ->
								Text.literal("Your claim use permission is now " + (claim.allowUse ? "enabled." : "disabled.")), false);
						return 1;
					})
			);

			// /extendclaim <extension> - Extend the claim symmetrically.
			dispatcher.register(CommandManager.literal("extendclaim")
					.requires(source -> source.hasPermissionLevel(0))
					.then(CommandManager.argument("extension", IntegerArgumentType.integer(1))
							.executes(context -> {
								ServerPlayerEntity player = context.getSource().getPlayer();
								if (player == null) {
									context.getSource().sendError(Text.literal("This command can only be used by a player."));
									return 0;
								}
								String playerName = player.getName().getString();
								ClaimConfig.ClaimedArea claim = config.playerClaims.get(playerName);
								if (claim == null) {
									context.getSource().sendFeedback(() ->
											Text.literal("You do not have a claim registered. Use /addclaimplayer first."), false);
									return 0;
								}
								int extension = IntegerArgumentType.getInteger(context, "extension");
								int newX = claim.x - extension;
								int newZ = claim.z - extension;
								int newSize = claim.size + (2 * extension);
								int allowedMax = config.playerClaimMax.getOrDefault(playerName, config.allowedClaimSize);
								if (newSize > allowedMax) {
									context.getSource().sendFeedback(() ->
											Text.literal("Extension failed: Your claim cannot exceed a size of " + allowedMax + " blocks."), false);
									return 0;
								}
								ClaimConfig.ClaimedArea extendedClaim = new ClaimConfig.ClaimedArea(newX, claim.y, newZ, newSize);
								extendedClaim.areaName = claim.areaName;
								extendedClaim.editMode = claim.editMode;
								extendedClaim.allowUse = claim.allowUse;
								if (claimsOverlap(extendedClaim, config.playerClaims, playerName)) {
									context.getSource().sendFeedback(() ->
											Text.literal("Extension failed: The new claim area overlaps with another player's claim."), false);
									return 0;
								}
								claim.x = newX;
								claim.z = newZ;
								claim.size = newSize;
								config.save();
								context.getSource().sendFeedback(() ->
										Text.literal("Your claim has been extended! New boundaries: x: " + claim.x +
												", z: " + claim.z + ", size: " + claim.size), false);
								return 1;
							})
					)
			);

			// /claimmax <playerName> <max> - Set per-player maximum claim size.
			dispatcher.register(CommandManager.literal("claimmax")
					.requires(source -> source.hasPermissionLevel(0))
					.then(CommandManager.argument("playerName", StringArgumentType.word())
							.then(CommandManager.argument("max", IntegerArgumentType.integer(1))
									.executes(context -> {
										String targetPlayer = StringArgumentType.getString(context, "playerName");
										int maxClaim = IntegerArgumentType.getInteger(context, "max");
										config.playerClaimMax.put(targetPlayer, maxClaim);
										config.save();
										context.getSource().sendFeedback(() ->
												Text.literal("Set maximum claim size for " + targetPlayer + " to " + maxClaim + " blocks."), false);
										return 1;
									})
							)
					)
			);

			// --- [NEW] Teleport to Claim Command: /tpc ---
			dispatcher.register(CommandManager.literal("tpc")
					.requires(source -> source.hasPermissionLevel(0))
					.executes(context -> {
						ServerPlayerEntity player = context.getSource().getPlayer();
						if (player == null) {
							context.getSource().sendError(Text.literal("This command can only be used by a player."));
							return 0;
						}
						String playerName = player.getName().getString();
						ClaimConfig.ClaimedArea claim = config.playerClaims.get(playerName);
						if (claim == null) {
							context.getSource().sendFeedback(() ->
									Text.literal("You don't have a registered claim. Use /addclaimplayer and /claimarea first."), false);
							return 0;
						}
						// Calculate the center coordinates of the claim.
						int centerX = claim.x + claim.size / 2;
						int centerZ = claim.z + claim.size / 2;
						int centerY = claim.y + 1; // Slight offset so that you don't suffocate in the floor.

						// Build the teleport command string.
						// Adding .5 to the X and Z coordinates centers the player within the block.
						String teleportCommand = "tp " + playerName + " " + (centerX + 0.5) + " " + centerY + " " + (centerZ + 0.5);

						// Execute the teleport command using the server's command dispatcher.
						var dispatcherInstance = player.getServer().getCommandManager().getDispatcher();
						dispatcherInstance.execute(dispatcherInstance.parse(teleportCommand, context.getSource().getServer().getCommandSource()));

						context.getSource().sendFeedback(() ->
								Text.literal("Teleported you to your claim at (" + (centerX + 0.5) + ", " + centerY + ", " + (centerZ + 0.5) + ")."), false);
						return 1;
					})
			);
		});

		// --- Server Tick Event for Region Notifications ---
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			tickCounter++;
			if (tickCounter % 20 == 0) {
				server.getPlayerManager().getPlayerList().forEach(player -> {
					BlockPos pos = player.getBlockPos();
					String regionName = "the Wilderness";
					for (Map.Entry<String, ClaimConfig.ClaimedArea> entry : config.playerClaims.entrySet()) {
						if (isWithinClaimFlat(pos, entry.getValue())) {
							regionName = (entry.getValue().areaName != null && !entry.getValue().areaName.isEmpty())
									? entry.getValue().areaName
									: entry.getKey() + "'s Den";
							break;
						}
					}
					UUID playerId = player.getUuid();
					String lastRegion = playerRegionCache.get(playerId);
					if (!regionName.equals(lastRegion)) {
						playerRegionCache.put(playerId, regionName);
						lastMessageSent.put(playerId, (long) tickCounter);
						player.sendMessage(Text.literal("You are in " + regionName), true);
					} else {
						long last = lastMessageSent.getOrDefault(playerId, (long) tickCounter);
						if (tickCounter - last >= 40) {
							player.sendMessage(Text.literal(""), true);
							lastMessageSent.put(playerId, (long) tickCounter);
						}
					}
				});
			}
		});
	}

	// Utility method to check if a position is within the claim (2D, based on x/z).
	public static boolean isWithinClaimFlat(BlockPos pos, ClaimConfig.ClaimedArea claim) {
		return pos.getX() >= claim.x && pos.getX() < claim.x + claim.size &&
				pos.getZ() >= claim.z && pos.getZ() < claim.z + claim.size;
	}

	// Helper to check overlapping claims (2D overlap).
	private static boolean overlapFlat(ClaimConfig.ClaimedArea a, ClaimConfig.ClaimedArea b) {
		return (a.x < b.x + b.size) && (a.x + a.size > b.x) &&
				(a.z < b.z + b.size) && (a.z + a.size > b.z);
	}

	// Helper to see if a new claim overlaps any existing claim (ignoring the current player's claim).
	private static boolean claimsOverlap(ClaimConfig.ClaimedArea newClaim, Map<String, ClaimConfig.ClaimedArea> claims, String currentPlayer) {
		for (Map.Entry<String, ClaimConfig.ClaimedArea> entry : claims.entrySet()) {
			if (entry.getKey().equals(currentPlayer)) continue;
			if (overlapFlat(newClaim, entry.getValue())) return true;
		}
		return false;
	}

	// --- Claim Configuration Class ---
	public static class ClaimConfig {
		private static final File CONFIG_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "swrpgclaim.json");
		private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
		public int allowedClaimSize = 20;
		public Map<String, ClaimedArea> playerClaims = new HashMap<>();
		public Map<String, Integer> playerClaimMax = new HashMap<>();

		public static class ClaimedArea {
			public int x, y, z;
			public int size;
			public String areaName;
			public boolean editMode;
			public boolean allowUse;

			public ClaimedArea(int x, int y, int z, int size) {
				this.x = x;
				this.y = y;
				this.z = z;
				this.size = size;
				this.areaName = "";
				this.editMode = false;
				this.allowUse = false;
			}
		}

		public static ClaimConfig load() {
			if (CONFIG_FILE.exists()) {
				try (FileReader reader = new FileReader(CONFIG_FILE)) {
					return GSON.fromJson(reader, ClaimConfig.class);
				} catch (IOException e) {
					LOGGER.error("Error loading config file", e);
				}
			}
			ClaimConfig config = new ClaimConfig();
			config.save();
			return config;
		}

		public void save() {
			try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
				GSON.toJson(this, writer);
			} catch (IOException e) {
				LOGGER.error("Error saving config file", e);
			}
		}
	}
}
