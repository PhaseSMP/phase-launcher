package gg.phase.space;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Вселенная Phase Space: тела, орбиты, кометы и объекты дальнего космоса из {@code /phasespace/bodies.json}.
 *
 * Всё считается от игрового времени мира (getGameTime), поэтому сервер (порталы, притяжение) и клиент
 * (отрисовка) видят одно и то же без пакетов синхронизации. Плоскость системы — Y = 0, Солнце в (0, 0, 0).
 * Мир космоса по высоте от −2032 до 2032: орбиты наклонены по-настоящему (Плутон, Эрида уходят далеко
 * вверх и вниз), а спуск на 2000 блоков ниже плоскости возвращает игрока на Землю.
 */
public final class SolarSystem {
	public static final double PLANE_Y = 0.0;
	public static final double TAU = Math.PI * 2.0;
	/** Год Земли (тики) и её орбита — по ним считаются годы планет без period (третий закон Кеплера) */
	public static final double EARTH_YEAR = 144000, EARTH_ORBIT = 3000;

	public static final Body SUN;
	public static final List<Body> BODIES;          // планеты и спутники, родитель всегда раньше ребёнка
	public static final List<Comet> COMETS;         // все кометы: именные и мелкие
	public static final int NAMED_COMETS;
	public static final List<Deep> DEEP;
	public static final List<Constellation> CONSTELLATIONS;
	public static final double BELT_INNER, BELT_OUTER, KUIPER_INNER, KUIPER_OUTER;
	public static final int BELT_DECOR, KUIPER_DECOR;
	public static final Body TORII_PARENT;
	public static final double TORII_ORBIT, TORII_PERIOD, TORII_SIZE;
	private static final Map<String, Body> BY_ID = new HashMap<>();
	private static final Map<String, Body> BY_DIM = new HashMap<>();
	private static final Map<String, Deep> DEEP_BY_ID = new HashMap<>();
	private static final Map<String, Constellation> CONST_BY_ID = new HashMap<>();

	/** Планета, спутник или Солнце. Все величины — блоки, тики, градусы (в JSON). */
	public static final class Body {
		public final String id, name, style, dim, ring;
		public final Body parent;
		public final double orbit, period, phase, yAmp, node, size, spin, tilt, gravity;
		public final double atmoScale, ringInner, ringOuter;
		public final double sx, sy, sz;                // форма (Хаумеа вытянута, Фобос — картофелина)
		public final int color, tex, aurora;
		public final boolean atmo, clouds, lights, spores, petals, geysers;
		public final List<Body> moons = new ArrayList<>();
		/** Радиус «системы»: планета + орбиты спутников + кольцо */
		public double systemRadius;
		public int index;

		Body(JsonObject o, Body parent) {
			this.id = str(o, "id", "?");
			this.name = str(o, "name", id);
			this.style = str(o, "style", id);
			this.parent = parent;
			this.orbit = num(o, "orbit", 0);
			double kepler = EARTH_YEAR * Math.pow(Math.max(1, orbit) / EARTH_ORBIT, 1.5);
			this.period = num(o, "period", kepler);
			this.phase = Math.toRadians(num(o, "phase", 0));
			this.yAmp = num(o, "yAmp", 0);
			this.node = Math.toRadians(num(o, "node", 0));
			this.size = num(o, "size", 10);
			this.spin = num(o, "spin", 24000);
			this.tilt = num(o, "tilt", 0);
			this.gravity = num(o, "gravity", 1);
			this.color = rgb(str(o, "color", "#ffffff"));
			this.tex = (int) num(o, "tex", 16);
			this.atmo = o.has("atmo");
			this.atmoScale = num(o, "atmoScale", 1.08);
			this.clouds = bool(o, "clouds");
			this.lights = bool(o, "lights");
			this.spores = bool(o, "spores");
			this.petals = bool(o, "petals");
			this.geysers = bool(o, "geysers");
			this.aurora = o.has("aurora") ? rgb(o.get("aurora").getAsString()) : -1;
			this.ring = o.has("ring") && !o.get("ring").isJsonNull() ? o.get("ring").getAsString() : null;
			this.ringInner = num(o, "ringInner", 0.7);
			this.ringOuter = num(o, "ringOuter", 1.2);
			JsonArray sh = o.has("shape") ? o.getAsJsonArray("shape") : null;
			this.sx = sh == null ? 1 : sh.get(0).getAsDouble();
			this.sy = sh == null ? 1 : sh.get(1).getAsDouble();
			this.sz = sh == null ? 1 : sh.get(2).getAsDouble();
			boolean portal = !o.has("portal") || o.get("portal").getAsBoolean();
			// у планет и спутников свой мир, Земля ведёт в обычный мир
			this.dim = o.has("dim") ? o.get("dim").getAsString() : (!portal || id.equals("sun") ? null : PhaseSpace.MOD_ID + ":" + id);
		}

