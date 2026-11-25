package gg.phase.space;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import gg.phase.space.SolarSystem.Body;
import gg.phase.space.SolarSystem.Deep;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Сила вильтрумита — способность для админов (/space viltrum on [игроки]).
 * <ul>
 *   <li>Полёт в любом мире; с бегом — сверхзвук (хлопок и ударная волна при переходе барьера).</li>
 *   <li>Почти неуязвимость: сопротивление V, огнестойкость, дыхание под водой, сила V, спешка, регенерация, сытость.
 *       Звёзды, квазар, пульсар и двойная звезда не жгут, чёрная дыра почти не тянет.</li>
 *   <li>В космосе — гиперполёт: если долго лететь с бегом на полной скорости, игрок начинает «прыгать» вперёд
 *       на десятки блоков за тик (перед планетами тормозит сам).</li>
 *   <li>Рывок к цели (/space viltrum go &lt;тело&gt;): разгон 2 с — и игрок у планеты или объекта дальнего космоса,
 *       из любого мира.</li>
 *   <li>Удар о землю: падение или пике с высоты — ударная волна, раскидывает мобов (блоки не ломает).</li>
 *   <li>Удар рукой отбрасывает цель далеко (PhaseSpace регистрирует AttackEntityCallback).</li>
 *   <li>Чутьё (/space viltrum scan): ближайшие неизведанные миры и объекты с кнопками рывка.</li>
 * </ul>
 * Кто владеет силой — config/phasespace_viltrum.json (переживает перезапуск).
 */
public final class Viltrum {
	public static final int COLOR = 0xFF5A4A;
	private static final float VANILLA_FLY_SPEED = 0.05F;
	/** Скорость (бл/тик), на которой рвётся звуковой барьер */
	public static final double BOOM_SPEED = 2.2;
	private static Set<UUID> power;
	private static final Map<UUID, double[]> MOTION = new HashMap<>();   // {x, y, z, vy, скорость, кулдаун хлопка, разгон}
	private static final Map<UUID, Dash> DASH = new HashMap<>();
	private static long tick;
	private static String lastSync = "";

	private Viltrum() {}

	private record Dash(String id, String name, Body body, Deep deep, int[] left) {}

