package gg.phase.world;

import gg.phase.world.gen.LayoutSeeds;
import gg.phase.world.gen.layout.WorldLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Development smoke test (-Dphaseworld.selftest=true, used by CI): generates a strip of chunks from
 * the top of Fuji out into the ocean, logs heights, biomes and time per chunk, then stops the server.
 */
public final class SelfTest {
	private SelfTest() {
	}

	public static void run(MinecraftServer server) {
		ServerLevel level = server.overworld();
		WorldLayout layout = LayoutSeeds.layout(level.getSeed());
		PhaseWorld.LOG.info("[selftest] seed {} fuji at {} {}, spawn {} {}, islets {}, lakes {}, rivers {}",
			level.getSeed(), (int) layout.fujiX, (int) layout.fujiZ, layout.spawnX, layout.spawnZ,
			layout.isletCount(), layout.lakes.size(), layout.rivers.size());
		long t0 = System.nanoTime();
		int chunks = 0;
		for (int d = 0; d <= 3000; d += 48) {
			int x = (int) layout.fujiX + d, z = (int) layout.fujiZ + d / 3;
			level.getChunk(x >> 4, z >> 4);
			chunks++;
			int h = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
			String biome = level.getBiome(new BlockPos(x, h, z)).unwrapKey().map(k -> k.identifier().toString()).orElse("?");
			String cave = level.getBiome(new BlockPos(x, Math.max(-50, h - 60), z)).unwrapKey().map(k -> k.identifier().toString()).orElse("?");
			PhaseWorld.LOG.info("[selftest] d={} x={} z={} surface={} layout={} biome={} cave={}", d, x, z, h,
				Math.round(layout.column(x, z).height), biome, cave);
		}
		long ms = (System.nanoTime() - t0) / 1_000_000;
		PhaseWorld.LOG.info("[selftest] OK: {} chunks in {} ms ({} ms/chunk)", chunks, ms, ms / Math.max(1, chunks));
		server.halt(false);
	}
}
