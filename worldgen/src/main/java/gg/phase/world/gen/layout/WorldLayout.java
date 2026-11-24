package gg.phase.world.gen.layout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The whole Phase map as pure math: a ring continent around a central island with Mount Fuji,
 * a scatter of islets outside, endless ocean beyond. Everything that can move between seeds does:
 * coastline, ring width, region layout, islets, lakes, rivers, Fuji gullies and side crater.
 *
 * No Minecraft classes here: the same code drives the game and the offline preview tool.
 */
public final class WorldLayout {
	public static final int SEA = 63;
	/** Bottom of the world: 64 blocks deeper than vanilla. */
	public static final int WORLD_MIN = -128;
	/** Land fits into 5000 x 5000 blocks (radius 2500). */
	public static final double MAP_RADIUS = 3000;

	public static final int ZONE_OCEAN = 0, ZONE_INNER_SEA = 1, ZONE_RING = 2, ZONE_ISLAND = 3, ZONE_ISLET = 4;

	// Fuji: summit rim about 1200 blocks above sea level.
	static final double FUJI_FOOT = 1120;
	static final double FUJI_RIM = 125;
	static final double FUJI_H = 770;
	static final double CRATER_DEPTH = 78;

	private static final Map<Long, WorldLayout> CACHE = new ConcurrentHashMap<>();

	public static WorldLayout get(long layoutSeed) {
		if (CACHE.size() > 4) CACHE.clear();
		return CACHE.computeIfAbsent(layoutSeed, WorldLayout::new);
	}

	public final long seed;
	private final Noise warpA, warpB, coastA, coastB, coastC, shapeN, regionWarpA, regionWarpB;
	private final Noise relA, relB, relC, relD, fujiN, gullyN, caveA, caveB, caveC, misc, overhangN;

	final double ringCx, ringCz, islandCx, islandCz;
	public final double fujiX, fujiZ;
	private final double ringInBase, ringOutBase, islandBase;
	private final double osawaTh, rimPhase;
	public final double hoeiX, hoeiZ;
	private final double hoeiConeX, hoeiConeZ;
	private final double northTh;

	// regions on the ring
	final double[] regX, regZ;
	final Style[] regStyle;
	// islets
	final double[] isX, isZ, isR;
	final Style[] isStyle;
	// lakes and rivers
	public final List<Lake> lakes = new ArrayList<>();
	public final List<River> rivers = new ArrayList<>();
	private final Map<Long, int[]> riverGrid = new HashMap<>();
	private static final int RIVER_CELL = 48;
	public final int spawnX, spawnZ;

	private final ThreadLocal<Column[]> columnCache = ThreadLocal.withInitial(() -> {
		Column[] c = new Column[4096];
		for (int i = 0; i < c.length; i++) c[i] = new Column();
		return c;
	});

	public static final class Lake {
		public final double x, z, r, depth, phase;
		public double level;

		Lake(double x, double z, double r, double depth, double phase) {
			this.x = x; this.z = z; this.r = r; this.depth = depth; this.phase = phase;
		}
	}

	public static final class River {
		public double[] x, z, level, width, depth;
		public int n;
	}

	/** Result of a column query. Mutable and cached per thread: copy what you need. */
	public static final class Column {
		int cx = Integer.MIN_VALUE, cz = Integer.MIN_VALUE;
		public float height, base, coast, spine, overhang, water, fujiR, riverDist, karst;
		/** Floating island above this column (karst spires), NaN if none. */
		public float floatTop = Float.NaN, floatBot = Float.NaN;
		public byte zone, style, surface, waterKind;
	}

	private WorldLayout(long seed) {
		this.seed = seed;
		Noise.SplitMix r = new Noise.SplitMix(seed ^ 0x5DEECE66DL);
		warpA = new Noise(r.next()); warpB = new Noise(r.next());
		coastA = new Noise(r.next()); coastB = new Noise(r.next()); coastC = new Noise(r.next());
		shapeN = new Noise(r.next());
		regionWarpA = new Noise(r.next()); regionWarpB = new Noise(r.next());
		relA = new Noise(r.next()); relB = new Noise(r.next()); relC = new Noise(r.next()); relD = new Noise(r.next());
		fujiN = new Noise(r.next()); gullyN = new Noise(r.next());
		caveA = new Noise(r.next()); caveB = new Noise(r.next()); caveC = new Noise(r.next());
		misc = new Noise(r.next());
		overhangN = new Noise(r.next());

		ringCx = r.range(-45, 45); ringCz = r.range(-45, 45);
		islandCx = r.range(-55, 55); islandCz = r.range(-55, 55);
		fujiX = islandCx + r.range(-35, 35); fujiZ = islandCz + r.range(-35, 35);
		ringInBase = r.range(1640, 1710);
		ringOutBase = r.range(2640, 2710);
		islandBase = r.range(1230, 1280);
		northTh = -Math.PI / 2 + r.range(-0.45, 0.45);
		// Osawa gully faces west-ish, Hoei crater south-east like the real mountain.
		osawaTh = Math.PI + r.range(-0.5, 0.5);
		rimPhase = r.range(0, Math.PI);
		double hoeiTh = Math.PI / 4 + r.range(-0.35, 0.35);
		double hoeiR = FUJI_RIM + 0.27 * (FUJI_FOOT - FUJI_RIM);
		hoeiX = fujiX + Math.cos(hoeiTh) * hoeiR;
		hoeiZ = fujiZ + Math.sin(hoeiTh) * hoeiR;
		hoeiConeX = fujiX + Math.cos(hoeiTh) * (hoeiR + 105);
		hoeiConeZ = fujiZ + Math.sin(hoeiTh) * (hoeiR + 105);

		// ---- ring regions ----
		int n = 26 + r.nextInt(6);
		int extra = 8 + r.nextInt(5);
		regX = new double[n + extra];
		regZ = new double[n + extra];
		regStyle = new Style[n + extra];
		double phase = r.range(0, Math.PI * 2);
		double[] ang = new double[n];
		for (int i = 0; i < n; i++) {
			ang[i] = phase + Math.PI * 2 * i / n + r.range(-0.22, 0.22) * Math.PI * 2 / n;
			double mid = (ringIn(ang[i]) + ringOut(ang[i])) * 0.5;
			double w = ringOut(ang[i]) - ringIn(ang[i]);
			double rad = mid + r.range(-0.18, 0.18) * w;
			regX[i] = ringCx + Math.cos(ang[i]) * rad;
			regZ[i] = ringCz + Math.sin(ang[i]) * rad;
		}
		assignRingStyles(r, ang, n);
		for (int k = 0; k < extra; k++) {
			int i = n + k;
			double a = r.range(0, Math.PI * 2);
			boolean outer = r.nextDouble() < 0.6;
			double edge = outer ? ringOut(a) - 150 : ringIn(a) + 150;
			regX[i] = ringCx + Math.cos(a) * edge;
			regZ[i] = ringCz + Math.sin(a) * edge;
			double t = temperature(a);
			Style[] pool = outer
				? (t > 0.55 ? new Style[]{Style.PINE_CLIFFS, Style.DUNES, Style.SAKURA, Style.KARST, Style.PINE_CLIFFS} : new Style[]{Style.PINE_CLIFFS, Style.SUGI, Style.BIRCH, Style.PINE_CLIFFS})
				: (t > 0.45 ? new Style[]{Style.MARSH, Style.BAMBOO, Style.TERRACES, Style.SAKURA, Style.SPIRIT} : new Style[]{Style.MARSH, Style.MOMIJI, Style.TERRACES, Style.SPIRIT});
			regStyle[i] = pool[r.nextInt(pool.length)];
		}

		// ---- islets ----
		int islets = 48 + r.nextInt(16);
		isX = new double[islets]; isZ = new double[islets]; isR = new double[islets]; isStyle = new Style[islets];
		Style[] isletPool = {Style.SAKURA, Style.PINE_CLIFFS, Style.KARST, Style.BAMBOO, Style.ONSEN, Style.MOMIJI, Style.SUGI, Style.PINE_CLIFFS, Style.KARST, Style.SPIRIT};
		int placed = 0;
		for (int attempt = 0; attempt < islets * 80 && placed < islets; attempt++) {
			double a = r.range(0, Math.PI * 2);
			double u = r.nextDouble();
			double size = 26 + 140 * Math.pow(u, 1.9);
			double rad = ringOut(a) + r.range(90, 380) + size * 0.5;
			if (rad + size > MAP_RADIUS - 10) rad = MAP_RADIUS - 10 - size;
			double x = ringCx + Math.cos(a) * rad, z = ringCz + Math.sin(a) * rad;
			if (rad - size < ringOut(a) + 70) continue;
			boolean ok = true;
			for (int j = 0; j < placed; j++) {
				double dx = isX[j] - x, dz = isZ[j] - z;
				if (Math.sqrt(dx * dx + dz * dz) < isR[j] + size + 40) { ok = false; break; }
			}
			if (!ok) continue;
			isX[placed] = x; isZ[placed] = z; isR[placed] = size;
			isStyle[placed] = isletPool[r.nextInt(isletPool.length)];
			placed++;
		}
		isletCount = placed;

		// ---- lakes ----
		placeLakes(r);
		// ---- rivers ----
		traceRivers(r);
		// ---- spawn: southern foot of Fuji, on dry land ----
		int[] sp = findSpawn();
		spawnX = sp[0];
		spawnZ = sp[1];
	}

