package gg.phase.space;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import gg.phase.space.SolarSystem.Body;
import gg.phase.space.SolarSystem.Constellation;
import gg.phase.space.SolarSystem.Deep;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Звёздный атлас игрока: изученные созвездия, замеченные в телескоп планеты, посещённые миры и объекты
 * дальнего космоса. Хранится в config/phasespace_atlas.json, клиенту уходит пакетом AtlasPayload.
 *
 * <p><b>Изучение созвездий.</b> Ночью под открытым небом (или в космосе в любое время) смотреть на созвездие
 * в подзорную трубу ~8 секунд. Над хотбаром идёт шкала, на клиенте проступают линии созвездия.
 * Небо над Землёй медленно сдвигается: за 12 игровых дней по кругу проходят все 12 созвездий,
 * у каждого «дня-месяца» своё созвездие сезона. Награда — опыт; все 12 — звание «Звездочёт».
 *
 * <p><b>Телескоп.</b> Подзорная труба, наведённая на планету в небе Земли, называет её и расстояние,
 * первое наблюдение заносится в атлас. Влёт в мир планеты и полёт к объекту дальнего космоса — тоже.
 */
public final class Atlas {
	public static final double YEAR = 24000.0 * 12;            // «год» неба: 12 игровых дней
	private static final int STUDY_TICKS = 160;
	private static final double AIM = Math.toRadians(8);
	private static Map<UUID, Set<String>> data;
	private static final Map<UUID, double[]> STUDY = new HashMap<>(); // {индекс созвездия, прогресс}
	private static final double[] V = new double[3], E = new double[3], T = new double[3];
	private static long tick;

	private Atlas() {}

