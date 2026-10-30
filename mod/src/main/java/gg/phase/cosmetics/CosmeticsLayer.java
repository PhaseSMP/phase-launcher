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
		CosmeticsData.Worn worn = CosmeticsData.get(player.getName().getString());
		if (worn == null || worn.hat() == null) return;
		pose.pushPose();
		this.getParentModel().root().translateAndRotate(pose);
		this.getParentModel().translateToHead(pose);
		// пространство головы: ось y вниз, лицо к −z. Поворот на 180° вокруг z переводит модель «вверх ногами» в «нормально»
		pose.rotateDegrees(Axis.ZP, 180.0F);
		pose.translate(0.0F, 0.5F, 0.0F);
		CosmeticsModels.get(worn.hat()).submit(pose, out, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
		pose.popPose();
	}
}
