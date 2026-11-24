import gg.phase.world.gen.layout.PB;
import gg.phase.world.gen.layout.WorldLayout;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.stream.IntStream;

/**
 * Offline map preview: java Preview <seed> <out.png> [size=1200] [extent=3000] [cx] [cz]
 * Colors follow the biome, with hillshading; lakes/rivers in light blue.
 */
public class Preview {
	static int biomeColor(PB b) {
		switch (b) {
			case DEEP_OCEAN: return 0x101d3d;
			case OCEAN: return 0x1b2f5e;
			case INNER_SEA: return 0x214072;
			case BEACH: return 0xd8cf9a;
			case BLACK_SAND_BEACH: return 0x3d3a3a;
			case ROCKY_SHORE: return 0x8a8a84;
			case RIVER: return 0x3d7fd6;
			case SAKURA_VALLEY: return 0xd99bc4;
			case MOMIJI_HIGHLANDS: return 0xb4482c;
			case SUGI_FOREST: return 0x1f4a2a;
			case BAMBOO_GROVE: return 0x7fae3e;
			case RICE_TERRACES: return 0x9cc25a;
			case TEA_HILLS: return 0x4f9a42;
			case ALPINE_PEAKS: return 0x8d8f93;
			case SNOWY_HIGHLANDS: return 0xdfe7ea;
			case ONSEN_VALLEY: return 0xa69a6a;
			case PINE_CLIFFS: return 0x5a7a4a;
			case REED_MARSH: return 0x6f7d43;
			case SAND_DUNES: return 0xe0a35a;
			case FROZEN_SUMMITS: return 0xf4f7fa;
			case JUKAI_FOREST: return 0x234f2a;
			case FUJI_SUBALPINE: return 0x55704a;
			case FUJI_SLOPES: return 0x5b4a45;
			case FUJI_SUMMIT: return 0xfafafa;
			case WISTERIA_GROVE: return 0x9b6fd0;
			case SPIRIT_FOREST: return 0x1d6b5a;
			case GINKGO_GROVE: return 0xe8c23a;
			case HYDRANGEA_HILLS: return 0x5a7fd6;
			case HIGANBANA_FIELDS: return 0xd42a2a;
			case LOTUS_MARSH: return 0x8fb06f;
			case KARST_SPIRES: return 0x6e7d6a;
			case CORAL_COAST: return 0x2fc0c8;
			case JIGOKU_WASTES: return 0xc9b14a;
			case SHIRAKABA_PLATEAU: return 0xc8d8b0;
			case ALPINE_MEADOW: return 0x9ccf6a;
			default: return 0xff00ff;
		}
	}

	public static void main(String[] args) throws Exception {
		long seed = Long.parseLong(args[0]);
		String out = args[1];
		int size = args.length > 2 ? Integer.parseInt(args[2]) : 1200;
		double extent = args.length > 3 ? Double.parseDouble(args[3]) : 3000;
		double ox = args.length > 4 ? Double.parseDouble(args[4]) : 0;
		double oz = args.length > 5 ? Double.parseDouble(args[5]) : 0;
		long t0 = System.nanoTime();
		WorldLayout L = WorldLayout.get(seed);
		long t1 = System.nanoTime();
		double scale = extent * 2 / size;
		float[] h = new float[size * size];
		int[] biome = new int[size * size];
		float[] water = new float[size * size];
		IntStream.range(0, size).parallel().forEach(j -> {
			for (int i = 0; i < size; i++) {
				int x = (int) Math.round(ox - extent + i * scale);
				int z = (int) Math.round(oz - extent + j * scale);
				WorldLayout.Column c = L.column(x, z);
				h[j * size + i] = c.height;
				biome[j * size + i] = c.surface;
				water[j * size + i] = c.water;
			}
		});
		long t2 = System.nanoTime();
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		for (int j = 0; j < size; j++) {
			for (int i = 0; i < size; i++) {
				int k = j * size + i;
				PB b = PB.byId(biome[k]);
				int col = biomeColor(b);
				float hh = h[k];
				boolean wet = !Float.isNaN(water[k]) && water[k] > hh;
				if (wet) col = 0x4a8fe0;
				double shade = 1;
				if (hh > 62 && !wet) {
					float hl = h[j * size + Math.max(0, i - 1)], hu = h[Math.max(0, j - 1) * size + i];
					double dx = (hh - hl) / scale, dz = (hh - hu) / scale;
					shade = 1 + Math.max(-0.55, Math.min(0.45, -(dx + dz) * 0.9));
					shade *= 0.85 + 0.25 * Math.min(1, (hh - 62) / 600.0);
				} else if (hh <= 62 && !wet) {
					shade = 0.75 + 0.25 * Math.max(0, Math.min(1, (hh + 10) / 72.0));
				}
				int r = (int) Math.min(255, ((col >> 16) & 255) * shade);
				int g = (int) Math.min(255, ((col >> 8) & 255) * shade);
				int bl = (int) Math.min(255, (col & 255) * shade);
				img.setRGB(i, j, (r << 16) | (g << 8) | bl);
			}
		}
		// spawn marker
		int sx = (int) ((L.spawnX - ox + extent) / scale), sz = (int) ((L.spawnZ - oz + extent) / scale);
		for (int a = -3; a <= 3; a++) for (int b = -3; b <= 3; b++)
			if (sx + a >= 0 && sx + a < size && sz + b >= 0 && sz + b < size && Math.abs(a) + Math.abs(b) <= 3) img.setRGB(sx + a, sz + b, 0xff3030);
		ImageIO.write(img, "png", new File(out));
		float max = -1e9f;
		for (float v : h) max = Math.max(max, v);
		System.out.printf("seed %d: layout %.0f ms, sample %.0f ms (%.2f us/column), max height %.0f, islets %d, lakes %d, rivers %d, spawn %d %d%n",
			seed, (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t2 - t1) / 1e3 / (size * size), max, L.isletCount(), L.lakes.size(), L.rivers.size(), L.spawnX, L.spawnZ);
	}
}
