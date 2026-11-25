package gg.phase.space.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import gg.phase.space.SolarSystem;
import gg.phase.space.SolarSystem.Body;
import gg.phase.space.SolarSystem.Comet;
import gg.phase.space.SolarSystem.Deep;
import gg.phase.space.SpaceConfig;
import gg.phase.space.SpaceEvents;
import gg.phase.space.SpaceTravel;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;

/**
 * Отрисовка космоса (клиент, только в мире phasespace:space).
 *
 * <p><b>Небо</b> — восьмиугольная призма вокруг камеры: звёзды, Млечный Путь, туманности, галактики.
 * <p><b>Дальние объекты</b> — каждая «система» приближается к камере одним коэффициентом (угловые размеры
 * точные), поэтому видно и Нептун за 16 тысяч блоков, и квазар за 130 тысяч.
 * <p><b>Свет</b> — каждая грань куба получает свой уровень света по углу к Солнцу; нормаль подменяется
 * на «вверх», чтобы ванильный направленный свет предметов не красил грани.
 * <p><b>События</b> (SpaceEvents) — метеорный поток, вспышка, затмение, великая комета, блуждающая планета,
 * гамма-всплеск — считаются по игровому времени, как и на сервере.
 */
public final class SpaceRenderer {
	private static final int FULL = 0xF000F0;
	private static final String[] F = { "n", "s", "e", "w", "u", "d" };
	private static final double[][] N = { { 0, 0, -1 }, { 0, 0, 1 }, { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 } };
	private static final double K_FAR = 6000;

	// кадр
	private static double cx, cy, cz, skyR, near, far, time;
	private static float camYaw, camPitch;
	private static double fwX, fwY, fwZ, rtX, rtZ, upX, upY, upZ;
	private static int quality;
	private static double eclipse, flare, gamma, shower;
	private static final double[] P = new double[3], Q = new double[3], V = new double[3], W = new double[3];

	// имена моделей
	private static final int NB = SolarSystem.BODIES.size();
	private static final String[][] FACE = new String[NB][], CLOUDS = new String[NB][], LIGHTS = new String[NB][], ATMO = new String[NB][];
	private static final String[] SUN = new String[6], CORONA_A = new String[6], CORONA_B = new String[6], SKY_WALL = new String[8];
	private static final String[] ROGUE = names("b/rogue_"), ROGUE_LIGHTS = names("b/rogue_lights_");

	// обломки поясов (картинка вдали; рядом — настоящие астероиды из блоков)
	private static final int DECOR = SolarSystem.BELT_DECOR + SolarSystem.KUIPER_DECOR;
	private static final double[] DR = new double[DECOR], DPH = new double[DECOR], DY = new double[DECOR], DS = new double[DECOR],
		DSPIN = new double[DECOR], DPER = new double[DECOR];
	private static final String[] DMODEL = new String[DECOR];
	// пылевая дымка поясов (зодиакальный свет)
	private static final int HAZE = 140;
	private static final double[][] HAZE_P = new double[HAZE][4];

	// облака частиц у планет (споры Флораны, лепестки Сакуры)
	private static final int SPORES = 180;
	private static final double[] SR = new double[SPORES], SPH = new double[SPORES], SW = new double[SPORES], SINC = new double[SPORES], SSZ = new double[SPORES];

	// туманность Аматэрасу: сгустки газа и молодые звёзды
	private static final int NEB = 420, NEB_STARS = 90;
	private static final double[][] NEB_P = new double[NEB][5];
	private static final String[] NEB_M = new String[NEB];
	private static final double[][] NEB_S = new double[NEB_STARS][4];

	// пыль у камеры, метеоры, скорость камеры
	private static final int MOTES = 120;
	private static final double[][] MOTE = new double[MOTES][4];
	private static boolean motesInit;
	private static final List<double[]> METEORS = new ArrayList<>(); // x y z vx vy vz age life size
	private static final RandomSource RND = RandomSource.create();
	private static double velX, velY, velZ, lastX = Double.NaN, lastY, lastZ;

	static {
		for (Body b : SolarSystem.BODIES) {
			int i = b.index;
			FACE[i] = names("b/" + b.id + "_");
			if (b.clouds) CLOUDS[i] = names("b/" + b.id + "_clouds_");
			if (b.lights) LIGHTS[i] = names("b/" + b.id + "_lights_");
			if (b.atmo) ATMO[i] = names("b/" + b.id + "_atmo_");
		}
		for (int f = 0; f < 6; f++) {
			SUN[f] = "sun/sun_" + F[f];
			CORONA_A[f] = "sun/corona_a_" + F[f];
			CORONA_B[f] = "sun/corona_b_" + F[f];
		}
		for (int k = 0; k < 8; k++) SKY_WALL[k] = "sky/wall_" + k;
		RandomSource r = RandomSource.create(20261124L);
		for (int i = 0; i < DECOR; i++) {
			boolean kuiper = i >= SolarSystem.BELT_DECOR;
			double in = kuiper ? SolarSystem.KUIPER_INNER : SolarSystem.BELT_INNER, out = kuiper ? SolarSystem.KUIPER_OUTER : SolarSystem.BELT_OUTER;
			double t = (r.nextDouble() + r.nextDouble() + r.nextDouble()) / 3.0;
			DR[i] = in + (out - in) * t;
			DPH[i] = r.nextDouble() * SolarSystem.TAU;
			DY[i] = SolarSystem.PLANE_Y + r.nextGaussian() * (kuiper ? 300 : 130);
			DS[i] = (kuiper ? 4 : 3) + Math.pow(r.nextDouble(), 2.2) * (kuiper ? 14 : 12);
			DSPIN[i] = (r.nextDouble() - 0.5) * 0.9;
			DPER[i] = SolarSystem.EARTH_YEAR * Math.pow(DR[i] / SolarSystem.EARTH_ORBIT, 1.5);
			int m = kuiper ? (r.nextDouble() < 0.8 ? 3 : 0) : (r.nextDouble() < 0.06 ? 5 : r.nextInt(5));
			if (m == 3 && !kuiper) m = 0;
			DMODEL[i] = "rock/rock_" + m;
		}
		for (int i = 0; i < HAZE; i++) {
			boolean kuiper = i >= HAZE * 2 / 3;
			double in = kuiper ? SolarSystem.KUIPER_INNER : SolarSystem.BELT_INNER, out = kuiper ? SolarSystem.KUIPER_OUTER : SolarSystem.BELT_OUTER;
			double a = r.nextDouble() * SolarSystem.TAU, rr = in + (out - in) * (0.2 + 0.6 * r.nextDouble());
			HAZE_P[i][0] = Math.cos(a) * rr;
			HAZE_P[i][1] = SolarSystem.PLANE_Y + r.nextGaussian() * (kuiper ? 260 : 110);
			HAZE_P[i][2] = Math.sin(a) * rr;
			HAZE_P[i][3] = (kuiper ? 2600 : 1300) * (0.7 + r.nextDouble() * 0.7);
		}
		for (int i = 0; i < SPORES; i++) {
			SR[i] = 0.62 + r.nextDouble() * 0.75;
			SPH[i] = r.nextDouble() * SolarSystem.TAU;
			SW[i] = (0.0006 + r.nextDouble() * 0.0016) * (r.nextBoolean() ? 1 : -1);
			SINC[i] = (r.nextDouble() - 0.5) * 0.7;
			SSZ[i] = 0.5 + r.nextDouble() * 1.2;
		}
		String[] nebCol = { "neb_pink", "neb_pink", "neb_violet", "neb_blue", "neb_teal", "neb_gold" };
		for (int i = 0; i < NEB; i++) {
			// сгустки: несколько «лопастей» вдоль изогнутой оси, плотнее к центру
			double s = r.nextDouble() * 2 - 1;
			double ax = s * 0.42, ay = Math.sin(s * 3) * 0.08, az = Math.sin(s * 2.2) * 0.18;
			double spread = 0.12 + 0.12 * (1 - Math.abs(s));
			NEB_P[i][0] = ax + r.nextGaussian() * spread;
			NEB_P[i][1] = ay + r.nextGaussian() * spread * 0.55;
			NEB_P[i][2] = az + r.nextGaussian() * spread;
			NEB_P[i][3] = 0.10 + r.nextDouble() * 0.16;            // размер в долях туманности
			NEB_P[i][4] = r.nextDouble() * 360;                   // поворот
			// строение: золотое ядро, розово-фиолетовая середина, голубые и бирюзовые края
			double edge = Math.abs(s) + Math.sqrt(NEB_P[i][1] * NEB_P[i][1] + NEB_P[i][2] * NEB_P[i][2]) * 1.5;
			int c = edge < 0.25 ? (r.nextDouble() < 0.6 ? 5 : 0) : edge < 0.6 ? (r.nextDouble() < 0.6 ? 0 : 2) : (r.nextBoolean() ? 3 : 4);
			double al = r.nextDouble();
			NEB_M[i] = "fx/puff_" + nebCol[c] + (al < 0.2 ? "_b" : al < 0.65 ? "_c" : "_d");
		}
		for (int i = 0; i < NEB_STARS; i++) {
			NEB_S[i][0] = r.nextGaussian() * 0.16;
			NEB_S[i][1] = r.nextGaussian() * 0.07;
			NEB_S[i][2] = r.nextGaussian() * 0.16;
			NEB_S[i][3] = r.nextDouble();
		}
	}

