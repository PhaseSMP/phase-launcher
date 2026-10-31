package gg.phase.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Ауры-паки: аура вокруг игрока, след за ним и вспышка при приземлении. Каждая часть выключается в меню J.
 * Частицы — свои объёмные кубики (модели fx_&lt;цвет&gt;), как на превью в лаунчере, а не стандартные частицы игры.
 * Частицы ауры прикреплены к игроку: на бегу и в полёте аура остаётся на нём.
 * От первого лица своя аура не лезет в глаза: всё, что у лица, не рисуется, ниже — видно.
 */
public final class AuraFx {
	private static final RandomSource RND = RandomSource.create();
	private static final int BRIGHT = 0xF000F0;
	private static final int MAX = 2400;
	private static int age;

	private enum Pack {
		SAKURA("aura_sakura"), ENDER("aura_ender"), ONI("aura_oni"), LIMBO("aura_limbo"), FOXFIRE("aura_foxfire"), ASH("aura_ash"), ECLIPSE("aura_eclipse");

		final String id;

		Pack(String id) { this.id = id; }

		static Pack of(String id) {
			for (Pack p : values()) if (p.id.equals(id)) return p;
			return null;
		}
	}

	/** Частица-кубик */
	private static final class P {
		String model;
		boolean bright, flat, steady;
		int owner = -1;                 // ≥0 — прикреплена к игроку (аура); иначе висит в мире (след, вспышка)
		double x, y, z, px, py, pz;      // мир
		double vx, vy, vz;               // мир: скорость; аура: vx — расхождение от центра, vy — подъём
		double a, r, w, oy;              // аура: угол, радиус, скорость вращения, высота над ногами
		float size, rot, prot, spin, gravity, drag = 0.95F;
		int age, life;
	}

	private static final List<P> LIVE = new ArrayList<>();
	private static final Map<Integer, double[]> LAST = new HashMap<>(); // игрок → {y, dy, на земле}

	private AuraFx() {}

	static void tick(Minecraft mc) {
		if (mc.level == null) { LIVE.clear(); LAST.clear(); return; }
		if (mc.isPaused()) return;
		age++;
		for (Iterator<P> it = LIVE.iterator(); it.hasNext();) {
			P p = it.next();
			p.age++;
			if (p.age >= p.life) { it.remove(); continue; }
			p.prot = p.rot;
			p.rot += p.spin;
			if (p.owner >= 0) {
				p.a += p.w; p.oy += p.vy; p.r = Math.max(0.05, p.r + p.vx);
			} else {
				p.px = p.x; p.py = p.y; p.pz = p.z;
				p.vy -= p.gravity;
				p.vx *= p.drag; p.vy *= p.drag; p.vz *= p.drag;
				p.x += p.vx; p.y += p.vy; p.z += p.vz;
			}
		}
		Config cfg = Config.get();
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		Set<Integer> seen = new HashSet<>();
		for (Player pl : mc.level.players()) {
			boolean own = pl == mc.player;
			if (!own && !cfg.others) continue;
			if (pl.isInvisible() || pl.isSpectator() || pl.position().distanceToSqr(cam) > 48 * 48) continue;
			CosmeticsData.Worn w = CosmeticsData.get(pl.getName().getString());
			if (w == null || w.aura() == null) continue;
			Pack k = Pack.of(w.aura());
			if (k == null) continue;
			seen.add(pl.getId());
			boolean fp = own && mc.options.getCameraType() == CameraType.FIRST_PERSON;
			if ((!own || cfg.ownAura) && CosmeticsData.fxOn(w.fx(), 'a')) aura(pl, k, fp);
			double dx = pl.getX() - pl.xo, dz = pl.getZ() - pl.zo;
			double sp2 = dx * dx + dz * dz;
			if (sp2 > 0.0025 && CosmeticsData.fxOn(w.fx(), 't')) trail(pl, k, dx, dz, sp2, fp);
			// вспышка при приземлении после падения
			double[] last = LAST.computeIfAbsent(pl.getId(), id -> new double[] { pl.getY(), 0, 1 });
			double dy = pl.getY() - last[0];
			boolean ground = pl.onGround();
			if (ground && last[2] == 0 && last[1] < -0.28 && !pl.isInWater() && CosmeticsData.fxOn(w.fx(), 'b')) burst(pl, k);
			last[0] = pl.getY(); last[1] = dy; last[2] = ground ? 1 : 0;
		}
		LAST.keySet().retainAll(seen);
		if (LIVE.size() > MAX) LIVE.subList(0, LIVE.size() - MAX).clear();
	}

