package gg.phase.world.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A curtain of vines or flowers hanging from a ceiling, a branch or another piece of itself. */
public class HangingPlantBlock extends Block {
	private static final VoxelShape SHAPE = Block.column(12.0, 0.0, 16.0);

	public HangingPlantBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
		BlockPos up = pos.above();
		BlockState above = level.getBlockState(up);
		return above.is(this) || above.isFaceSturdy(level, up, Direction.DOWN) || above.is(net.minecraft.tags.BlockTags.LEAVES)
			|| above.is(net.minecraft.tags.BlockTags.LOGS);
	}

	@Override
	protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
									 BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
		if (direction == Direction.UP && !this.canSurvive(state, level, pos)) return Blocks.AIR.defaultBlockState();
		return super.updateShape(state, level, ticks, pos, direction, neighbourPos, neighbourState, random);
	}
}
