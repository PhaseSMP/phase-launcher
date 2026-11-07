package gg.phase.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Шляпа поверх игрока: рисуется в пространстве головы и поворачивается вместе с ней.
 * Шлем под шляпой прячет AvatarRendererMixin. Питомец рисуется отдельно, в мире (PetManager).
 */
public class CosmeticsLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	/** Состояние игрока, нарисованного в меню (предпросмотр), и шляпа, которую на нём показать */
	public static volatile Object PREVIEW_STATE;
	public static volatile String PREVIEW_HAT;

	public CosmeticsLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack pose, SubmitNodeCollector out, int light, AvatarRenderState state, float yRot, float xRot) {
		if (state.isInvisible || state.isSpectator) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		Entity e = mc.level.getEntity(state.id);
		if (!(e instanceof Player player)) return;
		String name = player.getName().getString();
		String hat;
		if (state == PREVIEW_STATE) hat = PREVIEW_HAT; // предпросмотр в меню: шляпа под курсором
		else {
			CosmeticsData.Worn worn = CosmeticsData.get(name);
			hat = worn == null ? null : worn.hat();
		}
		if (hat == null) return;
		float[] adj = CosmeticsData.adj(name, hat);
		pose.pushPose();
		this.getParentModel().root().translateAndRotate(pose);
		this.getParentModel().translateToHead(pose);
		// пространство головы: ось y вниз, лицо к −z. Поворот на 180° вокруг z переводит модель «вверх ногами» в «нормально»
		pose.rotateDegrees(Axis.ZP, 180.0F);
		pose.translate(0.0F, 0.5F, 0.0F);
		// настройка игрока: сдвиг (в пикселях головы), наклон, поворот, крен и размер — вокруг макушки
		if (adj != CosmeticsData.NO_ADJ) {
			pose.translate(-adj[0] / 16F, adj[1] / 16F, -adj[2] / 16F);
			if (adj[4] != 0) pose.rotateDegrees(Axis.YP, adj[4]);
			if (adj[3] != 0) pose.rotateDegrees(Axis.XP, adj[3]);
			if (adj[5] != 0) pose.rotateDegrees(Axis.ZP, adj[5]);
			if (adj[6] != 1) pose.scale(adj[6], adj[6], adj[6]);
		}
		CosmeticsModels.get(hat).submit(pose, out, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
		pose.popPose();
	}
}
