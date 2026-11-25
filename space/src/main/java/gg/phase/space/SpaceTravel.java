package gg.phase.space;

import gg.phase.space.SolarSystem.Body;
import gg.phase.space.SolarSystem.Comet;
import gg.phase.space.SolarSystem.Deep;
import gg.phase.space.SpaceEvents.Event;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Collection;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Перелёты и физика космоса (сервер).
 * <ul>
 *   <li>Обычный мир → космос: подняться выше Y 2000 (элитры + фейерверки). Игрок появляется над Землёй.</li>
 *   <li>Космос → обычный мир: спуститься на 2000 блоков ниже плоскости системы (Y −2000). Вход в атмосферу:
 *       игрок падает с высоты 1950 туда, откуда улетал, горит, но не разбивается, пока не коснётся земли.</li>
 *   <li>Космос → мир планеты: влететь в куб планеты или спутника. Мир планеты → космос: подняться к его верху.</li>
 *   <li>Невесомость: в космосе полёт у всех. Бег по прямой разгоняет («Разгон»), в дальнем космосе — сильнее.</li>
 *   <li>Звёзды обжигают, чёрная дыра Ёми притягивает и за горизонтом выбрасывает к квазару TON 618,
 *       ядро квазара возвращает домой, пульсар облучает.</li>
 *   <li>События (SpaceEvents): объявления, метеориты из блоков, солнечный ветер, пролёт блуждающей планеты.</li>
 * </ul>
 */
public final class SpaceTravel {
	public static final ResourceKey<Level> SPACE = ResourceKey.create(Registries.DIMENSION, PhaseSpace.id("space"));
	private static final Identifier GRAVITY_ID = PhaseSpace.id("planet_gravity");
	private static final Identifier FALL_ID = PhaseSpace.id("planet_fall");
	private static final float VANILLA_FLY_SPEED = 0.05F;
	private static final double DEEP_SPACE = 30000;   // дальше этого от Солнца — дальний космос

	private static final Set<UUID> FLYERS = new HashSet<>();          // кому мод включил полёт
	private static final Set<UUID> REENTRY = new HashSet<>();         // падают с орбиты на Землю
	private static final Map<UUID, Long> COOLDOWN = new HashMap<>();   // до какого тика нельзя снова прыгать между мирами
	private static final Map<UUID, double[]> RETURN = new HashMap<>(); // где игрок покинул обычный мир
	private static final Map<UUID, double[]> GRAVITY = new HashMap<>(); // {id сущности, g}
	private static final Map<UUID, double[]> WARP = new HashMap<>();    // {разгон, x, y, z прошлого тика, скорость бл/с}
	private static final Map<UUID, double[]> HEAT = new HashMap<>();    // {нагрев 0..100, x, y, z прошлого тика}
	private static final double[] P = new double[3], Q = new double[3];
	private static final RandomSource RND = RandomSource.create();
	private static long tick;
	private static long announced = Long.MIN_VALUE, warned = Long.MIN_VALUE;

	private SpaceTravel() {}

	public static void tick(MinecraftServer server) {
		tick++;
		double t = server.overworld().getGameTime();
		SpaceConfig cfg = SpaceConfig.get();
		Event ev = cfg.events ? SpaceEvents.at(t) : null;
		List<ServerPlayer> inSpace = new ArrayList<>();
		for (ServerPlayer p : new ArrayList<>(server.getPlayerList().getPlayers())) {
			if (!p.isAlive()) continue;
			ResourceKey<Level> dim = p.level().dimension();
			if (!cfg.spaceOpen && !dim.equals(Level.OVERWORLD) && !p.isCreative() && !p.isSpectator()
				&& (dim.equals(SPACE) || SolarSystem.byDimension(dim.identifier().toString()) != null)) {
				sendHome(server, p, false);
				p.sendSystemMessage(Component.literal("Космос закрыт — вы возвращены на Землю.").withStyle(s -> s.withColor(0xFF8A5C)));
				continue;
			}
			if (dim.equals(SPACE)) {
				inSpace.add(p);
				inSpace(server, p, t, cfg, ev);
				applyGravity(p, 1.0);
				continue;
			}
			release(p);
			if (dim.equals(Level.OVERWORLD)) {
				overworld(server, p, t, cfg);
				applyGravity(p, 1.0);
				continue;
			}
			REENTRY.remove(p.getUUID());
			Body planet = SolarSystem.byDimension(dim.identifier().toString());
			if (planet != null) onPlanet(server, p, planet, t, cfg);
			applyGravity(p, planet == null ? 1.0 : planet.gravity);
		}
		if (cfg.events) events(server, inSpace, t, ev, cfg);
		if (tick % 1200 == 0) {
			Set<UUID> online = new HashSet<>();
			for (ServerPlayer p : server.getPlayerList().getPlayers()) online.add(p.getUUID());
			FLYERS.retainAll(online);
			COOLDOWN.keySet().retainAll(online);
			GRAVITY.keySet().retainAll(online);
			WARP.keySet().retainAll(online);
			HEAT.keySet().retainAll(online);
		}
	}

	// ───────── Космос ─────────

