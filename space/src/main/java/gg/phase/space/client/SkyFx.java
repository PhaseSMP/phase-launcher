package gg.phase.space.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import gg.phase.space.Atlas;
import gg.phase.space.EarthSky;
import gg.phase.space.SolarSystem;
import gg.phase.space.SolarSystem.Body;
import gg.phase.space.SolarSystem.Comet;
import gg.phase.space.SolarSystem.Constellation;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;

/**
 * Небо с Земли и созвездия (клиент).
 * <ul>
 *   <li>12 созвездий Phase: звёзды крупнее ванильных, крутятся вместе с небом и за 12 дней проходят полный круг.
 *       У изученных видны линии; при наведении подзорной трубы линии проступают прямо во время изучения.</li>
 *   <li>Планеты у пути Солнца: Меркурий, Венера, Марс, Юпитер, Сатурн и фиолетовая Флорана —
 *       на своих настоящих местах относительно Солнца (из орбит мода).</li>
 *   <li>Кометы мода, подлетевшие к Солнцу, видны с Земли с хвостом от Солнца.</li>
 *   <li>В космосе — те же созвездия, неподвижно.</li>
 * </ul>
 * Солнце и Луна Земли заменены текстурами (assets/minecraft/textures/environment/celestial).
 */
public final class SkyFx {
	private static final int FULL = 0xF000F0;
	private static final Set<String> KNOWN = new HashSet<>();
	private static final double[] V = new double[3], W = new double[3], E = new double[3], T = new double[3], S = new double[3];
	static float camYaw, camPitch;
	static double fwX, fwY, fwZ, rtX, rtZ, upX, upY, upZ;
	private static double aimFade;
	private static int aimIndex = -1;

	private SkyFx() {}

	public static void setKnown(String csv) {
		KNOWN.clear();
		for (String s : csv.split(",")) if (!s.isBlank()) KNOWN.add(s.trim());
	}

	static String fx(Constellation c) {
		return switch (c.id) {
			case "kitsune" -> "sun_mid";
			case "torii" -> "lava";
			case "fuji" -> "coma";
			case "koi" -> "sun_outer";
			case "ryu" -> "aurora_green";
			case "katana" -> "mote";
			case "sakura" -> "petal";
			case "toro" -> "sun_core";
			case "ogi" -> "violet";
			case "eclipse" -> "dust";
			default -> "white";
		};
	}

	static void camera(Minecraft mc) {
		Camera camera = mc.gameRenderer.mainCamera();
		camYaw = camera.yRot();
		camPitch = camera.xRot();
		double yr = Math.toRadians(camYaw), xr = Math.toRadians(camPitch);
		fwX = -Math.sin(yr) * Math.cos(xr); fwY = -Math.sin(xr); fwZ = Math.cos(yr) * Math.cos(xr);
		rtX = -Math.cos(yr); rtZ = -Math.sin(yr);
		upX = -rtZ * fwY;
		upY = rtZ * fwX - rtX * fwZ;
		upZ = rtX * fwY;
	}

	// ───────── небо Земли ─────────

