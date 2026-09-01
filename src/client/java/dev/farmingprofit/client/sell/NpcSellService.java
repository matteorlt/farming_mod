package dev.farmingprofit.client.sell;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.farmingprofit.FarmingProfitMod;
import dev.farmingprofit.client.garden.SkyblockItems;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Vide un sack via {@code /gfs}, ouvre {@code /boostercookiemenu}, puis vend
 * uniquement l'item ciblé (1 middle-click par slot, 100 ms d'écart).
 * Répète le cycle N fois ; stop après 3 tours d'affilée sans vente.
 */
public final class NpcSellService {
	private static final int TICK_MS = 50;
	private static final int CLICK_DELAY_TICKS = 2;
	private static final int WAIT_AFTER_GFS_TICKS = 20;
	private static final int WAIT_BETWEEN_ROUNDS_TICKS = 10;
	private static final int WAIT_MENU_TIMEOUT_TICKS = 80;
	private static final int MAX_RECHECKS = 8;
	private static final int MAX_EMPTY_ROUNDS = 3;

	private enum Phase {
		IDLE, WAIT_GFS, OPEN_MENU, WAIT_MENU, SELLING, BETWEEN_ROUNDS
	}

	private Phase phase = Phase.IDLE;
	private String rawQuery = "";
	private String targetId = "";
	private String gfsName = "";
	private int ticksInPhase;
	private final List<Integer> slotsToClick = new ArrayList<>();
	private int nextSlotIndex;
	private int soldCount;
	private int soldThisRound;
	private int recheckCount;
	private int repeatCount = 1;
	private int currentRound = 1;
	private int emptyRounds;

	public boolean running() {
		return phase != Phase.IDLE;
	}

	public void start(String itemQuery) {
		if (itemQuery == null || itemQuery.isBlank()) {
			chat("Donne un nom d'item. Exemple: /fprofit sell enchanted_wheat 5", ChatFormatting.RED);
			return;
		}
		if (running()) {
			chat("Vente déjà en cours. /fprofit sell cancel pour arrêter.", ChatFormatting.RED);
			return;
		}

		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null || client.gameMode == null) {
			chat("Tu dois être en jeu.", ChatFormatting.RED);
			return;
		}

		ParsedStart parsed = parseQuery(itemQuery.trim());
		if (parsed.item().isBlank()) {
			chat("Donne un nom d'item. Exemple: /fprofit sell enchanted_wheat 5", ChatFormatting.RED);
			return;
		}

