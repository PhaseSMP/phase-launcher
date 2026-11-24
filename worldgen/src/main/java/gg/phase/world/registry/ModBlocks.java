package gg.phase.world.registry;

import gg.phase.world.block.PhaseCropBlock;
import gg.phase.world.block.WildPlantBlock;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ColorRGBA;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.ColoredFallingBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.UntintedParticleLeavesBlock;
import net.minecraft.world.level.block.sounds.AmbientLeavesBlockSoundPlayer;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class ModBlocks {
	public static final String MOD_ID = "phaseworld";
	/** Blocks that get a block item, in creative-tab order. */
	public static final List<Block> WITH_ITEMS = new ArrayList<>();

	// ---- terrain
	public static final Block VOLCANIC_ASH = reg("volcanic_ash", p -> new ColoredFallingBlock(new ColorRGBA(0xFF6E6A66), p),
		props().mapColor(MapColor.COLOR_GRAY).strength(0.5f).sound(SoundType.SAND), true);
	public static final Block BLACK_SAND = reg("black_sand", p -> new ColoredFallingBlock(new ColorRGBA(0xFF2D2A29), p),
		props().mapColor(MapColor.COLOR_BLACK).strength(0.5f).sound(SoundType.SAND), true);
	public static final Block SCORIA = reg("scoria", Block::new,
		props().mapColor(MapColor.TERRACOTTA_RED).requiresCorrectToolForDrops().strength(1.25f, 4.2f).sound(SoundType.BASALT), true);
	public static final Block PUMICE = reg("pumice", Block::new,
		props().mapColor(MapColor.TERRACOTTA_WHITE).requiresCorrectToolForDrops().strength(0.8f, 2.0f).sound(SoundType.TUFF), true);

	// ---- caves
	public static final Block HIKARI_MOSS = reg("hikari_moss", Block::new,
		props().mapColor(MapColor.GLOW_LICHEN).strength(0.1f).sound(SoundType.MOSS).lightLevel(s -> 10).pushReaction(PushReaction.POPPED), true);
	public static final Block HIKARI_MOSS_CARPET = reg("hikari_moss_carpet", CarpetBlock::new,
		props().mapColor(MapColor.GLOW_LICHEN).strength(0.1f).sound(SoundType.MOSS_CARPET).lightLevel(s -> 7).pushReaction(PushReaction.POPPED), true);
	public static final Block ECLIPSE_CRYSTAL_BLOCK = reg("eclipse_crystal_block", Block::new,
		props().mapColor(MapColor.COLOR_PURPLE).requiresCorrectToolForDrops().strength(1.5f).sound(SoundType.AMETHYST).lightLevel(s -> 6), true);
	public static final Block ECLIPSE_CRYSTAL_CLUSTER = reg("eclipse_crystal_cluster", p -> new AmethystClusterBlock(7.0f, 3.0f, p),
		props().mapColor(MapColor.COLOR_PURPLE).forceSolidOn().noOcclusion().strength(1.5f).sound(SoundType.AMETHYST_CLUSTER)
			.lightLevel(s -> 9).pushReaction(PushReaction.POPPED), true);

	// ---- trees
	public static final Block MOMIJI_LEAVES = reg("momiji_leaves",
		p -> new UntintedParticleLeavesBlock(0.02f, ParticleTypes.RED_POPLAR_LEAVES, AmbientLeavesBlockSoundPlayer.noAmbientSound(), p),
		BlockBehaviour.Properties.ofFullCopy(Blocks.RED_POPLAR_LEAVES).mapColor(MapColor.COLOR_RED), true);
	public static final Block GINKGO_LEAVES = reg("ginkgo_leaves",
		p -> new UntintedParticleLeavesBlock(0.02f, ParticleTypes.YELLOW_POPLAR_LEAVES, AmbientLeavesBlockSoundPlayer.noAmbientSound(), p),
		BlockBehaviour.Properties.ofFullCopy(Blocks.RED_POPLAR_LEAVES).mapColor(MapColor.COLOR_YELLOW), true);

	// ---- shrines and dungeons
	public static final Block SHRINE_BRICKS = reg("shrine_bricks", Block::new, stone(), true);
	public static final Block MOSSY_SHRINE_BRICKS = reg("mossy_shrine_bricks", Block::new, stone(), true);
	public static final Block CRACKED_SHRINE_BRICKS = reg("cracked_shrine_bricks", Block::new, stone(), true);
	public static final Block VERMILION_PLANKS = reg("vermilion_planks", Block::new,
		props().mapColor(MapColor.COLOR_RED).strength(2.0f, 3.0f).sound(SoundType.WOOD).ignitedByLava(), true);
	public static final Block TATAMI = reg("tatami", Block::new,
		props().mapColor(MapColor.TERRACOTTA_YELLOW).strength(0.8f).sound(SoundType.GRASS).ignitedByLava(), true);
	public static final Block STONE_LANTERN = reg("stone_lantern", Block::new,
		props().mapColor(MapColor.STONE).requiresCorrectToolForDrops().strength(1.5f, 6.0f).sound(SoundType.STONE)
			.noOcclusion().lightLevel(s -> 14), true);

	// ---- crops (placed by their seed items)
	public static final Block RICE_CROP = reg("rice_crop", p -> new PhaseCropBlock(p, () -> ModItems.RICE, true), crop(), false);
	public static final Block SOYBEAN_CROP = reg("soybean_crop", p -> new PhaseCropBlock(p, () -> ModItems.SOYBEANS, false), crop(), false);
	public static final Block DAIKON_CROP = reg("daikon_crop", p -> new PhaseCropBlock(p, () -> ModItems.DAIKON_SEEDS, false), crop(), false);
	public static final Block WASABI_CROP = reg("wasabi_crop", p -> new PhaseCropBlock(p, () -> ModItems.WASABI_ROOT, true), crop(), false);
	public static final Block TEA_CROP = reg("tea_crop", p -> new PhaseCropBlock(p, () -> ModItems.TEA_SEEDS, false), crop(), false);

	// ---- wild plants found on the map
	public static final Block WILD_RICE = reg("wild_rice", WildPlantBlock::new, plant(), true);
	public static final Block WILD_SOYBEAN = reg("wild_soybean", WildPlantBlock::new, plant(), true);
	public static final Block WILD_DAIKON = reg("wild_daikon", WildPlantBlock::new, plant(), true);
	public static final Block WILD_WASABI = reg("wild_wasabi", WildPlantBlock::new, plant(), true);
	public static final Block WILD_TEA = reg("wild_tea", WildPlantBlock::new, plant().sound(SoundType.SWEET_BERRY_BUSH), true);
	public static final Block SHIITAKE = reg("shiitake", WildPlantBlock::new, plant().sound(SoundType.FUNGUS).mapColor(MapColor.COLOR_BROWN), true);

	private ModBlocks() {
	}

	static Identifier id(String name) {
		return Identifier.fromNamespaceAndPath(MOD_ID, name);
	}

	private static BlockBehaviour.Properties props() {
		return BlockBehaviour.Properties.of();
	}

	private static BlockBehaviour.Properties stone() {
		return props().mapColor(MapColor.STONE).requiresCorrectToolForDrops().strength(1.5f, 6.0f).sound(SoundType.STONE);
	}

	private static BlockBehaviour.Properties crop() {
		return props().mapColor(MapColor.PLANT).noCollision().randomTicks().instabreak().sound(SoundType.CROP).pushReaction(PushReaction.POPPED);
	}

	private static BlockBehaviour.Properties plant() {
		return props().mapColor(MapColor.PLANT).noCollision().instabreak().sound(SoundType.GRASS).pushReaction(PushReaction.POPPED);
	}

	private static <T extends Block> T reg(String name, Function<BlockBehaviour.Properties, T> factory, BlockBehaviour.Properties props, boolean item) {
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id(name));
		T block = factory.apply(props.setId(key));
		Registry.register(BuiltInRegistries.BLOCK, key, block);
		if (item) {
			ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id(name));
			Registry.register(BuiltInRegistries.ITEM, itemKey, new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
			WITH_ITEMS.add(block);
		}
		return block;
	}

	public static void init() {
		// class loading registers everything
	}
}
