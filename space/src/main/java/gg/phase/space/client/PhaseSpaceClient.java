package gg.phase.space.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/** Клиент: отрисовка космоса. Работает только в мире phasespace:space, в остальных мирах ничего не делает. */
public class PhaseSpaceClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// резолвер моделей предметов приходит вместе с рендерерами сущностей (после загрузки ресурсов)
		LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
			if (!Models.ready()) Models.setResolver(context.getItemModelResolver());
		});
		InvalidateRenderStateCallback.EVENT.register(Models::invalidate);
		LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> SpaceRenderer.render(ctx.poseStack(), ctx.submitNodeCollector()));
		ClientTickEvents.END_CLIENT_TICK.register(SpaceRenderer::tick);
	}
}
