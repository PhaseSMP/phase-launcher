package gg.phase.world.compat;

import gg.phase.world.PhaseWorld;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.biome.v1.ModificationPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;

/**
 * Fabulous Birds: every songbird species lives in its own biomes. Songbirds spawn in our wooded and field biomes
 * (the mod's own spawn list only knows vanilla forests), and a natural spawn takes the species of its biome
 * (see SongbirdMixin). Spawn eggs and commands still give any species.
 */
public final class SongbirdBiomes {
	public static final String MOD = "fabulousbirds";
	static final Identifier SONGBIRD = Identifier.fromNamespaceAndPath(MOD, "songbird");

	/** variant index -> its biomes. One species per group, no biome shared between two species. */
	static final String[] HOMES = {
		"sakura_valley,tea_hills",                                  // 0
		"momiji_highlands,ginkgo_grove",                            // 1
		"bamboo_grove,rice_terraces",                               // 2
		"sugi_forest,jukai_forest,fuji_subalpine",                  // 3
		"wisteria_grove,hydrangea_hills",                           // 4
		"tropical_forest,mangrove_coast,yakushima_moss",            // 5
		"shirakaba_plateau,alpine_meadow,snowy_highlands",          // 6
		"susuki_plains,higanbana_fields,reed_marsh,lotus_marsh",    // 7
		"tsutsuji_hills,onsen_valley,karst_spires",                 // 8
		"spirit_forest,pine_cliffs,rocky_shore",                    // 9
	};

	private static final Map<ResourceKey<Biome>, Integer> VARIANT = new HashMap<>();

	static {
		for (int v = 0; v < HOMES.length; v++) for (ResourceKey<Biome> k : ModCompat.biomes(HOMES[v])) VARIANT.put(k, v);
	}

	private SongbirdBiomes() {
	}

	static void init() {
		// the entity type is looked up when the biomes are built (server start), after every mod registered it
		BiomeModifications.create(PhaseWorld.id("compat/songbirds"))
			.add(ModificationPhase.ADDITIONS, BiomeSelectors.includeByKey(VARIANT.keySet()), ctx -> {
				EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(SONGBIRD);
				if (type != null && BuiltInRegistries.ENTITY_TYPE.getKey(type).equals(SONGBIRD)) ctx.getSpawnSettings().addSpawn(type, 12, 3, 6);
			});
		PhaseWorld.LOG.info("Fabulous Birds: {} songbird species, each in its own biomes", HOMES.length);
	}

	/** The species for a natural spawn at pos, or -1 to keep the mod's choice. */
	public static int variantFor(LevelReader level, BlockPos pos, EntitySpawnReason reason) {
		if (reason != EntitySpawnReason.NATURAL && reason != EntitySpawnReason.CHUNK_GENERATION && reason != EntitySpawnReason.EVENT) return -1;
		Holder<Biome> biome = level.getBiome(pos);
		Integer v = biome.unwrapKey().map(VARIANT::get).orElse(null);
		return v == null ? -1 : v;
	}
}
