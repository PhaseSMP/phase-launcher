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
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelType;

/**
 * Шарф и варежки поверх игрока.
 * Шарф — из трёх частей: виток на плечах и узел (пространство тела), виток вокруг шеи (пространство головы —
 * поворачивается вместе с ней) и два конца спереди, которые качаются на пружине (WearPhysics) и висят
 * отвесно, даже когда игрок присел. Варежки — на кистях обеих рук, у тонких рук (slim) — своя модель.
 */
public class WearLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	private static final float PX = 1F / 16F;

	public WearLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack pose, SubmitNodeCollector out, int light, AvatarRenderState state, float yRot, float xRot) {
		if (state.isInvisible || state.isSpectator) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		Entity e = mc.level.getEntity(state.id);
		if (!(e instanceof Player player)) return;
		CosmeticsData.Worn w = CosmeticsData.get(player.getName().getString());
		if (w == null) return;
		PlayerModel m = this.getParentModel();
		if ("scarf_red".equals(w.neck())) this.scarf(pose, out, light, state, m, player.getId());
		if ("mittens_red".equals(w.hands())) this.mittens(pose, out, light, state, m);
	}

	private void scarf(PoseStack pose, SubmitNodeCollector out, int light, AvatarRenderState state, PlayerModel m, int id) {
		float pt = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
		// витки на плечах и узел — вместе с телом
		pose.pushPose();
		m.root().translateAndRotate(pose);
		m.body.translateAndRotate(pose);
		// пространство тела: ось y вниз, перед к −z. Модель «вверх ногами» → поворот на 180° вокруг z; верх тела — y=16 модели
		pose.rotateDegrees(Axis.ZP, 180.0F);
		pose.translate(0.0F, -0.5F, 0.0F);
		CosmeticsModels.get("scarf_red").submit(pose, out, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
		// концы: висят отвесно (компенсируем наклон тела при приседе) и качаются
		float lean = (float) Math.toDegrees(m.body.xRot);
		float swing = WearPhysics.swing(id, pt) + lean;
		float sway = WearPhysics.sway(id, pt);
		tail(pose, out, light, state, "scarf_red_tail_l", 6.9F, swing, sway);
		tail(pose, out, light, state, "scarf_red_tail_r", 9.25F, swing * 0.88F + 2F, sway * 0.85F);
		pose.popPose();
		// виток вокруг шеи — вместе с головой
		pose.pushPose();
		m.root().translateAndRotate(pose);
		m.translateToHead(pose);
		pose.rotateDegrees(Axis.ZP, 180.0F);
		pose.translate(0.0F, 0.5F, 0.0F);
		CosmeticsModels.get("scarf_red_neck").submit(pose, out, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
		pose.popPose();
	}

	/** Конец шарфа: поворот вокруг узла (x — середина конца, y 13.6, z 4.55 в координатах модели) */
	private static void tail(PoseStack pose, SubmitNodeCollector out, int light, AvatarRenderState state, String model, float cx, float swing, float sway) {
		float px = cx * PX - 0.5F, py = 13.6F * PX - 0.5F, pz = 4.55F * PX - 0.5F;
		pose.pushPose();
		pose.translate(px, py, pz);
		pose.rotateDegrees(Axis.XP, swing); // плюс — от груди вперёд
		pose.rotateDegrees(Axis.ZP, sway);
		pose.translate(-px, -py, -pz);
		CosmeticsModels.get(model).submit(pose, out, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
		pose.popPose();
	}

	private void mittens(PoseStack pose, SubmitNodeCollector out, int light, AvatarRenderState state, PlayerModel m) {
		boolean slim = state.skin.model() == PlayerModelType.SLIM;
		for (HumanoidArm arm : HumanoidArm.values()) {
			pose.pushPose();
			m.translateToHand(state, arm, pose); // ось руки — x = ∓1 px (у тонких рук тоже: игра сама сдвигает шарнир)
			pose.rotateDegrees(Axis.ZP, 180.0F);
			float cx = arm == HumanoidArm.RIGHT ? -1F : 1F;
			// кончики пальцев — низ руки (y = 10 px); модель: ось x = 8, кончики — y = 0
			pose.translate(-cx * PX, -0.125F, 0.0F);
			String side = arm == HumanoidArm.RIGHT ? "_r" : "_l";
			CosmeticsModels.get((slim ? "mitten_red_slim" : "mitten_red") + side).submit(pose, out, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
			pose.popPose();
		}
	}
}
