package dev.farmingprofit.client.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.farmingprofit.client.pack.ServerPackHider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.client.resources.server.PackReloadConfig;
import net.minecraft.server.packs.repository.RepositorySource;

@Mixin(DownloadedPackSource.class)
public abstract class DownloadedPackSourceMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Shadow
	private RepositorySource packSource;

	@Shadow
	private PackReloadConfig.Callbacks pendingReload;

	@Inject(method = "startReload", at = @At("HEAD"), cancellable = true)
	private void farmingprofit$skipServerPack(PackReloadConfig.Callbacks callbacks, CallbackInfo ci) {
		if (!ServerPackHider.enabled()) {
			return;
		}
		this.pendingReload = callbacks;
		this.packSource = output -> {
		};
		this.minecraft.reloadResourcePacks();
		ci.cancel();
	}
}
