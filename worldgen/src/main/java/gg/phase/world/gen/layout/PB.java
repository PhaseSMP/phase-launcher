package gg.phase.world.gen.layout;

/**
 * Every biome the layout can return. The ordinal is what the {@code phaseworld:biome_id}
 * density function outputs; the biome source maps names to real biome holders.
 * Never reorder: append new biomes at the end.
 */
public enum PB {
	DEEP_OCEAN("deep_ocean"),
	OCEAN("ocean"),
	INNER_SEA("inner_sea"),
	BEACH("beach"),
	BLACK_SAND_BEACH("black_sand_beach"),
	ROCKY_SHORE("rocky_shore"),
	RIVER("river"),
	SAKURA_VALLEY("sakura_valley"),
	MOMIJI_HIGHLANDS("momiji_highlands"),
	SUGI_FOREST("sugi_forest"),
	BAMBOO_GROVE("bamboo_grove"),
	RICE_TERRACES("rice_terraces"),
	TEA_HILLS("tea_hills"),
	ALPINE_PEAKS("alpine_peaks"),
	SNOWY_HIGHLANDS("snowy_highlands"),
	ONSEN_VALLEY("onsen_valley"),
	PINE_CLIFFS("pine_cliffs"),
	REED_MARSH("reed_marsh"),
	SAND_DUNES("sand_dunes"),
	FROZEN_SUMMITS("frozen_summits"),
	JUKAI_FOREST("jukai_forest"),
	FUJI_SUBALPINE("fuji_subalpine"),
	FUJI_SLOPES("fuji_slopes"),
	FUJI_SUMMIT("fuji_summit"),
	DEEP_CAVERNS("deep_caverns"),
	HIKARI_GROTTO("hikari_grotto"),
	ECLIPSE_HOLLOWS("eclipse_hollows"),
	MAGMA_TUBES("magma_tubes"),
	FLOODED_CAVES("flooded_caves"),
	ICE_CAVES("ice_caves");

	public static final PB[] VALUES = values();

	public final String id;

	PB(String id) {
		this.id = id;
	}

	public boolean isCave() {
		return ordinal() >= DEEP_CAVERNS.ordinal();
	}

	public static PB byId(int ordinal) {
		return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : DEEP_CAVERNS;
	}
}
