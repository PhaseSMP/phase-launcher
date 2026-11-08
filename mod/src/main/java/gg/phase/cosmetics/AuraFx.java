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
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/**
 * Паки эффектов (4.0): аура, след, приземление, смерть и убийство игрока — у каждого пака свои частицы и звуки.
 *
 * Частицы — плоские спрайты (assets/phase/items/fx), повёрнутые к камере: лепестки, огоньки, угольки, искры, дым,
 * осколки. У каждой — цвет, который меняется по ходу жизни (уголёк остывает, искра гаснет), покачивание, мерцание,
 * рост и затухание. Аура прикреплена к игроку и движется вместе с ним.
 *
 * Смерть игрока — эффект его пака. Убийство — эффект пака убийцы над поверженным: убийцу клиент узнаёт сам —
 * это ближайший игрок, который смотрел на жертву в момент удара, или хозяин стрелы рядом с ней.
 * От первого лица своя аура и след не рисуются: видно только приземление, смерть и убийство.
 */
public final class AuraFx {
	private static final RandomSource RND = RandomSource.create();
	private static final int BRIGHT = 0xF000F0;
	private static final int MAX = 2000;
	private static int age;

	private enum Pack {
		SAKURA("aura_sakura", "sakura"), ENDER("aura_ender", "ender"), ONI("aura_oni", "oni"), LIMBO("aura_limbo", "limbo"),
		FOXFIRE("aura_foxfire", "foxfire"), ASH("aura_ash", "ash"), ECLIPSE("aura_eclipse", "eclipse");

		final String id, snd;

		Pack(String id, String snd) { this.id = id; this.snd = snd; }

		static Pack of(String id) {
			if (id == null) return null;
			for (Pack p : values()) if (p.id.equals(id)) return p;
			return null;
		}
	}

	/** Частица-спрайт */
	private static final class P {
		String shape;
		String[] cols;                       // цвет по ходу жизни: от первого к последнему
		boolean bright = true, ground, steady, spiral;
		int owner = -1;                      // ≥0 — прикреплена к игроку (аура); иначе в мире
		int delay;                           // тиков до появления
		double x, y, z, px, py, pz;
		double vx, vy, vz;
		double a, r, w, oy, vr;              // орбита (аура) и спираль (смерть): угол, радиус, скорость вращения, высота, рост радиуса
		double cx, cz;                       // центр спирали
		float size, grow, stretch = 1F;      // размер, рост к концу жизни (×), вытянутость по вертикали
		float rot, prot, spin, gravity, drag = 0.95F;
		float sway, swayF = 0.25F, phase, twinkle;
		float tilt, yrot, pyrot, yspin;      // кольцо «гироскопом»: наклон и вращение вокруг вертикали
		double tiltY;                        // наклонная орбита: подъём по синусу угла
		int age, life;
		net.minecraft.client.renderer.item.ItemStackRenderState[] mdl; // модели по стадиям цвета (без поиска по строке каждый кадр)
		int gen = -1;
	}

	private static final List<P> LIVE = new ArrayList<>();
	private static final Map<Integer, double[]> LAST = new HashMap<>();   // игрок → {y, dy, на земле}
	private static final Map<Integer, Boolean> DEAD = new HashMap<>();    // игрок → был ли мёртв в прошлый тик
	private static final Map<Integer, Integer> HURT = new HashMap<>();    // игрок → прошлый hurtTime
	private static final Map<Integer, int[]> HIT_BY = new HashMap<>();    // жертва → {убийца, тик удара}
	private static final Map<String, SoundEvent> SOUNDS = new HashMap<>();
	private static final Map<Integer, Vec3> BASE = new HashMap<>();

	private AuraFx() {}