	private SpaceRenderer() {}

	private static String[] names(String prefix) {
		String[] a = new String[6];
		for (int f = 0; f < 6; f++) a[f] = prefix + F[f];
		return a;
	}

	private static boolean inSpace(Minecraft mc) {
		return mc.level != null && mc.level.dimension().equals(SpaceTravel.SPACE);
	}

	// ───────── Кадр ─────────

	public static void render(PoseStack pose, SubmitNodeCollector out) {
		Minecraft mc = Minecraft.getInstance();
		if (!inSpace(mc)) {
			SkyFx.renderEarth(pose, out);
			AtmosphereFx.render(pose, out);
			EclipseFx.render(pose, out);
			return;
		}
		if (!Models.ready()) return;
		if (ShaderCompat.shadowPass()) return;
		SpaceConfig cfg = SpaceConfig.get();
		quality = Math.max(0, Math.min(2, cfg.quality));
		float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		double t = mc.level.getGameTime() + pt;
		time = t;
		Camera camera = mc.gameRenderer.mainCamera();
		Vec3 cam = camera.position();
		cx = cam.x; cy = cam.y; cz = cam.z;
		camYaw = camera.yRot();
		camPitch = camera.xRot();
		double yr = Math.toRadians(camYaw), xr = Math.toRadians(camPitch);
		fwX = -Math.sin(yr) * Math.cos(xr); fwY = -Math.sin(xr); fwZ = Math.cos(yr) * Math.cos(xr);
		rtX = -Math.cos(yr); rtZ = -Math.sin(yr);                         // вправо по экрану
		// вверх по экрану = right × forward
		upX = 0 * fwZ - rtZ * fwY;
		upY = rtZ * fwX - rtX * fwZ;
		upZ = rtX * fwY - 0 * fwX;
		double rd = mc.options.getEffectiveRenderDistance() * 16.0;
		double fogStart = rd - Math.max(4, Math.min(64, rd / 10.0));
		skyR = Math.max(16, Math.min(rd * 0.9, fogStart / 1.0824 - 1.5));
		near = skyR * 0.42;
		far = skyR * 0.9;
		boolean ev = cfg.events;
		eclipse = ev ? SpaceEvents.eclipse(t) : 0;
		flare = ev ? SpaceEvents.flare(t) : 0;
		gamma = ev ? SpaceEvents.gamma(t) : 0;
		shower = ev ? SpaceEvents.shower(t) : 0;

		if (!ShaderCompat.shadersOn() || cfg.skyWithShaders) sky(pose, out);
		twinkle(pose, out, t);
		SkyFx.renderSpace(pose, out, skyR, t);
		decor(pose, out, t);

		// всё остальное — от дальних к ближним, чтобы полупрозрачное ложилось правильно
		List<double[]> order = new ArrayList<>(80);
		order.add(new double[] { dist(0, SolarSystem.PLANE_Y, 0), -1 });
		for (Body b : SolarSystem.BODIES) {
			if (!b.isPlanet()) continue;
			b.pos(t, P);
			order.add(new double[] { dist(P[0], P[1], P[2]), b.index });
		}
		for (int i = 0; i < SolarSystem.COMETS.size(); i++) {
			SolarSystem.COMETS.get(i).pos(t, P);
			order.add(new double[] { dist(P[0], P[1], P[2]), 1000 + i });
		}
		for (int i = 0; i < SolarSystem.DEEP.size(); i++) {
			Deep d = SolarSystem.DEEP.get(i);
			order.add(new double[] { dist(d.x, d.y, d.z), 2000 + i });
		}
		if (ev && SpaceEvents.greatComet(t, P, null)) order.add(new double[] { dist(P[0], P[1], P[2]), 3000 });
		if (ev && SpaceEvents.rogue(t, P)) order.add(new double[] { dist(P[0], P[1], P[2]), 3001 });
		order.sort((a, b) -> Double.compare(b[0], a[0]));
		for (double[] o : order) {
			int id = (int) o[1];
			if (id < 0) sun(pose, out, t);
			else if (id < 1000) system(pose, out, SolarSystem.BODIES.get(id), t);
			else if (id < 2000) comet(pose, out, SolarSystem.COMETS.get(id - 1000), t);
			else if (id < 3000) deep(pose, out, SolarSystem.DEEP.get(id - 2000), t);
			else if (id == 3000) greatComet(pose, out, t);
			else rogue(pose, out, t);
		}
		portals(pose, out, t);
		meteors(pose, out, pt);
		motes(pose, out);
		warpTunnel(pose, out, t);
		if (cfg.lensFlare) lensFlare(pose, out);
		if (gamma > 0.02) billboard(pose, out, fwX * 2, fwY * 2, fwZ * 2, 2.6, gamma > 0.5 ? "fx/glow_gamma_a" : gamma > 0.2 ? "fx/glow_gamma_b" : "fx/glow_gamma_c", FULL, 0);
	}