		public boolean isPlanet() { return parent == null; }

		/** Наибольший полуразмер с учётом формы */
		public double halfMax() { return size * 0.5 * Math.max(sx, Math.max(sy, sz)); }

		/** Центр тела в момент t (тики, можно дробные). out = {x, y, z} */
		public void pos(double t, double[] out) {
			if (this == SUN) { out[0] = 0; out[1] = PLANE_Y; out[2] = 0; return; }
			double a = phase + TAU * frac(t / period);
			double x = Math.cos(a) * orbit, z = Math.sin(a) * orbit;
			double y = yAmp * Math.sin(a - node);
			if (parent != null) {
				parent.pos(t, out);
				out[0] += x; out[1] += y; out[2] += z;
			} else {
				out[0] = x; out[1] = PLANE_Y + y; out[2] = z;
			}
		}

		/** Угол собственного вращения, градусы */
		public double spinDeg(double t) { return 360.0 * frac(t / spin); }
	}

	/** Комета на вытянутой орбите (уравнение Кеплера). Портала нет — красота и навигация. */
	public static final class Comet {
		public final String id, name;
		public final double q, bigQ, period, phase, omega, yAmp, size, tail;
		public final boolean minor;
		final double a, e, b;

		Comet(String id, String name, double q, double bigQ, double period, double phase, double omegaDeg, double yAmp, double size, double tail, boolean minor) {
			this.id = id; this.name = name; this.q = q; this.bigQ = bigQ; this.period = period; this.phase = phase;
			this.omega = Math.toRadians(omegaDeg); this.yAmp = yAmp; this.size = size; this.tail = tail; this.minor = minor;
			a = (q + bigQ) / 2;
			e = (bigQ - q) / (bigQ + q);
			b = a * Math.sqrt(1 - e * e);
		}

		public void pos(double t, double[] out) {
			double m = TAU * frac(t / period + phase);
			double ecc = m + e * Math.sin(m);
			for (int i = 0; i < 12; i++) {
				double f = ecc - e * Math.sin(ecc) - m;
				ecc -= f / (1 - e * Math.cos(ecc));
			}
			double x = a * (Math.cos(ecc) - e), z = b * Math.sin(ecc);
			double c = Math.cos(omega), s = Math.sin(omega);
			out[0] = x * c - z * s;
			out[2] = x * s + z * c;
			out[1] = PLANE_Y + yAmp * Math.sin(ecc);
		}
	}

	/** Объект дальнего космоса: чёрная дыра, квазар, пульсар, двойная звезда, туманность. */
	public static final class Deep {
		public final String id, type, name;
		public final double x, y, z, size, disk, pull, jet, beam, period, sep;
		public final int color, color2;

		Deep(JsonObject o) {
			id = str(o, "id", "?");
			type = str(o, "type", "?");
			name = str(o, "name", id);
			JsonArray p = o.getAsJsonArray("pos");
			x = p.get(0).getAsDouble(); y = p.get(1).getAsDouble(); z = p.get(2).getAsDouble();
			size = num(o, "size", 100);
			disk = num(o, "disk", 3);
			pull = num(o, "pull", 0);
			jet = num(o, "jet", 0);
			beam = num(o, "beam", 0);
			period = num(o, "period", 20);
			sep = num(o, "sep", 0);
			color = rgb(str(o, "color", "#ffffff"));
			color2 = rgb(str(o, "color2", "#ffffff"));
		}

		public double dist(double px, double py, double pz) {
			double dx = px - x, dy = py - y, dz = pz - z;
			return Math.sqrt(dx * dx + dy * dy + dz * dz);
		}
	}

	/**
	 * Созвездие. Звёзды заданы на «небесной сфере» мода: alpha — угол вдоль суточного вращения неба (как у Солнца),
	 * delta — отклонение к северу/югу. Над Землёй сфера крутится вместе с ванильными звёздами (STAR_ANGLE),
	 * в космосе стоит неподвижно — созвездия те же самые.
	 */
	public static final class Constellation {
		public final String id, name, lore;
		public final int color;
		public final double[][] stars;     // направления звёзд на неповёрнутой сфере (единичные векторы)
		public final double[] center;
		public final int[][] lines;

