package dev.farmingprofit.client.garden;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.farmingprofit.client.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * Lit {@code Cooldown: 1m 58s} dans le widget Pests du tab, puis déclenche
 * l’alerte quand il reste moins de N secondes.
 */
public final class PestCooldownTracker {
	private static final Pattern COOLDOWN = Pattern.compile("(?i)^\\s*Cooldown:\\s*(.+?)\\s*$");
	private static final Pattern HOURS = Pattern.compile("(\\d+)\\s*h", Pattern.CASE_INSENSITIVE);
	private static final Pattern MINUTES = Pattern.compile("(\\d+)\\s*m", Pattern.CASE_INSENSITIVE);
	private static final Pattern SECONDS = Pattern.compile("(\\d+)\\s*s", Pattern.CASE_INSENSITIVE);

	private static final int[] MELODY_TICKS = {0, 4, 8, 12};
	private static final float[] MELODY_PITCHES = {0.7f, 0.9f, 1.2f, 1.6f};

	private long endsAtMs;
	private State state = State.UNKNOWN;
	private boolean alertArmed = true;
	private int lastBeepSecond = -1;
	private int melodyTicks = -1;
	private boolean seenCooldownLine;

	public enum State {
		UNKNOWN, TIMING, READY, MAX
	}

	public void tick(Minecraft client, ModConfig config) {
		if (!config.pestCooldownAlert || client.player == null) {
			resetAlertPlayback();
			return;
		}
		if (!GardenDetector.inGarden()) {
			reset();
			return;
		}

		parseTab(client);

		int remaining = remainingSeconds();
		int threshold = Math.max(1, config.pestCooldownAlertSeconds);
		if (state != State.TIMING || remaining <= 0 || remaining > threshold) {
			if (remaining > threshold || state == State.READY || state == State.MAX) {
				alertArmed = true;
				lastBeepSecond = -1;
			}
			if (melodyTicks >= 0 && (state != State.TIMING || remaining <= 0)) {
				melodyTicks = -1;
			}
			advanceMelody(client);
			return;
		}

		if (alertArmed) {
			alertArmed = false;
			startMelody();
			lastBeepSecond = remaining;
		} else if (remaining != lastBeepSecond) {
			lastBeepSecond = remaining;
			play(client, SoundEvents.NOTE_BLOCK_HAT.value(), 1.15f, 0.55f);
		}
		advanceMelody(client);
	}

	public boolean alerting(ModConfig config) {
		if (!config.pestCooldownAlert || state != State.TIMING) {
			return false;
		}
		int remaining = remainingSeconds();
		return remaining > 0 && remaining <= Math.max(1, config.pestCooldownAlertSeconds);
	}

	public int remainingSeconds() {
		if (state != State.TIMING || endsAtMs <= 0) {
			return -1;
		}
		long left = endsAtMs - System.currentTimeMillis();
		if (left <= 0) {
			return 0;
		}
		return (int) Math.ceil(left / 1000.0);
	}

	public State state() {
		return state;
	}

	public boolean seenCooldownLine() {
		return seenCooldownLine;
	}

	public void reset() {
		endsAtMs = 0;
		state = State.UNKNOWN;
		alertArmed = true;
		lastBeepSecond = -1;
		melodyTicks = -1;
		seenCooldownLine = false;
	}

	private void resetAlertPlayback() {
		melodyTicks = -1;
		lastBeepSecond = -1;
	}

	private void parseTab(Minecraft client) {
		List<String> lines = TabList.lines(client);
		Integer parsed = null;
		State parsedState = null;
		for (int i = 0; i < lines.size(); i++) {
			Matcher matcher = COOLDOWN.matcher(lines.get(i));
			if (!matcher.matches()) {
				continue;
			}
			if (!pestContext(lines, i)) {
				continue;
			}
			seenCooldownLine = true;
			String value = matcher.group(1).trim();
			String upper = value.toUpperCase(Locale.ROOT);
			if (upper.contains("READY")) {
				parsedState = State.READY;
				parsed = 0;
				break;
			}
			if (upper.contains("MAX")) {
				parsedState = State.MAX;
				parsed = 0;
				break;
			}
			int seconds = parseDuration(value);
			if (seconds >= 0) {
				parsedState = State.TIMING;
				parsed = seconds;
				break;
			}
		}
		if (parsedState == null || parsed == null) {
			return;
		}
		applyParsed(parsedState, parsed);
	}

	private void applyParsed(State parsedState, int seconds) {
		if (parsedState != State.TIMING) {
			state = parsedState;
			endsAtMs = 0;
			return;
		}
		long now = System.currentTimeMillis();
		long candidate = now + seconds * 1000L;
		int current = remainingSeconds();
		if (state != State.TIMING || endsAtMs <= 0 || current < 0) {
			endsAtMs = candidate;
			state = State.TIMING;
			alertArmed = true;
			return;
		}
		if (seconds > current + 3) {
			endsAtMs = candidate;
			state = State.TIMING;
			alertArmed = true;
			lastBeepSecond = -1;
			return;
		}
		if (seconds < current) {
			endsAtMs = candidate;
		}
		state = State.TIMING;
	}

	private static boolean pestContext(List<String> lines, int index) {
		int from = Math.max(0, index - 8);
		int to = Math.min(lines.size() - 1, index + 3);
		for (int i = from; i <= to; i++) {
			String line = lines.get(i).toLowerCase(Locale.ROOT);
			if (line.contains("pest") || line.contains("alive:") || line.contains("infested")
					|| line.contains("max pests") || line.contains("plots:")) {
				return true;
			}
		}
		return GardenDetector.inGarden();
	}

	static int parseDuration(String raw) {
		if (raw == null || raw.isBlank()) {
			return -1;
		}
		int hours = firstInt(HOURS, raw);
		int minutes = firstInt(MINUTES, raw);
		int seconds = firstInt(SECONDS, raw);
		if (hours < 0 && minutes < 0 && seconds < 0) {
			return -1;
		}
		return Math.max(0, hours) * 3600 + Math.max(0, minutes) * 60 + Math.max(0, seconds);
	}

	private static int firstInt(Pattern pattern, String raw) {
		Matcher matcher = pattern.matcher(raw);
		if (!matcher.find()) {
			return -1;
		}
		try {
			return Integer.parseInt(matcher.group(1));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private void startMelody() {
		melodyTicks = 0;
	}

	private void advanceMelody(Minecraft client) {
		if (melodyTicks < 0) {
			return;
		}
		for (int i = 0; i < MELODY_TICKS.length; i++) {
			if (melodyTicks == MELODY_TICKS[i]) {
				play(client, SoundEvents.NOTE_BLOCK_CHIME.value(), MELODY_PITCHES[i], 1.0f);
			}
		}
		if (melodyTicks == 12) {
			play(client, SoundEvents.NOTE_BLOCK_BELL.value(), 1.35f, 1.0f);
		}
		melodyTicks++;
		if (melodyTicks > 16) {
			melodyTicks = -1;
		}
	}

	private static void play(Minecraft client, SoundEvent sound, float pitch, float volume) {
		if (client.getSoundManager() == null || sound == null) {
			return;
		}
		client.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
	}
}