	private final int isletCount;

	// ------------------------------------------------------------------ geometry helpers

	static double smooth(double e0, double e1, double x) {
		double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
		return t * t * (3 - 2 * t);
	}

	static double sq(double v) {
		return v * v;
	}

	static double angleDiff(double a, double b) {
		double d = a - b;
		while (d > Math.PI) d -= Math.PI * 2;
		while (d < -Math.PI) d += Math.PI * 2;
		return d;
	}

	private double per(Noise n, double th, double k, double salt) {
		return n.n2(Math.cos(th) * k + salt, Math.sin(th) * k - salt);
	}

	double ringIn(double th) {
		return ringInBase + 150 * per(shapeN, th, 1.6, 0) + 60 * per(shapeN, th, 4.8, 11);
	}

	double ringOut(double th) {
		double v = ringOutBase + 165 * per(shapeN, th, 1.7, 37) + 70 * per(shapeN, th, 5.2, 53);
		return Math.max(v, ringIn(th) + 480);
	}

	double islandRad(double th) {
		return islandBase + 70 * per(shapeN, th, 2.0, 71) + 30 * per(shapeN, th, 6.0, 91);
	}

	/** 0 at the cold north, 1 at the warm south. */
	double temperature(double th) {
		return (1 - Math.cos(th - northTh)) * 0.5;
	}

	private void assignRingStyles(Noise.SplitMix r, double[] ang, int n) {
		Style[] cold = {Style.SNOWLAND, Style.ALPS, Style.SUGI, Style.BIRCH, Style.SNOWLAND, Style.BIRCH};
		Style[] cool = {Style.SUGI, Style.MOMIJI, Style.ALPS, Style.ONSEN, Style.TERRACES, Style.MOMIJI, Style.SPIRIT, Style.KARST, Style.BIRCH};
		Style[] warm = {Style.TERRACES, Style.TEA, Style.SAKURA, Style.BAMBOO, Style.ONSEN, Style.MARSH, Style.MOMIJI, Style.SPIRIT, Style.KARST};
		Style[] hot = {Style.BAMBOO, Style.DUNES, Style.SAKURA, Style.MARSH, Style.TEA, Style.KARST, Style.SPIRIT, Style.TERRACES};
		for (int pass = 0; pass < 60; pass++) {
			for (int i = 0; i < n; i++) {
				double t = temperature(ang[i]);
				Style[] pool = t < 0.28 ? cold : t < 0.55 ? cool : t < 0.82 ? warm : hot;
				regStyle[i] = pool[r.nextInt(pool.length)];
			}
			if (validStyles(n)) return;
		}
		// fallback: force the must-haves in the right climate
		int coldest = 0, warmest = 0;
		for (int i = 0; i < n; i++) {
			if (temperature(ang[i]) < temperature(ang[coldest])) coldest = i;
			if (temperature(ang[i]) > temperature(ang[warmest])) warmest = i;
		}
		regStyle[coldest] = Style.ALPS;
		regStyle[(coldest + 1) % n] = Style.SNOWLAND;
		regStyle[warmest] = Style.SAKURA;
		regStyle[(warmest + n - 1) % n] = Style.BAMBOO;
		regStyle[(coldest + n / 2) % n] = Style.TERRACES;
		regStyle[(coldest + n / 2 + 1) % n] = Style.ONSEN;
		regStyle[(coldest + n / 4) % n] = Style.SPIRIT;
		regStyle[(coldest + 3 * n / 4) % n] = Style.KARST;
		regStyle[(coldest + n / 4 + 2) % n] = Style.MOMIJI;
		regStyle[(coldest + 2) % n] = Style.SUGI;
	}

	private boolean validStyles(int n) {
		int[] count = new int[Style.VALUES.length];
		for (int i = 0; i < n; i++) {
			count[regStyle[i].ordinal()]++;
			if (regStyle[i] == regStyle[(i + 1) % n]) return false;
		}
		Style[] must = {Style.ALPS, Style.SAKURA, Style.BAMBOO, Style.TERRACES, Style.ONSEN, Style.SUGI, Style.MOMIJI, Style.SPIRIT, Style.KARST, Style.TEA};
		for (Style s : must) if (count[s.ordinal()] == 0) return false;
		return count[Style.DUNES.ordinal()] <= 2 && count[Style.MARSH.ordinal()] <= 4 && count[Style.ALPS.ordinal()] <= 5
			&& count[Style.KARST.ordinal()] <= 4 && count[Style.SPIRIT.ordinal()] <= 5 && count[Style.BAMBOO.ordinal()] <= 4;
	}

	// ------------------------------------------------------------------ land shape

	/** Scratch result of the land-shape stage. */
	private static final class Land {
		double d, dFlow, spine, rr, thR;
		int zone, islet = -1;
	}

	private void land(double x, double z, Land out) {
		double wx = x + 150 * warpA.fbm2(x / 900, z / 900, 3) + 35 * warpB.n2(x / 210, z / 210);
		double wz = z + 150 * warpB.fbm2(x / 900, z / 900, 3) + 35 * warpA.n2(x / 210 + 9, z / 210);
		double cnLarge = 95 * coastA.fbm2(x / 700, z / 700, 3);
		double cnSmall = 34 * coastB.fbm2(x / 190, z / 190, 3) + 7 * coastC.n2(x / 40, z / 40);

		double rx = wx - ringCx, rz = wz - ringCz;
		double rr = Math.sqrt(rx * rx + rz * rz);
		double thr = Math.atan2(rz, rx);
		double rin = ringIn(thr), rout = ringOut(thr);
		double dRing = Math.min(rr - rin, rout - rr);

		double ix = wx - islandCx, iz = wz - islandCz;
		double ri = Math.sqrt(ix * ix + iz * iz);
		double dIsl = islandRad(Math.atan2(iz, ix)) - ri;

		double best = dRing + cnLarge + cnSmall, bestFlow = dRing + cnLarge;
		int zone = ZONE_RING, islet = -1;
		double di = dIsl + 0.55 * (cnLarge + cnSmall);
		if (di > best) { best = di; bestFlow = dIsl + 0.55 * cnLarge; zone = ZONE_ISLAND; }
		for (int j = 0; j < isletCount; j++) {
			double dx = wx - isX[j], dz = wz - isZ[j];
			double lim = isR[j] * 1.6 + 120;
			if (Math.abs(dx) > lim || Math.abs(dz) > lim) continue;
			double dist = Math.sqrt(dx * dx + dz * dz);
			double ph = Math.atan2(dz, dx);
			double s = isR[j] * (1 + 0.24 * per(shapeN, ph, 1.6, 100 + j * 13.7) + 0.1 * per(shapeN, ph, 4.5, 300 + j * 7.1));
			double dj = s - dist + 0.35 * cnSmall * Math.min(1, isR[j] / 120);
			if (dj > best) { best = dj; bestFlow = s - dist; zone = ZONE_ISLET; islet = j; }
		}
		if (best < 0) {
			// open water: inner sea sits inside the ring
			zone = rr < rin ? ZONE_INNER_SEA : ZONE_OCEAN;
		}
		double spine;
		if (zone == ZONE_RING) {
			double m = (rr - rin) / (rout - rin);
			spine = smooth(0, 1, 1 - Math.abs(2 * m - 1)) * smooth(0, 190, best);
		} else if (zone == ZONE_ISLET) {
			spine = smooth(0, Math.max(20, isR[islet] * 0.75), best) * Math.min(1, isR[islet] / 130);
		} else {
			spine = smooth(0, 220, best);
		}
		out.d = best;
		out.dFlow = bestFlow;
		out.spine = spine;
		out.rr = rr;
		out.thR = thr;
		out.zone = zone;
		out.islet = islet;
	}

	// ------------------------------------------------------------------ relief per style

