package dev.farmingprofit.client.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 * Compare la version locale à la dernière release GitHub, puis installe
 * depuis le jeu (download → script détaché → fermeture de Minecraft).
 */
public final class UpdateChecker {
	public static final String GITHUB_REPO = "matteorlt/farming_mod";
	private static final String LATEST_API = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
	private static final String RELEASES_PAGE = "https://github.com/" + GITHUB_REPO + "/releases/latest";

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.ALWAYS)
			.build();
	private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "farmingprofit-update");
		thread.setDaemon(true);
		return thread;
	});

	private volatile Release latest;
	private volatile String lastError;
	private volatile boolean announced;
	private volatile boolean installing;
	private int joinTicks = -1;
	private int quitInTicks = -1;

	public void onJoin() {
		announced = false;
		joinTicks = 0;
		refresh(false);
	}

	public void tick(Minecraft client) {
		if (quitInTicks >= 0) {
			quitInTicks--;
			if (quitInTicks == 0 && client != null) {
				client.stop();
			}
		}
		if (joinTicks < 0) {
			return;
		}
		joinTicks++;
		if (joinTicks == 60) {
			if (Minecraft.getInstance().player != null) {
				announceIfNeeded(client, false);
			} else {
				joinTicks = 40;
			}
		}
	}

	public void refreshNow() {
		refresh(true);
	}

	public void installNow() {
		Minecraft client = Minecraft.getInstance();
		if (installing) {
			tell(client, "Installation déjà en cours…", ChatFormatting.YELLOW);
			return;
		}
		if (UpdateInstaller.development()) {
			tell(client, "Install auto désactivée en environnement de dev (Loom).", ChatFormatting.RED);
			return;
		}
		installing = true;
		tell(client, "Téléchargement de la mise à jour…", ChatFormatting.YELLOW);
		CompletableFuture.runAsync(this::doInstall, executor).whenComplete((_, error) -> {
			if (error != null) {
				installing = false;
				FarmingProfitMod.LOGGER.warn("Install update failed: {}", error.toString());
				Minecraft.getInstance().execute(() -> tell(
						Minecraft.getInstance(),
						"Échec de l’install : " + rootMessage(error),
						ChatFormatting.RED));
			}
		});
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

	private void refresh(boolean fromCommand) {
		CompletableFuture.runAsync(this::fetchLatest, executor).whenComplete((_, error) -> {
			Minecraft client = Minecraft.getInstance();
			client.execute(() -> {
				if (fromCommand) {
					if (error != null) {
						tell(client, "Vérif GitHub échouée : " + rootMessage(error), ChatFormatting.RED);
						return;
					}
					announceFromCommand(client);
				} else if (error == null) {
					announceIfNeeded(client, false);
				}
			});
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
			throw new RuntimeException("Vérif interrompue", e);
		} catch (Exception e) {
			lastError = e.toString();
			FarmingProfitMod.LOGGER.warn("Update check: {}", e.toString());
			throw new RuntimeException(e);
		}
	}

	private void doInstall() {
		if (latest == null || !updateAvailable() || latest.jarUrl() == null) {
			fetchLatest();
		}
		if ("no-release".equals(lastError) || latest == null) {
			throw new IllegalStateException("Pas de release GitHub à installer.");
		}
		if (!updateAvailable()) {
			throw new IllegalStateException("Déjà à jour (" + currentVersion() + ").");
		}
		if (latest.jarUrl() == null || latest.jarUrl().isBlank()) {
			throw new IllegalStateException("La release n’a pas de JAR (asset GitHub manquant).");
		}

		Path pending = UpdateInstaller.pendingFile();
		Path destination = UpdateInstaller.destinationJar(latest.version());
		try {
			Files.deleteIfExists(pending);
			UpdateInstaller.download(http, latest.jarUrl(), pending);
			assertJar(pending);

			List<Path> oldJars = new ArrayList<>(UpdateInstaller.installedJars());
			Path current = UpdateInstaller.currentJar();
			if (current != null) {
				Path normalized = current.toAbsolutePath().normalize();
				if (!containsPath(oldJars, normalized)) {
					oldJars.add(normalized);
				}
			}

			UpdateInstaller.launchSwapAndExit(pending, destination, oldJars);
		} catch (IOException | InterruptedException e) {
			try {
				Files.deleteIfExists(pending);
			} catch (IOException ignored) {
			}
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			throw new RuntimeException(e);
		}

		Minecraft client = Minecraft.getInstance();
		client.execute(() -> {
			tell(client, "Mise à jour " + latest.version() + " téléchargée. Minecraft va se fermer — relance le jeu.",
					ChatFormatting.GREEN);
			quitInTicks = 40;
		});
	}

	private static void assertJar(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			byte[] head = in.readNBytes(4);
			if (head.length < 2 || head[0] != 'P' || head[1] != 'K') {
				Files.deleteIfExists(file);
				throw new IOException("Le fichier téléchargé n’est pas un JAR.");
			}
		}
	}

	private static boolean containsPath(List<Path> paths, Path candidate) {
		for (Path path : paths) {
			if (path.toAbsolutePath().normalize().equals(candidate)) {
				return true;
			}
		}
		return false;
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

	private void announceFromCommand(Minecraft client) {
		if (client.player == null) {
			return;
		}
		if ("no-release".equals(lastError)) {
			tell(client, "Pas encore de release GitHub.", ChatFormatting.YELLOW);
			return;
		}
		if (lastError != null && latest == null) {
			tell(client, "Vérif GitHub échouée : " + lastError, ChatFormatting.RED);
			return;
		}
		if (!updateAvailable()) {
			tell(client, "Déjà à jour (" + currentVersion() + ").", ChatFormatting.GREEN);
			return;
		}
		announceIfNeeded(client, true);
	}

	private void announceIfNeeded(Minecraft client, boolean force) {
		if (client.player == null || (!force && announced)) {
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

		MutableComponent install = Component.literal("[Installer]")
				.withStyle(style -> style
						.withClickEvent(new ClickEvent.RunCommand("/fprofit update install"))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal(
								"Télécharge le JAR, ferme Minecraft, puis relance")))
						.withColor(ChatFormatting.GREEN)
						.withUnderlined(true));
		MutableComponent link = Component.literal("  [Page GitHub]")
				.withStyle(style -> withLink(style, release.pageUrl()).withColor(ChatFormatting.AQUA).withUnderlined(true));
		MutableComponent command = Component.literal("  [Vérifier]")
				.withStyle(style -> style
						.withClickEvent(new ClickEvent.RunCommand("/fprofit update"))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal("Relance la vérif GitHub")))
						.withColor(ChatFormatting.GRAY)
						.withUnderlined(true));
		client.player.sendSystemMessage(Component.literal("").append(install).append(link).append(command));
	}

	private static void tell(Minecraft client, String message, ChatFormatting color) {
		if (client == null || client.player == null) {
			return;
		}
		client.player.sendSystemMessage(Component.literal("[Farming Profit] " + message).withStyle(color));
	}

	private static String rootMessage(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		String message = current.getMessage();
		return message == null || message.isBlank() ? current.toString() : message;
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