	// ───────── Создание частиц ─────────
	private static String pick(String... c) { return c[RND.nextInt(c.length)]; }

	private static double rnd(double a, double b) { return a + RND.nextDouble() * (b - a); }

	private static P orbit(Player pl, String col, boolean bright, double a, double r, double h, double w, double vy, float size, int life) {
		P p = new P();
		p.model = "fx_" + col; p.bright = bright; p.owner = pl.getId();
		p.a = a; p.r = r; p.oy = h; p.w = w; p.vy = vy;
		p.size = size; p.life = life;
		p.rot = p.prot = RND.nextFloat() * 360F; p.spin = (RND.nextFloat() - 0.5F) * 16F;
		LIVE.add(p);
		return p;
	}

	private static P world(String col, boolean bright, double x, double y, double z, double vx, double vy, double vz, float size, int life, float gravity) {
		P p = new P();
		p.model = "fx_" + col; p.bright = bright;
		p.x = p.px = x; p.y = p.py = y; p.z = p.pz = z;
		p.vx = vx; p.vy = vy; p.vz = vz; p.gravity = gravity;
		p.size = size; p.life = life;
		p.rot = p.prot = RND.nextFloat() * 360F; p.spin = (RND.nextFloat() - 0.5F) * 20F;
		LIVE.add(p);
		return p;
	}

	/** Аура вокруг игрока (частицы прикреплены к нему) */
	private static void aura(Player pl, Pack k, boolean fp) {
		double head = pl.getEyeHeight();
		switch (k) {
			case SAKURA -> {
				if (age % (fp ? 4 : 2) != 0) return;
				orbit(pl, pick("ffb7d0", "ff8fb8", "ffd8e6", "f4efe6"), false, rnd(0, Mth.TWO_PI), rnd(0.6, 0.95), rnd(0.5, head + 0.4), rnd(0.02, 0.045), -0.008, 0.11F, 70).flat = true;
			}
			case ENDER -> {
				if (age % 2 == 0) for (int i = 0; i < 2; i++) {
					P p = orbit(pl, i == 0 ? "b36bff" : "e36bff", true, age * 0.32 + i * Math.PI, 0.62, 0.05, 0.12, 0.032, 0.075F, 60);
					p.vx = -0.002;
				}
				if (age % 8 == 0) orbit(pl, "f0d6ff", true, rnd(0, Mth.TWO_PI), rnd(0.3, 0.7), rnd(0.2, head), 0.02, 0.012, 0.05F, 40);
			}
			case ONI -> {
				if (age % 3 == 0) for (int i = 0; i < 3; i++)
					orbit(pl, pick("ff3b2f", "ff6a3d", "ffb13c"), true, age * 0.12 + i * Mth.TWO_PI / 3, 0.6, 0.06, 0.09, 0.006, 0.1F, 24);
				if (age % 7 == 0) orbit(pl, "d21f2a", true, rnd(0, Mth.TWO_PI), rnd(0.3, 0.6), 0.05, 0.05, 0.02, 0.08F, 26);
			}
			case LIMBO -> {
				if (age % 2 == 0) for (int i = 0; i < 2; i++) {
					P p = orbit(pl, i == 0 ? "ff2d4a" : "b36bff", true, age * 0.3 + i * Math.PI, 0.6, 0.08, 0.11, 0.03, 0.08F, 58);
					p.vx = -0.0015;
				}
				if (age % 3 == 0) orbit(pl, pick("ff6a3d", "ff2d4a"), true, age * 0.1, 0.62, 0.05, 0.08, 0.004, 0.09F, 22);
				if (age % 10 == 0) orbit(pl, pick("2a0b3d", "f0d6ff"), false, rnd(0, Mth.TWO_PI), rnd(0.4, 0.8), rnd(0.2, head), 0.03, 0.01, 0.06F, 40);
			}
			case FOXFIRE -> {
				// пять голубых огней кружат на уровне груди, за каждым — хвост
				if (fp && age % 2 != 0) return;
				for (int i = 0; i < 5; i++) {
					double a = age * 0.07 + i * Mth.TWO_PI / 5, h = head - 0.45 + Mth.sin(age * 0.05F + i) * 0.15;
					P core = orbit(pl, "a8ecff", true, a, 0.85, h, 0.07, 0, 0.14F, 1);
					core.steady = true;
					orbit(pl, i % 2 == 0 ? "5ac8e6" : "2a7bd6", true, a, 0.85, h, 0.0, 0.006, 0.09F, 9);
				}
				if (age % 6 == 0) orbit(pl, "ffffff", true, rnd(0, Mth.TWO_PI), rnd(0.5, 0.9), rnd(0.3, head), 0.03, 0.015, 0.04F, 30);
			}
			case ASH -> {
				if (age % (fp ? 3 : 2) == 0)
					orbit(pl, pick("3a3640", "6b6570", "9a949e"), false, rnd(0, Mth.TWO_PI), rnd(0.55, 1.0), rnd(0.4, head + 0.5), rnd(0.02, 0.04), -0.01, 0.09F, 60).flat = true;
				if (age % 6 == 0) orbit(pl, pick("ff6a3d", "ffb13c"), true, rnd(0, Mth.TWO_PI), rnd(0.4, 0.8), rnd(0.1, 0.8), 0.03, 0.02, 0.06F, 30);
			}
			case ECLIPSE -> {
				// чёрное солнце-нимб над головой
				double h = head + 0.42;
				for (int i = 0; i < 16; i++) {
					P halo = orbit(pl, i % 2 == 0 ? "0b0b12" : "1a1622", false, age * 0.04 + i * Mth.TWO_PI / 16, 0.42, h, 0.04, 0, 0.1F, 1);
					halo.steady = true; halo.spin = 0;
				}
				// алая корона: искры расходятся от нимба
				if (age % 2 == 0) {
					P s = orbit(pl, pick("ff2d4a", "ffd36b", "ff6a3d"), true, rnd(0, Mth.TWO_PI), 0.45, h, 0.02, -0.004, 0.05F, 26);
					s.vx = 0.012;
				}
				if (age % 4 == 0) orbit(pl, "ff2d4a", true, age * 0.08, 0.58, 0.05, 0.07, 0.005, 0.08F, 22);
			}
		}
	}