	/** Elevation above the coastal base (blocks) for a style. */
	double styleRelief(Style s, double x, double z, double sp) {
		switch (s) {
			case SAKURA:
				return 10 + 42 * sp * (0.55 + 0.45 * relA.fbm2(x / 420, z / 420, 4)) + 7 * relB.fbm2(x / 90, z / 90, 3);
			case MOMIJI:
				return 16 + sp * (60 + 120 * relB.ridged2(x / 520, z / 520, 4)) + 9 * relA.fbm2(x / 110, z / 110, 3);
			case SUGI:
				return 22 + Math.pow(sp, 0.8) * (110 + 190 * relC.ridged2(x / 640, z / 640, 5)) + 12 * relA.fbm2(x / 130, z / 130, 3);
			case BAMBOO:
				return 7 + 24 * sp * (0.6 + 0.4 * relA.fbm2(x / 300, z / 300, 3)) + 6 * relD.fbm2(x / 70, z / 70, 3);
			case TERRACES: {
				double base = 10 + sp * (55 + 75 * (0.5 + 0.5 * relB.fbm2(x / 380, z / 380, 4)));
				double step = 4;
				double f = base / step;
				double fl = Math.floor(f);
				return (fl + smooth(0.78, 1.0, f - fl)) * step;
			}
			case TEA:
				return 12 + sp * (34 + 34 * (0.5 + 0.5 * relC.fbm2(x / 260, z / 260, 3))) + 3 * relD.n2(x / 45, z / 45);
			case ALPS: {
				// a broad massif cut by several jagged ridges and deep valleys
				double rid = relD.ridged2(x / 430, z / 430, 6);
				double massif = Math.pow(sp, 0.9);
				return 40 + massif * (90 + 470 * rid * (0.55 + 0.45 * relC.fbm2(x / 900, z / 900, 2))) + 22 * relA.fbm2(x / 90, z / 90, 3);
			}
			case SNOWLAND: {
				double base = 28 + sp * (100 + 70 * relA.fbm2(x / 500, z / 500, 4));
				double mesa = smooth(0.05, 0.22, relC.fbm2(x / 300, z / 300, 3));
				return base + 34 * mesa * sp + 6 * relB.fbm2(x / 80, z / 80, 2);
			}
			case ONSEN: {
				double vents = Math.pow(1 - Math.abs(relD.n2(x / 120, z / 120)), 5);
				return 18 + sp * (55 + 75 * (0.5 + 0.5 * relB.fbm2(x / 350, z / 350, 4))) + 16 * vents * sp;
			}
			case PINE_CLIFFS:
				return 26 + 30 * sp * (0.6 + 0.4 * relA.fbm2(x / 300, z / 300, 3)) + 8 * relC.fbm2(x / 60, z / 60, 2);
			case MARSH:
				return 1.1 + 2.8 * relA.fbm2(x / 60, z / 60, 3) + 2.5 * sp;
			case DUNES: {
				double dune = Math.pow(1 - Math.abs(relB.n2(x / 95 + 0.35 * relC.n2(x / 300, z / 300), z / 420)), 2);
				return 5 + sp * (12 + 26 * dune) + 4 * relA.fbm2(x / 150, z / 150, 2);
			}
			case SPIRIT: {
				// rolling old hills cut by deep, narrow mossy gorges
				double base = 14 + sp * (45 + 55 * (0.5 + 0.5 * relB.fbm2(x / 420, z / 420, 4))) + 8 * relA.fbm2(x / 90, z / 90, 3);
				double gorge = Math.pow(1 - Math.abs(relC.n2(x / 260 + 0.3 * relD.n2(x / 500, z / 500), z / 260)), 9);
				return base - gorge * 30 * smooth(0.1, 0.6, sp);
			}
			case KARST: {
				double base = 6 + sp * (14 + 16 * (0.5 + 0.5 * relA.fbm2(x / 260, z / 260, 3))) + 4 * relD.fbm2(x / 60, z / 60, 2);
				return base + (0.35 + 0.65 * smooth(0.0, 0.3, sp)) * karstSpires(x, z) * smooth(8, 50, sp * 400);
			}
			case BIRCH: {
				double base = 24 + sp * (60 + 30 * relA.fbm2(x / 500, z / 500, 3));
				double mesa = smooth(-0.1, 0.2, relC.fbm2(x / 350, z / 350, 3)) * 20 * sp;
				return base + mesa + 5 * relB.fbm2(x / 70, z / 70, 2);
			}
			case JUKAI:
			default:
				return 12 + 16 * relA.fbm2(x / 330, z / 330, 4) + 3.5 * Math.abs(relD.n2(x / 22, z / 22));
		}
	}

	private long hashMix(long a, long b, long salt) {
		long h = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL ^ (salt + this.seed) * 0x165667B19E3779F9L;
		h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L; h ^= h >>> 33;
		return h;
	}

	/** Uniform [0, 1) from a cell and a salt. */
	double hash01(long a, long b, long salt) {
		return (hashMix(a, b, salt) >>> 11) * 0x1.0p-53;
	}

	/** Steep stone towers with flat, slightly domed tops (Zhangjiajie / Hallelujah mountains). */
	double karstSpires(double x, double z) {
		final double cell = 31;
		long cx = (long) Math.floor(x / cell), cz = (long) Math.floor(z / cell);
		double best = 0;
		for (long i = cx - 1; i <= cx + 1; i++) {
			for (long j = cz - 1; j <= cz + 1; j++) {
				if (hash01(i, j, 11) > 0.72) continue;
				double hx = (i + 0.15 + 0.7 * hash01(i, j, 12)) * cell;
				double hz = (j + 0.15 + 0.7 * hash01(i, j, 13)) * cell;
				double r = 4.5 + 10 * Math.pow(hash01(i, j, 14), 1.5);
				double H = (40 + 160 * Math.pow(hash01(i, j, 15), 1.2)) * (0.65 + 0.35 * relB.n2(hx / 300, hz / 300));
				double d = Math.hypot(x - hx, z - hz) * (1 + 0.12 * relC.n2(x / 7.0, z / 7.0));
				if (d < r) {
					double t = d / r;
					double prof = (1 - Math.pow(t, 6)) * (1 - 0.2 * t);
					best = Math.max(best, H * prof + 2.5 * relC.n2(x / 9.0, z / 9.0) * (1 - t));
				} else if (d < r * 1.7) {
					double t = (d - r) / (r * 0.7);
					best = Math.max(best, H * 0.13 * sq(1 - t));
				}
			}
		}
		return best;
	}

	private final Map<Long, Float> islandStrength = new ConcurrentHashMap<>();

	/** How firmly a floating-rock cell sits in karst country (0..1), judged at the rock's centre. */
	private double islandStrength(long i, long j, double cell) {
		long key = (i << 32) ^ (j & 0xFFFFFFFFL);
		Float v = islandStrength.get(key);
		if (v != null) return v;
		double hx = (i + 0.2 + 0.6 * hash01(i, j, 22)) * cell;
		double hz = (j + 0.2 + 0.6 * hash01(i, j, 23)) * cell;
		Land L = new Land();
		land(hx, hz, L);
		double out = 0;
		if (L.zone == ZONE_ISLET) {
			out = isStyle[L.islet] == Style.KARST ? smooth(15, 60, L.d) : 0;
		} else if (L.zone == ZONE_RING) {
			double qx = hx + 260 * regionWarpA.fbm2(hx / 950, hz / 950, 3) + 70 * regionWarpB.n2(hx / 230, hz / 230);
			double qz = hz + 260 * regionWarpB.fbm2(hx / 950, hz / 950, 3) + 70 * regionWarpA.n2(hx / 230 + 5, hz / 230);
			double d0 = 1e18, sum = 0, ka = 0;
			double[] dist = new double[regX.length];
			for (int k = 0; k < regX.length; k++) {
				dist[k] = Math.sqrt(sq(qx - regX[k]) + sq(qz - regZ[k]));
				d0 = Math.min(d0, dist[k]);
			}
			for (int k = 0; k < regX.length; k++) {
				double w = Math.exp(-(dist[k] - d0) / 70);
				sum += w;
				if (regStyle[k] == Style.KARST) ka += w;
			}
			out = smooth(0.4, 0.85, ka / sum) * smooth(40, 130, L.d);
		}
		if (islandStrength.size() < 200_000) islandStrength.put(key, (float) out);
		return out;
	}

