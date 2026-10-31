package gg.phase.cosmetics.mixin;

import gg.phase.cosmetics.Config;
import gg.phase.cosmetics.CosmeticsData;
import gg.phase.cosmetics.NameTags;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Надета шляпа Phase — шлем (и тыква, и голова моба) не рисуется, видна шляпа */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
	private void phase$hatOverHelmet(Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
		if (!(entity instanceof Player p) || !Config.get().hideHelmet) return;
		CosmeticsData.Worn w = CosmeticsData.get(p.getName().getString());
		if (w == null || w.hat() == null) return;
		state.headEquipment = ItemStack.EMPTY;
		state.headItem.clear();
		state.wornHeadType = null;
		state.wornHeadProfile = null;
	}

	/** Табличка над ником: тег клана и значок роли/подписки */
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
	private void phase$nameTag(Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
		if (state.nameTag == null || !(entity instanceof Player p) || !Config.get().nametags) return;
		CosmeticsData.Worn w = CosmeticsData.get(p.getName().getString());
		if (w == null || (w.clan() == null && w.badge() == null)) return;
		state.nameTag = NameTags.decorate(state.nameTag, w);
	}
}
