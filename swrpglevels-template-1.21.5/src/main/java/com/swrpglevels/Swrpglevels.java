package com.swrpglevels;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.CropBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.BrewingStandScreenHandler; // For alchemy
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.EnchantmentScreenHandler; // For enchanting
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.SmokerScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Swrpglevels implements ModInitializer {
	public static final String MOD_ID = "swrpglevels";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final Map<UUID, PlayerStats> playerStatsMap = new HashMap<>();
	private static final Path CONFIG_DIR = Path.of("config", MOD_ID), CONFIG_FILE = CONFIG_DIR.resolve("skill_config.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static SkillConfig config;

	private static final Map<UUID, Map<Item, Integer>> prevCraftingInvCounts = new HashMap<>();
	private static final Map<UUID, ItemStack> lastCookingResult = new HashMap<>();
	private static final Map<UUID, Map<Item, Integer>> lastFishCounts = new HashMap<>();
	private static final Map<UUID, Map<Item, Integer>> lastCampfireCookedFishCounts = new HashMap<>();
	// For inventory-based checks using maximum-count tracking:
	private static final Map<UUID, Map<Item, Integer>> maxFurnaceCookedFoodCounts = new HashMap<>();
	private static final Map<UUID, Map<Item, Integer>> maxFurnaceSmithingFoodCounts = new HashMap<>();
	// For tracking brewing stand outputs for Alchemy XP.
	private static final Map<UUID, Map<Integer, ItemStack>> lastBrewingStandResults = new HashMap<>();
	// For tracking changes in the Enchantment screen (slot 0) for Enchanting XP.
	private static final Map<UUID, ItemStack> lastEnchantmentItem = new HashMap<>();

	// Functional interface for updating XP
	private interface XPUpdater {
		int getExp(PlayerStats s);
		void addExp(PlayerStats s, int xp);
		int baseXP();
		boolean expandable();
		int vanillaXP();
	}

	// Map skill string names to updater implementations.
	private static final Map<String, XPUpdater> XP_UPDATERS = new HashMap<>();
	static {
		XP_UPDATERS.put("agility", new XPUpdater() {
			public int getExp(PlayerStats s){ return s.getAgilityExp(); }
			public void addExp(PlayerStats s, int xp){ s.addAgilityExp(xp); }
			public int baseXP(){ return config.agilityBaseXP; }
			public boolean expandable(){ return config.agilityExpandable; }
			public int vanillaXP(){ return config.agilityVanillaXP; }
		});
		XP_UPDATERS.put("woodcutting", new XPUpdater() {
			public int getExp(PlayerStats s){ return s.getWoodcutExp(); }
			public void addExp(PlayerStats s, int xp){ s.addWoodcutExp(xp); }
			public int baseXP(){ return config.woodcuttingBaseXP; }
			public boolean expandable(){ return config.woodcuttingExpandable; }
			public int vanillaXP(){ return config.woodcuttingVanillaXP; }
		});
		XP_UPDATERS.put("farming", new XPUpdater() {
			public int getExp(PlayerStats s){ return s.getFarmingExp(); }
			public void addExp(PlayerStats s, int xp){ s.addFarmingExp(xp); }
			public int baseXP(){ return config.farmingBaseXP; }
			public boolean expandable(){ return config.farmingExpandable; }
			public int vanillaXP(){ return config.farmingVanillaXP; }
		});
		XP_UPDATERS.put("harvesting", new XPUpdater() {
			public int getExp(PlayerStats s){ return s.getHarvestingExp(); }
			public void addExp(PlayerStats s, int xp){ s.addHarvestingExp(xp); }
			public int baseXP(){ return config.harvestingBaseXP; }
			public boolean expandable(){ return config.harvestingExpandable; }
			public int vanillaXP(){ return config.harvestingVanillaXP; }
		});
		XP_UPDATERS.put("fishing", new XPUpdater() {
			public int getExp(PlayerStats s){ return s.getFishingExp(); }
			public void addExp(PlayerStats s, int xp){ s.addFishingExp(xp); }
			public int baseXP(){ return config.fishingBaseXP; }
			public boolean expandable(){ return config.fishingExpandable; }
			public int vanillaXP(){ return config.fishingVanillaXP; }
		});
		XP_UPDATERS.put("crafting", new XPUpdater() {
			public int getExp(PlayerStats s){ return s.getCraftingExp(); }
			public void addExp(PlayerStats s, int xp){ s.addCraftingExp(xp); }
			public int baseXP(){ return config.craftingBaseXP; }
			public boolean expandable(){ return config.craftingExpandable; }
			public int vanillaXP(){ return config.craftingVanillaXP; }
		});
		XP_UPDATERS.put("mining", new XPUpdater() {
			public int getExp(PlayerStats s){ return s.getMiningExp(); }
			public void addExp(PlayerStats s, int xp){ s.addMiningExp(xp); }
			public int baseXP(){ return config.miningBaseXP; }
			public boolean expandable(){ return config.miningExpandable; }
			public int vanillaXP(){ return config.miningVanillaXP; }
		});
		XP_UPDATERS.put("cooking", new XPUpdater() {
			public int getExp(PlayerStats s){ return s.getCookingExp(); }
			public void addExp(PlayerStats s, int xp){ s.addCookingExp(xp); }
			public int baseXP(){ return config.cookingBaseXP; }
			public boolean expandable(){ return config.cookingExpandable; }
			public int vanillaXP(){ return config.cookingVanillaXP; }
		});
		// XP updater for smithing.
		XP_UPDATERS.put("smithing", new XPUpdater() {
			public int getExp(PlayerStats s){ return s.getSmithingExp(); }
			public void addExp(PlayerStats s, int xp){ s.addSmithingExp(xp); }
			public int baseXP(){ return config.smithingBaseXP; }
			public boolean expandable(){ return config.smithingExpandable; }
			public int vanillaXP(){ return config.smithingVanillaXP; }
		});
		// XP updater for alchemy.
		XP_UPDATERS.put("alchemy", new XPUpdater() {
			public int getExp(PlayerStats s){ return s.getAlchemyExp(); }
			public void addExp(PlayerStats s, int xp){ s.addAlchemyExp(xp); }
			public int baseXP(){ return config.alchemyBaseXP; }
			public boolean expandable(){ return config.alchemyExpandable; }
			public int vanillaXP(){ return config.alchemyVanillaXP; }
		});
		// XP updater for enchanting.
		XP_UPDATERS.put("enchanting", new XPUpdater() {
			public int getExp(PlayerStats s){ return s.getEnchantingExp(); }
			public void addExp(PlayerStats s, int xp){ s.addEnchantingExp(xp); }
			public int baseXP(){ return config.enchantingBaseXP; }
			public boolean expandable(){ return config.enchantingExpandable; }
			public int vanillaXP(){ return config.enchantingVanillaXP; }
		});
	}

	// Standard leveling formula.
	private static int getLevelForSkill(int xp, int baseXP, boolean expandable) {
		return expandable ? (int)Math.floor((Math.sqrt(8.0 * xp / baseXP + 1) - 1) / 2) : xp / baseXP;
	}

	// Award XP using our updater mapping.
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
			sp.getWorld().playSound(null, sp.getBlockPos(), SoundEvents.ENTITY_PLAYER_LEVELUP, sp.getSoundCategory(), 1.0F, 1.0F);
			sp.sendMessage(Text.literal(skill + " leveled up to " + newLevel + "!").formatted(Formatting.GREEN), true);
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
		registerBlockBreakEvents();
		LOGGER.info("Mod initialization complete!");
	}

	private void registerPlayerEvents() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			UUID id = handler.player.getUuid();
			getStatsForPlayer(id);
			prevCraftingInvCounts.put(id, new HashMap<>());
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID id = handler.player.getUuid();
			PlayerStats s = playerStatsMap.get(id);
			if (s != null)
				try {
					savePlayerStats(id, s);
				} catch(IOException e){
					LOGGER.error("Error saving stats for player {}", id, e);
				}
			prevCraftingInvCounts.remove(id);
			lastCookingResult.remove(id);
			lastFishCounts.remove(id);
			lastCampfireCookedFishCounts.remove(id);
			maxFurnaceCookedFoodCounts.remove(id);
			maxFurnaceSmithingFoodCounts.remove(id);
			lastBrewingStandResults.remove(id);
			lastEnchantmentItem.remove(id);
		});
	}

	private void registerCommands() {
		// Command now uses "/stats" to display skill statistics.
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
				"§6Agility: §a" + s.getAgilityExp() + " §6(Level: §a" + getLevelForSkill(s.getAgilityExp(), config.agilityBaseXP, config.agilityExpandable) + "§6)\n" +
						"§6Woodcutting: §a" + s.getWoodcutExp() + " §6(Level: §a" + getLevelForSkill(s.getWoodcutExp(), config.woodcuttingBaseXP, config.woodcuttingExpandable) + "§6)\n" +
						"§6Farming: §a" + s.getFarmingExp() + " §6(Level: §a" + getLevelForSkill(s.getFarmingExp(), config.farmingBaseXP, config.farmingExpandable) + "§6)\n" +
						"§6Harvesting: §a" + s.getHarvestingExp() + " §6(Level: §a" + getLevelForSkill(s.getHarvestingExp(), config.harvestingBaseXP, config.harvestingExpandable) + "§6)\n" +
						"§6Fishing: §a" + s.getFishingExp() + " §6(Level: §a" + getLevelForSkill(s.getFishingExp(), config.fishingBaseXP, config.fishingExpandable) + "§6)\n" +
						"§6Crafting: §a" + s.getCraftingExp() + " §6(Level: §a" + getLevelForSkill(s.getCraftingExp(), config.craftingBaseXP, config.craftingExpandable) + "§6)\n" +
						"§6Cooking: §a" + s.getCookingExp() + " §6(Level: §a" + getLevelForSkill(s.getCookingExp(), config.cookingBaseXP, config.cookingExpandable) + "§6)\n" +
						"§6Smithing: §a" + s.getSmithingExp() + " §6(Level: §a" + getLevelForSkill(s.getSmithingExp(), config.smithingBaseXP, config.smithingExpandable) + "§6)\n" +
						"§6Mining: §a" + s.getMiningExp() + " §6(Level: §a" + getLevelForSkill(s.getMiningExp(), config.miningBaseXP, config.miningExpandable) + "§6)\n" +
						"§6Alchemy: §a" + s.getAlchemyExp() + " §6(Level: §a" + getLevelForSkill(s.getAlchemyExp(), config.alchemyBaseXP, config.alchemyExpandable) + "§6)\n" +
						"§6Enchanting: §a" + s.getEnchantingExp() + " §6(Level: §a" + getLevelForSkill(s.getEnchantingExp(), config.enchantingBaseXP, config.enchantingExpandable) + "§6)"
		);
	}

	private void registerTickEvents() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			int t = server.getTicks();

			// Agility XP (sprinting)
			if (t % 20 == 0)
				server.getPlayerManager().getPlayerList().forEach(p -> {
					if (p.isSprinting())
						awardSkillXP(p, "agility", 1);
				});

			// Crafting XP check
			if (t % 2 == 0) {
				server.getPlayerManager().getPlayerList().forEach(p -> {
					if (p.currentScreenHandler instanceof CraftingScreenHandler ||
							p.currentScreenHandler instanceof PlayerScreenHandler ||
							p.currentScreenHandler instanceof AnvilScreenHandler) {
						Map<Item, Integer> cur = getInvCounts(p), prev = prevCraftingInvCounts.get(p.getUuid());
						if (prev != null)
							cur.forEach((i, cnt) -> {
								int d = cnt - prev.getOrDefault(i, 0);
								if (d > 0 && config.craftingItems.containsKey(Registries.ITEM.getId(i).toString()))
									awardSkillXP(p, "crafting", config.craftingItems.get(Registries.ITEM.getId(i).toString()) * d);
							});
						prevCraftingInvCounts.put(p.getUuid(), cur);
					} else {
						prevCraftingInvCounts.remove(p.getUuid());
					}
				});
			}

			// Furnace/Smoker UI check (active interaction)
			if (t % 2 == 0) {
				server.getPlayerManager().getPlayerList().forEach(p -> {
					if (p.currentScreenHandler instanceof FurnaceScreenHandler ||
							p.currentScreenHandler instanceof SmokerScreenHandler) {
						int o = 2;
						if (!p.currentScreenHandler.slots.isEmpty() && p.currentScreenHandler.getSlot(o) != null) {
							ItemStack cur = p.currentScreenHandler.getSlot(o).getStack();
							ItemStack last = lastCookingResult.get(p.getUuid());
							if (last != null && !last.isEmpty()) {
								String lastItemId = Registries.ITEM.getId(last.getItem()).toString();
								// Check for cooking items.
								if (config.cookingItems != null && config.cookingItems.containsKey(lastItemId)) {
									int multiplier = config.cookingItems.get(lastItemId);
									int curCount = cur.isEmpty() ? 0 : cur.getCount();
									if (last.getCount() > curCount) {
										int delta = last.getCount() - curCount;
										awardSkillXP(p, "cooking", multiplier * delta);
									}
									// Check for smithing items.
								} else if (config.smithingItems != null && config.smithingItems.containsKey(lastItemId)) {
									int multiplier = config.smithingItems.get(lastItemId);
									int curCount = cur.isEmpty() ? 0 : cur.getCount();
									if (last.getCount() > curCount) {
										int delta = last.getCount() - curCount;
										awardSkillXP(p, "smithing", multiplier * delta);
									}
								}
							}
							lastCookingResult.put(p.getUuid(), cur.copy());
						}
					} else {
						lastCookingResult.remove(p.getUuid());
					}
				});
			}

			// Campfire check for cooked fish (cod and salmon)
			if (t % 20 == 0) {
				server.getPlayerManager().getPlayerList().forEach(p -> {
					if (!(p.currentScreenHandler instanceof FurnaceScreenHandler || p.currentScreenHandler instanceof SmokerScreenHandler)) {
						Map<Item, Integer> cur = new HashMap<>();
						for (int i = 0; i < p.getInventory().size(); i++) {
							ItemStack s = p.getInventory().getStack(i);
							if (s.getItem() == Items.COOKED_COD || s.getItem() == Items.COOKED_SALMON)
								cur.merge(s.getItem(), s.getCount(), Integer::sum);
						}
						if (isNearCampfire(p)) {
							Map<Item, Integer> prev = lastCampfireCookedFishCounts.get(p.getUuid());
							if (prev != null)
								cur.forEach((i, cnt) -> {
									int d = cnt - prev.getOrDefault(i, 0);
									if (d > 0)
										awardSkillXP(p, "cooking", 7 * d);
								});
							lastCampfireCookedFishCounts.put(p.getUuid(), cur);
						} else {
							lastCampfireCookedFishCounts.remove(p.getUuid());
						}
					}
				});
			}

			// Inventory-based furnace/smoker check using max count method (split for cooking and smithing)
			if (t % 20 == 0) {
				server.getPlayerManager().getPlayerList().forEach(p -> {
					if (!(p.currentScreenHandler instanceof FurnaceScreenHandler || p.currentScreenHandler instanceof SmokerScreenHandler)) {
						if (!isNearCampfire(p)) {
							// Build separate maps for cooking and smithing items from inventory.
							Map<Item, Integer> currentCooking = new HashMap<>();
							Map<Item, Integer> currentSmithing = new HashMap<>();
							for (int i = 0; i < p.getInventory().size(); i++) {
								ItemStack s = p.getInventory().getStack(i);
								String itemId = Registries.ITEM.getId(s.getItem()).toString();
								if (config.cookingItems != null && config.cookingItems.containsKey(itemId))
									currentCooking.merge(s.getItem(), s.getCount(), Integer::sum);
								if (config.smithingItems != null && config.smithingItems.containsKey(itemId))
									currentSmithing.merge(s.getItem(), s.getCount(), Integer::sum);
							}
							// Process cooking items.
							Map<Item, Integer> maxCooking = maxFurnaceCookedFoodCounts.computeIfAbsent(p.getUuid(), k -> new HashMap<>());
							currentCooking.forEach((item, cnt) -> {
								int baseline = maxCooking.getOrDefault(item, 0);
								if (cnt > baseline) {
									int delta = cnt - baseline;
									String itemId = Registries.ITEM.getId(item).toString();
									int multiplier = config.cookingItems.get(itemId);
									awardSkillXP(p, "cooking", multiplier * delta);
									maxCooking.put(item, cnt);
								}
							});
							// Process smithing items.
							Map<Item, Integer> maxSmithing = maxFurnaceSmithingFoodCounts.computeIfAbsent(p.getUuid(), k -> new HashMap<>());
							currentSmithing.forEach((item, cnt) -> {
								int baseline = maxSmithing.getOrDefault(item, 0);
								if (cnt > baseline) {
									int delta = cnt - baseline;
									String itemId = Registries.ITEM.getId(item).toString();
									int multiplier = config.smithingItems.get(itemId);
									awardSkillXP(p, "smithing", multiplier * delta);
									maxSmithing.put(item, cnt);
								}
							});
						}
					}
				});
			}

			// Fishing XP check
			if (t % 2 == 0) {
				server.getPlayerManager().getPlayerList().forEach(p -> {
					if (p.getMainHandStack().getItem().equals(Items.FISHING_ROD) ||
							p.getOffHandStack().getItem().equals(Items.FISHING_ROD)) {
						Map<Item, Integer> cur = new HashMap<>();
						for (int i = 0; i < p.getInventory().size(); i++) {
							ItemStack s = p.getInventory().getStack(i);
							if (isFishItem(s.getItem()))
								cur.merge(s.getItem(), s.getCount(), Integer::sum);
						}
						Map<Item, Integer> prev = lastFishCounts.get(p.getUuid());
						if (prev != null)
							cur.forEach((i, cnt) -> {
								int d = cnt - prev.getOrDefault(i, 0);
								if (d > 0)
									awardSkillXP(p, "fishing", 10 * d);
							});
						lastFishCounts.put(p.getUuid(), cur);
					}
				});
			}

			// Brewing Stand (Alchemy) UI check (active interaction)
			if (t % 2 == 0) {
				server.getPlayerManager().getPlayerList().forEach(p -> {
					if (p.currentScreenHandler instanceof BrewingStandScreenHandler) {
						Map<Integer, ItemStack> lastMap = lastBrewingStandResults.computeIfAbsent(p.getUuid(), k -> new HashMap<>());
						// Assume output slots are at indices 1, 2, and 3.
						for (int slotIndex = 1; slotIndex <= 3; slotIndex++) {
							if (p.currentScreenHandler.getSlot(slotIndex) != null) {
								ItemStack curStack = p.currentScreenHandler.getSlot(slotIndex).getStack();
								ItemStack lastStack = lastMap.get(slotIndex);
								if (lastStack != null && !lastStack.isEmpty()) {
									int lastCount = lastStack.getCount();
									int curCount = curStack.isEmpty() ? 0 : curStack.getCount();
									if (lastCount > curCount) { // Player removed brewed potions
										int delta = lastCount - curCount;
										String itemId = Registries.ITEM.getId(lastStack.getItem()).toString();
										if (config.alchemyItems != null && config.alchemyItems.containsKey(itemId)) {
											int multiplier = config.alchemyItems.get(itemId);
											awardSkillXP(p, "alchemy", multiplier * delta);
										}
									}
								}
								// Store the current state for comparison next tick
								lastMap.put(slotIndex, curStack.copy());
							}
						}
					} else {
						lastBrewingStandResults.remove(p.getUuid());
					}
				});
			}

			// Enchanting XP check
			if (t % 2 == 0) {
				server.getPlayerManager().getPlayerList().forEach(p -> {
					if (p.currentScreenHandler instanceof EnchantmentScreenHandler) {
						if (p.currentScreenHandler.getSlot(0) != null) {
							ItemStack current = p.currentScreenHandler.getSlot(0).getStack();
							ItemStack last = lastEnchantmentItem.get(p.getUuid());
							if (last != null && !last.isEmpty() && !current.isEmpty()) {
								boolean lastEnchanted = last.hasEnchantments();
								boolean currentEnchanted = current.hasEnchantments();
								// If the item in slot 0 becomes enchanted (or its enchantments change)
								if ((!lastEnchanted && currentEnchanted) ||
										(lastEnchanted && currentEnchanted && !last.getEnchantments().equals(current.getEnchantments()))) {
									awardSkillXP(p, "enchanting", config.enchantingXP);
								}
							}
							lastEnchantmentItem.put(p.getUuid(), current.copy());
						}
					} else {
						lastEnchantmentItem.remove(p.getUuid());
					}
				});
			}
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

	private void registerUseBlockCallback() {
		UseBlockCallback.EVENT.register((p, w, h, hr) -> {
			if (!w.isClient()) {
				String id = Registries.ITEM.getId(p.getStackInHand(h).getItem()).toString();
				if (config.farmingSeeds.containsKey(id))
					awardSkillXP(p, "farming", config.farmingSeeds.get(id));
			}
			return ActionResult.PASS;
		});
	}

	private void registerBlockBreakEvents() {
		PlayerBlockBreakEvents.AFTER.register((w, p, pos, s, be) -> {
			String id = Registries.BLOCK.getId(s.getBlock()).toString();
			LOGGER.debug("Player {} broke block: {}", p.getUuid(), id);
			if (config.woodcuttingBlocks.containsKey(id))
				awardSkillXP(p, "woodcutting", config.woodcuttingBlocks.get(id));
			if (config.harvestingCrops.containsKey(id)) {
				int xp = config.harvestingCrops.get(id);
				if (s.getBlock() instanceof CropBlock crop) {
					int age = getCropAge(s, crop), max = getMaxAgeForCrop(crop);
					if (age == max)
						awardSkillXP(p, "harvesting", xp);
				} else {
					awardSkillXP(p, "harvesting", xp);
				}
			}
			if (config.miningBlocks.containsKey(id))
				awardSkillXP(p, "mining", config.miningBlocks.get(id));
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

	private static boolean isNearCampfire(ServerPlayerEntity p) {
		BlockPos pos = p.getBlockPos();
		for (int x = -2; x <= 2; x++)
			for (int y = -1; y <= 1; y++)
				for (int z = -2; z <= 2; z++){
					BlockPos cp = pos.add(x, y, z);
					BlockState st = p.getWorld().getBlockState(cp);
					if (st.getBlock() instanceof CampfireBlock && st.get(CampfireBlock.LIT))
						return true;
				}
		return false;
	}

	private static boolean isFishItem(Item i) {
		return i == Items.COD || i == Items.SALMON || i == Items.TROPICAL_FISH || i == Items.PUFFERFISH;
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

	private static void loadConfig() {
		try {
			Files.createDirectories(CONFIG_DIR);
			if (Files.exists(CONFIG_FILE)) {
				try (Reader r = Files.newBufferedReader(CONFIG_FILE)) {
					config = GSON.fromJson(r, SkillConfig.class);
				}
				if (config.miningBlocks == null)
					config.miningBlocks = getDefaultConfig().miningBlocks;
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

	private static void saveConfig(){
		try {
			Files.createDirectories(CONFIG_DIR);
			try (Writer w = Files.newBufferedWriter(CONFIG_FILE)) {
				GSON.toJson(config, w);
			}
		} catch (IOException e) {
			LOGGER.error("Config save error", e);
		}
	}

	private static SkillConfig getDefaultConfig(){
		SkillConfig d = new SkillConfig();
		d.agilityBaseXP = d.woodcuttingBaseXP = d.farmingBaseXP = d.harvestingBaseXP =
				d.fishingBaseXP = d.craftingBaseXP = d.miningBaseXP = d.cookingBaseXP = d.smithingBaseXP = d.alchemyBaseXP = d.enchantingBaseXP = 100;
		d.agilityExpandable = d.woodcuttingExpandable = d.farmingExpandable =
				d.harvestingExpandable = d.fishingExpandable = d.craftingExpandable = d.miningExpandable =
						d.cookingExpandable = d.smithingExpandable = d.alchemyExpandable = d.enchantingExpandable = true;
		d.agilityVanillaXP = d.woodcuttingVanillaXP = d.farmingVanillaXP =
				d.harvestingVanillaXP = d.fishingVanillaXP = d.craftingVanillaXP = d.miningVanillaXP =
						d.cookingVanillaXP = d.smithingVanillaXP = d.alchemyVanillaXP = d.enchantingVanillaXP = 5;
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
		// Default cooking items for furnace/smoker XP.
		d.cookingItems = Map.ofEntries(
				Map.entry("minecraft:cooked_beef", 5),
				Map.entry("minecraft:cooked_porkchop", 5),
				Map.entry("minecraft:cooked_chicken", 5),
				Map.entry("minecraft:cooked_mutton", 5),
				Map.entry("minecraft:cooked_rabbit", 5),
				Map.entry("minecraft:cooked_cod", 5),
				Map.entry("minecraft:cooked_salmon", 5)
		);
		// Default smithing items (smelted bars) for furnace/smoker XP.
		d.smithingItems = Map.ofEntries(
				Map.entry("minecraft:iron_ingot", 10),
				Map.entry("minecraft:gold_ingot", 15),
				Map.entry("minecraft:copper_ingot", 8)
		);
		// Default alchemy config for brewing potions.
		d.alchemyBaseXP = 100;
		d.alchemyExpandable = true;
		d.alchemyVanillaXP = 5;
		d.alchemyItems = Map.ofEntries(
				Map.entry("minecraft:potion", 15),
				Map.entry("minecraft:splash_potion", 10),
				Map.entry("minecraft:lingering_potion", 20)
		);
		// Default enchanting config.
		d.enchantingBaseXP = 100;
		d.enchantingExpandable = true;
		d.enchantingVanillaXP = 5;
		d.enchantingXP = 50; // XP awarded each time an item is enchanted
		return d;
	}

	public static class SkillConfig {
		public int agilityBaseXP, woodcuttingBaseXP, farmingBaseXP, harvestingBaseXP,
				fishingBaseXP, craftingBaseXP, miningBaseXP, cookingBaseXP, smithingBaseXP, alchemyBaseXP, enchantingBaseXP;
		public boolean agilityExpandable, woodcuttingExpandable, farmingExpandable,
				harvestingExpandable, fishingExpandable, craftingExpandable, miningExpandable, cookingExpandable, smithingExpandable, alchemyExpandable, enchantingExpandable;
		public int agilityVanillaXP, woodcuttingVanillaXP, farmingVanillaXP,
				harvestingVanillaXP, fishingVanillaXP, craftingVanillaXP, miningVanillaXP, cookingVanillaXP, smithingVanillaXP, alchemyVanillaXP, enchantingVanillaXP;
		public Map<String, Integer> farmingSeeds, harvestingCrops, miningBlocks, woodcuttingBlocks, craftingItems;
		public Map<String, Integer> cookingItems;
		public Map<String, Integer> smithingItems;
		public Map<String, Integer> alchemyItems;
		public int enchantingXP; // XP awarded per enchanting action
	}

	public static class PlayerStats {
		private int agilityExp, woodcutExp, farmingExp, harvestingExp, fishingExp, craftingExp, miningExp, cookingExp, smithingExp, alchemyExp, enchantingExp;
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
	}
}
