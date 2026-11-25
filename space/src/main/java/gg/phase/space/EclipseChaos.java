package gg.phase.space;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Хаос затмения — событие обычного мира, запускается командой (/space eclipse start).
 * Центр — кратер Фудзи из карты Phase World (берётся из раскладки мода карты), без него — точка из команды.
 *
 * <p>Ход (доли длительности, по умолчанию 6 минут):
 * <pre>
 * 0.00–0.15  Предвестие — Луна наползает на Солнце, гул, дым над кратером
 * 0.15–0.30  Тень      — свет гаснет, по Фудзи бьют молнии
 * 0.30–0.80  Хаос      — полная фаза: луч из кратера в небо (поднимает до космоса), ударные волны по карте,
 *                        дождь осколков, фантомы в темноте, извержение кратера Хоэй, звёзды днём
 * 0.80–0.84  Кольцо    — «бриллиантовое кольцо», последняя волна, тени рассеиваются
 * 0.84–1.00  Рассвет   — Солнце возвращается
 * </pre>
 * Вся геометрия события (положение Луны, тень, волны, молнии у кратера) детерминирована от времени мира
 * и параметров из пакета, поэтому сервер и клиенты видят одно и то же.
 */
public final class EclipseChaos {
	public static final double P_OMEN = 0.15, P_SHADOW = 0.30, P_RING = 0.80, P_DAWN = 0.84;
	/** Радиус полной тени и край полутени, блоков */
	public static final double UMBRA = 1500, PENUMBRA = 4600;
	/** Скорость ударной волны, блоков за тик, и её дальность */
	public static final double WAVE_SPEED = 6, WAVE_RANGE = 3600;
	/** Угловой радиус «Луны» и полуширина квадратного Солнца (радианы, как на небе Земли) */
	public static final double MOON_R = 0.22, SUN_HALF = 0.15;
	public static final double SEA = 63;
	/** Профиль Фудзи из Phase World (WorldLayout): подошва, кромка кратера, высота */
	public static final double FUJI_FOOT = 1120, FUJI_RIM = 125, FUJI_H = 770;
	/** Ширина столба луча (блоков от оси), внутри которого он поднимает игрока */
	public static final double BEAM_R = 16;

	/** Параметры одного затмения — одинаковые на сервере и клиенте */
	public static final class State {
		public long start;
		public int dur;
		public double x, y, z, hx, hz;
		public long seed;
		public boolean fuji;

		public double f(double t) { return (t - start) / dur; }

		public boolean chaos(double t) { double f = f(t); return f >= P_SHADOW && f < P_RING; }

		/** Смещение «Луны» от Солнца вдоль пути, радианы (+ до полной фазы, − после) */
		public double moonOffset(double t) {
			double f = f(t);
			if (f < 0 || f > 1) return 1;
			if (f < P_SHADOW) {
				double x = f / P_SHADOW;
				return 0.52 * (1 - x) * (1 - x * 0.35);
			}
			if (f < P_RING) return 0.006 * Math.sin((t - start) * 0.01);
			double x = (f - P_RING) / (1 - P_RING);
			return -0.55 * x * x * (0.4 + 0.6 * x) - 0.012;
		}

		/** Сдвиг «Луны» поперёк пути для наблюдателя на расстоянии dist от центра тени */
		public static double perp(double dist) {
			return 0.5 * Math.max(0, Math.min(1, (dist - UMBRA) / (PENUMBRA - UMBRA)));
		}

		/** Насколько закрыто Солнце у наблюдателя (0..1) */
		public double cover(double t, double px, double pz) {
			double f = f(t);
			if (f < 0 || f > 1) return 0;
			double off = moonOffset(t), p = perp(Math.hypot(px - x, pz - z));
			double sep = Math.hypot(off, p);
			double full = MOON_R - SUN_HALF, none = MOON_R + SUN_HALF * 1.2;
			return Math.max(0, Math.min(1, (none - sep) / (none - full)));
		}

		/** Насколько темно у наблюдателя (0..1): свет почти не меняется, пока Солнце не закрыто на 60 % */
		public double darkness(double t, double px, double pz) {
			double c = cover(t, px, pz);
			return 0.9 * smooth(0.55, 1.0, c) + 0.1 * c;
		}

