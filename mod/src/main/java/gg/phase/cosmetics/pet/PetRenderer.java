package gg.phase.cosmetics.pet;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import gg.phase.cosmetics.CosmeticsModels;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Рисует дракончика по скелету PetRigData: каждая часть — своя модель, её поворот считается из позы питомца.
 * Единицы внутри — пиксели модели (1 px = SCALE/16 блока).
 */
public final class PetRenderer {
	public static final float SCALE = 0.38F;
	private static final float PX = 1F / 16F;

	private PetRenderer() {}

	/** Высота центра туловища над опорной точкой, px */
	static float bodyHeight(float sit, float lie) {
		return 5.2F - sit * 0.9F - lie * 2.7F;
	}

	public static void render(Pet pet, float pt, Vec3 cam, PoseStack pose, SubmitNodeCollector out, int light) {
		float[] v = new float[Pet.N];
		for (int i = 0; i < Pet.N; i++) v[i] = Mth.lerp(pt, pet.o[i], pet.p[i]);
		float fly = v[Pet.FLY], sit = v[Pet.SIT], lie = v[Pet.LIE], fold = Mth.clamp(v[Pet.FOLD], 0F, 1F);
		float flapPh = Mth.lerp(pt, pet.flapO, pet.flap), tailPh = Mth.lerp(pt, pet.tailO, pet.tail);
		float amp = v[Pet.FLAP_AMP];
		float age = pet.age + pt;

		double x = Mth.lerp(pt, pet.prevPos.x, pet.pos.x) - cam.x;
		double y = Mth.lerp(pt, pet.prevPos.y, pet.pos.y) - cam.y;
		double z = Mth.lerp(pt, pet.prevPos.z, pet.pos.z) - cam.z;
		float yaw = Mth.rotLerp(pt, pet.yawO, pet.yaw);
		float pitch = Mth.lerp(pt, pet.pitchO, pet.pitch);
		float roll = Mth.lerp(pt, pet.rollO, pet.roll);

		pose.pushPose();
		pose.translate(x, y, z);
		pose.scale(SCALE, SCALE, SCALE);
		// туловище: высота над опорой + покачивание взмахами
		float bob = fly * amp * -Mth.sin(flapPh) * 0.35F;
		pose.translate(0F, (bodyHeight(sit, lie) + bob) * PX, 0F);
		pose.rotateDegrees(Axis.YP, 180F - yaw);
		float bodyPitch = pitch + sit * 26F;
		pose.rotateDegrees(Axis.XP, bodyPitch);
		pose.rotateDegrees(Axis.ZP, roll);

		PetRigData.Part[] parts = PetRigData.PARTS;
		PoseStack.Pose[] at = new PoseStack.Pose[parts.length];
		float wrappedPitch = Mth.wrapDegrees(bodyPitch);
		for (int i = 0; i < parts.length; i++) {
			PetRigData.Part part = parts[i];
			pose.pushPose();
			if (part.parent() >= 0) restore(pose, at[part.parent()]);
			pose.translate(part.px() * PX, part.py() * PX, part.pz() * PX);
			String model = part.model();
			switch (part.name()) {
				case "body" -> {
					if (fly < 0.5F) {
						float br = 1F + Mth.sin(age * 0.12F) * 0.025F * (1F - fly);
						at[i] = pose.last().copy();
						pose.scale(1F, br, 1F);
					}
				}
				case "neck" -> {
					float neckX = 22F * (1F - lie) - 38F * lie - sit * 10F;
					pose.rotateDegrees(Axis.YP, -v[Pet.HEAD_YAW] * 0.4F);
					pose.rotateDegrees(Axis.XP, neckX + v[Pet.HEAD_PITCH] * 0.3F);
				}
				case "head" -> {
					float neckX = 22F * (1F - lie) - 38F * lie - sit * 10F;
					float want = v[Pet.HEAD_PITCH] * (1F - lie) - 4F * lie;
					float headX = Mth.clamp(want - wrappedPitch - neckX - v[Pet.HEAD_PITCH] * 0.3F, -60F, 60F);
					pose.rotateDegrees(Axis.YP, -v[Pet.HEAD_YAW] * 0.6F);
					pose.rotateDegrees(Axis.XP, headX);
					model = switch (pet.head) { case 1 -> "pet_head_blink"; case 2 -> "pet_head_happy"; case 3 -> "pet_head_sleep"; default -> "pet_head"; };
				}
				case "jaw" -> pose.rotateDegrees(Axis.XP, -v[Pet.JAW] * 32F);
				case "wing_l", "wing_r" -> {
					float s = part.name().endsWith("_l") ? 1F : -1F;
					float flap = amp * Mth.sin(flapPh) * 42F + (1F - amp) * 8F;
					pose.rotateDegrees(Axis.YP, s * fold * 80F);
					pose.rotateDegrees(Axis.XP, fold * 88F);
					pose.rotateDegrees(Axis.ZP, s * ((1F - fold) * -flap - fold * 6F));
				}
				case "wing_tip_l", "wing_tip_r" -> {
					float s = part.name().endsWith("_l") ? 1F : -1F;
					float flap2 = amp * Mth.sin(flapPh - 0.9F) * 30F;
					pose.rotateDegrees(Axis.YP, s * (1F - fold) * amp * Mth.cos(flapPh) * 8F);
					pose.rotateDegrees(Axis.ZP, s * ((1F - fold) * -flap2 + fold * 168F));
				}
				case "leg_fl", "leg_fr" -> {
					float side = part.name().endsWith("l") ? 0F : 0.6F;
					pose.rotateDegrees(Axis.XP, fly * (48F + Mth.sin(age * 0.1F + side) * 6F) - sit * 26F + lie * 78F);
				}
				case "leg_bl", "leg_br" -> {
					pose.rotateDegrees(Axis.XP, fly * -62F + sit * 70F - lie * 76F);
				}
				default -> { // хвост
					if (part.name().startsWith("tail")) {
						int k = part.name().charAt(4) - '1';
						float curl = v[Pet.CURL];
						float sway = Mth.sin(tailPh - k * 0.9F) * (6F + 5F * k) * (0.35F + v[Pet.WAG]) * (1F - curl * 0.6F);
						float curlY = curl * (k == 0 ? 18F : 36F);
						float rx;
						if (k == 0) rx = fly * (-8F + v[Pet.LEAN] * 14F) - sit * 52F - lie * 6F;
						else if (k == 1) rx = sit * 30F;
						else rx = 0F;
						rx += fly * Mth.sin(tailPh * 0.8F - k) * 5F;
						pose.rotateDegrees(Axis.YP, sway + curlY);
						pose.rotateDegrees(Axis.XP, rx);
					}
				}
			}
			if (at[i] == null) at[i] = pose.last().copy();
			CosmeticsModels.get(model).submit(pose, out, light, OverlayTexture.NO_OVERLAY, 0);
			pose.popPose();
		}
		pose.popPose();
	}

	/** Вернуть преобразование родителя (позы частей не вкладываются в стек — родитель уже снят) */
	private static void restore(PoseStack pose, PoseStack.Pose parent) {
		pose.last().set(parent);
	}
}
