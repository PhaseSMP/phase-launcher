package gg.phase.cosmetics;

import com.mojang.blaze3d.platform.InputConstants;
import gg.phase.cosmetics.pet.PetManager;
import gg.phase.cosmetics.ui.PhaseMenuScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;

/**
 * Phase Cosmetics: шляпы, живой эндер-дракончик, скины из лаунчера и меню в игре (J).
 * Данные — с сервера аккаунтов Phase, работает в одиночной игре и на любом сервере.
 */
public class PhaseCosmetics implements ClientModInitializer {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("phase", "cosmetics"));
	public static KeyMapping MENU;

	@Override
	@SuppressWarnings("unchecked")
	public void onInitializeClient() {
		CosmeticsData.start();
		MENU = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.phase.menu", InputConstants.KEY_J, CATEGORY));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (MENU.consumeClick()) {
				if (mc.screen == null && mc.player != null) mc.setScreen(new PhaseMenuScreen());
			}
			PetManager.INSTANCE.tick(mc);
			SkinData.tick(mc);
		});
		InvalidateRenderStateCallback.EVENT.register(CosmeticsModels::invalidate);
		LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
			if (!CosmeticsModels.ready()) CosmeticsModels.setResolver(context.getItemModelResolver());
			if (renderer instanceof AvatarRenderer<?>) {
				helper.register(new CosmeticsLayer((RenderLayerParent<AvatarRenderState, PlayerModel>) renderer));
			}
		});
		LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> PetManager.INSTANCE.render(ctx.poseStack(), ctx.submitNodeCollector()));
	}
}
