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
			if (p.isInvisible() || p.isSpectator() || p.position().distanceToSqr(cam) > 40 * 40) continue;
			CosmeticsData.Worn w = CosmeticsData.get(p.getName().getString());
			if (w == null) continue;
			// от первого лица своя аура — реже и не перед глазами
			boolean fp = own && mc.options.getCameraType() == CameraType.FIRST_PERSON;
			if (w.aura() != null && (!own || cfg.ownAura)) spawn(mc, p, w.aura(), fp);
			if (w.trail() != null) trail(mc, p, w.trail(), fp);
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
			case "aura_limbo" -> {
				if (firstPerson && age % 2 != 0) return;
				// разлом Limbo: алое пламя у ног + фиолетовые искры по спирали вверх
				double a0 = age * 0.1;
				for (int k = 0; k < 2; k++) {
					double a = a0 + k * Math.PI;
					mc.level.addParticle(ParticleTypes.SMALL_FLAME, x + Math.cos(a) * 0.62, y + 0.06, z + Math.sin(a) * 0.62, 0, 0.01, 0);
				}
				double sp = age * 0.3, h = (age * 0.04) % 2.0;
				mc.level.addParticle(k(age) ? ParticleTypes.PORTAL : ParticleTypes.CRIMSON_SPORE, x + Math.cos(sp) * (0.6 - h * 0.1), y + 0.1 + h, z + Math.sin(sp) * (0.6 - h * 0.1), 0, 0.02, 0);
				if (age % 8 == 0) mc.level.addParticle(ParticleTypes.END_ROD, x + (RND.nextDouble() - 0.5) * 0.9, y + 0.1 + RND.nextDouble() * 1.8, z + (RND.nextDouble() - 0.5) * 0.9, 0, 0.02, 0);
				if (age % 18 == 0) mc.level.addParticle(ParticleTypes.FLAME, x + (RND.nextDouble() - 0.5) * 0.7, y + 0.08, z + (RND.nextDouble() - 0.5) * 0.7, 0, 0.02, 0);
			}
			default -> { }
		}
	}

	private static boolean k(int a) { return (a / 3) % 2 == 0; }

	/** Следы: частицы ложатся вдоль пройденного за тик отрезка, только пока игрок движется */
	private static void trail(Minecraft mc, Player p, String trail, boolean firstPerson) {
		double dx = p.getX() - p.xo, dz = p.getZ() - p.zo;
		double sp2 = dx * dx + dz * dz;
		if (sp2 < 0.0025) return; // стоит на месте — следа нет
		double sp = Math.sqrt(sp2);
		boolean fast = sp > 0.2; // бег или элитры — след гуще
		int n = firstPerson ? 1 : fast ? 3 : 2;
		double y = p.yo + 0.05;
		for (int i = 0; i < n; i++) {
			double t = RND.nextDouble();
			double bx = p.xo + dx * t - dx / sp * 0.35 + (RND.nextDouble() - 0.5) * 0.5;
			double bz = p.zo + dz * t - dz / sp * 0.35 + (RND.nextDouble() - 0.5) * 0.5;
			switch (trail) {
				case "trail_sakura" -> {
					mc.level.addParticle(ParticleTypes.CHERRY_LEAVES, bx, y + 0.2 + RND.nextDouble() * 0.3, bz, 0, 0, 0);
					if (i == 0 && age % 2 == 0) mc.level.addParticle(ParticleTypes.SPORE_BLOSSOM_AIR, bx, y + 0.1, bz, 0, 0.005, 0);
				}
				case "trail_ember" -> {
					mc.level.addParticle(ParticleTypes.SMALL_FLAME, bx, y, bz, 0, 0.006, 0);
					if (i == 0) mc.level.addParticle(ParticleTypes.FLAME, bx, y + 0.02, bz, 0, 0.012, 0);
					if (i == 0 && age % 3 == 0) mc.level.addParticle(ParticleTypes.ASH, bx, y + 0.2, bz, 0, 0, 0);
					if (i == 0 && age % 7 == 0) mc.level.addParticle(ParticleTypes.LAVA, bx, y + 0.05, bz, 0, 0, 0);
				}
				case "trail_frost" -> {
					mc.level.addParticle(ParticleTypes.SNOWFLAKE, bx, y + 0.1 + RND.nextDouble() * 0.2, bz, (RND.nextDouble() - 0.5) * 0.02, 0.008, (RND.nextDouble() - 0.5) * 0.02);
					if (i == 0) mc.level.addParticle(ParticleTypes.WHITE_ASH, bx, y + 0.25, bz, 0, 0, 0);
				}
				case "trail_stars" -> {
					mc.level.addParticle(i == 0 ? ParticleTypes.END_ROD : ParticleTypes.ELECTRIC_SPARK, bx, y + 0.15 + RND.nextDouble() * 0.4, bz, 0, 0.01, 0);
					if (i == 0 && (fast || age % 2 == 0)) mc.level.addParticle(ParticleTypes.GLOW, bx, y + 0.3, bz, 0, 0.005, 0);
				}
				default -> { return; }
			}
		}
	}
}