		/** Начала ударных волн (тики мира) */
		public List<Long> waves() {
			List<Long> w = new ArrayList<>();
			long a = start + (long) (P_SHADOW * dur) + 200, b = start + (long) (P_RING * dur);
			int step = Math.max(300, dur / 12);
			for (long s = a; s < b - 100; s += step) w.add(s);
			w.add(start + (long) (0.815 * dur));
			return w;
		}

		/** Высота поверхности Фудзи на расстоянии r от центра (приближённо, для молний и волн) */
		public double ground(double r) {
			if (!fuji) return y;
			if (r < FUJI_RIM * 0.85) return y;
			if (r < FUJI_RIM) return y + (SEA + FUJI_H + 7 - y) * smooth(FUJI_RIM * 0.85, FUJI_RIM, r);
			if (r >= FUJI_FOOT) return SEA + 3;
			double s = (r - FUJI_RIM) / (FUJI_FOOT - FUJI_RIM);
			return SEA + FUJI_H * (0.62 * Math.pow(1 - s, 2.1) + 0.38 * Math.pow(1 - s, 1.2));
		}

		/** Молния у Фудзи в «слоте» i (по 4 тика): {угол, радиус} или null */
		public double[] bolt(long i) {
			long t = start + i * 4;
			double f = f(t);
			if (f < P_OMEN || f >= P_DAWN) return null;
			double chance = f < P_SHADOW ? 0.12 : f < P_RING ? 0.42 : 0.2;
			double r0 = rnd(seed, i, 1);
			if (r0 > chance) return null;
			return new double[] { rnd(seed, i, 2) * Math.PI * 2, (fuji ? FUJI_RIM + 10 : 40) + rnd(seed, i, 3) * (fuji ? 950 : 500) };
		}
	}

	public static double smooth(double e0, double e1, double v) {
		double x = Math.max(0, Math.min(1, (v - e0) / (e1 - e0)));
		return x * x * (3 - 2 * x);
	}

	public static double rnd(long seed, long i, int k) {
		long h = seed * 0x9E3779B97F4A7C15L + i * 0xBF58476D1CE4E5B9L + k * 0x94D049BB133111EBL;
		h ^= h >>> 31; h *= 0x7FB5D3B5A2F3C3E9L; h ^= h >>> 29;
		return (h >>> 11) * 0x1.0p-53;
	}

	public static String phaseName(double f) {
		if (f < P_OMEN) return "Предвестие";
		if (f < P_SHADOW) return "Тень";
		if (f < P_RING) return "Хаос";
		if (f < P_DAWN) return "Бриллиантовое кольцо";
		return "Рассвет";
	}

	// ───────── сервер ─────────

	private static State state;
	private static ServerBossEvent bar;
	private static int lastPhase = -1;
	private static final Map<UUID, Integer> WITNESS = new HashMap<>();
	private static final Set<UUID> MOBS = new HashSet<>();
	private static final Map<UUID, Integer> MOB_OWNER = new HashMap<>();
	private static final RandomSource RND = RandomSource.create();
	private static final int VIOLET = 0xC27DFF;

	public static State state() { return state; }

	public static boolean active() { return state != null; }

	/** Темнота у игрока в обычном мире (0, если затмения нет) */
	public static double darkness(ServerPlayer p) {
		if (state == null || !p.level().dimension().equals(Level.OVERWORLD)) return 0;
		return state.darkness(p.level().getGameTime(), p.getX(), p.getZ());
	}

	/** Центр по умолчанию — кратер Фудзи из Phase World. null, если мод карты не стоит */
	public static double[] fujiCenter(ServerLevel level) {
		try {
			Class<?> seeds = Class.forName("gg.phase.world.gen.LayoutSeeds");
			Method m = seeds.getMethod("layout", long.class);
			Object layout = m.invoke(null, level.getSeed());
			Class<?> c = layout.getClass();
			return new double[] { c.getField("fujiX").getDouble(layout), c.getField("fujiZ").getDouble(layout),
				c.getField("hoeiX").getDouble(layout), c.getField("hoeiZ").getDouble(layout) };
		} catch (Throwable e) {
			return null;
		}
	}

