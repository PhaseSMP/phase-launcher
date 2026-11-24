package gg.phase.world.gen.feature;

import com.mojang.serialization.MapCodec;
import gg.phase.world.gen.LayoutSeeds;
import gg.phase.world.gen.layout.WorldLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.material.Fluids;

/**
 * Fills rivers and lakes that sit above sea level (the ocean and anything below sea level is filled
 * by vanilla aquifers), and makes sure the water stays put:
 * <ul>
 * <li>every water block gets a solid bed under it, even where a cave or a carver ran under the river;</li>
 * <li>a dry neighbour that is lower than the water surface gets a bank (the terrain is interpolated in
 * 4-block cells, so a thin bank can dip a block below the water);</li>
 * <li>only real steps of the same river spill over, which makes the rapids and waterfalls.</li>
 * </ul>
 */
public record WaterFillFeature() implements Feature {
	public static final MapCodec<WaterFillFeature> CODEC = MapCodec.unit(WaterFillFeature::new);
	private static final int MAX_DEPTH = 28;

	@Override
	public MapCodec<WaterFillFeature> codec() {
		return CODEC;
	}

	private static BlockState bed(byte kind, RandomSource random) {
		float r = random.nextFloat();
		if (kind == 1) return r < 0.55f ? Blocks.GRAVEL.defaultBlockState() : r < 0.85f ? Blocks.SAND.defaultBlockState() : Blocks.CLAY.defaultBlockState();
		return r < 0.4f ? Blocks.MUD.defaultBlockState() : r < 0.7f ? Blocks.CLAY.defaultBlockState() : r < 0.9f ? Blocks.SAND.defaultBlockState() : Blocks.GRAVEL.defaultBlockState();
	}

	private static boolean holds(BlockState s) {
		// what can sit next to water without letting it through
		return !s.isAir() && (s.getFluidState().is(Fluids.WATER) || s.isSolid() || s.is(BlockTags.LEAVES));
	}

	@Override
	public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
		WorldLayout layout = LayoutSeeds.layout(level.getSeed());
		int x0 = Math.floorDiv(origin.getX(), 16) * 16;
		int z0 = Math.floorDiv(origin.getZ(), 16) * 16;
		BlockState water = Blocks.WATER.defaultBlockState();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos np = new BlockPos.MutableBlockPos();
		boolean any = false;
		int[] tops = new int[256];
		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				int x = x0 + dx, z = z0 + dz;
				WorldLayout.Column c = layout.column(x, z);
				tops[dx * 16 + dz] = Integer.MIN_VALUE;
				float w = c.water;
				if (Float.isNaN(w)) continue;
				byte kind = c.waterKind;
				int top = (int) Math.floor(w);
				tops[dx * 16 + dz] = top;
				int y = top;
				// clear anything floating over the water surface that the terrain left (overhang noise)
				for (; y >= top - MAX_DEPTH; y--) {
					pos.set(x, y, z);
					BlockState s = level.getBlockState(pos);
					if (s.isAir()) {
						level.setBlock(pos, water, 2);
						any = true;
					} else if (s.getFluidState().is(Fluids.WATER)) {
						// already water (vanilla aquifer or a neighbour chunk)
					} else {
						// the bed: no grass under water
						if (y < top && (s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.PODZOL) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.MOSS_BLOCK))) {
							level.setBlock(pos, bed(kind, random), 2);
						}
						break;
					}
				}
				if (y < top - MAX_DEPTH) {
					// ran into a cave under the river: give the water a floor
					pos.set(x, y + 1, z);
					level.setBlock(pos, bed(kind, random), 2);
				}
			}
		}
		// banks and spills, after the whole chunk holds its water
		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				int top = tops[dx * 16 + dz];
				if (top == Integer.MIN_VALUE) continue;
				int x = x0 + dx, z = z0 + dz;
				boolean spill = false;
				for (int k = 0; k < 4; k++) {
					int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = z + (k == 2 ? 1 : k == 3 ? -1 : 0);
					WorldLayout.Column nc = layout.column(nx, nz);
					float nw = nc.water;
					int ntop = Float.isNaN(nw) ? Integer.MIN_VALUE : (int) Math.floor(nw);
					for (int y = top; y > top - 8; y--) {
						if (ntop >= y) break; // the neighbour holds water up to here itself
						np.set(nx, y, nz);
						BlockState ns = level.getBlockState(np);
						if (holds(ns)) break;
						boolean lowerWater = ntop != Integer.MIN_VALUE && ntop < top && nc.waterKind != 0;
						boolean mouth = nc.riverDist < 0.5f || nc.height < WorldLayout.SEA
							|| level.getBlockState(np.below()).getFluidState().is(Fluids.WATER);
						if (lowerWater || mouth) {
							// a lower step of the same water: let it fall
							spill = true;
							break;
						}
						// dry ground lower than the water: build the bank up
						level.setBlock(np, y == top ? bankTop(level, np) : Blocks.DIRT.defaultBlockState(), 2);
					}
				}
				if (spill) {
					level.scheduleTick(new BlockPos(x, top, z), Fluids.WATER, 1);
				}
			}
		}
		return any;
	}

	private static BlockState bankTop(WorldGenLevel level, BlockPos pos) {
		BlockState below = level.getBlockState(pos.below());
		if (below.is(Blocks.SAND) || below.is(Blocks.GRAVEL) || below.is(Blocks.STONE) || below.is(Blocks.MUD)) return below;
		return level.getBlockState(pos.above()).isAir() ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState();
	}
}
