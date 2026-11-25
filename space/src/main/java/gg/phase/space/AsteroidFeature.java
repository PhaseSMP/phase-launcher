package gg.phase.space;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * Настоящие астероиды из блоков в мире космоса — на них можно сесть и копать.
 *
 * Плотность зависит от расстояния до Солнца: у Солнца пусто, в главном поясе (5400–6600) густо,
 * между планетами редкие глыбы, за орбитой Плутона — пояс Койпера из льда. Орбиты планетных систем
 * всегда чистые, чтобы планеты и спутники не пролетали сквозь камни.
 *
 * Типы: каменный (руды в ядре, алмазы редко и глубоко), металлический (сырое железо, золото),
 * углистый (уголь, обсидиан), ледяной и редкий жеод с аметистами внутри.
 */
public record AsteroidFeature() implements Feature {
	public static final MapCodec<AsteroidFeature> CODEC = MapCodec.unit(AsteroidFeature::new);

	private enum Kind { ROCK, METAL, CARBON, ICE, GEODE, METEORITE }

	@Override
	public MapCodec<AsteroidFeature> codec() {
		return CODEC;
	}

	@Override
	public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
		int cx = origin.getX() & ~15, cz = origin.getZ() & ~15;
		double r = Math.sqrt((cx + 8.0) * (cx + 8.0) + (cz + 8.0) * (cz + 8.0));
		if (r < 1700 || r > 27000) return false;                    // у Солнца всё расплавилось, в дальнем космосе пусто
		if (SolarSystem.nearOrbit(r, 60)) return false;

