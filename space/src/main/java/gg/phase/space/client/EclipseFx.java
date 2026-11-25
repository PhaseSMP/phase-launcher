package gg.phase.space.client;

import com.mojang.blaze3d.vertex.PoseStack;
import gg.phase.space.EclipseChaos;
import gg.phase.space.EclipseChaos.State;
import gg.phase.space.EclipsePayload;
import gg.phase.space.SolarSystem;
import gg.phase.space.SpaceConfig;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Хаос затмения на клиенте.
 * <ul>
 *   <li>Небо: квадратное Солнце закрывает чёрный диск Луны, в полной фазе — корона с лучами, «бриллиантовое кольцо»
 *       и чётки Бейли на краях полной фазы, закатное зарево по всему горизонту, звёзды и планеты днём (SkyFx).</li>
 *   <li>Тень: чем ближе к центру, тем полнее затмение и темнее мир; далеко — частное затмение.</li>
 *   <li>Фудзи: столб света из кратера в небо, чёрное «око» над кратером, молнии по склонам, извержение кратера Хоэй,
 *       кольца ударных волн, расходящиеся по всей карте, дождь осколков вокруг игрока.</li>
 * </ul>
 * Далёкие объекты (дальше прорисовки) проецируются на сферу радиуса D — как небесные тела, поэтому луч и волны
 * видны с любого конца карты.
 */
public final class EclipseFx {
	private static State st;
	private static final double[] S = new double[3], M = new double[3], P = new double[3], Q = new double[3], V = new double[3];
	private static double shake, lastJx, lastJy;
	private static double camDark;

	private EclipseFx() {}

	public static void set(EclipsePayload p) {
		st = p.active() ? p.toState() : null;
	}

	/** Подсветка звёзд днём (0..1) — для SkyFx */
	public static float starBoost() {
		return (float) (camDark * 0.95);
	}

	// ───────── тик: тряска камеры ─────────

