package gg.phase.world.gen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import gg.phase.world.gen.layout.WorldLayout;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import java.util.Locale;

/**
 * {@code {"type": "phaseworld:layout", "kind": "terrain"}} - exposes the Phase map to the vanilla noise router.
 * The layout is seeded from the world seed through the compile context, so every world gets its own map.
 */
public record LayoutFunction(Kind kind) implements DensityFunction {
	public static final MapCodec<LayoutFunction> CODEC = Codec.STRING
		.xmap(s -> new LayoutFunction(Kind.valueOf(s.toUpperCase(Locale.ROOT))), f -> f.kind.name().toLowerCase(Locale.ROOT))
		.fieldOf("kind");

	public enum Kind {
		/** Surface height in blocks (2D). */
		SURFACE(true),
		/** Terrain density near the surface, ~ (height - y) / 8 (3D). */
		TERRAIN(false),
		/** Giant caverns and Fuji lava tubes, positive = stone (3D). */
		CAVERNS(false),
		/** Biome ordinal for {@link PhaseBiomeSource} (3D). */
		BIOME(false),
		/** 0..1 how good a spot is for the world spawn (2D). */
		SPAWN(true),
		/** Signed distance to the coast / 300, clamped to [-1, 1] (2D). */
		CONTINENTS(true),
		/** (height - y) / 128, like vanilla depth (3D). */
		DEPTH(false),
		/** Positive where aquifers stay dry (3D). */
		AQUIFER_EXCLUSION(false),
		/** Aquifer floodedness for underground lakes and grottoes (3D). */
		FLOODEDNESS(false),
		/** Solid ground under and beside water, -10 elsewhere; combined with max (3D). */
		WATER_GUARD(false);

		final boolean flat;

		Kind(boolean flat) {
			this.flat = flat;
		}
	}

	@Override
	public DensitySampler compileSampler(CompileContext context) {
		long layoutSeed = context.createRandom(LayoutSeeds.ID).nextLong();
		return new Sampler(this.kind, WorldLayout.get(layoutSeed));
	}

	@Override
	public DensityFunction rewriteChildren(DfRewriteRule rule) {
		return this;
	}

	@Override
	public Interval range() {
		return switch (this.kind) {
			case SURFACE -> Interval.of(-64.0f, 1400.0f);
			case TERRAIN -> Interval.of(-200.0f, 200.0f);
			case CAVERNS -> Interval.of(-10.0f, 10.0f);
			case BIOME -> Interval.of(0.0f, 64.0f);
			case SPAWN -> Interval.of(0.0f, 1.0f);
			case CONTINENTS, AQUIFER_EXCLUSION, FLOODEDNESS -> Interval.of(-1.0f, 1.0f);
			case DEPTH -> Interval.of(-12.0f, 12.0f);
			case WATER_GUARD -> Interval.of(-10.0f, 3.0f);
		};
	}

	@Override
	public @Axes int domainAxes() {
		return this.kind.flat ? AXIS_X | AXIS_Z : ALL_AXES;
	}

	@Override
	public MapCodec<? extends DensityFunction> codec() {
		return CODEC;
	}

	public record Sampler(Kind kind, WorldLayout layout) implements DensitySampler {
		@Override
		public float sampleValue(SamplerContext context, int x, int y, int z) {
			WorldLayout.Column c = this.layout.column(x, z);
			return (float) value(c, x, y, z);
		}

		private double value(WorldLayout.Column c, int x, int y, int z) {
			return switch (this.kind) {
				case SURFACE -> c.height;
				case TERRAIN -> this.layout.terrainDensity(x, y, z, c);
				case CAVERNS -> this.layout.cavern(x, y, z, c);
				case BIOME -> this.layout.biome(x, y, z);
				case SPAWN -> this.layout.spawnScore(x, z);
				case CONTINENTS -> Math.max(-1.0, Math.min(1.0, c.coast / 300.0));
				case DEPTH -> (c.height - y) / 128.0;
				case AQUIFER_EXCLUSION -> this.layout.aquiferExclusion(y, c);
				case FLOODEDNESS -> this.layout.floodedness(x, y, z, c);
				case WATER_GUARD -> this.layout.waterGuard(y, c);
			};
		}

		@Override
		public void sampleVolume(SamplerContext context, DensityBuffer out, DensityVolume volume) {
			// Buffer order is y fastest, then x, then z: look each column up once.
			int index = 0;
			for (int iz = 0; iz < volume.sizeZ(); iz++) {
				int z = volume.blockZ(iz);
				for (int ix = 0; ix < volume.sizeX(); ix++) {
					int x = volume.blockX(ix);
					WorldLayout.Column c = this.layout.column(x, z);
					if (this.kind.flat) {
						float v = (float) value(c, x, 0, z);
						out.setRange(index, volume.sizeY(), v);
						index += volume.sizeY();
						continue;
					}
					for (int iy = 0; iy < volume.sizeY(); iy++) {
						out.set(index++, (float) value(c, x, volume.blockY(iy), z));
					}
				}
			}
		}
	}
}
