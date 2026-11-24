package gg.phase.world.gen.feature;

import com.mojang.serialization.MapCodec;
import gg.phase.world.gen.LayoutSeeds;
import gg.phase.world.gen.layout.WorldLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.material.Fluids;

/**
 * Fills rivers and lakes that sit above sea level (the ocean and anything below sea level is filled
 * by vanilla aquifers). Where a river surface steps down, the edge block gets a fluid tick so the
 * water spills over naturally and forms rapids and waterfalls.
 */
public record WaterFillFeature() implements Feature {
	public static final MapCodec<WaterFillFeature> CODEC = MapCodec.unit(WaterFillFeature::new);

	@Override
	public MapCodec<WaterFillFeature> codec() {
		return CODEC;
	}

	private static BlockState bed(byte kind, RandomSource random) {
		float r = random.nextFloat();
		if (kind == 1) return r < 0.55f ? Blocks.GRAVEL.defaultBlockState() : r < 0.85f ? Blocks.SAND.defaultBlockState() : Blocks.CLAY.defaultBlockState();
		return r < 0.4f ? Blocks.MUD.defaultBlockState() : r < 0.7f ? Blocks.CLAY.defaultBlockState() : r < 0.9f ? Blocks.SAND.defaultBlockState() : Blocks.GRAVEL.defaultBlockState();
	}

	@Override
	public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
		WorldLayout layout = LayoutSeeds.layout(level.getSeed());
		int x0 = Math.floorDiv(origin.getX(), 16) * 16;
		int z0 = Math.floorDiv(origin.getZ(), 16) * 16;
		BlockState water = Blocks.WATER.defaultBlockState();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		boolean any = false;
		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				int x = x0 + dx, z = z0 + dz;
				WorldLayout.Column c = layout.column(x, z);
				float w = c.water;
				if (Float.isNaN(w)) continue;
				int top = (int) Math.floor(w);
				int bottom = Math.max((int) Math.ceil(c.height) - 1, top - 24);
				for (int y = top; y >= bottom; y--) {
					pos.set(x, y, z);
					BlockState s = level.getBlockState(pos);
					if (s.isAir()) {
						level.setBlock(pos, water, 2);
						any = true;
					} else if (!s.is(Blocks.WATER)) {
						// the bed: no grass under water
						if (y < top && (s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.PODZOL) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.MOSS_BLOCK))) {
							level.setBlock(pos, bed(c.waterKind, random), 2);
						}
						break;
					}
				}
				// spill over where the neighbour's surface is lower
				pos.set(x, top, z);
				if (!level.getBlockState(pos).is(Blocks.WATER)) continue;
				for (int k = 0; k < 4; k++) {
					int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = z + (k == 2 ? 1 : k == 3 ? -1 : 0);
					float nw = layout.column(nx, nz).water;
					if (Float.isNaN(nw) || (int) Math.floor(nw) < top) {
						if (level.getBlockState(new BlockPos(nx, top, nz)).isAir()) {
							level.scheduleTick(pos.immutable(), Fluids.WATER, 1);
							break;
						}
					}
				}
			}
		}
		return any;
	}
}
