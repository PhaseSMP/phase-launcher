package gg.phase.world.gen;

import gg.phase.world.gen.layout.WorldLayout;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * Density functions get their randomness from {@code CompileContext.createRandom(ID)};
 * features only know the world seed. This reproduces RandomState's derivation
 * (non-legacy random source) so both sides build the very same layout.
 */
public final class LayoutSeeds {
	public static final Identifier ID = Identifier.fromNamespaceAndPath("phaseworld", "layout");

	private LayoutSeeds() {
	}

	public static long fromWorldSeed(long worldSeed) {
		return new XoroshiroRandomSource(worldSeed).forkPositional().fromHashOf(ID).nextLong();
	}

	public static WorldLayout layout(long worldSeed) {
		return WorldLayout.get(fromWorldSeed(worldSeed));
	}
}