	// ───────── Тик: движение частиц и события игроков ─────────
	static void tick(Minecraft mc) {
		if (mc.level == null) { LIVE.clear(); LAST.clear(); DEAD.clear(); HURT.clear(); HIT_BY.clear(); return; }
		if (mc.isPaused()) return;
		age++;
		for (Iterator<P> it = LIVE.iterator(); it.hasNext();) {
			P p = it.next();
			if (p.delay > 0) { p.delay--; continue; }
			p.age++;
			if (p.age >= p.life) { it.remove(); continue; }
			p.prot = p.rot;
			p.rot += p.spin;
			p.pyrot = p.yrot;
			p.yrot += p.yspin;
			if (p.owner >= 0) {
				p.a += p.w; p.oy += p.vy; p.r = Math.max(0.05, p.r + p.vr);
			} else if (p.spiral) {
				p.px = p.x; p.py = p.y; p.pz = p.z;
				p.a += p.w; p.r = Math.max(0.02, p.r + p.vr); p.vy -= p.gravity;
				p.x = p.cx + Math.cos(p.a) * p.r; p.y += p.vy; p.z = p.cz + Math.sin(p.a) * p.r;
			} else {
				p.px = p.x; p.py = p.y; p.pz = p.z;
				p.vy -= p.gravity;
				p.vx *= p.drag; p.vy *= p.drag; p.vz *= p.drag;
				// покачивание (лепестки, пепел) — смещение вбок, а не скорость
				double sw = p.sway == 0 ? 0 : Math.sin(p.age * p.swayF + p.phase) - Math.sin((p.age - 1) * p.swayF + p.phase);
				p.x += p.vx + sw * p.sway; p.y += p.vy; p.z += p.vz + sw * p.sway * 0.6;
			}
		}
		Config cfg = Config.get();
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		Set<Integer> seen = new HashSet<>();
		for (Player pl : mc.level.players()) {
			int id = pl.getId();
			seen.add(id);
			boolean own = pl == mc.player;
			watchHits(mc, pl);
			CosmeticsData.Worn w = CosmeticsData.get(pl.getName().getString());
			Pack k = w == null ? null : Pack.of(w.aura());
			// смерть: эффект пака погибшего и (если есть) пака убийцы
			boolean dead = pl.isDeadOrDying();
			boolean was = DEAD.getOrDefault(id, false);
			DEAD.put(id, dead);
			if (dead && !was) onDeath(mc, pl, w, k);
			if (dead) continue;
			if (k == null || (!own && !cfg.others)) continue;
			if (pl.isInvisible() || pl.isSpectator()) continue;
			double d2 = pl.position().distanceToSqr(cam);
			if (d2 > 48 * 48) continue;
			boolean far = d2 > 24 * 24;           // дальше 24 блоков — вдвое реже
			if (far && (age + id) % 2 != 0) continue;
			boolean fp = own && mc.options.getCameraType() == CameraType.FIRST_PERSON;
			if (!fp && (!own || cfg.ownAura) && CosmeticsData.on(w, 'a')) aura(pl, k);
			double dx = pl.getX() - pl.xo, dz = pl.getZ() - pl.zo;
			double sp2 = dx * dx + dz * dz;
			if (!fp && sp2 > 0.0025 && CosmeticsData.on(w, 't')) trail(pl, k, dx, dz, sp2);
			// приземление после падения
			double[] last = LAST.computeIfAbsent(id, i -> new double[] { pl.getY(), 0, 1 });
			double dy = pl.getY() - last[0];
			boolean ground = pl.onGround();
			if (ground && last[2] == 0 && last[1] < -0.28 && !pl.isInWater() && CosmeticsData.on(w, 'b')) {
				land(pl.getX(), pl.getY(), pl.getZ(), k, Math.min(1.6, -last[1] * 2.2));
				sound(pl.getX(), pl.getY(), pl.getZ(), k, "land", 0.55F);
			}
			last[0] = pl.getY(); last[1] = dy; last[2] = ground ? 1 : 0;
		}
		LAST.keySet().retainAll(seen);
		DEAD.keySet().retainAll(seen);
		HURT.keySet().retainAll(seen);
		HIT_BY.entrySet().removeIf(e -> age - e.getValue()[1] > 200);
		if (LIVE.size() > MAX) LIVE.subList(0, LIVE.size() - MAX).clear();
	}

	/** Кто ударил игрока: в момент удара — ближайший игрок, смотрящий на него, или хозяин стрелы рядом */
	private static void watchHits(Minecraft mc, Player victim) {
		int id = victim.getId();
		int prev = HURT.getOrDefault(id, 0);
		HURT.put(id, victim.hurtTime);
		if (victim.hurtTime <= prev || victim.hurtTime == 0) return;
		Player best = null;
		double bestD = 5.5 * 5.5;
		Vec3 vc = victim.position().add(0, victim.getBbHeight() * 0.5, 0);
		for (Player o : mc.level.players()) {
			if (o == victim || o.isSpectator()) continue;
			double d = o.position().distanceToSqr(victim.position());
			if (d > bestD) continue;
			Vec3 to = vc.subtract(o.getEyePosition());
			double len = to.length();
			if (len < 1e-3 || o.getViewVector(1F).dot(to.scale(1 / len)) < 0.55) continue;
			best = o; bestD = d;
		}
		if (best == null) {
			for (Projectile pr : mc.level.getEntitiesOfClass(Projectile.class, victim.getBoundingBox().inflate(2.5))) {
				if (pr.getOwner() instanceof Player o && o != victim) { best = o; break; }
			}
		}
		if (best != null) HIT_BY.put(id, new int[] { best.getId(), age });
	}

	private static void onDeath(Minecraft mc, Player pl, CosmeticsData.Worn w, Pack k) {
		double x = pl.getX(), y = pl.getY(), z = pl.getZ();
		if (k != null && CosmeticsData.on(w, 'd')) {
			death(x, y, z, pl.getBbHeight(), k);
			sound(x, y + 1, z, k, "death", 0.9F);
		}
		int[] hit = HIT_BY.remove(pl.getId());
		if (hit == null || age - hit[1] > 120) return;
		Entity e = mc.level.getEntity(hit[0]);
		if (!(e instanceof Player killer)) return;
		CosmeticsData.Worn kw = CosmeticsData.get(killer.getName().getString());
		Pack kk = kw == null ? null : Pack.of(kw.aura());
		if (kk == null || !CosmeticsData.on(kw, 'k')) return;
		kill(x, y, z, pl.getBbHeight(), kk);
		sound(x, y + 1.5, z, kk, "kill", 0.9F);
	}

