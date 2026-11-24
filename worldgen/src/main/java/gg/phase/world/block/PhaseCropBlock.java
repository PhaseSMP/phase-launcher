package gg.phase.world.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Supplier;

/** A vanilla-style crop (age 0..7) with its own seed item. Rice also grows on mud. */
public class PhaseCropBlock extends CropBlock {
	private final Supplier<ItemLike> seed;
	private final boolean paddy;

	public PhaseCropBlock(BlockBehaviour.Properties properties, Supplier<ItemLike> seed, boolean paddy) {
		super(properties);
		this.seed = seed;
		this.paddy = paddy;
	}

	@Override
	protected ItemLike getBaseSeedId() {
		return this.seed.get();
	}

	@Override
	protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
		return super.mayPlaceOn(state, level, pos) || (this.paddy && state.is(Blocks.MUD));
	}
}
