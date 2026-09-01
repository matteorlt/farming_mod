package dev.farmingprofit.client.hud;

import dev.farmingprofit.client.config.ModConfig;
import dev.farmingprofit.client.garden.PestCooldownTracker;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Gros titre central quand le cooldown pest du tab passe sous le seuil.
 */
public final class PestCooldownAlertHud {
	private PestCooldownAlertHud() {
	}

	public static void render(
			GuiGraphicsExtractor graphics,
			DeltaTracker deltaTracker,
			ModConfig config,
			PestCooldownTracker tracker
	) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.screen instanceof HudMoveScreen) {
			return;
		}
		if (!tracker.alerting(config)) {
			return;
		}

		int remaining = tracker.remainingSeconds();
		Font font = client.font;
		int windowWidth = client.getWindow().getGuiScaledWidth();
		int windowHeight = client.getWindow().getGuiScaledHeight();

		String title = "PEST";
		String countdown = remaining + "s";
		String hint = "Cooldown bientôt fini";

		float pulse = 1.0f + 0.12f * (float) Math.sin(System.currentTimeMillis() / 110.0);
		float scale = (remaining <= 3 ? 5.2f : 4.2f) * pulse;
		int color = remaining <= 3 ? 0xFFFF1744 : 0xFFFFEA00;
		int shadow = 0xCC000000;

		int bannerTop = Math.round(windowHeight * 0.18f);
		int bannerHeight = Math.round(92 * pulse);
		graphics.fill(0, bannerTop - 8, windowWidth, bannerTop + bannerHeight, 0x99000000);

		drawScaledCentered(graphics, font, title, windowWidth, bannerTop + 6, scale * 0.55f, 0xFFFF6D00, shadow);
		drawScaledCentered(graphics, font, countdown, windowWidth, bannerTop + 34, scale, color, shadow);
		drawScaledCentered(graphics, font, hint, windowWidth, bannerTop + 34 + Math.round(18 * scale / 4.2f) + 8, 1.35f, 0xFFFFF8E1, shadow);
	}

	private static void drawScaledCentered(
			GuiGraphicsExtractor graphics,
			Font font,
			String text,
			int windowWidth,
			int y,
			float scale,
			int color,
			int shadow
	) {
		int width = font.width(text);
		float x = (windowWidth - width * scale) / 2.0f;
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale, scale);
		graphics.text(font, text, 1, 1, shadow, false);
		graphics.text(font, text, 0, 0, color, false);
		graphics.pose().popMatrix();
	}
}
