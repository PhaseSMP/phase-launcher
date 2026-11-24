package gg.phase.world.gen.feature;

import com.mojang.serialization.MapCodec;
import gg.phase.world.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * Small signs of life on the map: a torii gate, a roadside hokora shrine, a pair of stone lanterns
 * with a gravel path, or a little moss-and-stone garden.
 */
public record ShrineDecorFeature() implements Feature {
	public static final MapCodec<ShrineDecorFeature> CODEC = MapCodec.unit(ShrineDecorFeature::new);

	@Override
	public MapCodec<ShrineDecorFeature> codec() {
		return CODEC;
	}

	@Override
	public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
		int x = origin.getX(), z = origin.getZ();
		int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
		// flat, dry ground only
		for (int dx = -4; dx <= 4; dx += 4) {
			for (int dz = -4; dz <= 4; dz += 4) {
				int hy = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x + dx, z + dz);
				if (Math.abs(hy - y) > 1) return false;
				BlockState g = level.getBlockState(new BlockPos(x + dx, hy - 1, z + dz));
				if (!g.is(BlockTags.DIRT) && !g.is(Blocks.SAND) && !g.is(Blocks.GRAVEL) && !g.is(Blocks.SNOW_BLOCK)) return false;
			}
		}
		BlockPos base = new BlockPos(x, y, z);
		boolean alongX = random.nextBoolean();
		return switch (random.nextInt(4)) {
			case 0 -> torii(level, base, alongX);
			case 1 -> hokora(level, base, random);
			case 2 -> lanterns(level, base, alongX, random);
			default -> garden(level, base, random);
		};
	}

	private static BlockPos off(BlockPos b, boolean alongX, int u, int y, int v) {
		return alongX ? b.offset(u, y, v) : b.offset(v, y, u);
	}

	private static void set(WorldGenLevel level, BlockPos p, BlockState s) {
		level.setBlock(p, s, 2);
	}

	/** The same x/z on the actual ground: small props never hang a block above a dip. */
	private static BlockPos ground(WorldGenLevel level, BlockPos p) {
		return new BlockPos(p.getX(), level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, p.getX(), p.getZ()), p.getZ());
	}

	/** A post standing on the ground: fills down to solid ground (at most 4 blocks) with the foundation block. */
	private static void footing(WorldGenLevel level, BlockPos p, BlockState foundation) {
		BlockPos.MutableBlockPos m = p.mutable();
		for (int i = 0; i < 4; i++) {
			m.move(0, -1, 0);
			BlockState s = level.getBlockState(m);
			if (s.isSolid() && !s.is(BlockTags.LEAVES)) break;
			level.setBlock(m, foundation, 2);
		}
	}

	// Beautify (optional): bamboo lamps and flower pot clusters at the shrines
	private static BlockState optional(String id) {
		return BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id)).map(Block::defaultBlockState).orElse(null);
	}

	private static boolean torii(WorldGenLevel level, BlockPos b, boolean alongX) {
		BlockState red = ModBlocks.VERMILION_PLANKS.defaultBlockState();
		BlockState black = Blocks.POLISHED_BLACKSTONE.defaultBlockState();
		BlockState blackSlab = Blocks.POLISHED_BLACKSTONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
		for (int u : new int[]{-2, 2}) {
			set(level, off(b, alongX, u, -1, 0), Blocks.STONE_BRICKS.defaultBlockState());
			footing(level, off(b, alongX, u, -1, 0), Blocks.STONE_BRICKS.defaultBlockState());
			for (int y = 0; y < 5; y++) set(level, off(b, alongX, u, y, 0), red);
		}
		for (int u = -2; u <= 2; u++) set(level, off(b, alongX, u, 3, 0), red);       // nuki
		for (int u = -3; u <= 3; u++) set(level, off(b, alongX, u, 5, 0), red);       // shimaki
		for (int u = -4; u <= 4; u++) set(level, off(b, alongX, u, 6, 0), black);     // kasagi
		set(level, off(b, alongX, -5, 6, 0), blackSlab);
		set(level, off(b, alongX, 5, 6, 0), blackSlab);
		set(level, off(b, alongX, 0, 4, 0), Blocks.DARK_OAK_PLANKS.defaultBlockState()); // gakuzuka
		return true;
	}

	private static boolean hokora(WorldGenLevel level, BlockPos b, RandomSource random) {
		BlockState stone = ModBlocks.SHRINE_BRICKS.defaultBlockState();
		BlockState wood = ModBlocks.VERMILION_PLANKS.defaultBlockState();
		BlockState roof = Blocks.DARK_OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
			set(level, b.offset(dx, -1, dz), stone);
			footing(level, b.offset(dx, -1, dz), stone);
		}
		set(level, b, stone);
		set(level, b.above(), wood);
		set(level, b.above(2), Blocks.DARK_OAK_PLANKS.defaultBlockState());
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) set(level, b.offset(dx, 3, dz), roof);
		set(level, b.offset(1, 0, 0), ModBlocks.STONE_LANTERN.defaultBlockState());
		if (random.nextBoolean()) {
			BlockState cluster = optional("beautify:multi_flower_pot");
			set(level, b.offset(-1, 0, 1), cluster != null && random.nextBoolean() ? cluster : Blocks.FLOWER_POT.defaultBlockState());
		}
		return true;
	}

	private static boolean lanterns(WorldGenLevel level, BlockPos b, boolean alongX, RandomSource random) {
		BlockState lantern = ModBlocks.STONE_LANTERN.defaultBlockState();
		set(level, ground(level, off(b, alongX, -2, 0, 0)), lantern);
		set(level, ground(level, off(b, alongX, 2, 0, 0)), lantern);
		BlockState lamp = optional("beautify:lamp_bamboo");
		if (lamp != null && random.nextBoolean()) {
			for (int v : new int[]{-4, 4}) {
				BlockPos p = ground(level, off(b, alongX, 2, 0, v));
				if (level.getBlockState(p).isAir() && level.getBlockState(p.below()).isSolid()) set(level, p, lamp);
			}
		}
		for (int v = -5; v <= 5; v++) {
			if (random.nextInt(5) == 0) continue;
			BlockPos p = ground(level, off(b, alongX, 0, 0, v)).below();
			if (Math.abs(p.getY() + 1 - b.getY()) > 1) continue;
			set(level, p, random.nextInt(3) == 0 ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.GRAVEL.defaultBlockState());
			if (!level.getBlockState(p.above()).isAir() && !level.getBlockState(p.above()).is(ModBlocks.STONE_LANTERN)) set(level, p.above(), Blocks.AIR.defaultBlockState());
		}
		return true;
	}

	private static boolean garden(WorldGenLevel level, BlockPos b, RandomSource random) {
		for (int i = 0; i < 3; i++) {
			BlockPos c = ground(level, b.offset(random.nextInt(7) - 3, 0, random.nextInt(7) - 3)).below();
			int r = random.nextInt(2);
			for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = 0; dy <= r; dy++) {
				if (dx * dx + dz * dz + dy * dy > r * r + 1) continue;
				set(level, c.offset(dx, dy, dz), random.nextInt(3) == 0 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.ANDESITE.defaultBlockState());
			}
		}
		for (int i = 0; i < 14; i++) {
			BlockPos p = ground(level, b.offset(random.nextInt(9) - 4, 0, random.nextInt(9) - 4));
			if (level.getBlockState(p).isAir() && level.getBlockState(p.below()).is(BlockTags.DIRT)) set(level, p, Blocks.MOSS_CARPET.defaultBlockState());
		}
		return true;
	}
}