		boolean belt = r > SolarSystem.BELT_INNER && r < SolarSystem.BELT_OUTER;
		boolean kuiper = r > SolarSystem.KUIPER_INNER && r < SolarSystem.KUIPER_OUTER;
		double chance, spread;
		int minR, maxR;
		if (belt) {
			double edge = Math.min(r - SolarSystem.BELT_INNER, SolarSystem.BELT_OUTER - r) / 150.0; // края пояса реже
			chance = 0.55 * Math.min(1, 0.35 + edge);
			minR = 3; maxR = 9; spread = 140;
		} else if (kuiper) {
			chance = 0.16;
			minR = 3; maxR = 9; spread = 320;
		} else {
			chance = 0.022;
			minR = 2; maxR = 6; spread = 380;
		}
		if (random.nextDouble() >= chance) return false;
		int count = 1 + (belt && random.nextDouble() < 0.45 ? 1 : 0) + (belt && random.nextDouble() < 0.15 ? 1 : 0);
		int minY = level.getMinY() + 24, maxY = level.getMinY() + level.getHeight() - 24;
		boolean placed = false;
		for (int n = 0; n < count; n++) {
			int radius = minR + random.nextInt(maxR - minR + 1);
			int x = cx + random.nextInt(16), z = cz + random.nextInt(16);
			int y = (int) Math.round(SolarSystem.PLANE_Y + random.nextGaussian() * spread);
			y = Math.max(minY + radius, Math.min(maxY - radius, y));
			Kind kind = pick(random, r, belt, kuiper);
			placed |= build(level, random, new BlockPos(x, y, z), radius, kind);
		}
		return placed;
	}

	/** Метеорит из потока (событие): небольшая оплавленная глыба с магмой внутри и рудой */
	public static void meteorite(WorldGenLevel level, RandomSource random, BlockPos at) {
		build(level, random, at, 2 + random.nextInt(2), Kind.METEORITE);
	}

	private static Kind pick(RandomSource random, double r, boolean belt, boolean kuiper) {
		double v = random.nextDouble();
		if (kuiper) return v < 0.85 ? Kind.ICE : Kind.ROCK;
		if (r > 12000) return v < 0.6 ? Kind.ICE : v < 0.85 ? Kind.ROCK : Kind.CARBON;
		if (belt) return v < 0.52 ? Kind.ROCK : v < 0.72 ? Kind.METAL : v < 0.93 ? Kind.CARBON : Kind.GEODE;
		return v < 0.65 ? Kind.ROCK : v < 0.9 ? Kind.CARBON : Kind.METAL;
	}

	/** Бесформенная глыба из 2–4 сплюснутых шаров с шероховатой поверхностью */
	private static boolean build(WorldGenLevel level, RandomSource random, BlockPos c, int radius, Kind kind) {
		int lobes = 2 + random.nextInt(3);
		double[][] lobe = new double[lobes][4];
		for (int i = 0; i < lobes; i++) {
			double k = i == 0 ? 0 : 0.5;
			lobe[i][0] = (random.nextDouble() * 2 - 1) * radius * k;
			lobe[i][1] = (random.nextDouble() * 2 - 1) * radius * k * 0.7;
			lobe[i][2] = (random.nextDouble() * 2 - 1) * radius * k;
			lobe[i][3] = radius * (i == 0 ? 1.0 : 0.55 + random.nextDouble() * 0.35);
		}
		double sx = 0.75 + random.nextDouble() * 0.5, sy = 0.65 + random.nextDouble() * 0.4, sz = 0.75 + random.nextDouble() * 0.5;
		long salt = random.nextLong();
		int ext = (int) Math.ceil(radius * 1.6) + 1;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		boolean any = false;
		for (int dx = -ext; dx <= ext; dx++) {
			for (int dy = -ext; dy <= ext; dy++) {
				for (int dz = -ext; dz <= ext; dz++) {
					double v = 9;
					for (double[] l : lobe) {
						double ax = (dx - l[0]) / sx, ay = (dy - l[1]) / sy, az = (dz - l[2]) / sz;
						v = Math.min(v, Math.sqrt(ax * ax + ay * ay + az * az) / l[3]);
					}
					double jitter = (hash(salt, dx, dy, dz) - 0.5) * 0.28;
					if (v + jitter >= 1.0) continue;
					double depth = 1.0 - v;
					BlockState s = material(kind, depth, hash(salt + 77, dx, dy, dz), hash(salt + 991, dx >> 1, dy >> 1, dz >> 1));
					if (s.isAir()) continue;
					p.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
					if (!level.getBlockState(p).isAir()) continue;
					level.setBlock(p, s, 2);
					any = true;
				}
			}
		}
		return any;
	}

	private static BlockState material(Kind kind, double depth, double h, double patch) {
		switch (kind) {
			case METAL -> {
				if (depth < 0.22) return patch < 0.5 ? Blocks.SMOOTH_BASALT.defaultBlockState() : Blocks.TUFF.defaultBlockState();
				if (h < 0.07) return Blocks.RAW_IRON_BLOCK.defaultBlockState();
				if (h < 0.22) return Blocks.DEEPSLATE_IRON_ORE.defaultBlockState();
				if (h < 0.28) return Blocks.DEEPSLATE_COPPER_ORE.defaultBlockState();
				if (h < 0.305 && depth > 0.45) return Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState();
				return Blocks.DEEPSLATE.defaultBlockState();
			}
			case CARBON -> {
				if (depth < 0.25) return patch < 0.6 ? Blocks.BLACKSTONE.defaultBlockState() : Blocks.BASALT.defaultBlockState();
				if (h < 0.11) return Blocks.DEEPSLATE_COAL_ORE.defaultBlockState();
				if (h < 0.14) return Blocks.OBSIDIAN.defaultBlockState();
				return patch < 0.5 ? Blocks.BLACKSTONE.defaultBlockState() : Blocks.DEEPSLATE.defaultBlockState();
			}
			case ICE -> {
				if (depth < 0.18) return patch < 0.35 ? Blocks.SNOW_BLOCK.defaultBlockState() : Blocks.PACKED_ICE.defaultBlockState();
				if (h < 0.04) return Blocks.ANDESITE.defaultBlockState();
				return patch < 0.45 ? Blocks.BLUE_ICE.defaultBlockState() : Blocks.PACKED_ICE.defaultBlockState();
			}
			case METEORITE -> {
				if (depth < 0.3) return patch < 0.5 ? Blocks.BLACKSTONE.defaultBlockState() : patch < 0.8 ? Blocks.BASALT.defaultBlockState() : Blocks.OBSIDIAN.defaultBlockState();
				if (depth > 0.75) return Blocks.MAGMA_BLOCK.defaultBlockState();
				if (h < 0.18) return Blocks.RAW_IRON_BLOCK.defaultBlockState();
				if (h < 0.3) return Blocks.DEEPSLATE_IRON_ORE.defaultBlockState();
				if (h < 0.36) return Blocks.RAW_GOLD_BLOCK.defaultBlockState();
				if (h < 0.39) return Blocks.ANCIENT_DEBRIS.defaultBlockState();
				if (h < 0.42) return Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState();
				return Blocks.MAGMA_BLOCK.defaultBlockState();
			}
			case GEODE -> {
				if (depth < 0.16) return Blocks.SMOOTH_BASALT.defaultBlockState();
				if (depth < 0.28) return Blocks.CALCITE.defaultBlockState();
				if (depth < 0.42) return h < 0.1 ? Blocks.BUDDING_AMETHYST.defaultBlockState() : Blocks.AMETHYST_BLOCK.defaultBlockState();
				return Blocks.AIR.defaultBlockState();
			}
			default -> {
				if (depth < 0.24) {
					if (patch < 0.38) return Blocks.ANDESITE.defaultBlockState();
					if (patch < 0.68) return Blocks.STONE.defaultBlockState();
					if (patch < 0.9) return Blocks.TUFF.defaultBlockState();
					return Blocks.GRAVEL.defaultBlockState();
				}
				if (h < 0.04) return Blocks.DEEPSLATE_COAL_ORE.defaultBlockState();
				if (h < 0.085) return Blocks.DEEPSLATE_IRON_ORE.defaultBlockState();
				if (h < 0.115) return Blocks.DEEPSLATE_COPPER_ORE.defaultBlockState();
				if (h < 0.123) return Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState();
				if (h < 0.13) return Blocks.DEEPSLATE_LAPIS_ORE.defaultBlockState();
				if (h < 0.1335 && depth > 0.55) return Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState();
				return patch < 0.85 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.COBBLED_DEEPSLATE.defaultBlockState();
			}
		}
	}

	private static double hash(long seed, int x, int y, int z) {
		long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L);
		h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
		h = (h ^ (h >>> 29)) * 0x94D049BB133111EBL;
		h ^= h >>> 32;
		return (h >>> 11) * 0x1.0p-53;
	}
}
