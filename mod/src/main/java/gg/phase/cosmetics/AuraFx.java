package gg.phase.cosmetics;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Ауры: частицы вокруг игрока, только на клиенте (серверу ничего не отправляется).
 * Сакура — лепестки кружат и медленно падают; Эндер — искры спиралью вверх; Пламя они — огоньки хороводом у ног.
 */
public final class AuraFx {
	private static final RandomSource RND = RandomSource.create();
	private static int age;

	private AuraFx() {}

	static void tick(Minecraft mc) {
		if (mc.level == null || mc.isPaused()) return;
		age++;
		Config cfg = Config.get();
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		for (Player p : mc.level.players()) {
			boolean own = p == mc.player;
			if (!own && !cfg.others) continue;
			if (own && !cfg.ownAura) continue;
			if (p.isInvisible() || p.isSpectator() || p.position().distanceToSqr(cam) > 40 * 40) continue;
			CosmeticsData.Worn w = CosmeticsData.get(p.getName().getString());
			if (w == null || w.aura() == null) continue;
			// от первого лица своя аура — реже и не перед глазами
			boolean fp = own && mc.options.getCameraType() == CameraType.FIRST_PERSON;
			spawn(mc, p, w.aura(), fp);
		}
	}

	private static void spawn(Minecraft mc, Player p, String aura, boolean firstPerson) {
		double x = Mth.lerp(0.5, p.xo, p.getX()), y = p.getY(), z = Mth.lerp(0.5, p.zo, p.getZ());
		boolean moving = (p.getX() - p.xo) * (p.getX() - p.xo) + (p.getZ() - p.zo) * (p.getZ() - p.zo) > 0.001;
		switch (aura) {
			case "aura_sakura" -> {
				if (age % (firstPerson ? 6 : 3) != 0) return;
				double a = RND.nextDouble() * Mth.TWO_PI, r = 0.7 + RND.nextDouble() * 0.4;
				mc.level.addParticle(ParticleTypes.CHERRY_LEAVES, x + Math.cos(a) * r, y + 1.4 + RND.nextDouble() * 1.0, z + Math.sin(a) * r, 0, 0, 0);
			}
			case "aura_ender" -> {
				if (firstPerson && age % 2 != 0) return;
				for (int k = 0; k < 2; k++) {
					double a = age * 0.32 + k * Math.PI, h = (age * 0.035 + k * 0.5) % 2.0;
					double r = 0.65 - h * 0.12;
					mc.level.addParticle(k == 0 ? ParticleTypes.REVERSE_PORTAL : ParticleTypes.WITCH, x + Math.cos(a) * r, y + 0.1 + h, z + Math.sin(a) * r, 0, 0.01, 0);
				}
				if (age % 9 == 0) mc.level.addParticle(ParticleTypes.END_ROD, x + (RND.nextDouble() - 0.5) * 0.8, y + 0.2 + RND.nextDouble() * 1.6, z + (RND.nextDouble() - 0.5) * 0.8, 0, 0.015, 0);
			}
			case "aura_oni" -> {
				if (age % (moving ? 2 : 1) != 0) return;
				int n = 3;
				for (int k = 0; k < n; k++) {
					double a = age * 0.12 + k * Mth.TWO_PI / n, r = 0.6;
					mc.level.addParticle(ParticleTypes.SMALL_FLAME, x + Math.cos(a) * r, y + 0.08 + Math.abs(Math.sin(age * 0.2 + k)) * 0.15, z + Math.sin(a) * r, 0, 0.012, 0);
				}
				if (age % 7 == 0) mc.level.addParticle(ParticleTypes.FLAME, x + (RND.nextDouble() - 0.5) * 0.9, y + 0.1, z + (RND.nextDouble() - 0.5) * 0.9, 0, 0.03, 0);
			}
			default -> { }
		}
	}
}