	// ───────── владельцы ─────────

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("phasespace_viltrum.json");
	}

	private static Set<UUID> power() {
		if (power == null) {
			power = new HashSet<>();
			Path f = file();
			if (Files.exists(f)) {
				try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
					List<String> ids = new Gson().fromJson(r, new TypeToken<List<String>>() {}.getType());
					if (ids != null) for (String s : ids) power.add(UUID.fromString(s));
				} catch (Exception e) {
					PhaseSpace.LOG.warn("Bad {}: {}", f, e.toString());
				}
			}
		}
		return power;
	}

	private static void save() {
		List<String> ids = new ArrayList<>();
		for (UUID u : power()) ids.add(u.toString());
		try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
			new Gson().toJson(ids, w);
		} catch (Exception e) {
			PhaseSpace.LOG.warn("Cannot write {}: {}", file(), e.toString());
		}
	}

	public static boolean has(Player p) {
		return p != null && power().contains(p.getUUID());
	}

	public static void set(ServerPlayer p, boolean on) {
		if (on == has(p)) return;
		if (on) {
			power().add(p.getUUID());
			effects(p, true);
			ServerLevel level = (ServerLevel) p.level();
			level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WITHER_SPAWN, SoundSource.PLAYERS, 0.8F, 1.3F);
			level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1F, 0.7F);
			level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, p.getX(), p.getY() + 1, p.getZ(), 1, 0, 0, 0, 0);
			level.sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1, p.getZ(), 80, 0.6, 1, 0.6, 0.3);
			p.sendSystemMessage(Component.literal("✦ Сила вильтрумита пробуждена.").withStyle(s -> s.withColor(COLOR))
				.append(Component.literal("\nДвойной прыжок — полёт, бег — сверхзвук. В космосе долгий разгон включает гиперполёт. "
					+ "Пике в землю — ударная волна. /space viltrum go <тело> — рывок к цели, /space viltrum scan — чутьё.").withStyle(ChatFormatting.GRAY)));
		} else {
			power().remove(p.getUUID());
			DASH.remove(p.getUUID());
			for (Holder<MobEffect> e : List.of(MobEffects.RESISTANCE, MobEffects.FIRE_RESISTANCE, MobEffects.WATER_BREATHING, MobEffects.STRENGTH,
				MobEffects.HASTE, MobEffects.REGENERATION, MobEffects.SATURATION, MobEffects.NIGHT_VISION)) p.removeEffect(e);
			if (!p.level().dimension().equals(SpaceTravel.SPACE)) {
				Abilities ab = p.getAbilities();
				ab.setFlyingSpeed(VANILLA_FLY_SPEED);
				if (!p.isCreative() && !p.isSpectator()) { ab.mayfly = false; ab.flying = false; }
				p.onUpdateAbilities();
				p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 200, 0, false, false));
			}
			p.sendSystemMessage(Component.literal("Сила вильтрумита угасла.").withStyle(ChatFormatting.GRAY));
		}
		save();
		lastSync = "";
	}

	private static void effects(ServerPlayer p, boolean force) {
		SpaceConfig cfg = SpaceConfig.get();
		add(p, MobEffects.RESISTANCE, 4, force);
		add(p, MobEffects.FIRE_RESISTANCE, 0, force);
		add(p, MobEffects.WATER_BREATHING, 0, force);
		add(p, MobEffects.STRENGTH, 4, force);
		add(p, MobEffects.HASTE, 2, force);
		add(p, MobEffects.REGENERATION, 1, force);
		add(p, MobEffects.SATURATION, 0, force);
		if (cfg.viltrumNightVision) add(p, MobEffects.NIGHT_VISION, 0, force);
	}

	private static void add(ServerPlayer p, Holder<MobEffect> e, int amp, boolean force) {
		MobEffectInstance cur = p.getEffect(e);
		if (!force && cur != null && cur.getAmplifier() >= amp && cur.getDuration() > 240) return;
		p.addEffect(new MobEffectInstance(e, e == MobEffects.NIGHT_VISION ? 600 : 400, amp, true, false));
	}

	// ───────── каждый тик ─────────

	public static void tick(MinecraftServer server) {
		tick++;
		Set<UUID> pw = power();
		StringBuilder ids = new StringBuilder();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!pw.contains(p.getUUID())) { MOTION.remove(p.getUUID()); continue; }
			if (ids.length() > 0) ids.append(',');
			ids.append(p.getId());
			if (!p.isAlive()) continue;
			if (tick % 20 == 0) effects(p, false);
			ServerLevel level = (ServerLevel) p.level();
			boolean space = level.dimension().equals(SpaceTravel.SPACE);
			if (!space) flight(p);
			motion(p, level, space);
			Dash d = DASH.get(p.getUUID());
			if (d != null) dash(server, p, level, space, d);
		}
		String s = ids.toString();
		if (!s.equals(lastSync) || tick % 200 == 0) {
			lastSync = s;
			ViltrumPayload pl = new ViltrumPayload(s);
			for (ServerPlayer p : server.getPlayerList().getPlayers()) ServerPlayNetworking.send(p, pl);
		}
	}

	public static void sync(ServerPlayer p) {
		ServerPlayNetworking.send(p, new ViltrumPayload(lastSync));
	}

	/** Полёт вне космоса: в космосе им управляет SpaceTravel (там свой разгон и гиперполёт) */
	private static void flight(ServerPlayer p) {
		SpaceConfig cfg = SpaceConfig.get();
		Abilities ab = p.getAbilities();
		boolean changed = false;
		if (!ab.mayfly) { ab.mayfly = true; changed = true; }
		double[] m = MOTION.computeIfAbsent(p.getUUID(), k -> new double[] { p.getX(), p.getY(), p.getZ(), 0, 0, 0, 0 });
		if (ab.flying && p.isSprinting() && m[4] > 0.5) m[6] = Math.min(1, m[6] + 0.02);
		else m[6] = Math.max(0, m[6] - 0.08);
		float speed = (float) (0.06 + (cfg.viltrumFlyEarth - 0.06) * m[6] * m[6]);
		if (Math.abs(ab.getFlyingSpeed() - speed) > 0.004F) { ab.setFlyingSpeed(speed); changed = true; }
		if (changed) p.onUpdateAbilities();
		if (ab.flying) p.resetFallDistance();
	}

	private static void motion(ServerPlayer p, ServerLevel level, boolean space) {
		double[] m = MOTION.computeIfAbsent(p.getUUID(), k -> new double[] { p.getX(), p.getY(), p.getZ(), 0, 0, 0, 0 });
		double dx = p.getX() - m[0], dy = p.getY() - m[1], dz = p.getZ() - m[2];
		m[0] = p.getX(); m[1] = p.getY(); m[2] = p.getZ();
		double sp = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (sp > 120) { m[3] = 0; m[4] = 0; return; }              // телепорт
		double prevVy = m[3], prevSp = m[4];
		m[3] = dy;
		m[4] = sp;
		// звуковой барьер
		if (sp > BOOM_SPEED && prevSp <= BOOM_SPEED && m[5] <= tick) {
			m[5] = tick + 60;
			boom(level, p.getX() - dx, p.getY() + 1 - dy, p.getZ() - dz, 1.6F);
		}
		// удар о землю
		if (!space && p.onGround() && prevVy < -1.1) slam(p, level, -prevVy);
	}

	private static void boom(ServerLevel level, double x, double y, double z, float vol) {
		level.playSound(null, x, y, z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, vol, 0.6F);
		level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, vol * 0.8F, 0.5F);
		level.sendParticles(ParticleTypes.SONIC_BOOM, x, y, z, 3, 0.6, 0.6, 0.6, 0);
		level.sendParticles(ParticleTypes.EXPLOSION, x, y, z, 6, 1.2, 1.2, 1.2, 0);
	}

	private static void slam(ServerPlayer p, ServerLevel level, double v) {
		SpaceConfig cfg = SpaceConfig.get();
		double r = Math.min(16, 4 + v * 2.6);
		double x = p.getX(), y = p.getY(), z = p.getZ();
		level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 3F, 0.55F);
		level.playSound(null, x, y, z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 2F, 0.5F);
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 1, 0, 0, 0, 0);
		for (int k = 0; k < 24; k++) {
			double a = k * Math.PI / 12;
			level.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, x + Math.cos(a) * r * 0.7, y + 0.2, z + Math.sin(a) * r * 0.7, 2, 0.4, 0.1, 0.4, 0.02);
			level.sendParticles(ParticleTypes.EXPLOSION, x + Math.cos(a) * r * 0.5, y + 0.4, z + Math.sin(a) * r * 0.5, 1, 0.2, 0.1, 0.2, 0);
		}
		for (Entity e : level.getEntities(p, p.getBoundingBox().inflate(r), e -> e instanceof LivingEntity && e.isAlive())) {
			double ex = e.getX() - x, ez = e.getZ() - z, d = Math.max(0.5, Math.sqrt(ex * ex + ez * ez));
			if (d > r) continue;
			double k = (1 - d / r) * 2.6 + 0.4;
			e.push(ex / d * k, 0.5 + k * 0.35, ez / d * k);
			if (e instanceof ServerPlayer sp) {
				SpaceTravel.syncMotion(sp);
				if (cfg.viltrumHurtPlayers) sp.hurtServer(level, level.damageSources().playerAttack(p), (float) (2 + v * 2));
			} else {
				e.hurtServer(level, level.damageSources().playerAttack(p), (float) (4 + v * 4));
			}
		}
		if (v > 2.5) p.sendOverlayMessage(Component.literal("УДАР  ·  " + Math.round(v * 20) + " бл/с").withStyle(s -> s.withColor(COLOR)));
	}

	/** Удар рукой: цель улетает (урон — обычный, с силой V) */
	public static void punch(ServerPlayer p, Entity target) {
		if (!has(p) || !(target instanceof LivingEntity)) return;
		ServerLevel level = (ServerLevel) p.level();
		Vec3 look = p.getLookAngle();
		double k = p.isSprinting() || p.getAbilities().flying ? 3.2 : 2.0;
		target.push(look.x * k, 0.45 + Math.max(0, look.y) * k, look.z * k);
		if (target instanceof ServerPlayer sp) SpaceTravel.syncMotion(sp);
		level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 0.9F, 1.4F);
		level.sendParticles(ParticleTypes.EXPLOSION, target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(), 1, 0, 0, 0, 0);
		level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(), 20, 0.4, 0.4, 0.4, 0.6);
	}

	// ───────── гиперполёт (только в космосе) ─────────

	private static final Map<UUID, double[]> HYPER = new HashMap<>();
	private static final double[] Q = new double[3];

	/**
	 * Вызывается из SpaceTravel каждый тик для вильтрумита в космосе. atCap — разгон на пределе и игрок бежит.
	 * Через ~1 с на пределе начинается гиперполёт: раз в 2 тика игрок переносится вперёд по взгляду, всё дальше
	 * (до viltrumHyperSystem / viltrumHyperDeep бл/тик). Перед Солнцем, планетами и объектами дальнего космоса шаг
	 * сам укорачивается, чтобы не проскочить. true — игрок перенесён в этом тике.
	 */
	public static boolean hyper(ServerPlayer p, ServerLevel level, boolean atCap, double fromSun, double t, double x, double y, double z) {
		double[] h = HYPER.computeIfAbsent(p.getUUID(), k -> new double[1]);
		if (!atCap || !p.getAbilities().flying) {
			if (h[0] > 0.3) {
				level.playSound(null, x, y, z, SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1F, 1.6F);
				h[0] = 0;
			}
			h[0] = Math.max(0, h[0] - 0.2);
			return false;
		}
		if (h[0] < 0.15 && h[0] + 0.012 >= 0.15) {
			boom(level, x, y, z, 2.5F);
			p.sendOverlayMessage(Component.literal("ГИПЕРПОЛЁТ").withStyle(s -> s.withColor(COLOR)));
		}
		h[0] = Math.min(1, h[0] + 0.012);
		if (h[0] < 0.15 || tick % 2 != 0) return false;
		SpaceConfig cfg = SpaceConfig.get();
		double max = fromSun > 27000 ? cfg.viltrumHyperDeep : cfg.viltrumHyperSystem;
		double step = 2 * max * h[0] * h[0];
		// не проскочить мимо: тормозим у тел
		double clear = fromSun - SolarSystem.SUN.size * 0.5 - 60;
		for (Body b : SolarSystem.BODIES) {
			b.pos(t, Q);
			double d = Math.sqrt(sq(Q[0] - x) + sq(Q[1] - y) + sq(Q[2] - z)) - b.halfMax() - 24;
			if (d < clear) clear = d;
		}
		for (Deep d : SolarSystem.DEEP) {
			double r = switch (d.type) {
				case "black_hole" -> d.pull * 0.25;
				case "binary" -> d.sep * 1.2;
				case "nebula" -> 0;
				default -> d.size * 0.8;
			};
			double dd = Math.sqrt(sq(d.x - x) + sq(d.y - y) + sq(d.z - z)) - r;
			if (dd < clear) clear = dd;
		}
		step = Math.min(step, Math.max(0, clear * 0.5));
		if (step < 6) return false;
		Vec3 look = p.getLookAngle();
		int min = level.getMinY() + 60, top = level.getMinY() + level.getHeight() - 60;
		double ny = Math.max(min, Math.min(top, p.getY() + look.y * step));
		p.teleportTo(level, p.getX() + look.x * step, ny, p.getZ() + look.z * step,
			Relative.union(Relative.ROTATION, Relative.DELTA), 0F, 0F, false);
		return true;
	}

	// ───────── рывок ─────────

	/** Рывок к телу/объекту: 2 секунды разгона, потом хлопок — и игрок на месте */
	public static Component go(ServerPlayer p, String id) {
		Deep d = SolarSystem.deep(id);
		Body b = id.equals("sun") ? SolarSystem.byId("mercury") : SolarSystem.byId(id);
		if (d == null && b == null) return Component.literal("Нет такой цели: " + id + ". Список — /space list").withStyle(s -> s.withColor(0xFF8A5C));
		String name = d != null ? d.name : id.equals("sun") ? "Солнце" : b.name;
		DASH.put(p.getUUID(), new Dash(id, name, d == null ? b : null, d, new int[] { 40 }));
		((ServerLevel) p.level()).playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1.2F, 0.6F);
		return Component.literal("Рывок: " + name).withStyle(s -> s.withColor(COLOR));
	}

	private static void dash(MinecraftServer server, ServerPlayer p, ServerLevel level, boolean space, Dash d) {
		int left = --d.left()[0];
		Vec3 look = p.getLookAngle();
		if (left > 0) {
			// разгон: в космосе — вперёд, в мирах — вверх
			Vec3 v = space ? look.scale(0.4 + (40 - left) * 0.06) : new Vec3(0, 0.6 + (40 - left) * 0.09, 0);
			p.setDeltaMovement(v);
			SpaceTravel.syncMotion(p);
			p.resetFallDistance();
			level.sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 0.9, p.getZ(), 6, 0.3, 0.6, 0.3, 0.08);
			if (left % 10 == 0) {
				level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1F, 0.6F + (40 - left) * 0.03F);
				p.sendOverlayMessage(Component.literal("Рывок к " + d.name() + "  " + "▮".repeat((40 - left) / 4) + "▯".repeat(left / 4)).withStyle(s -> s.withColor(COLOR)));
			}
			return;
		}
		DASH.remove(p.getUUID());
		boom(level, p.getX(), p.getY() + 1, p.getZ(), 3F);
		if (d.deep() != null) SpaceTravel.sendToDeep(server, p, d.deep());
		else SpaceTravel.sendToSpace(server, p, d.body());
		ServerLevel now = (ServerLevel) p.level();
		boom(now, p.getX(), p.getY() + 1, p.getZ(), 2F);
		now.sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1, p.getZ(), 60, 1, 1, 1, 0.4);
		p.setDeltaMovement(p.getLookAngle().scale(1.5));
		SpaceTravel.syncMotion(p);
		MOTION.remove(p.getUUID());
		p.sendSystemMessage(Component.literal("✦ " + d.name()).withStyle(s -> s.withColor(COLOR))
			.append(Component.literal(d.body() != null && d.body().dim != null ? "  ·  влетите в куб, чтобы попасть в его мир" : "").withStyle(ChatFormatting.GRAY)));
	}

	// ───────── чутьё ─────────

	/** Ближайшие неизведанные миры и объекты дальнего космоса, с кнопками рывка */
	public static Component scan(MinecraftServer server, ServerPlayer p) {
		Set<String> known = Atlas.of(p.getUUID());
		double t = server.overworld().getGameTime();
		double[] from = new double[3];
		if (p.level().dimension().equals(SpaceTravel.SPACE)) { from[0] = p.getX(); from[1] = p.getY(); from[2] = p.getZ(); }
		else SolarSystem.byId("earth").pos(t, from);
		record Target(String id, String name, int color, double dist, boolean deep) {}
		List<Target> list = new ArrayList<>();
		double[] q = new double[3];
		for (Body b : SolarSystem.BODIES) {
			if (b.dim == null || known.contains("v:" + b.id)) continue;
			b.pos(t, q);
			list.add(new Target(b.id, b.name, b.color, Math.sqrt(sq(q[0] - from[0]) + sq(q[1] - from[1]) + sq(q[2] - from[2])), false));
		}
		for (Deep d : SolarSystem.DEEP) {
			if (known.contains("d:" + d.id)) continue;
			list.add(new Target(d.id, d.name, d.color, Math.sqrt(sq(d.x - from[0]) + sq(d.y - from[1]) + sq(d.z - from[2])), true));
		}
		list.sort(Comparator.comparingDouble(Target::dist));
		MutableComponent m = Component.literal("━━━━  ЧУТЬЁ ВИЛЬТРУМИТА  ━━━━").withStyle(s -> s.withColor(COLOR));
		if (list.isEmpty()) return m.append(Component.literal("\nВсё изведано.").withStyle(ChatFormatting.GRAY));
		m.append(Component.literal("\nНеизведано: " + list.size() + ". Ближайшие (нажмите — рывок):").withStyle(ChatFormatting.GRAY));
		for (int i = 0; i < Math.min(8, list.size()); i++) {
			Target tg = list.get(i);
			String cmd = "/space viltrum go " + tg.id();
			m.append(Component.literal("\n  ▸ ").withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.literal(tg.name()).withStyle(s -> s.withColor(tg.color())
					.withClickEvent(new ClickEvent.RunCommand(cmd))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal(cmd)))))
				.append(Component.literal("  " + fmtDist(tg.dist()) + (tg.deep() ? "  · дальний космос" : "")).withStyle(ChatFormatting.GRAY));
		}
		return m;
	}

	private static double sq(double v) { return v * v; }

	private static String fmtDist(double d) {
		return d >= 10000 ? String.format("%.1f тыс. бл", d / 1000) : Math.round(d) + " бл";
	}

	public static Component status(MinecraftServer server) {
		MutableComponent m = Component.literal("Сила вильтрумита: ").withStyle(s -> s.withColor(COLOR));
		List<String> names = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) if (has(p)) names.add(p.getGameProfile().name());
		m.append(Component.literal(names.isEmpty() ? "ни у кого в сети" : String.join(", ", names)).withStyle(ChatFormatting.WHITE));
		m.append(Component.literal("\n/space viltrum on|off [игроки] · go <тело> · scan").withStyle(ChatFormatting.GRAY));
		return m;
	}
}
