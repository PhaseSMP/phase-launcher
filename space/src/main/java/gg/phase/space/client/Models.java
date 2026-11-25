package gg.phase.space.client;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Готовые к отрисовке модели мода (assets/phasespace/items/...): грани планет, небо, спрайты.
 * Всё в космосе рисуется через обычный путь предметов, поэтому Sodium его не трогает,
 * а Iris/Photon освещают и подсвечивают так же, как остальную игру.
 */
public final class Models {
	private static final Map<String, ItemStackRenderState> CACHE = new HashMap<>();
	private static ItemModelResolver resolver;

	private Models() {}

	static void setResolver(ItemModelResolver r) {
		resolver = r;
		CACHE.clear();
	}

	static void invalidate() {
		CACHE.clear();
	}

	public static boolean ready() {
		return resolver != null;
	}

	public static ItemStackRenderState get(String path) {
		ItemStackRenderState st = CACHE.get(path);
		if (st != null) return st;
		ItemStack stack = new ItemStack(Items.PAPER);
		stack.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath("phasespace", path));
		st = new ItemStackRenderState();
		resolver.updateForTopItem(st, stack, ItemDisplayContext.NONE, null, null, 0);
		CACHE.put(path, st);
		return st;
	}
}
