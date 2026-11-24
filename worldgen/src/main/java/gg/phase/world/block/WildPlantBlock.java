package gg.phase.world.block;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Wild plants that grow on the map and drop seeds / produce (see loot tables). */
public class WildPlantBlock extends VegetationBlock {
	private static final VoxelShape SHAPE = Block.box(2.0, 0.0, 2.0, 14.0, 13.0, 14.0);

	public WildPlantBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
		return super.mayPlaceOn(state, level, pos) || state.is(Blocks.MUD) || state.is(Blocks.GRAVEL) || state.is(BlockTags.LOGS)
			|| state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.CLAY)
			|| state.is(gg.phase.world.registry.ModBlocks.HIKARI_MOSS) || state.is(Blocks.SAND) || state.is(Blocks.COBBLED_DEEPSLATE);
	}
}