	/** Floating rocks over karst country. Writes floatTop / floatBot (or NaN) into the column. */
	private void floatingIsland(double x, double z, Column c) {
		c.floatTop = Float.NaN;
		c.floatBot = Float.NaN;
		if (c.karst < 0.02) return;
		final double cell = 96;
		long cx = (long) Math.floor(x / cell), cz = (long) Math.floor(z / cell);
		double bestTop = -1e9, bestBot = 0;
		for (long i = cx - 1; i <= cx + 1; i++) {
			for (long j = cz - 1; j <= cz + 1; j++) {
				if (hash01(i, j, 21) > 0.55) continue;
				double thick = islandStrength(i, j, cell);
				if (thick < 0.25) continue;
				double hx = (i + 0.2 + 0.6 * hash01(i, j, 22)) * cell;
				double hz = (j + 0.2 + 0.6 * hash01(i, j, 23)) * cell;
				double r = 11 + 24 * Math.pow(hash01(i, j, 24), 1.2);
				double yc = SEA + 120 + 115 * hash01(i, j, 25);
				double d = Math.hypot(x - hx, z - hz);
				if (d > r * 1.3) continue;
				double edge = r * (1 + 0.2 * misc.n2(x / 13.0 + i * 3.1, z / 13.0 + j * 1.7));
				if (d >= edge) continue;
				double t = d / edge;
				double top = yc + (4 * (1 - t * t) + 1.5 * relC.n2(x / 11.0, z / 11.0)) * thick;
				double bot = yc - 2 - 0.85 * edge * Math.pow(1 - t, 1.3) * (0.75 + 0.25 * relD.n2(x / 9.0, z / 9.0)) * Math.max(0.6, thick);
				if (top > bestTop) { bestTop = top; bestBot = bot; }
			}
		}
		if (bestTop > -1e8 && bestTop - bestBot > 1.5) {
			c.floatTop = (float) bestTop;
			c.floatBot = (float) bestBot;
		}
	}

	private static double inland(Style s, double d) {
		return smooth(s.inlandStart, s.inlandEnd, d);
	}

	private static double coastRamp(double d) {
		return Math.min(d * 0.16, 3.2);
	}

	private double oceanFloor(double d, int zone, double x, double z) {
		double dd = -d;
		double depth;
		if (zone == ZONE_INNER_SEA) {
			depth = 3 + 29 * (1 - Math.exp(-dd / 95));
		} else {
			depth = 3 + 56 * (1 - Math.exp(-dd / 170)) + 14 * smooth(350, 1300, dd);
		}
		depth += 4 * relC.fbm2(x / 140, z / 140, 3) * smooth(0, 40, dd);
		return SEA - 1 - depth;
	}

	// ------------------------------------------------------------------ Fuji

	/** Height of the volcano above the island foothills. */
	double fuji(double x, double z) {
		double dx = x - fujiX, dz = z - fujiZ;
		double r = Math.sqrt(dx * dx + dz * dz);
		if (r > FUJI_FOOT * 1.1) return 0;
		double th = Math.atan2(dz, dx);
		double rw = r * (1 + 0.035 * per(fujiN, th, 2.2, 3) + 0.012 * per(fujiN, th, 9, 7));
		if (rw >= FUJI_FOOT) return 0;
		double peaks = 9 * Math.pow(Math.abs(Math.cos(4 * th + rimPhase)), 3);
		double h;
		if (rw > FUJI_RIM) {
			double s = (rw - FUJI_RIM) / (FUJI_FOOT - FUJI_RIM);
			// concave like the real cone: steep near the rim, a long gentle apron at the foot
			h = FUJI_H * (0.62 * Math.pow(1 - s, 2.1) + 0.38 * Math.pow(1 - s, 1.2));
			h += (7 + peaks) * Math.exp(-sq((rw - FUJI_RIM) / 14));
			double gm = Math.sin(Math.PI * Math.min(1, s * 1.25));
			double g = 1 - Math.abs(gullyN.n3(Math.cos(th) * 11, Math.sin(th) * 11, rw / 300.0));
			h -= Math.pow(g, 4) * 46 * gm;
			double g2 = 1 - Math.abs(gullyN.n3(Math.cos(th) * 34 + 40, Math.sin(th) * 34, rw / 120.0 + 40));
			h -= Math.pow(g2, 6) * 12 * gm;
			double dth = angleDiff(th, osawaTh);
			h -= 70 * Math.exp(-sq(dth / 0.03)) * smooth(0.0, 0.07, s) * (1 - smooth(0.45, 0.78, s));
		} else {
			double rimH = FUJI_H + 7 + peaks;
			double t = rw / FUJI_RIM;
			h = rimH - CRATER_DEPTH * Math.pow(Math.max(0, 1 - t * t), 0.85);
		}
		// Hoei crater: an amphitheatre on the south-east flank, with its small cone below.
		double hx = x - hoeiX, hz = z - hoeiZ;
		double hd = Math.sqrt(hx * hx + hz * hz);
		if (hd < 85) h -= 68 * Math.pow(1 - sq(hd / 85), 1.1);
		else if (hd < 100) h += 6 * Math.sin(Math.PI * (hd - 85) / 15);
		double cx = x - hoeiConeX, cz = z - hoeiConeZ;
		double cd = Math.sqrt(cx * cx + cz * cz);
		if (cd < 60) h += 32 * Math.pow(1 - cd / 60, 1.6);
		return Math.max(0, h);
	}

	/** 0..1 how much this spot of the cone lies in a gully (snow lingers there). */
	double fujiGully(double x, double z) {
		double dx = x - fujiX, dz = z - fujiZ;
		double r = Math.sqrt(dx * dx + dz * dz);
		double th = Math.atan2(dz, dx);
		double g = 1 - Math.abs(gullyN.n3(Math.cos(th) * 11, Math.sin(th) * 11, r / 300.0));
		return Math.pow(g, 4);
	}

	// ------------------------------------------------------------------ base height (no water carving)

	private final ThreadLocal<Land> landScratch = ThreadLocal.withInitial(Land::new);

	/** Terrain height before lakes and rivers. Fills style / spine / overhang into col. */
	private double baseHeight(double x, double z, Land L, Column col) {
		land(x, z, L);
		double d = L.d;
		if (col != null) {
			col.coast = (float) d;
			col.zone = (byte) L.zone;
			col.spine = (float) L.spine;
			col.style = -1;
			col.overhang = 0;
			col.karst = 0;
		}
		if (d < 0) {
			double floor = oceanFloor(d, L.zone, x, z);
			if (L.zone == ZONE_INNER_SEA || L.zone == ZONE_OCEAN) {
				// Fuji's apron may run into the sea a little
				double f = fuji(x, z) * smooth(-40, 0, d);
				return floor + f;
			}
			return floor;
		}
		if (L.zone == ZONE_ISLAND) {
			Style s = Style.JUKAI;
			double e = styleRelief(s, x, z, L.spine) * inland(s, d);
			double f = fuji(x, z) * smooth(-40, 60, d);
			if (col != null) {
				col.style = (byte) islandStyle(x, z).ordinal();
				col.overhang = (float) (f > 30 ? 1.5 : Style.JUKAI.overhang);
			}
			return SEA + coastRamp(d) + e + f;
		}
		if (L.zone == ZONE_ISLET) {
			Style s = isStyle[L.islet];
			double e = styleRelief(s, x, z, L.spine) * inland(s, d);
			if (col != null) {
				col.style = (byte) s.ordinal();
				col.overhang = s.overhang * 0.7f;
				col.karst = s == Style.KARST ? 1 : 0;
			}
			return SEA + coastRamp(d) + e;
		}
		// ring: blend the three nearest regions
		double qx = x + 260 * regionWarpA.fbm2(x / 950, z / 950, 3) + 70 * regionWarpB.n2(x / 230, z / 230);
		double qz = z + 260 * regionWarpB.fbm2(x / 950, z / 950, 3) + 70 * regionWarpA.n2(x / 230 + 5, z / 230);
		int b0 = -1, b1 = -1, b2 = -1;
		double d0 = 1e18, d1 = 1e18, d2 = 1e18;
		for (int i = 0; i < regX.length; i++) {
			double dx = qx - regX[i], dz = qz - regZ[i];
			double dist = dx * dx + dz * dz;
			if (dist < d0) { d2 = d1; b2 = b1; d1 = d0; b1 = b0; d0 = dist; b0 = i; }
			else if (dist < d1) { d2 = d1; b2 = b1; d1 = dist; b1 = i; }
			else if (dist < d2) { d2 = dist; b2 = i; }
		}
		d0 = Math.sqrt(d0); d1 = Math.sqrt(d1); d2 = Math.sqrt(d2);
		double blend = 70;
		double w0 = 1, w1 = Math.exp(-(d1 - d0) / blend), w2 = b2 >= 0 ? Math.exp(-(d2 - d0) / blend) : 0;
		double sum = w0 + w1 + w2;
		double e = 0, oh = 0, ka = 0;
		int[] idx = {b0, b1, b2};
		double[] w = {w0, w1, w2};
		for (int k = 0; k < 3; k++) {
			if (idx[k] < 0 || w[k] < 0.02) continue;
			Style s = regStyle[idx[k]];
			e += w[k] / sum * styleRelief(s, x, z, L.spine) * inland(s, d);
			oh += w[k] / sum * s.overhang;
			if (s == Style.KARST) ka += w[k] / sum;
		}
		if (col != null) {
			// biome borders get a finer, ragged warp on top of the smooth height blend
			double jx = qx + 34 * regionWarpA.n2(x / 90.0, z / 90.0);
			double jz = qz + 34 * regionWarpB.n2(x / 90.0 + 3, z / 90.0);
			int best = b0;
			double bd = 1e18;
			for (int k = 0; k < 3; k++) {
				if (idx[k] < 0) continue;
				double dd = sq(jx - regX[idx[k]]) + sq(jz - regZ[idx[k]]);
				if (dd < bd) { bd = dd; best = idx[k]; }
			}
			col.style = (byte) regStyle[best].ordinal();
			col.overhang = (float) oh;
			col.karst = (float) ka;
		}
		return SEA + coastRamp(d) + e;
	}

