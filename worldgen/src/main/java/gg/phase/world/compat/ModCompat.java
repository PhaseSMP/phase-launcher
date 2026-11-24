package gg.phase.world.compat;

import gg.phase.world.PhaseWorld;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.biome.v1.ModificationPhase;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/** Optional integrations: everything here only runs when the other mod is installed. */
public final class ModCompat {
	private ModCompat() {
	}

	public static boolean loaded(String modId) {
		return FabricLoader.getInstance().isModLoaded(modId);
	}

	static List<ResourceKey<Biome>> biomes(String csv) {
		List<ResourceKey<Biome>> keys = new ArrayList<>();
		for (String b : csv.split(",")) keys.add(ResourceKey.create(Registries.BIOME, PhaseWorld.id(b)));
		return keys;
	}

	public static void init() {
		if (loaded(TrimBan.MOD)) {
			TrimBan.init();
			PhaseWorld.LOG.info("More Armor Trims: templates are admin-only (no loot, no trades)");
		}
		// features of other mods in our biomes; one modification, rows in table order, so every biome gets
		// them in the same relative order (no feature order cycles)
		List<String[]> rows = new ArrayList<>();
		for (String[] row : CompatTable.FEATURES) if (loaded(row[0])) rows.add(row);
		if (!rows.isEmpty()) {
			for (String[] row : rows) {
				ResourceKey<PlacedFeature> key = ResourceKey.create(Registries.PLACED_FEATURE, Identifier.parse(row[1]));
				GenerationStep.Decoration step = GenerationStep.Decoration.values()[Integer.parseInt(row[2])];
				BiomeModifications.create(PhaseWorld.id("compat/" + key.identifier().getNamespace() + "/" + key.identifier().getPath()))
					.add(ModificationPhase.ADDITIONS, BiomeSelectors.includeByKey(biomes(row[3])), ctx -> ctx.getGenerationSettings().addFeature(step, key));
			}
			PhaseWorld.LOG.info("Phase World: {} features from other mods placed in Japanese biomes", rows.size());
		}
		if (loaded(SongbirdBiomes.MOD)) SongbirdBiomes.init();
	}
}