	private static void inSpace(MinecraftServer server, ServerPlayer p, double t, SpaceConfig cfg, Event ev) {
		if (p.isSpectator()) return;
		ServerLevel level = (ServerLevel) p.level();
		double x = p.getX(), y = p.getY(), z = p.getZ(), ey = y + 0.9;
		double fromSun = Math.sqrt(x * x + (ey - SolarSystem.PLANE_Y) * (ey - SolarSystem.PLANE_Y) + z * z);

		// невесомость и разгон
		double[] w = WARP.computeIfAbsent(p.getUUID(), k -> new double[] { 0, x, y, z, 0 });
		double mx = x - w[1], my = y - w[2], mz = z - w[3];
		double perTick = Math.sqrt(mx * mx + my * my + mz * mz);
		w[1] = x; w[2] = y; w[3] = z;
		w[4] = w[4] * 0.8 + perTick * 20 * 0.2;
		boolean vil = Viltrum.has(p);
		double cap = vil ? cfg.viltrumWarp : fromSun > DEEP_SPACE ? cfg.warpMaxDeep : cfg.warpMaxSystem;
		if (p.isSprinting() && perTick > 0.6 && perTick < 200) w[0] = Math.min(cap, w[0] + (vil ? 0.012 : fromSun > DEEP_SPACE ? 0.004 : 0.0025));
		else w[0] = Math.max(0, w[0] - 0.03);
		Abilities ab = p.getAbilities();
		boolean changed = false;
		if (!ab.mayfly) { ab.mayfly = true; changed = true; }
		if (!ab.flying) { ab.flying = true; changed = true; }
		float speed = (float) (cfg.flySpeed + w[0]);
		if (Math.abs(ab.getFlyingSpeed() - speed) > 0.004F) { ab.setFlyingSpeed(speed); changed = true; }
		if (changed) p.onUpdateAbilities();
		FLYERS.add(p.getUUID());
		if (p.isFallFlying()) p.stopFallFlying();
		p.resetFallDistance();
		// вильтрумит: гиперполёт — прыжки вперёд, когда разгон на пределе
		if (vil && Viltrum.hyper(p, level, w[0] >= cap * 0.97 && p.isSprinting(), fromSun, t, x, ey, z)) return;

		// низ — дорога домой, верх — граница
		int max = level.getMinY() + level.getHeight();
		if (y < cfg.spaceReturnY) {
			if (COOLDOWN.getOrDefault(p.getUUID(), 0L) <= tick) { reentry(server, p); return; }
		} else if (y < cfg.spaceReturnY + 64 && cfg.navigation && tick % 20 == 0) {
			p.sendOverlayMessage(Component.literal("Земля через " + fmt(y - cfg.spaceReturnY) + " бл вниз").withStyle(s -> s.withColor(0x8FC8FF)));
		}
		if (y > max - 24) {
			teleport(p, level, x, max - 30, z, p.getYRot(), p.getXRot());
			return;
		}
		if (y < level.getMinY() + 4) {
			teleport(p, level, x, level.getMinY() + 8, z, p.getYRot(), p.getXRot());
			return;
		}

		// Солнце
		double sr = SolarSystem.SUN.size * 0.5;
		if (fromSun < sr + 6) {
			double k = (sr + 20) / Math.max(1e-3, fromSun);
			teleport(p, level, x * k, SolarSystem.PLANE_Y + (ey - SolarSystem.PLANE_Y) * k - 0.9, z * k, p.getYRot(), p.getXRot());
			if (!vil) p.setRemainingFireTicks(200);
			p.sendOverlayMessage(Component.literal("Солнце не пускает дальше").withStyle(s -> s.withColor(0xFFB347)));
			return;
		}
		double flare = ev != null && ev.type() == SpaceEvents.Type.SOLAR_FLARE ? SpaceEvents.flare(t) : 0;
		if (cfg.starHeat && !vil && tick % 20 == 0 && (fromSun < sr * 2.1 || (flare > 0.3 && fromSun < 2500))) {
			p.setRemainingFireTicks(Math.max(p.getRemainingFireTicks(), 60));
		}
		if (flare > 0 && fromSun < 6000) {   // солнечный ветер
			double k = 0.06 * flare * (1 - fromSun / 6000) / Math.max(1, fromSun);
			p.push(x * k, (ey - SolarSystem.PLANE_Y) * k, z * k);
			syncMotion(p);
		}

		// дальний космос
		if (fromSun > DEEP_SPACE * 0.6 && deepSpace(server, p, level, x, ey, z, cfg)) return;

		// блуждающая планета расталкивает всех на пути
		if (ev != null && ev.type() == SpaceEvents.Type.ROGUE_PLANET && SpaceEvents.rogue(t, P)) {
			double dx = x - P[0], dy = ey - P[1], dz = z - P[2];
			double d = Math.sqrt(dx * dx + dy * dy + dz * dz), r = SpaceEvents.ROGUE_SIZE * 0.6;
			if (d < r + 6) {
				double k = (r + 14) / Math.max(1e-3, d);
				teleport(p, level, P[0] + dx * k, P[1] + dy * k - 0.9, P[2] + dz * k, p.getYRot(), p.getXRot());
				p.sendOverlayMessage(Component.literal("Блуждающая планета отбросила вас").withStyle(s -> s.withColor(0xFF6A3D)));
				return;
			}
		}

		// порталы планет и спутников
		if (COOLDOWN.getOrDefault(p.getUUID(), 0L) <= tick) {
			for (Body b : SolarSystem.BODIES) {
				if (b.dim == null) continue;
				b.pos(t, P);
				double dx = x - P[0], dy = ey - P[1], dz = z - P[2];
				double reach = b.halfMax() + 0.6;
				if (Math.abs(dx) > reach || Math.abs(dy) > reach || Math.abs(dz) > reach) continue;
				double hx = dx / (b.size * 0.5 * b.sx), hy = dy / (b.size * 0.5 * b.sy), hz = dz / (b.size * 0.5 * b.sz);
				if (hx * hx + hy * hy + hz * hz < 1.05) {
					enter(server, p, b, Math.max(-1, Math.min(1, hx)), Math.max(-1, Math.min(1, hz)));
					return;
				}
			}
		}
		if (cfg.navigation && (tick + p.getId()) % 10 == 0) nav(p, t, x, ey, z, w, ev);
	}