	private Style islandStyle(double x, double z) {
		double th = Math.atan2(z - fujiZ, x - fujiX);
		double rel = angleDiff(th, northTh) + 0.7 * misc.n2(x / 520, z / 520) + 0.25 * misc.n2(x / 140, z / 140);
		double a = Math.abs(rel);
		if (a < 1.75) return Style.JUKAI;            // north half: sea of trees and the lakes
		// south: tea fields and cherry slopes in irregular patches
		double patch = misc.fbm2(x / 300.0 + 40, z / 300.0, 3) + 0.2 * misc.n2(x / 60.0, z / 60.0) + 0.12 * Math.sin(rel * 2.2);
		return patch > 0 ? Style.TEA : Style.SAKURA;
	}

	public double baseHeight(double x, double z) {
		return baseHeight(x, z, landScratch.get(), null);
	}

	public double coastDistance(double x, double z) {
		Land L = landScratch.get();
		land(x, z, L);
		return L.d;
	}

	// ------------------------------------------------------------------ lakes

	private void placeLakes(Noise.SplitMix r) {
		Land L = new Land();
		// Fuji Five Lakes along the northern foot
		int fuji = 3 + r.nextInt(3);
		for (int i = 0, tries = 0; i < fuji && tries < 200; tries++) {
			double a = northTh + r.range(-1.45, 1.45);
			double rad = r.range(900, 1050);
			double x = fujiX + Math.cos(a) * rad, z = fujiZ + Math.sin(a) * rad;
			double size = r.range(45, 120);
			land(x, z, L);
			if (L.zone != ZONE_ISLAND || L.d < size + 45) continue;
			if (tooCloseToLake(x, z, size + 90)) continue;
			lakes.add(new Lake(x, z, size, r.range(7, 16), r.range(0, 100)));
			i++;
		}
		// ring lakes in calmer regions
		int ring = 6 + r.nextInt(4);
		for (int i = 0, tries = 0; i < ring && tries < 400; tries++) {
			double a = r.range(0, Math.PI * 2);
			double m = r.range(0.28, 0.72);
			double rad = ringIn(a) + m * (ringOut(a) - ringIn(a));
			double x = ringCx + Math.cos(a) * rad, z = ringCz + Math.sin(a) * rad;
			double size = r.range(40, 115);
			land(x, z, L);
			if (L.zone != ZONE_RING || L.d < size + 60) continue;
			Style s = nearestRegion(x, z);
			if (s == Style.ALPS || s == Style.DUNES || s == Style.PINE_CLIFFS || s == Style.KARST) continue;
			if (tooCloseToLake(x, z, size + 150)) continue;
			lakes.add(new Lake(x, z, size, r.range(6, 15), r.range(0, 100)));
			i++;
		}
		// water level: lowest point of the rim, so the basin always holds its water
		for (Lake lk : lakes) {
			double min = 1e9;
			for (int k = 0; k < 72; k++) {
				double a = Math.PI * 2 * k / 72;
				double rs = lakeShape(lk, a) * 1.18 + 4;
				min = Math.min(min, baseHeight(lk.x + Math.cos(a) * rs, lk.z + Math.sin(a) * rs));
			}
			lk.level = Math.max(SEA + 1, Math.floor(min) - 1);
		}
	}

	private boolean tooCloseToLake(double x, double z, double dist) {
		for (Lake l : lakes) {
			if (Math.hypot(l.x - x, l.z - z) < dist + l.r) return true;
		}
		return false;
	}

	Style nearestRegion(double x, double z) {
		int best = 0;
		double bd = 1e18;
		for (int i = 0; i < regX.length; i++) {
			double d = sq(x - regX[i]) + sq(z - regZ[i]);
			if (d < bd) { bd = d; best = i; }
		}
		return regStyle[best];
	}

	private double lakeShape(Lake lk, double a) {
		return lk.r * (1 + 0.34 * per(misc, a, 1.3, lk.phase) + 0.14 * per(misc, a, 3.6, lk.phase + 50) + 0.05 * per(misc, a, 9, lk.phase + 90));
	}

	// ------------------------------------------------------------------ rivers

	private void traceRivers(Noise.SplitMix r) {
		Land L = new Land();
		List<double[]> sources = new ArrayList<>();
		// outlets of lakes first: water must leave a lake somewhere
		for (Lake lk : lakes) {
			double bestA = 0, bestH = 1e9;
			for (int k = 0; k < 72; k++) {
				double a = Math.PI * 2 * k / 72;
				double rs = lakeShape(lk, a) * 1.18 + 4;
				double h = baseHeight(lk.x + Math.cos(a) * rs, lk.z + Math.sin(a) * rs);
				if (h < bestH) { bestH = h; bestA = a; }
			}
			double rs = lakeShape(lk, bestA) * 0.9;
			sources.add(new double[]{lk.x + Math.cos(bestA) * rs, lk.z + Math.sin(bestA) * rs, lk.level, Math.cos(bestA), Math.sin(bestA)});
		}
		// mountain springs along the ring spine
		int count = 24 + r.nextInt(8);
		for (int i = 0, tries = 0; i < count && tries < 600; tries++) {
			double a = r.range(0, Math.PI * 2);
			double m = r.range(0.38, 0.62);
			double rad = ringIn(a) + m * (ringOut(a) - ringIn(a));
			double x = ringCx + Math.cos(a) * rad, z = ringCz + Math.sin(a) * rad;
			land(x, z, L);
			if (L.zone != ZONE_RING || L.d < 140) continue;
			if (nearestRegion(x, z) == Style.DUNES && r.nextDouble() < 0.8) continue;
			boolean far = true;
			for (double[] s : sources) if (Math.hypot(s[0] - x, s[1] - z) < 260) { far = false; break; }
			if (!far) continue;
			double sign = m < 0.5 ? -1 : 1;
			if (r.nextDouble() < 0.25) sign = -sign;
			sources.add(new double[]{x, z, Double.NaN, Math.cos(a) * sign, Math.sin(a) * sign});
			i++;
		}
		// springs on the Fuji foothills (think Shiraito falls)
		for (int i = 0; i < 3; i++) {
			double a = northTh + Math.PI + r.range(-1.6, 1.6);
			double rad = r.range(720, 920);
			double x = fujiX + Math.cos(a) * rad, z = fujiZ + Math.sin(a) * rad;
			sources.add(new double[]{x, z, Double.NaN, Math.cos(a), Math.sin(a)});
		}
		int id = 0;
		for (double[] s : sources) {
			River rv = trace(s[0], s[1], s[2], s[3], s[4], id++, L);
			if (rv != null && rv.n >= 6) {
				rivers.add(rv);
				index(rivers.size() - 1, rv);
			}
		}
	}

