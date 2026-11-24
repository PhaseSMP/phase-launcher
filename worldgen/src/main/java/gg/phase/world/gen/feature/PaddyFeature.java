package gg.phase.world.gen.feature;

import com.mojang.serialization.MapCodec;
import gg.phase.world.gen.LayoutSeeds;
import gg.phase.world.gen.layout.PB;
import gg.phase.world.gen.layout.WorldLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * Turns the flat steps of the rice terraces into flooded paddies: one block of water over mud,
 * separated by grass bunds on a 7-block grid.
 */
public record PaddyFeature() implements Feature {
	public static final MapCodec<PaddyFeature> CODEC = MapCodec.unit(PaddyFeature::new);

	@Override
	public MapCodec<PaddyFeature> codec() {
		return CODEC;
	}

	@Override
	public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
		WorldLayout layout = LayoutSeeds.layout(level.getSeed());
		int x0 = Math.floorDiv(origin.getX(), 16) * 16;
		int z0 = Math.floorDiv(origin.getZ(), 16) * 16;
		boolean any = false;
		net.minecraft.world.level.block.CropBlock rice = (net.minecraft.world.level.block.CropBlock) gg.phase.world.registry.ModBlocks.RICE_CROP;
		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				int x = x0 + dx, z = z0 + dz;
				WorldLayout.Column c = layout.column(x, z);
				if (c.surface != PB.RICE_TERRACES.ordinal() || c.height < WorldLayout.SEA + 3) continue;
				int h = (int) Math.floor(c.height);
				// the edge of every terrace step stays a grass bund; that follows the contours, no square grid
				if ((int) Math.floor(layout.column(x + 1, z).height) != h || (int) Math.floor(layout.column(x - 1, z).height) != h
					|| (int) Math.floor(layout.column(x, z + 1).height) != h || (int) Math.floor(layout.column(x, z - 1).height) != h) continue;
				// rare winding dividing bunds inside wide steps
				double wob = 3.0 * Math.sin(x / 9.0) + 3.0 * Math.cos(z / 11.0);
				if (Math.floorMod((int) Math.round(x * 0.7 + z * 0.7 + wob), 23) == 0) continue;
				int top = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
				BlockPos p = new BlockPos(x, top, z);
				if (!level.getBlockState(p).is(Blocks.GRASS_BLOCK)) continue;
				level.setBlock(p.below(), Blocks.MUD.defaultBlockState(), 2);
				if (Math.floorMod(x + 2 * z, 3) != 0) {
					// rows of rice standing in the flooded field
					level.setBlock(p, Blocks.MUD.defaultBlockState(), 2);
					level.setBlock(p.above(), rice.getStateForAge(4 + random.nextInt(4)), 2);
				} else {
					level.setBlock(p, Blocks.WATER.defaultBlockState(), 2);
				}
				any = true;
			}
		}
		return any;
	}
}
