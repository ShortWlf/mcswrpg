package com.swrpglevels;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.CropBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.FishingRodItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.registry.Registries;
import net.minecraft.screen.BrewingStandScreenHandler;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.SmokerScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

public class Swrpglevels implements ModInitializer {
	public static final String MOD_ID = "swrpglevels";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Config directory and file
	private static final Path CONFIG_DIR = Path.of("config", MOD_ID);
	private static final Path CONFIG_FILE = CONFIG_DIR.resolve("skill_config.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static SkillConfig config;

	// In‑memory player stats, loaded per UUID.
	private static final Map<UUID, PlayerStats> playerStatsMap = new HashMap<>();

	// Maps for temporary data tracking.
	private static final Map<UUID, Map<Item, Integer>> prevCraftingInvCounts = new HashMap<>();
	private static final Map<UUID, ItemStack> lastCookingResult = new HashMap<>();
	// New map for tracking crafting results
	private static final Map<UUID, ItemStack> lastCraftingResult = new HashMap<>();
	private static final Map<UUID, Map<Item, Integer>> lastFishCounts = new HashMap<>();
	private static final Map<UUID, Map<Item, Integer>> lastCampfireCookedFishCounts = new HashMap<>();
	private static final Map<UUID, Map<Item, Integer>> maxFurnaceCookedFoodCounts = new HashMap<>();
	private static final Map<UUID, Map<Item, Integer>> maxFurnaceSmithingFoodCounts = new HashMap<>();
	private static final Map<UUID, Map<Integer, ItemStack>> lastBrewingStandResults = new HashMap<>();
	private static final Map<UUID, ItemStack> lastEnchantmentItem = new HashMap<>();

	// NEW: For fishing—track the last tick XP was awarded
	private static final Map<UUID, Long> lastFishingXpTime = new HashMap<>();

	// NEW: Map to track nearby hostile mobs for combat tracking.
	private static final Map<UUID, Map<UUID, String>> trackedCombatEntities = new HashMap<>();

	// Functional interface for updating XP.
	private interface XPUpdater {
		int getExp(PlayerStats s);
		void addExp(PlayerStats s, int xp);
		int baseXP();
		boolean expandable();
		int vanillaXP();
	}

	// Mapping skill names to their XP updater implementations.
	private static final Map<String, XPUpdater> XP_UPDATERS = new HashMap<>();
	static {
		XP_UPDATERS.put("agility", new XPUpdater() {
			public int getExp(PlayerStats s) { return s.getAgilityExp(); }
			public void addExp(PlayerStats s, int xp) { s.addAgilityExp(xp); }
			public int baseXP() { return config.agilityBaseXP; }
			public boolean expandable() { return config.agilityExpandable; }
			public int vanillaXP() { return config.agilityVanillaXP; }
		});
		XP_UPDATERS.put("woodcutting", new XPUpdater() {
			public int getExp(PlayerStats s) { return s.getWoodcutExp(); }
			public void addExp(PlayerStats s, int xp) { s.addWoodcutExp(xp); }
			public int baseXP() { return config.woodcuttingBaseXP; }
			public boolean expandable() { return config.woodcuttingExpandable; }
			public int vanillaXP() { return config.woodcuttingVanillaXP; }
		});
		XP_UPDATERS.put("farming", new XPUpdater() {
			public int getExp(PlayerStats s) { return s.getFarmingExp(); }
			public void addExp(PlayerStats s, int xp) { s.addFarmingExp(xp); }
			public int baseXP() { return config.farmingBaseXP; }
			public boolean expandable() { return config.farmingExpandable; }
			public int vanillaXP() { return config.farmingVanillaXP; }
		});
		XP_UPDATERS.put("harvesting", new XPUpdater() {
			public int getExp(PlayerStats s) { return s.getHarvestingExp(); }
			public void addExp(PlayerStats s, int xp) { s.addHarvestingExp(xp); }
			public int baseXP() { return config.harvestingBaseXP; }
			public boolean expandable() { return config.harvestingExpandable; }
			public int vanillaXP() { return config.harvestingVanillaXP; }
		});
		XP_UPDATERS.put("fishing", new XPUpdater() {
			public int getExp(PlayerStats s) { return s.getFishingExp(); }
			public void addExp(PlayerStats s, int xp) { s.addFishingExp(xp); }
			public int baseXP() { return config.fishingBaseXP; }
			public boolean expandable() { return config.fishingExpandable; }
			public int vanillaXP() { return config.fishingVanillaXP; }
		});
		XP_UPDATERS.put("crafting", new XPUpdater() {
			public int getExp(PlayerStats s) { return s.getCraftingExp(); }
			public void addExp(PlayerStats s, int xp) { s.addCraftingExp(xp); }
			public int baseXP() { return config.craftingBaseXP; }
			public boolean expandable() { return config.craftingExpandable; }
			public int vanillaXP() { return config.craftingVanillaXP; }
		});
		XP_UPDATERS.put("mining", new XPUpdater() {
			public int getExp(PlayerStats s) { return s.getMiningExp(); }
			public void addExp(PlayerStats s, int xp) { s.addMiningExp(xp); }
			public int baseXP() { return config.miningBaseXP; }
			public boolean expandable() { return config.miningExpandable; }
			public int vanillaXP() { return config.miningVanillaXP; }
		});
		XP_UPDATERS.put("cooking", new XPUpdater() {
			public int getExp(PlayerStats s) { return s.getCookingExp(); }
			public void addExp(PlayerStats s, int xp) { s.addCookingExp(xp); }
			public int baseXP() { return config.cookingBaseXP; }
			public boolean expandable() { return config.cookingExpandable; }
			public int vanillaXP() { return config.cookingVanillaXP; }
		});
		XP_UPDATERS.put("smithing", new XPUpdater() {
			public int getExp(PlayerStats s) { return s.getSmithingExp(); }
			public void addExp(PlayerStats s, int xp) { s.addSmithingExp(xp); }
			public int baseXP() { return config.smithingBaseXP; }
			public boolean expandable() { return config.smithingExpandable; }
			public int vanillaXP() { return config.smithingVanillaXP; }
		});
		XP_UPDATERS.put("alchemy", new XPUpdater() {
			public int getExp(PlayerStats s) { return s.getAlchemyExp(); }
			public void addExp(PlayerStats s, int xp) { s.addAlchemyExp(xp); }
			public int baseXP() { return config.alchemyBaseXP; }
			public boolean expandable() { return config.alchemyExpandable; }
			public int vanillaXP() { return config.alchemyVanillaXP; }
		});
		XP_UPDATERS.put("enchanting", new XPUpdater() {
			public int getExp(PlayerStats s) { return s.getEnchantingExp(); }
			public void addExp(PlayerStats s, int xp) { s.addEnchantingExp(xp); }
			public int baseXP() { return config.enchantingBaseXP; }
			public boolean expandable() { return config.enchantingExpandable; }
			public int vanillaXP() { return config.enchantingVanillaXP; }
		});
		// NEW: Combat updater.
		XP_UPDATERS.put("combat", new XPUpdater() {
			public int getExp(PlayerStats s) { return s.getCombatExp(); }
			public void addExp(PlayerStats s, int xp) { s.addCombatExp(xp); }
			public int baseXP() { return config.combatBaseXP; }
			public boolean expandable() { return config.combatExpandable; }
			public int vanillaXP() { return config.combatVanillaXP; }
		});
	}

	// Standard leveling formula.
	private static int getLevelForSkill(int xp, int baseXP, boolean expandable) {
		return expandable ? (int) Math.floor((Math.sqrt(8.0 * xp / baseXP + 1) - 1) / 2) : xp / baseXP;
	}

	// Award XP via the corresponding updater.
	private static void awardSkillXP(PlayerEntity player, String skill, int xpAmount) {
		if (!(player instanceof ServerPlayerEntity sp)) return;
		XPUpdater updater = XP_UPDATERS.get(skill);
		if (updater == null) return;
		PlayerStats stats = getStatsForPlayer(sp.getUuid());
		int oldLevel = getLevelForSkill(updater.getExp(stats), updater.baseXP(), updater.expandable());
		updater.addExp(stats, xpAmount);
		int newLevel = getLevelForSkill(updater.getExp(stats), updater.baseXP(), updater.expandable());
		if (newLevel > oldLevel) {
			sp.addExperience(updater.vanillaXP());
			sp.getWorld().playSound(null, sp.getBlockPos(), SoundEvents.ENTITY_PLAYER_LEVELUP,
					sp.getSoundCategory(), 1.0F, 1.0F);
			sp.sendMessage(Text.literal(skill + " leveled up to " + newLevel + "!")
					.formatted(Formatting.GREEN), true);
		}
	}

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing RPG Levels Mod...");
		loadConfig();
		registerPlayerEvents();
		registerCommands();
		registerTickEvents();
		registerUseBlockCallback();
		registerCampfireUseCallback();
		registerBlockBreakEvents();
		LOGGER.info("Mod initialization complete!");
	}

