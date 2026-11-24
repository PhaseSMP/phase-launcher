package gg.phase.world.gen.feature;

import com.mojang.serialization.MapCodec;
import gg.phase.world.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * A giant glowing mushroom for the luminous caves: a curved pale stem, a domed cap of glowcap blocks
 * and glowing threads hanging from the cap's rim.
 */
public record GlowcapFeature() implements Feature {
	public static final MapCodec<GlowcapFeature> CODEC = MapCodec.unit(GlowcapFeature::new);

	@Override
	public MapCodec<GlowcapFeature> codec() {
		return CODEC;
	}

	@Override
	public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
		BlockState ground = level.getBlockState(origin.below());
		if (!ground.isFaceSturdy(level, origin.below(), Direction.UP)) return false;
		if (!ground.is(BlockTags.BASE_STONE_OVERWORLD) && !ground.is(BlockTags.DIRT) && !ground.is(ModBlocks.HIKARI_MOSS)) return false;
		int stem = 5 + random.nextInt(10);
		int capR = 3 + random.nextInt(4) + stem / 5;
		// enough room above?
		for (int y = 1; y <= stem + 3; y++) {
			if (!level.getBlockState(origin.above(y)).isAir()) return false;
		}
		BlockState stemState = ModBlocks.GLOWCAP_STEM.defaultBlockState();
		BlockState cap = ModBlocks.GLOWCAP_BLOCK.defaultBlockState();
		double lean = random.nextDouble() * 6.28, bend = 0.12 + random.nextDouble() * 0.18;
		double x = 0, z = 0;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int y = 0; y < stem; y++) {
			x = Math.cos(lean) * bend * y * y / stem;
			z = Math.sin(lean) * bend * y * y / stem;
			int r = stem > 10 && y < 3 ? 1 : 0;
			for (int a = -r; a <= r; a++) {
				for (int b = -r; b <= r; b++) {
					p.set(origin.getX() + (int) Math.round(x) + a, origin.getY() + y, origin.getZ() + (int) Math.round(z) + b);
					if (level.getBlockState(p).isAir() || level.getBlockState(p).canBeReplaced()) level.setBlock(p, stemState, 2);
				}
			}
		}
		int cx = origin.getX() + (int) Math.round(x), cy = origin.getY() + stem, cz = origin.getZ() + (int) Math.round(z);
		for (int dx = -capR; dx <= capR; dx++) {
			for (int dz = -capR; dz <= capR; dz++) {
				double d = Math.sqrt(dx * dx + dz * dz);
				if (d > capR + 0.3) continue;
				double t = d / capR;
				int top = (int) Math.round((1 - t * t) * capR * 0.55);
				int bottom = t > 0.75 ? -1 - (int) Math.round((t - 0.75) * 4) : 0;
				for (int dy = bottom; dy <= top; dy++) {
					// hollow dome: only the outer shell and the rim
					boolean shell = dy == top || d > capR - 1.2 || dy == bottom && t > 0.8;
					if (!shell) continue;
					p.set(cx + dx, cy + dy, cz + dz);
					if (level.getBlockState(p).isAir()) level.setBlock(p, cap, 2);
				}
				// glowing threads under the rim
				if (t > 0.7 && random.nextInt(3) == 0) {
					int len = 1 + random.nextInt(5);
					for (int i = 1; i <= len; i++) {
						p.set(cx + dx, cy + bottom - i, cz + dz);
						if (!level.getBlockState(p).isAir()) break;
						level.setBlock(p, ModBlocks.SPIRIT_VINE.defaultBlockState(), 2);
					}
				}
			}
		}
		// small caps around the foot
		for (int i = 0; i < 6; i++) {
			p.set(origin.getX() + random.nextInt(7) - 3, origin.getY(), origin.getZ() + random.nextInt(7) - 3);
			if (level.getBlockState(p).isAir() && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) {
				level.setBlock(p, random.nextBoolean() ? ModBlocks.GLOWCAP.defaultBlockState() : Blocks.MOSS_CARPET.defaultBlockState(), 2);
			}
		}
		return true;
	}
}
