package gg.phase.world.gen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Big hand-shaped trees: ancient cedars, spirit trees with glowing curtains, weeping cherries,
 * wisteria, camphor and ginkgo giants. Everything stays within 15 blocks of the trunk so the tree
 * never writes outside the chunks a feature may touch.
 * <p>
 * JSON: {@code {"type": "phaseworld:giant_tree", "shape": "sugi", "log": {...}, "leaves": {...},
 * "min_height": 30, "max_height": 50, "hanging": {...}, "decor": {...}}}
 */
public record GiantTreeFeature(String shape, BlockState log, BlockState leaves, int minHeight, int maxHeight,
							   Optional<BlockState> hanging, Optional<BlockState> decor) implements Feature {
	public static final MapCodec<GiantTreeFeature> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		Codec.STRING.fieldOf("shape").forGetter(GiantTreeFeature::shape),
		BlockState.CODEC.fieldOf("log").forGetter(GiantTreeFeature::log),
		BlockState.CODEC.fieldOf("leaves").forGetter(GiantTreeFeature::leaves),
		Codec.INT.fieldOf("min_height").forGetter(GiantTreeFeature::minHeight),
		Codec.INT.fieldOf("max_height").forGetter(GiantTreeFeature::maxHeight),
		BlockState.CODEC.optionalFieldOf("hanging").forGetter(GiantTreeFeature::hanging),
		BlockState.CODEC.optionalFieldOf("decor").forGetter(GiantTreeFeature::decor)
	).apply(i, GiantTreeFeature::new));

	private static final int REACH = 15;

	@Override
	public MapCodec<GiantTreeFeature> codec() {
		return CODEC;
	}

	@Override
	public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
		BlockState ground = level.getBlockState(origin.below());
		if (!ground.is(BlockTags.DIRT) && !ground.is(Blocks.SNOW_BLOCK)) return false;
		if (!level.getBlockState(origin).isAir() && !level.getBlockState(origin).canBeReplaced()) return false;
		int h = this.minHeight + random.nextInt(Math.max(1, this.maxHeight - this.minHeight + 1));
		if (origin.getY() + h + 14 >= level.getMaxY()) return false;
		Builder b = new Builder(level, random, origin);
		switch (this.shape) {
			case "sugi" -> sugi(b, h, random);
			case "spirit" -> spirit(b, h, random);
			case "weeping" -> weeping(b, h, random);
			case "wisteria" -> wisteria(b, h, random);
			case "ginkgo" -> ginkgo(b, h, random);
			default -> broad(b, h, random);
		}
		b.commit(this.log, this.leaves);
		// curtains: from leaves with air below
		BlockState curtain = this.hanging.orElse(null);
		if (curtain == null && this.shape.equals("weeping")) curtain = this.leaves.setValue(LeavesBlock.PERSISTENT, true);
		if (curtain != null) {
			int chance = this.shape.equals("spirit") ? 5 : this.shape.equals("sugi") ? 30 : 3;
			b.curtains(curtain, chance, this.shape.equals("weeping") ? 9 : 10);
		}
		this.decor.ifPresent(d -> b.decorGround(d, this.shape.equals("spirit") ? 26 : 12));
		return true;
	}

	// ------------------------------------------------------------------ shapes

	private static void roots(Builder b, double r, int count, double len, RandomSource random) {
		for (int k = 0; k < count; k++) {
			double a = (k + random.nextDouble() * 0.6) * Math.PI * 2 / count;
			double l = len * (0.6 + 0.4 * random.nextDouble());
			for (double t = 0; t < l; t += 0.5) {
				double rr = r * 0.6 + t;
				double y = 2.5 - t * 0.55;
				b.disc(Math.cos(a) * rr, y, Math.sin(a) * rr, 1.3 * (1 - t / l) + 0.5, true);
			}
		}
	}

	private static void trunk(Builder b, int h, double r0, double r1, double flare, RandomSource random) {
		double lx = (random.nextDouble() - 0.5) * 0.08, lz = (random.nextDouble() - 0.5) * 0.08;
		double ph = random.nextDouble() * 6.28;
		for (int y = -1; y <= h; y++) {
			double t = Math.max(0, (double) y / h);
			double r = r0 + (r1 - r0) * t + flare * Math.exp(-Math.max(0, y) / 2.5);
			double ox = lx * y + 0.6 * Math.sin(y / 6.0 + ph), oz = lz * y + 0.6 * Math.cos(y / 7.0 + ph);
			b.disc(ox, y, oz, r, y <= 0);
			b.tx = ox;
			b.tz = oz;
		}
	}

	private static void branch(Builder b, double x, double y, double z, double dx, double dy, double dz, double len, double r0) {
		double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
		dx /= l; dy /= l; dz /= l;
		for (double t = 0; t <= len; t += 0.5) {
			double r = r0 * (1 - 0.6 * t / len);
			b.discAxis(x + dx * t, y + dy * t, z + dz * t, r, dx, dy, dz);
		}
	}

	private void sugi(Builder b, int h, RandomSource random) {
		double r0 = 2.0 + random.nextDouble() * 0.9;
		roots(b, r0, 6 + random.nextInt(3), 6, random);
		trunk(b, h, r0, 0.7, 1.4, random);
		for (int y = (int) (h * 0.32); y <= h; y += 2 + random.nextInt(2)) {
			double t = (double) (y - h * 0.32) / (h * 0.68);
			double len = 2.5 + 5.5 * (1 - t);
			int n = 3 + random.nextInt(2);
			double a0 = random.nextDouble() * 6.28;
			for (int k = 0; k < n; k++) {
				double a = a0 + k * 6.28 / n + random.nextDouble() * 0.5;
				double ex = b.tx + Math.cos(a) * len, ez = b.tz + Math.sin(a) * len;
				branch(b, b.tx, y, b.tz, Math.cos(a), 0.25, Math.sin(a), len, 0.6);
				b.blob(ex, y + 1, ez, 2.4 + 1.3 * (1 - t), 1.6, 2.4 + 1.3 * (1 - t), 0.9);
			}
			b.blob(b.tx, y + 1, b.tz, 2.2 + 2.6 * (1 - t), 1.4, 2.2 + 2.6 * (1 - t), 0.85);
		}
		b.blob(b.tx, h + 2, b.tz, 1.8, 3.2, 1.8, 1.0);
	}

	private void spirit(Builder b, int h, RandomSource random) {
		double r0 = 3.0 + random.nextDouble() * 1.0;
		roots(b, r0 + 1, 7 + random.nextInt(3), 9, random);
		int split = (int) (h * 0.55);
		trunk(b, split, r0, r0 * 0.7, 2.6, random);
		int limbs = 3 + random.nextInt(2);
		double a0 = random.nextDouble() * 6.28;
		for (int k = 0; k < limbs; k++) {
			double a = a0 + k * 6.28 / limbs + random.nextDouble() * 0.6;
			double reach = 5 + random.nextDouble() * 3;
			double up = h - split;
			branch(b, b.tx, split, b.tz, Math.cos(a) * reach, up, Math.sin(a) * reach, Math.sqrt(reach * reach + up * up), 1.9);
			double ex = b.tx + Math.cos(a) * reach, ez = b.tz + Math.sin(a) * reach;
			b.blob(ex, h + 1, ez, 7.0, 3.2, 7.0, 0.92);
			b.blob(ex * 0.6, h + 3, ez * 0.6, 5.0, 2.6, 5.0, 0.9);
			// side limbs
			double sa = a + (random.nextBoolean() ? 0.9 : -0.9);
			double sy = split + up * 0.45;
			double sx = b.tx + Math.cos(a) * reach * 0.45, sz = b.tz + Math.sin(a) * reach * 0.45;
			branch(b, sx, sy, sz, Math.cos(sa), 0.35, Math.sin(sa), 5, 0.9);
			b.blob(sx + Math.cos(sa) * 5, sy + 2, sz + Math.sin(sa) * 5, 3.5, 2.0, 3.5, 0.9);
		}
		b.blob(b.tx, h + 4, b.tz, 6.0, 3.0, 6.0, 0.9);
	}

	private void broad(Builder b, int h, RandomSource random) {
		double r0 = 1.4 + random.nextDouble() * 0.8;
		roots(b, r0, 5, 4, random);
		int split = (int) (h * (0.38 + random.nextDouble() * 0.1));
		trunk(b, split, r0, r0 * 0.8, 1.0, random);
		int n = 5 + random.nextInt(3);
		double a0 = random.nextDouble() * 6.28;
		for (int k = 0; k < n; k++) {
			double a = a0 + k * 6.28 / n + random.nextDouble() * 0.5;
			double reach = 4 + random.nextDouble() * 4.5;
			double up = (h - split) * (0.55 + random.nextDouble() * 0.45);
			branch(b, b.tx, split, b.tz, Math.cos(a) * reach, up, Math.sin(a) * reach, Math.sqrt(reach * reach + up * up), 0.9);
			b.blob(b.tx + Math.cos(a) * reach, split + up + 1, b.tz + Math.sin(a) * reach, 4.0 + random.nextDouble() * 1.8, 2.6, 4.0 + random.nextDouble() * 1.8, 0.9);
		}
		b.blob(b.tx, h, b.tz, 4.5, 2.8, 4.5, 0.9);
	}

	private void ginkgo(Builder b, int h, RandomSource random) {
		double r0 = 1.0 + random.nextDouble() * 0.6;
		roots(b, r0, 4, 3, random);
		trunk(b, h, r0, 0.5, 0.8, random);
		for (int y = (int) (h * 0.3); y < h; y += 3) {
			double t = (y - h * 0.3) / (h * 0.7);
			double w = 3.2 + 2.8 * Math.sin(Math.PI * Math.min(1, t * 1.15));
			double a = random.nextDouble() * 6.28;
			branch(b, b.tx, y, b.tz, Math.cos(a), 0.9, Math.sin(a), w * 0.8, 0.5);
			b.blob(b.tx, y + 1.5, b.tz, w, 2.2, w, 0.8);
		}
		b.blob(b.tx, h + 1, b.tz, 2.4, 2.4, 2.4, 0.95);
	}

	private void weeping(Builder b, int h, RandomSource random) {
		double r0 = 1.2 + random.nextDouble() * 0.5;
		roots(b, r0, 4, 3, random);
		int split = (int) (h * 0.45);
		trunk(b, split, r0, r0 * 0.85, 0.8, random);
		int n = 5 + random.nextInt(3);
		double a0 = random.nextDouble() * 6.28;
		for (int k = 0; k < n; k++) {
			double a = a0 + k * 6.28 / n;
			double reach = 4.5 + random.nextDouble() * 2.5;
			double up = h - split;
			branch(b, b.tx, split, b.tz, Math.cos(a) * reach, up, Math.sin(a) * reach, Math.sqrt(reach * reach + up * up), 0.7);
			b.blob(b.tx + Math.cos(a) * (reach + 1), h, b.tz + Math.sin(a) * (reach + 1), 3.4, 1.8, 3.4, 0.85);
		}
		b.blob(b.tx, h + 1, b.tz, 6.0, 2.4, 6.0, 0.9);
	}

	private void wisteria(Builder b, int h, RandomSource random) {
		double r0 = 1.0 + random.nextDouble() * 0.5;
		trunk(b, h, r0, 0.7, 0.6, random);
		int n = 4 + random.nextInt(3);
		double a0 = random.nextDouble() * 6.28;
		for (int k = 0; k < n; k++) {
			double a = a0 + k * 6.28 / n;
			double reach = 5 + random.nextDouble() * 3;
			branch(b, b.tx, h - 1, b.tz, Math.cos(a), 0.15, Math.sin(a), reach, 0.6);
			b.blob(b.tx + Math.cos(a) * reach, h + 0.5, b.tz + Math.sin(a) * reach, 3.6, 1.4, 3.6, 0.95);
		}
		b.blob(b.tx, h + 1, b.tz, 5.5, 1.6, 5.5, 0.95);
	}

	// ------------------------------------------------------------------ builder

	private static final class Builder {
		final WorldGenLevel level;
		final RandomSource random;
		final BlockPos o;
		final Map<Long, int[]> logs = new LinkedHashMap<>();   // pos -> axis (0 x, 1 y, 2 z) and root flag
		final Map<Long, Boolean> leaves = new LinkedHashMap<>();
		double tx, tz;

		Builder(WorldGenLevel level, RandomSource random, BlockPos o) {
			this.level = level;
			this.random = random;
			this.o = o;
		}

		static long key(int x, int y, int z) {
			return BlockPos.asLong(x, y, z);
		}

		boolean inReach(int dx, int dz) {
			return Math.abs(dx) <= REACH && Math.abs(dz) <= REACH;
		}

		void log(int dx, int dy, int dz, int axis, boolean root) {
			if (!inReach(dx, dz)) return;
			this.logs.put(key(dx, dy, dz), new int[]{axis, root ? 1 : 0});
		}

		void disc(double cx, double y, double cz, double r, boolean root) {
			int iy = (int) Math.floor(y);
			int ir = (int) Math.ceil(r);
			for (int dx = -ir; dx <= ir; dx++) {
				for (int dz = -ir; dz <= ir; dz++) {
					double fx = Math.floor(cx) + dx + 0.5 - cx, fz = Math.floor(cz) + dz + 0.5 - cz;
					if (fx * fx + fz * fz <= r * r + 0.3) log((int) Math.floor(cx) + dx, iy, (int) Math.floor(cz) + dz, 1, root);
				}
			}
		}

		void discAxis(double cx, double cy, double cz, double r, double dx, double dy, double dz) {
			int axis = Math.abs(dy) > 0.7 ? 1 : Math.abs(dx) > Math.abs(dz) ? 0 : 2;
			int ir = (int) Math.ceil(r);
			for (int a = -ir; a <= ir; a++) {
				for (int b = -ir; b <= ir; b++) {
					for (int c = -ir; c <= ir; c++) {
						if (a * a + b * b + c * c > r * r + 0.35) continue;
						log((int) Math.floor(cx + a), (int) Math.floor(cy + b), (int) Math.floor(cz + c), axis, false);
					}
				}
			}
		}

		void blob(double cx, double cy, double cz, double rx, double ry, double rz, double fill) {
			int x0 = (int) Math.floor(cx - rx), x1 = (int) Math.ceil(cx + rx);
			int y0 = (int) Math.floor(cy - ry), y1 = (int) Math.ceil(cy + ry);
			int z0 = (int) Math.floor(cz - rz), z1 = (int) Math.ceil(cz + rz);
			for (int x = x0; x <= x1; x++) {
				for (int y = y0; y <= y1; y++) {
					for (int z = z0; z <= z1; z++) {
						double ex = (x + 0.5 - cx) / rx, ey = (y + 0.5 - cy) / ry, ez = (z + 0.5 - cz) / rz;
						double d = ex * ex + ey * ey + ez * ez;
						if (d > 1) continue;
						if (d > 0.55 && this.random.nextDouble() > fill) continue;
						if (!inReach(x, z)) continue;
						long k = key(x, y, z);
						if (!this.logs.containsKey(k)) this.leaves.put(k, Boolean.TRUE);
					}
				}
			}
		}

		private boolean replaceable(BlockState s, boolean root) {
			if (s.isAir() || s.canBeReplaced() || s.is(BlockTags.LEAVES) || s.is(BlockTags.REPLACEABLE_BY_TREES)) return true;
			return root && (s.is(BlockTags.DIRT) || s.is(Blocks.SNOW_BLOCK) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Blocks.GRAVEL));
		}

		void commit(BlockState log, BlockState leafState) {
			BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
			for (Map.Entry<Long, int[]> e : this.logs.entrySet()) {
				long k = e.getKey();
				p.set(this.o.getX() + BlockPos.getX(k), this.o.getY() + BlockPos.getY(k), this.o.getZ() + BlockPos.getZ(k));
				if (!replaceable(this.level.getBlockState(p), e.getValue()[1] == 1 || BlockPos.getY(k) <= 0)) continue;
				BlockState s = log;
				if (s.hasProperty(BlockStateProperties.AXIS)) {
					s = s.setValue(BlockStateProperties.AXIS, e.getValue()[0] == 0 ? Direction.Axis.X : e.getValue()[0] == 2 ? Direction.Axis.Z : Direction.Axis.Y);
				}
				this.level.setBlock(p, s, 2);
			}
			// leaf distances from the logs, so the canopy decays like a vanilla tree's when cut
			Map<Long, Integer> dist = new HashMap<>();
			ArrayDeque<Long> queue = new ArrayDeque<>();
			for (long k : this.logs.keySet()) {
				dist.put(k, 0);
				queue.add(k);
			}
			int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
			while (!queue.isEmpty()) {
				long k = queue.poll();
				int d = dist.get(k);
				if (d >= 7) continue;
				int x = BlockPos.getX(k), y = BlockPos.getY(k), z = BlockPos.getZ(k);
				for (int[] dv : dirs) {
					long n = key(x + dv[0], y + dv[1], z + dv[2]);
					if (!this.leaves.containsKey(n) || dist.containsKey(n)) continue;
					dist.put(n, d + 1);
					queue.add(n);
				}
			}
			boolean hasDistance = leafState.hasProperty(LeavesBlock.DISTANCE);
			for (long k : this.leaves.keySet()) {
				p.set(this.o.getX() + BlockPos.getX(k), this.o.getY() + BlockPos.getY(k), this.o.getZ() + BlockPos.getZ(k));
				BlockState cur = this.level.getBlockState(p);
				if (!(cur.isAir() || cur.canBeReplaced())) continue;
				BlockState s = leafState;
				if (hasDistance) {
					Integer d = dist.get(k);
					s = d == null || d > 6 ? s.setValue(LeavesBlock.PERSISTENT, true).setValue(LeavesBlock.DISTANCE, 7)
						: s.setValue(LeavesBlock.DISTANCE, Math.max(1, d)).setValue(LeavesBlock.PERSISTENT, false);
				}
				this.level.setBlock(p, s, 2);
			}
		}

		void curtains(BlockState state, int chance, int maxLen) {
			BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
			List<Long> keys = new ArrayList<>(this.leaves.keySet());
			for (long k : keys) {
				if (this.random.nextInt(chance) != 0) continue;
				int x = this.o.getX() + BlockPos.getX(k), y = this.o.getY() + BlockPos.getY(k), z = this.o.getZ() + BlockPos.getZ(k);
				p.set(x, y, z);
				if (!this.level.getBlockState(p).is(BlockTags.LEAVES)) continue;
				int len = 2 + this.random.nextInt(Math.max(1, maxLen - 1));
				for (int i = 1; i <= len; i++) {
					p.set(x, y - i, z);
					if (!this.level.getBlockState(p).isAir()) break;
					this.level.setBlock(p, state, 2);
				}
			}
		}

		void decorGround(BlockState state, int tries) {
			BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
			for (int i = 0; i < tries; i++) {
				int dx = this.random.nextInt(19) - 9, dz = this.random.nextInt(19) - 9;
				for (int dy = 4; dy >= -4; dy--) {
					p.set(this.o.getX() + dx, this.o.getY() + dy, this.o.getZ() + dz);
					if (this.level.getBlockState(p).isAir() && this.level.getBlockState(p.below()).is(BlockTags.DIRT)) {
						if (state.canSurvive(this.level, p)) this.level.setBlock(p, state, 2);
						break;
					}
				}
			}
		}
	}
}
