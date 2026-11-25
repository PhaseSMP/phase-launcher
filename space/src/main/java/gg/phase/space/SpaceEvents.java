package gg.phase.space;

/**
 * События космоса. Только в мире космоса — на обычный мир не влияют.
 *
 * Расписание детерминированное: время делится на окна по 15 минут, в каждом окне событие либо есть, либо нет,
 * а тип, начало и параметры берутся из хеша номера окна. Сервер и клиент считают одно и то же по игровому
 * времени, поэтому метеоры, вспышка и затмение у всех игроков происходят одновременно без пакетов.
 *
 * <ul>
 *   <li>Метеорный поток — сотни метеоров из одной точки неба, рядом с игроками падают настоящие метеориты
 *       (магма, железо, золото, иногда древние обломки и алмазы).</li>
 *   <li>Солнечная вспышка — Солнце разгорается, выбрасывает корональную волну, полярные сияния на Земле,
 *       Юпитере, Сатурне и Флоране; ближе 6000 блоков к Солнцу толкает солнечным ветром и обжигает.</li>
 *   <li>Затмение Фазы — Солнце закрывает чёрный диск, остаётся корона и «бриллиантовое кольцо»; вся система темнеет.</li>
 *   <li>Великая комета — яркая комета по параболе проходит у самого Солнца, хвост через полнеба.</li>
 *   <li>Блуждающая планета — тёмный мир с лавовыми трещинами пролетает сквозь систему по прямой.</li>
 *   <li>Гамма-всплеск TON 618 — квазар вспыхивает, джеты разгораются, всё небо на миг белеет.</li>
 * </ul>
 */
public final class SpaceEvents {
	public static final long SLOT = 18000;              // 15 минут
	private static final long SEED = 0x50484153455350L; // "PHASESP"

	public enum Type {
		METEOR_SHOWER("Метеорный поток", 3600, 30, 0xFFD9A0),
		SOLAR_FLARE("Солнечная вспышка", 1800, 20, 0xFFB347),
		ECLIPSE("Затмение Фазы", 2400, 14, 0xFFE7B0),
		GREAT_COMET("Великая комета", 16000, 16, 0xCFF4FF),
		ROGUE_PLANET("Блуждающая планета", 16000, 10, 0xFF6A3D),
		GAMMA_BURST("Гамма-всплеск TON 618", 400, 10, 0xBFE0FF);

		public final String title;
		public final int duration, weight, color;

		Type(String title, int duration, int weight, int color) {
			this.title = title; this.duration = duration; this.weight = weight; this.color = color;
		}
	}

	/** Событие в окне: тип, тики начала и конца, номер окна */
	public record Event(Type type, long start, long end, long slot) {
		public double progress(double t) { return Math.max(0, Math.min(1, (t - start) / (double) (end - start))); }

		/** Плавное появление и затухание (0…1) */
		public double fade(double t, double edge) {
			double p = progress(t);
			return Math.min(1, Math.min(p / edge, (1 - p) / edge));
		}

		public double rnd(int k) { return SpaceEvents.rnd(slot, k); }
	}

	private SpaceEvents() {}

	/** Событие, идущее в момент t, или null */
	public static Event at(double t) {
		Event e = inSlot((long) Math.floor(t / SLOT));
		return e != null && t >= e.start && t < e.end ? e : null;
	}

	/** Ближайшее событие, которое начнётся после t (для подсказки «скоро»), ищет до 8 окон вперёд */
	public static Event next(double t) {
		long s = (long) Math.floor(t / SLOT);
		for (long k = s; k < s + 8; k++) {
			Event e = inSlot(k);
			if (e != null && e.start > t) return e;
		}
		return null;
	}

	public static Event inSlot(long slot) {
		if (rnd(slot, 0) >= 0.62) return null;           // в ~62% окон что-то происходит
		int total = 0;
		for (Type ty : Type.values()) total += ty.weight;
		double pick = rnd(slot, 1) * total;
		Type type = Type.METEOR_SHOWER;
		for (Type ty : Type.values()) {
			pick -= ty.weight;
			if (pick < 0) { type = ty; break; }
		}
		long start = slot * SLOT + (long) (rnd(slot, 2) * (SLOT - type.duration - 20));
		return new Event(type, start, start + type.duration, slot);
	}

	public static double rnd(long slot, int k) {
		long h = SEED ^ (slot * 0x9E3779B97F4A7C15L) ^ (k * 0xC2B2AE3D27D4EB4FL);
		h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
		h = (h ^ (h >>> 29)) * 0x94D049BB133111EBL;
		h ^= h >>> 32;
		return (h >>> 11) * 0x1.0p-53;
	}

