package dev.farmingprofit.client.loadout;

import java.util.Locale;

import dev.farmingprofit.FarmingProfitMod;
import dev.farmingprofit.client.config.ModConfig;
import dev.farmingprofit.client.garden.SkyblockItems;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * Clic droit canne → {@code /loadout} → Pest. Reclic canne en Mode Pest → Farm.
 */
public final class PestLoadoutService {
	private static final int WAIT_MENU_TIMEOUT_TICKS = 80;
	private static final int CLICK_DELAY_TICKS = 4;
	private static final int CLOSE_DELAY_TICKS = 6;
	private static final long RETRIGGER_COOLDOWN_MS = 2500L;

	private enum Phase {
		IDLE, OPEN_MENU, WAIT_MENU, CLICK, CLOSING
	}

	private final ModConfig config;
	private Phase phase = Phase.IDLE;
	private int ticksInPhase;
	private boolean pestMode;
	private boolean wasUseDown;
	private long lastTriggerMs;
	private String pendingTarget = "Pest";

	public PestLoadoutService(ModConfig config) {
		this.config = config;
	}

	public boolean pestMode() {
		return pestMode && config.pestRodLoadout;
	}

	public void setPestMode(boolean pestMode) {
		this.pestMode = pestMode;
	}

	public boolean running() {
		return phase != Phase.IDLE;
	}

	public void cancel() {
		reset();
	}

	public void resetSession() {
		reset();
		pestMode = false;
		wasUseDown = false;
	}