	/** След: кубики ложатся позади ног, пока игрок движется */
	private static void trail(Player pl, Pack k, double dx, double dz, double sp2, boolean fp) {
		double sp = Math.sqrt(sp2);
		int n = fp ? 1 : sp > 0.2 ? 3 : 2;
		double y = pl.yo + 0.05;
		for (int i = 0; i < n; i++) {
			double t = RND.nextDouble();
			double x = pl.xo + dx * t - dx / sp * 0.35 + rnd(-0.25, 0.25);
			double z = pl.zo + dz * t - dz / sp * 0.35 + rnd(-0.25, 0.25);
			switch (k) {
				case SAKURA -> world(pick("ffb7d0", "ff8fb8", "ffd8e6"), false, x, y + rnd(0.05, 0.3), z, 0, 0, 0, 0.1F, 45, 0.002F).flat = true;
				case ENDER -> world(pick("f0d6ff", "b36bff", "e36bff"), true, x, y + rnd(0.05, 0.4), z, 0, 0.01, 0, 0.055F, 32, -0.0008F);
				case ONI -> world(pick("ff6a3d", "ffb13c", "d21f2a"), true, x, y, z, 0, 0.012, 0, 0.08F, 26, -0.0005F);
				case LIMBO -> world(pick("ff2d4a", "b36bff", "2a0b3d"), true, x, y + rnd(0, 0.2), z, 0, 0.01, 0, 0.07F, 30, -0.0006F);
				case FOXFIRE -> world(pick("5ac8e6", "a8ecff", "ffffff"), true, x, y + rnd(0, 0.25), z, 0, 0.015, 0, 0.07F, 30, -0.0008F);
				case ASH -> {
					P p = world(pick("3a3640", "6b6570", "9a949e"), false, x, y + rnd(0.05, 0.3), z, 0, 0, 0, 0.09F, 45, 0.0015F);
					p.flat = true;
					if (i == 0 && age % 3 == 0) world(pick("ff6a3d", "ffb13c"), true, x, y, z, 0, 0.02, 0, 0.06F, 22, -0.001F);
				}
				case ECLIPSE -> world(i == 0 ? pick("ff2d4a", "ffd36b") : pick("0b0b12", "1a1622"), i == 0, x, y + rnd(0, 0.2), z, 0, 0.012, 0, 0.075F, 32, -0.0006F);
			}
		}
	}