		Constellation(JsonObject o) {
			id = str(o, "id", "?");
			name = str(o, "name", id);
			lore = str(o, "lore", "");
			color = rgb(str(o, "color", "#ffffff"));
			double a0 = num(o, "alpha", 0), d0 = num(o, "delta", 0);
			JsonArray st = o.getAsJsonArray("stars");
			stars = new double[st.size()][];
			double cx = 0, cy = 0, cz = 0;
			for (int i = 0; i < st.size(); i++) {
				JsonArray p = st.get(i).getAsJsonArray();
				double d = d0 + p.get(1).getAsDouble();
				double a = a0 + p.get(0).getAsDouble() / Math.max(0.3, Math.cos(Math.toRadians(d0)));
				stars[i] = sphere(a, d);
				cx += stars[i][0]; cy += stars[i][1]; cz += stars[i][2];
			}
			double l = Math.sqrt(cx * cx + cy * cy + cz * cz);
			center = new double[] { cx / l, cy / l, cz / l };
			JsonArray ln = o.getAsJsonArray("lines");
			lines = new int[ln.size()][2];
			for (int i = 0; i < ln.size(); i++) {
				lines[i][0] = ln.get(i).getAsJsonArray().get(0).getAsInt();
				lines[i][1] = ln.get(i).getAsJsonArray().get(1).getAsInt();
			}
		}
	}

	/** Точка небесной сферы по углам (градусы): при alpha 0 и delta 0 — зенит в полдень */
	public static double[] sphere(double alphaDeg, double deltaDeg) {
		double a = Math.toRadians(alphaDeg), d = Math.toRadians(deltaDeg);
		return new double[] { Math.sin(d), Math.cos(d) * Math.cos(a), Math.cos(d) * Math.sin(a) };
	}

	/**
	 * Повернуть точку сферы так же, как игра поворачивает звёзды: Ry(−90°)·Rx(angle).
	 * angle — радианы (STAR_ANGLE или SUN_ANGLE из атрибутов окружения); в космосе — 0.
	 */
	public static void skyDir(double[] v, double angle, double[] out) {
		double c = Math.cos(angle), s = Math.sin(angle);
		double x = v[0], y = v[1] * c - v[2] * s, z = v[1] * s + v[2] * c;
		out[0] = -z;
		out[1] = y;
		out[2] = x;
	}

	/**
	 * Где на небе Земли видна планета: элонгация от Солнца вдоль эклиптики (путь Солнца) и небольшая широта.
	 * Возвращает точку на неповёрнутой сфере (дальше — skyDir с углом Солнца). null, если тело — сама Земля.
	 */
	public static double[] seenFromEarth(double[] earth, double[] target) {
		double sx = -earth[0], sz = -earth[2];
		double px = target[0] - earth[0], pz = target[2] - earth[2], py = target[1] - earth[1];
		double dist = Math.sqrt(px * px + pz * pz);
		if (dist < 1) return null;
		double elong = Math.atan2(sx * pz - sz * px, sx * px + sz * pz);
		double lat = Math.atan2(py, dist);
		return sphere(Math.toDegrees(elong), Math.toDegrees(lat) * 3 + 4);
	}

