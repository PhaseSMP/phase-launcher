package gg.phase.space.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import gg.phase.space.SpaceConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;

/**
 * Эффекты верхней атмосферы в обычном мире (клиент).
 * <ul>
 *   <li>Край космоса: выше Y ~1300 сквозь небо проступают звёзды, к высоте вылета их всё больше.</li>
 *   <li>Вход в атмосферу: при быстром спуске сверху игрок раскаляется — впереди по ходу плазменный фронт,
 *       по краям экрана огненное кольцо, мимо летят искры, позади тянется дым. Сила — по «нагреву»,
 *       который считается так же, как на сервере (SpaceTravel.heat).</li>
 * </ul>
 */
public final class AtmosphereFx {
	private static final int FULL = 0xF000F0;
	private static final int STARS = 260;
	private static final double[][] STAR = new double[STARS][4];
	private static double heat, lastX = Double.NaN, lastY, lastZ, velX, velY, velZ;
	private static final double[][] SPARKS = new double[60][5];
	private static final RandomSource RND = RandomSource.create();
	private static float camYaw, camPitch;

	static {
		RandomSource r = RandomSource.create(1940L);
		for (int i = 0; i < STARS; i++) {
			double u = r.nextDouble() * 1.6 - 0.6, a = r.nextDouble() * Math.PI * 2, c = Math.sqrt(1 - u * u);
			STAR[i][0] = Math.cos(a) * c; STAR[i][1] = u; STAR[i][2] = Math.sin(a) * c;
			STAR[i][3] = r.nextDouble();
		}
	}

	private AtmosphereFx() {}

