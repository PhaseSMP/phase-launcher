package gg.phase.world;

import gg.phase.world.gen.LayoutFunction;
import gg.phase.world.gen.PhaseBiomeSource;
import gg.phase.world.gen.feature.GiantTreeFeature;
import gg.phase.world.gen.feature.GlowcapFeature;
import gg.phase.world.gen.feature.OnsenFeature;
import gg.phase.world.gen.feature.ShrineDecorFeature;
import gg.phase.world.gen.feature.PaddyFeature;
import gg.phase.world.gen.feature.WaterFillFeature;
import gg.phase.world.registry.ModBlocks;
import gg.phase.world.registry.ModItems;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PhaseWorld implements ModInitializer {
	public static final String MOD_ID = ModBlocks.MOD_ID;
	public static final Logger LOG = LoggerFactory.getLogger("Phase World");

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		ModBlocks.init();
		ModItems.init();

		Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, id("layout"), LayoutFunction.CODEC);
		Registry.register(BuiltInRegistries.BIOME_SOURCE, id("phase"), PhaseBiomeSource.CODEC);
		Registry.register(BuiltInRegistries.FEATURE_TYPE, id("water_fill"), WaterFillFeature.CODEC);
		Registry.register(BuiltInRegistries.FEATURE_TYPE, id("paddy"), PaddyFeature.CODEC);
		Registry.register(BuiltInRegistries.FEATURE_TYPE, id("onsen"), OnsenFeature.CODEC);
		Registry.register(BuiltInRegistries.FEATURE_TYPE, id("giant_tree"), GiantTreeFeature.CODEC);
		Registry.register(BuiltInRegistries.FEATURE_TYPE, id("glowcap"), GlowcapFeature.CODEC);
		Registry.register(BuiltInRegistries.FEATURE_TYPE, id("shrine_decor"), ShrineDecorFeature.CODEC);

		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, ResourceKey.create(Registries.CREATIVE_MODE_TAB, id("main")),
			FabricCreativeModeTab.builder()
				.title(Component.translatable("itemGroup.phaseworld"))
				.icon(() -> new ItemStack(ModItems.ONIGIRI))
				.displayItems((context, out) -> {
					for (Block b : ModBlocks.WITH_ITEMS) out.accept(b);
					ModItems.ALL.forEach(out::accept);
				})
				.build());

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			PhaseConfig.applyWorldBorder(server);
			if (Boolean.getBoolean("phaseworld.selftest")) SelfTest.run(server);
		});
		LOG.info("Phase World loaded: ring map, Fuji, {} blocks, {} items", ModBlocks.WITH_ITEMS.size(), ModItems.ALL.size());
	}
}
