package gg.phase.world.gen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import gg.phase.world.gen.layout.PB;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Picks biomes straight from the Phase layout. The noise router's {@code temperature} slot carries
 * {@code phaseworld:layout kind=biome}, which returns the biome ordinal (see {@link PB}).
 * JSON: {@code {"type": "phaseworld:phase", "biomes": {"sakura_valley": "phaseworld:sakura_valley", ...}}}.
 */
public class PhaseBiomeSource extends BiomeSource {
	public static final MapCodec<PhaseBiomeSource> CODEC = Codec.unboundedMap(Codec.STRING, Biome.CODEC)
		.fieldOf("biomes")
		.xmap(PhaseBiomeSource::new, s -> s.biomes);

	private final Map<String, Holder<Biome>> biomes;
	private final Holder<Biome>[] byOrdinal;

	@SuppressWarnings("unchecked")
	public PhaseBiomeSource(Map<String, Holder<Biome>> biomes) {
		this.biomes = biomes;
		this.byOrdinal = new Holder[PB.VALUES.length];
		Holder<Biome> fallback = biomes.values().iterator().next();
		for (PB b : PB.VALUES) {
			Holder<Biome> h = biomes.get(b.id);
			this.byOrdinal[b.ordinal()] = h != null ? h : fallback;
		}
	}

	@Override
	protected MapCodec<? extends BiomeSource> codec() {
		return CODEC;
	}

	@Override
	protected Stream<Holder<Biome>> collectPossibleBiomes() {
		return this.biomes.values().stream();
	}

	@Override
	public BiomeResolver createResolver(Climate.Sampler sampler) {
		DensitySampler.Bound ids = sampler.temperature();
		return (qx, qy, qz) -> this.byId(ids.sampleValue(QuartPos.toBlock(qx), QuartPos.toBlock(qy), QuartPos.toBlock(qz)));
	}

	private Holder<Biome> byId(float value) {
		int i = Math.round(value);
		return i >= 0 && i < this.byOrdinal.length ? this.byOrdinal[i] : this.byOrdinal[PB.DEEP_CAVERNS.ordinal()];
	}

	@Override
	public void addDebugInfo(List<String> result, BlockPos feetPos, Climate.Sampler sampler) {
		float v = sampler.temperature().sampleValue(feetPos.getX(), feetPos.getY(), feetPos.getZ());
		result.add("Phase biome: " + PB.byId(Math.round(v)).id);
	}
}