	// ───────── Звуки паков ─────────
	private static void sound(double x, double y, double z, Pack k, String kind, float vol) {
		Minecraft mc = Minecraft.getInstance();
		float v = CosmeticsData.packVolume() * vol;
		if (v <= 0.01F || mc.level == null) return;
		SoundEvent ev = SOUNDS.computeIfAbsent(k.snd + "_" + kind,
			n -> SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("phase", "pack." + n)));
		mc.level.playLocalSound(x, y, z, ev, SoundSource.PLAYERS, v, 0.95F + RND.nextFloat() * 0.1F, false);
	}

	// ───────── Создание частиц ─────────
	private static String pick(String... c) { return c[RND.nextInt(c.length)]; }

	private static double rnd(double a, double b) { return a + RND.nextDouble() * (b - a); }

	private static float rndf(float a, float b) { return a + RND.nextFloat() * (b - a); }

	private static String[] c(String... cols) { return cols; }

	private static P make(String shape, String[] cols, float size, int life) {
		P p = new P();
		p.shape = shape; p.cols = cols; p.size = size; p.life = life;
		// пламя, огоньки и искры всегда стоят вертикально — крутятся только лепестки, пепел, осколки
		boolean upright = shape.equals("flame") || shape.equals("fire") || shape.equals("spark");
		p.rot = p.prot = upright ? 0F : RND.nextFloat() * 360F;
		p.spin = upright ? 0F : rndf(-6F, 6F);
		p.phase = RND.nextFloat() * Mth.TWO_PI;
		LIVE.add(p);
		return p;
	}

	/** Частица ауры — кружит вокруг игрока */
	private static P orb(Player pl, String shape, String[] cols, double a, double r, double h, double w, double vy, float size, int life) {
		P p = make(shape, cols, size, life);
		p.owner = pl.getId(); p.a = a; p.r = r; p.oy = h; p.w = w; p.vy = vy;
		return p;
	}

	/** Частица в мире */
	private static P at(String shape, String[] cols, double x, double y, double z, double vx, double vy, double vz, float size, int life, float gravity) {
		P p = make(shape, cols, size, life);
		p.x = p.px = x; p.y = p.py = y; p.z = p.pz = z;
		p.vx = vx; p.vy = vy; p.vz = vz; p.gravity = gravity;
		return p;
	}

	/** Частица на спирали вокруг точки (вихри, воронки) */
	private static P swirl(String shape, String[] cols, double cx, double y, double cz, double a, double r, double w, double vr, double vy, float size, int life) {
		P p = make(shape, cols, size, life);
		p.spiral = true; p.cx = cx; p.cz = cz; p.a = a; p.r = r; p.w = w; p.vr = vr; p.vy = vy;
		p.x = p.px = cx + Math.cos(a) * r; p.y = p.py = y; p.z = p.pz = cz + Math.sin(a) * r;
		return p;
	}

	/** Кольцо, лежащее на земле и расходящееся */
	private static void ringOnGround(String col, double x, double y, double z, float from, float grow, int life) {
		P p = at("ring", c(col), x, y + 0.04, z, 0, 0, 0, from, life, 0);
		p.ground = true; p.grow = grow; p.spin = 0;
	}

	private static void burstOut(String shape, String[] cols, double x, double y, double z, int n, double v, double up, float size, int life, float g) {
		for (int i = 0; i < n; i++) {
			double a = i * Mth.TWO_PI / n + rnd(-0.2, 0.2), s = v * rnd(0.7, 1.15);
			at(shape, cols, x, y, z, Math.cos(a) * s, up * rnd(0.6, 1.3), Math.sin(a) * s, size * rndf(0.8F, 1.2F), (int) (life * rnd(0.8, 1.2)), g);
		}
	}

	/** Аура вокруг игрока */
	private static void aura(Player pl, Pack k) {
		double head = pl.getEyeHeight();
		switch (k) {
			case SAKURA -> {
				if (age % 2 == 0) {
					P p = orb(pl, "petal", c(pick("ffb7d0", "ff8fb8", "ffd8e6", "f4efe6", "ff6f9f")), rnd(0, Mth.TWO_PI), rnd(0.55, 0.95), rnd(0.4, head + 0.5), rnd(0.012, 0.035), -0.007, rndf(0.09F, 0.13F), 80);
					p.spin = rndf(-9F, 9F);
				}
				if (age % 9 == 0) { P m = orb(pl, "glow", c("ffd8e6", "ff8fb8"), rnd(0, Mth.TWO_PI), rnd(0.3, 0.7), rnd(0.2, head), 0.02, 0.012, 0.06F, 40); m.twinkle = 0.5F; }
			}
			case ENDER -> {
				for (int i = 0; i < 2; i++) {
					P p = orb(pl, "glow", c("f0d6ff", "e36bff", "b36bff", "7a1fa6"), age * 0.28 + i * Math.PI, 0.6, 0.05, 0.11, 0.03, 0.08F, 62);
					p.vr = -0.002; p.twinkle = 0.25F;
				}
				if (age % 5 == 0) { P s = orb(pl, "star", c("f0d6ff", "ffffff"), rnd(0, Mth.TWO_PI), rnd(0.25, 0.75), rnd(0.2, head + 0.2), 0.02, 0.01, 0.07F, 26); s.twinkle = 0.9F; s.spin = 0; }
			}
			case ONI -> {
				if (age % 2 == 0) for (int i = 0; i < 3; i++) {
					P f = orb(pl, "flame", c("ffb13c", "ff6a3d", "ff3b2f", "d21f2a"), age * 0.11 + i * Mth.TWO_PI / 3, 0.62, 0.02, 0.08, 0.012, 0.13F, 18);
					f.spin = 0; f.stretch = 1.4F; f.twinkle = 0.3F;
				}
				if (age % 3 == 0) { P e = orb(pl, "ember", c("ffd36b", "ffb13c", "ff6a3d"), rnd(0, Mth.TWO_PI), rnd(0.3, 0.7), rnd(0.05, 0.6), 0.03, 0.03, 0.05F, 34); e.twinkle = 0.7F; }
				if (age % 14 == 0) { P s = orb(pl, "smoke", c("6b6570", "3a3640"), rnd(0, Mth.TWO_PI), 0.5, 0.4, 0.01, 0.02, 0.12F, 40); s.grow = 1.4F; s.bright = false; }
			}
			case LIMBO -> {
				if (age % 3 == 0) {
					P s = orb(pl, "shard", c(pick("ff2d4a", "b36bff", "f0d6ff"), "2a0b3d"), rnd(0, Mth.TWO_PI), rnd(0.55, 0.8), rnd(0.3, head - 0.1), rnd(0.04, 0.07), 0.004, rndf(0.08F, 0.12F), 46);
					s.spin = rndf(-14F, 14F);
				}
				if (age % 2 == 0) { P g = orb(pl, "glow", c("ff2d4a", "b36bff"), age * 0.26, 0.62, 0.06, 0.1, 0.028, 0.07F, 50); g.vr = -0.0015; }
				if (age % 12 == 0) { P d = orb(pl, "smoke", c("2a0b3d"), rnd(0, Mth.TWO_PI), rnd(0.3, 0.6), rnd(0.1, head), 0.02, 0.01, 0.14F, 40); d.grow = 1.5F; }
			}
			case FOXFIRE -> {
				for (int i = 0; i < 5; i++) {
					double a = age * 0.06 + i * Mth.TWO_PI / 5, h = head - 0.45 + Mth.sin(age * 0.05F + i) * 0.16;
					P core = orb(pl, "fire", c("a8ecff"), a, 0.85, h, 0.06, 0, 0.17F, 1);
					core.steady = true; core.spin = 0; core.rot = core.prot = 0;
					if (age % 2 == 0) { P t = orb(pl, "glow", c("a8ecff", "5ac8e6", "2a7bd6"), a - 0.06, 0.85, h, 0.0, 0.008, 0.08F, 14); t.spin = 0; }
				}
				if (age % 7 == 0) { P s = orb(pl, "star", c("ffffff"), rnd(0, Mth.TWO_PI), rnd(0.4, 0.9), rnd(0.3, head), 0.03, 0.012, 0.05F, 24); s.twinkle = 1F; s.spin = 0; }
			}
			case ASH -> {
				if (age % 2 == 0) {
					P a = orb(pl, "smoke", c(pick("9a949e", "6b6570", "3a3640")), rnd(0, Mth.TWO_PI), rnd(0.5, 1.0), rnd(0.5, head + 0.6), rnd(0.015, 0.03), -0.012, rndf(0.05F, 0.08F), 60);
					a.bright = false; a.spin = rndf(-8F, 8F);
				}
				if (age % 5 == 0) { P e = orb(pl, "ember", c("ffb13c", "ff6a3d"), rnd(0, Mth.TWO_PI), rnd(0.3, 0.75), rnd(0.05, 0.7), 0.025, 0.025, 0.045F, 32); e.twinkle = 0.8F; }
			}
			case ECLIPSE -> {
				// Затмение: ровный нимб над головой — тёмное кольцо с алой каймой (луна закрыла солнце),
				// над ним маленькое чёрное солнце с золотой короной. Высота — от роста игрока, на скин не налезает.
				double h = head + 0.42;
				if (age % 4 == 0) {
					P dark = orb(pl, "ring", c("0b0b12"), 0, 0, h, 0, 0, 0.7F, 5);
					dark.ground = true; dark.steady = true; dark.bright = false; dark.spin = 1.2F; dark.rot = dark.prot = age * 1.2F;
					P rim = orb(pl, "ring", c("ff2d4a"), 0, 0, h + 0.012, 0, 0, 0.6F, 5);
					rim.ground = true; rim.steady = true; rim.spin = -1.6F; rim.rot = rim.prot = -age * 1.6F;
					P sun = orb(pl, "glow", c("0b0b12"), 0, 0, h + 0.2, 0, 0, 0.14F, 5);
					sun.steady = true; sun.bright = false; sun.spin = 0;
					P corona = orb(pl, "ring", c("ffd36b"), 0, 0, h + 0.2, 0, 0, 0.21F, 5);
					corona.steady = true; corona.spin = 2F; corona.rot = corona.prot = age * 2F;
				}
				// искры бегут по кругу нимба
				if (age % 3 == 0) { P sp = orb(pl, "spark", c("ffd36b", "ff2d4a"), age * 0.12, 0.3, h + 0.01, 0.12, 0, 0.04F, 12); sp.spin = 0; sp.steady = true; }
				if (age % 8 == 0) { P g = orb(pl, "star", c("ffd36b"), rnd(0, Mth.TWO_PI), rnd(0.32, 0.5), rnd(h - 0.05, h + 0.3), 0.02, 0.003, 0.045F, 26); g.twinkle = 1F; g.spin = 0; }
			}
		}
	}

	/** След: частицы остаются позади, пока игрок движется */
	private static void trail(Player pl, Pack k, double dx, double dz, double sp2) {
		double sp = Math.sqrt(sp2);
		int n = sp > 0.2 ? 3 : 2;
		double y = pl.yo + 0.05;
		for (int i = 0; i < n; i++) {
			double t = RND.nextDouble();
			double x = pl.xo + dx * t - dx / sp * 0.35 + rnd(-0.25, 0.25);
			double z = pl.zo + dz * t - dz / sp * 0.35 + rnd(-0.25, 0.25);
			switch (k) {
				case SAKURA -> { P p = at("petal", c(pick("ffb7d0", "ff8fb8", "ffd8e6")), x, y + rnd(0.1, 0.4), z, 0, 0, 0, rndf(0.08F, 0.11F), 55, 0.0012F); p.sway = 0.08F; p.spin = rndf(-8F, 8F); }
				case ENDER -> { P p = at(i == 0 ? "star" : "glow", c("f0d6ff", "e36bff", "b36bff"), x, y + rnd(0.05, 0.4), z, 0, 0.012, 0, 0.055F, 34, -0.0006F); p.twinkle = 0.8F; }
				case ONI -> {
					P e = at("ember", c("ffd36b", "ffb13c", "ff6a3d"), x, y, z, 0, 0.004, 0, 0.06F, 40, 0); e.twinkle = 0.6F; e.drag = 0.9F;
					if (i == 0 && age % 2 == 0) { P f = at("flame", c("ffb13c", "ff6a3d", "d21f2a"), x, y, z, 0, 0.02, 0, 0.09F, 14, -0.001F); f.spin = 0; f.stretch = 1.3F; }
				}
				case LIMBO -> {
					P s = at("shard", c(pick("ff2d4a", "b36bff"), "2a0b3d"), x, y + 0.02, z, 0, 0, 0, rndf(0.07F, 0.1F), 40, 0); s.ground = true; s.spin = 0;
					if (i == 0) at("spark", c("ff6a3d", "ff2d4a"), x, y, z, 0, 0.03, 0, 0.05F, 16, 0.002F).stretch = 1.8F;
				}
				case FOXFIRE -> { P g = at("glow", c("a8ecff", "5ac8e6"), x, y + rnd(0, 0.3), z, 0, 0.014, 0, 0.065F, 30, -0.0006F); g.twinkle = 0.5F; }
				case ASH -> {
					P a = at("smoke", c("9a949e", "6b6570", "3a3640"), x, y + rnd(0.1, 0.4), z, 0, 0, 0, rndf(0.05F, 0.08F), 50, 0.001F); a.sway = 0.06F; a.bright = false;
					if (i == 0 && age % 3 == 0) { P e = at("ember", c("ffb13c", "ff6a3d"), x, y, z, 0, 0.02, 0, 0.05F, 24, -0.0008F); e.twinkle = 0.8F; }
				}
				case ECLIPSE -> {
					if (i == 0) { P s = at("spark", c("ffd36b", "ff2d4a"), x, y + rnd(0, 0.3), z, 0, 0.015, 0, 0.06F, 24, -0.0006F); s.stretch = 1.6F; s.spin = 0; }
					else { P d = at("smoke", c("1a1622"), x, y + rnd(0, 0.3), z, 0, 0.008, 0, 0.08F, 30, 0); d.grow = 1.3F; }
				}
			}
		}
	}

	/** Приземление: кольцо по земле и выброс частиц (сила — от высоты падения) */
	private static void land(double x, double y, double z, Pack k, double power) {
		float pw = (float) Math.max(0.6, power);
		int n = (int) (12 + 8 * pw);
		switch (k) {
			case SAKURA -> { ringOnGround("ffb7d0", x, y, z, 0.3F, 5F * pw, 16); burstOut("petal", c("ffb7d0", "ff8fb8", "ffd8e6"), x, y + 0.1, z, n, 0.12 * pw, 0.12, 0.1F, 34, 0.004F); }
			case ENDER -> { ringOnGround("b36bff", x, y, z, 0.3F, 5F * pw, 14); burstOut("spark", c("e36bff", "b36bff"), x, y + 0.1, z, n, 0.1 * pw, 0.18, 0.06F, 20, 0.006F); }
			case ONI -> { ringOnGround("ff6a3d", x, y, z, 0.3F, 5.5F * pw, 12); burstOut("flame", c("ffb13c", "ff6a3d", "d21f2a"), x, y + 0.05, z, n, 0.12 * pw, 0.06, 0.11F, 16, -0.002F); burstOut("ember", c("ffd36b", "ffb13c"), x, y + 0.1, z, 8, 0.08, 0.2, 0.05F, 26, 0.008F); }
			case LIMBO -> { ringOnGround("ff2d4a", x, y, z, 0.3F, 5F * pw, 14); burstOut("shard", c("ff2d4a", "b36bff", "2a0b3d"), x, y + 0.1, z, n, 0.12 * pw, 0.16, 0.09F, 24, 0.01F); }
			case FOXFIRE -> { ringOnGround("5ac8e6", x, y, z, 0.3F, 5F * pw, 16); burstOut("fire", c("a8ecff", "5ac8e6", "2a7bd6"), x, y + 0.1, z, 8, 0.07 * pw, 0.12, 0.11F, 24, -0.002F); }
			case ASH -> { ringOnGround("6b6570", x, y, z, 0.3F, 4.5F * pw, 18); for (int i = 0; i < n; i++) { double a = rnd(0, Mth.TWO_PI), v = rnd(0.03, 0.09) * pw; P s = at("smoke", c("9a949e", "6b6570", "3a3640"), x, y + 0.1, z, Math.cos(a) * v, rnd(0.01, 0.05), Math.sin(a) * v, 0.12F, 30, -0.0005F); s.grow = 1.8F; s.bright = false; } }
			case ECLIPSE -> { ringOnGround("0b0b12", x, y, z, 0.3F, 4.5F * pw, 18); ringOnGround("ff2d4a", x, y + 0.01, z, 0.2F, 6F * pw, 14); burstOut("spark", c("ffd36b", "ff6a3d", "ff2d4a"), x, y + 0.1, z, n, 0.1 * pw, 0.14, 0.06F, 20, 0.006F); }
		}
	}

	/** Смерть игрока — большой эффект его пака */
	private static void death(double x, double y, double z, float h, Pack k) {
		double mid = y + h * 0.5;
		switch (k) {
			case SAKURA -> {
				for (int i = 0; i < 70; i++) {
					P p = swirl("petal", c(pick("ffb7d0", "ff8fb8", "ffd8e6", "f4efe6", "ff6f9f")), x, y + rnd(0, h), z, rnd(0, Mth.TWO_PI), rnd(0.2, 0.7), rnd(0.12, 0.2), 0.012, rnd(0.03, 0.07), rndf(0.09F, 0.14F), (int) rnd(50, 80));
					p.spin = rndf(-12F, 12F); p.delay = i / 4;
				}
				ringOnGround("ffb7d0", x, y, z, 0.4F, 6F, 24);
				for (int i = 0; i < 10; i++) { P s = at("star", c("ffffff"), x + rnd(-0.8, 0.8), y + rnd(0.3, h + 0.6), z + rnd(-0.8, 0.8), 0, 0.01, 0, 0.08F, 30, 0); s.twinkle = 1F; s.delay = (int) rnd(0, 20); s.spin = 0; }
			}
			case ENDER -> {
				for (int i = 0; i < 60; i++) {
					double a = rnd(0, Mth.TWO_PI), b = rnd(-1, 1), r = 1.8, s = Math.sqrt(1 - b * b);
					double ox = Math.cos(a) * s * r, oy = b * r, oz = Math.sin(a) * s * r;
					P p = at(pick("glow", "star"), c("f0d6ff", "e36bff", "b36bff"), x + ox, mid + oy, z + oz, -ox * 0.07, -oy * 0.07, -oz * 0.07, 0.08F, 16, 0);
					p.drag = 1F; p.twinkle = 0.4F;
				}
				P core = at("smoke", c("2a0b3d"), x, mid, z, 0, 0, 0, 0.3F, 26, 0); core.grow = -0.8F; core.delay = 4;
				P flash = at("glow", c("f0d6ff", "e36bff"), x, mid, z, 0, 0, 0, 0.9F, 10, 0); flash.delay = 16; flash.grow = 1.2F;
				for (int i = 0; i < 16; i++) { double a = i * Mth.TWO_PI / 16; P sp = at("spark", c("e36bff", "b36bff"), x, mid, z, Math.cos(a) * 0.22, rnd(-0.08, 0.12), Math.sin(a) * 0.22, 0.07F, 16, 0); sp.delay = 16; sp.stretch = 2F; }
			}
			case ONI -> {
				for (int i = 0; i < 46; i++) {
					P f = at("flame", c("ffb13c", "ff6a3d", "ff3b2f", "d21f2a"), x + rnd(-0.35, 0.35), y + rnd(0, 0.4), z + rnd(-0.35, 0.35), 0, rnd(0.12, 0.26), 0, rndf(0.16F, 0.26F), (int) rnd(16, 26), 0.004F);
					f.spin = 0; f.stretch = 1.5F; f.delay = i / 3;
				}
				for (int i = 0; i < 14; i++) { P s = at("smoke", c("6b6570", "3a3640"), x + rnd(-0.4, 0.4), y + rnd(1.2, 2.4), z + rnd(-0.4, 0.4), 0, 0.03, 0, 0.3F, 44, -0.0005F); s.grow = 1.6F; s.bright = false; s.delay = 8 + i; }
				burstOut("ember", c("ffd36b", "ffb13c", "ff6a3d"), x, y + 0.5, z, 24, 0.14, 0.28, 0.06F, 36, 0.012F);
				ringOnGround("ff6a3d", x, y, z, 0.4F, 7F, 18);
			}
			case LIMBO -> {
				for (int i = 0; i < 56; i++) {
					P s = swirl("shard", c(pick("ff2d4a", "b36bff", "f0d6ff"), "2a0b3d"), x, y + rnd(0, h + 0.4), z, rnd(0, Mth.TWO_PI), rnd(1.0, 1.8), rnd(0.12, 0.2), -0.04, rnd(-0.01, 0.01), rndf(0.09F, 0.14F), 34);
					s.spin = rndf(-18F, 18F);
				}
				P rift = at("smoke", c("2a0b3d"), x, mid, z, 0, 0, 0, 0.6F, 34, 0); rift.grow = -0.7F;
				for (int i = 0; i < 18; i++) { double a = i * Mth.TWO_PI / 18; P sp = at("spark", c("ff2d4a", "ff6a3d"), x, mid, z, Math.cos(a) * 0.26, rnd(-0.1, 0.16), Math.sin(a) * 0.26, 0.07F, 14, 0); sp.delay = 28; sp.stretch = 2F; }
				ringOnGround("ff2d4a", x, y, z, 1.6F, -0.85F, 30);
			}
			case FOXFIRE -> {
				for (int i = 0; i < 9; i++) {
					double a = i * Mth.TWO_PI / 9;
					P f = at("fire", c("a8ecff", "5ac8e6", "2a7bd6"), x, mid, z, Math.cos(a) * 0.12, 0.05, Math.sin(a) * 0.12, 0.2F, 50, -0.004F);
					f.drag = 0.97F; f.spin = 0; f.rot = f.prot = 0;
					for (int j = 0; j < 6; j++) { P t = at("glow", c("a8ecff", "5ac8e6", "2a7bd6"), x, mid, z, Math.cos(a) * 0.12, 0.05, Math.sin(a) * 0.12, 0.09F, 50, -0.004F); t.delay = j * 2; t.drag = 0.97F; }
				}
				ringOnGround("5ac8e6", x, y, z, 0.4F, 5F, 22);
			}
			case ASH -> {
				for (int i = 0; i < 80; i++) {
					P a = at("smoke", c("9a949e", "6b6570", "3a3640", "1e1b22"), x + rnd(-0.3, 0.3), y + rnd(0, h), z + rnd(-0.3, 0.3), rnd(-0.02, 0.02), rnd(-0.01, 0.02), rnd(-0.02, 0.02), rndf(0.06F, 0.11F), (int) rnd(40, 70), 0.0025F);
					a.sway = 0.07F; a.bright = false; a.delay = (int) (i * 0.4);
				}
				burstOut("ember", c("ffb13c", "ff6a3d"), x, y + h * 0.6, z, 18, 0.06, 0.12, 0.05F, 40, 0.004F);
				ringOnGround("6b6570", x, y, z, 0.5F, 4F, 40);
			}
			case ECLIPSE -> {
				P sun = at("glow", c("0b0b12", "1a1622"), x, y + h + 0.6, z, 0, 0, 0, 1.1F, 30, 0); sun.grow = -0.9F; sun.bright = true;
				P cor = at("ring", c("ff2d4a", "ffd36b"), x, y + h + 0.6, z, 0, 0, 0, 1.4F, 30, 0); cor.grow = -0.7F; cor.spin = 4F;
				for (int i = 0; i < 40; i++) { double a = rnd(0, Mth.TWO_PI), v = rnd(0.12, 0.3); P s = at("spark", c("ffd36b", "ff6a3d", "ff2d4a"), x, y + h + 0.6, z, Math.cos(a) * v, rnd(-0.15, 0.1), Math.sin(a) * v, 0.08F, 26, 0.006F); s.delay = 24; s.stretch = 2F; }
				ringOnGround("0b0b12", x, y, z, 0.4F, 7F, 30);
				ringOnGround("ff2d4a", x, y + 0.01, z, 0.3F, 8F, 26);
			}
		}
	}

	/** Убийство игрока — эффект пака убийцы над поверженным */
	private static void kill(double x, double y, double z, float h, Pack k) {
		double top = y + h + 0.5;
		switch (k) {
			case SAKURA -> {
				// ветка сакуры распускается: лепестки раскрываются цветком над головой
				for (int ring = 0; ring < 3; ring++) for (int i = 0; i < 10; i++) {
					double a = i * Mth.TWO_PI / 10 + ring * 0.3, v = 0.05 + ring * 0.03;
					P p = at("petal", c(pick("ffb7d0", "ff8fb8", "ff6f9f")), x, top, z, Math.cos(a) * v, 0.02 + ring * 0.01, Math.sin(a) * v, 0.13F, 46, 0.0015F);
					p.delay = ring * 4; p.sway = 0.06F; p.spin = rndf(-8F, 8F);
				}
				P g = at("glow", c("ffd8e6", "ff8fb8"), x, top, z, 0, 0, 0, 0.7F, 14, 0); g.grow = 0.6F;
				for (int i = 0; i < 8; i++) { P s = at("star", c("ffffff"), x + rnd(-0.6, 0.6), top + rnd(-0.3, 0.4), z + rnd(-0.6, 0.6), 0, 0.005, 0, 0.09F, 26, 0); s.twinkle = 1F; s.delay = (int) rnd(2, 16); s.spin = 0; }
			}
			case ENDER -> {
				P portal = at("ring", c("b36bff"), x, top, z, 0, 0, 0, 0.2F, 24, 0); portal.grow = 6F; portal.spin = 10F;
				burstOut("spark", c("e36bff", "b36bff"), x, top, z, 20, 0.2, 0.06, 0.08F, 18, 0);
				for (int i = 0; i < 12; i++) { P s = at("star", c("f0d6ff", "ffffff"), x + rnd(-0.7, 0.7), top + rnd(-0.5, 0.5), z + rnd(-0.7, 0.7), 0, 0.01, 0, 0.08F, 24, 0); s.twinkle = 1F; s.delay = (int) rnd(0, 10); s.spin = 0; }
			}
			case ONI -> {
				P g = at("glow", c("ffd36b", "ff6a3d", "d21f2a"), x, top, z, 0, 0, 0, 0.9F, 12, 0); g.grow = 0.8F;
				burstOut("spark", c("ffd36b"), x, top, z, 26, 0.26, 0.1, 0.07F, 16, 0.01F);
				burstOut("ember", c("ffd36b", "ffb13c", "ff6a3d"), x, top, z, 20, 0.14, 0.2, 0.06F, 32, 0.012F);
				for (int i = 0; i < 10; i++) { P f = at("flame", c("ffb13c", "ff6a3d", "d21f2a"), x + rnd(-0.4, 0.4), top - 0.3, z + rnd(-0.4, 0.4), 0, 0.08, 0, 0.18F, 16, 0); f.spin = 0; f.stretch = 1.4F; f.delay = (int) rnd(0, 6); }
			}
			case LIMBO -> {
				P g = at("glow", c("ff2d4a", "b36bff"), x, top, z, 0, 0, 0, 0.8F, 16, 0); g.grow = 0.5F;
				for (int i = 0; i < 24; i++) { double a = i * Mth.TWO_PI / 24; P s = at("shard", c(pick("ff2d4a", "b36bff", "f0d6ff"), "2a0b3d"), x, top, z, Math.cos(a) * 0.16, Math.sin(a * 2) * 0.05, Math.sin(a) * 0.16, 0.12F, 26, 0); s.spin = rndf(-16F, 16F); }
				P d = at("smoke", c("2a0b3d"), x, top, z, 0, 0, 0, 0.4F, 30, 0); d.grow = 1.4F;
			}
			case FOXFIRE -> {
				for (int i = 0; i < 9; i++) {
					P f = swirl("fire", c("a8ecff", "5ac8e6", "2a7bd6"), x, top - 0.2, z, i * Mth.TWO_PI / 9, 0.6, 0.18, -0.008, 0.012, 0.16F, 44);
					f.spin = 0; f.rot = f.prot = 0;
				}
				P g = at("glow", c("a8ecff", "5ac8e6"), x, top, z, 0, 0, 0, 0.6F, 16, 0); g.grow = 0.6F;
			}
			case ASH -> {
				ringOnGround("6b6570", x, y, z, 1.4F, 0.1F, 50);
				for (int i = 0; i < 20; i++) { double a = i * Mth.TWO_PI / 20; P e = at("ember", c("ffb13c", "ff6a3d"), x + Math.cos(a) * 0.75, y + 0.05, z + Math.sin(a) * 0.75, 0, 0.004, 0, 0.07F, 50, 0); e.twinkle = 0.9F; e.drag = 0.9F; }
				for (int i = 0; i < 12; i++) { P s = at("smoke", c("9a949e", "6b6570", "3a3640"), x + rnd(-0.6, 0.6), y + 0.2, z + rnd(-0.6, 0.6), 0, 0.03, 0, 0.18F, 40, -0.0004F); s.grow = 1.8F; s.bright = false; s.delay = (int) rnd(0, 12); }
			}
			case ECLIPSE -> {
				P cor = at("ring", c("ff2d4a", "ffd36b"), x, top, z, 0, 0, 0, 0.3F, 26, 0); cor.grow = 4.5F; cor.spin = 6F;
				P sun = at("glow", c("0b0b12", "1a1622"), x, top, z, 0, 0, 0, 0.55F, 26, 0);
				sun.steady = false;
				burstOut("spark", c("ffd36b", "ff6a3d", "ff2d4a"), x, top, z, 28, 0.22, 0.04, 0.08F, 20, 0.004F);
			}
		}
	}

	// ───────── Отрисовка ─────────
	public static void render(PoseStack pose, SubmitNodeCollector out) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || LIVE.isEmpty() || !CosmeticsModels.ready()) return;
		float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Camera camera = mc.gameRenderer.mainCamera();
		Vec3 cam = camera.position();
		float camY = camera.yRot(), camX = camera.xRot();
		boolean fp = mc.options.getCameraType() == CameraType.FIRST_PERSON;
		int me = mc.player == null ? -1 : mc.player.getId();
		Map<Integer, Vec3> base = BASE;
		base.clear();
		// направление взгляда: частицы за спиной камеры не отправляем на отрисовку
		float yr = camY * Mth.DEG_TO_RAD, xr = camX * Mth.DEG_TO_RAD;
		double fx = -Mth.sin(yr) * Mth.cos(xr), fy = -Mth.sin(xr), fz = Mth.cos(yr) * Mth.cos(xr);
		int gen = CosmeticsModels.gen();
		for (P p : LIVE) {
			if (p.delay > 0) continue;
			double x, y, z;
			if (p.owner >= 0) {
				if (fp && p.owner == me) continue; // своя аура от первого лица не рисуется (остатки после переключения камеры)
				Vec3 b = base.get(p.owner);
				if (b == null) {
					Entity e = mc.level.getEntity(p.owner);
					if (e == null) continue;
					b = new Vec3(Mth.lerp(pt, e.xo, e.getX()), Mth.lerp(pt, e.yo, e.getY()), Mth.lerp(pt, e.zo, e.getZ()));
					base.put(p.owner, b);
				}
				double a = p.a + p.w * pt, r = Math.max(0.05, p.r + p.vr * pt);
				x = b.x + Math.cos(a) * r; y = b.y + p.oy + p.vy * pt + (p.tiltY != 0 ? Math.sin(a) * r * p.tiltY : 0); z = b.z + Math.sin(a) * r;
			} else {
				x = Mth.lerp(pt, p.px, p.x); y = Mth.lerp(pt, p.py, p.y); z = Mth.lerp(pt, p.pz, p.z);
			}
			double ex = x - cam.x, ey = y - cam.y, ez = z - cam.z;
			double d2 = ex * ex + ey * ey + ez * ez;
			if (fp && d2 < 0.36) continue; // ничего вплотную к камере
			if (d2 > 56 * 56 || ex * fx + ey * fy + ez * fz < -1.5) continue; // далеко или за спиной
			float t = Math.min(1F, (p.age + pt) / p.life);
			// у спрайтов прозрачные края — видимая часть меньше кубика того же размера, поэтому чуть крупнее
			float s = p.size * (p.ground ? 1F : 1.4F) * (1F + p.grow * t);
			if (!p.steady) s *= t < 0.12F ? t / 0.12F : t > 0.65F ? Math.max(0F, (1F - t) / 0.35F) : 1F;
			if (p.twinkle > 0) s *= 1F - p.twinkle * 0.5F * (1F + Mth.sin((p.age + pt) * 0.9F + p.phase));
			if (s < 0.004F) continue;
			int ci = Math.min(p.cols.length - 1, (int) (t * p.cols.length));
			int light = p.bright ? BRIGHT : LightCoordsUtil.getLightCoords(mc.level, BlockPos.containing(x, y, z));
			float rot = p.prot + (p.rot - p.prot) * pt;
			pose.pushPose();
			pose.translate(ex, ey, ez);
			if (p.ground) {
				if (p.tilt != 0) pose.rotateDegrees(Axis.YP, p.pyrot + (p.yrot - p.pyrot) * pt); // наклонное кольцо кружит вокруг вертикали
				pose.rotateDegrees(Axis.XP, 90F + p.tilt);    // кольцо лежит на земле (или наклонено)
				pose.rotateDegrees(Axis.ZP, rot);
				pose.scale(s, s, s);
			} else {
				pose.rotateDegrees(Axis.YP, 180F - camY);     // спрайт повёрнут к камере
				pose.rotateDegrees(Axis.XP, -camX);
				pose.rotateDegrees(Axis.ZP, rot);
				pose.scale(s, s * p.stretch, s);
			}
			if (p.gen != gen || p.mdl == null) { p.mdl = new net.minecraft.client.renderer.item.ItemStackRenderState[p.cols.length]; p.gen = gen; }
			var mdl = p.mdl[ci];
			if (mdl == null) mdl = p.mdl[ci] = CosmeticsModels.fx(p.shape + "_" + p.cols[ci]);
			mdl.submit(pose, out, light, OverlayTexture.NO_OVERLAY, 0);
			pose.popPose();
		}
	}

	// ───────── Для автотеста: эффекты по команде ─────────
	public static void testDeath(double x, double y, double z, String pack) { Pack k = Pack.of(pack); if (k != null) { death(x, y, z, 1.8F, k); sound(x, y + 1, z, k, "death", 0.9F); } }

	public static void testKill(double x, double y, double z, String pack) { Pack k = Pack.of(pack); if (k != null) { kill(x, y, z, 1.8F, k); sound(x, y + 1.5, z, k, "kill", 0.9F); } }

	public static void testLand(double x, double y, double z, String pack) { Pack k = Pack.of(pack); if (k != null) { land(x, y, z, k, 1.2); sound(x, y, z, k, "land", 0.55F); } }
}