	// ───────── хранение ─────────

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("phasespace_atlas.json");
	}

	private static Map<UUID, Set<String>> data() {
		if (data != null) return data;
		data = new HashMap<>();
		try {
			if (Files.exists(file())) {
				try (Reader r = Files.newBufferedReader(file(), StandardCharsets.UTF_8)) {
					Map<String, Set<String>> raw = new Gson().fromJson(r, new TypeToken<Map<String, LinkedHashSet<String>>>() {}.getType());
					if (raw != null) raw.forEach((k, v) -> data.put(UUID.fromString(k), new LinkedHashSet<>(v)));
				}
			}
		} catch (Exception e) {
			PhaseSpace.LOG.warn("Bad atlas file: {}", e.toString());
		}
		return data;
	}

	private static void save() {
		Map<String, Set<String>> raw = new HashMap<>();
		data().forEach((k, v) -> raw.put(k.toString(), v));
		try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
			new GsonBuilder().setPrettyPrinting().create().toJson(raw, w);
		} catch (Exception e) {
			PhaseSpace.LOG.warn("Cannot write atlas: {}", e.toString());
		}
	}

	public static Set<String> of(UUID id) {
		return data().computeIfAbsent(id, k -> new LinkedHashSet<>());
	}

	public static void sync(ServerPlayer p) {
		try {
			ServerPlayNetworking.send(p, new AtlasPayload(String.join(",", of(p.getUUID()))));
		} catch (Throwable t) {
			// у игрока может не быть мода (например, бот) — это не ошибка
		}
	}

	/** Записать открытие. true — новое */
	public static boolean discover(ServerPlayer p, String key, Component title, Component lore, int xp) {
		Set<String> s = of(p.getUUID());
		if (!s.add(key)) return false;
		save();
		sync(p);
		MutableComponent m = Component.literal("✦ ").withStyle(s2 -> s2.withColor(0xFFE7B0)).append(title);
		if (lore != null) m.append(Component.literal("\n  ").append(lore));
		p.sendSystemMessage(m);
		if (xp > 0) p.giveExperiencePoints(xp);
		((ServerLevel) p.level()).playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.2F, 0.8F);
		return true;
	}

	// ───────── каждый тик ─────────

	public static void tick(MinecraftServer server) {
		tick++;
		if (tick % 2 != 0) return;
		double t = server.overworld().getGameTime();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!p.isAlive() || p.isSpectator()) continue;
			boolean space = p.level().dimension().equals(SpaceTravel.SPACE);
			boolean earth = p.level().dimension().equals(Level.OVERWORLD);
			if (!space && !earth) {
				STUDY.remove(p.getUUID());
				continue;
			}
			if (space && tick % 20 == 0) deepVisits(p);
			if (!p.isScoping()) {
				STUDY.remove(p.getUUID());
				continue;
			}
			double angle = 0;
			if (earth) {
				ServerLevel level = (ServerLevel) p.level();
				BlockPos head = BlockPos.containing(p.getX(), p.getEyeY(), p.getZ());
				float stars = level.environmentAttributes().getValue(EnvironmentAttributes.STAR_BRIGHTNESS, head);
				angle = Math.toRadians(level.environmentAttributes().getValue(EnvironmentAttributes.STAR_ANGLE, head));
				if (!level.canSeeSky(head.above())) { STUDY.remove(p.getUUID()); continue; }
				if (telescopePlanets(p, level, head, t)) continue;
				if (stars < 0.2F) {
					if (tick % 20 == 0) p.sendOverlayMessage(Component.literal("Звёзд не видно — дождитесь ночи").withStyle(ChatFormatting.GRAY));
					STUDY.remove(p.getUUID());
					continue;
				}
			}
			study(p, angle, earth ? seasonShift(t) : 0);
		}
	}

	/** Сдвиг созвездий за «год» неба (радианы): каждые сутки видно немного другое небо */
	public static double seasonShift(double t) {
		return -SolarSystem.TAU * SolarSystem.frac(t / YEAR);
	}

	/** Номер «месяца» (0–11) и созвездие сезона — то, что в полночь стоит выше всех */
	public static Constellation seasonal(double t) {
		Constellation best = null;
		double bestY = -2;
		for (Constellation c : SolarSystem.CONSTELLATIONS) {
			SolarSystem.skyDir(c.center, Math.PI + seasonShift(t), V);
			if (V[1] > bestY) { bestY = V[1]; best = c; }
		}
		return best;
	}

	private static void study(ServerPlayer p, double angle, double shift) {
		Vec3 look = p.getLookAngle();
		int bestI = -1;
		double best = AIM;
		for (int i = 0; i < SolarSystem.CONSTELLATIONS.size(); i++) {
			SolarSystem.skyDir(SolarSystem.CONSTELLATIONS.get(i).center, angle + shift, V);
			if (V[1] < 0.05 && angle != 0) continue;                       // под горизонтом
			double a = Math.acos(Math.max(-1, Math.min(1, V[0] * look.x + V[1] * look.y + V[2] * look.z)));
			if (a < best) { best = a; bestI = i; }
		}
		if (bestI < 0) {
			STUDY.remove(p.getUUID());
			if (tick % 20 == 0) p.sendOverlayMessage(Component.literal("Наведите трубу на созвездие").withStyle(ChatFormatting.DARK_GRAY));
			return;
		}
		Constellation c = SolarSystem.CONSTELLATIONS.get(bestI);
		boolean known = of(p.getUUID()).contains("c:" + c.id);
		double[] st = STUDY.get(p.getUUID());
		if (st == null || st[0] != bestI) st = new double[] { bestI, 0 };
		STUDY.put(p.getUUID(), st);
		if (known) {
			if (tick % 10 == 0) p.sendOverlayMessage(Component.literal("✦ " + c.name).withStyle(s -> s.withColor(c.color))
				.append(Component.literal("  ·  " + count(p) + "/" + SolarSystem.CONSTELLATIONS.size() + " изучено").withStyle(ChatFormatting.GRAY)));
			return;
		}
		st[1] += 2;
		int bars = (int) Math.min(10, Math.round(10 * st[1] / STUDY_TICKS));
		if (tick % 6 == 0) {
			p.sendOverlayMessage(Component.literal("Изучение созвездия  " + "▮".repeat(bars) + "▯".repeat(10 - bars)).withStyle(s -> s.withColor(c.color)));
		}
		if (st[1] >= STUDY_TICKS) {
			STUDY.remove(p.getUUID());
			discover(p, "c:" + c.id, Component.literal("Созвездие «" + c.name + "»").withStyle(s -> s.withColor(c.color)),
				Component.literal(c.lore).withStyle(ChatFormatting.GRAY), 120);
			int n = count(p);
			if (n == SolarSystem.CONSTELLATIONS.size()) {
				p.giveExperienceLevels(10);
				Component all = Component.literal("✦ " + p.getGameProfile().name() + " изучил все " + n + " созвездий и стал Звездочётом").withStyle(s -> s.withColor(0xFFE7B0));
				for (ServerPlayer o : ((ServerLevel) p.level()).getServer().getPlayerList().getPlayers()) o.sendSystemMessage(all);
			}
		}
	}

	private static int count(ServerPlayer p) {
		int n = 0;
		for (String k : of(p.getUUID())) if (k.startsWith("c:")) n++;
		return n;
	}

	/** Подзорная труба в небе Земли: планеты называются по имени, первое наблюдение — в атлас */
	private static boolean telescopePlanets(ServerPlayer p, ServerLevel level, BlockPos head, double t) {
		double sun = Math.toRadians(level.environmentAttributes().getValue(EnvironmentAttributes.SUN_ANGLE, head));
		Body earth = SolarSystem.byId("earth");
		earth.pos(t, E);
		Vec3 look = p.getLookAngle();
		Body best = null;
		double bestA = Math.toRadians(3);
		for (Body b : EarthSky.VISIBLE) {
			b.pos(t, T);
			double[] base = SolarSystem.seenFromEarth(E, T);
			if (base == null) continue;
			SolarSystem.skyDir(base, sun, V);
			if (V[1] < 0.02) continue;
			double a = Math.acos(Math.max(-1, Math.min(1, V[0] * look.x + V[1] * look.y + V[2] * look.z)));
			if (a < bestA) { bestA = a; best = b; }
		}
		if (best == null) return false;
		best.pos(t, T);
		double dx = T[0] - E[0], dy = T[1] - E[1], dz = T[2] - E[2];
		double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
		final Body b = best;
		if (tick % 10 == 0) {
			p.sendOverlayMessage(Component.literal("◆ " + b.name).withStyle(s -> s.withColor(b.color))
				.append(Component.literal(String.format("  ·  планета, %.1f тыс бл от Земли", dist / 1000).replace('.', ',')).withStyle(ChatFormatting.GRAY)));
		}
		discover(p, "o:" + b.id, Component.literal("Наблюдение: " + b.name).withStyle(s -> s.withColor(b.color)),
			Component.literal(b.id.equals("florana") ? "Фиолетовая блуждающая звезда — её нет ни в одном старом атласе." : "Теперь она есть в вашем атласе. Долететь — выше Y 2000.").withStyle(ChatFormatting.GRAY), 40);
		return true;
	}

	/** Влёт в мир тела (вызывает SpaceTravel) */
	public static void visited(ServerPlayer p, Body b) {
		discover(p, "v:" + b.id, Component.literal("Новый мир: " + b.name).withStyle(s -> s.withColor(b.color)),
			Component.literal("Посещено миров: " + (visitedCount(p) + 1) + " из " + SolarSystem.BODIES.stream().filter(x -> x.dim != null).count()).withStyle(ChatFormatting.GRAY), 60);
	}

	private static int visitedCount(ServerPlayer p) {
		int n = 0;
		for (String k : of(p.getUUID())) if (k.startsWith("v:")) n++;
		return n;
	}

	private static void deepVisits(ServerPlayer p) {
		for (Deep d : SolarSystem.DEEP) {
			double r = switch (d.type) {
				case "nebula" -> d.size * 0.6;
				case "quasar" -> d.size * 12;
				case "black_hole" -> d.pull;
				default -> d.size * 20 + 3000;
			};
			if (d.dist(p.getX(), p.getY(), p.getZ()) < r) {
				discover(p, "d:" + d.id, Component.literal("Дальний космос: " + d.name).withStyle(s -> s.withColor(d.color)), null, 200);
			}
		}
	}

	/** Текст для /space atlas */
	public static Component report(ServerPlayer p) {
		Set<String> s = of(p.getUUID());
		MutableComponent m = Component.literal("━━━━  ЗВЁЗДНЫЙ АТЛАС  ━━━━").withStyle(st -> st.withColor(0xFFE7B0));
		m.append(Component.literal("\nСозвездия " + count(p) + "/" + SolarSystem.CONSTELLATIONS.size() + ": ").withStyle(ChatFormatting.GRAY));
		for (Constellation c : SolarSystem.CONSTELLATIONS) {
			boolean k = s.contains("c:" + c.id);
			int col = k ? c.color : 0x555566;
			m.append(Component.literal((k ? c.name : "???") + "  ").withStyle(st -> st.withColor(col)));
		}
		m.append(Component.literal("\nВ телескоп: ").withStyle(ChatFormatting.GRAY));
		for (Body b : EarthSky.VISIBLE) {
			boolean k = s.contains("o:" + b.id);
			int col = k ? b.color : 0x555566;
			m.append(Component.literal((k ? b.name : "???") + "  ").withStyle(st -> st.withColor(col)));
		}
		m.append(Component.literal("\nМиры: " + visitedCount(p) + "/" + SolarSystem.BODIES.stream().filter(x -> x.dim != null).count() + "  ").withStyle(ChatFormatting.GRAY));
		for (Body b : SolarSystem.BODIES) if (s.contains("v:" + b.id)) m.append(Component.literal(b.name + "  ").withStyle(st -> st.withColor(b.color)));
		m.append(Component.literal("\nДальний космос: ").withStyle(ChatFormatting.GRAY));
		for (Deep d : SolarSystem.DEEP) {
			boolean k = s.contains("d:" + d.id);
			int col = k ? d.color : 0x555566;
			m.append(Component.literal((k ? d.name : "???") + "  ").withStyle(st -> st.withColor(col)));
		}
		return m;
	}
}
