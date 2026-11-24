package gg.phase.world.compat;

import gg.phase.world.PhaseWorld;
import net.fabricmc.loader.api.FabricLoader;

/** Optional integrations: everything here only runs when the other mod is installed. */
public final class ModCompat {
	private ModCompat() {
	}

	public static boolean loaded(String modId) {
		return FabricLoader.getInstance().isModLoaded(modId);
	}

	public static void init() {
		if (loaded(TrimBan.MOD)) {
			TrimBan.init();
			PhaseWorld.LOG.info("More Armor Trims: templates are admin-only (no loot, no trades)");
		}
	}
}
