package dev.farmingprofit.client.pack;

import java.util.Locale;

import dev.farmingprofit.client.FarmingProfitClient;
import dev.farmingprofit.client.config.ModConfig;
import dev.farmingprofit.client.garden.GardenDetector;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;

/**
 * Accepte le pack serveur Hypixel (obligatoire pour jouer) sans charger ses textures.
 */
public final class ServerPackHider {
	private ServerPackHider() {
	}

	public static boolean enabled() {
		ModConfig config = FarmingProfitClient.config();
		return config != null && config.hideServerResourcePack && isHypixelPack();
	}

	public static boolean shouldHide(ClientboundResourcePackPushPacket packet) {
		ModConfig config = FarmingProfitClient.config();
		if (config == null || !config.hideServerResourcePack) {
			return false;
		}
		if (isHypixelPack()) {
			return true;
		}
		String prompt = packet.prompt().map(component -> component.getString()).orElse("");
		String blob = (packet.url() + " " + prompt).toLowerCase(Locale.ROOT);
		return blob.contains("hypixel") || blob.contains("skyblock");
	}

	public static boolean isHypixelPack() {
		Minecraft client = Minecraft.getInstance();
		if (client.getCurrentServer() != null) {
			String ip = client.getCurrentServer().ip.toLowerCase(Locale.ROOT);
			if (ip.contains("hypixel.net") || ip.contains("hypixel.io")) {
				return true;
			}
		}
		return GardenDetector.onHypixel();
	}
}