	static {
		JsonObject root;
		try (InputStream in = SolarSystem.class.getResourceAsStream("/phasespace/bodies.json")) {
			if (in == null) throw new IllegalStateException("phasespace/bodies.json missing from the jar");
			root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception ex) {
			throw new RuntimeException("Phase Space: cannot read bodies.json", ex);
		}
		SUN = new Body(root.getAsJsonObject("sun"), null);
		List<Body> list = new ArrayList<>();
		for (JsonElement el : root.getAsJsonArray("bodies")) {
			JsonObject o = el.getAsJsonObject();
			Body parent = o.has("parent") ? BY_ID.get(o.get("parent").getAsString()) : null;
			Body b = new Body(o, parent);
			b.index = list.size();
			if (parent != null) parent.moons.add(b);
			list.add(b);
			BY_ID.put(b.id, b);
			if (b.dim != null) BY_DIM.put(b.dim, b);
		}
		for (Body b : list) {
			double r = b.halfMax() * Math.sqrt(3) * (b.atmo ? b.atmoScale : 1);
			if (b.ring != null) r = Math.max(r, b.size * b.ringOuter * Math.sqrt(2));
			for (Body m : b.moons) r = Math.max(r, m.orbit + Math.abs(m.yAmp) + m.size);
			b.systemRadius = r;
		}
		BODIES = Collections.unmodifiableList(list);

		List<Comet> comets = new ArrayList<>();
		for (JsonElement el : root.getAsJsonArray("comets")) {
			JsonObject o = el.getAsJsonObject();
			comets.add(new Comet(str(o, "id", "?"), str(o, "name", "?"), num(o, "q", 600), num(o, "Q", 12000), num(o, "period", 200000),
				num(o, "phase", 0), num(o, "omega", 0), num(o, "yAmp", 500), num(o, "size", 4), num(o, "tail", 1), false));
		}
		NAMED_COMETS = comets.size();
		// мелкие безымянные кометы: всегда одни и те же (фиксированное зерно), чтобы сервер и клиент совпадали
		JsonObject mc = root.getAsJsonObject("minorComets");
		Random rnd = new Random((long) num(mc, "seed", 618));
		int n = (int) num(mc, "count", 30);
		for (int i = 0; i < n; i++) {
			double q = 500 + rnd.nextDouble() * 6000;
			double bigQ = q + 6000 + rnd.nextDouble() * 26000;
			double a = (q + bigQ) / 2;
			double period = EARTH_YEAR * Math.pow(a / EARTH_ORBIT, 1.5) * (0.35 + rnd.nextDouble() * 0.3); // ускорены, чтобы чаще пролетали
			String code = String.format("C/%d-%c%d", 2026 + i / 12, (char) ('A' + (i % 12)), 1 + i % 7);
			comets.add(new Comet("minor_" + i, "Комета " + code, q, bigQ, period, rnd.nextDouble(), rnd.nextDouble() * 360,
				(rnd.nextDouble() * 2 - 1) * 1800, 1.5 + rnd.nextDouble() * 3, 0.35 + rnd.nextDouble() * 0.6, true));
		}
		COMETS = Collections.unmodifiableList(comets);

		List<Deep> deep = new ArrayList<>();
		for (JsonElement el : root.getAsJsonArray("deep")) {
			Deep d = new Deep(el.getAsJsonObject());
			deep.add(d);
			DEEP_BY_ID.put(d.id, d);
		}
		DEEP = Collections.unmodifiableList(deep);
		List<Constellation> cons = new ArrayList<>();
		if (root.has("constellations")) {
			for (JsonElement el : root.getAsJsonArray("constellations")) {
				Constellation c = new Constellation(el.getAsJsonObject());
				cons.add(c);
				CONST_BY_ID.put(c.id, c);
			}
		}
		CONSTELLATIONS = Collections.unmodifiableList(cons);

		JsonObject belt = root.getAsJsonObject("belt"), kuiper = root.getAsJsonObject("kuiper"), torii = root.getAsJsonObject("torii");
		BELT_INNER = num(belt, "inner", 5400);
		BELT_OUTER = num(belt, "outer", 6600);
		BELT_DECOR = (int) num(belt, "decor", 1000);
		KUIPER_INNER = num(kuiper, "inner", 19500);
		KUIPER_OUTER = num(kuiper, "outer", 25000);
		KUIPER_DECOR = (int) num(kuiper, "decor", 400);
		TORII_PARENT = BY_ID.get(str(torii, "parent", "earth"));
		TORII_ORBIT = num(torii, "orbit", 80);
		TORII_PERIOD = num(torii, "period", 7200);
		TORII_SIZE = num(torii, "size", 12);
	}

	private SolarSystem() {}

	public static Body byId(String id) { return BY_ID.get(id); }

	public static Deep deep(String id) { return DEEP_BY_ID.get(id); }

	public static Constellation constellation(String id) { return CONST_BY_ID.get(id); }

	/** Тело, в мир которого ведёт измерение (например phasespace:mars), или null */
	public static Body byDimension(String dimId) { return BY_DIM.get(dimId); }

	/** Попадает ли радиус r (от Солнца) на орбиту какой-нибудь планетной системы — там астероидов нет */
	public static boolean nearOrbit(double r, double margin) {
		for (Body b : BODIES) {
			if (b.parent != null) continue;
			if (Math.abs(r - b.orbit) < b.systemRadius + margin) return true;
		}
		return false;
	}

	public static double frac(double v) { return v - Math.floor(v); }

	static int rgb(String hex) {
		String h = hex.startsWith("#") ? hex.substring(1) : hex;
		return Integer.parseInt(h, 16) & 0xFFFFFF;
	}

	private static String str(JsonObject o, String k, String def) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def;
	}

	private static double num(JsonObject o, String k, double def) {
		return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : def;
	}

	private static boolean bool(JsonObject o, String k) {
		return o.has(k) && o.get(k).getAsBoolean();
	}
}