	public static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null || !mc.level.dimension().equals(Level.OVERWORLD)) {
			heat = 0;
			lastX = Double.NaN;
			return;
		}
		if (mc.isPaused()) return;
		Vec3 p = mc.player.position();
		if (!Double.isNaN(lastX)) {
			double vx = p.x - lastX, vy = p.y - lastY, vz = p.z - lastZ;
			if (vx * vx + vy * vy + vz * vz > 2500) { vx = 0; vy = 0; vz = 0; }
			velX = vx; velY = vy; velZ = vz;
		}
		lastX = p.x; lastY = p.y; lastZ = p.z;
		SpaceConfig cfg = SpaceConfig.get();
		double speed = Math.max(0, -velY) + Math.sqrt(velX * velX + velZ * velZ) * 0.35;
		boolean high = p.y > cfg.atmosphereY && p.y < cfg.overworldExitY + 100;
		boolean immune = mc.player.isCreative() || mc.player.isSpectator();
		if (high && speed > 1.6 && !immune) heat = Math.min(100, heat + (speed - 1.6) * (mc.player.isFallFlying() ? 2.5 : 5));
		else heat = Math.max(0, heat - (high ? 2 : 6));
		// искры: летят мимо игрока назад по ходу движения
		double sp = Math.sqrt(velX * velX + velY * velY + velZ * velZ);
		for (double[] s : SPARKS) {
			s[4] -= 1;
			if (s[4] <= 0 && heat > 15 && sp > 0.5 && RND.nextDouble() < heat / 100) {
				double ox = RND.nextGaussian(), oy = RND.nextGaussian(), oz = RND.nextGaussian();
				s[0] = velX / sp * 5 + ox * 1.5; s[1] = velY / sp * 5 + oy * 1.5; s[2] = velZ / sp * 5 + oz * 1.5;
				s[3] = 0.6 + RND.nextDouble();
				s[4] = 8 + RND.nextInt(10);
			}
			s[0] -= velX * 0.9; s[1] -= velY * 0.9; s[2] -= velZ * 0.9;
		}
	}

	public static void render(PoseStack pose, SubmitNodeCollector out) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || !mc.level.dimension().equals(Level.OVERWORLD) || !Models.ready()) return;
		if (ShaderCompat.shadowPass()) return;
		Camera camera = mc.gameRenderer.mainCamera();
		Vec3 cam = camera.position();
		camYaw = camera.yRot();
		camPitch = camera.xRot();
		SpaceConfig cfg = SpaceConfig.get();
		double t = mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		// край космоса: звёзды проступают сквозь небо
		double stars = Math.max(0, Math.min(1, (cam.y - (cfg.overworldExitY - 700)) / 600.0));
		if (stars > 0.01) {
			double rd = Math.min(90, mc.options.getEffectiveRenderDistance() * 16.0 * 0.8);
			int n = (int) (STARS * stars);
			for (int i = 0; i < n; i++) {
				double[] s = STAR[i];
				double tw = 0.55 + 0.45 * Math.sin(t * (0.05 + s[3] * 0.1) + i);
				double size = rd * (0.006 + s[3] * 0.012) * tw;
				billboard(pose, out, s[0] * rd, s[1] * rd, s[2] * rd, size, s[3] > 0.85 ? "fx/spark_white" : stars > 0.6 ? "fx/glow_white_b" : "fx/glow_white_c");
			}
		}
		if (heat < 6) return;
		// вход в атмосферу: плазма впереди по ходу движения
		double sp = Math.sqrt(velX * velX + velY * velY + velZ * velZ);
		if (sp < 0.3) return;
		double dx = velX / sp, dy = velY / sp, dz = velZ / sp;
		double h = heat / 100.0;
		String hot = h > 0.7 ? "fx/glow_sun_core_b" : "fx/glow_sun_mid_c";
		int puffs = 14;
		for (int i = 0; i < puffs; i++) {
			double a = i * 2.39996 + t * 0.3, rr = 0.6 + (i % 5) * 0.45;
			double ox = Math.cos(a) * rr, oz = Math.sin(a) * rr;
			// поперёк направления движения
			double px = -dz, pz = dx, len = Math.max(1e-3, Math.sqrt(px * px + pz * pz));
			px /= len; pz /= len;
			double qx = dy * pz, qy = dz * px - dx * pz, qz = -dy * px;
			double fx = dx * (3.2 - rr * 0.6) + px * ox + qx * oz, fy = dy * (3.2 - rr * 0.6) + qy * oz, fz = dz * (3.2 - rr * 0.6) + pz * ox + qz * oz;
			billboard(pose, out, fx, fy, fz, (1.6 + rr) * (0.5 + h), i % 3 == 0 ? hot : i % 3 == 1 ? "fx/glow_lava_b" : "fx/glow_prom_b");
		}
		billboard(pose, out, dx * 4.5, dy * 4.5, dz * 4.5, 6 * (0.4 + h), h > 0.6 ? "fx/glow_lava_a" : "fx/glow_lava_c");
		// огненное кольцо по краям экрана
		double fwX = -Math.sin(Math.toRadians(camYaw)) * Math.cos(Math.toRadians(camPitch));
		double fwY = -Math.sin(Math.toRadians(camPitch));
		double fwZ = Math.cos(Math.toRadians(camYaw)) * Math.cos(Math.toRadians(camPitch));
		billboard(pose, out, fwX * 1.2, fwY * 1.2, fwZ * 1.2, 2.6 + (1 - h) * 0.8, h > 0.6 ? "fx/ring_lava_a" : "fx/ring_lava_c");
		if (h > 0.5) billboard(pose, out, fwX * 1.1, fwY * 1.1, fwZ * 1.1, 2.0, "fx/puff_lava_d");
		// искры
		for (double[] s : SPARKS) {
			if (s[4] <= 0) continue;
			billboard(pose, out, s[0], s[1], s[2], 0.12 * s[3], s[3] > 1.2 ? "fx/glow_sun_core_a" : "fx/glow_meteor_a");
			billboard(pose, out, s[0] + velX * 0.4, s[1] + velY * 0.4, s[2] + velZ * 0.4, 0.09 * s[3], "fx/glow_meteor_b");
		}
	}

	private static void billboard(PoseStack pose, SubmitNodeCollector out, double rx, double ry, double rz, double size, String model) {
		if (size <= 0) return;
		pose.pushPose();
		pose.translate(rx, ry, rz);
		pose.rotateDegrees(Axis.YP, 180F - camYaw);
		pose.rotateDegrees(Axis.XP, -camPitch);
		pose.scale((float) size, (float) size, (float) size);
		((Matrix3f) pose.last().normal()).set(0, 0, 0, 0, 0, 0, 0, 1, 0);
		Models.get(model).submit(pose, out, FULL, OverlayTexture.NO_OVERLAY, 0);
		pose.popPose();
	}
}
