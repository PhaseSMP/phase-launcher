package gg.phase.world.mixin;

import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Vanilla floods every open cave below y -54 with lava (the global aquifer fluid). The Phase world goes
 * 64 blocks deeper, so the lava sea moves down with the floor: it starts 10 blocks above the bottom.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class FluidPickerMixin {
	@ModifyConstant(method = "createFluidPicker", constant = @Constant(intValue = -54))
	private static int phaseworld$lavaLevel(int original, NoiseGeneratorSettings settings) {
		int minY = settings.noiseSettings().minY();
		return minY < -64 ? minY + 10 : original;
	}
}
