package gg.phase.cosmetics;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Сколько игрок стоит на месте: дракончик садится через 3 с и засыпает через 20 с. */
public final class PetMotion {
	private record Track(Vec3 pos, int still) {}
	private static final Map<Integer, Track> TRACKS = new HashMap<>();

	private PetMotion() {}

	static void tick(Minecraft client) {
		if (client.level == null) { TRACKS.clear(); return; }
		Map<Integer, Track> seen = new HashMap<>();
		for (Player p : client.level.players()) {
			Track old = TRACKS.get(p.getId());
			Vec3 pos = p.position();
			int still = old != null && old.pos().distanceToSqr(pos) < 0.0004 ? old.still() + 1 : 0;
			seen.put(p.getId(), new Track(pos, still));
		}
		TRACKS.clear();
		TRACKS.putAll(seen);
	}

	/** 0 — летает, 1 — сидит, 2 — лежит */
	public static int state(Player p) {
		if (!p.onGround() || p.isFallFlying() || p.isVisuallySwimming() || p.isPassenger()) return 0;
		Track t = TRACKS.get(p.getId());
		int still = t == null ? 0 : t.still();
		if (still >= 400) return 2;
		if (still >= 60 || p.isShiftKeyDown()) return 1;
		return 0;
	}
}