	private River trace(double x, double z, double startLevel, double dx, double dz, int id, Land L) {
		int max = 420;
		double step = 16;
		double[] px = new double[max], pz = new double[max], lv = new double[max];
		int n = 0;
		double level = Double.isNaN(startLevel) ? baseHeight(x, z) - 2 : startLevel;
		double len = Math.hypot(dx, dz);
		dx /= len; dz /= len;
		for (int i = 0; i < max; i++) {
			land(x, z, L);
			double hHere = Double.isNaN(startLevel) || i > 3 ? baseHeight(x, z) : level + 2;
			// banks on both sides must hold the water
			double nx = -dz, nz = dx;
			double side = Math.min(baseHeight(x + nx * 9, z + nz * 9), baseHeight(x - nx * 9, z - nz * 9));
			double cap = Math.min(hHere, side) - 2.5;
			if (L.d < 0) cap = SEA;
			level = Math.max(SEA, Math.min(level, cap));
			px[n] = x; pz[n] = z; lv[n] = level; n++;
			if (L.d < -14) break;
			if (i > 4) {
				Lake lk = insideLake(x, z);
				if (lk != null) {
					lv[n - 1] = Math.max(lk.level, lv[n - 1]);
					break;
				}
			}
			// flow downhill towards the coast, with inertia and slow meanders
			double gx = coastDistanceFlow(x + 6, z) - coastDistanceFlow(x - 6, z);
			double gz = coastDistanceFlow(x, z + 6) - coastDistanceFlow(x, z - 6);
			double gl = Math.hypot(gx, gz);
			if (gl > 1e-6) { gx /= gl; gz /= gl; }
			double tx = 0.8 * dx - 0.2 * gx, tz = 0.8 * dz - 0.2 * gz;
			// keep away from rivers already traced
			double[] rep = repel(x, z);
			tx += rep[0]; tz += rep[1];
			double meander = misc.n2(i * 0.09 + id * 9.7, id * 3.3) + 0.4 * misc.n2(i * 0.3 + id * 2.1, id * 7.7);
			double c = Math.cos(meander * 0.42), sn = Math.sin(meander * 0.42);
			double ndx = tx * c - tz * sn, ndz = tx * sn + tz * c;
			double nl = Math.hypot(ndx, ndz);
			if (nl < 1e-6) break;
			dx = ndx / nl; dz = ndz / nl;
			x += dx * step; z += dz * step;
			if (Math.hypot(x, z) > MAP_RADIUS + 300) break;
		}
		if (n < 2) return null;
		// make sure the level never rises downstream and ends at sea level if it reached the coast
		for (int i = 1; i < n; i++) lv[i] = Math.min(lv[i], lv[i - 1]);
		River rv = new River();
		rv.n = n;
		rv.x = new double[n]; rv.z = new double[n]; rv.level = new double[n]; rv.width = new double[n]; rv.depth = new double[n];
		for (int i = 0; i < n; i++) {
			double t = (double) i / (n - 1);
			rv.x[i] = px[i]; rv.z[i] = pz[i]; rv.level[i] = lv[i];
			rv.width[i] = 2.5 + 7.5 * Math.sqrt(t) + 1.5 * misc.n2(i * 0.11, id * 5.1);
			rv.depth[i] = 1.6 + 2.6 * t;
		}
		// estuary
		for (int i = Math.max(0, n - 6); i < n; i++) rv.width[i] *= 1.0 + 0.12 * (i - (n - 6));
		return rv;
	}

	private Lake insideLake(double x, double z) {
		for (Lake lk : lakes) {
			double d = Math.hypot(x - lk.x, z - lk.z);
			if (d < lakeShape(lk, Math.atan2(z - lk.z, x - lk.x)) * 0.95) return lk;
		}
		return null;
	}

	private double coastDistanceFlow(double x, double z) {
		Land L = landScratch.get();
		land(x, z, L);
		return L.dFlow;
	}

	private double[] repel(double x, double z) {
		double rx = 0, rz = 0;
		for (River rv : rivers) {
			for (int i = 0; i < rv.n; i += 2) {
				double dx = x - rv.x[i], dz = z - rv.z[i];
				double d2 = dx * dx + dz * dz;
				if (d2 < 90 * 90 && d2 > 1) {
					double d = Math.sqrt(d2);
					double f = 0.5 * (1 - d / 90);
					rx += dx / d * f;
					rz += dz / d * f;
				}
			}
		}
		return new double[]{rx, rz};
	}

	private void index(int riverIdx, River rv) {
		for (int i = 0; i < rv.n - 1; i++) {
			double pad = rv.width[i] * 1.5 + 70;
			int x0 = (int) Math.floor((Math.min(rv.x[i], rv.x[i + 1]) - pad) / RIVER_CELL);
			int x1 = (int) Math.floor((Math.max(rv.x[i], rv.x[i + 1]) + pad) / RIVER_CELL);
			int z0 = (int) Math.floor((Math.min(rv.z[i], rv.z[i + 1]) - pad) / RIVER_CELL);
			int z1 = (int) Math.floor((Math.max(rv.z[i], rv.z[i + 1]) + pad) / RIVER_CELL);
			for (int cx = x0; cx <= x1; cx++) {
				for (int cz = z0; cz <= z1; cz++) {
					long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
					int[] arr = riverGrid.get(key);
					int ref = (riverIdx << 12) | i;
					if (arr == null) arr = new int[]{ref};
					else {
						arr = java.util.Arrays.copyOf(arr, arr.length + 1);
						arr[arr.length - 1] = ref;
					}
					riverGrid.put(key, arr);
				}
			}
		}
	}

	// ------------------------------------------------------------------ column query

	public Column column(int x, int z) {
		Column[] cache = columnCache.get();
		int h = (x * 73856093) ^ (z * 19349663);
		Column c = cache[(h ^ (h >>> 13)) & 4095];
		if (c.cx == x && c.cz == z) return c;
		compute(x, z, c);
		c.cx = x;
		c.cz = z;
		return c;
	}