	/** Вспышка при приземлении: кольцо кубиков от ног */
	private static void burst(Player pl, Pack k) {
		String[] cols = switch (k) {
			case SAKURA -> new String[] { "ffb7d0", "ff8fb8", "f4efe6" };
			case ENDER -> new String[] { "b36bff", "e36bff", "f0d6ff" };
			case ONI -> new String[] { "ff3b2f", "ff6a3d", "ffb13c" };
			case LIMBO -> new String[] { "ff2d4a", "b36bff", "ff6a3d" };
			case FOXFIRE -> new String[] { "5ac8e6", "a8ecff", "2a7bd6" };
			case ASH -> new String[] { "3a3640", "6b6570", "ff6a3d" };
			case ECLIPSE -> new String[] { "0b0b12", "ff2d4a", "ffd36b" };
		};
		boolean bright = k != Pack.ASH && k != Pack.SAKURA;
		for (int i = 0; i < 18; i++) {
			double a = i * Mth.TWO_PI / 18 + rnd(-0.1, 0.1), v = rnd(0.11, 0.17);
			world(cols[i % cols.length], bright, pl.getX(), pl.getY() + 0.05, pl.getZ(), Math.cos(a) * v, rnd(0.04, 0.1), Math.sin(a) * v, 0.08F, 18, 0.008F);
		}
	}

	// ───────── Отрисовка ─────────
	public static void render(PoseStack pose, SubmitNodeCollector out) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || LIVE.isEmpty() || !CosmeticsModels.ready()) return;
		float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		boolean fp = mc.options.getCameraType() == CameraType.FIRST_PERSON;
		int me = mc.player == null ? -1 : mc.player.getId();
		Map<Integer, Vec3> base = new HashMap<>();
		for (P p : LIVE) {
			double x, y, z;
			if (p.owner >= 0) {
				Vec3 b = base.get(p.owner);
				if (b == null) {
					Entity e = mc.level.getEntity(p.owner);
					if (e == null) continue;
					b = new Vec3(Mth.lerp(pt, e.xo, e.getX()), Mth.lerp(pt, e.yo, e.getY()), Mth.lerp(pt, e.zo, e.getZ()));
					base.put(p.owner, b);
				}
				double a = p.a + p.w * pt, r = Math.max(0.05, p.r + p.vx * pt);
				x = b.x + Math.cos(a) * r; y = b.y + p.oy + p.vy * pt; z = b.z + Math.sin(a) * r;
			} else {
				x = Mth.lerp(pt, p.px, p.x); y = Mth.lerp(pt, p.py, p.y); z = Mth.lerp(pt, p.pz, p.z);
			}
			double ex = x - cam.x, ey = y - cam.y, ez = z - cam.z;
			if (fp) {
				// от первого лица: ничего вплотную к камере, своя аура — только ниже уровня глаз
				if (ex * ex + ey * ey + ez * ez < 0.36) continue;
				if (p.owner == me && ey > -0.5 && ex * ex + ez * ez < 1.1) continue;
			}
			float s = p.size;
			if (!p.steady) {
				float t = (p.age + pt) / p.life;
				s *= t < 0.15F ? t / 0.15F : t > 0.7F ? Math.max(0F, (1F - t) / 0.3F) : 1F;
			}
			if (s < 0.004F) continue;
			int light = p.bright ? BRIGHT : LightCoordsUtil.getLightCoords(mc.level, BlockPos.containing(x, y, z));
			float rot = p.prot + (p.rot - p.prot) * pt;
			pose.pushPose();
			pose.translate(ex, ey, ez);
			pose.rotateDegrees(Axis.YP, rot);
			pose.rotateDegrees(Axis.XP, rot * 0.6F);
			pose.scale(s, p.flat ? s * 0.3F : s, s);
			CosmeticsModels.get(p.model).submit(pose, out, light, OverlayTexture.NO_OVERLAY, 0);
			pose.popPose();
		}
	}
}
