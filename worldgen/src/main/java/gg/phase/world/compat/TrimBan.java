package gg.phase.world.compat;

import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * More Armor Trims templates are admin-only on Phase: they never drop from any loot table (chests,
 * archaeology, mobs, fishing, bartering) and are never sold by villagers or wandering traders
 * (see AbstractVillagerMixin). Duplicating a template you were given still works.
 */
public final class TrimBan {
	public static final String MOD = "more_armor_trims";

	private TrimBan() {
	}

	public static boolean banned(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		return MOD.equals(id.getNamespace()) && id.getPath().endsWith("smithing_template");
	}

	public static void init() {
		LootTableEvents.MODIFY_DROPS.register((holder, context, drops) -> drops.removeIf(TrimBan::banned));
	}
}
