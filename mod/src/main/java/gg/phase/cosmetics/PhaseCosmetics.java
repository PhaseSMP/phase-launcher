package gg.phase.cosmetics;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/** Phase Cosmetics: шляпы на голове и эндер-дракончик рядом с игроком. Данные — с сервера аккаунтов Phase. */
public class PhaseCosmetics implements ClientModInitializer {
	@Override
	@SuppressWarnings("unchecked")
	public void onInitializeClient() {
		CosmeticsData.start();
		ClientTickEvents.END_CLIENT_TICK.register(PetMotion::tick);
		InvalidateRenderStateCallback.EVENT.register(CosmeticsLayer::invalidate);
		LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
			if (renderer instanceof AvatarRenderer<?>) {
				helper.register(new CosmeticsLayer((RenderLayerParent<AvatarRenderState, PlayerModel>) renderer, context.getItemModelResolver()));
			}
		});
	}
}