	/** Радиант метеорного потока: направление, ОТКУДА летят метеоры (единичный вектор) */
	public static void radiant(Event e, double[] out) {
		double a = e.rnd(10) * SolarSystem.TAU, u = e.rnd(11) * 1.2 - 0.6;
		double c = Math.sqrt(1 - u * u);
		out[0] = Math.cos(a) * c; out[1] = u; out[2] = Math.sin(a) * c;
	}

	/** Великая комета: парабола с перигелием у Солнца. Возвращает false, если её сейчас нет. */
	public static boolean greatComet(double t, double[] out, double[] vel) {
		Event e = at(t);
		if (e == null || e.type != Type.GREAT_COMET) return false;
		double q = 650 + e.rnd(20) * 900;
		double rMax = 26000;
		double nuMax = Math.acos(Math.max(-0.999, 2 * q / rMax - 1));
		double dMax = Math.tan(nuMax / 2), tMax = dMax + dMax * dMax * dMax / 3;  // уравнение Баркера
		double tt = (e.progress(t) * 2 - 1) * tMax;
		double d = tt;
		for (int i = 0; i < 20; i++) d -= (d + d * d * d / 3 - tt) / (1 + d * d);
		double nu = 2 * Math.atan(d);
		parabola(e, q, nu, out);
		if (vel != null) {
			double[] o2 = new double[3];
			double d2 = d + 0.002 * (1 + d * d);
			parabola(e, q, 2 * Math.atan(d2), o2);
			vel[0] = o2[0] - out[0]; vel[1] = o2[1] - out[1]; vel[2] = o2[2] - out[2];
		}
		return true;
	}

	private static void parabola(Event e, double q, double nu, double[] out) {
		double r = 2 * q / (1 + Math.cos(nu));
		double x = r * Math.cos(nu), z = r * Math.sin(nu);
		double rot = e.rnd(21) * SolarSystem.TAU, inc = (e.rnd(22) - 0.5) * 1.1;
		double c = Math.cos(rot), s = Math.sin(rot);
		out[0] = x * c - z * s * Math.cos(inc);
		out[2] = x * s + z * c * Math.cos(inc);
		out[1] = SolarSystem.PLANE_Y + Math.max(-1800, Math.min(1800, z * Math.sin(inc)));
	}

	public static final double ROGUE_SIZE = 150;

	/** Блуждающая планета: прямой пролёт сквозь систему. false — её сейчас нет. */
	public static boolean rogue(double t, double[] out) {
		Event e = at(t);
		if (e == null || e.type != Type.ROGUE_PLANET) return false;
		double closest = 2500 + e.rnd(30) * 12000;
		double a = e.rnd(31) * SolarSystem.TAU;
		double cx = Math.cos(a) * closest, cz = Math.sin(a) * closest;
		double dx = -Math.sin(a), dz = Math.cos(a);       // летит по касательной
		double s = (e.progress(t) * 2 - 1) * 32000;
		out[0] = cx + dx * s;
		out[1] = SolarSystem.PLANE_Y + (e.rnd(32) - 0.5) * 1200 + (e.progress(t) - 0.5) * (e.rnd(33) - 0.5) * 1400;
		out[2] = cz + dz * s;
		return true;
	}

	/** Насколько сейчас затмение закрывает Солнце (0 — нет, 1 — полное) */
	public static double eclipse(double t) {
		Event e = at(t);
		if (e == null || e.type != Type.ECLIPSE) return 0;
		double p = e.progress(t);
		// диск наползает 25% времени, полная фаза, потом сползает
		return p < 0.25 ? p / 0.25 : p > 0.75 ? (1 - p) / 0.25 : 1;
	}

	/** Сила солнечной вспышки (0…1) */
	public static double flare(double t) {
		Event e = at(t);
		if (e == null || e.type != Type.SOLAR_FLARE) return 0;
		double p = e.progress(t);
		return p < 0.08 ? p / 0.08 : Math.pow(1 - (p - 0.08) / 0.92, 1.5);
	}

	/** Сила метеорного потока (0…1) */
	public static double shower(double t) {
		Event e = at(t);
		if (e == null || e.type != Type.METEOR_SHOWER) return 0;
		return e.fade(t, 0.15);
	}

	/** Гамма-всплеск (0…1): резкая вспышка и медленное угасание */
	public static double gamma(double t) {
		Event e = at(t);
		if (e == null || e.type != Type.GAMMA_BURST) return 0;
		double p = e.progress(t);
		return p < 0.03 ? p / 0.03 : Math.pow(1 - p, 2.5);
	}
}
