package gg.phase.world.gen.layout;

/**
 * Seeded gradient noise (improved Perlin) in 2D and 3D, plus fBm / ridged helpers.
 * Pure Java, no Minecraft dependencies, so the layout can be previewed outside the game.
 */
public final class Noise {
	private final int[] perm = new int[512];
	private final double ox, oy, oz;

	public Noise(long seed) {
		SplitMix r = new SplitMix(seed);
		int[] p = new int[256];
		for (int i = 0; i < 256; i++) p[i] = i;
		for (int i = 255; i > 0; i--) {
			int j = (int) Math.floorMod(r.next(), (long) (i + 1));
			int t = p[i]; p[i] = p[j]; p[j] = t;
		}
		for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
		ox = r.nextDouble() * 256.0;
		oy = r.nextDouble() * 256.0;
		oz = r.nextDouble() * 256.0;
	}

	private static double fade(double t) {
		return t * t * t * (t * (t * 6 - 15) + 10);
	}

	private static double lerp(double t, double a, double b) {
		return a + t * (b - a);
	}

	private static double grad2(int h, double x, double y) {
		switch (h & 7) {
			case 0: return x + y;
			case 1: return -x + y;
			case 2: return x - y;
			case 3: return -x - y;
			case 4: return x;
			case 5: return -x;
			case 6: return y;
			default: return -y;
		}
	}

	private static double grad3(int h, double x, double y, double z) {
		int hh = h & 15;
		double u = hh < 8 ? x : y;
		double v = hh < 4 ? y : (hh == 12 || hh == 14 ? x : z);
		return ((hh & 1) == 0 ? u : -u) + ((hh & 2) == 0 ? v : -v);
	}

	/** 2D noise, roughly in [-1, 1]. */
	public double n2(double x, double y) {
		x += ox; y += oy;
		double fx = Math.floor(x), fy = Math.floor(y);
		int X = ((int) fx) & 255, Y = ((int) fy) & 255;
		x -= fx; y -= fy;
		double u = fade(x), v = fade(y);
		int a = perm[X] + Y, b = perm[X + 1] + Y;
		double r = lerp(v,
			lerp(u, grad2(perm[a], x, y), grad2(perm[b], x - 1, y)),
			lerp(u, grad2(perm[a + 1], x, y - 1), grad2(perm[b + 1], x - 1, y - 1)));
		return r * 0.7071;
	}

	/** 3D noise, roughly in [-1, 1]. */
	public double n3(double x, double y, double z) {
		x += ox; y += oy; z += oz;
		double fx = Math.floor(x), fy = Math.floor(y), fz = Math.floor(z);
		int X = ((int) fx) & 255, Y = ((int) fy) & 255, Z = ((int) fz) & 255;
		x -= fx; y -= fy; z -= fz;
		double u = fade(x), v = fade(y), w = fade(z);
		int A = perm[X] + Y, AA = perm[A] + Z, AB = perm[A + 1] + Z;
		int B = perm[X + 1] + Y, BA = perm[B] + Z, BB = perm[B + 1] + Z;
		return lerp(w,
			lerp(v, lerp(u, grad3(perm[AA], x, y, z), grad3(perm[BA], x - 1, y, z)),
				lerp(u, grad3(perm[AB], x, y - 1, z), grad3(perm[BB], x - 1, y - 1, z))),
			lerp(v, lerp(u, grad3(perm[AA + 1], x, y, z - 1), grad3(perm[BA + 1], x - 1, y, z - 1)),
				lerp(u, grad3(perm[AB + 1], x, y - 1, z - 1), grad3(perm[BB + 1], x - 1, y - 1, z - 1))));
	}

	/** Fractal sum of 2D noise, normalised to roughly [-1, 1]. */
	public double fbm2(double x, double y, int octaves) {
		double sum = 0, amp = 1, norm = 0, f = 1;
		for (int i = 0; i < octaves; i++) {
			sum += amp * n2(x * f + i * 31.7, y * f - i * 17.3);
			norm += amp;
			amp *= 0.5;
			f *= 2.03;
		}
		return sum / norm;
	}

	public double fbm3(double x, double y, double z, int octaves) {
		double sum = 0, amp = 1, norm = 0, f = 1;
		for (int i = 0; i < octaves; i++) {
			sum += amp * n3(x * f + i * 31.7, y * f + i * 7.1, z * f - i * 17.3);
			norm += amp;
			amp *= 0.5;
			f *= 2.03;
		}
		return sum / norm;
	}

	/** Ridged multifractal in [0, 1]: sharp crests, wide valleys. */
	public double ridged2(double x, double y, int octaves) {
		double sum = 0, amp = 1, norm = 0, f = 1, weight = 1;
		for (int i = 0; i < octaves; i++) {
			double n = 1.0 - Math.abs(n2(x * f + i * 13.1, y * f + i * 7.7));
			n *= n;
			n *= weight;
			weight = Math.min(1.0, Math.max(0.0, n * 1.6));
			sum += n * amp;
			norm += amp;
			amp *= 0.5;
			f *= 2.1;
		}
		return sum / norm;
	}

	/** Small, fast deterministic generator used for seeding. */
	public static final class SplitMix {
		private long state;

		public SplitMix(long seed) {
			this.state = seed;
		}

		public long next() {
			long z = (state += 0x9E3779B97F4A7C15L);
			z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
			z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
			return z ^ (z >>> 31);
		}

		public double nextDouble() {
			return (next() >>> 11) * 0x1.0p-53;
		}

		public double range(double a, double b) {
			return a + nextDouble() * (b - a);
		}

		public int nextInt(int bound) {
			return (int) Math.floorMod(next(), (long) bound);
		}
	}
}
