package dev.farmingprofit.client.update;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.farmingprofit.FarmingProfitMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * Compare la version locale à la dernière release GitHub.
 * Un JAR déjà chargé ne peut pas être remplacé à chaud : on notifie + lien,
 * et éventuellement on télécharge le nouveau JAR dans mods/ pour le prochain lancement.
 */
public final class UpdateChecker {
	public static final String GITHUB_REPO = "matteorlt/farming_mod";
	private static final String LATEST_API = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
	private static final String RELEASES_PAGE = "https://github.com/" + GITHUB_REPO + "/releases/latest";

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();
	private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "farmingprofit-update");
		thread.setDaemon(true);
		return thread;
	});

	private volatile Release latest;
	private volatile String lastError;
	private volatile boolean announced;
	private int joinTicks = -1;

	public void onJoin() {
		announced = false;
		joinTicks = 0;
		refresh(false);
	}

	public void tick(Minecraft client) {
		if (joinTicks < 0) {
			return;
		}
		joinTicks++;
		if (joinTicks == 60) {
			if (Minecraft.getInstance().player != null) {
				announceIfNeeded(client);
			} else {
				joinTicks = 40;
			}
		}
	}

	public void refreshNow() {
		announced = false;
		refresh(true);
	}

	public Release latest() {
		return latest;
	}

	public String currentVersion() {
		return FabricLoader.getInstance()
				.getModContainer(FarmingProfitMod.MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("1.0.0");
	}

	public boolean updateAvailable() {
		Release release = latest;
		return release != null && isNewer(release.version(), currentVersion());
	}

	private void refresh(boolean announceImmediately) {
		CompletableFuture.runAsync(() -> fetchLatest(), executor).whenComplete((_, error) -> {
			if (error != null) {
				lastError = error.getMessage();
				FarmingProfitMod.LOGGER.warn("Update check failed: {}", error.toString());
				return;
			}
			if (announceImmediately) {
				Minecraft client = Minecraft.getInstance();
				client.execute(() -> announceIfNeeded(client));
			}
		});
	}

	private void fetchLatest() {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST_API))
					.timeout(Duration.ofSeconds(15))
					.header("User-Agent", "FarmingProfit/" + currentVersion() + " (Minecraft Fabric)")
					.header("Accept", "application/vnd.github+json")
					.GET()
					.build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 404) {
				lastError = "no-release";
				latest = null;
				return;
			}
			if (response.statusCode() != 200) {
				lastError = "HTTP " + response.statusCode();
				return;
			}
			JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
			String tag = json.has("tag_name") ? json.get("tag_name").getAsString() : "";
			String htmlUrl = json.has("html_url") ? json.get("html_url").getAsString() : RELEASES_PAGE;
			String jarUrl = findJarUrl(json.getAsJsonArray("assets"));
			latest = new Release(tag, normalizeVersion(tag), htmlUrl, jarUrl);
			lastError = null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			lastError = e.toString();
			FarmingProfitMod.LOGGER.warn("Update check: {}", e.toString());
		}
	}

	private static String findJarUrl(JsonArray assets) {
		if (assets == null) {
			return null;
		}
		for (JsonElement element : assets) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject asset = element.getAsJsonObject();
			String name = asset.has("name") ? asset.get("name").getAsString() : "";
			String lower = name.toLowerCase(Locale.ROOT);
			if (lower.endsWith(".jar") && !lower.contains("sources") && !lower.contains("dev")) {
				return asset.get("browser_download_url").getAsString();
			}
		}
		return null;
	}

	private void announceIfNeeded(Minecraft client) {
		if (client.player == null || announced) {
			return;
		}
		if ("no-release".equals(lastError)) {
			return;
		}
		if (lastError != null && latest == null) {
			return;
		}
		if (!updateAvailable()) {
			return;
		}
		announced = true;
		Release release = latest;
		client.player.sendSystemMessage(Component.literal("[Farming Profit] Mise à jour " + release.version()
				+ " disponible (actuel " + currentVersion() + ").").withStyle(ChatFormatting.GOLD));

		MutableComponent link = Component.literal("[Télécharger]")
				.withStyle(style -> withLink(style, release.pageUrl()).withColor(ChatFormatting.AQUA).withUnderlined(true));
		MutableComponent command = Component.literal("  [Vérifier]")
				.withStyle(style -> style
						.withClickEvent(new ClickEvent.RunCommand("/fprofit update"))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal("Relance la vérif GitHub")))
						.withColor(ChatFormatting.GRAY)
						.withUnderlined(true));
		client.player.sendSystemMessage(Component.literal("").append(link).append(command));
		client.player.sendSystemMessage(Component.literal("Ferme Minecraft après avoir remplacé le JAR dans mods/.")
				.withStyle(ChatFormatting.YELLOW));
	}

	private static Style withLink(Style style, String url) {
		try {
			return style
					.withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal(url)));
		} catch (Exception e) {
			return style;
		}
	}

	static boolean isNewer(String latestVersion, String currentVersion) {
		int[] latest = parse(latestVersion);
		int[] current = parse(currentVersion);
		int n = Math.max(latest.length, current.length);
		for (int i = 0; i < n; i++) {
			int l = i < latest.length ? latest[i] : 0;
			int c = i < current.length ? current[i] : 0;
			if (l != c) {
				return l > c;
			}
		}
		return false;
	}

	private static int[] parse(String version) {
		String[] parts = normalizeVersion(version).split("[^0-9]+");
		int[] values = new int[Math.max(1, parts.length)];
		int i = 0;
		for (String part : parts) {
			if (part.isEmpty()) {
				continue;
			}
			try {
				values[i++] = Integer.parseInt(part);
			} catch (NumberFormatException ignored) {
			}
		}
		return values;
	}

	private static String normalizeVersion(String tag) {
		String value = tag == null ? "0" : tag.trim();
		if (value.startsWith("v") || value.startsWith("V")) {
			value = value.substring(1);
		}
		return value.isEmpty() ? "0" : value;
	}

	public record Release(String tag, String version, String pageUrl, String jarUrl) {
	}
}
