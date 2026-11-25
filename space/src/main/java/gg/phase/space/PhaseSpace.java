package gg.phase.space;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase Space — космос сервера Phase.
 *
 * Мир {@code phasespace:space}: пустота без земли и неба, в которой летают кубические планеты
 * Солнечной системы и Флорана. Каждая планета и спутник — портал в свой мир ({@code phasespace:<id>}),
 * Земля — портал домой. Сервер считает орбиты и переносит игроков (SpaceTravel), клиент рисует
 * космос (client/SpaceRenderer), астероиды — настоящие блоки (AsteroidFeature).
 */
public class PhaseSpace implements ModInitializer {
	public static final String MOD_ID = "phasespace";
	public static final Logger LOG = LoggerFactory.getLogger("Phase Space");

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		Registry.register(BuiltInRegistries.FEATURE_TYPE, id("asteroid"), AsteroidFeature.CODEC);
		SpaceConfig.get();
		ServerTickEvents.END_SERVER_TICK.register(SpaceTravel::tick);
		SpaceCommands.register();
		PayloadTypeRegistry.clientboundPlay().register(AtlasPayload.TYPE, AtlasPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(EclipsePayload.TYPE, EclipsePayload.CODEC);
		ServerTickEvents.END_SERVER_TICK.register(Atlas::tick);
		ServerTickEvents.END_SERVER_TICK.register(EclipseChaos::tick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			Atlas.sync(handler.getPlayer());
			EclipseChaos.sync(handler.getPlayer());
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(EclipseChaos::stop);
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (Boolean.getBoolean("phasespace.selftest")) SelfTest.run(server);
		});
		LOG.info("Phase Space loaded: {} bodies, {} comets", SolarSystem.BODIES.size(), SolarSystem.COMETS.size());
	}
}
