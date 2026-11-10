package gg.phase.cosmetics.pet;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import gg.phase.cosmetics.CosmeticsModels;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Рисует слизня: ядро и пузырьки, полупрозрачную оболочку и лицо (меняется по настроению).
 * Желе сжимается от низа: сплющился — шире и ниже, вытянулся — у́же и выше. Сальто и кувырок — вокруг центра.
 */
public final class SlimeRenderer {
	public static final float SCALE = 0.5F;
	private static final float PX = 1F / 16F;
	/** Раскраски слизня; остальные (раскраски дракончика) — обычный «Лайм» */
	public static final Set<String> SKINS = Set.of("sakura", "frost", "lava", "honey", "void", "prism");

	private SlimeRenderer() {}

	public static void render(Slime s, float pt, Vec3 cam, PoseStack pose, SubmitNodeCollector out, int light) {
		double x = Mth.lerp(pt, s.prevPos.x, s.pos.x) - cam.x;
		double y = Mth.lerp(pt, s.prevPos.y, s.pos.y) - cam.y;
		double z = Mth.lerp(pt, s.prevPos.z, s.pos.z) - cam.z;
		float yaw = Mth.rotLerp(pt, s.yawO, s.yaw);
		// на плече: позиция от хозяина в этом же кадре — не отстаёт при прыжке и беге
		Minecraft mc = Minecraft.getInstance();
		if (s.perched && mc.level != null && mc.level.getEntity(s.ownerId) instanceof Player owner) {
			Vec3 sh = Pet.shoulder(owner, pt, Mth.lerp(pt, s.perchYO, s.perchY));
			x = sh.x - cam.x; y = sh.y - cam.y; z = sh.z - cam.z;
			yaw = Mth.rotLerp(pt, owner.yBodyRotO, owner.yBodyRot);
		}
		float pitch = Mth.lerp(pt, s.pitchO, s.pitch);
		float roll = Mth.lerp(pt, s.rollO, s.roll);
		float sq = Mth.lerp(pt, s.sqO, s.sq);
		float sy = 1F - sq, sxz = 1F + sq * 0.55F;

		pose.pushPose();
		pose.translate(x, y, z);
		pose.scale(SCALE, SCALE, SCALE);
		pose.rotateDegrees(Axis.YP, 180F - yaw);
		if (pitch != 0F || roll != 0F) {
			float c = 5.5F * PX; // центр слизня
			pose.translate(0F, c, 0F);
			pose.rotateDegrees(Axis.XP, pitch);
			pose.rotateDegrees(Axis.ZP, roll);
			pose.translate(0F, -c, 0F);
		}
		pose.scale(sxz, sy, sxz);
		String tint = SKINS.contains(s.color) ? "__" + s.color : "";
		int face = Mth.clamp(s.face, 0, Slime.FACE_MODEL.length - 1);
		// сначала непрозрачное (ядро, лицо), потом полупрозрачная оболочка
		CosmeticsModels.get("slime_core" + tint).submit(pose, out, light, OverlayTexture.NO_OVERLAY, 0);
		CosmeticsModels.get(Slime.FACE_MODEL[face] + tint).submit(pose, out, light, OverlayTexture.NO_OVERLAY, 0);
		CosmeticsModels.get("slime_shell" + tint).submit(pose, out, light, OverlayTexture.NO_OVERLAY, 0);
		pose.popPose();
	}
}
