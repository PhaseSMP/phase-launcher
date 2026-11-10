package gg.phase.cosmetics;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/**
 * Концы шарфа «живут» сами: на пружине с затуханием. Остановился — по инерции качнулись вперёд;
 * разогнался — прижались к груди; падаешь — взлетают; прыгнул вверх — прижались; повернулся или пошёл
 * боком — отстают в сторону. Считается каждый тик для всех игроков в шарфе.
 */
public final class WearPhysics {
	private static final class St {
		float swing, swingO, swingV, sway, swayO, swayV, lastYaw;
		double lastFwd;
	}

	private static final Map<Integer, St> MAP = new HashMap<>();

	private WearPhysics() {}

	static void tick(Minecraft mc) {
		if (mc.level == null) { MAP.clear(); return; }
		if (mc.isPaused()) return;
		Set<Integer> alive = new HashSet<>();
		for (Player pl : mc.level.players()) {
			CosmeticsData.Worn w = CosmeticsData.get(pl.getName().getString());
			if (w == null || w.neck() == null) continue;
			alive.add(pl.getId());
			St s = MAP.computeIfAbsent(pl.getId(), k -> { St n = new St(); n.lastYaw = pl.yBodyRot; return n; });
			s.swingO = s.swing;
			s.swayO = s.sway;
			double dx = pl.getX() - pl.xo, dz = pl.getZ() - pl.zo, dy = pl.getY() - pl.yo;
			float by = pl.yBodyRot * Mth.DEG_TO_RAD;
			double fwd = -Mth.sin(by) * dx + Mth.cos(by) * dz;   // скорость вперёд, блоков за тик
			double side = Mth.cos(by) * dx + Mth.sin(by) * dz;
			double acc = fwd - s.lastFwd;
			s.lastFwd = fwd;
			float yawRate = Mth.wrapDegrees(pl.yBodyRot - s.lastYaw);
			s.lastYaw = pl.yBodyRot;
			float speed = (float) Math.sqrt(dx * dx + dz * dz);
			float age = pl.tickCount;
			// вперёд-назад: висят с небольшим отступом от груди
			float target = 4F + (float) Mth.clamp(-dy * 160.0, -6.0, 70.0) - (float) Mth.clamp(fwd * 60.0, 0.0, 10.0)
				+ Mth.sin(age * 0.9F) * Math.min(speed * 40F, 5F);
			s.swingV += (target - s.swing) * 0.16F;
			s.swingV += (float) Mth.clamp(-acc * 260.0, -12.0, 18.0); // резко остановился — концы летят вперёд
			s.swingV *= 0.8F;
			s.swing = Mth.clamp(s.swing + s.swingV, 0F, 85F);
			// в стороны: отстают при шаге вбок и при повороте, слегка колышутся
			float st = (float) Mth.clamp(-side * 140.0, -28.0, 28.0) + Mth.clamp(-yawRate * 0.8F, -25F, 25F) + Mth.sin(age * 0.07F) * 2F;
			s.swayV += (st - s.sway) * 0.14F;
			s.swayV *= 0.8F;
			s.sway = Mth.clamp(s.sway + s.swayV, -35F, 35F);
		}
		MAP.keySet().retainAll(alive);
	}

	/** Насколько концы отошли от груди, градусы */
	public static float swing(int id, float pt) {
		St s = MAP.get(id);
		return s == null ? 4F : Mth.lerp(pt, s.swingO, s.swing);
	}

	/** Насколько концы ушли в сторону, градусы */
	public static float sway(int id, float pt) {
		St s = MAP.get(id);
		return s == null ? 0F : Mth.lerp(pt, s.swayO, s.sway);
	}
}
