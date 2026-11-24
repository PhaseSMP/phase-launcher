package gg.phase.world.registry;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.Consumables;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProviders;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Japanese food chain: everything here can be found, grown or cooked on the Phase map.
 * Balance: everyday dishes sit between bread and steak; full meals (ramen, sushi set) are the best
 * non-magic food in the game but need several farmed ingredients.
 */
public final class ModItems {
	public static final List<Item> ALL = new ArrayList<>();

	// seeds / raw produce (seed items plant the crops)
	public static final Item RICE = seed("rice", ModBlocks.RICE_CROP);
	public static final Item SOYBEANS = seed("soybeans", ModBlocks.SOYBEAN_CROP);
	public static final Item DAIKON_SEEDS = seed("daikon_seeds", ModBlocks.DAIKON_CROP);
	public static final Item WASABI_ROOT = seed("wasabi_root", ModBlocks.WASABI_CROP);
	public static final Item TEA_SEEDS = seed("tea_seeds", ModBlocks.TEA_CROP);
	public static final Item DAIKON = food("daikon", 3, 0.6f);
	public static final Item TEA_LEAVES = plain("tea_leaves");

	// ingredients
	public static final Item NORI = plain("nori");
	public static final Item RICE_FLOUR = plain("rice_flour");
	public static final Item MISO_PASTE = plain("miso_paste");
	public static final Item MATCHA = plain("matcha");
	public static final Item NOODLES = plain("noodles");

	// dishes
	public static final Item COOKED_RICE = food("cooked_rice", 4, 0.5f);
	public static final Item ONIGIRI = food("onigiri", 6, 0.7f);
	public static final Item SALMON_NIGIRI = food("salmon_nigiri", 5, 0.65f);
	public static final Item MAKI_ROLL = food("maki_roll", 3, 0.6f);
	public static final Item TOFU = food("tofu", 3, 0.4f);
	public static final Item EDAMAME = food("edamame", 2, 0.4f);
	public static final Item MOCHI = food("mochi", 4, 0.4f);
	public static final Item DANGO = food("dango", 7, 0.6f);
	public static final Item TAKUAN = food("takuan", 3, 0.5f);
	public static final Item GRILLED_SHIITAKE = food("grilled_shiitake", 4, 0.55f);
	public static final Item YAKITORI = food("yakitori", 5, 0.6f);
	public static final Item TAMAGOYAKI = food("tamagoyaki", 5, 0.6f);
	public static final Item MATCHA_MOCHI = reg("matcha_mochi", p -> new Item(p.food(foodProps(6, 0.6f),
		Consumables.defaultFood().onConsume(effect(MobEffects.SPEED, 30 * 20, 0)).build())));
	public static final Item MISO_SOUP = bowl("miso_soup", 8, 0.8f, null);
	public static final Item UDON = bowl("udon", 9, 0.8f, null);
	public static final Item RAMEN = bowl("ramen", 12, 0.9f, effect(MobEffects.REGENERATION, 6 * 20, 0));
	public static final Item SUSHI_SET = reg("sushi_set", p -> new Item(p.stacksTo(16).food(foodProps(14, 0.9f),
		Consumables.defaultFood().consumeSeconds(2.4f).onConsume(effect(MobEffects.WATER_BREATHING, 90 * 20, 0)).build())));
	public static final Item GREEN_TEA = reg("green_tea", p -> new Item(p.stacksTo(16).craftRemainder(Items.GLASS_BOTTLE)
		.food(new FoodProperties.Builder().nutrition(1).saturationModifier(0.4f).alwaysEdible().build(),
			Consumables.defaultDrink().onConsume(effect(MobEffects.HASTE, 60 * 20, 0)).build())
		.usingConvertsTo(Items.GLASS_BOTTLE)));

	private ModItems() {
	}

	private static ApplyStatusEffectsConsumeEffect effect(Holder<MobEffect> e, int ticks, int amp) {
		return new ApplyStatusEffectsConsumeEffect(new MobEffectInstance(e, ticks, amp));
	}

	private static FoodProperties foodProps(int nutrition, float saturation) {
		return new FoodProperties.Builder().nutrition(nutrition).saturationModifier(saturation).build();
	}

	private static Item reg(String name, Function<Item.Properties, Item> factory) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, ModBlocks.id(name));
		Item item = factory.apply(new Item.Properties().setId(key));
		Registry.register(BuiltInRegistries.ITEM, key, item);
		ALL.add(item);
		return item;
	}

	private static Item plain(String name) {
		return reg(name, Item::new);
	}

	private static Item food(String name, int nutrition, float saturation) {
		return reg(name, p -> new Item(p.food(foodProps(nutrition, saturation)).compostable(ContextIntProviders.COMPOSTABLE_MEDIUM)));
	}

	private static Item bowl(String name, int nutrition, float saturation, ApplyStatusEffectsConsumeEffect extra) {
		return reg(name, p -> {
			Item.Properties q = p.stacksTo(1).usingConvertsTo(Items.BOWL);
			if (extra == null) return new Item(q.food(foodProps(nutrition, saturation)));
			Consumable c = Consumables.defaultFood().onConsume(extra).build();
			return new Item(q.food(foodProps(nutrition, saturation), c));
		});
	}

	private static Item seed(String name, Block crop) {
		return reg(name, p -> new BlockItem(crop, p.useItemDescriptionPrefix().compostable(ContextIntProviders.COMPOSTABLE_LOW)));
	}

	public static void init() {
	}
}