	private void registerPlayerEvents() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			UUID id = handler.player.getUuid();
			// Create/load player stats.
			PlayerStats stats = getStatsForPlayer(id);
			// Immediately write stats to file if missing.
			Path statsFile = CONFIG_DIR.resolve(id.toString() + ".json");
			if (!Files.exists(statsFile)) {
				try {
					savePlayerStats(id, stats);
					LOGGER.info("Created new stats file for player: {}", id);
				} catch (IOException e) {
					LOGGER.error("Error saving new stats for player {}", id, e);
				}
			}
			prevCraftingInvCounts.put(id, new HashMap<>());
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID id = handler.player.getUuid();
			PlayerStats s = playerStatsMap.get(id);
			if (s != null) {
				try {
					savePlayerStats(id, s);
				} catch (IOException e) {
					LOGGER.error("Error saving stats for player {}", id, e);
				}
			}
			prevCraftingInvCounts.remove(id);
			lastCookingResult.remove(id);
			lastCraftingResult.remove(id);
			lastFishCounts.remove(id);
			lastCampfireCookedFishCounts.remove(id);
			maxFurnaceCookedFoodCounts.remove(id);
			maxFurnaceSmithingFoodCounts.remove(id);
			lastBrewingStandResults.remove(id);
			lastEnchantmentItem.remove(id);
			trackedCombatEntities.remove(id);
			lastFishingXpTime.remove(id);
		});
	}

	private void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) ->
				dispatcher.register(
						net.minecraft.server.command.CommandManager.literal("stats")
								.executes(ctx -> {
									ServerPlayerEntity p = ctx.getSource().getPlayer();
									p.sendMessage(getStatsMessage(getStatsForPlayer(p.getUuid())), false);
									return 1;
								})
				)
		);
		CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) ->
				dispatcher.register(
						net.minecraft.server.command.CommandManager.literal("swrpgrefresh")
								.requires(src -> src.hasPermissionLevel(2))
								.executes(ctx -> {
									loadConfig();
									ctx.getSource().sendFeedback(() -> Text.literal("RPG Levels Mod configuration reloaded!")
											.formatted(Formatting.YELLOW), false);
									return 1;
								})
				)
		);
	}

	private Text getStatsMessage(PlayerStats s) {
		return Text.literal(
				"§6Agility: §a" + s.getAgilityExp() + " §6(Level: §a" +
						getLevelForSkill(s.getAgilityExp(), config.agilityBaseXP, config.agilityExpandable) + "§6)\n" +
						"§6Woodcutting: §a" + s.getWoodcutExp() + " §6(Level: §a" +
						getLevelForSkill(s.getWoodcutExp(), config.woodcuttingBaseXP, config.woodcuttingExpandable) + "§6)\n" +
						"§6Farming: §a" + s.getFarmingExp() + " §6(Level: §a" +
						getLevelForSkill(s.getFarmingExp(), config.farmingBaseXP, config.farmingExpandable) + "§6)\n" +
						"§6Harvesting: §a" + s.getHarvestingExp() + " §6(Level: §a" +
						getLevelForSkill(s.getHarvestingExp(), config.harvestingBaseXP, config.harvestingExpandable) + "§6)\n" +
						"§6Fishing: §a" + s.getFishingExp() + " §6(Level: §a" +
						getLevelForSkill(s.getFishingExp(), config.fishingBaseXP, config.fishingExpandable) + "§6)\n" +
						"§6Crafting: §a" + s.getCraftingExp() + " §6(Level: §a" +
						getLevelForSkill(s.getCraftingExp(), config.craftingBaseXP, config.craftingExpandable) + "§6)\n" +
						"§6Cooking: §a" + s.getCookingExp() + " §6(Level: §a" +
						getLevelForSkill(s.getCookingExp(), config.cookingBaseXP, config.cookingExpandable) + "§6)\n" +
						"§6Smithing: §a" + s.getSmithingExp() + " §6(Level: §a" +
						getLevelForSkill(s.getSmithingExp(), config.smithingBaseXP, config.smithingExpandable) + "§6)\n" +
						"§6Mining: §a" + s.getMiningExp() + " §6(Level: §a" +
						getLevelForSkill(s.getMiningExp(), config.miningBaseXP, config.miningExpandable) + "§6)\n" +
						"§6Alchemy: §a" + s.getAlchemyExp() + " §6(Level: §a" +
						getLevelForSkill(s.getAlchemyExp(), config.alchemyBaseXP, config.alchemyExpandable) + "§6)\n" +
						"§6Enchanting: §a" + s.getEnchantingExp() + " §6(Level: §a" +
						getLevelForSkill(s.getEnchantingExp(), config.enchantingBaseXP, config.enchantingExpandable) + "§6)\n" +
						"§6Combat: §a" + s.getCombatExp() + " §6(Level: §a" +
						getLevelForSkill(s.getCombatExp(), config.combatBaseXP, config.combatExpandable) + "§6)"
		);
	}

	private void registerTickEvents() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			int t = server.getTicks();

			// Agility: Award 1 XP every second for sprinting.
			if (t % 20 == 0) {
				server.getPlayerManager().getPlayerList().forEach(p -> {
					if (p.isSprinting())
						awardSkillXP(p, "agility", 1);
				});

// Combat XP: Tick-based mob scanning with local area checking.
				server.getPlayerManager().getPlayerList().forEach(player -> {
					UUID pid = player.getUuid();
					Map<UUID, String> oldTracking = trackedCombatEntities.computeIfAbsent(pid, k -> new HashMap<>());
					Map<UUID, String> newTracking = new HashMap<>();
					double scanRadius = 20.0D;
					var world = (net.minecraft.server.world.ServerWorld) player.getWorld();
					Box box = new Box(
							player.getX() - scanRadius, player.getY() - scanRadius, player.getZ() - scanRadius,
							player.getX() + scanRadius, player.getY() + scanRadius, player.getZ() + scanRadius
					);
					List<LivingEntity> nearbyMobs = world.getEntitiesByClass(LivingEntity.class, box,
							entity -> entity instanceof Monster);

					// Populate newTracking with mob type AND its coordinates encoded as "mobType|x,y,z".
					for (LivingEntity mob : nearbyMobs) {
						Identifier mobId = Registries.ENTITY_TYPE.getId(mob.getType());
						if (mobId != null) {
							String mobType = mobId.toString().trim().toLowerCase();
							String encoded = mobType + "|" + mob.getX() + "," + mob.getY() + "," + mob.getZ();
							newTracking.put(mob.getUuid(), encoded);
						}
					}

					// Define the awarding radius – only count mobs that were close (within this radius).
					double awardingRadius = 20.0D; // Adjust this value if needed.

					// Check for mobs from oldTracking that no longer appear in newTracking.
					for (UUID oldUUID : new ArrayList<>(oldTracking.keySet())) {
						if (!newTracking.containsKey(oldUUID)) {
							String encoded = oldTracking.get(oldUUID);
							// We expect the string to be in the format "mobType|x,y,z"
							String[] parts = encoded.split("\\|");
							if (parts.length == 2) {
								String mobType = parts[0];
								String[] posParts = parts[1].split(",");
								if (posParts.length == 3) {
									try {
										double lastX = Double.parseDouble(posParts[0]);
										double lastY = Double.parseDouble(posParts[1]);
										double lastZ = Double.parseDouble(posParts[2]);
										// Calculate distance between the player's current location and the mob’s last known position.
										double distanceSq = player.squaredDistanceTo(lastX, lastY, lastZ);
										if (distanceSq <= awardingRadius * awardingRadius) {
											int xpAward = (config.combatMobs != null && config.combatMobs.containsKey(mobType))
													? config.combatMobs.get(mobType)
													: config.combatVanillaXP;
											awardSkillXP(player, "combat", xpAward);
										}
									} catch (NumberFormatException e) {
										LOGGER.error("Error parsing mob position for combat XP: {}", encoded, e);
									}
								}
							}
							oldTracking.remove(oldUUID);
						}
					}
					trackedCombatEntities.put(pid, newTracking);
				});
			}

			// Cooking/Smithing: Handle furnace and smoker outputs.
			server.getPlayerManager().getPlayerList().forEach(player -> {
				if (player instanceof ServerPlayerEntity sp) {
					if (sp.currentScreenHandler instanceof FurnaceScreenHandler || sp.currentScreenHandler instanceof SmokerScreenHandler) {
						ItemStack currentResult = ItemStack.EMPTY;
						if (sp.currentScreenHandler instanceof FurnaceScreenHandler furnaceHandler) {
							currentResult = furnaceHandler.getSlot(2).getStack();
						} else if (sp.currentScreenHandler instanceof SmokerScreenHandler smokerHandler) {
							currentResult = smokerHandler.getSlot(2).getStack();
						}
						UUID pid = sp.getUuid();
						ItemStack lastResult = lastCookingResult.get(pid);
						if (lastResult != null && !lastResult.isEmpty() && currentResult.isEmpty()) {
							Identifier itemId = Registries.ITEM.getId(lastResult.getItem());
							String itemKey = itemId.toString();
							if (config.cookingItems != null && config.cookingItems.containsKey(itemKey)) {
								int xpAward = config.cookingItems.get(itemKey);
								awardSkillXP(sp, "cooking", xpAward);
								LOGGER.debug("Awarded cooking XP: {} for item: {}", xpAward, itemKey);
							} else if (config.smithingItems != null && config.smithingItems.containsKey(itemKey)) {
								int xpAward = config.smithingItems.get(itemKey);
								awardSkillXP(sp, "smithing", xpAward);
								LOGGER.debug("Awarded smithing XP: {} for item: {}", xpAward, itemKey);
							} else {
								awardSkillXP(sp, "cooking", config.cookingVanillaXP);
							}
							lastCookingResult.put(pid, ItemStack.EMPTY);
						} else if (!currentResult.isEmpty() && (lastResult == null || !currentResult.equals(lastResult))) {
							lastCookingResult.put(pid, currentResult.copy());
						}
					}

					// Crafting: Handle crafting output from table or inventory.
					if (sp.currentScreenHandler instanceof CraftingScreenHandler || sp.currentScreenHandler instanceof PlayerScreenHandler) {
						ItemStack currentCraftResult = sp.currentScreenHandler.getSlot(0).getStack();
						UUID pid = sp.getUuid();
						ItemStack lastCraftResult = lastCraftingResult.get(pid);
						if (lastCraftResult != null && !lastCraftResult.isEmpty() && currentCraftResult.isEmpty()) {
							Identifier itemId = Registries.ITEM.getId(lastCraftResult.getItem());
							String itemKey = itemId.toString();
							if (config.craftingItems != null && config.craftingItems.containsKey(itemKey)) {
								int xpAward = config.craftingItems.get(itemKey);
								awardSkillXP(sp, "crafting", xpAward);
								LOGGER.debug("Awarded crafting XP: {} for item: {}", xpAward, itemKey);
							} else {
								awardSkillXP(sp, "crafting", config.craftingVanillaXP);
							}
							lastCraftingResult.put(pid, ItemStack.EMPTY);
						} else if (!currentCraftResult.isEmpty() && (lastCraftResult == null || !currentCraftResult.equals(lastCraftResult))) {
							lastCraftingResult.put(pid, currentCraftResult.copy());
						}
					}

					// Alchemy: Handle brewing stand outputs.
					if (sp.currentScreenHandler instanceof BrewingStandScreenHandler brewHandler) {
						UUID pid = sp.getUuid();
						// For simplicity, check slot 0.
						ItemStack currentBrewResult = brewHandler.getSlot(0).getStack();
						Map<Integer, ItemStack> lastBrewMap = lastBrewingStandResults.computeIfAbsent(pid, k -> new HashMap<>());
						ItemStack lastBrewResult = lastBrewMap.getOrDefault(0, ItemStack.EMPTY);
						if (!lastBrewResult.isEmpty() && currentBrewResult.isEmpty()) {
							Identifier itemId = Registries.ITEM.getId(lastBrewResult.getItem());
							String itemKey = itemId.toString();
							int xpAward = (config.alchemyItems != null && config.alchemyItems.containsKey(itemKey))
									? config.alchemyItems.get(itemKey)
									: config.alchemyVanillaXP;
							awardSkillXP(sp, "alchemy", xpAward);
							LOGGER.debug("Awarded alchemy XP: {} for brewed item: {}", xpAward, itemKey);
							lastBrewMap.put(0, ItemStack.EMPTY);
						} else if (!currentBrewResult.isEmpty() && (lastBrewResult.isEmpty() || !currentBrewResult.equals(lastBrewResult))) {
							lastBrewMap.put(0, currentBrewResult.copy());
						}
					}

					// Enchanting: Handle enchanting table output.
					if (sp.currentScreenHandler instanceof EnchantmentScreenHandler enchantHandler) {
						UUID pid = sp.getUuid();
						ItemStack enchantItem = enchantHandler.getSlot(0).getStack();
						ItemStack lastEnchItem = lastEnchantmentItem.get(pid);
						if (lastEnchItem != null && !lastEnchItem.isEmpty() && enchantItem.isEmpty()) {
							awardSkillXP(sp, "enchanting", config.enchantingXP);
							LOGGER.debug("Awarded enchanting XP: {} on item finish", config.enchantingXP);
							lastEnchantmentItem.put(pid, ItemStack.EMPTY);
						} else if (!enchantItem.isEmpty() && (lastEnchItem == null || !enchantItem.equals(lastEnchItem))) {
							lastEnchantmentItem.put(pid, enchantItem.copy());
						}
					}

					if (sp.getMainHandStack().getItem() instanceof FishingRodItem) {
						// Cast a ray from the player's eyes out to 20 blocks (including fluids).
						HitResult rayResult = sp.raycast(20.0, 1.0F, true);
						if (rayResult.getType() == HitResult.Type.BLOCK) {
							Vec3d hitPos = rayResult.getPos();
							// Convert the Vec3d coordinates to integer block coordinates using floor.
							BlockPos pos = new BlockPos(
									(int) Math.floor(hitPos.x),
									(int) Math.floor(hitPos.y),
									(int) Math.floor(hitPos.z)
							);
							// Check if the target block contains fluid.
							if (!sp.getWorld().getBlockState(pos).getFluidState().isEmpty()) {
								UUID pid = sp.getUuid();
								long lastTime = lastFishingXpTime.getOrDefault(pid, 0L);
								if (t - lastTime >= 20) { // roughly once per second
									awardSkillXP(sp, "fishing", 1);
									LOGGER.debug("Awarded fishing XP");
									lastFishingXpTime.put(pid, (long) t);
								}
							}
						}
					}

				}
			});
		});
	}

	private Map<Item, Integer> getInvCounts(ServerPlayerEntity p) {
		Map<Item, Integer> m = new HashMap<>();
		for (int i = 0; i < p.getInventory().size(); i++) {
			ItemStack s = p.getInventory().getStack(i);
			m.merge(s.getItem(), s.getCount(), Integer::sum);
		}
		return m;
	}

	// UseBlockCallback for farming seeds (unchanged).
	private void registerUseBlockCallback() {
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (!world.isClient()) {
				String id = Registries.ITEM.getId(player.getStackInHand(hand).getItem()).toString();
				if (config.farmingSeeds.containsKey(id))
					awardSkillXP(player, "farming", config.farmingSeeds.get(id));
			}
			return ActionResult.PASS;
		});
	}

	// Campfire: Award cooking XP when a player interacts with a lit campfire while holding raw food.
	private void registerCampfireUseCallback() {
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (!world.isClient() && hitResult instanceof BlockHitResult bhr) {
				BlockPos pos = bhr.getBlockPos();
				BlockState state = world.getBlockState(pos);
				if (state.getBlock() instanceof CampfireBlock && state.get(CampfireBlock.LIT)) {
					String heldItemId = Registries.ITEM.getId(player.getStackInHand(hand).getItem()).toString();
					// Example: if the held item is raw beef, award XP for cooked beef.
					if (heldItemId.equals("minecraft:raw_beef")) {
						int xpAward = (config.cookingItems != null && config.cookingItems.containsKey("minecraft:cooked_beef"))
								? config.cookingItems.get("minecraft:cooked_beef")
								: config.cookingVanillaXP;
						awardSkillXP(player, "cooking", xpAward);
						LOGGER.debug("Awarded campfire cooking XP for raw beef: {}", xpAward);
					}
				}
			}
			return ActionResult.PASS;
		});
	}

	private void registerBlockBreakEvents() {
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
			String id = Registries.BLOCK.getId(state.getBlock()).toString();
			LOGGER.debug("Player {} broke block: {}", player.getUuid(), id);
			if (config.woodcuttingBlocks.containsKey(id))
				awardSkillXP(player, "woodcutting", config.woodcuttingBlocks.get(id));
			if (config.harvestingCrops.containsKey(id)) {
				int xp = config.harvestingCrops.get(id);
				if (state.getBlock() instanceof CropBlock crop) {
					int age = getCropAge(state, crop), max = getMaxAgeForCrop(crop);
					if (age == max)
						awardSkillXP(player, "harvesting", xp);
				} else {
					awardSkillXP(player, "harvesting", xp);
				}
			}
			if (config.miningBlocks.containsKey(id))
				awardSkillXP(player, "mining", config.miningBlocks.get(id));
		});
	}

	private static int getCropAge(BlockState s, CropBlock c) {
		try {
			return s.get(CropBlock.AGE);
		} catch (Exception e) {
			try {
				return s.get(Properties.AGE_7);
			} catch (Exception ex) {
				LOGGER.error("Crop age error, defaulting to 7", ex);
				return 7;
			}
		}
	}

	private static int getMaxAgeForCrop(CropBlock c) {
		try {
			return c.getMaxAge();
		} catch (Exception e) {
			try {
				Method m = CropBlock.class.getDeclaredMethod("getMaxAge");
				m.setAccessible(true);
				return (Integer) m.invoke(c);
			} catch (Exception ex) {
				LOGGER.error("Max age error", ex);
				return 7;
			}
		}
	}

	private static PlayerStats getStatsForPlayer(UUID id) {
		return playerStatsMap.computeIfAbsent(id, pid -> {
			try {
				return loadPlayerStats(pid);
			} catch (Exception e) {
				LOGGER.error("Load error for player {}", pid, e);
				return new PlayerStats();
			}
		});
	}

	private static void savePlayerStats(UUID id, PlayerStats s) throws IOException {
		Path f = CONFIG_DIR.resolve(id.toString() + ".json");
		Files.createDirectories(CONFIG_DIR);
		try (Writer w = Files.newBufferedWriter(f)) {
			GSON.toJson(s, w);
		}
	}

	private static PlayerStats loadPlayerStats(UUID id) throws IOException {
		Path f = CONFIG_DIR.resolve(id.toString() + ".json");
		if (Files.exists(f)) {
			try (Reader r = Files.newBufferedReader(f)) {
				return GSON.fromJson(r, PlayerStats.class);
			}
		}
		return new PlayerStats();
	}

	// Load config with migration logic to fill in missing keys.
	private static void loadConfig() {
		try {
			Files.createDirectories(CONFIG_DIR);
			File configFile = CONFIG_FILE.toFile();
			if (configFile.exists()) {
				try (Reader r = new FileReader(configFile)) {
					Type configType = new TypeToken<SkillConfig>() {}.getType();
					config = GSON.fromJson(r, configType);
				}
				// Migration checks.
				SkillConfig defaultConfig = getDefaultConfig();
				if (config.smithingItems == null) config.smithingItems = defaultConfig.smithingItems;
				if (config.alchemyItems == null) config.alchemyItems = defaultConfig.alchemyItems;
				if (config.craftingItems == null) config.craftingItems = defaultConfig.craftingItems;
				if (config.enchantingXP == 0) config.enchantingXP = defaultConfig.enchantingXP;
			} else {
				config = getDefaultConfig();
				saveConfig();
			}
			LOGGER.info("Config loaded: {}", GSON.toJson(config));
		} catch (IOException e) {
			LOGGER.error("Config load error", e);
			config = getDefaultConfig();
		}
	}

	private static void saveConfig() {
		try {
			Files.createDirectories(CONFIG_DIR);
			try (Writer w = new FileWriter(CONFIG_FILE.toFile())) {
				GSON.toJson(config, w);
			}
		} catch (IOException e) {
			LOGGER.error("Config save error", e);
		}
	}

	private static SkillConfig getDefaultConfig() {
		SkillConfig d = new SkillConfig();
		d.agilityBaseXP = d.woodcuttingBaseXP = d.farmingBaseXP = d.harvestingBaseXP =
				d.fishingBaseXP = d.craftingBaseXP = d.miningBaseXP = d.cookingBaseXP =
						d.smithingBaseXP = d.alchemyBaseXP = d.enchantingBaseXP = 100;
		d.agilityExpandable = d.woodcuttingExpandable = d.farmingExpandable =
				d.harvestingExpandable = d.fishingExpandable = d.craftingExpandable =
						d.miningExpandable = d.cookingExpandable = d.smithingExpandable =
								d.alchemyExpandable = d.enchantingExpandable = true;
		d.agilityVanillaXP = d.woodcuttingVanillaXP = d.farmingVanillaXP =
				d.harvestingVanillaXP = d.fishingVanillaXP = d.craftingVanillaXP =
						d.miningVanillaXP = d.cookingVanillaXP = d.smithingVanillaXP =
								d.alchemyVanillaXP = d.enchantingVanillaXP = 5;
		// New defaults for combat:
		d.combatBaseXP = 100;
		d.combatExpandable = true;
		d.combatVanillaXP = 5;
		d.combatMobs = Map.ofEntries(
				Map.entry("minecraft:zombie", 5),
				Map.entry("minecraft:creeper", 7),
				Map.entry("minecraft:skeleton", 6),
				Map.entry("minecraft:spider", 4),
				Map.entry("minecraft:witch", 10)
		);
		d.farmingSeeds = Map.ofEntries(
				Map.entry("minecraft:wheat_seeds", 5),
				Map.entry("minecraft:beetroot_seeds", 5),
				Map.entry("minecraft:melon_seeds", 10),
				Map.entry("minecraft:pumpkin_seeds", 10),
				Map.entry("minecraft:torchflower_seeds", 15),
				Map.entry("minecraft:pitcher_pod", 15),
				Map.entry("minecraft:potato", 5),
				Map.entry("minecraft:carrot", 5),
				Map.entry("minecraft:nether_wart", 8)
		);
		d.harvestingCrops = Map.ofEntries(
				Map.entry("minecraft:wheat", 10),
				Map.entry("minecraft:beetroot", 10),
				Map.entry("minecraft:carrot", 10),
				Map.entry("minecraft:potato", 10),
				Map.entry("minecraft:melon", 20),
				Map.entry("minecraft:pumpkin", 20),
				Map.entry("minecraft:kelp", 15),
				Map.entry("minecraft:sugar_cane", 15),
				Map.entry("minecraft:cactus", 15),
				Map.entry("minecraft:bamboo", 20),
				Map.entry("minecraft:nether_wart", 8),
				Map.entry("minecraft:chorus_flower", 18)
		);
		d.miningBlocks = Map.ofEntries(
				Map.entry("minecraft:stone", 5),
				Map.entry("minecraft:granite", 5),
				Map.entry("minecraft:andesite", 5),
				Map.entry("minecraft:diorite", 5),
				Map.entry("minecraft:coal_ore", 10),
				Map.entry("minecraft:iron_ore", 15),
				Map.entry("minecraft:gold_ore", 20),
				Map.entry("minecraft:diamond_ore", 50),
				Map.entry("minecraft:emerald_ore", 50),
				Map.entry("minecraft:redstone_ore", 15),
				Map.entry("minecraft:lapis_ore", 15),
				Map.entry("minecraft:deepslate", 5),
				Map.entry("minecraft:copper_ore", 12)
		);
		d.woodcuttingBlocks = Map.ofEntries(
				Map.entry("minecraft:oak_log", 10),
				Map.entry("minecraft:spruce_log", 10),
				Map.entry("minecraft:birch_log", 10),
				Map.entry("minecraft:jungle_log", 15),
				Map.entry("minecraft:dark_oak_log", 20),
				Map.entry("minecraft:acacia_log", 10),
				Map.entry("minecraft:crimson_stem", 30),
				Map.entry("minecraft:warped_stem", 30)
		);
		d.craftingItems = Map.ofEntries(
				Map.entry("minecraft:torch", 2),
				Map.entry("minecraft:wooden_pickaxe", 10),
				Map.entry("minecraft:stone_pickaxe", 15),
				Map.entry("minecraft:iron_pickaxe", 25),
				Map.entry("minecraft:diamond_pickaxe", 40),
				Map.entry("minecraft:wooden_hoe", 10),
				Map.entry("minecraft:stone_hoe", 15),
				Map.entry("minecraft:wooden_axe", 10),
				Map.entry("minecraft:stone_axe", 15),
				Map.entry("minecraft:iron_axe", 25),
				Map.entry("minecraft:bow", 20),
				Map.entry("minecraft:arrow", 1),
				Map.entry("minecraft:crafting_table", 5),
				Map.entry("minecraft:anvil", 35)
		);
		d.cookingItems = Map.ofEntries(
				Map.entry("minecraft:cooked_beef", 5),
				Map.entry("minecraft:cooked_porkchop", 5),
				Map.entry("minecraft:cooked_chicken", 5),
				Map.entry("minecraft:cooked_mutton", 5),
				Map.entry("minecraft:cooked_rabbit", 5),
				Map.entry("minecraft:cooked_cod", 5),
				Map.entry("minecraft:cooked_salmon", 5)
		);
		d.smithingItems = Map.ofEntries(
				Map.entry("minecraft:iron_ingot", 10),
				Map.entry("minecraft:gold_ingot", 15),
				Map.entry("minecraft:copper_ingot", 8)
		);
		d.alchemyBaseXP = 100;
		d.alchemyExpandable = true;
		d.alchemyVanillaXP = 5;
		d.alchemyItems = Map.ofEntries(
				Map.entry("minecraft:potion", 15),
				Map.entry("minecraft:splash_potion", 10),
				Map.entry("minecraft:lingering_potion", 20)
		);
		d.enchantingBaseXP = 100;
		d.enchantingExpandable = true;
		d.enchantingVanillaXP = 5;
		d.enchantingXP = 50;
		return d;
	}

	public static class SkillConfig {
		public int agilityBaseXP, woodcuttingBaseXP, farmingBaseXP, harvestingBaseXP,
				fishingBaseXP, craftingBaseXP, miningBaseXP, cookingBaseXP, smithingBaseXP, alchemyBaseXP, enchantingBaseXP, combatBaseXP;
		public boolean agilityExpandable, woodcuttingExpandable, farmingExpandable,
				harvestingExpandable, fishingExpandable, craftingExpandable, miningExpandable, cookingExpandable, smithingExpandable, alchemyExpandable, enchantingExpandable, combatExpandable;
		public int agilityVanillaXP, woodcuttingVanillaXP, farmingVanillaXP,
				harvestingVanillaXP, fishingVanillaXP, craftingVanillaXP, miningVanillaXP, cookingVanillaXP, smithingVanillaXP, alchemyVanillaXP, enchantingVanillaXP, combatVanillaXP;
		public Map<String, Integer> farmingSeeds, harvestingCrops, miningBlocks, woodcuttingBlocks, craftingItems;
		public Map<String, Integer> cookingItems;
		public Map<String, Integer> smithingItems;
		public Map<String, Integer> alchemyItems;
		public int enchantingXP;
		// NEW: Mapping of mob id to combat XP.
		public Map<String, Integer> combatMobs;
	}

	public static class PlayerStats {
		private int agilityExp, woodcutExp, farmingExp, harvestingExp, fishingExp, craftingExp, miningExp, cookingExp, smithingExp, alchemyExp, enchantingExp;
		// NEW: Combat skill.
		private int combatExp;

		public int getAgilityExp() { return agilityExp; }
		public void addAgilityExp(int a) { agilityExp += a; }
		public int getWoodcutExp() { return woodcutExp; }
		public void addWoodcutExp(int a) { woodcutExp += a; }
		public int getFarmingExp() { return farmingExp; }
		public void addFarmingExp(int a) { farmingExp += a; }
		public int getHarvestingExp() { return harvestingExp; }
		public void addHarvestingExp(int a) { harvestingExp += a; }
		public int getFishingExp() { return fishingExp; }
		public void addFishingExp(int a) { fishingExp += a; }
		public int getCraftingExp() { return craftingExp; }
		public void addCraftingExp(int a) { craftingExp += a; }
		public int getMiningExp() { return miningExp; }
		public void addMiningExp(int a) { miningExp += a; }
		public int getCookingExp() { return cookingExp; }
		public void addCookingExp(int a) { cookingExp += a; }
		public int getSmithingExp() { return smithingExp; }
		public void addSmithingExp(int a) { smithingExp += a; }
		public int getAlchemyExp() { return alchemyExp; }
		public void addAlchemyExp(int a) { alchemyExp += a; }
		public int getEnchantingExp() { return enchantingExp; }
		public void addEnchantingExp(int a) { enchantingExp += a; }
		public int getCombatExp() { return combatExp; }
		public void addCombatExp(int a) { combatExp += a; }
	}
}