	/** Toggle canne : Pest si inactif, Farm si Mode Pest. */
	public void startFromCommand() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}
		if (running()) {
			chat("Changement de loadout déjà en cours.", ChatFormatting.RED);
			return;
		}
		start(client, client.player, !pestMode);
	}

	public void tick(Minecraft client) {
		if (!config.pestRodLoadout) {
			wasUseDown = client.options.keyUse.isDown();
			if (running()) {
				reset();
			}
			return;
		}

		LocalPlayer player = client.player;
		if (player == null || client.gameMode == null) {
			if (running()) {
				reset();
			}
			wasUseDown = false;
			return;
		}

		if (phase == Phase.IDLE) {
			scanOpenLoadout(client);
		}

		boolean useDown = client.options.keyUse.isDown();
		boolean rising = useDown && !wasUseDown;
		wasUseDown = useDown;
		if (rising && phase == Phase.IDLE && client.screen == null) {
			if (isFishingRod(player.getMainHandItem()) || isFishingRod(player.getOffhandItem())) {
				start(client, player, !pestMode);
			}
		}

		if (!running()) {
			return;
		}

		ticksInPhase++;
		switch (phase) {
			case OPEN_MENU -> {
				if (ticksInPhase >= 2) {
					phase = Phase.WAIT_MENU;
					ticksInPhase = 0;
				}
			}
			case WAIT_MENU -> {
				if (isLoadoutMenu(client) && findTargetSlot(player.containerMenu) >= 0) {
					if (ticksInPhase >= CLICK_DELAY_TICKS) {
						phase = Phase.CLICK;
						ticksInPhase = 0;
					}
				} else if (ticksInPhase >= WAIT_MENU_TIMEOUT_TICKS) {
					chat("Menu loadout / item « " + pendingTarget + " » introuvable.", ChatFormatting.RED);
					reset();
				}
			}
			case CLICK -> {
				if (!isLoadoutMenu(client)) {
					chat("Menu loadout fermé trop tôt.", ChatFormatting.RED);
					reset();
					return;
				}
				int slotId = findTargetSlot(player.containerMenu);
				if (slotId < 0) {
					chat("Loadout « " + pendingTarget + " » introuvable dans le menu.", ChatFormatting.RED);
					reset();
					return;
				}
				client.gameMode.handleContainerInput(
						player.containerMenu.containerId,
						slotId,
						0,
						ContainerInput.PICKUP,
						player
				);
				pestMode = isPestName(pendingTarget);
				FarmingProfitMod.LOGGER.info("Loadout {} left-click slot {}", pendingTarget, slotId);
				phase = Phase.CLOSING;
				ticksInPhase = 0;
			}
			case CLOSING -> {
				if (ticksInPhase >= CLOSE_DELAY_TICKS) {
					if (client.screen != null) {
						client.setScreen(null);
					}
					chat("Loadout " + pendingTarget + " équipé.", ChatFormatting.GREEN);
					reset();
				}
			}
			case IDLE -> {
			}
		}
	}

	private void start(Minecraft client, LocalPlayer player, boolean toPest) {
		long now = System.currentTimeMillis();
		if (now - lastTriggerMs < RETRIGGER_COOLDOWN_MS) {
			return;
		}
		lastTriggerMs = now;
		pendingTarget = toPest ? pestName() : farmName();
		ticksInPhase = 0;
		phase = Phase.OPEN_MENU;
		if (client.screen != null) {
			client.setScreen(null);
		}
		player.connection.sendCommand("loadout");
	}

	private void scanOpenLoadout(Minecraft client) {
		if (!isLoadoutMenu(client) || client.player == null) {
			return;
		}
		String selected = findSelectedLoadoutName(client.player.containerMenu);
		if (selected == null) {
			return;
		}
		pestMode = isPestName(selected);
	}

	private int findTargetSlot(AbstractContainerMenu menu) {
		Inventory inventory = Minecraft.getInstance().player == null ? null : Minecraft.getInstance().player.getInventory();
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.getSlot(i);
			if (inventory != null && slot.container instanceof Inventory) {
				continue;
			}
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) {
				continue;
			}
			if (matchesName(hoverName(stack), pendingTarget)) {
				return slot.index;
			}
		}
		return -1;
	}

	private String findSelectedLoadoutName(AbstractContainerMenu menu) {
		Inventory inventory = Minecraft.getInstance().player == null ? null : Minecraft.getInstance().player.getInventory();
		for (Slot slot : menu.slots) {
			if (inventory != null && slot.container instanceof Inventory) {
				continue;
			}
			ItemStack stack = slot.getItem();
			if (stack.isEmpty() || !looksSelected(stack)) {
				continue;
			}
			String name = hoverName(stack);
			if (!name.isEmpty()) {
				return name;
			}
		}
		return null;
	}

	private static boolean looksSelected(ItemStack stack) {
		ItemLore lore = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
		StringBuilder text = new StringBuilder();
		for (Component line : lore.lines()) {
			text.append(line.getString().toLowerCase(Locale.ROOT)).append('\n');
		}
		String loreText = text.toString();
		if (loreText.contains("click to select") && !loreText.contains("currently")) {
			return false;
		}
		return loreText.contains("selected")
				|| loreText.contains("currently equipped")
				|| loreText.contains("currently selected")
				|| loreText.contains("active loadout")
				|| loreText.contains("equipped");
	}

	private boolean isPestName(String name) {
		return matchesName(name, pestName());
	}

	private static boolean matchesName(String actual, String expected) {
		return strip(actual).equalsIgnoreCase(strip(expected));
	}

	private String pestName() {
		String name = config.pestLoadoutName;
		return name == null || name.isBlank() ? "Pest" : name.trim();
	}

	private String farmName() {
		String name = config.farmLoadoutName;
		return name == null || name.isBlank() ? "Farm" : name.trim();
	}

	private static boolean isLoadoutMenu(Minecraft client) {
		if (!(client.screen instanceof AbstractContainerScreen<?>) || client.screen instanceof InventoryScreen) {
			return false;
		}
		String title = client.screen.getTitle().getString().toLowerCase(Locale.ROOT);
		return title.contains("loadout");
	}

	private static boolean isFishingRod(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		if (stack.getItem() instanceof FishingRodItem) {
			return true;
		}
		String id = SkyblockItems.skyblockId(stack).toUpperCase(Locale.ROOT);
		if (id.contains("FISHING_ROD")) {
			return true;
		}
		String name = strip(stack.getHoverName().getString()).toLowerCase(Locale.ROOT);
		return name.contains("fishing rod");
	}

	private static String hoverName(ItemStack stack) {
		return strip(stack.getHoverName().getString());
	}

	private static String strip(String value) {
		String stripped = ChatFormatting.stripFormatting(value);
		return stripped == null ? "" : stripped.trim();
	}

	private void reset() {
		phase = Phase.IDLE;
		ticksInPhase = 0;
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
}
