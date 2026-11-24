package gg.phase.world.test;

import gg.phase.world.gen.LayoutSeeds;
import gg.phase.world.gen.layout.PB;
import gg.phase.world.gen.layout.WorldLayout;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CI screenshots of the real game: creates a Phase world, finds interesting places straight from the
 * layout (Fuji, karst spires, the spirit forest, a river, a lake, the luminous caves...) and photographs them.
 */
public class WorldShots implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("Phase World shots");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			mc.options.renderDistance().set(6);
			mc.options.simulationDistance().set(5);
			mc.options.fov().set(80);
		});
		try (TestSingleplayerContext sp = ctx.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> s.setSeed("20261124"))
			.create()) {
			waitRender(sp, "spawn");
			cmd(sp, "gamemode spectator @p");
			cmd(sp, "time set 1000");
			cmd(sp, "weather clear 100000");
			cmd(sp, "gamerule doDaylightCycle false");
			cmd(sp, "gamerule advance_time false");
			cmd(sp, "gamerule doWeatherCycle false");
			cmd(sp, "gamerule advance_weather false");
			ctx.runOnClient(mc -> {
				if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
			});
			long seed = sp.getServer().computeOnServer(server -> server.overworld().getSeed());
			WorldLayout L = LayoutSeeds.layout(seed);
			LOG.info("[shots] seed {}", seed);

			// Fuji from the south, high up
			shot(ctx, sp, "01-fuji", (int) L.fujiX + 40, 420, (int) L.fujiZ + 1500, 180, -8);
			// spawn area
			int sh = (int) L.column(L.spawnX, L.spawnZ).height;
			shot(ctx, sp, "02-spawn", L.spawnX, sh + 24, L.spawnZ, 0, 12);
			biomeShot(ctx, sp, L, PB.KARST_SPIRES, "03-karst", 70, 30, 0);
			biomeShot(ctx, sp, L, PB.SPIRIT_FOREST, "04-spirit-forest", 3, 18, 0);
			biomeShot(ctx, sp, L, PB.WISTERIA_GROVE, "05-wisteria", 4, 10, 0);
			biomeShot(ctx, sp, L, PB.HYDRANGEA_HILLS, "06-hydrangea", 4, 12, 0);
			biomeShot(ctx, sp, L, PB.HIGANBANA_FIELDS, "07-higanbana", 3, 15, 0);
			biomeShot(ctx, sp, L, PB.SAKURA_VALLEY, "08-sakura", 8, 15, 0);
			biomeShot(ctx, sp, L, PB.MOMIJI_HIGHLANDS, "09-momiji", 10, 15, 0);
			biomeShot(ctx, sp, L, PB.GINKGO_GROVE, "10-ginkgo", 6, 12, 0);
			biomeShot(ctx, sp, L, PB.SUGI_FOREST, "11-sugi", 4, 15, 0);
			biomeShot(ctx, sp, L, PB.RICE_TERRACES, "12-terraces", 25, 25, 0);
			biomeShot(ctx, sp, L, PB.JIGOKU_WASTES, "13-jigoku", 10, 20, 0);
			biomeShot(ctx, sp, L, PB.SHIRAKABA_PLATEAU, "14-shirakaba", 4, 12, 0);
			biomeShot(ctx, sp, L, PB.LOTUS_MARSH, "15-lotus", 6, 20, 0);
			biomeShot(ctx, sp, L, PB.CORAL_COAST, "16-coral", 3, 30, 0);
			riverShot(ctx, sp, L);
			lakeShot(ctx, sp, L);
			caveShot(ctx, sp, L, PB.LUMINOUS_DEPTHS, "20-luminous");
			caveShot(ctx, sp, L, PB.ROOT_HALLS, "21-root-halls");
			caveShot(ctx, sp, L, PB.HIKARI_GROTTO, "22-hikari");
			caveShot(ctx, sp, L, PB.ABYSS, "23-abyss");
			// the spirit forest at night: glowing plants and spirit vines
			cmd(sp, "time set 18000");
			biomeShot(ctx, sp, L, PB.SPIRIT_FOREST, "30-spirit-night", 3, 14, 1);
			biomeShot(ctx, sp, L, PB.LOTUS_MARSH, "31-lotus-night", 6, 16, 1);
			cmd(sp, "time set 1000");
		}
	}

	private static void waitRender(TestSingleplayerContext sp, String what) {
		try {
			sp.getConnection().waitForChunksRender(true, 20 * 600);
		} catch (Throwable t) {
			LOG.info("[shots] chunks did not finish for {}: {}", what, t.toString());
		}
	}

	private static void cmd(TestSingleplayerContext sp, String c) {
		try {
			sp.getServer().runCommand(c);
		} catch (Throwable t) {
			LOG.info("[shots] command failed: {} ({})", c, t.toString());
		}
	}

	private static void shot(ClientGameTestContext ctx, TestSingleplayerContext sp, String name, int x, int y, int z, float yaw, float pitch) {
		LOG.info("[shots] {} at {} {} {}", name, x, y, z);
		long t0 = System.currentTimeMillis();
		// generate the area on the server first, then fly there and wait for the client to receive and draw it
		try {
			sp.getServer().runOnServer(server -> {
				net.minecraft.server.level.ServerLevel lvl = server.overworld();
				for (int i = -6; i <= 6; i++) for (int j = -6; j <= 6; j++) lvl.getChunk((x >> 4) + i, (z >> 4) + j);
			});
		} catch (Throwable t) {
			LOG.info("[shots] pregen failed for {}: {}", name, t.toString());
		}
		cmd(sp, String.format(java.util.Locale.ROOT, "tp @p %d %d %d %.1f %.1f", x, y, z, yaw, pitch));
		ctx.waitTicks(40);
		waitRender(sp, name);
		ctx.waitTicks(60);
		ctx.takeScreenshot(name);
		LOG.info("[shots] {} done in {} s", name, (System.currentTimeMillis() - t0) / 1000);
	}

	/** Finds a spot deep inside a biome (closest to Fuji first) and looks across it. */
	private static void biomeShot(ClientGameTestContext ctx, TestSingleplayerContext sp, WorldLayout L, PB biome, String name, int up, int back, int night) {
		int[] p = find(L, biome);
		if (p == null) {
			LOG.info("[shots] {} not found", biome.id);
			return;
		}
		int h = (int) Math.ceil(L.column(p[0], p[1]).height);
		shot(ctx, sp, name, p[0], h + up, p[1] + back, 180, up > 20 ? 18 : 4);
	}

	private static int[] find(WorldLayout L, PB biome) {
		int best = Integer.MAX_VALUE;
		int[] out = null;
		for (int x = -2600; x <= 2600; x += 16) {
			for (int z = -2600; z <= 2600; z += 16) {
				if (L.column(x, z).surface != biome.ordinal()) continue;
				boolean deep = true;
				for (int a = -24; a <= 24 && deep; a += 24)
					for (int b = -24; b <= 24 && deep; b += 24)
						if (L.column(x + a, z + b).surface != biome.ordinal()) deep = false;
				if (!deep) continue;
				int d = Math.abs(x - L.spawnX) + Math.abs(z - L.spawnZ);
				if (d < best) { best = d; out = new int[]{x, z}; }
			}
		}
		return out;
	}

	private static void riverShot(ClientGameTestContext ctx, TestSingleplayerContext sp, WorldLayout L) {
		for (WorldLayout.River rv : L.rivers) {
			for (int i = rv.n / 3; i < rv.n; i++) {
				if (rv.level[i] < WorldLayout.SEA + 8 || rv.width[i] < 5) continue;
				int x = (int) rv.x[i], z = (int) rv.z[i];
				int y = (int) rv.level[i];
				shot(ctx, sp, "17-river", x + 14, y + 9, z + 14, 135, 25);
				return;
			}
		}
	}

	private static void lakeShot(ClientGameTestContext ctx, TestSingleplayerContext sp, WorldLayout L) {
		if (L.lakes.isEmpty()) return;
		WorldLayout.Lake lk = L.lakes.get(0);
		shot(ctx, sp, "18-lake", (int) lk.x, (int) lk.level + 14, (int) (lk.z + lk.r + 20), 180, 22);
	}

	private static void caveShot(ClientGameTestContext ctx, TestSingleplayerContext sp, WorldLayout L, PB biome, String name) {
		// look for an open hall: a stack of carved cells inside the wanted cave biome
		for (int x = -1800; x <= 1800; x += 24) {
			for (int z = -1800; z <= 1800; z += 24) {
				WorldLayout.Column c = L.column(x, z);
				if (c.coast < 60) continue;
				for (int y = -110; y < Math.min(60, c.height - 40); y += 6) {
					WorldLayout.Column cc = L.column(x, z);
					if (L.cavern(x, y, z, cc) > -0.5 || L.cavern(x, y + 6, z, cc) > -0.5 || L.cavern(x, y - 4, z, cc) > 0) continue;
					if (L.caveBiome(x, y, z, cc) != biome) continue;
					shot(ctx, sp, name, x, y, z, 30, 10);
					cmd(sp, "effect give @p minecraft:night_vision infinite 0 true");
					ctx.waitTicks(20);
					ctx.takeScreenshot(name + "-nv");
					cmd(sp, "effect clear @p");
					return;
				}
			}
		}
		LOG.info("[shots] cave {} not found", biome.id);
	}
}
