package gg.phase.world.gen.layout;

/**
 * Every biome the layout can return. The ordinal is what the {@code phaseworld:biome_id}
 * density function outputs; the biome source maps names to real biome holders.
 * Never reorder: append new biomes at the end.
 */
public enum PB {
	DEEP_OCEAN("deep_ocean", false),
	OCEAN("ocean", false),
	INNER_SEA("inner_sea", false),
	BEACH("beach", false),
	BLACK_SAND_BEACH("black_sand_beach", false),
	ROCKY_SHORE("rocky_shore", false),
	RIVER("river", false),
	SAKURA_VALLEY("sakura_valley", false),
	MOMIJI_HIGHLANDS("momiji_highlands", false),
	SUGI_FOREST("sugi_forest", false),
	BAMBOO_GROVE("bamboo_grove", false),
	RICE_TERRACES("rice_terraces", false),
	TEA_HILLS("tea_hills", false),
	ALPINE_PEAKS("alpine_peaks", false),
	SNOWY_HIGHLANDS("snowy_highlands", false),
	ONSEN_VALLEY("onsen_valley", false),
	PINE_CLIFFS("pine_cliffs", false),
	REED_MARSH("reed_marsh", false),
	SAND_DUNES("sand_dunes", false),
	FROZEN_SUMMITS("frozen_summits", false),
	JUKAI_FOREST("jukai_forest", false),
	FUJI_SUBALPINE("fuji_subalpine", false),
	FUJI_SLOPES("fuji_slopes", false),
	FUJI_SUMMIT("fuji_summit", false),
	DEEP_CAVERNS("deep_caverns", true),
	HIKARI_GROTTO("hikari_grotto", true),
	ECLIPSE_HOLLOWS("eclipse_hollows", true),
	MAGMA_TUBES("magma_tubes", true),
	FLOODED_CAVES("flooded_caves", true),
	ICE_CAVES("ice_caves", true),
	// ---- 1.1
	WISTERIA_GROVE("wisteria_grove", false),
	SPIRIT_FOREST("spirit_forest", false),
	GINKGO_GROVE("ginkgo_grove", false),
	HYDRANGEA_HILLS("hydrangea_hills", false),
	HIGANBANA_FIELDS("higanbana_fields", false),
	LOTUS_MARSH("lotus_marsh", false),
	KARST_SPIRES("karst_spires", false),
	CORAL_COAST("coral_coast", false),
	JIGOKU_WASTES("jigoku_wastes", false),
	SHIRAKABA_PLATEAU("shirakaba_plateau", false),
	ALPINE_MEADOW("alpine_meadow", false),
	LUMINOUS_DEPTHS("luminous_depths", true),
	ROOT_HALLS("root_halls", true),
	ABYSS("abyss", true),
	// ---- 1.3
	TSUTSUJI_HILLS("tsutsuji_hills", false),
	YAKUSHIMA_MOSS("yakushima_moss", false),
	SUSUKI_PLAINS("susuki_plains", false),
	MANGROVE_COAST("mangrove_coast", false),
	TROPICAL_FOREST("tropical_forest", false);

	public static final PB[] VALUES = values();

	public final String id;
	private final boolean cave;

	PB(String id, boolean cave) {
		this.id = id;
		this.cave = cave;
	}

	public boolean isCave() {
		return this.cave;
	}

	public static PB byId(int ordinal) {
		return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : DEEP_CAVERNS;
	}
}