	/** Запуск. at == null — над Фудзи (или в точке из конфига, если Phase World нет) */
	public static Component start(MinecraftServer server, int seconds, Vec3 at) {
		ServerLevel level = server.overworld();
		SpaceConfig cfg = SpaceConfig.get();
		if (state != null) finish(server, false);
		State s = new State();
		s.start = level.getGameTime() + 40;
		s.dur = Math.max(60, Math.min(3600, seconds)) * 20;
		s.seed = RND.nextLong();
		double[] fuji = at == null ? fujiCenter(level) : null;
		if (fuji != null) {
			s.fuji = true;
			s.x = fuji[0]; s.z = fuji[1]; s.hx = fuji[2]; s.hz = fuji[3];
			s.y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(s.x), (int) Math.floor(s.z));
		} else {
			double x = at != null ? at.x : cfg.eclipseX, z = at != null ? at.z : cfg.eclipseZ;
			s.x = x; s.z = z; s.hx = x + 300; s.hz = z + 300;
			s.y = at != null ? at.y : level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
		}
		state = s;
		lastPhase = -1;
		WITNESS.clear();
		bar = new ServerBossEvent(UUID.randomUUID(), Component.literal("Затмение"), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.NOTCHED_10);
		bar.setDarkenScreen(true);
		if (cfg.eclipseSetNoon) {
			run(server, "time set noon");
			run(server, "weather clear " + (s.dur / 20 + 120) + "s");
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) sync(p);
		String where = s.fuji ? "над Фудзи" : "в точке " + (int) s.x + " " + (int) s.z;
		return Component.literal("☾ Затмение запущено " + where + " (кратер " + (int) s.x + " " + (int) s.y + " " + (int) s.z
			+ "), длительность " + s.dur / 1200 + " мин " + s.dur / 20 % 60 + " с.");
	}

	public static void stop(MinecraftServer server) {
		if (state != null) finish(server, false);
	}

	private static void run(MinecraftServer server, String cmd) {
		try {
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd);
		} catch (Throwable e) {
			PhaseSpace.LOG.warn("eclipse: '{}' failed: {}", cmd, e.toString());
		}
	}

	public static void sync(ServerPlayer p) {
		if (state != null) net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, EclipsePayload.of(state));
	}

	private static void finish(MinecraftServer server, boolean natural) {
		ServerLevel level = server.overworld();
		if (bar != null) bar.removeAllPlayers();
		bar = null;
		// тени рассеиваются
		for (UUID id : MOBS) {
			Entity e = level.getEntity(id);
			if (e != null && e.isAlive()) {
				level.sendParticles(ParticleTypes.REVERSE_PORTAL, e.getX(), e.getY() + 0.5, e.getZ(), 30, 0.6, 0.6, 0.6, 0.05);
				e.discard();
			}
		}
		MOBS.clear();
		MOB_OWNER.clear();
		if (natural) {
			int need = Math.max(400, state.dur / 6);
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				Integer w = WITNESS.get(p.getUUID());
				if (w != null && w >= need) {
					Atlas.discover(p, "e:witness", Component.literal("Свидетель затмения").withStyle(s -> s.withColor(VIOLET)),
						Component.literal("Пережил полную фазу в тени Луны").withStyle(ChatFormatting.GRAY), 400);
				}
			}
		}
		WITNESS.clear();
		state = null;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, EclipsePayload.STOP);
			if (p.level().dimension().equals(Level.OVERWORLD)) {
				p.sendSystemMessage(Component.literal(natural ? "☀ Затмение закончилось." : "☀ Затмение остановлено.").withStyle(s -> s.withColor(0xFFE7B0)));
				((ServerLevel) p.level()).playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.AMBIENT, 1F, 0.6F);
			}
		}
	}

	public static void tick(MinecraftServer server) {
		State s = state;
		if (s == null) return;
		ServerLevel level = server.overworld();
		long t = level.getGameTime();
		double f = s.f(t);
		if (f >= 1) { finish(server, true); return; }
		if (f < 0) return;
		SpaceConfig cfg = SpaceConfig.get();
		List<ServerPlayer> earth = level.players();

		// ── смена фазы ──
		int phase = f < P_OMEN ? 0 : f < P_SHADOW ? 1 : f < P_RING ? 2 : f < P_DAWN ? 3 : 4;
		if (phase != lastPhase) {
			lastPhase = phase;
			announce(s, phase, earth);
		}

		// ── полоса босса ──
		if (bar != null && t % 10 == 0) {
			int left = (int) ((s.start + s.dur - t) / 20);
			bar.setName(Component.literal("☾ " + phaseName(f) + "  ·  " + left / 60 + ":" + String.format("%02d", left % 60)));
			bar.setProgress((float) Math.max(0, Math.min(1, 1 - f)));
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (p.level() == level) bar.addPlayer(p);
				else bar.removePlayer(p);
			}
		}

		// ── молнии на склонах Фудзи: только звук (вспышку рисует клиент) ──
		if (t % 4 == 0) {
			long i = (t - s.start) / 4;
			double[] b = s.bolt(i);
			if (b != null) {
				double bx = s.x + Math.cos(b[0]) * b[1], bz = s.z + Math.sin(b[0]) * b[1];
				level.playSound(null, bx, s.ground(b[1]), bz, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 30F, 0.6F + (float) rnd(s.seed, i, 4) * 0.4F);
			}
		}

		// ── дым и искры над кратером ──
		if (t % 5 == 0) {
			level.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, s.x, s.y + 4, s.z, 6, 12, 2, 12, 0.02);
			if (phase == 2) level.sendParticles(ParticleTypes.REVERSE_PORTAL, s.x, s.y + 10, s.z, 40, BEAM_R * 0.5, 20, BEAM_R * 0.5, 0.2);
		}

		List<Long> waves = s.waves();
		for (ServerPlayer p : earth) {
			if (!p.isAlive() || p.isSpectator()) continue;
			boolean vulnerable = !p.isCreative();
			double dist = Math.hypot(p.getX() - s.x, p.getZ() - s.z);
			double dark = s.darkness(t, p.getX(), p.getZ());
			if (dark > 0.7) WITNESS.merge(p.getUUID(), 1, Integer::sum);

			// сердцебиение в темноте
			if (dark > 0.6 && (t + p.getId() * 3L) % 34 == 0) {
				level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.AMBIENT, 0.5F + (float) dark * 0.4F, 0.8F);
			}

			// ударные волны
			if (cfg.eclipseWaves) {
				for (long ws : waves) {
					double age = t - ws;
					if (age < 0) continue;
					double r = age * WAVE_SPEED, r0 = r - WAVE_SPEED;
					if (r > WAVE_RANGE + WAVE_SPEED) continue;
					if (dist > r0 && dist <= r) wave(level, s, p, dist, ws == waves.get(waves.size() - 1), vulnerable && cfg.eclipseWaveDamage);
				}
			}

			if (phase == 2) {
				// луч: поднимает из кратера в небо (до космоса), присесть — выйти
				if (cfg.eclipseBeam && dist < BEAM_R && p.getY() > s.y - 25) beam(level, s, p, dist, cfg);
				// дождь осколков
				if (cfg.eclipseCrystals && dist < UMBRA + 300 && (t + p.getId() * 7L) % 90 == 0) crystal(level, p);
				// молния рядом (только вспышка и гром)
				if (dist < UMBRA && (t + p.getId() * 11L) % 220 == 0 && RND.nextFloat() < 0.6F) strike(level, p);
				// фантомы в темноте
				if (cfg.eclipseMobs && vulnerable && dark > 0.6 && (t + p.getId() * 13L) % 260 == 0) shadows(level, p);
			}
		}
	}

	private static void announce(State s, int phase, List<ServerPlayer> earth) {
		String where = s.fuji ? "над Фудзи" : "над " + (int) s.x + " " + (int) s.z;
		Component msg = switch (phase) {
			case 0 -> Component.literal("☾ Начинается затмение " + where + ". Луна закрывает Солнце.").withStyle(st -> st.withColor(VIOLET));
			case 1 -> Component.literal("☾ Тень Луны накрывает остров. По склонам бьют молнии.").withStyle(st -> st.withColor(VIOLET));
			case 2 -> Component.literal("☾ Полная фаза. Из кратера поднялся луч — он несёт вверх, до космоса (присесть — выйти из луча). "
				+ "От кратера идут ударные волны: щит в блоке или присед спасают.").withStyle(st -> st.withColor(0xE0B8FF));
			case 3 -> Component.literal("✦ Бриллиантовое кольцо. Последняя волна — тени рассеиваются.").withStyle(st -> st.withColor(0xFFF4C2));
			default -> Component.literal("☀ Солнце возвращается.").withStyle(st -> st.withColor(0xFFE7B0));
		};
		SoundEvent snd = switch (phase) {
			case 0 -> SoundEvents.BEACON_POWER_SELECT;
			case 1 -> SoundEvents.ELDER_GUARDIAN_CURSE;
			case 2 -> SoundEvents.END_PORTAL_SPAWN;
			case 3 -> SoundEvents.TOTEM_USE;
			default -> SoundEvents.BEACON_ACTIVATE;
		};
		for (ServerPlayer p : earth) {
			p.sendSystemMessage(msg);
			((ServerLevel) p.level()).playSound(null, p.getX(), p.getY(), p.getZ(), snd, SoundSource.AMBIENT, phase == 2 ? 1.6F : 1F, phase == 2 ? 0.5F : 0.7F);
			if (phase == 2) p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 60, 0, false, false));
		}
	}

	private static void wave(ServerLevel level, State s, ServerPlayer p, double dist, boolean last, boolean damage) {
		double nx = (p.getX() - s.x) / Math.max(1, dist), nz = (p.getZ() - s.z) / Math.max(1, dist);
		double fade = 1 - Math.min(0.7, dist / WAVE_RANGE);
		level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.AMBIENT, 1.2F, last ? 0.5F : 0.7F);
		level.sendParticles(ParticleTypes.SONIC_BOOM, p.getX() - nx * 2, p.getY() + 1, p.getZ() - nz * 2, 1, 0, 0, 0, 0);
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, p.getX(), p.getY() + 1, p.getZ(), 40, 1.5, 1, 1.5, 0.3);
		if (p.isCreative()) return;
		if (p.isBlocking()) {
			p.push(nx * 0.25, 0.1, nz * 0.25);
			SpaceTravel.syncMotion(p);
			Atlas.discover(p, "e:wave", Component.literal("Щит против волны").withStyle(st -> st.withColor(VIOLET)),
				Component.literal("Принял ударную волну затмения на щит").withStyle(ChatFormatting.GRAY), 150);
			return;
		}
		double k = (p.isShiftKeyDown() ? 0.45 : 1.5) * fade * (last ? 1.4 : 1);
		p.push(nx * k, p.isShiftKeyDown() ? 0.15 : 0.55 * fade + 0.2, nz * k);
		SpaceTravel.syncMotion(p);
		p.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 80, 0, false, false));
		if (damage && !p.isShiftKeyDown()) p.hurtServer(level, level.damageSources().magic(), (float) (last ? 5 : 3) * (float) fade);
	}

	private static void beam(ServerLevel level, State s, ServerPlayer p, double dist, SpaceConfig cfg) {
		if (p.isShiftKeyDown()) {
			p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 200, 0, false, false));
			return;
		}
		Vec3 v = p.getDeltaMovement();
		double vy = Math.min(2.4, Math.max(v.y, 0) + 0.22);
		boolean capped = !cfg.spaceOpen && p.getY() > cfg.overworldExitY - 40;
		if (capped) vy = 0.02;
		double ax = (s.x - p.getX()) * 0.01, az = (s.z - p.getZ()) * 0.01;
		p.setDeltaMovement(v.x * 0.9 + ax, vy, v.z * 0.9 + az);
		SpaceTravel.syncMotion(p);
		p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 400, 0, false, false));
		long t = level.getGameTime();
		if (t % 3 == 0) {
			level.sendParticles(ParticleTypes.REVERSE_PORTAL, p.getX(), p.getY() + 1, p.getZ(), 12, 0.8, 1.5, 0.8, 0.15);
			level.sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() - 1, p.getZ(), 3, 0.5, 0.5, 0.5, 0.05);
		}
		if (t % 20 == 0) {
			level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BEACON_AMBIENT, SoundSource.AMBIENT, 1.5F, 0.5F + (float) Math.min(1, (p.getY() - s.y) / 1200));
			int h = (int) (p.getY() - s.y);
			p.sendOverlayMessage(Component.literal("Луч затмения  ↑ " + Math.max(0, h) + " бл"
				+ (capped ? "  ·  космос закрыт" : "  ·  до космоса " + Math.max(0, cfg.overworldExitY - (int) p.getY()))).withStyle(st -> st.withColor(0xE0B8FF)));
		}
		if (p.getY() > s.y + 500) {
			Atlas.discover(p, "e:beam", Component.literal("Луч затмения").withStyle(st -> st.withColor(VIOLET)),
				Component.literal("Поднялся по лучу из кратера выше облаков").withStyle(ChatFormatting.GRAY), 250);
		}
	}

	private static void crystal(ServerLevel level, ServerPlayer p) {
		double x = p.getX() + (RND.nextDouble() - 0.5) * 36, z = p.getZ() + (RND.nextDouble() - 0.5) * 36;
		double y = Math.max(p.getY() + 28, level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z)) + 28);
		ItemStack stack;
		Optional<Item> cluster = BuiltInRegistries.ITEM.getOptional(Identifier.fromNamespaceAndPath("phaseworld", "eclipse_crystal_cluster"));
		if (RND.nextFloat() < 0.18F) stack = new ItemStack(cluster.orElse(Items.AMETHYST_CLUSTER));
		else {
			stack = new ItemStack(Items.AMETHYST_SHARD, 1 + RND.nextInt(2));
			stack.set(DataComponents.CUSTOM_NAME, Component.literal("Осколок затмения").withStyle(st -> st.withColor(VIOLET).withItalic(false)));
			stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		}
		ItemEntity item = new ItemEntity(level, x, y, z, stack);
		item.setDeltaMovement(0, -0.6, 0);
		item.setGlowingTag(true);
		item.setPickUpDelay(10);
		level.addFreshEntity(item);
		level.sendParticles(ParticleTypes.END_ROD, x, y, z, 6, 0.2, 0.2, 0.2, 0.02);
	}

	private static void strike(ServerLevel level, ServerPlayer p) {
		double a = RND.nextDouble() * Math.PI * 2, r = 10 + RND.nextDouble() * 26;
		double x = p.getX() + Math.cos(a) * r, z = p.getZ() + Math.sin(a) * r;
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
		if (!(spawn(level, "lightning_bolt") instanceof LightningBolt bolt)) return;
		bolt.setVisualOnly(true);
		bolt.snapTo(x, y, z);
		level.addFreshEntity(bolt);
	}

	private static void shadows(ServerLevel level, ServerPlayer p) {
		int alive = 0;
		for (Map.Entry<UUID, Integer> e : MOB_OWNER.entrySet()) {
			if (e.getValue() != p.getId()) continue;
			Entity m = level.getEntity(e.getKey());
			if (m != null && m.isAlive()) alive++;
		}
		MOB_OWNER.keySet().removeIf(id -> { Entity m = level.getEntity(id); return m == null || !m.isAlive(); });
		MOBS.retainAll(MOB_OWNER.keySet());
		if (alive >= 3) return;
		double a = RND.nextDouble() * Math.PI * 2;
		double x = p.getX() + Math.cos(a) * 24, z = p.getZ() + Math.sin(a) * 24, y = p.getY() + 22;
		Entity e = spawn(level, "phantom");
		if (e == null) return;
		e.snapTo(x, y, z, (float) Math.toDegrees(a) + 90F, 0F);
		e.addTag("phasespace_eclipse");
		if (e instanceof Mob m) m.setTarget(p);
		if (level.addFreshEntity(e)) {
			MOBS.add(e.getUUID());
			MOB_OWNER.put(e.getUUID(), p.getId());
			level.sendParticles(ParticleTypes.REVERSE_PORTAL, x, y, z, 30, 0.8, 0.8, 0.8, 0.1);
		}
	}

	/** Создать сущность ванильного типа по id (без добавления в мир) */
	private static Entity spawn(ServerLevel level, String id) {
		Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.withDefaultNamespace(id));
		return type.isPresent() ? type.get().create(level, EntitySpawnReason.EVENT) : null;
	}

	/** Строки для /space eclipse */
	public static Component info(MinecraftServer server) {
		if (state == null) {
			double[] f = fujiCenter(server.overworld());
			return Component.literal("Затмения сейчас нет. Центр по умолчанию: " + (f != null ? "кратер Фудзи " + (int) f[0] + " " + (int) f[1]
				: "точка из конфига " + (int) SpaceConfig.get().eclipseX + " " + (int) SpaceConfig.get().eclipseZ + " (Phase World не найден)")
				+ ".\n/space eclipse start [секунды] · /space eclipse here [секунды] · /space eclipse stop").withStyle(ChatFormatting.GRAY);
		}
		long t = server.overworld().getGameTime();
		int left = (int) Math.max(0, (state.start + state.dur - t) / 20);
		return Component.literal("☾ Затмение: " + phaseName(state.f(t)) + ", осталось " + left / 60 + ":" + String.format("%02d", left % 60)
			+ ". Центр " + (int) state.x + " " + (int) state.y + " " + (int) state.z + (state.fuji ? " (Фудзи)" : "")).withStyle(st -> st.withColor(VIOLET));
	}
}
