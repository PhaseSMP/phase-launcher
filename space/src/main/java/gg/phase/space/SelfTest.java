package gg.phase.space;

import gg.phase.space.SolarSystem.Body;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Проверка для CI (-Dphasespace.selftest=true): есть ли мир космоса и миры всех планет, какая высота
 * поверхности на планетах, сколько астероидов в полосе через главный пояс и сколько стоит генерация.
 * Пишет всё в лог с меткой [selftest] и останавливает сервер.
 */
public final class SelfTest {
	private SelfTest() {}

	public static void run(MinecraftServer server) {
		ServerLevel space = server.getLevel(SpaceTravel.SPACE);
		PhaseSpace.LOG.info("[selftest] space level: {}", space == null ? "MISSING" : "ok, y " + space.getMinY() + ".." + (space.getMinY() + space.getHeight()));
		int missing = 0;
		for (Body b : SolarSystem.BODIES) {
			if (b.dim == null) continue;
			ServerLevel lvl = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(b.dim)));
			if (lvl == null) {
				missing++;
				PhaseSpace.LOG.info("[selftest] world {} MISSING", b.dim);
				continue;
			}
			lvl.getChunk(0, 0);
			int h = lvl.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0);
			String top = lvl.getBlockState(new BlockPos(0, h - 1, 0)).getBlock().toString();
			PhaseSpace.LOG.info("[selftest] world {}: surface y={} top={} height {}..{}", b.dim, h, top, lvl.getMinY(), lvl.getMinY() + lvl.getHeight());
		}
		PhaseSpace.LOG.info("[selftest] bodies at t=0: {}", SpaceTravel.describe(0));
		PhaseSpace.LOG.info("[selftest] bodies now: {}", SpaceTravel.describe(server.getLevel(Level.OVERWORLD).getGameTime()));
		if (space != null) {
			long t0 = System.nanoTime();
			int chunks = 0, rocks = 0, ores = 0, ice = 0, amethyst = 0;
			// полоса 8 чанков шириной поперёк пояса по оси +X (каждый второй столбец, ±500 от плоскости)
			for (int cx = (int) (SolarSystem.BELT_INNER - 64) >> 4; cx <= (int) (SolarSystem.BELT_OUTER + 64) >> 4; cx++) {
				for (int cz = -4; cz < 4; cz++) {
					ChunkAccess ch = space.getChunk(cx, cz);
					chunks++;
					BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
					for (int x = 0; x < 16; x += 2) for (int z = 0; z < 16; z += 2) for (int y = -500; y < 500; y += 1) {
						p.set(ch.getPos().getMinBlockX() + x, y, ch.getPos().getMinBlockZ() + z);
						BlockState s = ch.getBlockState(p);
						if (s.isAir()) continue;
						rocks++;
						String n = s.getBlock().toString();
						if (n.contains("_ore") || n.contains("raw_")) ores++;
						if (n.contains("ice") || n.contains("snow")) ice++;
						if (n.contains("amethyst")) amethyst++;
					}
				}
			}
			long ms = (System.nanoTime() - t0) / 1_000_000;
			PhaseSpace.LOG.info("[selftest] belt strip: {} chunks, {} blocks ({} ores, {} ice, {} amethyst) in {} ms", chunks, rocks, ores, ice, amethyst, ms);
			// у Солнца и на орбите Земли должно быть пусто
			int inner = 0;
			for (int cx = 60; cx < 68; cx++) for (int cz = -4; cz < 4; cz++) {
				ChunkAccess ch = space.getChunk(cx, cz);
				BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
				for (int x = 0; x < 16; x += 2) for (int z = 0; z < 16; z += 2) for (int y = -400; y < 400; y += 2) {
					p.set(ch.getPos().getMinBlockX() + x, y, ch.getPos().getMinBlockZ() + z);
					if (!ch.getBlockState(p).isAir()) inner++;
				}
			}
			PhaseSpace.LOG.info("[selftest] near the sun (r 960-1090): {} solid samples (expected 0)", inner);
		}
		// расписание событий на ближайшие сутки игры
		StringBuilder ev = new StringBuilder();
		for (long k = 0; k < 24; k++) {
			SpaceEvents.Event e = SpaceEvents.inSlot(k);
			ev.append(e == null ? "-" : e.type().name()).append(' ');
		}
		PhaseSpace.LOG.info("[selftest] events by 15-min slot: {}", ev);
		PhaseSpace.LOG.info("[selftest] {}", missing == 0 && space != null ? "OK" : "FAILED");
		Runtime.getRuntime().halt(0);
	}
}
