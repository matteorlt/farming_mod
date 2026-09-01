package dev.farmingprofit.client.garden;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.PlayerTeam;

/**
 * Lignes du tab Hypixel (widgets inclus, souvent des faux joueurs unlistés).
 */
public final class TabList {
	private TabList() {
	}

	public static List<String> lines(Minecraft client) {
		List<String> lines = new ArrayList<>();
		if (client.player == null || client.player.connection == null) {
			return lines;
		}
		ClientPacketListener connection = client.player.connection;
		Set<PlayerInfo> entries = new LinkedHashSet<>();
		addAll(entries, connection.getListedOnlinePlayers());
		addAll(entries, connection.getOnlinePlayers());
		for (PlayerInfo info : entries) {
			String line = lineOf(info);
			if (!line.isBlank()) {
				lines.add(line);
			}
		}
		return lines;
	}

	private static void addAll(Set<PlayerInfo> target, Collection<PlayerInfo> source) {
		if (source != null) {
			target.addAll(source);
		}
	}

	private static String lineOf(PlayerInfo info) {
		Component display = info.getTabListDisplayName();
		if (display != null) {
			String text = strip(display.getString());
			if (!text.isBlank()) {
				return text;
			}
		}
		String name = info.getProfile().name();
		PlayerTeam team = info.getTeam();
		if (team != null) {
			return strip(team.getPlayerPrefix().getString() + name + team.getPlayerSuffix().getString());
		}
		return strip(name);
	}

	static String strip(String text) {
		if (text == null) {
			return "";
		}
		String stripped = ChatFormatting.stripFormatting(text);
		return stripped == null ? text.trim() : stripped.trim();
	}
}
