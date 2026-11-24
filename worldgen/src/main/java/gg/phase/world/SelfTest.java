package gg.phase.world;

import gg.phase.world.gen.LayoutSeeds;
import gg.phase.world.gen.layout.WorldLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

/**
 * Development smoke test (-Dphaseworld.selftest=true, used by CI): generates a strip of chunks from
 * the top of Fuji out into the ocean, a block of land for timing, and stretches of rivers where it
 * looks for water that hangs in the air or leaks out of its bed. Logs everything, then stops the server.
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
		java.util.List<ChunkAccess> scan = new java.util.ArrayList<>();
		for (int d = 0; d <= 3200; d += 48) {
			int x = (int) layout.fujiX + d, z = (int) layout.fujiZ + d / 3;
			scan.add(level.getChunk(x >> 4, z >> 4));
			chunks++;
			int h = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
			String biome = level.getBiome(new BlockPos(x, h, z)).unwrapKey().map(k -> k.identifier().toString()).orElse("?");
			String cave = level.getBiome(new BlockPos(x, Math.max(-110, h - 60), z)).unwrapKey().map(k -> k.identifier().toString()).orElse("?");
			PhaseWorld.LOG.info("[selftest] d={} x={} z={} surface={} layout={} biome={} cave={}", d, x, z, h,
				Math.round(layout.column(x, z).height), biome, cave);
		}
		long ms = (System.nanoTime() - t0) / 1_000_000;
		PhaseWorld.LOG.info("[selftest] strip: {} chunks in {} ms ({} ms/chunk)", chunks, ms, ms / Math.max(1, chunks));

		// timing on a solid block of land (8x8 chunks next to spawn)
		t0 = System.nanoTime();
		int cx0 = layout.spawnX >> 4, cz0 = layout.spawnZ >> 4;
		for (int i = 0; i < 8; i++) for (int j = 0; j < 8; j++) level.getChunk(cx0 + 20 + i, cz0 + j);
		ms = (System.nanoTime() - t0) / 1_000_000;
		PhaseWorld.LOG.info("[selftest] land block: 64 chunks in {} ms ({} ms/chunk)", ms, ms / 64);

		// rivers: water must sit in its bed
		int checked = 0, floating = 0, leaks = 0, sources = 0;
		for (WorldLayout.River rv : layout.rivers) {
			for (int i = 2; i < rv.n && checked < 60; i += 9) {
				if (rv.level[i] < WorldLayout.SEA + 3) continue;
				int x = (int) rv.x[i], z = (int) rv.z[i];
				for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) level.getChunk((x >> 4) + a, (z >> 4) + b);
				ChunkAccess ch = level.getChunk(x >> 4, z >> 4);
				checked++;
				BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
				for (int dx = 0; dx < 16; dx++) {
					for (int dz = 0; dz < 16; dz++) {
						int wx = ch.getPos().getMinBlockX() + dx, wz = ch.getPos().getMinBlockZ() + dz;
						for (int y = (int) rv.level[i] - 12; y <= (int) rv.level[i] + 3; y++) {
							p.set(wx, y, wz);
							BlockState s = level.getBlockState(p);
							if (!s.getFluidState().is(Fluids.WATER) || !s.getFluidState().isSource()) continue;
							sources++;
							if (level.getBlockState(p.below()).isAir()) floating++;
							for (int k = 0; k < 4; k++) {
								BlockPos n = p.offset(k == 0 ? 1 : k == 1 ? -1 : 0, 0, k == 2 ? 1 : k == 3 ? -1 : 0);
								BlockState ns = level.getBlockState(n);
								if (ns.isAir() && Float.isNaN(layout.column(n.getX(), n.getZ()).water)
									&& !level.getBlockState(n.below()).getFluidState().is(Fluids.WATER)
									&& layout.column(n.getX(), n.getZ()).riverDist > 0.5f) {
									leaks++;
									break;
								}
							}
						}
					}
				}
			}
		}
		PhaseWorld.LOG.info("[selftest] rivers: {} chunks, {} water sources, {} floating, {} leaking sideways", checked, sources, floating, leaks);

		// generation bugs and other mods' blocks on the land block: anything that could not survive where it was
		// placed (a flower over air, a rock on leaves...) is a bug; blocks of other mods are counted per namespace
		java.util.Map<String, Integer> bad = new java.util.TreeMap<>(), mods = new java.util.TreeMap<>();
		BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
		int scanned = 0;
		for (int i = 0; i < 8; i++) for (int j = 0; j < 8; j++) scan.add(level.getChunk(cx0 + 20 + i, cz0 + j));
		for (ChunkAccess ch : scan) {
			for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) {
				int wx = ch.getPos().getMinBlockX() + dx, wz = ch.getPos().getMinBlockZ() + dz;
				int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz);
				for (int y = top - 10; y <= top + 2; y++) {
					q.set(wx, y, wz);
					BlockState s = level.getBlockState(q);
					if (s.isAir()) continue;
					scanned++;
					String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
					String ns = id.substring(0, id.indexOf(':'));
					if (!ns.equals("minecraft") && !ns.equals(PhaseWorld.MOD_ID)) mods.merge(ns, 1, Integer::sum);
					if (!s.canSurvive(level, q)) bad.merge(id, 1, Integer::sum);
				}
			}
		}
		PhaseWorld.LOG.info("[selftest] land scan: {} blocks, other mods {}, cannot survive {}", scanned, mods, bad);
		PhaseWorld.LOG.info("[selftest] OK");
		// halt() skips shutdown hooks, so write the CI profile (if one is running) ourselves
		try {
			for (jdk.jfr.Recording r : jdk.jfr.FlightRecorder.getFlightRecorder().getRecordings()) r.dump(java.nio.file.Path.of("worldgen.jfr"));
		} catch (Throwable t) {
			PhaseWorld.LOG.info("[selftest] no profile: {}", t.toString());
		}
		// the test world is throwaway: leave at once instead of saving hundreds of tall chunks
		Runtime.getRuntime().halt(0);
	}
}
