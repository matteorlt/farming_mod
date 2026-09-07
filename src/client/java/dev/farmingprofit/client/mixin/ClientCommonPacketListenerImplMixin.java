package dev.farmingprofit.client.mixin;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.farmingprofit.client.pack.ServerPackHider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerImplMixin {
	@Shadow
	@Final
	protected Minecraft minecraft;

	@Inject(
			method = "handleResourcePackPush",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/Minecraft;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"
			),
			cancellable = true
	)
	private void farmingprofit$acceptHiddenPack(ClientboundResourcePackPushPacket packet, CallbackInfo ci) {
		if (!ServerPackHider.shouldHide(packet)) {
			return;
		}
		try {
			URL url = URI.create(packet.url()).toURL();
			this.minecraft.getDownloadedPackSource().pushPack(packet.id(), url, packet.hash());
			this.minecraft.getDownloadedPackSource().allowServerPacks();
			ci.cancel();
		} catch (IllegalArgumentException | MalformedURLException ignored) {
		}
	}
}