	private void compute(int x, int z, Column c) {
		Land L = landScratch.get();
		double h0 = baseHeight(x, z, L, c);
		double h = h0;
		double water = Double.NaN;
		byte kind = 0;
		c.base = (float) h0;
		c.riverDist = 1e6f;
		double dxF = x - fujiX, dzF = z - fujiZ;
		c.fujiR = (float) Math.sqrt(dxF * dxF + dzF * dzF);

		// lakes
		for (Lake lk : lakes) {
			double dx = x - lk.x, dz = z - lk.z;
			double lim = lk.r * 1.6 + 60;
			if (Math.abs(dx) > lim || Math.abs(dz) > lim) continue;
			double dist = Math.sqrt(dx * dx + dz * dz);
			double rs = lakeShape(lk, Math.atan2(dz, dx));
			if (dist < rs) {
				double t = dist / rs;
				double bed = lk.level - 1 - lk.depth * Math.pow(1 - t * t, 0.7) + 1.5 * relA.n2(x / 18.0, z / 18.0);
				h = Math.min(h, bed);
				water = lk.level;
				kind = 2;
			} else if (dist < rs + 45) {
				// a flat rim a few blocks wide sits clearly above the water, then the shore rises
				double t = Math.max(0, dist - rs - 3) / 42;
				if (h0 > lk.level + 1.7) h = Math.min(h, lk.level + 1.7 + Math.pow(t, 1.4) * (h0 - lk.level - 1.7));
				else if (dist < rs + 4 && h0 > lk.level - 3) h = Math.max(h, lk.level + 1.3);
			}
		}

		// rivers
		if (c.zone != ZONE_OCEAN && c.zone != ZONE_INNER_SEA || c.coast > -30) {
			int cx = Math.floorDiv(x, RIVER_CELL), cz = Math.floorDiv(z, RIVER_CELL);
			int[] refs = riverGrid.get(((long) cx << 32) ^ (cz & 0xFFFFFFFFL));
			if (refs != null) {
				double bestScore = 1e9, bestDist = 0, bestW = 0, bestL = 0, bestDepth = 0;
				for (int ref : refs) {
					River rv = rivers.get(ref >>> 12);
					int i = ref & 4095;
					double ax = rv.x[i], az = rv.z[i], bx = rv.x[i + 1], bz = rv.z[i + 1];
					double vx = bx - ax, vz = bz - az;
					double l2 = vx * vx + vz * vz;
					double u = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((x - ax) * vx + (z - az) * vz) / l2));
					double qx = ax + vx * u - x, qz = az + vz * u - z;
					double dist = Math.sqrt(qx * qx + qz * qz);
					double w = rv.width[i] + (rv.width[i + 1] - rv.width[i]) * u;
					double score = dist - w;
					if (score < bestScore) {
						bestScore = score;
						bestDist = dist;
						bestW = w;
						// step-wise level: the water surface drops at segment joints, not mid-segment
						bestL = u < 0.5 ? rv.level[i] : rv.level[i + 1];
						bestDepth = rv.depth[i] + (rv.depth[i + 1] - rv.depth[i]) * u;
					}
				}
				if (bestScore < 70) {
					c.riverDist = (float) bestScore;
					double lvl = Math.floor(bestL);
					if (bestDist < bestW) {
						double t = bestDist / bestW;
						double bed = lvl - 0.6 - bestDepth * (1 - t * t);
						if (bed < h) {
							h = bed;
							if (lvl > SEA - 1 && (Double.isNaN(water) || lvl > water)) {
								water = lvl;
								kind = 1;
							}
						}
					} else {
						// banks: a 2-3 block shelf above the water, then the valley side
						double bank = Math.min(60, 6 + 0.35 * Math.max(0, h0 - lvl));
						double t = Math.max(0, bestDist - bestW - 2.5) / bank;
						if (t < 1 && h0 > lvl + 1.7) {
							h = Math.min(h, lvl + 1.7 + Math.pow(t, 1.6) * (h0 - lvl - 1.7));
						} else if (bestDist < bestW + 3.5 && h0 > lvl - 4 && lvl > SEA && h < lvl + 1.3) {
							// low ground beside the river: a natural levee keeps the water in its bed
							h = lvl + 1.3;
						}
					}
				}
			}
		}
		// water below sea level is the ocean's business (aquifers fill it)
		if (!Double.isNaN(water) && water <= SEA) {
			water = Double.NaN;
			kind = 0;
		}
		c.height = (float) h;
		c.water = (float) water;
		c.waterKind = kind;
		floatingIsland(x, z, c);
		c.surface = (byte) surfaceBiome(x, z, c).ordinal();
	}

	private PB surfaceBiome(int x, int z, Column c) {
		double d = c.coast;
		if (d < 0 && c.waterKind == 0) {
			if (c.zone == ZONE_INNER_SEA) return PB.INNER_SEA;
			if (c.height > SEA - 18 && warmth(x, z) > 0.62 && misc.n2(x / 170.0, z / 170.0) > -0.35) return PB.CORAL_COAST;
			return c.height < SEA - 30 ? PB.DEEP_OCEAN : PB.OCEAN;
		}
		if (c.riverDist < 2.5f) return PB.RIVER;
		Style st = c.style >= 0 ? Style.VALUES[c.style] : Style.SAKURA;
		double beachW = 14 + 8 * misc.n2(x / 90.0, z / 90.0);
		if (d < beachW && c.height < SEA + 5) {
			if (c.zone == ZONE_ISLAND || st == Style.ONSEN) return PB.BLACK_SAND_BEACH;
			if (st == Style.PINE_CLIFFS || st == Style.ALPS || st == Style.SUGI || st == Style.KARST || c.height - c.base < -3) return PB.ROCKY_SHORE;
			if (st == Style.MARSH) return PB.REED_MARSH;
			return PB.BEACH;
		}
		if (st == Style.PINE_CLIFFS && d < 26) return PB.ROCKY_SHORE;
		double h = c.height;
		if (c.zone == ZONE_ISLAND && c.fujiR < FUJI_FOOT) {
			double g = fujiGully(x, z);
			double snow = SEA + 0.8 * FUJI_H - 110 * g + 25 * misc.n2(x / 60.0, z / 60.0);
			double tree = SEA + 0.58 * FUJI_H + 35 * misc.n2(x / 120.0, z / 120.0);
			if (h > snow) return PB.FUJI_SUMMIT;
			if (h > tree) return PB.FUJI_SLOPES;
			if (h > SEA + 0.36 * FUJI_H + 25 * misc.n2(x / 90.0, z / 90.0)) return PB.FUJI_SUBALPINE;
			if (Math.hypot(x - hoeiX, z - hoeiZ) < 95) return PB.FUJI_SLOPES;
		}
		if (h > SEA + 470 + 35 * misc.n2(x / 110.0, z / 110.0)) return PB.FROZEN_SUMMITS;
		// sub-biomes: patches of a few hundred blocks inside each region
		double sub = 8.3 * misc.fbm2(x / 330.0 + 111, z / 330.0 - 37, 2);
		double sub2 = 8.3 * overhangN.fbm2(x / 380.0 - 71, z / 380.0 + 53, 2);
		double warm = warmth(x, z);
		if ((st == Style.SAKURA || st == Style.TERRACES) && c.riverDist > 2.5f && c.riverDist < 40
			&& misc.fbm2(x / 260.0, z / 260.0, 2) > 0.12) return PB.HIGANBANA_FIELDS;
		if (d < 90 && h < SEA + 6 && warm > 0.7 && (st == Style.MARSH || st == Style.BAMBOO || st == Style.KARST))
			return PB.MANGROVE_COAST;
		switch (st) {
			case SAKURA:
				if (sub > 0.55) return PB.WISTERIA_GROVE;
				if (sub < -0.75) return PB.HIGANBANA_FIELDS;
				if (sub2 > 0.6) return PB.TSUTSUJI_HILLS;
				break;
			case MOMIJI:
				if (sub > 0.5) return PB.GINKGO_GROVE;
				if (sub2 > 0.6) return PB.TSUTSUJI_HILLS;
				if (sub2 < -0.7) return PB.SUSUKI_PLAINS;
				break;
			case SUGI:
				if (sub > 0.6) return PB.SPIRIT_FOREST;
				if (sub2 > 0.5 && warm > 0.35) return PB.YAKUSHIMA_MOSS;
				break;
			case JUKAI:
				if (sub > 0.7) return PB.SPIRIT_FOREST;
				if (sub2 > 0.55) return PB.YAKUSHIMA_MOSS;
				break;
			case BAMBOO:
				if (sub > 0.45 && warm > 0.6) return PB.TROPICAL_FOREST;
				if (sub < -0.6) return PB.TSUTSUJI_HILLS;
				if (sub2 > 0.6) return PB.HYDRANGEA_HILLS;
				break;
			case TERRACES:
				if (sub > 0.55) return PB.HYDRANGEA_HILLS;
				if (sub2 < -0.6) return PB.SUSUKI_PLAINS;
				break;
			case TEA:
				if (sub < -0.6) return PB.HYDRANGEA_HILLS;
				if (sub2 > 0.6) return PB.TSUTSUJI_HILLS;
				if (sub2 < -0.65) return PB.SUSUKI_PLAINS;
				break;
			case ALPS:
				if (h < SEA + 230 + 30 * misc.n2(x / 80.0, z / 80.0) && sub > -0.3) return PB.ALPINE_MEADOW;
				if (sub2 > 0.7 && warm < 0.45) return PB.SHIRAKABA_PLATEAU;
				break;
			case SNOWLAND:
				if (sub > 0.4) return PB.SHIRAKABA_PLATEAU;
				if (sub2 < -0.6) return PB.SUSUKI_PLAINS;
				break;
			case ONSEN:
				if (sub > 0.45) return PB.JIGOKU_WASTES;
				if (sub2 > 0.5) return PB.SUSUKI_PLAINS;
				break;
			case MARSH:
				if (sub > 0.1) return PB.LOTUS_MARSH;
				break;
			case BIRCH:
				if (sub < -0.6) return PB.ALPINE_MEADOW;
				if (sub2 > 0.6) return PB.SUSUKI_PLAINS;
				break;
			case SPIRIT:
				if (sub < -0.9) return PB.MOMIJI_HIGHLANDS;
				if (sub2 > 0.55) return PB.YAKUSHIMA_MOSS;
				if (sub2 < -0.6 && warm > 0.65) return PB.TROPICAL_FOREST;
				break;
			case KARST:
				if (sub2 > 0.6 && warm > 0.6) return PB.TROPICAL_FOREST;
				break;
			case DUNES:
				if (sub2 > 0.5) return PB.SUSUKI_PLAINS;
				break;
			case PINE_CLIFFS:
				if (sub2 > 0.6) return PB.TSUTSUJI_HILLS;
				break;
			default:
				break;
		}
		return st.biome;
	}

	/** 0 at the cold north of the ring, 1 at the warm south. */
	double warmth(double x, double z) {
		return temperature(Math.atan2(z - ringCz, x - ringCx));
	}

	// ------------------------------------------------------------------ 3D queries

	/** Biome ordinal at a block position (caves included). */
	public int biome(int x, int y, int z) {
		Column c = column(x, z);
		double depth = c.height - y;
		if (depth < 14 || c.surface <= PB.INNER_SEA.ordinal() && y > c.height - 6 || c.surface == PB.CORAL_COAST.ordinal() && y > c.height - 6) return c.surface;
		return caveBiome(x, y, z, c).ordinal();
	}

	public PB caveBiome(int x, int y, int z, Column c) {
		double n1 = caveA.fbm3(x / 230.0, y / 110.0, z / 230.0, 2);
		double n2 = caveB.fbm3(x / 260.0, y / 130.0, z / 260.0, 2);
		// the deep layer below vanilla's bottom: vanilla-style caves, sculk in places
		if (y < -64) return n1 > 0.12 ? PB.ABYSS : PB.DEEP_CAVERNS;
		boolean underFuji = c.zone == ZONE_ISLAND && c.fujiR < FUJI_FOOT * 0.95;
		if (underFuji) {
			// lava tubes inside the cone, wind and ice caves near its northern foot
			if (y < SEA + 260 && c.fujiR < 850 && n1 > -0.25) return PB.MAGMA_TUBES;
			double rel = Math.abs(angleDiff(Math.atan2(z - fujiZ, x - fujiX), northTh));
			if (rel < 1.3 && y > 10 && y < SEA + 160 && n2 > 0.05) return PB.ICE_CAVES;
		}
		Style st = c.style >= 0 ? Style.VALUES[c.style] : Style.SAKURA;
		if ((st == Style.SNOWLAND || st == Style.ALPS || st == Style.BIRCH) && y > 0 && n2 > 0.25) return PB.ICE_CAVES;
		if (st == Style.ONSEN && n1 > 0.1 && y < 40) return PB.MAGMA_TUBES;
		// the giant halls and everything right around them glow
		if (chamberField(x, y, z) > chamberThreshold(y, c) - 0.035) {
			if (y < -8 && n1 > 0.3) return PB.ECLIPSE_HOLLOWS;
			if (n2 < -0.35) return PB.UNDERGROUND_LAKE;
			return n2 > -0.3 ? PB.LUMINOUS_DEPTHS : PB.DEEP_CAVERNS;
		}
		double depth = c.height - y;
		boolean forest = st == Style.SUGI || st == Style.SPIRIT || st == Style.JUKAI || st == Style.MOMIJI || st == Style.BAMBOO;
		if (forest && depth < 75 && n1 > 0.05) return PB.ROOT_HALLS;
		double n3 = caveC.fbm3(x / 200.0 + 300, y / 90.0, z / 200.0 - 300, 2);
		if (y < -8 && n1 > 0.3) return PB.ECLIPSE_HOLLOWS;
		if (c.coast < 380 && y < SEA - 4 && y > -40 && n3 > 0.22) return PB.BLUE_GROTTO;
		if (n3 < -0.28 && y < 20) return PB.UNDERGROUND_LAKE;
		if (n2 < -0.35 && y > -50) return PB.MUSHROOM_CAVERNS;
		if (n2 > 0.42 && y > -40) return PB.HIKARI_GROTTO;
		if ((c.coast < 220 || st == Style.MARSH) && y < SEA - 5 && n1 < -0.2) return PB.FLOODED_CAVES;
		return PB.DEEP_CAVERNS;
	}

	/**
	 * Aquifer floodedness: lakes stand in the underground-lake halls (partly flooded, the water level varies by cell),
	 * the blue grottoes near the coast fill to sea level. Elsewhere vanilla's own noise decides (combined with max).
	 */
	public double floodedness(int x, int y, int z, Column c) {
		if (c.height - y < 20 || y > SEA) return -1;
		PB b = caveBiome(x, y, z, c);
		if (b == PB.BLUE_GROTTO) return 0.9;
		if (b == PB.UNDERGROUND_LAKE) return 0.6;
		return -1;
	}

	/** Low-frequency 3D field of the giant halls: carve where it exceeds {@link #chamberThreshold}. */
	double chamberField(int x, int y, int z) {
		return caveC.fbm3(x / 320.0, y / 105.0, z / 320.0, 3);
	}

	double chamberThreshold(int y, Column c) {
		// three bands of halls: shallow, the main one around y 0 and a deep one near y -85
		double band = Math.exp(-sq((y + 5) / 55.0)) * 0.12 + Math.exp(-sq((y - 70) / 60.0)) * 0.06 + Math.exp(-sq((y + 85) / 30.0)) * 0.10;
		Style st = c.style >= 0 ? Style.VALUES[c.style] : Style.SAKURA;
		double mountain = (st == Style.ALPS || st == Style.SUGI || st == Style.MOMIJI || st == Style.KARST) ? 0.05 * smooth(120, 300, c.height - SEA) : 0;
		return 0.33 - band - mountain;
	}

	/**
	 * Giant halls with stone pillars, and lava tubes, as a density (positive = keep stone, negative = carve).
	 * Only matters well below the surface.
	 */
	public double cavern(int x, int y, int z, Column c) {
		double depth = c.height - y;
		if (depth < 26 || y < WORLD_MIN + 6) return 1;
		double fade = smooth(26, 60, depth) * smooth(WORLD_MIN + 6, WORLD_MIN + 26, y);
		double ch = chamberField(x, y, z);
		double v = Math.min(1, (chamberThreshold(y, c) - ch) * 9);
		if (v < 0.6) {
			// pillars: hourglass columns that hold up the ceiling
			final double cell = 34;
			long cx = (long) Math.floor(x / cell), cz = (long) Math.floor(z / cell);
			for (long i = cx - 1; i <= cx + 1; i++) {
				for (long j = cz - 1; j <= cz + 1; j++) {
					if (hash01(i, j, 31) > 0.45) continue;
					double px = (i + 0.2 + 0.6 * hash01(i, j, 32)) * cell, pz = (j + 0.2 + 0.6 * hash01(i, j, 33)) * cell;
					double r0 = 2.5 + 4.5 * hash01(i, j, 34);
					double r = r0 * (1 + 0.38 * Math.sin(y / 9.0 + hash01(i, j, 35) * 6.28)) + 0.8 * relA.n2(x / 5.0, z / 5.0 + y / 7.0);
					double dd = Math.hypot(x - px, z - pz);
					if (dd < r + 2) v = Math.max(v, (r - dd) * 1.5);
				}
			}
		}
		// lava tubes under Fuji: long, flat, winding tunnels a few blocks under the cone surface
		if (c.zone == ZONE_ISLAND && c.fujiR < FUJI_FOOT && depth < 90) {
			double t = Math.abs(caveA.n3(x / 95.0, y / 26.0, z / 95.0));
			double t2 = Math.abs(caveB.n3(x / 95.0 + 50, y / 26.0, z / 95.0));
			double tube = Math.max(t, t2);
			v = Math.min(v, (tube - 0.1) * 12);
		}
		return 1 + (v - 1) * fade;
	}

	/**
	 * Terrain density around the surface: positive is stone. Roughly (height - y) / 8 like vanilla's
	 * sloped cheese, so vanilla cave thresholds keep working; styles with overhangs bend the surface in 3D.
	 * Floating karst rocks are added on top.
	 */
	public double terrainDensity(int x, int y, int z, Column c) {
		double h = c.height;
		double amp = c.overhang;
		if (amp > 0.5) {
			double band = amp * 2.4;
			double dy = Math.abs(y - h);
			if (dy < band) {
				double n = overhangN.fbm3(x / 34.0, y / 22.0, z / 34.0, 2);
				h += n * amp * 0.8 * (1 - dy / band);
			}
		}
		double g = (h - y) / 8.0;
		if (!Float.isNaN(c.floatTop) && y > c.floatBot - 12 && y < c.floatTop + 12) {
			double isl = Math.min(c.floatTop - y, y - c.floatBot) / 8.0;
			if (isl > -1) isl += 0.25 * overhangN.n3(x / 8.0, y / 6.0, z / 8.0);
			g = Math.max(g, isl);
		}
		return g;
	}

	/** Positive where aquifers should stay dry: deep inside mountains above sea level. */
	public double aquiferExclusion(int y, Column c) {
		return (c.height - y > 50 && y > SEA + 30) ? 1 : -1;
	}

	/** Water surface of a lake/river at this column, or NaN. */
	public double waterLevel(int x, int z) {
		return column(x, z).water;
	}

	public double spawnScore(int x, int z) {
		double d = Math.hypot(x - spawnX, z - spawnZ);
		return Math.exp(-sq(d / 650.0));
	}

	private int[] findSpawn() {
		double a = northTh + Math.PI;
		int[] best = {(int) fujiX, (int) (fujiZ + 1000)};
		double bestScore = -1e9;
		for (int k = -10; k <= 10; k++) {
			for (double rad = 940; rad <= 1160; rad += 25) {
				double aa = a + k * 0.06;
				int x = (int) (fujiX + Math.cos(aa) * rad), z = (int) (fujiZ + Math.sin(aa) * rad);
				Column c = column(x, z);
				if (c.zone != ZONE_ISLAND || c.coast < 45 || !Double.isNaN(c.water) || c.riverDist < 25) continue;
				double s = -Math.abs(c.height - (SEA + 22)) - Math.abs(k) * 2;
				if (s > bestScore) { bestScore = s; best = new int[]{x, z}; }
			}
		}
		return best;
	}

	public int isletCount() {
		return isletCount;
	}
}
