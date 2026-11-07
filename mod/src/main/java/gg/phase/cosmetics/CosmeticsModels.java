package gg.phase.cosmetics;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Модели косметики из ресурсов мода (assets/phase/models/item/wear) — готовые к отрисовке, с кешем. */
public final class CosmeticsModels {
	private static final Map<String, ItemStackRenderState> CACHE = new HashMap<>();
	private static ItemModelResolver resolver;
	private static int gen;

	private CosmeticsModels() {}

	static void setResolver(ItemModelResolver r) { resolver = r; CACHE.clear(); gen++; }

	static void invalidate() { CACHE.clear(); gen++; }

	/** Номер «поколения» кеша: меняется при перезагрузке ресурсов — запомненные модели надо взять заново */
	public static int gen() { return gen; }

	public static boolean ready() { return resolver != null; }

	public static ItemStackRenderState get(String name) { return model("wear/" + name); } // не cosm/: её перекрывает серверный ресурспак

	/** Спрайт частицы пака (assets/phase/items/fx): плоская картинка, светится сама */
	public static ItemStackRenderState fx(String name) { return model("fx/" + name); }

	private static ItemStackRenderState model(String path) {
		return CACHE.computeIfAbsent(path, n -> {
			ItemStack stack = new ItemStack(Items.PAPER);
			stack.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath("phase", n));
			ItemStackRenderState st = new ItemStackRenderState();
			resolver.updateForTopItem(st, stack, ItemDisplayContext.NONE, null, null, 0);
			return st;
		});
	}
}