	public static void renderEarth(PoseStack pose, SubmitNodeCollector out) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD) || !Models.ready()) return;
		camera(mc);
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		BlockPos at = BlockPos.containing(cam);
		float stars = mc.level.environmentAttributes().getValue(EnvironmentAttributes.STAR_BRIGHTNESS, at);
		double starAngle = Math.toRadians(mc.level.environmentAttributes().getValue(EnvironmentAttributes.STAR_ANGLE, at));
		double sunAngle = Math.toRadians(mc.level.environmentAttributes().getValue(EnvironmentAttributes.SUN_ANGLE, at));
		// выше облаков звёзд больше (край космоса)
		stars = (float) Math.max(stars, Math.max(0, Math.min(1, (cam.y - 1300) / 600)));
		// в полной фазе затмения днём видны звёзды и планеты
		stars = (float) Math.max(stars, EclipseFx.starBoost());
		if (stars < 0.02F) return;
		double t = mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		double D = Math.max(40, mc.options.getEffectiveRenderDistance() * 16.0 * 0.78);
		boolean scoping = mc.player != null && mc.player.isScoping();
		constellations(pose, out, starAngle + Atlas.seasonShift(t), D, stars, scoping, true, t);
		planets(pose, out, sunAngle, D, stars, t, scoping);
		comets(pose, out, sunAngle, D, stars, t);
	}

	// ───────── созвездия ─────────

	/** angle — поворот сферы (0 в космосе); D — расстояние до «купола» */
	static void constellations(PoseStack pose, SubmitNodeCollector out, double angle, double D, double fade, boolean scoping, boolean horizon, double t) {
		// на какое созвездие направлена труба
		int aim = -1;
		double best = Math.toRadians(8);
		for (int i = 0; i < SolarSystem.CONSTELLATIONS.size(); i++) {
			SolarSystem.skyDir(SolarSystem.CONSTELLATIONS.get(i).center, angle, V);
			double a = Math.acos(Math.max(-1, Math.min(1, V[0] * fwX + V[1] * fwY + V[2] * fwZ)));
			if (a < best) { best = a; aim = i; }
		}
		if (scoping && aim >= 0 && aim == aimIndex) aimFade = Math.min(1, aimFade + 0.006);
		else if (aim != aimIndex || !scoping) { aimFade = Math.max(0, aimFade - 0.05); if (aimFade == 0) aimIndex = aim; }
		for (int i = 0; i < SolarSystem.CONSTELLATIONS.size(); i++) {
			Constellation c = SolarSystem.CONSTELLATIONS.get(i);
			SolarSystem.skyDir(c.center, angle, V);
			if (horizon && V[1] < -0.15) continue;
			if (V[0] * fwX + V[1] * fwY + V[2] * fwZ < 0.1) continue;      // за спиной
			boolean known = KNOWN.contains("c:" + c.id);
			double lines = (known ? 0.55 : 0) + (i == aimIndex ? aimFade : 0);
			String col = fx(c);
			// линии
			if (lines > 0.05) {
				String m = "fx/ray_" + col + (lines > 0.6 ? "_a" : "_c");
				for (int[] l : c.lines) {
					SolarSystem.skyDir(c.stars[l[0]], angle, V);
					SolarSystem.skyDir(c.stars[l[1]], angle, W);
					line(pose, out, V[0] * D, V[1] * D, V[2] * D, W[0] * D, W[1] * D, W[2] * D, D * 0.02, m);
				}
			}
			// звёзды
			for (int k = 0; k < c.stars.length; k++) {
				SolarSystem.skyDir(c.stars[k], angle, V);
				if (horizon && V[1] < -0.02) continue;
				double tw = 0.75 + 0.25 * Math.sin(t * 0.06 + k * 2.3 + i);
				double size = D * (0.010 + (k % 3 == 0 ? 0.006 : 0)) * tw * (0.5 + 0.5 * fade);
				String m = fade > 0.6 ? "fx/glow_" + col + "_a" : fade > 0.3 ? "fx/glow_" + col + "_b" : "fx/glow_" + col + "_c";
				billboard(pose, out, V[0] * D, V[1] * D, V[2] * D, size, m, 0);
				if (k % 3 == 0 && fade > 0.4) billboard(pose, out, V[0] * D, V[1] * D, V[2] * D, size * 0.8, "fx/spark_white_c", (float) (t * 0.2 + k * 20));
			}
		}
	}

	// ───────── планеты и кометы с Земли ─────────

	private static void planets(PoseStack pose, SubmitNodeCollector out, double sunAngle, double D, double stars, double t, boolean scoping) {
		Body earth = SolarSystem.byId("earth");
		earth.pos(t, E);
		for (Body b : EarthSky.VISIBLE) {
			b.pos(t, T);
			double[] base = SolarSystem.seenFromEarth(E, T);
			if (base == null) continue;
			SolarSystem.skyDir(base, sunAngle, V);
			if (V[1] < -0.03) continue;
			double br = EarthSky.brightness(b);
			double vis = Math.min(1, stars * 1.6 + (b.id.equals("venus") ? 0.35 : 0));
			if (vis < 0.05) continue;
			double size = D * (0.008 + 0.014 * br) * vis * (scoping ? 1.4 : 1);
			String col = switch (b.id) {
				case "venus" -> "sun_core";
				case "mars" -> "lava";
				case "jupiter" -> "dust";
				case "saturn" -> "sun_mid";
				case "florana" -> "violet";
				default -> "white";
			};
			double pulse = b.id.equals("florana") ? 0.75 + 0.25 * Math.sin(t * 0.03) : 1;
			billboard(pose, out, V[0] * D, V[1] * D, V[2] * D, size * 2.2 * pulse, "fx/glow_" + col + "_c", 0);
			billboard(pose, out, V[0] * D, V[1] * D, V[2] * D, size * pulse, "fx/glow_" + col + "_a", 0);
			if (br > 0.75) billboard(pose, out, V[0] * D, V[1] * D, V[2] * D, size * 1.6, "fx/spark_" + col, 0);
		}
	}

	private static void comets(PoseStack pose, SubmitNodeCollector out, double sunAngle, double D, double stars, double t) {
		Body earth = SolarSystem.byId("earth");
		earth.pos(t, E);
		SolarSystem.skyDir(SolarSystem.sphere(0, 4), sunAngle, S);              // направление на Солнце
		for (Comet c : SolarSystem.COMETS) {
			if (c.minor) continue;
			c.pos(t, T);
			double r = Math.sqrt(T[0] * T[0] + T[2] * T[2]);
			if (r > 9000) continue;
			double[] base = SolarSystem.seenFromEarth(E, T);
			if (base == null) continue;
			SolarSystem.skyDir(base, sunAngle, V);
			if (V[1] < 0) continue;
			double act = Math.min(1, 3000 / Math.max(500, r)) * Math.min(1, stars * 2);
			if (act < 0.05) continue;
			// хвост: от Солнца, по дуге неба
			double tx = V[0] - S[0], ty = V[1] - S[1], tz = V[2] - S[2];
			double tl = Math.max(1e-3, Math.sqrt(tx * tx + ty * ty + tz * tz));
			tx /= tl; ty /= tl; tz /= tl;
			int n = 14;
			for (int k = n - 1; k >= 0; k--) {
				double f = k / (double) n;
				double len = 0.25 * act * f;
				double x = V[0] + tx * len, y = V[1] + ty * len, z = V[2] + tz * len;
				double l = Math.sqrt(x * x + y * y + z * z);
				billboard(pose, out, x / l * D, y / l * D, z / l * D, D * (0.01 + 0.03 * f) * act, f < 0.3 ? "fx/glow_ion_b" : "fx/glow_ion_d", 0);
			}
			billboard(pose, out, V[0] * D, V[1] * D, V[2] * D, D * 0.012 * act, "fx/glow_coma_a", 0);
		}
	}

	// ───────── в космосе ─────────

	public static void renderSpace(PoseStack pose, SubmitNodeCollector out, double skyR, double t) {
		Minecraft mc = Minecraft.getInstance();
		camera(mc);
		boolean scoping = mc.player != null && mc.player.isScoping();
		constellations(pose, out, 0, skyR * 0.9, 1, scoping, false, t);
	}

	// ───────── спрайты ─────────

	static void billboard(PoseStack pose, SubmitNodeCollector out, double rx, double ry, double rz, double size, String model, float roll) {
		if (size <= 0) return;
		pose.pushPose();
		pose.translate(rx, ry, rz);
		pose.rotateDegrees(Axis.YP, 180F - camYaw);
		pose.rotateDegrees(Axis.XP, -camPitch);
		if (roll != 0) pose.rotateDegrees(Axis.ZP, roll);
		pose.scale((float) size, (float) size, (float) size);
		((Matrix3f) pose.last().normal()).set(0, 0, 0, 0, 0, 0, 0, 1, 0);
		Models.get(model).submit(pose, out, FULL, OverlayTexture.NO_OVERLAY, 0);
		pose.popPose();
	}

	/** Линия между двумя точками (относительно камеры): вытянутый спрайт-луч, повёрнутый по экрану */
	static void line(PoseStack pose, SubmitNodeCollector out, double ax, double ay, double az, double bx, double by, double bz, double width, String model) {
		double fa = ax * fwX + ay * fwY + az * fwZ, fb = bx * fwX + by * fwY + bz * fwZ;
		if (fa < 0.2 || fb < 0.2) return;
		double sax = (ax * rtX + az * rtZ) / fa, say = (ax * upX + ay * upY + az * upZ) / fa;
		double sbx = (bx * rtX + bz * rtZ) / fb, sby = (bx * upX + by * upY + bz * upZ) / fb;
		float roll = (float) Math.toDegrees(Math.atan2(-(sbx - sax), sby - say));
		double mx = (ax + bx) / 2, my = (ay + by) / 2, mz = (az + bz) / 2;
		double len = Math.sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by) + (az - bz) * (az - bz));
		pose.pushPose();
		pose.translate(mx, my, mz);
		pose.rotateDegrees(Axis.YP, 180F - camYaw);
		pose.rotateDegrees(Axis.XP, -camPitch);
		pose.rotateDegrees(Axis.ZP, roll);
		pose.scale((float) width, (float) (len * 1.15), (float) width);
		((Matrix3f) pose.last().normal()).set(0, 0, 0, 0, 0, 0, 0, 1, 0);
		Models.get(model).submit(pose, out, FULL, OverlayTexture.NO_OVERLAY, 0);
		pose.popPose();
	}
}
