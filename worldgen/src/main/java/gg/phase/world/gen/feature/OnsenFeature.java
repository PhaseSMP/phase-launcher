package gg.phase.world.gen.feature;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;

/** A small natural hot spring: a stone-rimmed pool with sulfur crust around it. */
public record OnsenFeature() implements Feature {
	public static final MapCodec<OnsenFeature> CODEC = MapCodec.unit(OnsenFeature::new);

	@Override
	public MapCodec<OnsenFeature> codec() {
		return CODEC;
	}

	@Override
	public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
		int cx = origin.getX(), cz = origin.getZ();
		int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, cx, cz) - 1;
		double r = 2.2 + random.nextDouble() * 2.6;
		int ir = (int) Math.ceil(r) + 2;
		// only on fairly level ground
		for (int k = 0; k < 4; k++) {
			int sx = cx + (k < 2 ? (k == 0 ? ir : -ir) : 0), sz = cz + (k >= 2 ? (k == 2 ? ir : -ir) : 0);
			if (Math.abs(level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, sx, sz) - 1 - surface) > 2) return false;
		}
		BlockState rim = Blocks.TUFF.defaultBlockState();
		BlockState crust = Blocks.SULFUR.defaultBlockState();
		BlockState calcite = Blocks.CALCITE.defaultBlockState();
		BlockState water = Blocks.WATER.defaultBlockState();
		for (int dx = -ir; dx <= ir; dx++) {
			for (int dz = -ir; dz <= ir; dz++) {
				double d = Math.sqrt(dx * dx + dz * dz) + random.nextDouble() * 0.6;
				BlockPos top = new BlockPos(cx + dx, surface, cz + dz);
				if (d < r) {
					int depth = d < r * 0.5 ? 2 : 1;
					for (int y = 0; y <= 2; y++) level.setBlock(top.above(y + 1), Blocks.AIR.defaultBlockState(), 2);
					for (int y = 0; y < depth; y++) level.setBlock(top.below(y), water, 2);
					level.setBlock(top.below(depth), Blocks.SMOOTH_BASALT.defaultBlockState(), 2);
				} else if (d < r + 1.2) {
					level.setBlock(top, random.nextFloat() < 0.35 ? calcite : rim, 2);
				} else if (d < r + 2.4 && random.nextFloat() < 0.45) {
					level.setBlock(top, crust, 2);
				}
			}
		}
		return true;
	}
}