	public static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			st = null;
			camDark = 0;
			return;
		}
		if (st == null || !mc.level.dimension().equals(Level.OVERWORLD)) {
			camDark = 0;
			undoShake(mc);
			return;
		}
		double t = mc.level.getGameTime();
		double f = st.f(t);
		if (f > 1.02) { st = null; camDark = 0; undoShake(mc); return; }
		Vec3 c = mc.player.position();
		double dist = Math.hypot(c.x - st.x, c.z - st.z);
		camDark = st.darkness(t, c.x, c.z) * Math.max(0, Math.min(1, SpaceConfig.get().eclipseDarkness));
		if (mc.isPaused()) return;
		// дрожь земли перед полной фазой и при проходе волны
		double amp = 0;
		if (f > 0 && f < EclipseChaos.P_SHADOW && dist < EclipseChaos.UMBRA) amp = 0.12 * f / EclipseChaos.P_SHADOW;
		for (long ws : st.waves()) {
			double r = (t - ws) * EclipseChaos.WAVE_SPEED;
			if (r > 0 && Math.abs(r - dist) < 10) shake = Math.max(shake, 12);
		}
		if (shake > 0) { amp = Math.max(amp, 0.25 * shake); shake--; }
		if (amp > 0.01) {
			double jx = (Math.random() - 0.5) * amp, jy = (Math.random() - 0.5) * amp * 0.6;
			mc.player.setYRot((float) (mc.player.getYRot() - lastJx + jx));
			mc.player.setXRot((float) Math.max(-90, Math.min(90, mc.player.getXRot() - lastJy + jy)));
			lastJx = jx; lastJy = jy;
		} else undoShake(mc);
	}

	private static void undoShake(Minecraft mc) {
		if (mc.player != null && (lastJx != 0 || lastJy != 0)) {
			mc.player.setYRot((float) (mc.player.getYRot() - lastJx));
			mc.player.setXRot((float) (mc.player.getXRot() - lastJy));
		}
		lastJx = 0;
		lastJy = 0;
	}

	// ───────── кадр ─────────

	public static void render(PoseStack pose, SubmitNodeCollector out) {
		Minecraft mc = Minecraft.getInstance();
		if (st == null || mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD) || !Models.ready()) return;
		if (ShaderCompat.shadowPass()) return;
		double t = mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		double f = st.f(t);
		if (f < 0 || f > 1) return;
		SkyFx.camera(mc);
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		BlockPos at = BlockPos.containing(cam);
		double sunAngle = Math.toRadians(mc.level.environmentAttributes().getValue(EnvironmentAttributes.SUN_ANGLE, at));
		double D = Math.max(40, mc.options.getEffectiveRenderDistance() * 16.0 * 0.78);
		double dist = Math.hypot(cam.x - st.x, cam.z - st.z);
		double cover = st.cover(t, cam.x, cam.z);
		double dark = st.darkness(t, cam.x, cam.z) * Math.max(0, Math.min(1, SpaceConfig.get().eclipseDarkness));

		sky(pose, out, t, f, sunAngle, D, dist, cover, dark);
		beam(pose, out, t, f, cam, D, dist);
		bolts(pose, out, t, cam, D);
		if (st.fuji) hoei(pose, out, t, f, cam, D);
		waves(pose, out, t, cam, D, dist);
		rain(pose, out, t, f, cam, dark);
		overlay(pose, out, t, f, dark, dist);
	}

	// ───────── небо ─────────

	private static void sky(PoseStack pose, SubmitNodeCollector out, double t, double f, double sunAngle, double D, double dist, double cover, double dark) {
		SolarSystem.skyDir(SolarSystem.sphere(0, 0), sunAngle, S);
		// закатное зарево по всему кругу горизонта
		if (dark > 0.35) {
			double g = (dark - 0.35) / 0.65;
			for (int k = 0; k < 16; k++) {
				double a = k * Math.PI / 8 + 0.2;
				double x = Math.cos(a), y = 0.03, z = Math.sin(a), l = Math.sqrt(x * x + y * y + z * z);
				SkyFx.billboard(pose, out, x / l * D, y / l * D, z / l * D, D * 0.55 * g, k % 2 == 0 ? "fx/glow_sun_outer_d" : "fx/glow_prom_d", 0);
			}
		}
		if (S[1] < -0.12) return;
		double off = st.moonOffset(t), perp = State.perp(dist);
		double sep = Math.hypot(off, perp);
		double R = EclipseChaos.MOON_R;
		// корона и лучи-стримеры (рисуются до диска, диск — поверх)
		double corona = EclipseChaos.smooth(0.82, 1.0, cover);
		if (corona > 0.02) {
			float rot = (float) (t * 0.02);
			SkyFx.billboard(pose, out, S[0] * D, S[1] * D, S[2] * D, D * 1.5 * corona, "fx/glow_aurora_violet_d", 0);
			SkyFx.billboard(pose, out, S[0] * D, S[1] * D, S[2] * D, D * 0.95 * corona, "fx/glow_white_c", 0);
			SkyFx.billboard(pose, out, S[0] * D, S[1] * D, S[2] * D, D * 0.62, corona > 0.6 ? "fx/glow_sun_core_a" : "fx/glow_sun_core_b", 0);
			for (int k = 0; k < 7; k++) {
				double len = D * (0.9 + 0.5 * EclipseChaos.rnd(st.seed, k, 40)) * corona;
				SkyFx.billboard(pose, out, S[0] * D, S[1] * D, S[2] * D, len, k % 2 == 0 ? "fx/ray_white_c" : "fx/ray_coma_c", rot * (k % 2 == 0 ? 1 : -0.6F) + k * 25.7F);
			}
			SkyFx.billboard(pose, out, S[0] * D, S[1] * D, S[2] * D, D * 0.56, "fx/ring_violet_c", rot * 3);
		}
		// Луна — чёрный диск чуть ближе Солнца
		if (sep < R + EclipseChaos.SUN_HALF * 1.6) {
			SolarSystem.skyDir(SolarSystem.sphere(Math.toDegrees(off), Math.toDegrees(perp)), sunAngle, M);
			double d = D * 0.985;
			SkyFx.billboard(pose, out, M[0] * d, M[1] * d, M[2] * d, d * 2 * Math.tan(R) / 0.97, "fx/disc", 0);
		}
		// бриллиантовое кольцо и чётки Бейли — на краях полной фазы
		double ring = Math.max(bump(f, EclipseChaos.P_SHADOW - 0.012, 0.012), bump(f, EclipseChaos.P_RING + 0.012, 0.014)) * EclipseChaos.smooth(0.9, 1.0, cover + 0.05);
		if (ring > 0.02) {
			double edge = off + (f < 0.5 ? -R : R);
			SolarSystem.skyDir(SolarSystem.sphere(Math.toDegrees(edge), Math.toDegrees(perp)), sunAngle, V);
			double d = D * 0.98;
			SkyFx.billboard(pose, out, V[0] * d, V[1] * d, V[2] * d, D * 0.55 * ring, "fx/spark_white", (float) (t * 0.5));
			SkyFx.billboard(pose, out, V[0] * d, V[1] * d, V[2] * d, D * 0.24 * ring, "fx/glow_sun_core_a", 0);
			SkyFx.billboard(pose, out, V[0] * d, V[1] * d, V[2] * d, D * 0.5 * ring, "fx/glow_white_b", 0);
			for (int k = -3; k <= 3; k++) {
				if (k == 0) continue;
				double ang = k * 0.22;
				double e2 = off + (f < 0.5 ? -R : R) * Math.cos(ang), p2 = perp + R * Math.sin(ang);
				SolarSystem.skyDir(SolarSystem.sphere(Math.toDegrees(e2), Math.toDegrees(p2)), sunAngle, V);
				double bead = ring * (0.5 + 0.5 * Math.sin(t * 0.7 + k * 1.7));
				SkyFx.billboard(pose, out, V[0] * d, V[1] * d, V[2] * d, D * 0.05 * bead, "fx/glow_sun_core_a", 0);
			}
		}
	}

	private static double bump(double x, double c, double w) {
		double d = Math.abs(x - c) / w;
		return d >= 1 ? 0 : (1 - d * d) * (1 - d * d);
	}

	// ───────── проекция далёкого на сферу ─────────

	/** Позиция относительно камеры, ужатая до радиуса D. Возвращает масштаб для размеров */
	private static double proj(double wx, double wy, double wz, Vec3 cam, double D, double[] o) {
		double rx = wx - cam.x, ry = wy - cam.y, rz = wz - cam.z;
		double d = Math.sqrt(rx * rx + ry * ry + rz * rz);
		double k = d > D ? D / d : 1;
		o[0] = rx * k; o[1] = ry * k; o[2] = rz * k;
		return k;
	}

	private static void seg(PoseStack pose, SubmitNodeCollector out, Vec3 cam, double D,
			double ax, double ay, double az, double bx, double by, double bz, double width, String model) {
		double ka = proj(ax, ay, az, cam, D, P), kb = proj(bx, by, bz, cam, D, Q);
		double w = width * (ka + kb) * 0.5;
		if (w < 0.02) w = 0.02;
		SkyFx.line(pose, out, P[0], P[1], P[2], Q[0], Q[1], Q[2], w, model);
	}

	private static void dot(PoseStack pose, SubmitNodeCollector out, Vec3 cam, double D, double x, double y, double z, double size, String model, float roll) {
		double k = proj(x, y, z, cam, D, P);
		SkyFx.billboard(pose, out, P[0], P[1], P[2], size * k, model, roll);
	}

	// ───────── луч из кратера и «око» ─────────

	private static void beam(PoseStack pose, SubmitNodeCollector out, double t, double f, Vec3 cam, double D, double dist) {
		double on = EclipseChaos.smooth(EclipseChaos.P_SHADOW - 0.01, EclipseChaos.P_SHADOW + 0.01, f) * (1 - EclipseChaos.smooth(EclipseChaos.P_RING, EclipseChaos.P_RING + 0.025, f));
		double eye = EclipseChaos.smooth(EclipseChaos.P_OMEN, EclipseChaos.P_SHADOW, f) * (1 - EclipseChaos.smooth(EclipseChaos.P_RING + 0.01, EclipseChaos.P_DAWN, f));
		double x = st.x, z = st.z, y0 = st.y - 4;
		float rot = (float) (t * 0.8);
		// око над кратером
		if (eye > 0.02) {
			double ey = st.y + 46;
			dot(pose, out, cam, D, x, ey, z, 220 * eye, "fx/glow_aurora_violet_d", 0);
			dot(pose, out, cam, D, x, ey, z, 120 * eye, "fx/glow_violet_b", 0);
			dot(pose, out, cam, D, x, ey, z, 150 * eye, "fx/ring_violet_a", rot);
			dot(pose, out, cam, D, x, ey, z, 105 * eye, "fx/ring_white_c", -rot * 1.7F);
			dot(pose, out, cam, D, x, ey, z, 44 * eye, "fx/disc", 0);
		}
		if (on < 0.02) return;
		double pulse = 0.85 + 0.15 * Math.sin(t * 0.3);
		int n = 22;
		double H = 5600;
		for (int i = 0; i < n; i++) {
			double a = H * Math.pow(i / (double) n, 2), b = H * Math.pow((i + 1) / (double) n, 2);
			seg(pose, out, cam, D, x, y0 + a, z, x, y0 + b, z, 110 * on * pulse, "fx/ray_neb_violet_c");
			seg(pose, out, cam, D, x, y0 + a, z, x, y0 + b, z, 46 * on * pulse, "fx/ray_aurora_violet_c");
			seg(pose, out, cam, D, x, y0 + a, z, x, y0 + b, z, 14 * on, "fx/ray_violet_a");
			seg(pose, out, cam, D, x, y0 + a, z, x, y0 + b, z, 5 * on, "fx/ray_white_a");
		}
		// кольца, бегущие вверх по лучу
		for (int k = 0; k < 6; k++) {
			double h = ((t * 4 + k * 160) % 960);
			dot(pose, out, cam, D, x, y0 + h, z, (70 + h * 0.08) * on, "fx/ring_violet_c", rot + k * 40);
		}
		dot(pose, out, cam, D, x, y0 + 6, z, 160 * on, "fx/glow_violet_a", 0);
		// внутри луча — искры, летящие вверх вокруг камеры
		if (dist < EclipseChaos.BEAM_R * 1.6 && cam.y > st.y - 30) {
			for (int k = 0; k < 24; k++) {
				double a = k * 2.39996 + t * 0.05, r = 2.5 + (k % 5) * 1.3;
				double h = ((t * (0.8 + (k % 3) * 0.4) + k * 7) % 24) - 8;
				SkyFx.billboard(pose, out, Math.cos(a) * r, h, Math.sin(a) * r, 0.5 + (k % 4) * 0.2, k % 3 == 0 ? "fx/glow_white_b" : "fx/glow_violet_a", 0);
			}
		}
	}

	// ───────── молнии на склонах ─────────

	private static void bolts(PoseStack pose, SubmitNodeCollector out, double t, Vec3 cam, double D) {
		long now = (long) Math.floor((t - st.start) / 4);
		for (long i = now - 1; i <= now; i++) {
			if (i < 0) continue;
			double[] b = st.bolt(i);
			if (b == null) continue;
			double age = t - (st.start + i * 4);
			if (age < 0 || age > 7) continue;
			double gx = st.x + Math.cos(b[0]) * b[1], gz = st.z + Math.sin(b[0]) * b[1];
			double gy = st.ground(b[1]);
			double top = Math.max(gy + 500, st.y + 320);
			String core = age < 3 ? "fx/ray_white_a" : "fx/ray_white_c", glow = age < 3 ? "fx/ray_aurora_violet_c" : "fx/ray_violet_c";
			double sx = gx + (EclipseChaos.rnd(st.seed, i, 9) - 0.5) * 140, sz = gz + (EclipseChaos.rnd(st.seed, i, 10) - 0.5) * 140;
			double px = sx, py = top, pz = sz;
			int n = 9;
			for (int k = 1; k <= n; k++) {
				double u = k / (double) n;
				double j = k == n ? 0 : 70 * (1 - u * 0.5);
				double nx = gx + (sx - gx) * (1 - u) + (EclipseChaos.rnd(st.seed, i, 20 + k) - 0.5) * j;
				double ny = top + (gy - top) * u;
				double nz = gz + (sz - gz) * (1 - u) + (EclipseChaos.rnd(st.seed, i, 40 + k) - 0.5) * j;
				seg(pose, out, cam, D, px, py, pz, nx, ny, nz, 22, glow);
				seg(pose, out, cam, D, px, py, pz, nx, ny, nz, 5, core);
				px = nx; py = ny; pz = nz;
			}
			dot(pose, out, cam, D, gx, gy + 6, gz, age < 3 ? 120 : 60, "fx/glow_white_b", 0);
		}
	}

	// ───────── извержение кратера Хоэй ─────────

	private static void hoei(PoseStack pose, SubmitNodeCollector out, double t, double f, Vec3 cam, double D) {
		double on = EclipseChaos.smooth(EclipseChaos.P_SHADOW, EclipseChaos.P_SHADOW + 0.04, f) * (1 - EclipseChaos.smooth(EclipseChaos.P_RING, EclipseChaos.P_DAWN + 0.05, f));
		if (on < 0.02) return;
		double hr = Math.hypot(st.hx - st.x, st.hz - st.z);
		double by = st.ground(hr) + 4;
		dot(pose, out, cam, D, st.hx, by, st.hz, 170 * on, "fx/glow_lava_a", 0);
		dot(pose, out, cam, D, st.hx, by + 60, st.hz, 240 * on, "fx/puff_lava_d", (float) (t * 0.2));
		double g = 0.22, life = 110;
		for (int k = 0; k < 28; k++) {
			double ph = SolarSystem.frac(t / life + EclipseChaos.rnd(st.seed, k, 60));
			double tau = ph * life;
			double v0 = 9 + 4 * EclipseChaos.rnd(st.seed, k, 61), vh = 0.6 + 1.2 * EclipseChaos.rnd(st.seed, k, 62);
			double a = EclipseChaos.rnd(st.seed, k, 63) * Math.PI * 2;
			double y = by + v0 * tau - 0.5 * g * tau * tau;
			if (y < by - 30) continue;
			double x = st.hx + Math.cos(a) * vh * tau, z = st.hz + Math.sin(a) * vh * tau;
			dot(pose, out, cam, D, x, y, z, (16 + 10 * (1 - ph)) * on, ph < 0.5 ? "fx/glow_lava_a" : "fx/glow_prom_b", 0);
			dot(pose, out, cam, D, x, y, z, 34 * on, "fx/puff_lava_d", (float) (k * 30 + t));
		}
	}

	// ───────── ударные волны ─────────

	private static void waves(PoseStack pose, SubmitNodeCollector out, double t, Vec3 cam, double D, double dist) {
		List<Long> ws = st.waves();
		for (int w = 0; w < ws.size(); w++) {
			double age = t - ws.get(w);
			if (age < 0) continue;
			double R = age * EclipseChaos.WAVE_SPEED;
			if (R > EclipseChaos.WAVE_RANGE) continue;
			boolean last = w == ws.size() - 1;
			double fade = 1 - R / EclipseChaos.WAVE_RANGE;
			double base = st.ground(R) + 3;
			int n = 128;
			double px = 0, py = 0, pz = 0;
			for (int k = 0; k <= n; k++) {
				double a = k * Math.PI * 2 / n;
				double x = st.x + Math.cos(a) * R, z = st.z + Math.sin(a) * R;
				double hd = Math.hypot(x - cam.x, z - cam.z);
				double y = base + (cam.y - 1.5 - base) * EclipseChaos.smooth(280, 60, hd);
				if (k > 0) {
					double width = (last ? 16 : 9) * (0.4 + 0.6 * fade);
					seg(pose, out, cam, D, px, py, pz, x, y, z, width * 3, last ? "fx/ray_white_c" : "fx/ray_aurora_violet_c");
					seg(pose, out, cam, D, px, py, pz, x, y, z, width, last ? "fx/ray_white_a" : "fx/ray_violet_a");
					seg(pose, out, cam, D, px, py + 22, pz, x, y + 22, z, width * 0.6, "fx/ray_violet_c");
					if (hd < 220) seg(pose, out, cam, D, x, y - 2, z, x, y + 30, z, 2.5, "fx/ray_violet_c");
				}
				px = x; py = y; pz = z;
			}
			// фронт рядом с игроком — вспышка
			if (Math.abs(R - dist) < 14) {
				double s = 1 - Math.abs(R - dist) / 14;
				SkyFx.billboard(pose, out, SkyFx.fwX * 0.5, SkyFx.fwY * 0.5, SkyFx.fwZ * 0.5, 3.2 * s, last ? "fx/glow_white_b" : "fx/glow_violet_b", 0);
			}
		}
	}

	// ───────── дождь осколков ─────────

	private static void rain(PoseStack pose, SubmitNodeCollector out, double t, double f, Vec3 cam, double dark) {
		if (f < EclipseChaos.P_SHADOW || f > EclipseChaos.P_RING || dark < 0.4) return;
		for (int k = 0; k < 26; k++) {
			double cyc = 70 + 30 * EclipseChaos.rnd(st.seed, k, 80);
			double ph = SolarSystem.frac(t / cyc + EclipseChaos.rnd(st.seed, k, 81));
			long gen = (long) Math.floor(t / cyc + EclipseChaos.rnd(st.seed, k, 81));
			double a = EclipseChaos.rnd(st.seed, gen * 31 + k, 82) * Math.PI * 2, r = 12 + 90 * EclipseChaos.rnd(st.seed, gen * 31 + k, 83);
			double x = Math.cos(a) * r, z = Math.sin(a) * r, y = 110 - ph * 150;
			double dx = 0.35, dy = 1.0;
			SkyFx.line(pose, out, x, y, z, x - dx * 10, y + dy * 10, z, 0.35, "fx/ray_violet_c");
			SkyFx.billboard(pose, out, x, y, z, 1.1, "fx/glow_violet_a", 0);
		}
	}

	// ───────── затемнение мира и вспышки ─────────

	private static void overlay(PoseStack pose, SubmitNodeCollector out, double t, double f, double dark, double dist) {
		double d = 0.35;
		double fx = SkyFx.fwX * d, fy = SkyFx.fwY * d, fz = SkyFx.fwZ * d;
		int k = (int) Math.round(dark * 8);
		if (k > 0) SkyFx.billboard(pose, out, fx, fy, fz, 3.0, "fx/shade_" + Math.min(8, k), 0);
		if (dark > 0.5) SkyFx.billboard(pose, out, fx * 0.95, fy * 0.95, fz * 0.95, 2.6, "fx/vignette", 0);
		// вспышка «бриллиантового кольца»
		double flash = bump(f, EclipseChaos.P_RING + 0.012, 0.008) * Math.max(0, 1 - dist / EclipseChaos.PENUMBRA);
		if (flash > 0.15) SkyFx.billboard(pose, out, fx * 0.9, fy * 0.9, fz * 0.9, 2.8 * flash, flash > 0.6 ? "fx/glow_white_a" : "fx/glow_white_b", 0);
	}
}
