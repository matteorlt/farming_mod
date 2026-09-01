package dev.farmingprofit.client.garden;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.farmingprofit.client.config.ModConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * Lit {@code Cooldown: 1m 58s} dans le widget Pests du tab, puis déclenche
 * l’alerte quand il reste moins de N secondes.
 */
public final class PestCooldownTracker {
	private static final Pattern COOLDOWN = Pattern.compile("(?i)cooldown:\\s*(.+)$");
	private static final Pattern HOURS = Pattern.compile("(\\d+)\\s*h", Pattern.CASE_INSENSITIVE);
	private static final Pattern MINUTES = Pattern.compile("(\\d+)\\s*m(?!s)", Pattern.CASE_INSENSITIVE);
	private static final Pattern SECONDS = Pattern.compile("(\\d+)\\s*s", Pattern.CASE_INSENSITIVE);
	private static final Pattern CLOCK = Pattern.compile("(\\d{1,2}):(\\d{2})");
	private static final Pattern BARE_NUMBER = Pattern.compile("^\\d{1,3}$");

	private long endsAtMs;
	private State state = State.UNKNOWN;
	private boolean alertArmed = true;
	private boolean lastParseCoarse;
	private int lastBeepSecond = -1;
	private int awayTicks;
	private boolean seenCooldownLine;

	public enum State {
		UNKNOWN, TIMING, READY, MAX
	}

	public void tick(Minecraft client, ModConfig config) {
		if (!config.pestCooldownAlert || client.player == null) {
			return;
		}

		boolean parsed = parseTab(client);
		if (parsed || GardenDetector.inGarden()) {
			awayTicks = 0;
		} else {
			awayTicks++;
			if (awayTicks > 80) {
				reset();
			}
			return;
		}

		int remaining = remainingSeconds();
		int threshold = Math.max(1, config.pestCooldownAlertSeconds);
		if (state != State.TIMING || lastParseCoarse || remaining <= 0 || remaining > threshold) {
			if (remaining > threshold || lastParseCoarse || state == State.READY || state == State.MAX) {
				alertArmed = true;
				lastBeepSecond = -1;
			}
			return;
		}

		if (alertArmed) {
			alertArmed = false;
			lastBeepSecond = remaining;
			pushTitle(client, remaining);
			playLoud(client, SoundEvents.PLAYER_LEVELUP, 0.9f, 3.0f);
			playLoud(client, SoundEvents.NOTE_BLOCK_PLING.value(), 1.4f, 3.0f);
			playLoud(client, SoundEvents.NOTE_BLOCK_BELL.value(), 1.1f, 2.5f);
		} else if (remaining != lastBeepSecond) {
			lastBeepSecond = remaining;
			pushTitle(client, remaining);
			playLoud(client, SoundEvents.NOTE_BLOCK_PLING.value(), remaining <= 3 ? 1.8f : 1.35f, 2.8f);
		}
	}

	public boolean alerting(ModConfig config) {
		if (!config.pestCooldownAlert || state != State.TIMING || lastParseCoarse) {
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
		lastParseCoarse = false;
		lastBeepSecond = -1;
		awayTicks = 0;
		seenCooldownLine = false;
	}

	private boolean parseTab(Minecraft client) {
		List<String> lines = TabList.lines(client);
		for (int i = 0; i < lines.size(); i++) {
			Matcher matcher = COOLDOWN.matcher(lines.get(i));
			if (!matcher.find()) {
				continue;
			}
			if (!pestContext(lines, i)) {
				continue;
			}
			seenCooldownLine = true;
			String value = matcher.group(1).trim();
			String upper = value.toUpperCase(Locale.ROOT);
			if (upper.contains("READY")) {
				applyParsed(State.READY, 0, false);
				return true;
			}
			if (upper.contains("MAX")) {
				applyParsed(State.MAX, 0, false);
				return true;
			}
			ParsedTime time = parseDuration(value);
			if (time.seconds() >= 0) {
				applyParsed(State.TIMING, time.seconds(), time.coarse());
				return true;
			}
		}
		return seenCooldownLine && state != State.UNKNOWN;
	}

	private void applyParsed(State parsedState, int seconds, boolean coarse) {
		if (parsedState != State.TIMING) {
			state = parsedState;
			endsAtMs = 0;
			lastParseCoarse = false;
			return;
		}
		lastParseCoarse = coarse;
		long now = System.currentTimeMillis();
		long candidate = now + seconds * 1000L;
		int current = remainingSeconds();

		if (coarse) {
			state = State.TIMING;
			if (current < 0 || seconds > current + 3) {
				endsAtMs = candidate;
				alertArmed = true;
			} else {
				endsAtMs = candidate;
			}
			return;
		}

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
					|| line.contains("max pests") || line.contains("plots:") || line.contains("cooldown:")) {
				return true;
			}
		}
		return GardenDetector.inGarden();
	}

	static ParsedTime parseDuration(String raw) {
		if (raw == null || raw.isBlank()) {
			return ParsedTime.invalid();
		}
		String value = raw.trim();
		int hours = firstInt(HOURS, value);
		int minutes = firstInt(MINUTES, value);
		int secs = firstInt(SECONDS, value);
		boolean hasSeconds = secs >= 0 || CLOCK.matcher(value).find();
		if (hours < 0 && minutes < 0 && secs < 0) {
			Matcher clock = CLOCK.matcher(value);
			if (clock.find()) {
				int total = Integer.parseInt(clock.group(1)) * 60 + Integer.parseInt(clock.group(2));
				return new ParsedTime(total, false);
			}
			if (BARE_NUMBER.matcher(value).matches()) {
				return new ParsedTime(Integer.parseInt(value), false);
			}
			return ParsedTime.invalid();
		}
		int total = Math.max(0, hours) * 3600 + Math.max(0, minutes) * 60 + Math.max(0, secs);
		return new ParsedTime(total, !hasSeconds);
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

	private static void pushTitle(Minecraft client, int remaining) {
		Component title = Component.literal("PEST " + remaining + "s")
				.withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
		Component subtitle = Component.literal("Cooldown bientôt fini").withStyle(ChatFormatting.GOLD);
		client.gui.setTimes(0, 30, 10);
		client.gui.setSubtitle(subtitle);
		client.gui.setTitle(title);
		client.gui.setOverlayMessage(title, false);
	}

	private static void playLoud(Minecraft client, SoundEvent sound, float pitch, float volume) {
		if (client.getSoundManager() == null || sound == null) {
			return;
		}
		client.getSoundManager().play(new SimpleSoundInstance(
				sound.location(),
				SoundSource.MASTER,
				volume,
				pitch,
				RandomSource.create(),
				false,
				0,
				SoundInstance.Attenuation.NONE,
				0.0,
				0.0,
				0.0,
				true
		));
	}

	record ParsedTime(int seconds, boolean coarse) {
		static ParsedTime invalid() {
			return new ParsedTime(-1, false);
		}
	}
}