		rawQuery = parsed.item();
		targetId = normalize(rawQuery);
		gfsName = rawQuery.replace(' ', '_');
		repeatCount = parsed.repeats();
		currentRound = 1;
		emptyRounds = 0;
		soldCount = 0;
		soldThisRound = 0;
		beginRound(client, player, true);
		FarmingProfitMod.LOGGER.info("NPC sell start item={} repeats={}", targetId, repeatCount);
	}

	public void cancel() {
		if (!running()) {
			chat("Aucune vente en cours.", ChatFormatting.GRAY);
			return;
		}
		chat("Vente annulée (" + soldCount + " slot(s), tour " + currentRound + "/" + repeatCount + ").", ChatFormatting.RED);
		reset();
	}

	public void tick(Minecraft client) {
		if (!running()) {
			return;
		}
		LocalPlayer player = client.player;
		if (player == null || client.gameMode == null) {
			chat("Joueur introuvable, vente stoppée.", ChatFormatting.RED);
			reset();
			return;
		}

		ticksInPhase++;
		switch (phase) {
			case WAIT_GFS -> {
				if (ticksInPhase >= WAIT_AFTER_GFS_TICKS) {
					phase = Phase.OPEN_MENU;
					ticksInPhase = 0;
					chat("Ouverture du shop NPC → /boostercookiemenu", ChatFormatting.YELLOW);
					player.connection.sendCommand("boostercookiemenu");
				}
			}
			case OPEN_MENU -> {
				if (ticksInPhase >= 2) {
					phase = Phase.WAIT_MENU;
					ticksInPhase = 0;
					chat("En attente du menu cookie...", ChatFormatting.GRAY);
				}
			}
			case WAIT_MENU -> {
				if (isNpcMenuOpen(client)) {
					collectSlots(player, true);
					if (slotsToClick.isEmpty()) {
						chat("Tour " + currentRound + "/" + repeatCount + " : rien à vendre dans l'inventaire.", ChatFormatting.RED);
						finishRound(client, 0);
						return;
					}
					phase = Phase.SELLING;
					ticksInPhase = 0;
					chat("Tour " + currentRound + "/" + repeatCount + " : " + slotsToClick.size()
							+ " slot(s) (middle-click, " + (CLICK_DELAY_TICKS * TICK_MS) + " ms).", ChatFormatting.GOLD);
				} else if (ticksInPhase >= WAIT_MENU_TIMEOUT_TICKS) {
					chat("Le menu cookie ne s'est pas ouvert. Cookie actif ? Vente annulée.", ChatFormatting.RED);
					reset();
				}
			}
			case SELLING -> {
				if (!isNpcMenuOpen(client)) {
					chat("Menu fermé, vente interrompue après " + soldCount + " slot(s).", ChatFormatting.RED);
					reset();
					return;
				}
				if (ticksInPhase < CLICK_DELAY_TICKS) {
					return;
				}
				ticksInPhase = 0;
				clickNext(client, player);
			}
			case BETWEEN_ROUNDS -> {
				if (ticksInPhase >= WAIT_BETWEEN_ROUNDS_TICKS) {
					beginRound(client, player, false);
				}
			}
			case IDLE -> {
			}
		}
	}

	private void beginRound(Minecraft client, LocalPlayer player, boolean first) {
		ticksInPhase = 0;
		nextSlotIndex = 0;
		soldThisRound = 0;
		recheckCount = 0;
		slotsToClick.clear();
		if (client.screen != null) {
			client.setScreen(null);
		}
		phase = Phase.WAIT_GFS;
		if (first) {
			chat("Sack → /gfs " + gfsName + " 9999  (" + currentRound + "/" + repeatCount + ")", ChatFormatting.YELLOW);
		} else {
			chat("Tour suivant " + currentRound + "/" + repeatCount + " → /gfs " + gfsName + " 9999", ChatFormatting.YELLOW);
		}
		player.connection.sendCommand("gfs " + gfsName + " 9999");
	}

	private void clickNext(Minecraft client, LocalPlayer player) {
		if (nextSlotIndex >= slotsToClick.size()) {
			recheckRemaining(client, player);
			return;
		}

		int slotId = slotsToClick.get(nextSlotIndex++);
		AbstractContainerMenu menu = player.containerMenu;
		if (!menu.isValidSlotIndex(slotId)) {
			chat("Slot " + slotId + " invalide, ignoré.", ChatFormatting.RED);
			return;
		}

		Slot slot = menu.getSlot(slotId);
		ItemStack stack = slot.getItem();
		if (stack.isEmpty() || !matches(stack, targetId)) {
			chat("Slot " + slotId + " n'est plus « " + rawQuery + " », ignoré.", ChatFormatting.GRAY);
			return;
		}

		String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
		int count = stack.getCount();
		client.gameMode.handleContainerInput(menu.containerId, slotId, 2, ContainerInput.CLONE, player);
		soldCount++;
		soldThisRound++;
		chat("Middle-click slot " + slotId + " → " + name + " x" + count, ChatFormatting.AQUA);
	}

	private void recheckRemaining(Minecraft client, LocalPlayer player) {
		collectSlots(player, false);
		if (slotsToClick.isEmpty()) {
			finishRound(client, soldThisRound);
			return;
		}
		recheckCount++;
		if (recheckCount > MAX_RECHECKS) {
			chat("Encore " + slotsToClick.size() + " slot(s) après " + MAX_RECHECKS + " rechecks, on passe au tour suivant.", ChatFormatting.RED);
			finishRound(client, soldThisRound);
			return;
		}
		nextSlotIndex = 0;
		ticksInPhase = 0;
		chat("Recheck : encore " + slotsToClick.size() + " slot(s) de « " + rawQuery + " ».", ChatFormatting.YELLOW);
	}

	private void finishRound(Minecraft client, int soldInRound) {
		if (soldInRound == 0) {
			emptyRounds++;
			chat("Tour " + currentRound + "/" + repeatCount + " : 0 vente (" + emptyRounds + "/" + MAX_EMPTY_ROUNDS + " vides d'affilée).", ChatFormatting.RED);
		} else {
			emptyRounds = 0;
			chat("Tour " + currentRound + "/" + repeatCount + " : " + soldInRound + " slot(s) vendu(s).", ChatFormatting.GREEN);
		}

		if (emptyRounds >= MAX_EMPTY_ROUNDS) {
			chat("Stop : 3 tours d'affilée sans rien vendre. Total : " + soldCount + " slot(s).", ChatFormatting.RED);
			reset();
			return;
		}
		if (currentRound >= repeatCount) {
			chat("Terminé : " + soldCount + " slot(s) au total, " + currentRound + " tour(s).", ChatFormatting.GREEN);
			reset();
			return;
		}

		currentRound++;
		if (client.screen != null) {
			client.setScreen(null);
		}
		phase = Phase.BETWEEN_ROUNDS;
		ticksInPhase = 0;
		chat("Pause avant le tour " + currentRound + "/" + repeatCount + "...", ChatFormatting.GRAY);
	}

	private void collectSlots(LocalPlayer player, boolean logTargets) {
		slotsToClick.clear();
		AbstractContainerMenu menu = player.containerMenu;
		Inventory inventory = player.getInventory();
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.getSlot(i);
			if (!isPlayerInvSlot(slot, inventory)) {
				continue;
			}
			ItemStack stack = slot.getItem();
			if (stack.isEmpty() || !matches(stack, targetId)) {
				continue;
			}
			slotsToClick.add(i);
			if (logTargets) {
				String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
				chat("Cible slot " + i + " : " + name + " x" + stack.getCount(), ChatFormatting.GRAY);
			}
		}
	}

	private static boolean isPlayerInvSlot(Slot slot, Inventory inventory) {
		if (slot.container != inventory) {
			return false;
		}
		int invSlot = slot.getContainerSlot();
		return invSlot >= 0 && invSlot < 36;
	}

	private static boolean isNpcMenuOpen(Minecraft client) {
		return client.screen instanceof AbstractContainerScreen<?> && !(client.screen instanceof InventoryScreen);
	}

	static boolean matches(ItemStack stack, String targetId) {
		if (stack.isEmpty()) {
			return false;
		}
		String skyblockId = normalize(SkyblockItems.skyblockId(stack));
		if (!skyblockId.isEmpty() && skyblockId.equals(targetId)) {
			return true;
		}
		String hover = ChatFormatting.stripFormatting(stack.getHoverName().getString());
		return hover != null && normalize(hover).equals(targetId);
	}

	static String normalize(String value) {
		return value.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
	}

	static ParsedStart parseQuery(String raw) {
		String[] parts = raw.trim().split("\\s+");
		if (parts.length >= 2) {
			try {
				int repeats = Integer.parseInt(parts[parts.length - 1]);
				if (repeats >= 1 && repeats <= 999) {
					String item = String.join(" ", java.util.Arrays.copyOf(parts, parts.length - 1));
					return new ParsedStart(item, repeats);
				}
			} catch (NumberFormatException ignored) {
			}
		}
		return new ParsedStart(raw.trim(), 1);
	}

	private void reset() {
		phase = Phase.IDLE;
		ticksInPhase = 0;
		nextSlotIndex = 0;
		recheckCount = 0;
		soldThisRound = 0;
		emptyRounds = 0;
		slotsToClick.clear();
	}

	private static void chat(String message, ChatFormatting color) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}
		client.player.sendSystemMessage(
				Component.literal("[Farming Profit] " + message).withStyle(color)
		);
	}

	record ParsedStart(String item, int repeats) {
	}
}
