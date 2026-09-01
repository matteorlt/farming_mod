package dev.farmingprofit.client.update;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.farmingprofit.FarmingProfitMod;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Télécharge le JAR dans {@code mods/} puis lance un process détaché
 * (comme libautoupdate / ModUpdater) pour swapper après la fermeture,
 * parce que Windows verrouille le JAR chargé.
 */
public final class UpdateInstaller {
	private static final String PENDING_NAME = "farmingprofit-update.tmp";

	private UpdateInstaller() {
	}

	public static Path modsDir() {
		return FabricLoader.getInstance().getGameDir().resolve("mods");
	}

	public static boolean development() {
		return FabricLoader.getInstance().isDevelopmentEnvironment();
	}

	public static Path currentJar() {
		try {
			var source = FarmingProfitMod.class.getProtectionDomain().getCodeSource();
			if (source == null || source.getLocation() == null) {
				return null;
			}
			Path path = Path.of(source.getLocation().toURI());
			if (Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
				return path;
			}
		} catch (Exception e) {
			FarmingProfitMod.LOGGER.warn("JAR courant introuvable: {}", e.toString());
		}
		return null;
	}

	public static List<Path> installedJars() {
		List<Path> jars = new ArrayList<>();
		Path mods = modsDir();
		if (!Files.isDirectory(mods)) {
			return jars;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(mods, "farmingprofit*.jar")) {
			for (Path path : stream) {
				String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
				if (name.contains("sources") || name.contains("-dev")) {
					continue;
				}
				jars.add(path.toAbsolutePath().normalize());
			}
		} catch (IOException e) {
			FarmingProfitMod.LOGGER.warn("Scan mods/ : {}", e.toString());
		}
		return jars;
	}

	public static void download(HttpClient http, String jarUrl, Path destination) throws IOException, InterruptedException {
		Files.createDirectories(destination.getParent());
		HttpRequest request = HttpRequest.newBuilder(URI.create(jarUrl))
				.timeout(Duration.ofMinutes(2))
				.header("User-Agent", "FarmingProfit-Updater")
				.GET()
				.build();
		HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(destination));
		if (response.statusCode() / 100 != 2) {
			Files.deleteIfExists(destination);
			throw new IOException("Téléchargement HTTP " + response.statusCode());
		}
		if (Files.size(destination) < 1024) {
			Files.deleteIfExists(destination);
			throw new IOException("Fichier trop petit, téléchargement invalide.");
		}
	}

	public static Path pendingFile() {
		return modsDir().resolve(PENDING_NAME);
	}

	public static Path destinationJar(String version) {
		return modsDir().resolve("farmingprofit-" + version + ".jar");
	}

	public static void launchSwapAndExit(Path pending, Path destination, List<Path> oldJars) throws IOException {
		boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
		if (windows) {
			launchWindows(pending, destination, oldJars);
		} else {
			launchUnix(pending, destination, oldJars);
		}
	}

	private static void launchWindows(Path pending, Path dest, List<Path> oldJars) throws IOException {
		Path script = modsDir().resolve("farmingprofit-install.bat");
		StringBuilder bat = new StringBuilder();
		bat.append("@echo off\r\n");
		bat.append("ping 127.0.0.1 -n 6 > NUL\r\n");
		for (Path old : oldJars) {
			bat.append("del /f /q ").append(winQuote(old)).append("\r\n");
		}
		bat.append("move /y ").append(winQuote(pending)).append(" ").append(winQuote(dest)).append("\r\n");
		bat.append("del /f /q ").append(winQuote(script)).append("\r\n");
		Files.writeString(script, bat.toString(), StandardCharsets.UTF_8);

		new ProcessBuilder("cmd.exe", "/c",
				"start \"fp-upd\" /min \"" + script.toAbsolutePath().normalize() + "\"")
				.directory(modsDir().toFile())
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.start();
	}

	private static void launchUnix(Path pending, Path dest, List<Path> oldJars) throws IOException {
		Path script = modsDir().resolve("farmingprofit-install.sh");
		StringBuilder sh = new StringBuilder();
		sh.append("#!/bin/sh\n");
		sh.append("sleep 6\n");
		for (Path old : oldJars) {
			sh.append("rm -f ").append(shQuote(old)).append("\n");
		}
		sh.append("mv -f ").append(shQuote(pending)).append(" ").append(shQuote(dest)).append("\n");
		sh.append("rm -f ").append(shQuote(script)).append("\n");
		Files.writeString(script, sh.toString(), StandardCharsets.UTF_8);
		script.toFile().setExecutable(true);
		new ProcessBuilder("/bin/sh", "-c", "nohup " + shQuote(script) + " >/dev/null 2>&1 &")
				.directory(modsDir().toFile())
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.start();
	}

	private static String winQuote(Path path) {
		return "\"" + path.toAbsolutePath().normalize() + "\"";
	}

	private static String shQuote(Path path) {
		return "'" + path.toAbsolutePath().normalize().toString().replace("'", "'\\''") + "'";
	}
}
