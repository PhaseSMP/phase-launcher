package gg.phase.world.gen.layout;

/**
 * Terrain styles for the ring, islets and Fuji foothills.
 * Each style has its own relief, coastline ramp and amount of 3D overhangs.
 */
public enum Style {
	//            biome                 inlandStart inlandEnd overhang
	SAKURA      (PB.SAKURA_VALLEY,      8,  150, 1.5f),
	MOMIJI      (PB.MOMIJI_HIGHLANDS,   8,  170, 6f),
	SUGI        (PB.SUGI_FOREST,       10,  200, 7f),
	BAMBOO      (PB.BAMBOO_GROVE,       6,  120, 1f),
	TERRACES    (PB.RICE_TERRACES,      8,  160, 0f),
	TEA         (PB.TEA_HILLS,          8,  150, 0f),
	ALPS        (PB.ALPINE_PEAKS,      12,  240, 13f),
	SNOWLAND    (PB.SNOWY_HIGHLANDS,   10,  190, 4f),
	ONSEN       (PB.ONSEN_VALLEY,       8,  160, 5f),
	PINE_CLIFFS (PB.PINE_CLIFFS,        2,   16, 9f),
	MARSH       (PB.REED_MARSH,         4,   70, 0f),
	DUNES       (PB.SAND_DUNES,         6,  110, 0f),
	JUKAI       (PB.JUKAI_FOREST,       6,  120, 2.5f);

	public static final Style[] VALUES = values();

	public final PB biome;
	public final float inlandStart, inlandEnd, overhang;

	Style(PB biome, float inlandStart, float inlandEnd, float overhang) {
		this.biome = biome;
		this.inlandStart = inlandStart;
		this.inlandEnd = inlandEnd;
		this.overhang = overhang;
	}
}
