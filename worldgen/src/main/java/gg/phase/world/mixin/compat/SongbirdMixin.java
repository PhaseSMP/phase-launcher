package gg.phase.world.mixin.compat;

import gg.phase.world.compat.SongbirdBiomes;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fabulous Birds (optional): a songbird spawned in the wild takes the species that lives in its biome. */
@Pseudo
@Mixin(targets = "com.example.fabulousbirds.entity.Songbird")
public abstract class SongbirdMixin {
	@Shadow
	public abstract void setVariant(int variant);

	@Inject(method = "finalizeSpawn", at = @At("RETURN"), require = 0)
	private void phaseworld$biomeSpecies(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData data,
			CallbackInfoReturnable<SpawnGroupData> cir) {
		int v = SongbirdBiomes.variantFor(level, ((Entity) (Object) this).blockPosition(), reason);
		if (v >= 0) this.setVariant(v);
	}
}
