package gg.phase.cosmetics.mixin;

import gg.phase.cosmetics.SkinData;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Скин из лаунчера Phase: игроку в мире, руке от первого лица, голове в списке игроков */
@Mixin(PlayerInfo.class)
public abstract class PlayerInfoMixin {
	@Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
	private void phase$skin(CallbackInfoReturnable<PlayerSkin> cir) {
		PlayerSkin s = SkinData.lookup(((PlayerInfo) (Object) this).getProfile().name());
		if (s != null) cir.setReturnValue(s);
	}
}