	private static double dist(double x, double y, double z) {
		double dx = x - cx, dy = y - cy, dz = z - cz;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** Во сколько раз приблизить объект на расстоянии d, чтобы он оказался внутри неба */
	private static double scaleFor(double d) {
		if (d <= near) return 1;
		double dp = near + (far - near) * (1 - Math.exp(-(d - near) / K_FAR));
		return dp / d;
	}

	private static String alpha(String base, double a) {
		return base + (a > 0.75 ? "_a" : a > 0.42 ? "_b" : a > 0.2 ? "_c" : "_d");
	}

	// ───────── Небо и дымка ─────────

	private static void sky(PoseStack pose, SubmitNodeCollector out) {
		double s = skyR * 2;
		for (int k = 0; k < 8; k++) {
			pose.pushPose();
			pose.rotateDegrees(Axis.YP, k * 45F);
			pose.scale((float) s, (float) s, (float) s);
			submitFlat(pose, out, SKY_WALL[k], FULL, 0, 0, 1);
			pose.popPose();
		}
		pose.pushPose();
		pose.scale((float) s, (float) s, (float) s);
		submitFlat(pose, out, "sky/cap_top", FULL, 0, -1, 0);
		submitFlat(pose, out, "sky/cap_bottom", FULL, 0, 1, 0);
		pose.popPose();
	}

	/** Пылевые пояса светятся тусклой полосой — видно, где кольцо астероидов */
	private static void haze(PoseStack pose, SubmitNodeCollector out) {
		int step = quality == 0 ? 3 : 1;
		for (int i = 0; i < HAZE; i += step) {
			double[] h = HAZE_P[i];
			double dx = h[0] - cx, dy = h[1] - cy, dz = h[2] - cz;
			double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (d < h[3] * 0.9) continue;                // изнутри дымки не видно
			double s = scaleFor(d);
			boolean kuiper = i >= HAZE * 2 / 3;
			billboard(pose, out, dx * s, dy * s, dz * s, h[3] * s, kuiper ? "fx/puff_ion_d" : "fx/puff_dust_d", FULL, (float) (i * 47 % 360));
		}
	}

	// ───────── Солнце ─────────

	private static void sun(PoseStack pose, SubmitNodeCollector out, double t) {
		Body sun = SolarSystem.SUN;
		double d = dist(0, SolarSystem.PLANE_Y, 0);
		double s = scaleFor(d);
		double rx = -cx * s, ry = (SolarSystem.PLANE_Y - cy) * s, rz = -cz * s;
		double size = sun.size * s;
		double spin = sun.spinDeg(t);
		double lit = 1 - eclipse;
		double boost = 1 + flare * 0.8;
		// дальние слои свечения
		if (lit > 0.05) {
			billboard(pose, out, rx, ry, rz, size * 4.6 * boost, alpha("fx/glow_sun_outer", 0.55 * lit), FULL, 0);
			billboard(pose, out, rx, ry, rz, size * 2.5 * boost, alpha("fx/glow_sun_mid", 1.0 * lit), FULL, 0);
			// лучи: длинные тонкие спрайты, медленно вращаются
			int rays = quality == 0 ? 6 : 12;
			for (int k = 0; k < rays; k++) {
				double len = size * (3.2 + 1.6 * Math.sin(k * 2.3 + t * 0.002)) * boost;
				billboard(pose, out, rx, ry, rz, len, k % 3 == 0 ? "fx/ray_sun_core_c" : "fx/ray_sun_mid_c", FULL, (float) (k * 360.0 / rays + t * 0.01));
			}
		}
		pose.pushPose();
		pose.translate(rx, ry, rz);
		pose.rotateDegrees(Axis.ZP, (float) sun.tilt);
		pose.pushPose();
		pose.rotateDegrees(Axis.YP, (float) spin);
		pose.scale((float) size, (float) size, (float) size);
		int coreLight = eclipse < 0.5 ? FULL : sky(Math.max(1, (int) Math.round(15 * (1 - eclipse))));
		cubeFlat(pose, out, SUN, null, coreLight);
		pose.popPose();
		pose.pushPose();
		pose.rotateDegrees(Axis.YP, (float) (-spin * 1.7));
		pose.rotateDegrees(Axis.XP, 12F);
		float c1 = (float) (size * (1.10 + 0.015 * Math.sin(t * 0.011)) * (1 + flare * 0.12));
		pose.scale(c1, c1, c1);
		cubeFlat(pose, out, CORONA_A, null, FULL);
		pose.popPose();
		pose.pushPose();
		pose.rotateDegrees(Axis.YP, (float) (spin * 2.3 + 40));
		pose.rotateDegrees(Axis.ZP, -18F);
		float c2 = (float) (size * (1.24 + 0.02 * Math.sin(t * 0.007 + 1)) * (1 + flare * 0.25));
		pose.scale(c2, c2, c2);
		cubeFlat(pose, out, CORONA_B, null, FULL);
		pose.popPose();
		pose.popPose();
		if (eclipse > 0.02) {
			// Затмение Фазы: чёрный диск наползает, вокруг — корона, в конце «бриллиантовое кольцо»
			double slide = (1 - eclipse) * size * 1.4;
			double ox = rtX * slide, oz = rtZ * slide;
			double tx = rx - fwX * size * 0.9 + ox, ty = ry - fwY * size * 0.9, tz = rz - fwZ * size * 0.9 + oz;
			billboard(pose, out, tx, ty, tz, size * 1.5, "fx/disc", FULL, 0);
			if (eclipse > 0.6) {
				billboard(pose, out, rx - fwX * size, ry - fwY * size, rz - fwZ * size, size * 2.0, "fx/ring_sun_core_a", FULL, 0);
				billboard(pose, out, rx - fwX * size, ry - fwY * size, rz - fwZ * size, size * 3.4, "fx/glow_sun_core_c", FULL, 0);
				if (eclipse < 0.95) {
					double a = 0.7;
					billboard(pose, out, rx - fwX * size + (rtX * Math.cos(a) + upX * Math.sin(a)) * size * 0.7,
						ry - fwY * size + upY * Math.sin(a) * size * 0.7, rz - fwZ * size + (rtZ * Math.cos(a) + upZ * Math.sin(a)) * size * 0.7,
						size * 0.9, "fx/spark_white", FULL, 0);
				}
			}
		} else {
			billboard(pose, out, rx, ry, rz, size * 1.6 * boost, "fx/glow_sun_core_b", FULL, 0);
			prominences(pose, out, rx, ry, rz, size, t);
		}
		SpaceEvents.Event e = flare > 0.02 ? SpaceEvents.at(t) : null;
		if (e != null) {
			// корональный выброс: расширяющееся кольцо
			double p = e.progress(t);
			double cme = size * (1.3 + p * 14);
			billboard(pose, out, rx, ry, rz, cme, p < 0.5 ? "fx/ring_sun_mid_a" : "fx/ring_sun_outer_c", FULL, (float) (p * 40));
		}
	}

	/** Протуберанцы: дуги из светящихся сгустков над поверхностью, во время вспышки — огромные */
	private static void prominences(PoseStack pose, SubmitNodeCollector out, double rx, double ry, double rz, double size, double t) {
		int arcs = quality == 0 ? 3 : 7, pts = quality == 0 ? 8 : 14;
		for (int a = 0; a < arcs; a++) {
			double base = a * 1.9 + t * 0.00004 * (a % 2 == 0 ? 1 : -1);
			double lat = Math.sin(a * 2.7) * 0.6;
			double span = 0.22 + 0.06 * Math.sin(a * 5.1);
			double life = 0.5 + 0.5 * Math.sin(t * 0.0021 + a * 1.3);
			double h = (0.18 + 0.16 * life) * (1 + flare * 2.5);
			for (int i = 0; i <= pts; i++) {
				double f = i / (double) pts;
				double ang = base + span * (f - 0.5);
				double rr = 0.5 * (1.02 + h * Math.sin(Math.PI * f));
				double x = Math.cos(ang) * Math.cos(lat) * rr, y = Math.sin(lat) * rr, z = Math.sin(ang) * Math.cos(lat) * rr;
				double sz = size * (0.045 + 0.03 * Math.sin(Math.PI * f)) * (0.6 + 0.6 * life) * (1 + flare);
				billboard(pose, out, rx + x * size, ry + y * size, rz + z * size, sz, i % 3 == 0 ? "fx/glow_sun_core_b" : "fx/glow_prom_b", FULL, 0);
			}
		}
	}

	/** Блики объектива, когда Солнце в кадре */
	private static void lensFlare(PoseStack pose, SubmitNodeCollector out) {
		if (eclipse > 0.4) return;
		double sx = -cx, sy = SolarSystem.PLANE_Y - cy, sz = -cz;
		double sd = Math.sqrt(sx * sx + sy * sy + sz * sz);
		if (sd < SolarSystem.SUN.size) return;
		sx /= sd; sy /= sd; sz /= sd;
		double f = sx * fwX + sy * fwY + sz * fwZ;
		if (f < 0.55) return;
		double tx = (sx * rtX + sz * rtZ) / f, ty = (sx * upX + sy * upY + sz * upZ) / f;
		double D = skyR * 0.25, fade = Math.min(1, (f - 0.55) / 0.25) * (1 - eclipse);
		double[] m = { 0.6, 0.3, -0.15, -0.45, -0.8, -1.3 };
		String[] mdl = { "fx/flare_b", "fx/flare_a", "fx/glow_sun_mid_d", "fx/flare_c", "fx/flare_a", "fx/flare_b" };
		double[] sz2 = { 0.05, 0.08, 0.14, 0.06, 0.1, 0.18 };
		for (int k = 0; k < m.length; k++) {
			double px = fwX + rtX * tx * m[k] + upX * ty * m[k];
			double py = fwY + upY * ty * m[k];
			double pz = fwZ + rtZ * tx * m[k] + upZ * ty * m[k];
			double l = Math.sqrt(px * px + py * py + pz * pz);
			billboard(pose, out, px / l * D, py / l * D, pz / l * D, D * sz2[k] * fade, mdl[k], FULL, 0);
		}
	}

	// ───────── Планетные системы ─────────

	private static void system(PoseStack pose, SubmitNodeCollector out, Body planet, double t) {
		planet.pos(t, P);
		double px = P[0], py = P[1], pz = P[2];
		double d = dist(px, py, pz);
		double s = scaleFor(d);
		double ang = planet.systemRadius / Math.max(1, d);
		if (!visible(px - cx, py - cy, pz - cz, d, planet.systemRadius)) return;
		if (planet.size / d < 0.0012) {
			billboard(pose, out, (px - cx) * s, (py - cy) * s, (pz - cz) * s, Math.max(planet.size * 3, d * 0.004) * s, "fx/spark_white", FULL, 0);
			return;
		}
		List<double[]> parts = new ArrayList<>(planet.moons.size() + 2);
		parts.add(new double[] { d, -1 });
		for (int i = 0; i < planet.moons.size(); i++) {
			planet.moons.get(i).pos(t, Q);
			parts.add(new double[] { dist(Q[0], Q[1], Q[2]), i });
		}
		if (planet == SolarSystem.TORII_PARENT) {
			toriiPos(t, px, py, pz, Q);
			parts.add(new double[] { dist(Q[0], Q[1], Q[2]), 999 });
		}
		parts.sort((a, b) -> Double.compare(b[0], a[0]));
		for (double[] part : parts) {
			int i = (int) part[1];
			if (i == -1) body(pose, out, planet, t, s, px, py, pz);
			else if (i == 999) torii(pose, out, t, s, px, py, pz);
			else {
				Body m = planet.moons.get(i);
				m.pos(t, Q);
				if (m.size / Math.max(1, part[0]) < 0.0008) continue;
				body(pose, out, m, t, s, Q[0], Q[1], Q[2]);
			}
		}
		if (planet.spores && ang > 0.01) swarm(pose, out, planet, t, s, px, py, pz, false);
		if (planet.petals && ang > 0.01) swarm(pose, out, planet, t, s, px, py, pz, true);
	}

	/** Тело: грани со светом от Солнца, облака, ночные огни, атмосфера, сияния, гейзеры, кольцо */
	private static void body(PoseStack pose, SubmitNodeCollector out, Body b, double t, double s, double x, double y, double z) {
		double rx = (x - cx) * s, ry = (y - cy) * s, rz = (z - cz) * s;
		double size = b.size * s;
		double lx = -x, ly = SolarSystem.PLANE_Y - y, lz = -z;
		double ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
		lx /= ll; ly /= ll; lz /= ll;
		double spin = b.spinDeg(t);
		int[] light = new int[6];
		double[] bright = new double[6];
		faceLight(b.tilt, spin, lx, ly, lz, bright, light, b.atmo ? 0.05 : 0.0);
		int i = b.index;
		if (b.id.equals("earth")) billboard(pose, out, rx, ry, rz, size * 1.7, "fx/glow_earth_c", FULL, 0);
		if (b.id.equals("florana")) {
			billboard(pose, out, rx, ry, rz, size * 2.4, "fx/glow_violet_c", FULL, 0);
			billboard(pose, out, rx, ry, rz, size * 1.6, "fx/glow_violet_b", FULL, 0);
		}
		if (b.id.equals("sakura")) billboard(pose, out, rx, ry, rz, size * 2.0, "fx/glow_petal_c", FULL, 0);
		String halo = switch (b.id) {
			case "venus", "jupiter", "saturn" -> "fx/glow_dust_d";
			case "mars" -> "fx/glow_lava_d";
			case "uranus", "titan" -> b.id.equals("titan") ? "fx/glow_sun_mid_d" : "fx/glow_coma_d";
			case "neptune" -> "fx/glow_ion_d";
			default -> null;
		};
		if (halo != null) billboard(pose, out, rx, ry, rz, size * 1.75, halo, FULL, 0);
		if (b.id.equals("lumi")) billboard(pose, out, rx, ry, rz, size * 2.2, "fx/glow_aurora_c", FULL, 0);
		pose.pushPose();
		pose.translate(rx, ry, rz);
		pose.rotateDegrees(Axis.ZP, (float) b.tilt);
		pose.pushPose();
		pose.rotateDegrees(Axis.YP, (float) spin);
		pose.scale((float) (size * b.sx), (float) (size * b.sy), (float) (size * b.sz));
		for (int f = 0; f < 6; f++) if (facing(f, b.tilt, spin, rx, ry, rz)) submitFace(pose, out, FACE[i][f], light[f], f);
		if (LIGHTS[i] != null) {
			pose.pushPose();
			pose.scale(1.006F, 1.006F, 1.006F);
			for (int f = 0; f < 6; f++) {
				if (bright[f] > 0.32 || !facing(f, b.tilt, spin, rx, ry, rz)) continue;
				submitFace(pose, out, LIGHTS[i][f], FULL, f);
			}
			pose.popPose();
		}
		pose.popPose();
		if (CLOUDS[i] != null) {
			double cs = spin * 1.12 + 25;
			int[] cl = new int[6];
			faceLight(b.tilt, cs, lx, ly, lz, new double[6], cl, 0.08);
			pose.pushPose();
			pose.rotateDegrees(Axis.YP, (float) cs);
			float k = (float) (size * 1.035);
			pose.scale(k, k, k);
			for (int f = 0; f < 6; f++) if (facing(f, b.tilt, cs, rx, ry, rz)) submitFace(pose, out, CLOUDS[i][f], cl[f], f);
			pose.popPose();
		}
		if (ATMO[i] != null) {
			pose.pushPose();
			pose.rotateDegrees(Axis.YP, (float) spin);
			float k = (float) (size * b.atmoScale);
			pose.scale((float) (k * b.sx), (float) (k * b.sy), (float) (k * b.sz));
			for (int f = 0; f < 6; f++) {
				if (!facing(f, b.tilt, spin, rx, ry, rz)) continue;
				int lv = Math.max(6, (light[f] >> 20) & 15);
				submitFace(pose, out, ATMO[i][f], b.id.equals("florana") || b.id.equals("sakura") ? FULL : sky(lv), f);
			}
			pose.popPose();
		}
		if (b.ring != null) {
			pose.pushPose();
			pose.rotateDegrees(Axis.YP, (float) (spin * 0.05));
			float k = (float) (size * b.ringOuter * 2);
			pose.scale(k, k, k);
			int lv = b.ring.equals("florana") ? FULL : sky((int) Math.max(3, Math.round(13 * (1 - eclipse * 0.7))));
			submitFlat(pose, out, "ring/" + b.ring + "_u", lv, 0, 1, 0);
			submitFlat(pose, out, "ring/" + b.ring + "_d", lv, 0, -1, 0);
			pose.popPose();
		}
		pose.popPose();
		// полярные сияния: кольца свечения над полюсами, во время вспышки ярче
		if (b.aurora >= 0 && size > 1.5) {
			double a = 0.35 + flare * 0.65 + 0.1 * Math.sin(t * 0.05 + b.index);
			rotate(N[4], b.tilt, 0, V);
			String m = (b.id.equals("earth") ? "fx/ring_aurora_green" : b.id.equals("florana") ? "fx/ring_aurora_violet" : "fx/ring_aurora_blue") + (a > 0.6 ? "_a" : "_c");
			for (int pole = -1; pole <= 1; pole += 2) {
				double hx = V[0] * size * 0.56 * pole, hy = V[1] * size * 0.56 * pole, hz = V[2] * size * 0.56 * pole;
				billboard(pose, out, rx + hx, ry + hy, rz + hz, size * (0.55 + 0.25 * flare), m, FULL, (float) (t * 0.3));
				if (flare > 0.2) billboard(pose, out, rx + hx * 1.15, ry + hy * 1.15, rz + hz * 1.15, size * 0.9, alpha("fx/puff_aurora", flare * 0.5), FULL, (float) (t * 0.2));
			}
		}
		// грозы: молнии вспыхивают в облаках газовых гигантов и Венеры
		if ((b.id.equals("jupiter") || b.id.equals("saturn") || b.id.equals("venus")) && size > 3) {
			for (int k = 0; k < 3; k++) {
				long seed = (long) Math.floor(t / 7) * 31 + k * 977 + b.index;
				double r1 = hash01(seed), r2 = hash01(seed + 1), r3 = hash01(seed + 2);
				if (r1 > 0.55) continue;
				double life = SolarSystem.frac(t / 7);
				if (life > 0.35) continue;
				// точка на видимой половине: от центра к камере плюс разброс
				double d0 = Math.sqrt(rx * rx + ry * ry + rz * rz);
				double vx = -rx / d0, vy = -ry / d0, vz = -rz / d0;
				double ox = (r2 - 0.5) * size * 0.8, oy = (r3 - 0.5) * size * 0.8 * b.sy;
				billboard(pose, out, rx + vx * size * 0.52 + rtX * ox + upX * oy, ry + vy * size * 0.52 + upY * oy, rz + vz * size * 0.52 + rtZ * ox + upZ * oy,
					size * (0.05 + 0.06 * r2) * (1 - life / 0.35), life < 0.1 ? "fx/spark_white" : "fx/glow_ion_b", FULL, (float) (r3 * 90));
			}
		}
		// гейзеры Энцелада и Тритона: струи льда с южного полюса
		if (b.geysers && size > 1.2) {
			rotate(N[5], b.tilt, spin, V);
			int jets = quality == 0 ? 6 : 14;
			for (int k = 0; k < jets; k++) {
				double f = ((t * 0.01 + k / (double) jets) % 1.0);
				double hh = size * (0.5 + f * 2.2);
				double wob = Math.sin(k * 2.1) * size * 0.25 * f;
				billboard(pose, out, rx + V[0] * hh + wob, ry + V[1] * hh, rz + V[2] * hh + wob * 0.6, size * (0.2 + f * 0.8), f < 0.5 ? "fx/glow_geyser_b" : "fx/glow_geyser_d", FULL, 0);
			}
		}
	}

	/** Яркость граней по направлению на Солнце: bright 0..1 и упакованный свет */
	private static void faceLight(double tilt, double spin, double lx, double ly, double lz, double[] bright, int[] light, double ambient) {
		double dim = 1 - eclipse * 0.8;
		for (int f = 0; f < 6; f++) {
			rotate(N[f], tilt, spin, V);
			double dot = V[0] * lx + V[1] * ly + V[2] * lz;
			double b = Math.max(0, (dot + 0.18) / 1.18);
			b = Math.max(ambient, Math.pow(b, 0.85) * dim);
			bright[f] = b;
			int lv = (int) Math.round(15 * 4 * b / (1 + 3 * b));
			light[f] = sky(Math.max(3, Math.min(15, lv)));
		}
	}

	private static int sky(int level) {
		return level << 20;
	}

	private static boolean facing(int f, double tilt, double spin, double rx, double ry, double rz) {
		rotate(N[f], tilt, spin, V);
		return V[0] * -rx + V[1] * -ry + V[2] * -rz > -1e-3 * (Math.abs(rx) + Math.abs(ry) + Math.abs(rz));
	}

	/** v = Rz(tilt) · Ry(spin) · n — так же, как поворачивает PoseStack */
	private static void rotate(double[] n, double tilt, double spin, double[] out) {
		double a = Math.toRadians(spin), c = Math.cos(a), s = Math.sin(a);
		double x = n[0] * c + n[2] * s, y = n[1], z = -n[0] * s + n[2] * c;
		double b = Math.toRadians(tilt), cb = Math.cos(b), sb = Math.sin(b);
		out[0] = x * cb - y * sb;
		out[1] = x * sb + y * cb;
		out[2] = z;
	}

	private static void cubeFlat(PoseStack pose, SubmitNodeCollector out, String[] faces, int[] light, int fixed) {
		for (int f = 0; f < 6; f++) submitFace(pose, out, faces[f], light == null ? fixed : light[f], f);
	}

	private static void submitFace(PoseStack pose, SubmitNodeCollector out, String model, int light, int f) {
		submitFlat(pose, out, model, light, (float) N[f][0], (float) N[f][1], (float) N[f][2]);
	}

	/**
	 * Модель с «плоской» нормалью: матрица нормалей переводит нормаль грани (nx,ny,nz) в «вверх».
	 * Тогда направленный свет предметов (и шейдеров) одинаков для всех граней, светотень задаёт уровень света.
	 */
	private static void submitFlat(PoseStack pose, SubmitNodeCollector out, String model, int light, float nx, float ny, float nz) {
		ItemStackRenderState st = Models.get(model);
		pose.pushPose();
		((Matrix3f) pose.last().normal()).set(0, nx, 0, 0, ny, 0, 0, nz, 0);
		st.submit(pose, out, light, OverlayTexture.NO_OVERLAY, 0);
		pose.popPose();
	}

	private static void submit(PoseStack pose, SubmitNodeCollector out, String model, int light) {
		Models.get(model).submit(pose, out, light, OverlayTexture.NO_OVERLAY, 0);
	}

	/** Спрайт, повёрнутый к камере (r — относительно камеры) */
	private static void billboard(PoseStack pose, SubmitNodeCollector out, double rx, double ry, double rz, double size, String model, int light, float roll) {
		if (size <= 0) return;
		double dd = Math.sqrt(rx * rx + ry * ry + rz * rz);
		size = Math.min(size, Math.max(0.05, dd * 1.3));
		double along = rx * fwX + ry * fwY + rz * fwZ;
		if (along < -size) return;
		pose.pushPose();
		pose.translate(rx, ry, rz);
		pose.rotateDegrees(Axis.YP, 180F - camYaw);
		pose.rotateDegrees(Axis.XP, -camPitch);
		if (roll != 0) pose.rotateDegrees(Axis.ZP, roll);
		pose.scale((float) size, (float) size, (float) size);
		submitFlat(pose, out, model, light, 0, 0, 1);
		pose.popPose();
	}

	/** Вытянутый вдоль экрана спрайт (луч, хвост): длина len, ширина wid, повёрнут на roll */
	private static void streak(PoseStack pose, SubmitNodeCollector out, double rx, double ry, double rz, double len, double wid, String model, float roll) {
		double dd = Math.sqrt(rx * rx + ry * ry + rz * rz);
		len = Math.min(len, Math.max(0.05, dd * 1.6));
		pose.pushPose();
		pose.translate(rx, ry, rz);
		pose.rotateDegrees(Axis.YP, 180F - camYaw);
		pose.rotateDegrees(Axis.XP, -camPitch);
		pose.rotateDegrees(Axis.ZP, roll);
		pose.scale((float) wid, (float) len, (float) wid);
		submitFlat(pose, out, model, FULL, 0, 0, 1);
		pose.popPose();
	}

	private static boolean visible(double rx, double ry, double rz, double d, double rad) {
		if (d <= rad * 1.5) return true;
		double cos = (rx * fwX + ry * fwY + rz * fwZ) / d;
		double halfAngle = Math.asin(Math.min(1, rad / d));
		return Math.acos(Math.max(-1, Math.min(1, cos))) < Math.toRadians(80) + halfAngle;
	}

	// ───────── Тории «Врата Фазы» ─────────

	private static void toriiPos(double t, double px, double py, double pz, double[] out) {
		double a = SolarSystem.TAU * SolarSystem.frac(t / SolarSystem.TORII_PERIOD);
		out[0] = px + Math.cos(a) * SolarSystem.TORII_ORBIT;
		out[1] = py + Math.sin(a * 2) * 6;
		out[2] = pz + Math.sin(a) * SolarSystem.TORII_ORBIT;
	}

	private static void torii(PoseStack pose, SubmitNodeCollector out, double t, double s, double px, double py, double pz) {
		toriiPos(t, px, py, pz, Q);
		double a = SolarSystem.TAU * SolarSystem.frac(t / SolarSystem.TORII_PERIOD);
		double size = SolarSystem.TORII_SIZE * s;
		double rx = (Q[0] - cx) * s, ry = (Q[1] - cy) * s, rz = (Q[2] - cz) * s;
		pose.pushPose();
		pose.translate(rx, ry, rz);
		pose.rotateDegrees(Axis.YP, (float) (-Math.toDegrees(a)));
		pose.rotateDegrees(Axis.XP, (float) (Math.sin(t * 0.004) * 6));
		pose.scale((float) size, (float) size, (float) size);
		submit(pose, out, "torii", sky(14));
		pose.popPose();
		billboard(pose, out, rx, ry, rz, size * 2.2, "fx/glow_sun_mid_d", FULL, 0);
	}

	// ───────── Облака частиц: споры Флораны и лепестки Сакуры ─────────

	private static void swarm(PoseStack pose, SubmitNodeCollector out, Body b, double t, double s, double px, double py, double pz, boolean petals) {
		int n = quality == 0 ? 50 : quality == 1 ? 120 : SPORES;
		for (int i = 0; i < n; i++) {
			double a = SPH[i] + t * SW[i] * (petals ? 1.6 : 1);
			double r = SR[i] * b.size * (petals ? 1.3 : 1);
			double x = Math.cos(a) * r, z = Math.sin(a) * r, y = Math.sin(a * 2 + i) * r * SINC[i];
			double tw = 0.6 + 0.4 * Math.sin(t * 0.05 + i * 1.7);
			String m = petals ? "fx/petal" : i % 4 == 0 ? "fx/spark_spore" : "fx/glow_spore_b";
			double sz = petals ? SSZ[i] * 1.3 : SSZ[i] * tw * 1.4;
			billboard(pose, out, (px + x - cx) * s, (py + y - cy) * s, (pz + z - cz) * s, sz * s, m, FULL, (float) (i * 37 % 360 + (petals ? t * (i % 2 == 0 ? 2 : -2) : 0)));
		}
	}

	// ───────── Кометы ─────────

	private static void comet(PoseStack pose, SubmitNodeCollector out, Comet c, double t) {
		c.pos(t, P);
		c.pos(t + 60, Q);
		cometAt(pose, out, P[0], P[1], P[2], Q[0] - P[0], Q[1] - P[1], Q[2] - P[2], c.size, c.tail, c.minor, t);
	}

	private static void greatComet(PoseStack pose, SubmitNodeCollector out, double t) {
		if (!SpaceEvents.greatComet(t, P, W)) return;
		cometAt(pose, out, P[0], P[1], P[2], W[0], W[1], W[2], 16, 4.2, false, t);
	}

	private static void cometAt(PoseStack pose, SubmitNodeCollector out, double x, double y, double z, double vx, double vy, double vz,
		double nucleus, double tail, boolean minor, double t) {
		double d = dist(x, y, z);
		double sunY = y - SolarSystem.PLANE_Y;
		double r = Math.sqrt(x * x + sunY * sunY + z * z);
		double ax = x / r, ay = sunY / r, az = z / r;
		double vl = Math.max(1e-6, Math.sqrt(vx * vx + vy * vy + vz * vz));
		vx /= vl; vy /= vl; vz /= vl;
		double act = Math.min(1.8, Math.pow(3600.0 / Math.max(400, r), 1.6));
		double len = tail * Math.max(60, Math.min(4200, 1300 * act));
		if (minor && d > 9000) return;
		if (!visible(x - cx, y - cy, z - cz, d, len)) return;
		double s = scaleFor(d);
		double rx = (x - cx) * s, ry = (y - cy) * s, rz = (z - cz) * s;
		int n = minor ? (quality == 0 ? 6 : 12) : quality == 0 ? 16 : quality == 1 ? 34 : 50;
		boolean away = (ax * rx + ay * ry + az * rz) > 0;
		for (int pass = 0; pass < 2; pass++) {
			boolean ion = pass == 0;
			if (minor && !ion && quality < 2) continue;
			for (int k = 0; k < n; k++) {
				int i = away ? n - 1 - k : k;
				double f = (i + 0.5) / n;
				double dx, dy, dz;
				if (ion) {
					dx = ax; dy = ay; dz = az;
				} else {
					dx = ax - vx * 0.5 * f; dy = ay - vy * 0.5 * f; dz = az - vz * 0.5 * f;
					double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
					dx /= l; dy /= l; dz /= l;
				}
				double L = len * f * (ion ? 1.0 : 0.75);
				double gap = len * (ion ? 1.0 : 0.75) / n;
				double sz = Math.max((ion ? 10 + 70 * f : 18 + 160 * f) * tail * (0.5 + 0.5 * act), gap * (ion ? 2.0 : 2.6));
				String m = ion ? (f < 0.15 ? "fx/glow_ion_b" : f < 0.5 ? "fx/glow_ion_c" : "fx/glow_ion_d")
					: (f < 0.15 ? "fx/glow_dust_b" : f < 0.45 ? "fx/glow_dust_c" : "fx/glow_dust_d");
				billboard(pose, out, rx + dx * L * s, ry + dy * L * s, rz + dz * L * s, sz * s, m, FULL, (float) (i * 41 % 360));
			}
		}
		billboard(pose, out, rx, ry, rz, (nucleus * 7 + 40 * act) * s, "fx/glow_coma_c", FULL, 0);
		billboard(pose, out, rx, ry, rz, (nucleus * 2.6 + 10 * act) * s, "fx/glow_coma_a", FULL, 0);
		if (d * s < skyR) {
			pose.pushPose();
			pose.translate(rx, ry, rz);
			pose.rotateDegrees(Axis.YP, (float) (t * 0.3 % 360));
			pose.rotateDegrees(Axis.XP, (float) (t * 0.17 % 360));
			float k = (float) (nucleus * s);
			pose.scale(k, k, k);
			submit(pose, out, "comet/nucleus", sky(13));
			pose.popPose();
		}
	}

	// ───────── Блуждающая планета (событие) ─────────

	private static void rogue(PoseStack pose, SubmitNodeCollector out, double t) {
		if (!SpaceEvents.rogue(t, P)) return;
		double x = P[0], y = P[1], z = P[2];
		double d = dist(x, y, z);
		double s = scaleFor(d);
		double size = SpaceEvents.ROGUE_SIZE * s;
		double rx = (x - cx) * s, ry = (y - cy) * s, rz = (z - cz) * s;
		double lx = -x, ly = SolarSystem.PLANE_Y - y, lz = -z;
		double ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
		int[] light = new int[6];
		faceLight(14, t * 0.02, lx / ll, ly / ll, lz / ll, new double[6], light, 0.0);
		billboard(pose, out, rx, ry, rz, size * 1.8, "fx/glow_lava_d", FULL, 0);
		pose.pushPose();
		pose.translate(rx, ry, rz);
		pose.rotateDegrees(Axis.ZP, 14F);
		pose.rotateDegrees(Axis.YP, (float) (t * 0.02));
		pose.scale((float) size, (float) size, (float) size);
		for (int f = 0; f < 6; f++) submitFace(pose, out, ROGUE[f], light[f], f);
		pose.scale(1.006F, 1.006F, 1.006F);
		for (int f = 0; f < 6; f++) submitFace(pose, out, ROGUE_LIGHTS[f], FULL, f);
		pose.popPose();
	}

	// ───────── Дальний космос ─────────

	private static void deep(PoseStack pose, SubmitNodeCollector out, Deep o, double t) {
		double d = dist(o.x, o.y, o.z);
		double reach = switch (o.type) {
			case "quasar" -> o.jet;
			case "nebula" -> o.size;
			case "binary" -> o.sep * 2;
			default -> o.size * o.disk * 3;
		};
		if (!visible(o.x - cx, o.y - cy, o.z - cz, d, reach)) return;
		double s = scaleFor(d);
		double rx = (o.x - cx) * s, ry = (o.y - cy) * s, rz = (o.z - cz) * s;
		switch (o.type) {
			case "black_hole" -> blackHole(pose, out, o, t, s, rx, ry, rz);
			case "quasar" -> quasar(pose, out, o, t, s, rx, ry, rz, d);
			case "pulsar" -> pulsar(pose, out, o, t, s, rx, ry, rz);
			case "binary" -> binary(pose, out, o, t, s);
			case "nebula" -> nebula(pose, out, o, t, s, d);
			default -> { }
		}
	}

	/** Чёрная дыра Ёми: линзированное гало, чёрный горизонт, аккреционный диск, фотонное кольцо */
	private static void blackHole(PoseStack pose, SubmitNodeCollector out, Deep o, double t, double s, double rx, double ry, double rz) {
		double size = o.size * s;
		billboard(pose, out, rx, ry, rz, size * 9, "fx/glow_hole_d", FULL, 0);
		billboard(pose, out, rx, ry, rz, size * 2.9, "fx/ring_hole_c", FULL, (float) (t * 0.05));
		// горизонт: чёрный диск рисуется ДО диска — дальняя половина диска скрывается за ним, ближняя ложится сверху
		billboard(pose, out, rx, ry, rz, size * 1.02, "fx/disc", FULL, 0);
		pose.pushPose();
		pose.translate(rx, ry, rz);
		pose.rotateDegrees(Axis.XP, 12F);
		pose.rotateDegrees(Axis.YP, (float) (t * 0.9 % 360));
		float k = (float) (size * o.disk * 2);
		pose.scale(k, k, k);
		submitFlat(pose, out, "deep/accretion_u", FULL, 0, 1, 0);
		submitFlat(pose, out, "deep/accretion_d", FULL, 0, -1, 0);
		pose.popPose();
		// «линзированный» верх диска — свет из-за дыры, изогнутый гравитацией
		billboard(pose, out, rx, ry, rz, size * 1.55, "fx/ring_hole_a", FULL, (float) (-t * 0.07));
		billboard(pose, out, rx, ry, rz, size * 1.18, "fx/ring_white_c", FULL, 0);
	}

	/** TON 618: ослепительное ядро, голубой диск, два релятивистских джета на десятки тысяч блоков */
	private static void quasar(PoseStack pose, SubmitNodeCollector out, Deep o, double t, double s, double rx, double ry, double rz, double d) {
		double size = o.size * s;
		double g = 1 + gamma * 3;
		billboard(pose, out, rx, ry, rz, size * 14 * g, "fx/glow_quasar_d", FULL, 0);
		billboard(pose, out, rx, ry, rz, size * 5 * g, "fx/glow_quasar_c", FULL, 0);
		pose.pushPose();
		pose.translate(rx, ry, rz);
		pose.rotateDegrees(Axis.ZP, 8F);
		pose.rotateDegrees(Axis.YP, (float) (t * 0.25 % 360));
		float k = (float) (size * o.disk * 2);
		pose.scale(k, k, k);
		submitFlat(pose, out, "deep/quasar_disk_u", FULL, 0, 1, 0);
		submitFlat(pose, out, "deep/quasar_disk_d", FULL, 0, -1, 0);
		pose.popPose();
		billboard(pose, out, rx, ry, rz, size * 1.6, "fx/glow_white_a", FULL, 0);
		billboard(pose, out, rx, ry, rz, size * 3.0 * g, "fx/spark_quasar", FULL, (float) (t * 0.02));
		// джеты по оси диска
		double jx = Math.sin(Math.toRadians(8)) * -1, jy = Math.cos(Math.toRadians(8)), jz = 0;
		int n = quality == 0 ? 16 : 40;
		for (int side = -1; side <= 1; side += 2) {
			for (int i = 0; i < n; i++) {
				double f = (i + 0.5) / n;
				double L = o.jet * f * side;
				double w = Math.max((o.size * 0.5 + o.jet * 0.07 * f) * g, o.jet / n * 3.5);
				billboard(pose, out, rx + jx * L * s, ry + jy * L * s, rz + jz * L * s, w * s,
					f < 0.2 ? "fx/glow_quasar_b" : f < 0.6 ? "fx/glow_quasar_c" : "fx/glow_quasar_d", FULL, (float) (i * 53));
			}
		}
	}

	/** Пульсар Кагура: крошечное ядро и два луча, которые метут небо, — мигает, когда луч смотрит на нас */
	private static void pulsar(PoseStack pose, SubmitNodeCollector out, Deep o, double t, double s, double rx, double ry, double rz) {
		double a = SolarSystem.TAU * SolarSystem.frac(t / o.period);
		double tilt = Math.toRadians(35);
		double bx = Math.cos(a) * Math.sin(tilt), by = Math.cos(tilt), bz = Math.sin(a) * Math.sin(tilt);
		double toCam = Math.abs(-(bx * rx + by * ry + bz * rz)) / Math.max(1e-6, Math.sqrt(rx * rx + ry * ry + rz * rz));
		double flash = Math.pow(toCam, 12);
		billboard(pose, out, rx, ry, rz, o.size * s * (30 + 260 * flash), flash > 0.4 ? "fx/glow_pulsar_a" : "fx/glow_pulsar_c", FULL, 0);
		billboard(pose, out, rx, ry, rz, o.size * s * 6, "fx/spark_pulsar", FULL, (float) Math.toDegrees(a));
		int n = quality == 0 ? 10 : 22;
		for (int side = -1; side <= 1; side += 2) {
			for (int i = 0; i < n; i++) {
				double f = (i + 0.5) / n;
				double L = o.beam * f * side;
				billboard(pose, out, rx + bx * L * s, ry + by * L * s, rz + bz * L * s, Math.max(o.size * 2 + o.beam * 0.12 * f, o.beam / n * 2.2) * s,
					f < 0.3 ? "fx/glow_pulsar_b" : "fx/glow_pulsar_d", FULL, 0);
			}
		}
	}

	/** Идзанаги и Идзанами: голубая и оранжевая звёзды кружат друг вокруг друга, между ними поток газа */
	private static void binary(PoseStack pose, SubmitNodeCollector out, Deep o, double t, double s) {
		SpaceTravel.binaryStar(o, t, 0, P);
		SpaceTravel.binaryStar(o, t, 1, Q);
		for (int k = 0; k < 2; k++) {
			double[] c = k == 0 ? P : Q;
			double rx = (c[0] - cx) * s, ry = (c[1] - cy) * s, rz = (c[2] - cz) * s;
			double size = o.size * s * (k == 0 ? 1 : 0.76);
			String col = k == 0 ? "star_blue" : "star_orange";
			billboard(pose, out, rx, ry, rz, size * 5, "fx/glow_" + col + "_b", FULL, 0);
			billboard(pose, out, rx, ry, rz, size * 2.2, "fx/glow_" + col + "_a", FULL, 0);
			billboard(pose, out, rx, ry, rz, size * 1.1, "fx/glow_white_a", FULL, 0);
			for (int r = 0; r < 6; r++) streak(pose, out, rx, ry, rz, size * 4, size * 0.25, "fx/ray_" + col + "_c", (float) (r * 30 + t * (k == 0 ? 0.02 : -0.02)));
		}
		// поток вещества от оранжевой к голубой: спираль к аккреционному кольцу
		int n = quality == 0 ? 12 : 28;
		for (int i = 0; i < n; i++) {
			double f = (i + 0.5) / n;
			double sw = Math.sin(f * Math.PI) * o.sep * 0.15;
			double x = Q[0] + (P[0] - Q[0]) * f + sw * 0.6, y = Q[1] + (P[1] - Q[1]) * f + sw * 0.2, z = Q[2] + (P[2] - Q[2]) * f - sw;
			billboard(pose, out, (x - cx) * s, (y - cy) * s, (z - cz) * s, o.size * s * (0.4 + 0.3 * f), f < 0.5 ? "fx/glow_star_orange_c" : "fx/glow_star_blue_c", FULL, 0);
		}
	}

	/** Туманность Аматэрасу: сотни сгустков светящегося газа и молодые звёзды — внутрь можно влететь */
	private static void nebula(PoseStack pose, SubmitNodeCollector out, Deep o, double t, double sFar, double d) {
		int n = d > o.size * 3 ? (quality == 0 ? 60 : 140) : quality == 0 ? 160 : quality == 1 ? 300 : NEB;
		double[][] order = new double[n][2];
		for (int i = 0; i < n; i++) {
			double x = o.x + NEB_P[i][0] * o.size, y = o.y + NEB_P[i][1] * o.size, z = o.z + NEB_P[i][2] * o.size;
			order[i][0] = dist(x, y, z);
			order[i][1] = i;
		}
		java.util.Arrays.sort(order, (a, b) -> Double.compare(b[0], a[0]));
		for (double[] e : order) {
			int i = (int) e[1];
			double x = o.x + NEB_P[i][0] * o.size, y = o.y + NEB_P[i][1] * o.size, z = o.z + NEB_P[i][2] * o.size;
			double di = e[0];
			if (di < 40) continue;
			double s = scaleFor(di);
			billboard(pose, out, (x - cx) * s, (y - cy) * s, (z - cz) * s, NEB_P[i][3] * o.size * s, NEB_M[i], FULL, (float) (NEB_P[i][4] + t * 0.004));
		}
		int ns = quality == 0 ? 30 : NEB_STARS;
		for (int i = 0; i < ns; i++) {
			double x = o.x + NEB_S[i][0] * o.size, y = o.y + NEB_S[i][1] * o.size, z = o.z + NEB_S[i][2] * o.size;
			double di = dist(x, y, z);
			double s = scaleFor(di);
			double tw = 0.7 + 0.3 * Math.sin(t * 0.07 + i);
			billboard(pose, out, (x - cx) * s, (y - cy) * s, (z - cz) * s, (40 + NEB_S[i][3] * 160) * tw * s, NEB_S[i][3] > 0.7 ? "fx/spark_star_blue" : "fx/spark_white", FULL, 0);
		}
	}

	// ───────── Мерцающие звёзды, порталы, гиперпрыжок ─────────

	private static final int TWINKLE = 90;
	private static final double[][] TW = new double[TWINKLE][4];

	static {
		RandomSource r = RandomSource.create(77L);
		for (int i = 0; i < TWINKLE; i++) {
			double u = r.nextDouble() * 2 - 1, a = r.nextDouble() * SolarSystem.TAU, c = Math.sqrt(1 - u * u);
			TW[i][0] = Math.cos(a) * c; TW[i][1] = u; TW[i][2] = Math.sin(a) * c; TW[i][3] = r.nextDouble();
		}
	}

	/** Яркие звёзды неба мерцают и иногда вспыхивают лучами */
	private static void twinkle(PoseStack pose, SubmitNodeCollector out, double t) {
		int n = quality == 0 ? 30 : TWINKLE;
		double r = skyR * 0.93;
		for (int i = 0; i < n; i++) {
			double[] s = TW[i];
			double tw = 0.5 + 0.5 * Math.sin(t * (0.04 + s[3] * 0.09) + i * 2.1);
			double size = r * (0.004 + 0.01 * s[3]) * (0.4 + tw);
			String m = tw > 0.92 && s[3] > 0.5 ? "fx/spark_white" : s[3] > 0.7 ? "fx/glow_star_blue_b" : s[3] > 0.4 ? "fx/glow_white_b" : "fx/glow_star_orange_c";
			billboard(pose, out, s[0] * r, s[1] * r, s[2] * r, size, m, FULL, (float) (i * 13));
		}
	}

	/** Вблизи портала: кольца-воронки затягивают пыль в куб планеты */
	private static void portals(PoseStack pose, SubmitNodeCollector out, double t) {
		for (Body b : SolarSystem.BODIES) {
			if (b.dim == null) continue;
			b.pos(t, P);
			double d = dist(P[0], P[1], P[2]);
			double reach = b.halfMax() * 2.4 + 24;
			if (d > reach) continue;
			double k = 1 - (d - b.halfMax()) / (reach - b.halfMax());
			k = Math.max(0, Math.min(1, k));
			double s = scaleFor(d);
			double rx = (P[0] - cx) * s, ry = (P[1] - cy) * s, rz = (P[2] - cz) * s;
			double size = b.halfMax() * 2 * s;
			for (int i = 0; i < 3; i++) {
				double ph = SolarSystem.frac(t / 60.0 + i / 3.0);
				billboard(pose, out, rx, ry, rz, size * (2.2 - ph * 1.1), k > 0.6 ? "fx/ring_white_c" : "fx/ring_aurora_c", FULL, (float) (t * (i % 2 == 0 ? 1.5 : -1.5)));
			}
			int n = quality == 0 ? 10 : 26;
			for (int i = 0; i < n; i++) {
				double ph = SolarSystem.frac(t / 40.0 + i / (double) n);
				double a = i * 2.39996 + ph * 3;
				double rr = size * (1.6 - ph * 1.05);
				double ox = Math.cos(a) * rr, oy = Math.sin(a) * rr;
				billboard(pose, out, rx + rtX * ox + upX * oy, ry + upY * oy, rz + rtZ * ox + upZ * oy, size * 0.05 * (0.5 + k), "fx/glow_coma_b", FULL, 0);
			}
		}
	}

	/** Гиперпрыжок: на большой скорости вокруг взгляда тянутся световые линии к точке схода */
	private static void warpTunnel(PoseStack pose, SubmitNodeCollector out, double t) {
		double sp = Math.sqrt(velX * velX + velY * velY + velZ * velZ);
		if (sp < 3) return;
		double k = Math.min(1, (sp - 3) / 8);
		double dx = velX / sp, dy = velY / sp, dz = velZ / sp;
		// базис поперёк направления полёта
		double ax = -dz, az = dx, al = Math.sqrt(ax * ax + az * az);
		if (al < 1e-3) { ax = 1; az = 0; al = 1; }
		ax /= al; az /= al;
		double bx = dy * az, by = dz * ax - dx * az, bz = -dy * ax;
		int n = (int) ((quality == 0 ? 20 : quality == 1 ? 46 : 70) * k) + 4;
		for (int i = 0; i < n; i++) {
			double h1 = hash01(i * 7919L), h2 = hash01(i * 104729L + 3), h3 = hash01(i * 1299709L + 5);
			double ang = h1 * SolarSystem.TAU, rad = 5 + h2 * 16;
			double along = 30 - SolarSystem.frac(t * (0.04 + h3 * 0.05) * (1 + k) + h3) * 60;   // летят навстречу
			double ox = Math.cos(ang) * rad, oy = Math.sin(ang) * rad;
			double px = dx * along + ax * ox + bx * oy, py = dy * along + by * oy, pz = dz * along + az * ox + bz * oy;
			// линия вдоль направления полёта, повёрнутая на экране
			double qx = px + dx, qy = py + dy, qz = pz + dz;
			double s1x = px * rtX + pz * rtZ, s1y = px * upX + py * upY + pz * upZ, f1 = px * fwX + py * fwY + pz * fwZ;
			double s2x = qx * rtX + qz * rtZ, s2y = qx * upX + qy * upY + qz * upZ, f2 = qx * fwX + qy * fwY + qz * fwZ;
			if (f1 < 0.5 || f2 < 0.5) continue;
			double sx = s2x / f2 - s1x / f1, sy = s2y / f2 - s1y / f1;
			float roll = (float) Math.toDegrees(Math.atan2(-sx, sy));
			String m = h3 > 0.8 ? "fx/ray_violet_c" : h3 > 0.5 ? "fx/ray_ion_c" : "fx/ray_white_c";
			streak(pose, out, px, py, pz, 6 + 18 * k * h2, 0.35 + 0.25 * k, m, roll);
		}
		if (k > 0.3) billboard(pose, out, dx * 40, dy * 40, dz * 40, 8 + 20 * k, k > 0.7 ? "fx/glow_ion_b" : "fx/glow_ion_c", FULL, 0);
	}

	private static double hash01(long v) {
		long h = v * 0x9E3779B97F4A7C15L;
		h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
		h ^= h >>> 29;
		return (h >>> 11) * 0x1.0p-53;
	}

	// ───────── Обломки поясов ─────────

	private static void decor(PoseStack pose, SubmitNodeCollector out, double t) {
		int step = quality == 0 ? 3 : 1;
		double minD = skyR * 1.05;
		for (int i = 0; i < DECOR; i += step) {
			double a = DPH[i] + SolarSystem.TAU * SolarSystem.frac(t / DPER[i]);
			double x = Math.cos(a) * DR[i], z = Math.sin(a) * DR[i], y = DY[i];
			double dx = x - cx, dy = y - cy, dz = z - cz;
			double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (d < minD || DS[i] / d < 0.0011) continue;
			if ((dx * fwX + dy * fwY + dz * fwZ) / d < 0.2) continue;
			double s = scaleFor(d);
			pose.pushPose();
			pose.translate(dx * s, dy * s, dz * s);
			pose.rotateDegrees(Axis.YP, (float) ((t * DSPIN[i] + i * 31) % 360));
			pose.rotateDegrees(Axis.XP, (float) ((t * DSPIN[i] * 0.7 + i * 17) % 360));
			float k = (float) (DS[i] * s);
			pose.scale(k, k, k);
			submit(pose, out, DMODEL[i], sky(12));
			pose.popPose();
		}
	}

	// ───────── Пыль у камеры, разгон и метеоры ─────────

	public static void tick(Minecraft mc) {
		AtmosphereFx.tick(mc);
		EclipseFx.tick(mc);
		if (!inSpace(mc)) {
			motesInit = false;
			METEORS.clear();
			lastX = Double.NaN;
			return;
		}
		if (mc.isPaused()) return;
		Vec3 c = mc.gameRenderer.mainCamera().position();
		if (!Double.isNaN(lastX)) {
			double vx = c.x - lastX, vy = c.y - lastY, vz = c.z - lastZ;
			if (vx * vx + vy * vy + vz * vz > 400 * 400) { vx = 0; vy = 0; vz = 0; }  // телепорт
			velX = velX * 0.6 + vx * 0.4; velY = velY * 0.6 + vy * 0.4; velZ = velZ * 0.6 + vz * 0.4;
		}
		lastX = c.x; lastY = c.y; lastZ = c.z;
		double range = 40;
		for (int i = 0; i < MOTES; i++) {
			double[] m = MOTE[i];
			double dx = m[0] - c.x, dy = m[1] - c.y, dz = m[2] - c.z;
			if (!motesInit || dx * dx + dy * dy + dz * dz > range * range) {
				double u = RND.nextDouble() * 2 - 1, ph = RND.nextDouble() * SolarSystem.TAU;
				double rr = motesInit ? range * (0.85 + RND.nextDouble() * 0.15) : range * Math.cbrt(RND.nextDouble());
				double sq = Math.sqrt(1 - u * u);
				double ox = Math.cos(ph) * sq, oy = u, oz = Math.sin(ph) * sq;
				// на скорости новые пылинки появляются впереди
				double sp = Math.sqrt(velX * velX + velY * velY + velZ * velZ);
				if (motesInit && sp > 0.5) {
					double k = Math.min(0.9, sp / 6);
					ox = ox * (1 - k) + velX / sp * k; oy = oy * (1 - k) + velY / sp * k; oz = oz * (1 - k) + velZ / sp * k;
					double l = Math.sqrt(ox * ox + oy * oy + oz * oz);
					ox /= l; oy /= l; oz /= l;
				}
				m[0] = c.x + ox * rr; m[1] = c.y + oy * rr; m[2] = c.z + oz * rr;
				m[3] = RND.nextDouble();
			}
			m[1] += 0.002 * (m[3] - 0.5);
			// солнечный ветер: у Солнца пыль сносит прочь от него (во время вспышки — сильно)
			double sd = Math.sqrt(c.x * c.x + c.y * c.y + c.z * c.z);
			double wind = sd < 9000 ? (1 - sd / 9000) * 0.35 * (1 + 4 * SpaceEvents.flare(mc.level.getGameTime())) : 0;
			if (wind > 0.01) { m[0] += c.x / sd * wind; m[1] += c.y / sd * wind; m[2] += c.z / sd * wind; }
		}
		motesInit = true;
		// метеоры: редкие всегда, а в метеорный поток — ливнем из радианта
		int q = SpaceConfig.get().quality;
		double t = mc.level.getGameTime();
		double sh = SpaceConfig.get().events ? SpaceEvents.shower(t) : 0;
		int spawns = 0;
		if (q > 0 && RND.nextInt(q == 2 ? 70 : 130) == 0) spawns++;
		if (sh > 0) spawns += (int) (sh * (q == 0 ? 1 : q == 1 ? 2 : 4)) + (RND.nextDouble() < sh * 0.6 ? 1 : 0);
		SpaceEvents.Event e = sh > 0 ? SpaceEvents.at(t) : null;
		for (int k = 0; k < spawns && METEORS.size() < 90; k++) {
			double u = RND.nextDouble() * 2 - 1, ph = RND.nextDouble() * SolarSystem.TAU, sq = Math.sqrt(1 - u * u);
			double r = 70 + RND.nextDouble() * 260;
			double vx, vy, vz, x, y, z;
			if (e != null && k > 0 || (e != null && RND.nextBoolean())) {
				SpaceEvents.radiant(e, W);
				// летят ОТ радианта: появляются на его стороне, со случайным сдвигом поперёк
				double off = (RND.nextDouble() - 0.5) * 2;
				x = c.x + W[0] * r + Math.cos(ph) * sq * r * 0.8 * off;
				y = c.y + W[1] * r + u * r * 0.5 * off;
				z = c.z + W[2] * r + Math.sin(ph) * sq * r * 0.8 * off;
				double sp = 4 + RND.nextDouble() * 4;
				vx = -W[0] * sp; vy = -W[1] * sp; vz = -W[2] * sp;
			} else {
				x = c.x + Math.cos(ph) * sq * r; y = c.y + u * r * 0.6; z = c.z + Math.sin(ph) * sq * r;
				vx = RND.nextDouble() - 0.5; vy = (RND.nextDouble() - 0.5) * 0.4; vz = RND.nextDouble() - 0.5;
				double vl = Math.sqrt(vx * vx + vy * vy + vz * vz), sp = 2.5 + RND.nextDouble() * 2.5;
				vx = vx / vl * sp; vy = vy / vl * sp; vz = vz / vl * sp;
			}
			double size = RND.nextDouble() < 0.08 ? 4.5 : 1 + RND.nextDouble();   // иногда — болид
			METEORS.add(new double[] { x, y, z, vx, vy, vz, 0, 35 + RND.nextInt(45), size });
		}
		METEORS.removeIf(m -> {
			m[0] += m[3]; m[1] += m[4]; m[2] += m[5];
			return ++m[6] > m[7];
		});
	}

	private static void motes(PoseStack pose, SubmitNodeCollector out) {
		if (!motesInit) return;
		int n = quality == 0 ? 40 : quality == 1 ? 90 : MOTES;
		double sp = Math.sqrt(velX * velX + velY * velY + velZ * velZ);
		int trail = sp > 1.2 ? (quality == 0 ? 2 : 5) : 0;           // разгон: пыль превращается в штрихи
		for (int i = 0; i < n; i++) {
			double[] m = MOTE[i];
			double dx = m[0] - cx, dy = m[1] - cy, dz = m[2] - cz;
			if (dx * dx + dy * dy + dz * dz < 1) continue;
			double sz = 0.07 + m[3] * 0.12;
			billboard(pose, out, dx, dy, dz, sz, m[3] > 0.8 ? "fx/glow_mote_b" : "fx/glow_mote_c", FULL, 0);
			for (int k = 1; k <= trail; k++) {
				double f = k * Math.min(3.0, sp * 0.6);
				billboard(pose, out, dx - velX * f, dy - velY * f, dz - velZ * f, sz * (1 - k * 0.12), "fx/glow_mote_c", FULL, 0);
			}
		}
	}

	private static void meteors(PoseStack pose, SubmitNodeCollector out, float pt) {
		for (double[] m : METEORS) {
			double life = m[6] / m[7];
			double fade = life < 0.15 ? life / 0.15 : life > 0.7 ? (1 - life) / 0.3 : 1;
			double x = m[0] + m[3] * pt, y = m[1] + m[4] * pt, z = m[2] + m[5] * pt;
			double d = dist(x, y, z);
			double s = scaleFor(d);
			double size = m[8];
			int len = size > 3 ? 16 : 10;
			for (int k = len - 1; k >= 0; k--) {
				double back = k * 1.6 * (size > 3 ? 1.6 : 1);
				double sz = (k == 0 ? 1.6 : 1.1 - k * 0.06) * fade * size;
				String mdl = k == 0 ? "fx/glow_meteor_a" : k < 4 ? (size > 3 ? "fx/glow_lava_b" : "fx/glow_meteor_b") : "fx/glow_meteor_c";
				billboard(pose, out, (x - m[3] * back - cx) * s, (y - m[4] * back - cy) * s, (z - m[5] * back - cz) * s, sz * s, mdl, FULL, 0);
			}
			if (size > 3) billboard(pose, out, (x - cx) * s, (y - cy) * s, (z - cz) * s, size * 4 * fade * s, "fx/glow_meteor_c", FULL, 0);
		}
	}
}