	/** Чёрная дыра, квазар, пульсар, двойная звезда. true — игрок перемещён */
	private static boolean deepSpace(MinecraftServer server, ServerPlayer p, ServerLevel level, double x, double y, double z, SpaceConfig cfg) {
		boolean vil = Viltrum.has(p);
		boolean heat = cfg.starHeat && !vil;
		for (Deep d : SolarSystem.DEEP) {
			double dx = d.x - x, dy = d.y - y, dz = d.z - z;
			double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
			switch (d.type) {
				case "black_hole" -> {
					if (dist > d.pull) continue;
					double horizon = d.size * 0.5;
					if (dist < horizon) {
						// за горизонтом событий: кротовая нора к квазару
						Deep q = SolarSystem.deep("ton618");
						double ox = q == null ? 0 : q.x - 9000, oy = q == null ? 0 : q.y + 200, oz = q == null ? 0 : q.z + 4000;
						teleport(p, level, ox, oy, oz, yawTo(q == null ? 1 : q.x - ox, q == null ? 0 : q.z - oz), 0F);
						p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 120, 0, false, false));
						p.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 200, 0, false, false));
						level.playSound(null, ox, oy, oz, SoundEvents.PORTAL_TRAVEL, SoundSource.PLAYERS, 0.6F, 0.5F);
						p.sendSystemMessage(Component.literal("Ёми проглотила вас — и выбросила у квазара TON 618. Его ядро ведёт домой.")
							.withStyle(s -> s.withColor(d.color)));
						return true;
					}
					double k = Math.min(1.4, 450000.0 / (dist * dist)) / dist * (vil ? 0.12 : 1);
					p.push(dx * k, dy * k, dz * k);
					syncMotion(p);
					if (!vil && dist < 1500 && tick % 40 == 0) p.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 120, 0, false, false));
					if (!vil && dist < 700 && tick % 40 == 0) p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 60, 0, false, false));
				}
				case "quasar" -> {
					double horiz = Math.sqrt(dx * dx + dz * dz);
					if (dist < d.size * 0.22) {
						Body earth = SolarSystem.byId("earth");
						earth.pos(server.overworld().getGameTime(), P);
						teleport(p, level, P[0], P[1] + earth.size * 0.5 + 60, P[2], yawTo(-P[0], -P[2]), 10F);
						p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0, false, false));
						level.playSound(null, P[0], P[1], P[2], SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1F, 0.5F);
						p.sendSystemMessage(Component.literal("Квазар TON 618 вернул вас домой.").withStyle(s -> s.withColor(d.color)));
						return true;
					}
					if (heat && tick % 20 == 0 && (dist < d.size * 1.6 || (horiz < 500 && Math.abs(dy) > d.size * 0.4 && Math.abs(dy) < d.jet))) {
						p.setRemainingFireTicks(Math.max(p.getRemainingFireTicks(), 60));
						if (horiz < 500) { p.push(0, dy > 0 ? -1.2 : 1.2, 0); syncMotion(p); } // джет сносит
					}
				}
				case "pulsar" -> {
					if (heat && dist < 1400 && tick % 40 == 0) {
						p.addEffect(new MobEffectInstance(MobEffects.WITHER, 60, dist < 500 ? 1 : 0, false, true));
						p.sendOverlayMessage(Component.literal("Излучение пульсара").withStyle(s -> s.withColor(d.color)));
					}
				}
				case "binary" -> {
					if (!heat || dist > d.sep * 2) continue;
					double t = server.overworld().getGameTime();
					for (int k = 0; k < 2; k++) {
						binaryStar(d, t, k, Q);
						double sx = x - Q[0], sy = y - Q[1], sz = z - Q[2];
						double sd = Math.sqrt(sx * sx + sy * sy + sz * sz), r = d.size * (k == 0 ? 0.5 : 0.38);
						if (sd < r + 6) {
							double m = (r + 24) / Math.max(1e-3, sd);
							teleport(p, level, Q[0] + sx * m, Q[1] + sy * m - 0.9, Q[2] + sz * m, p.getYRot(), p.getXRot());
							p.setRemainingFireTicks(200);
							return true;
						}
						if (sd < r * 2.2 && tick % 20 == 0) p.setRemainingFireTicks(Math.max(p.getRemainingFireTicks(), 60));
					}
				}
				default -> { }
			}
		}
		return false;
	}

	/** Центр звезды k (0 — голубая Идзанаги, 1 — оранжевая Идзанами) двойной системы */
	public static void binaryStar(Deep d, double t, int k, double[] out) {
		double a = SolarSystem.TAU * SolarSystem.frac(t / d.period) + (k == 0 ? 0 : Math.PI);
		double r = d.sep * (k == 0 ? 0.42 : 0.58);
		out[0] = d.x + Math.cos(a) * r;
		out[1] = d.y + Math.sin(a) * r * 0.25;
		out[2] = d.z + Math.sin(a) * r;
	}

	/** Влёт в планету: rx, rz — точка входа относительно центра (−1…1) */
	private static void enter(MinecraftServer server, ServerPlayer p, Body b, double rx, double rz) {
		ServerLevel target = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(b.dim)));
		if (target == null) {
			PhaseSpace.LOG.warn("No world {} for {}", b.dim, b.id);
			COOLDOWN.put(p.getUUID(), tick + 100);
			return;
		}
		double x, z;
		double[] back = b.id.equals("earth") ? RETURN.remove(p.getUUID()) : null;
		if (back != null) {
			x = back[0];
			z = back[1];
		} else {
			double spread = b.id.equals("earth") ? 1200 : 600;
			x = rx * spread;
			z = rz * spread;
		}
		int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
		target.getChunk(bx >> 4, bz >> 4);
		int y = target.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
		boolean ground = y > target.getMinY() + 1;
		if (!ground) y = target.getMinY() + target.getHeight() / 2;
		release(p);
		WARP.remove(p.getUUID());
		COOLDOWN.put(p.getUUID(), tick + 100);
		teleport(p, target, bx + 0.5, y + 0.05, bz + 0.5, p.getYRot(), 0F);
		p.resetFallDistance();
		p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, ground ? 60 : 600, 0, false, false));
		target.playSound(null, bx + 0.5, y + 1.0, bz + 0.5, SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.7F, 0.6F);
		p.sendOverlayMessage(Component.literal(b.name).withStyle(s -> s.withColor(b.color)));
		Atlas.visited(p, b);
	}

	/** Спуск ниже Y −2000: падение на Землю туда, откуда игрок улетал */
	private static void reentry(MinecraftServer server, ServerPlayer p) {
		ServerLevel world = server.overworld();
		SpaceConfig cfg = SpaceConfig.get();
		double[] back = RETURN.remove(p.getUUID());
		double x = back != null ? back[0] : p.getX() * 0.02, z = back != null ? back[1] : p.getZ() * 0.02;
		double y = cfg.overworldExitY - 60;
		release(p);
		WARP.remove(p.getUUID());
		COOLDOWN.put(p.getUUID(), tick + 200);
		REENTRY.add(p.getUUID());
		teleport(p, world, x, y, z, p.getYRot(), 30F);
		HEAT.put(p.getUUID(), new double[] { 40, x, y, z });
		p.resetFallDistance();
		world.playSound(null, x, y, z, SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 0.8F, 0.6F);
		p.sendOverlayMessage(Component.literal("Вход в атмосферу").withStyle(s -> s.withColor(0xFF9A4A)));
	}

	// ───────── Обычный мир и миры планет ─────────

	private static void overworld(MinecraftServer server, ServerPlayer p, double t, SpaceConfig cfg) {
		heat(p, cfg);
		if (REENTRY.contains(p.getUUID())) {
			// падение с орбиты: урона от падения нет, пока не коснётся земли или воды
			p.resetFallDistance();
			if (p.onGround() || p.isInWater() || tick % 20 == 0 && COOLDOWN.getOrDefault(p.getUUID(), 0L) + 2400 < tick) {
				REENTRY.remove(p.getUUID());
			}
			return;
		}
		if (p.isSpectator()) return;
		int exit = cfg.overworldExitY;
		double y = p.getY();
		if (y < exit - 64) return;
		if (!cfg.spaceOpen) {
			if (tick % 20 == 0) p.sendOverlayMessage(Component.literal("Космос закрыт").withStyle(s -> s.withColor(0xFF8A5C)));
			return;
		}
		if (y < exit) {
			if (cfg.navigation && tick % 20 == 0) {
				p.sendOverlayMessage(Component.literal("Космос через " + fmt(Math.max(0, exit - y)) + " бл").withStyle(s -> s.withColor(0xBFD7FF)));
			}
			return;
		}
		if (COOLDOWN.getOrDefault(p.getUUID(), 0L) > tick) return;
		RETURN.put(p.getUUID(), new double[] { p.getX(), p.getZ() });
		Body earth = SolarSystem.byId("earth");
		earth.pos(t, P);
		toSpace(server, p, P[0], P[1] + earth.size * 0.5 + 50, P[2], yawTo(-P[0], -P[2]), earth);
	}

	/**
	 * Нагрев в атмосфере: быстрый спуск выше atmosphereY раскаляет игрока, как обшивку корабля.
	 * Скорость спуска (и треть горизонтальной) выше 1,6 бл/тик греет, медленнее — остывает.
	 * Элитры (торможение) греют вдвое меньше. Видно пламя, искры, дым; на сильном нагреве — короткие
	 * вспышки огня (≈ полсердца в 2 с), зелье огнестойкости спасает. Клиент рисует плазменный кокон сам.
	 */
	private static void heat(ServerPlayer p, SpaceConfig cfg) {
		double[] h = HEAT.computeIfAbsent(p.getUUID(), k -> new double[] { 0, p.getX(), p.getY(), p.getZ() });
		double vx = p.getX() - h[1], vy = p.getY() - h[2], vz = p.getZ() - h[3];
		h[1] = p.getX(); h[2] = p.getY(); h[3] = p.getZ();
		if (vx * vx + vy * vy + vz * vz > 2500) return;                 // телепорт
		double speed = Math.max(0, -vy) + Math.sqrt(vx * vx + vz * vz) * 0.35;
		boolean high = p.getY() > cfg.atmosphereY && p.getY() < cfg.overworldExitY + 100;
		if (high && speed > 1.6 && !p.isSpectator() && !p.isCreative()) {
			h[0] = Math.min(100, h[0] + (speed - 1.6) * (p.isFallFlying() ? 2.5 : 5));
		} else {
			h[0] = Math.max(0, h[0] - (high ? 2 : 6));
		}
		double heat = h[0];
		if (heat < 8) return;
		ServerLevel level = (ServerLevel) p.level();
		// фронт плазмы — по направлению движения, хвост дыма — позади
		double nx = vx / Math.max(1e-3, Math.sqrt(vx * vx + vy * vy + vz * vz)), ny = vy / Math.max(1e-3, Math.sqrt(vx * vx + vy * vy + vz * vz)), nz = vz / Math.max(1e-3, Math.sqrt(vx * vx + vy * vy + vz * vz));
		double fx = p.getX() + nx * 1.4, fy = p.getY() + 0.9 + ny * 1.4, fz = p.getZ() + nz * 1.4;
		int n = (int) (heat / 12);
		level.sendParticles(ParticleTypes.FLAME, fx, fy, fz, n + 1, 0.6, 0.6, 0.6, 0.04);
		if (heat > 35) level.sendParticles(ParticleTypes.LAVA, fx, fy, fz, n / 3 + 1, 0.5, 0.5, 0.5, 0.0);
		if (heat > 50) level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, fx, fy, fz, n / 2, 0.8, 0.8, 0.8, 0.05);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, p.getX() - nx * 3, p.getY() + 0.9 - ny * 3, p.getZ() - nz * 3, 1 + n / 3, 0.4, 0.4, 0.4, 0.01);
		if (tick % 16 == 0 && heat > 30) level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.FIRE_AMBIENT, SoundSource.PLAYERS, (float) (0.4 + heat / 100), 0.6F);
		if (tick % 10 == 0) {
			int bars = (int) Math.round(heat / 10);
			int col = heat > 80 ? 0xFF4A2A : heat > 50 ? 0xFF9A3A : 0xFFD27A;
			p.sendOverlayMessage(Component.literal("Нагрев " + "▮".repeat(bars) + "▯".repeat(10 - bars) + "  " + Math.round(400 + heat * 16) + "°")
				.withStyle(s -> s.withColor(col)));
		}
		if (cfg.reentryDamage && heat > 80 && tick % 40 == 0 && !p.hasEffect(MobEffects.FIRE_RESISTANCE)) {
			p.setRemainingFireTicks(Math.max(p.getRemainingFireTicks(), 12));
		}
	}

	// ───────── Для команд ─────────

	/** Отправить в космос рядом с телом (или с Землёй, если null) */
	public static void sendToSpace(MinecraftServer server, ServerPlayer p, Body near) {
		Body b = near == null ? SolarSystem.byId("earth") : near;
		if (p.level().dimension().equals(Level.OVERWORLD)) RETURN.put(p.getUUID(), new double[] { p.getX(), p.getZ() });
		b.pos(server.overworld().getGameTime(), P);
		double off = b.halfMax() + Math.max(30, b.size * 0.6);
		toSpace(server, p, P[0] + off * 0.6, P[1] + off * 0.5, P[2] + off * 0.6, yawTo(-0.6, -0.6), b);
	}

	/** К объекту дальнего космоса — на безопасное расстояние */
	public static void sendToDeep(MinecraftServer server, ServerPlayer p, Deep d) {
		double off = switch (d.type) {
			case "black_hole" -> d.pull + 1500;
			case "quasar" -> d.size * 8;
			case "nebula" -> d.size * 0.9;
			case "binary" -> d.sep * 2.4;
			default -> 3000;
		};
		if (p.level().dimension().equals(Level.OVERWORLD)) RETURN.put(p.getUUID(), new double[] { p.getX(), p.getZ() });
		toSpace(server, p, d.x + off * 0.8, d.y + off * 0.15, d.z + off * 0.6, yawTo(-0.8, -0.6), SolarSystem.byId("earth"));
		p.sendOverlayMessage(Component.literal(d.name).withStyle(s -> s.withColor(d.color)));
	}

	/** Высадить на поверхность мира тела */
	public static void landOn(MinecraftServer server, ServerPlayer p, Body b) {
		if (b.dim == null) return;
		if (b.id.equals("earth")) { sendHome(server, p, false); return; }
		COOLDOWN.remove(p.getUUID());
		enter(server, p, b, (RND.nextDouble() - 0.5) * 0.4, (RND.nextDouble() - 0.5) * 0.4);
	}

	/** Домой на Землю: туда, откуда улетал (или 0 0). reentry — с падением из атмосферы */
	public static void sendHome(MinecraftServer server, ServerPlayer p, boolean reentry) {
		if (reentry) { reentry(server, p); return; }
		ServerLevel world = server.overworld();
		double[] back = RETURN.remove(p.getUUID());
		int x = back != null ? (int) Math.floor(back[0]) : 0, z = back != null ? (int) Math.floor(back[1]) : 0;
		world.getChunk(x >> 4, z >> 4);
		int y = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		release(p);
		WARP.remove(p.getUUID());
		REENTRY.remove(p.getUUID());
		COOLDOWN.put(p.getUUID(), tick + 100);
		teleport(p, world, x + 0.5, y + 0.1, z + 0.5, p.getYRot(), 0F);
		p.resetFallDistance();
	}

	/** Закрыть космос: все из космоса и миров планет — домой (кроме креатива и наблюдателей) */
	public static int closeSpace(MinecraftServer server) {
		int n = 0;
		for (ServerPlayer p : new ArrayList<>(server.getPlayerList().getPlayers())) {
			ResourceKey<Level> dim = p.level().dimension();
			if (p.isCreative() || p.isSpectator()) continue;
			if (dim.equals(SPACE) || SolarSystem.byDimension(dim.identifier().toString()) != null && !dim.equals(Level.OVERWORLD)) {
				sendHome(server, p, false);
				n++;
			}
		}
		return n;
	}

	public static Collection<UUID> flyers() { return FLYERS; }

	private static void onPlanet(MinecraftServer server, ServerPlayer p, Body b, double t, SpaceConfig cfg) {
		if (p.isSpectator()) return;
		ServerLevel level = (ServerLevel) p.level();
		int exit = level.getMinY() + level.getHeight() - cfg.planetExitMargin;
		double y = p.getY();
		if (y < exit - 64) return;
		if (y < exit) {
			if (cfg.navigation && tick % 20 == 0) {
				p.sendOverlayMessage(Component.literal("Космос через " + fmt(Math.max(0, exit - y)) + " бл").withStyle(s -> s.withColor(0xBFD7FF)));
			}
			return;
		}
		if (COOLDOWN.getOrDefault(p.getUUID(), 0L) > tick) return;
		double dx = p.getX(), dz = p.getZ();
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 1) { dx = 1; dz = 0; len = 1; }
		dx /= len; dz /= len;
		b.pos(t, P);
		double off = b.halfMax() + 12;
		toSpace(server, p, P[0] + dx * off, P[1] + 3, P[2] + dz * off, yawTo(dx, dz), b);
	}

	private static void toSpace(MinecraftServer server, ServerPlayer p, double x, double y, double z, float yaw, Body near) {
		ServerLevel space = server.getLevel(SPACE);
		if (space == null) {
			PhaseSpace.LOG.warn("Space dimension phasespace:space is missing");
			COOLDOWN.put(p.getUUID(), tick + 200);
			return;
		}
		int min = space.getMinY(), max = min + space.getHeight();
		y = Math.max(min + 40, Math.min(max - 40, y));
		COOLDOWN.put(p.getUUID(), tick + 100);
		if (p.isFallFlying()) p.stopFallFlying();
		teleport(p, space, x, y, z, yaw, 8F);
		Abilities ab = p.getAbilities();
		ab.mayfly = true;
		ab.flying = true;
		ab.setFlyingSpeed((float) SpaceConfig.get().flySpeed);
		p.onUpdateAbilities();
		FLYERS.add(p.getUUID());
		p.resetFallDistance();
		space.playSound(null, x, y, z, SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 0.8F, 0.5F);
		p.sendOverlayMessage(Component.literal("Космос").withStyle(s -> s.withColor(0xBFD7FF))
			.append(Component.literal("  ·  рядом " + near.name).withStyle(ChatFormatting.GRAY)));
	}

	/** Выключить полёт, который включил мод */
	static void release(ServerPlayer p) {
		if (!FLYERS.remove(p.getUUID())) return;
		Abilities ab = p.getAbilities();
		if (Viltrum.has(p)) {                  // вильтрумит летает и вне космоса — полёт не выключаем
			ab.setFlyingSpeed(VANILLA_FLY_SPEED);
			p.onUpdateAbilities();
			return;
		}
		ab.setFlyingSpeed(VANILLA_FLY_SPEED);
		if (!p.isCreative() && !p.isSpectator()) {
			ab.mayfly = false;
			ab.flying = false;
		}
		p.onUpdateAbilities();
	}

	private static void applyGravity(ServerPlayer p, double g) {
		double[] last = GRAVITY.get(p.getUUID());
		if (last != null && last[0] == p.getId() && last[1] == g) return;
		GRAVITY.put(p.getUUID(), new double[] { p.getId(), g });
		AttributeInstance grav = p.getAttribute(Attributes.GRAVITY);
		AttributeInstance fall = p.getAttribute(Attributes.FALL_DAMAGE_MULTIPLIER);
		if (g == 1.0) {
			if (grav != null) grav.removeModifier(GRAVITY_ID);
			if (fall != null) fall.removeModifier(FALL_ID);
			return;
		}
		if (grav != null) grav.addOrReplacePermanentModifier(new AttributeModifier(GRAVITY_ID, g - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		if (fall != null) fall.addOrReplacePermanentModifier(new AttributeModifier(FALL_ID, Math.max(-0.9, g - 1.0), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
	}

	// ───────── События ─────────

	private static void events(MinecraftServer server, List<ServerPlayer> players, double t, Event ev, SpaceConfig cfg) {
		if (players.isEmpty()) return;
		Event soon = SpaceEvents.next(t);
		if (soon != null && soon.start() - t < 1200 && soon.start() != warned) {
			warned = soon.start();
			for (ServerPlayer p : players) {
				p.sendSystemMessage(Component.literal("Через минуту: " + soon.type().title).withStyle(s -> s.withColor(soon.type().color)));
			}
		}
		if (ev == null) return;
		if (ev.start() != announced) {
			announced = ev.start();
			String what = switch (ev.type()) {
				case METEOR_SHOWER -> "Метеорный поток. Смотрите на радиант — и под ноги: рядом падают метеориты с рудой.";
				case SOLAR_FLARE -> "Солнечная вспышка. Солнечный ветер сносит, у Солнца жжёт. Полярные сияния на планетах.";
				case ECLIPSE -> "Затмение Фазы. Солнце гаснет, остаётся только корона.";
				case GREAT_COMET -> "Великая комета идёт к Солнцу. Хвост будет через полнеба.";
				case ROGUE_PLANET -> "Блуждающая планета пролетает сквозь систему.";
				case GAMMA_BURST -> "Гамма-всплеск квазара TON 618.";
			};
			for (ServerPlayer p : players) {
				p.sendSystemMessage(Component.literal("◆ " + ev.type().title + ". ").withStyle(s -> s.withColor(ev.type().color))
					.append(Component.literal(what).withStyle(ChatFormatting.GRAY)));
			}
		}
		if (ev.type() == SpaceEvents.Type.METEOR_SHOWER && cfg.meteorites && tick % 50 == 0) {
			double power = SpaceEvents.shower(t);
			SpaceEvents.radiant(ev, Q);
			for (ServerPlayer p : players) {
				if (p.isSpectator() || RND.nextDouble() > 0.45 * power) continue;
				ServerLevel level = (ServerLevel) p.level();
				// метеорит падает в 25–70 блоках от игрока, со стороны, куда летит поток
				double ang = RND.nextDouble() * SolarSystem.TAU, r = 25 + RND.nextDouble() * 45;
				double ux = -Q[2], uz = Q[0];
				double ul = Math.max(1e-3, Math.sqrt(ux * ux + uz * uz));
				ux /= ul; uz /= ul;
				double x = p.getX() + Math.cos(ang) * r * ux - Q[0] * 30;
				double z = p.getZ() + Math.cos(ang) * r * uz - Q[2] * 30;
				double y = p.getY() + Math.sin(ang) * r * 0.6 - Q[1] * 30;
				int miny = level.getMinY() + 10, maxy = level.getMinY() + level.getHeight() - 10;
				y = Math.max(miny, Math.min(maxy, y));
				BlockPos at = BlockPos.containing(x, y, z);
				AsteroidFeature.meteorite(level, RND, at);
				level.sendParticles(ParticleTypes.EXPLOSION, x, y, z, 6, 2, 2, 2, 0.1);
				level.sendParticles(ParticleTypes.FLAME, x, y, z, 30, 2.5, 2.5, 2.5, 0.15);
				level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.AMBIENT, 2.5F, 0.7F + RND.nextFloat() * 0.3F);
			}
		}
	}

	// ───────── Навигация ─────────

	/** Над хотбаром: на что смотрит игрок (или ближайшее тело), расстояние, разгон */
	private static void nav(ServerPlayer p, double t, double x, double y, double z, double[] warp, Event ev) {
		Vec3 look = p.getLookAngle();
		String name = null;
		int color = 0xFFFFFF;
		double dist = 0, size = 0, best = Math.toRadians(6.0);
		boolean portal = false;
		for (int i = -1; i < SolarSystem.BODIES.size(); i++) {
			Body b = i < 0 ? SolarSystem.SUN : SolarSystem.BODIES.get(i);
			b.pos(t, P);
			double score = score(look, P[0] - x, P[1] - y, P[2] - z, b.halfMax() * 1.2);
			if (score < best) {
				best = score; name = b.name; color = b.color; dist = len(P[0] - x, P[1] - y, P[2] - z) - b.halfMax(); size = b.size; portal = b.dim != null;
			}
		}
		for (Comet c : SolarSystem.COMETS) {
			c.pos(t, P);
			double score = score(look, P[0] - x, P[1] - y, P[2] - z, c.size * 4) + (c.minor ? Math.toRadians(1) : 0);
			if (score < best) { best = score; name = c.name; color = 0xCFF4FF; dist = len(P[0] - x, P[1] - y, P[2] - z); size = 0; portal = false; }
		}
		for (Deep d : SolarSystem.DEEP) {
			double score = score(look, d.x - x, d.y - y, d.z - z, d.size * 2);
			if (score < best) { best = score; name = d.name; color = d.color; dist = Math.max(0, len(d.x - x, d.y - y, d.z - z) - d.size * 0.5); size = 0; portal = false; }
		}
		if (ev != null && SpaceEvents.greatComet(t, P, null)) {
			double score = score(look, P[0] - x, P[1] - y, P[2] - z, 60);
			if (score < best) { best = score; name = "Великая комета"; color = 0xCFF4FF; dist = len(P[0] - x, P[1] - y, P[2] - z); size = 0; portal = false; }
		}
		if (ev != null && SpaceEvents.rogue(t, P)) {
			double score = score(look, P[0] - x, P[1] - y, P[2] - z, SpaceEvents.ROGUE_SIZE);
			if (score < best) { best = score; name = "Блуждающая планета"; color = 0xFF6A3D; dist = len(P[0] - x, P[1] - y, P[2] - z) - SpaceEvents.ROGUE_SIZE / 2; size = 0; portal = false; }
		}
		if (name == null) {
			double near = 1500;
			for (Body b : SolarSystem.BODIES) {
				b.pos(t, P);
				double d = len(P[0] - x, P[1] - y, P[2] - z) - b.halfMax();
				if (d < near) { near = d; name = b.name; color = b.color; dist = d; size = b.size; portal = b.dim != null; }
			}
			Deep neb = SolarSystem.deep("amaterasu");
			if (name == null && neb != null && neb.dist(x, y, z) < neb.size * 0.5) { name = neb.name; color = neb.color; dist = 0; }
		}
		MutableComponent msg = null;
		if (name != null) {
			final int col = color;
			msg = Component.literal("◆ " + name).withStyle(s -> s.withColor(col));
			if (portal && dist < Math.max(16, size * 0.8)) msg.append(Component.literal("  ·  вход в мир").withStyle(ChatFormatting.WHITE));
			else if (dist > 0) msg.append(Component.literal("  ·  " + fmt(Math.max(0, dist)) + " бл").withStyle(ChatFormatting.GRAY));
		}
		if (warp[0] > 0.02 || warp[4] > 40) {
			int bars = (int) Math.round(10 * warp[0] / Math.max(0.01, SpaceConfig.get().warpMaxDeep));
			String gauge = "▮".repeat(Math.max(0, Math.min(10, bars))) + "▯".repeat(Math.max(0, 10 - bars));
			MutableComponent sp = Component.literal("Разгон " + gauge + "  " + fmt(warp[4]) + " бл/с").withStyle(s -> s.withColor(0x9FD8FF));
			msg = msg == null ? sp : msg.append(Component.literal("    ")).append(sp);
		}
		if (msg != null) p.sendOverlayMessage(msg);
	}

	private static double score(Vec3 look, double dx, double dy, double dz, double radius) {
		double d = len(dx, dy, dz);
		if (d < 1e-3) return 9;
		double dot = Math.max(-1, Math.min(1, (dx * look.x + dy * look.y + dz * look.z) / d));
		return Math.acos(dot) - Math.atan(radius / d);
	}

	private static double len(double x, double y, double z) {
		return Math.sqrt(x * x + y * y + z * z);
	}

	private static String fmt(double v) {
		if (v >= 100000) return String.format(Locale.ROOT, "%.0f тыс", v / 1000);
		if (v >= 10000) return String.format(Locale.ROOT, "%.1f тыс", v / 1000).replace('.', ',');
		return String.format(Locale.ROOT, "%,d", Math.round(v)).replace(',', ' ');
	}

	private static float yawTo(double dx, double dz) {
		return (float) Math.toDegrees(Math.atan2(-dx, dz));
	}

	/** Сообщить клиенту новую скорость после push (сам клиент движет игрока) */
	static void syncMotion(ServerPlayer p) {
		p.connection.send(new ClientboundSetEntityMotionPacket(p.getId(), p.getDeltaMovement()));
	}

	static void teleport(ServerPlayer p, ServerLevel level, double x, double y, double z, float yaw, float pitch) {
		p.teleport(new TeleportTransition(level, new Vec3(x, y, z), Vec3.ZERO, yaw, pitch, TeleportTransition.DO_NOTHING));
	}

	// ───────── Для самотеста ─────────

	static String describe(double t) {
		StringBuilder sb = new StringBuilder();
		for (Body b : SolarSystem.BODIES) {
			b.pos(t, P);
			sb.append(String.format(Locale.ROOT, "%s(%.0f %.0f %.0f) ", b.id, P[0], P[1], P[2]));
		}
		return sb.toString();
	}
}
